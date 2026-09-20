package com.ninecsdev.wallpaperchanger

import android.app.Application
import com.ninecsdev.wallpaperchanger.service.TilePublisher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WallpaperApp : Application() {

    @Inject lateinit var tilePublisher: TilePublisher

    override fun onCreate() {
        super.onCreate()
        tilePublisher.start()
    }
}
