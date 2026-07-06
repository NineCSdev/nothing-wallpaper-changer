package com.ninecsdev.wallpaperchanger.logic

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the in-memory rotation state (the "magazine") and the shuffle-cycle algorithm.
 * Picks the next image, delegates disk-buffer preparation to [BufferManager],
 * and self-heals by removing images that fail to load.
 *
 * The magazine is kept in sync **reactively**: [start] subscribes to
 * [WallpaperRepository.activeCollectionImagesFlow] and rebuilds the magazine whenever the active
 * collection or its images change.
 * All mutable state is guarded by a single [Mutex] so a screen-off refill and a reactive reload
 * cannot interleave.
 */
@Singleton
class RotationEngine @Inject constructor(
    private val repository: WallpaperRepository,
    private val bufferManager: BufferManager
) {
    private companion object {
        const val TAG = "RotationEngine"
    }

    private val mutex = Mutex()
    private val imageMagazine = mutableListOf<WallpaperImage>()
    private var currentPointer = -1
    private var activeCropRule: CropRule = CropRule.FIT

    private var collectorJob: Job? = null

    /**
     * Starts observing the active collection's images and keeping the magazine + disk buffer in
     * sync. Cancels any previous subscription first (idempotent across service restarts) and
     * suspends until the first load + refill pass completes, so the caller can rely on the buffer
     * being ready before it marks the service Running.
     *
     * The passed [scope] should be the owning service's scope: cancelling it (in `onDestroy`)
     * tears the subscription down.
     */
    suspend fun start(scope: CoroutineScope) {
        collectorJob?.cancel()
        val firstLoad = CompletableDeferred<Unit>()
        // Collect off the main thread: the magazine shuffle/rebuild runs in the collector block.
        collectorJob = scope.launch(Dispatchers.IO) {
            repository.activeCollectionImagesFlow().collectLatest { snapshot ->
                try {
                    reloadAndRefill(snapshot)
                } finally {
                    firstLoad.complete(Unit)
                }
            }
        }
        firstLoad.await()
    }

    /**
     * Rebuilds the magazine from a fresh snapshot (shuffled, pointer reset) and refills the disk
     * buffer, all under the lock so it is atomic with respect to a concurrent screen-off refill.
     * A `null` snapshot (no active collection) clears the magazine.
     */
    private suspend fun reloadAndRefill(snapshot: Pair<WallpaperCollection, List<WallpaperImage>>?) {
        mutex.withLock {
            if (snapshot == null) {
                clearMagazine()
                return
            }
            val (collection, images) = snapshot
            loadMagazineLocked(collection.defaultCropRule, images)
            refillDiskBufferLocked()
        }
    }

    private fun loadMagazineLocked(cropRule: CropRule, images: List<WallpaperImage>) {
        imageMagazine.clear()
        imageMagazine.addAll(images.shuffled())
        currentPointer = -1
        activeCropRule = cropRule
        Log.d(TAG, "Magazine loaded: ${imageMagazine.size} items.")
    }

    /**
     * Advances to the next image and pre-renders it into the disk buffer. Called both by the
     * reactive reload (already holding the lock via [reloadAndRefill]) and by `ScreenOffReceiver`
     * after each rotation (via the public [refillDiskBuffer]). Self-heals by marking a file
     * unavailable the moment its source is definitively gone (see [BufferPreparationResult.Failure]),
     * rather than deleting the wallpaper/collection entry so the user's curation is never destroyed by
     * a background failure, and [WallpaperRepository.reprobeUnavailableFiles] can restore it later.
     */
    suspend fun refillDiskBuffer(): Boolean = mutex.withLock { refillDiskBufferLocked() }

    private suspend fun refillDiskBufferLocked(): Boolean {
        val maxAttempts = imageMagazine.size
        if (maxAttempts == 0) return false

        for (attempt in 0 until maxAttempts) {
            if (imageMagazine.isEmpty()) break

            currentPointer++
            if (currentPointer >= imageMagazine.size) {
                Log.d(TAG, "Cycle complete. Reshuffling for new sequence.")
                reshuffleMagazineLocked()
                currentPointer = 0
            }
            val nextImage = imageMagazine[currentPointer]

            when (val result = bufferManager.prepareNextWallpaper(nextImage, activeCropRule)) {
                is BufferPreparationResult.Success -> return true
                is BufferPreparationResult.Failure -> {
                    if (result.definitive) {
                        Log.w(TAG, "Source definitively unavailable, marking unavailable: ${nextImage.uri}")
                        repository.markFileUnavailable(nextImage.fileId)

                        imageMagazine.removeAt(currentPointer)
                        // Step back so the next iteration's increment lands on the element that
                        // shifted into this slot instead of skipping it. Empties end at -1 naturally.
                        currentPointer--
                    } else {
                        Log.w(TAG, "Transient failure loading ${nextImage.uri}, will retry on a later rotation.")
                    }
                }
            }
        }

        return false
    }

    /**
     * Reshuffles the magazine for a new cycle, ensuring the image that just closed the previous
     * cycle (at [imageMagazine]'s last index when called) doesn't land first.
     */
    private fun reshuffleMagazineLocked() {
        val lastShown = imageMagazine.last()
        imageMagazine.shuffle()
        if (imageMagazine.size > 1 && imageMagazine[0] == lastShown) {
            val swapIndex = (1..imageMagazine.lastIndex).random()
            imageMagazine[0] = imageMagazine[swapIndex]
            imageMagazine[swapIndex] = lastShown
        }
    }

    /**
     * Resets the in-memory magazine state.
     *
     * Note: only call if already holding the lock (see [reloadAndRefill]) or during teardown
     * (magazine won't be read afterward, see [WallpaperService.onDestroy()][com.ninecsdev.wallpaperchanger.service.WallpaperService.onDestroy])
     */
    fun clearMagazine() {
        imageMagazine.clear()
        currentPointer = -1
    }
}
