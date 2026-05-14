package com.ninecsdev.wallpaperchanger.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.model.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.CollectionType
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.LockscreenZoomFix
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.service.WallpaperService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Coordinator of the Data Layer.
 * Orchestrates Room Database, System States, and Preferences.
 */
@Singleton
class WallpaperRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val dao: WallpaperDao,
    private val appDataStore: AppDataStore,
    private val rotationEngine: RotationEngine,
    private val imageInternalizer: ImageInternalizer
) {
    private companion object {
        const val TAG = "WallpaperRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _serviceEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val serviceEvent: SharedFlow<Unit> = _serviceEvent.asSharedFlow()

    private val _serviceStateFlow = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceStateFlow: StateFlow<ServiceState> = _serviceStateFlow.asStateFlow()

    private val _defaultWallpaperUriFlow = MutableStateFlow<Uri?>(null)
    val defaultWallpaperUriFlow: StateFlow<Uri?> = _defaultWallpaperUriFlow.asStateFlow()

    private val _revertToDefaultFlow = MutableStateFlow(true)
    val revertToDefaultFlow: StateFlow<Boolean> = _revertToDefaultFlow.asStateFlow()

    init {
        _serviceStateFlow.value = ServiceState.Stopped

        scope.launch {
            _defaultWallpaperUriFlow.value = appDataStore.getDefaultWallpaperUri()
            _revertToDefaultFlow.value = appDataStore.shouldRevertToDefault()
        }
    }

    /**
     * Emits a signal to all UI consumers that service state has changed.
     * Replaces broadcast-based UI sync for MainActivity and TileService.
     */
    fun notifyServiceStateChanged() {
        _serviceEvent.tryEmit(Unit)
    }

    // UI Data Access (Flows)
    fun getAllCollections(): Flow<List<WallpaperCollection>> {
        return dao.getAllCollections()
    }

    fun getImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>> {
        return dao.getImagesForCollection(collectionId)
    }

    suspend fun getCollectionById(collectionId: Long): WallpaperCollection? {
        return dao.getCollectionById(collectionId)
    }

    // Collection Management

    suspend fun importFolderAsCollection(name: String, treeUri: Uri, rule: CropRule) {
        withContext(Dispatchers.IO) {
            val isFirst = dao.getActiveCollection() == null

            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.FOLDER,
                    rootUri = treeUri,
                    isActive = isFirst,
                    defaultCropRule = rule
                )
            )

            val images = getImageListFromFolder(treeUri).map {
                it.copy(collectionId = collectionId)
            }
            dao.insertImages(images)
            Log.d(TAG, "Imported ${images.size} images to collection: $name")
        }
    }

    suspend fun createManualCollection(name: String, uris: List<Uri>, rule: CropRule) {
        withContext(Dispatchers.IO) {
            val isFirst = dao.getActiveCollection() == null
            val internalizedUris = imageInternalizer.internalizeImages(appContext, uris)

            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.MANUAL,
                    rootUri = null,
                    isActive = isFirst,
                    defaultCropRule = rule
                )
            )

            val images = internalizedUris.map {
                WallpaperImage(collectionId = collectionId, uri = it)
            }

            dao.insertImages(images)
        }
    }

    /**
     * Adds wallpapers to an existing collection.
     * For FOLDER collections the new images are marked [WallpaperImage.isManuallyAdded]
     * so they survive folder-sync diffs.
     */
    suspend fun addImagesToCollection(collectionId: Long, uris: List<Uri>) {
        withContext(Dispatchers.IO) {
            val collection = dao.getCollectionById(collectionId) ?: return@withContext
            val internalizedUris = imageInternalizer.internalizeImages(appContext, uris)

            val isFolder = collection.type == CollectionType.FOLDER
            val images = internalizedUris.map {
                WallpaperImage(
                    collectionId = collectionId,
                    uri = it,
                    isManuallyAdded = isFolder
                )
            }
            dao.insertImages(images)

            // Reload rotation engine if this is the active collection
            if (collection.isActive) {
                rotationEngine.loadMagazine()
                rotationEngine.refillDiskBuffer()
            }
        }
    }

    /**
     * Deletes specific wallpaper images and cleans up their internal files.
     */
    suspend fun deleteImagesById(images: List<WallpaperImage>) {
        withContext(Dispatchers.IO) {
            images.forEach { image ->
                imageInternalizer.deleteInternalFile(image.uri.path)
                imageInternalizer.deleteInternalFile(image.editedUri?.path)
            }
            dao.deleteImagesByIds(images.map { it.id })
        }
    }

    /**
     *  If the collection is a folder type it auto syncs
     */
    suspend fun setActiveCollection(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.setActiveCollection(collectionId)
            val collection = dao.getCollectionById(collectionId)
            if (collection?.type == CollectionType.FOLDER) {
                Log.d(TAG, "Auto-syncing folder collection: ${collection.name}")
                syncCollection(collectionId)
            }
            rotationEngine.clearMagazine()
            rotationEngine.loadMagazine()
            rotationEngine.refillDiskBuffer()
        }
    }

    /**
     * Non-flow version of getActiveCollection() for use in background tasks
     * like the CacheManager or Service.
     */
    suspend fun getActiveCollectionOnce(): WallpaperCollection? {
        return dao.getActiveCollection()
    }

    /**
     * Non-flow version of getImagesForCollection for background tasks.
     */
    suspend fun getImagesForCollectionOnce(collectionId: Long): List<WallpaperImage> {
        return dao.getImagesForCollectionOnce(collectionId)
    }

    /**
     *  Return the size of a collection in a non-flow way.
     */
    suspend fun getSizeOfCollection(collectionId: Long): Int {
        return dao.getImageCountOfCollection(collectionId)
    }

    // Editor Operations

    /** Fetches a single wallpaper for the editor. */
    suspend fun getWallpaperById(wallpaperId: Long): WallpaperImage? {
        return dao.getWallpaperById(wallpaperId)
    }

    /**
     * Saves the edited wallpaper image and its edit parameters.
     * Deletes the previous edited file if one existed.
     * If this wallpaper belongs to the active collection, reloads the rotation engine
     * and refills the disk buffer so the change is immediately respected.
     */
    suspend fun saveWallpaperEdit(
        wallpaper: WallpaperImage,
        editedUri: Uri,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        withContext(Dispatchers.IO) {
            imageInternalizer.deleteInternalFile(wallpaper.editedUri?.path)
            dao.updateWallpaperEdit(wallpaper.id, editedUri.toString(), zoom, offsetX, offsetY)

            val active = dao.getActiveCollection()
            if (active?.id == wallpaper.collectionId) {
                rotationEngine.loadMagazine()
                rotationEngine.refillDiskBuffer()
            }
        }
    }

    /**
     * Resets a wallpaper edit: removes the edited file and clears all edit parameters.
     * Falls back to the original URI for display and rotation.
     */
    suspend fun resetWallpaperEdit(wallpaper: WallpaperImage) {
        withContext(Dispatchers.IO) {
            imageInternalizer.deleteInternalFile(wallpaper.editedUri?.path)
            dao.updateWallpaperEdit(wallpaper.id, null, null, null, null)

            val active = dao.getActiveCollection()
            if (active?.id == wallpaper.collectionId) {
                rotationEngine.loadMagazine()
                rotationEngine.refillDiskBuffer()
            }
        }
    }

    suspend fun updateCollection(
        id: Long,
        newName: String,
        newRule: CropRule,
        newFrequency: RotationFrequency
    ) {
        withContext(Dispatchers.IO) {
            dao.updateCollection(id, newName, newRule, newFrequency)
            if (dao.getActiveCollection()?.id == id) {
                rotationEngine.refillDiskBuffer()
            }
        }
    }

    suspend fun markWallpaperChanged(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.updateLastWallpaperChangeAt(collectionId)
        }
    }

    suspend fun deleteCollection(collection: WallpaperCollection) {
        withContext(Dispatchers.IO) {
            if (collection.isActive) {
                rotationEngine.clearMagazine()
                markServiceStopped()
            }

            // Clean up internal files for manual collections and manually-added folder images.
            val images = dao.getImagesForCollectionOnce(collection.id)
            images.forEach { image ->
                if (collection.type == CollectionType.MANUAL || image.isManuallyAdded) {
                    imageInternalizer.deleteInternalFile(image.uri.path)
                }
                imageInternalizer.deleteInternalFile(image.editedUri?.path)
            }

            // Release the persisted folder permission if this is a folder collection
            if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
                try {
                    appContext.contentResolver.releasePersistableUriPermission(
                        collection.rootUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Permission already released for: ${collection.rootUri}", e)
                }
            }

            dao.deleteCollection(collection)
        }
    }

    /**
     * Syncs a folder collection with its physical directory.
     * Uses diff-based approach: removes stale images, adds new ones, preserves manually added images.
     */
    suspend fun syncCollection(collectionId: Long) {
        withContext(Dispatchers.IO) {
            val collection = dao.getCollectionById(collectionId) ?: return@withContext

            if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
                try {
                    Log.d(TAG, "Syncing physical folder for collection: ${collection.name}")

                    val freshImages = getImageListFromFolder(collection.rootUri).map {
                        it.copy(collectionId = collectionId)
                    }

                    val added = dao.syncFolderImages(collectionId, freshImages)
                    Log.d(TAG, "Sync complete: ${freshImages.size} on disk, $added new images added.")

                    if (collection.isActive) {
                        rotationEngine.loadMagazine()
                        Log.i(TAG, "Active collection synced. Magazine reloaded.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed for collection ${collection.id}: ${e.message}")
                }
            }
        }
    }


    // Service status & Preferences

    private fun updateServiceState(state: ServiceState) {
        if (_serviceStateFlow.value == state) return

        _serviceStateFlow.value = state
        notifyServiceStateChanged()
    }

    private fun persistServiceRunningState(isRunning: Boolean) {
        scope.launch { appDataStore.setServiceRunning(isRunning) }
    }

    private fun resolveStoppedVisualState(isPowerSave: Boolean): ServiceState {
        return if (isPowerSave) ServiceState.DisabledPowerSave else ServiceState.Stopped
    }

    fun markServiceLoading() {
        updateServiceState(ServiceState.Loading)
    }

    fun markServiceRunning() {
        persistServiceRunningState(true)
        updateServiceState(ServiceState.Running)
    }

    fun markServicePaused() {
        persistServiceRunningState(true)
        updateServiceState(ServiceState.Paused)
    }

    fun markServiceStopped() {
        persistServiceRunningState(false)
        updateServiceState(ServiceState.Stopped)
    }

    suspend fun getServiceState(): ServiceState {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode ?: false
        if (getActiveCollectionOnce() == null) return ServiceState.DisabledNoCollection

        val currentState = _serviceStateFlow.value
        val isPersistedRunning = appDataStore.isServiceRunning()
        val isServiceAlive = WallpaperService.isAlive
        val isServiceMarkedActive = isServiceAlive || isPersistedRunning
        val stoppedState = resolveStoppedVisualState(isPowerSave)

        return when {
            currentState is ServiceState.Loading -> ServiceState.Loading
            currentState is ServiceState.Running || currentState is ServiceState.Paused -> {
                if (isServiceMarkedActive) {
                    currentState
                } else {
                    persistServiceRunningState(false)
                    stoppedState
                }
            }
            currentState is ServiceState.Stopped -> {
                if (isPersistedRunning && !isServiceAlive) {
                    persistServiceRunningState(false)
                }
                stoppedState
            }
            isServiceMarkedActive -> ServiceState.Running
            else -> stoppedState
        }
    }

    suspend fun getDefaultWallpaperUri(): Uri? = appDataStore.getDefaultWallpaperUri()
    suspend fun shouldRevertToDefault(): Boolean = appDataStore.shouldRevertToDefault()

    // Passthroughs to Datastore
    fun setRevertToDefault(revert: Boolean) {
        _revertToDefaultFlow.value = revert
        scope.launch { appDataStore.setRevertToDefault(revert) }
    }

    fun saveDefaultWallpaperUri(uri: Uri) {
        _defaultWallpaperUriFlow.value = uri
        scope.launch { appDataStore.saveDefaultWallpaperUri(uri) }
    }

    suspend fun isServiceRunning(): Boolean = appDataStore.isServiceRunning()

    suspend fun shouldStartOnBoot(): Boolean = appDataStore.shouldStartOnBoot()
    fun setStartOnBoot(enabled: Boolean) {
        scope.launch { appDataStore.setStartOnBoot(enabled) }
    }

    suspend fun getScreenOffDelay(): Long = appDataStore.getScreenOffDelay()
    fun setScreenOffDelay(delayMs: Long) {
        scope.launch { appDataStore.setScreenOffDelay(delayMs) }
    }

    suspend fun getCompressionQualityHigh(): Int = appDataStore.getCompressionQualityHigh()
    fun setCompressionQualityHigh(quality: Int) {
        scope.launch { appDataStore.setCompressionQualityHigh(quality) }
    }

    suspend fun getCompressionQualityLow(): Int = appDataStore.getCompressionQualityLow()
    fun setCompressionQualityLow(quality: Int) {
        scope.launch { appDataStore.setCompressionQualityLow(quality) }
    }

    suspend fun getBatterySaverPolicy(): BatterySaverPolicy = appDataStore.getBatterySaverPolicy()
    fun setBatterySaverPolicy(policy: BatterySaverPolicy) {
        scope.launch { appDataStore.setBatterySaverPolicy(policy) }
    }

    suspend fun getLockscreenZoomFix(): LockscreenZoomFix = appDataStore.getLockscreenZoomFix()
    fun setLockscreenZoomFix(zoomFix: LockscreenZoomFix) {
        scope.launch { appDataStore.setLockscreenZoomFix(zoomFix) }
    }


    // File System Utilities
    // TODO: Move the file system utility to a separate class in the future.

    /**
     * Scans the user-selected folder for images.
     * @param rootFolderUri The top-level folder URI granted by the user.
     * @return A list of [WallpaperImage] objects.
     */
    private suspend fun getImageListFromFolder(rootFolderUri: Uri): List<WallpaperImage> {
        return withContext(Dispatchers.IO) {
            val imageList = mutableListOf<WallpaperImage>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootFolderUri, DocumentsContract.getTreeDocumentId(rootFolderUri)
            )

            try {
                appContext.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeTypeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) // Get the column index

                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeTypeCol)

                        if (mimeType != null && mimeType.startsWith("image/")) {
                            val docId = cursor.getString(idCol)
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootFolderUri, docId)
                            imageList.add(WallpaperImage(collectionId = 0, uri = docUri))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Folder scan failed: $e")
            }
            Log.d(TAG, "Folder scan found ${imageList.size} valid images.")
            imageList
        }
    }
}
