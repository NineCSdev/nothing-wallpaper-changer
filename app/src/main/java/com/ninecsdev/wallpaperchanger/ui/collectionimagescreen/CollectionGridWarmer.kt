package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.content.Context
import coil3.Extras
import coil3.getExtra
import coil3.imageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.Scale
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.di.ApplicationScope
import com.ninecsdev.wallpaperchanger.ui.components.GridThumbnail
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Decodes the first screen of a collection's grid into Coil's memory cache when the collection is tapped. */
@Singleton
class CollectionGridWarmer @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: WallpaperRepository,
    @param:ApplicationScope private val scope: CoroutineScope
) {
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageResult>>()

    fun warm(collectionId: Long) {
        scope.launch {
            val metrics = appContext.resources.displayMetrics
            // Whole cell, padding included, keeps the bitmap at least as large as the tile's request
            val cellPx = metrics.widthPixels / GRID_COLUMNS
            val firstScreen = (metrics.heightPixels + cellPx - 1) / cellPx * GRID_COLUMNS
            val imageLoader = appContext.imageLoader

            repository.getImagesForCollection(collectionId).first()
                .take(firstScreen)
                // Edited tiles are sized to their frame and cached under another key
                .filter { it.editParams == null }
                .forEach { wallpaper ->
                    val key = wallpaperThumbCacheKey(wallpaper.uriString)
                    val job = imageLoader.enqueue(
                        ImageRequest.Builder(appContext)
                            .data(GridThumbnail(wallpaper.fileId, wallpaper.uriString, wallpaper.sourceType, wallpaper.isAvailable))
                            .memoryCacheKey(key)
                            .size(cellPx)
                            .scale(Scale.FILL)
                            .interceptorCoroutineContext(Dispatchers.IO)
                            .apply { extras[WARM_UP] = true }
                            .build()
                    ).job
                    inFlight[key] = job
                    job.invokeOnCompletion { inFlight.remove(key, job) }
                }
        }
    }

    /** Holds a grid request until the warm-up for the same tile lands, so it isn't loaded twice. */
    val interceptor = Interceptor { chain ->
        val request = chain.request
        if (!request.getExtra(WARM_UP)) {
            // join, not await: a canceled warm-up must not cancel the request waiting on it
            request.memoryCacheKey?.let { inFlight[it] }?.join()
        }
        chain.proceed()
    }

    private companion object {
        // Extras stay out of the memory cache key, so a warm-up fills the grid's own entry
        val WARM_UP = Extras.Key(default = false)
    }
}
