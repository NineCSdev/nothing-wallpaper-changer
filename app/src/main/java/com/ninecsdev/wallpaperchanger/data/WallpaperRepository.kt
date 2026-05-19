package com.ninecsdev.wallpaperchanger.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.model.CollectionType
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the data layer.
 *
 * Responsible exclusively for collection and wallpaper image CRUD,
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

    suspend fun getCollectionById(collectionId: Long): WallpaperCollection? =
        dao.getCollectionById(collectionId)

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
            val activeCollectionId = dao.getActiveCollection()?.id
            val affectsActive = activeCollectionId != null && images.any { it.collectionId == activeCollectionId }

            images.forEach { image ->
                imageInternalizer.deleteInternalFile(image.uri.path)
                imageInternalizer.deleteInternalFile(image.editedUri?.path)
            }
            dao.deleteImagesByIds(images.map { it.id })

            if (affectsActive) {
                rotationEngine.loadMagazine()
                rotationEngine.refillDiskBuffer()
            }
        }
    }

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

    // Editor Operations

    /** Fetches a single wallpaper for the editor. */
    suspend fun getWallpaperById(wallpaperId: Long): WallpaperImage? =
        dao.getWallpaperById(wallpaperId)

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

    // File System Utilities
    // TODO: Move folder scanning to a dedicated FolderScanner collaborator

    /**
     * Scans the user-selected folder for images.
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
