package com.ninecsdev.wallpaperchanger.ui.components

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Scale
import coil.size.pxOrElse
import com.ninecsdev.wallpaperchanger.data.source.GridThumbnailCache
import com.ninecsdev.wallpaperchanger.data.source.ThumbnailBox
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import okio.Path.Companion.toOkioPath
import java.io.IOException

/** Coil model for a small image of a file (a grid tile, edited or not, or a collection card slot): its [GridThumbnailCache] entry instead of its original. */
data class GridThumbnail(val fileId: Long, val uriString: String, val sourceType: SourceType)

/** Loads a [GridThumbnail], creating it on first view. */
class GridThumbnailFetcher(
    private val data: GridThumbnail,
    private val options: Options,
    private val cache: GridThumbnailCache
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { 0 }
        val height = options.size.height.pxOrElse { 0 }
        // An unsized axis takes the other's size, and an unsized request a third of a phone's width
        val box = ThumbnailBox(
            width = width.takeIf { it > 0 } ?: height.takeIf { it > 0 } ?: FALLBACK_SIDE,
            height = height.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: FALLBACK_SIDE,
            fill = options.scale == Scale.FILL
        )
        val file = cache.fileFor(data.fileId, data.uriString, data.sourceType, box)
            ?: throw IOException("No thumbnail for ${data.uriString}")
        return SourceResult(
            source = ImageSource(file.toOkioPath()),
            mimeType = null, // JPEG or WebP, sniffed by the decoder
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val cache: GridThumbnailCache) : Fetcher.Factory<GridThumbnail> {
        override fun create(data: GridThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            GridThumbnailFetcher(data, options, cache)
    }

    private companion object {
        const val FALLBACK_SIDE = 360
    }
}
