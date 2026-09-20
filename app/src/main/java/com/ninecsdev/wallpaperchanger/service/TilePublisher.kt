package com.ninecsdev.wallpaperchanger.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import com.ninecsdev.wallpaperchanger.di.ApplicationScope
import com.ninecsdev.wallpaperchanger.logic.ServiceLifecycle
import com.ninecsdev.wallpaperchanger.model.ServiceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the quick settings tile to redraw whenever [ServiceLifecycle.serviceState] changes.
 *
 * The tile is declared *active*, so the system never binds it when the panel opens and nothing else
 * would ask it to repaint.
 */
@Singleton
class TilePublisher @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val serviceLifecycle: ServiceLifecycle,
    @param:ApplicationScope private val scope: CoroutineScope
) {
    private companion object {
        const val TAG = "TilePublisher"
    }

    /** Called once, from the application's `onCreate`. */
    fun start() {
        serviceLifecycle.serviceState
            .filterNotNull()
            .onEach(::requestRedraw)
            .launchIn(scope)
    }

    private fun requestRedraw(state: ServiceState) {
        try {
            TileService.requestListeningState(
                appContext,
                ComponentName(appContext, WallpaperTileService::class.java)
            )
            Log.d(TAG, "Asked the tile to redraw for $state")
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask the tile to redraw for $state", e)
        }
    }
}
