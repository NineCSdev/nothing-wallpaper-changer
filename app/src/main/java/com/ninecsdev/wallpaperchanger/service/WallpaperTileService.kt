package com.ninecsdev.wallpaperchanger.service

import android.app.AlertDialog
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.ServiceStateManager
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick settings tile for instant control of the app.
 */
@AndroidEntryPoint
class WallpaperTileService : TileService() {

    private val tag = "WallpaperTileService"
    @Inject lateinit var serviceStateManager: ServiceStateManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()

        // Single source of truth: render the tile from the manager's derived state flow.
        // This replaces the old serviceEvent collection + separate power-save BroadcastReceiver.
        stateJob = serviceScope.launch {
            serviceStateManager.serviceState.collectLatest { render(it) }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        stateJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /**
     * Handles the user tapping the tile.
     */
    override fun onClick() {
        super.onClick()

        when (serviceStateManager.serviceState.value) {
            is ServiceState.DisabledNoCollection -> {
                showTileMessage(getString(R.string.tile_no_collection_message))
            }
            is ServiceState.DisabledPowerSave -> Unit
            is ServiceState.Stopping -> Unit
            is ServiceState.Loading -> Unit
            is ServiceState.Stopped -> {
                serviceStateManager.markServiceLoading()
                startForegroundService(Intent(this, WallpaperService::class.java))
            }
            is ServiceState.Running, is ServiceState.Paused -> {
                val intent = Intent(this, WallpaperService::class.java).apply {
                    action = WallpaperService.ACTION_STOP_SERVICE
                }
                startService(intent)
            }
        }
    }

    /**
     * Renders the tile's visual state from a resolved [ServiceState].
     */
    private fun render(state: ServiceState) {
        val tile = qsTile ?: return

        tile.label = getString(R.string.tile_label)

        when (state) {
            is ServiceState.Running -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(R.string.tile_subtitle_active)
            }
            is ServiceState.Loading -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(R.string.tile_subtitle_initializing)
            }
            is ServiceState.Stopping -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_subtitle_stopping)
            }
            is ServiceState.Stopped -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_subtitle_ready)
            }
            is ServiceState.DisabledNoCollection -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.tile_subtitle_no_list)
            }
            is ServiceState.DisabledPowerSave -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.tile_subtitle_power_save)
            }
            is ServiceState.Paused -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(R.string.tile_subtitle_paused)
            }
        }
        tile.updateTile()
        Log.d(tag, "Tile updated: state=${tile.state}")
    }

    /**
     * Shows a system dialog originating from the tile.
     * This is used to provide more detailed feedback or actions to the user, such as
     * prompting them to open the app.
     * @param message The string to display in the dialog's body.
     */
    private fun showTileMessage(message: String) {
        showDialog(
            AlertDialog.Builder(this)
            .setTitle(getString(R.string.tile_dialog_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.tile_dialog_open_app)) { _, _ ->
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.tile_dialog_cancel), null)
            .create())
    }
}