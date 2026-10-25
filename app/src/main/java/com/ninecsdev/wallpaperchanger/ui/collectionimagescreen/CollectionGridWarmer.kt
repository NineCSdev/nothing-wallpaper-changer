package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.content.Context
import coil.imageLoader
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Scale
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

/**
 * Decodes the first screen of a collection's grid into Coil's memory cache as the collection is
 * tapped, so the grid's own requests, issued once it has composed, find their tiles already
 * there instead of drawing placeholders while they load.
 */
@Singleton
class CollectionGridWarmer @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: WallpaperRepository,
    @param:ApplicationScope private val scope: CoroutineScope
) {
    /** Warm-ups still loading, by memory cache key. */
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageResult>>()

    fun warm(collectionId: Long) {
        scope.launch {
            val metrics = appContext.resources.displayMetrics
            // The grid spans the screen width, so a cell is a column's share of it. Asking for the
            // whole cell, padding included, keeps the bitmap at least as large as the tile's request
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
                            .data(GridThumbnail(wallpaper.fileId, wallpaper.uriString, wallpaper.sourceType))
                            .memoryCacheKey(key)
                            .size(cellPx)
                            .scale(Scale.FILL)
                            .interceptorDispatcher(Dispatchers.IO)
                            .setParameter(WARM_UP, true, memoryCacheKey = null)
                            .build()
                    ).job
                    inFlight[key] = job
                    job.invokeOnCompletion { inFlight.remove(key, job) }
                }
        }
    }

    /**
     * Holds a grid request back until the warm-up for the same tile lands, so it reads the
     * result from memory instead of loading the same thumbnail a second time alongside it.
     */
    val interceptor = Interceptor { chain ->
        val request = chain.request
        if (request.parameters.value<Boolean>(WARM_UP) != true) {
            // join, not await: a canceled warm-up must not cancel the request waiting on it
            request.memoryCacheKey?.let { inFlight[it.key] }?.join()
        }
        chain.proceed(request)
    }

    private companion object {
        const val WARM_UP = "collection_grid_warm_up"
    }
}
