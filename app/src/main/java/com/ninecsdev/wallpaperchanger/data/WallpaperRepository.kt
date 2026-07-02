package com.ninecsdev.wallpaperchanger.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the data layer.
 *
 * Responsible for collection and wallpaper image CRUD,
 * folder scanning, and rotation-engine coordination.
 * Service state is managed by [ServiceStateManager] and settings
 * by [com.ninecsdev.wallpaperchanger.data.local.AppDataStore],
 * both injected directly by consumers that need them.
 */
@Singleton
class WallpaperRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val dao: WallpaperDao,
    private val rotationEngine: RotationEngine,
    private val imageInternalizer: ImageInternalizer,
    private val serviceStateManager: ServiceStateManager
) {
    private companion object {
        const val TAG = "WallpaperRepository"
    }

    // UI Data Access (Flows)

    fun getAllCollections(): Flow<List<WallpaperCollection>> = dao.getAllCollections()

    fun getImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>> =
        dao.getImagesForCollection(collectionId)

    // Collection operations

    suspend fun updateCollection(
        id: Long,
        newName: String,
        newRule: CropRule,
        newFrequency: RotationFrequency
    ) {
        withContext(Dispatchers.IO) {
            dao.updateCollection(id, newName, newRule, newFrequency)
            refreshEngineIfActive(id)
        }
    }

    suspend fun createFolderCollection(name: String, treeUri: Uri, rule: CropRule) {
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
            refreshEngineIfActive(collectionId)
        }
    }

    suspend fun getCollectionById(collectionId: Long): WallpaperCollection? =
        dao.getCollectionById(collectionId)

    /**
     * Non-flow version of getActiveCollection() for use in background tasks.
     */
    suspend fun getActiveCollectionOnce(): WallpaperCollection? = dao.getActiveCollection()

    /**
     * Non-flow version of getImagesForCollection for background tasks.
     */
    suspend fun getImagesForCollectionOnce(collectionId: Long): List<WallpaperImage> =
        dao.getImagesForCollectionOnce(collectionId)

    /**
     * Returns the image count of a collection in a non-flow way.
     */
    suspend fun getSizeOfCollection(collectionId: Long): Int =
        dao.getImageCountOfCollection(collectionId)

    /**
     * Sets the active collection and auto-syncs if it is a folder type.
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

    suspend fun markWallpaperChanged(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.updateLastWallpaperChangeAt(collectionId)
        }
    }

    /**
     * Syncs a folder collection with its physical directory.
     * Uses diff-based approach: removes stale images, adds new ones,
     * preserves manually added images.
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

    /**
     * Deletes specific wallpaper images and cleans up their internal files.
     * PRE: All given wallpaper images are from the same collection
     */
    suspend fun deleteImagesFromCollection(images: List<WallpaperImage>) {
        if (images.isEmpty()) return

        withContext(Dispatchers.IO) {
            val collectionId = images.first().collectionId

            images.forEach { image ->
                imageInternalizer.deleteInternalFile(image.uri.path)
            }

            dao.deleteImagesByIds(images.map { it.id })
            refreshEngineIfActive(collectionId)
        }
    }

    /**
     * Deletes a collection and cleans up associated files and permissions.
     * If the deleted collection was active, clears the rotation magazine and
     * marks the service as stopped via [ServiceStateManager].
     */
    suspend fun deleteCollection(collection: WallpaperCollection) {
        withContext(Dispatchers.IO) {
            if (collection.isActive) {
                rotationEngine.clearMagazine()
                serviceStateManager.markServiceStopped()
            }

            // Clean up internal files for manual collections and manually-added folder images.
            val images = dao.getImagesForCollectionOnce(collection.id)
            images.forEach { image ->
                if (collection.type == CollectionType.MANUAL || image.isManuallyAdded) {
                    imageInternalizer.deleteInternalFile(image.uri.path)
                }
            }

            // Release the persisted folder permission if this is a folder collection
            if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
                releaseFolderUriPermission(collection.rootUri)
            }

            dao.deleteCollection(collection)
        }
    }

    /**
     * Releases a persisted folder-tree URI permission previously taken via
     * `takePersistableUriPermission`. Safe to call even if already released.
     */
    fun releaseFolderUriPermission(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission already released for: $uri", e)
        }
    }

    // TODO(v0.3.3): temporary cleanup, remove this method and its call site in
    // MainViewModel's init block once installs have had a chance to run it.
    // It exists only to release folder-URI grants leaked by the pre-fix
    // "cancel create modal" bug.
    /**
     * Releases any persisted folder-tree URI grants that no longer belong to
     * an existing FOLDER collection. Safe to call repeatedly.
     */
    suspend fun releaseOrphanedFolderUriPermissions() {
        withContext(Dispatchers.IO) {
            val activeRootUris = dao.getAllCollections().first()
                .mapNotNull { it.rootUri }
                .toSet()

            appContext.contentResolver.persistedUriPermissions
                .map { it.uri }
                .filter { it !in activeRootUris }
                .forEach { releaseFolderUriPermission(it) }
        }
    }

    // Wallpaper operations

    /** Fetches a single wallpaper */
    suspend fun getWallpaperById(wallpaperId: Long): WallpaperImage? =
        dao.getWallpaperById(wallpaperId)

    /**
     * Saves the edit parameters for a wallpaper.
     * The edit (zoom/offset) is applied on-the-fly during rotation — no file is written to disk.
     * If this wallpaper belongs to the active collection, reloads the rotation engine
     * and refills the disk buffer so the change is immediately respected.
     */
    suspend fun saveWallpaperEdit(
        wallpaper: WallpaperImage,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        withContext(Dispatchers.IO) {
            dao.updateWallpaperEdit(wallpaper.id, zoom, offsetX, offsetY)
            refreshEngineIfActive(wallpaper.collectionId)
        }
    }

    /**
     * Resets a wallpaper edit: clears all edit parameters.
     */
    suspend fun resetWallpaperEdit(wallpaper: WallpaperImage) {
        withContext(Dispatchers.IO) {
            dao.updateWallpaperEdit(wallpaper.id, null, null, null)
            refreshEngineIfActive(wallpaper.collectionId)
        }
    }

    // Helpers

    /**
     * Refreshes the rotation engine if the changed collection is currently active.
     */
    private suspend fun refreshEngineIfActive(collectionId: Long) {
        val activeCollection = dao.getActiveCollection()

        if (activeCollection?.id == collectionId) {
            Log.d(TAG, "Refreshing rotation engine for active collection: ${activeCollection.name}")
            rotationEngine.loadMagazine()
            rotationEngine.refillDiskBuffer()
        }
    }

    // File System Utilities
    // TODO: Move folder scanning to a dedicated FolderScanner collaborator
    /**
     * Scans ONLY (no subfolders)  the user-selected folder for images.
     * @param rootFolderUri The top-level folder URI granted by the user.
     * @return A list of [WallpaperImage] objects with collectionId = 0 (caller must copy).
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
                    val mimeTypeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

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
