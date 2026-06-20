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
        const val MAX_FAILURES_BEFORE_PURGE = 2
    }

    private val imageMagazine = mutableListOf<WallpaperImage>()
    private var currentPointer = -1
    private val failureCounts = mutableMapOf<Long, Int>()

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
                failureCounts.clear()
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
        val maxAttempts = synchronized(imageMagazine) { imageMagazine.size }
        if (maxAttempts == 0) return@withContext false

        // Added multiple tries before deleting for being more permissive in case there was an issue
        // and the file wasn't actually deleted, not sure if it is better than trying once for UX so may
        // revert in the future
        for (attempt in 0 until maxAttempts) {
            val nextImage = synchronized(imageMagazine) {
                if (imageMagazine.isEmpty()) {
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
            } ?: break

            val preparation = bufferManager.prepareNextWallpaper(
                nextImage,
                activeCollection.defaultCropRule
            )

            when (preparation) {
                is BufferPreparationResult.Success -> {
                    if (preparation.source == BufferSource.ORIGINAL && nextImage.editedUri != null) {
                        Log.w(TAG, "Edited wallpaper failed; reverting to original for ${nextImage.id}.")
                        imageInternalizer.deleteInternalFile(nextImage.editedUri.path)
                        dao.updateWallpaperEdit(nextImage.id, null, null, null, null)
                    }
                    failureCounts.remove(nextImage.id)
                    return@withContext true
                }
                BufferPreparationResult.Failure -> {
                    val failures = (failureCounts[nextImage.id] ?: 0) + 1
                    if (failures < MAX_FAILURES_BEFORE_PURGE) {
                        failureCounts[nextImage.id] = failures
                        Log.w(TAG, "Failed to load ${nextImage.uri}. Will retry later ($failures/$MAX_FAILURES_BEFORE_PURGE).")
                    } else {
                        failureCounts.remove(nextImage.id)
                        Log.w(TAG, "Failed to load ${nextImage.uri}. Removing after $failures failures.")
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
                }
            }
        }

        false
    }

    /**
     * Clears the magazine from RAM. Called when the service stops or the active collection changes.
     */
    fun clearMagazine() {
        synchronized(imageMagazine) {
            imageMagazine.clear()
            currentPointer = -1
            failureCounts.clear()
        }
    }
}
