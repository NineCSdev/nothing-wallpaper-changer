package com.ninecsdev.wallpaperchanger.logic

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the in-memory rotation state (the "magazine") and the shuffle-cycle algorithm.
 * Picks the next image, delegates disk-buffer preparation to [BufferManager],
 * and self-heals by removing images that fail to load
 */
@Singleton
class RotationEngine @Inject constructor(
    private val dao: WallpaperDao,
    private val bufferManager: BufferManager,
    private val imageInternalizer: ImageInternalizer
) {
    private companion object {
        const val TAG = "RotationEngine"
    }

    private val imageMagazine = mutableListOf<WallpaperImage>()
    private var currentPointer = -1

    /**
     * Loads and shuffles the images from the active collection into RAM
     */
    suspend fun loadMagazine() {
        withContext(Dispatchers.IO) {
            val active = dao.getActiveCollection() ?: return@withContext
            val images = dao.getImagesForCollectionOnce(collectionId = active.id)

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
     */
    suspend fun refillDiskBuffer(): Boolean = withContext(Dispatchers.IO) {
        if (imageMagazine.isEmpty()) loadMagazine()

        val activeCollection = dao.getActiveCollection() ?: return@withContext false
        var result: Boolean? = null

        // Uses an iterative approach to avoid stack overflow from recursion
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

            val success = bufferManager.prepareNextWallpaper(
                nextImage,
                activeCollection.defaultCropRule
            )

            if (success) {
                result = true
                continue
            }

            // Cleanup
            Log.w(TAG, "Failed to load ${nextImage.uri}. Removing from rotation.")
            imageInternalizer.deleteInternalFile(nextImage.editedUri?.path)
            if (nextImage.uri.toString().contains("internal_wallpapers")) {
                imageInternalizer.deleteInternalFile(nextImage.uri.path)
            }
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
     * Clears the magazine from RAM. Called when the service stops or the active collection changes.
     */
    fun clearMagazine() {
        synchronized(imageMagazine) {
            imageMagazine.clear()
            currentPointer = -1
        }
    }
}
