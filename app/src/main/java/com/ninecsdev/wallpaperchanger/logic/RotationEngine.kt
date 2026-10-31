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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the in-memory rotation state (the "magazine") and the shuffle cycle. All mutable state is
 * guarded by a single [Mutex] so a screen-off refill and a reactive reload cannot interleave.
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
    private var activeCropRule: CropRule = WallpaperCollection.DEFAULT_CROP_RULE

    private var collectorJob: Job? = null

    private val _loadedCollectionId = MutableStateFlow<Long?>(null)

    /** The collection the magazine was last loaded from, set once its buffer refill has been attempted. */
    val loadedCollectionId: StateFlow<Long?> = _loadedCollectionId.asStateFlow()

    /**
     * Idempotent across service restarts. Suspends until the first load and refill complete, so the
     * buffer is ready before the caller marks the service Running. Cancelling [scope] tears it down.
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

    /** Atomic with respect to a concurrent screen-off refill. A null snapshot (no active collection) clears the magazine. */
    private suspend fun reloadAndRefill(snapshot: Pair<WallpaperCollection, List<WallpaperImage>>?) {
        mutex.withLock {
            if (snapshot == null) {
                clearMagazine()
                _loadedCollectionId.value = null
                return
            }
            val (collection, images) = snapshot
            loadMagazineLocked(collection.defaultCropRule, images)
            refillDiskBufferLocked()
            _loadedCollectionId.value = collection.id
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
     * Advances to the next image and pre-renders it. A definitively gone source is marked unavailable,
     * never deleted, so the user's curation survives and [WallpaperRepository.reprobeUnavailableFiles] can restore it.
     */
    suspend fun refillDiskBuffer(): Boolean = mutex.withLock { refillDiskBufferLocked() }

    /**
     * Runs [block] only if the prepared buffer belongs to [collectionId], else returns null. Takes the
     * reload lock, so an in-flight rebuild is waited out and none starts meanwhile.
     * [block] must not re-enter the engine.
     */
    // TODO tests: see vault note tests/Collection Switch Rotation Gate Tests.md
    suspend fun <T> withBufferFor(collectionId: Long, block: suspend () -> T): T? = mutex.withLock {
        val prepared = bufferManager.preparedCollectionId()

        if (prepared != collectionId) {
            Log.w(TAG, "Prepared buffer belongs to collection $prepared, not $collectionId.")
            return@withLock null
        }
        block()
    }

    private suspend fun refillDiskBufferLocked(): Boolean {
        val maxAttempts = imageMagazine.size
        if (maxAttempts == 0) return forgetPreparedAndFail()

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
                        Log.w(TAG, "Source definitively unavailable, marking unavailable: ${nextImage.uriString}")
                        repository.markFileUnavailable(nextImage.fileId)

                        imageMagazine.removeAt(currentPointer)
                        // Step back so the next iteration's increment lands on the element that
                        // shifted into this slot instead of skipping it. Empties end at -1 naturally.
                        currentPointer--
                    } else {
                        Log.w(TAG, "Transient failure loading ${nextImage.uriString}, will retry on a later rotation.")
                    }
                }
            }
        }

        return forgetPreparedAndFail()
    }

    /** Nothing renderable was left, so the stamp on whatever is still on disk is withdrawn */
    private suspend fun forgetPreparedAndFail(): Boolean {
        bufferManager.forgetPrepared()
        return false
    }

    /** Ensures the image that just closed the previous cycle doesn't land first. */
    private fun reshuffleMagazineLocked() {
        val lastShown = imageMagazine.last()
        imageMagazine.shuffle()
        if (imageMagazine.size > 1 && imageMagazine[0] == lastShown) {
            val swapIndex = (1..imageMagazine.lastIndex).random()
            imageMagazine[0] = imageMagazine[swapIndex]
            imageMagazine[swapIndex] = lastShown
        }
    }

    /** Only call while holding the lock (see [reloadAndRefill]) or during teardown, when the magazine won't be read afterward. */
    fun clearMagazine() {
        imageMagazine.clear()
        currentPointer = -1
    }
}
