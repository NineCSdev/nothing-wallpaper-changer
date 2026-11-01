package com.ninecsdev.wallpaperchanger

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.imageDecoderEnabled
import com.ninecsdev.wallpaperchanger.data.source.GridThumbnailCache
import com.ninecsdev.wallpaperchanger.service.TilePublisher
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.CollectionGridWarmer
import com.ninecsdev.wallpaperchanger.ui.components.GridThumbnailFetcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WallpaperApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var tilePublisher: TilePublisher
    @Inject lateinit var gridThumbnailCache: GridThumbnailCache
    @Inject lateinit var collectionGridWarmer: CollectionGridWarmer

    override fun onCreate() {
        super.onCreate()
        tilePublisher.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Only network loads write it, and every image here is local
            .diskCache(null)
            // ImageDecoder sizes by target size alone, which downsamples softer than
            // BitmapFactory's power-of-two sample plus a filtered scale
            .imageDecoderEnabled(false)
            .components {
                add(collectionGridWarmer.interceptor)
                add(GridThumbnailFetcher.Factory(gridThumbnailCache))
            }
            .build()
}
