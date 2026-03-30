package com.ninecsdev.wallpaperchanger.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDatabase
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.CollectionType
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.service.WallpaperService
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

/**
 * The Coordinator of the Data Layer.
 * Orchestrates Room Database, System States, and the Exhaustive Rotation Engine.
 */
object WallpaperRepository {
    private const val TAG = "WallpaperRepository"

    private lateinit var appContext: Context
    private lateinit var dao: WallpaperDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _serviceEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val serviceEvent: SharedFlow<Unit> = _serviceEvent.asSharedFlow()

    private val _serviceStateFlow = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceStateFlow: StateFlow<ServiceState> = _serviceStateFlow.asStateFlow()

    private val _defaultWallpaperUriFlow = MutableStateFlow<Uri?>(null)
    val defaultWallpaperUriFlow: StateFlow<Uri?> = _defaultWallpaperUriFlow.asStateFlow()

    private val _revertToDefaultFlow = MutableStateFlow(true)
    val revertToDefaultFlow: StateFlow<Boolean> = _revertToDefaultFlow.asStateFlow()

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            dao = AppDatabase.getDatabase(appContext).wallpaperDao()
            _serviceStateFlow.value = ServiceState.Stopped

            scope.launch {
                _defaultWallpaperUriFlow.value = AppDataStore.getDefaultWallpaperUri(appContext)
                _revertToDefaultFlow.value = AppDataStore.shouldRevertToDefault(appContext)
            }
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

            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.MANUAL,
                    rootUri = null,
                    isActive = isFirst,
                    defaultCropRule = rule
                )
            )

            val images = uris.map {
                WallpaperImage(collectionId = collectionId, uri = it)
            }

            dao.insertImages(images)
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
            clearMagazine()
            loadMagazine()
            refillDiskBuffer()
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

    suspend fun updateCollection(
        id: Long,
        newName: String,
        newRule: CropRule,
        newFrequency: RotationFrequency
    ) {
        withContext(Dispatchers.IO) {
            dao.updateCollection(id, newName, newRule, newFrequency)
            if (dao.getActiveCollection()?.id == id) {
                refillDiskBuffer()
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
                clearMagazine()
                markServiceStopped()
            }

            // Clean up internal files for all images in the manual collection
            val images = dao.getImagesForCollectionOnce(collection.id)
            images.forEach { image ->
                if (collection.type == CollectionType.MANUAL) {
                    ImageInternalizer.deleteInternalFile(image.uri.path)
                }
                ImageInternalizer.deleteInternalFile(image.editedUri?.path)
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
                        loadMagazine()
                        Log.i(TAG, "Active collection synced. Magazine reloaded.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed for collection ${collection.id}: ${e.message}")
                }
            }
        }
    }

    // Rotation Logic and vals
    // TODO: Move the rotation logic to a separate class in the future.
    private val imageMagazine = mutableListOf<WallpaperImage>()
    private var currentPointer = -1

    /**
     * Loads and shuffles the images from the active collection into RAM.
     */
    suspend fun loadMagazine() {
        withContext(Dispatchers.IO) {
            val active = getActiveCollectionOnce() ?: return@withContext
            val images = getImagesForCollectionOnce(collectionId = active.id)

            synchronized(imageMagazine) {
                imageMagazine.clear()
                imageMagazine.addAll(images.shuffled())
                currentPointer = -1
            }
            Log.d(TAG, "Magazine loaded: ${imageMagazine.size} items.")
        }
    }

    /**
     * Processes the next wallpapers while only showing each once before shuffling.
     * It self-heals if an image fails to load.
     * Uses an iterative approach to avoid stack overflow from recursion.
     */
    suspend fun refillDiskBuffer(): Boolean = withContext(Dispatchers.IO) {
        if (imageMagazine.isEmpty()) loadMagazine()

        val activeCollection = getActiveCollectionOnce() ?: return@withContext false
        var result: Boolean? = null

        while (result == null) {
            val nextImage = synchronized(imageMagazine) {
                if (imageMagazine.isEmpty()) {
                    result = false
                    null
                } else {
                    currentPointer++

                    if (currentPointer >= imageMagazine.size) {
                        Log.d(TAG, "Cycle complete. Reshuffling for new sequence.")
                        imageMagazine.shuffle()
                        currentPointer = 0
                    }

                    imageMagazine[currentPointer]
                }
            } ?: continue

            val success = BufferManager.prepareNextWallpaper(
                nextImage,
                activeCollection.defaultCropRule
            )

            if (success) {
                result = true
                continue
            }

            Log.w(TAG, "Failed to load ${nextImage.uri}. Removing from rotation.")
            ImageInternalizer.deleteInternalFile(nextImage.editedUri?.path)
            dao.deleteImageById(nextImage.id)

            synchronized(imageMagazine) {
                imageMagazine.remove(nextImage)
                if (imageMagazine.isEmpty()) {
                    currentPointer = -1
                } else if (currentPointer >= imageMagazine.size) {
                    currentPointer = imageMagazine.lastIndex
                }
            }
        }

        result ?: false
    }

    /**
     * Helper to clear RAM when service stops
     */
    fun clearMagazine() {
        synchronized(imageMagazine) {
            imageMagazine.clear()
            currentPointer = -1
        }
    }

    // Service status & Preferences

    private fun updateServiceState(state: ServiceState) {
        if (_serviceStateFlow.value != state) {
            _serviceStateFlow.value = state
        }
        notifyServiceStateChanged()
    }

    fun markServiceLoading() {
        updateServiceState(ServiceState.Loading)
    }

    fun markServiceRunning() {
        scope.launch { AppDataStore.setServiceRunning(appContext, true) }
        updateServiceState(ServiceState.Running)
    }

    fun markServicePaused() {
        scope.launch { AppDataStore.setServiceRunning(appContext, true) }
        updateServiceState(ServiceState.Paused)
    }

    fun markServiceStopped() {
        scope.launch { AppDataStore.setServiceRunning(appContext, false) }
        updateServiceState(ServiceState.Stopped)
    }

    suspend fun getServiceState(): ServiceState {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode ?: false
        val activeCollection = getActiveCollectionOnce()
        val currentState = _serviceStateFlow.value
        val isRunning = AppDataStore.isServiceRunning(appContext)

        return when {
            activeCollection == null -> ServiceState.DisabledNoCollection
            currentState is ServiceState.Loading -> ServiceState.Loading
            currentState is ServiceState.Paused -> ServiceState.Paused
            isRunning && WallpaperService.isAlive -> ServiceState.Running
            isRunning -> {
                markServiceStopped()
                ServiceState.Stopped
            }
            isPowerSave -> ServiceState.DisabledPowerSave
            else -> ServiceState.Stopped
        }
    }

    suspend fun getDefaultWallpaperUri(): Uri? = AppDataStore.getDefaultWallpaperUri(appContext)
    suspend fun shouldRevertToDefault(): Boolean = AppDataStore.shouldRevertToDefault(appContext)

    // Passthroughs to Preferences
    fun setRevertToDefault(revert: Boolean) {
        _revertToDefaultFlow.value = revert
        scope.launch { AppDataStore.setRevertToDefault(appContext, revert) }
    }

    fun saveDefaultWallpaperUri(uri: Uri) {
        _defaultWallpaperUriFlow.value = uri
        scope.launch { AppDataStore.saveDefaultWallpaperUri(appContext, uri) }
    }

    fun setServiceRunning(isRunning: Boolean) {
        if (isRunning) {
            markServiceRunning()
        } else {
            markServiceStopped()
        }
    }

    suspend fun isServiceRunning(): Boolean = AppDataStore.isServiceRunning(appContext)
    suspend fun shouldStartOnBoot(): Boolean = AppDataStore.shouldStartOnBoot(appContext)
    fun setStartOnBoot(enabled: Boolean) {
        scope.launch { AppDataStore.setStartOnBoot(appContext, enabled) }
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
