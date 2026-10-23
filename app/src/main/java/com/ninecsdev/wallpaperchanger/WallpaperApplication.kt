package com.ninecsdev.wallpaperchanger

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ninecsdev.wallpaperchanger.data.source.GridThumbnailCache
import com.ninecsdev.wallpaperchanger.service.TilePublisher
import com.ninecsdev.wallpaperchanger.ui.components.GridThumbnailFetcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WallpaperApp : Application(), ImageLoaderFactory {

    @Inject lateinit var tilePublisher: TilePublisher
    @Inject lateinit var gridThumbnailCache: GridThumbnailCache

    override fun onCreate() {
        super.onCreate()
        tilePublisher.start()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(GridThumbnailFetcher.Factory(gridThumbnailCache)) }
            .build()
}
