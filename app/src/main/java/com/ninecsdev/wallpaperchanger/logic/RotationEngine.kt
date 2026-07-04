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
        const val MAX_FAILURES_BEFORE_PURGE = 2
    }

    private val mutex = Mutex()
    private val imageMagazine = mutableListOf<WallpaperImage>()
    private var currentPointer = -1
    private var activeCropRule: CropRule = CropRule.FIT
    private val failureCounts = mutableMapOf<Long, Int>()

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
        failureCounts.clear()
        Log.d(TAG, "Magazine loaded: ${imageMagazine.size} items.")
    }

    /**
     * Advances to the next image and pre-renders it into the disk buffer. Called both by the
     * reactive reload (already holding the lock via [reloadAndRefill]) and by `ScreenOffReceiver`
     * after each rotation (via the public [refillDiskBuffer]). Self-heals by purging images that
     * fail to load repeatedly.
     */
    suspend fun refillDiskBuffer(): Boolean = mutex.withLock { refillDiskBufferLocked() }

    private suspend fun refillDiskBufferLocked(): Boolean {
        val maxAttempts = imageMagazine.size
        if (maxAttempts == 0) return false

        // Added multiple tries before deleting for being more permissive in case there was an issue
        // and the file wasn't actually deleted, not sure if it is better than trying once for UX so may
        // revert in the future
        for (attempt in 0 until maxAttempts) {
            if (imageMagazine.isEmpty()) break

            currentPointer++
            if (currentPointer >= imageMagazine.size) {
                Log.d(TAG, "Cycle complete. Reshuffling for new sequence.")
                imageMagazine.shuffle()
                currentPointer = 0
            }
            val nextImage = imageMagazine[currentPointer]

            when (bufferManager.prepareNextWallpaper(nextImage, activeCropRule)) {
                is BufferPreparationResult.Success -> {
                    failureCounts.remove(nextImage.id)
                    return true
                }
                is BufferPreparationResult.Failure -> {
                    val failures = (failureCounts[nextImage.id] ?: 0) + 1
                    if (failures < MAX_FAILURES_BEFORE_PURGE) {
                        failureCounts[nextImage.id] = failures
                        Log.w(TAG, "Failed to load ${nextImage.uri}. Will retry later ($failures/$MAX_FAILURES_BEFORE_PURGE).")
                    } else {
                        failureCounts.remove(nextImage.id)
                        Log.w(TAG, "Failed to load ${nextImage.uri}. Removing after $failures failures.")
                        repository.deleteImagesFromCollection(listOf(nextImage))

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

        return false
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
        failureCounts.clear()
    }
}
