package com.ninecsdev.wallpaperchanger.service

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick settings tile for instant control of the app.
 */
@AndroidEntryPoint
class WallpaperTileService : TileService() {

    private val tag = "WallpaperTileService"
    @Inject lateinit var repository: WallpaperRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var serviceEventJob: Job? = null

    /**
     * A BroadcastReceiver that listens for system-level power save mode changes
     * to update the tile state accordingly.
     */
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(tag, "System event: ${intent?.action}")
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        registerReceiver(
            systemReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            RECEIVER_NOT_EXPORTED
        )

        serviceEventJob = serviceScope.launch {
            repository.serviceEvent.collect { updateTile() }
        }

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        serviceEventJob?.cancel()
        try {
            unregisterReceiver(systemReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering receiver", e)
        }
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

        serviceScope.launch {
            val currentState = repository.getServiceState()

            when (currentState) {
                is ServiceState.DisabledNoCollection -> {
                    showTileMessage("No collection active. Please set one up in the app.")
                }
                is ServiceState.DisabledPowerSave -> {
                    updateTile()
                }
                is ServiceState.Paused -> {
                    updateTile()
                }
                is ServiceState.Stopped -> {
                    repository.markServiceLoading()
                    updateTile()
                    val intent = Intent(this@WallpaperTileService, WallpaperService::class.java)
                    startForegroundService(intent)
                }
                is ServiceState.Running -> {
                    val intent = Intent(this@WallpaperTileService, WallpaperService::class.java).apply {
                        action = WallpaperService.ACTION_STOP_SERVICE
                    }
                    startService(intent)
                    updateTile()
                }
                else -> updateTile()
            }
        }
    }

    /**
     * Updates the Tile visual state based on the app state.
     */
    private fun updateTile() {
        val tile = qsTile ?: return

        serviceScope.launch {
            val state = repository.getServiceState()

            tile.label = "Changer"

            when (state) {
                is ServiceState.Running -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.subtitle = "Active"
                }
                is ServiceState.Loading -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.subtitle = "Initializing..."
                }
                is ServiceState.Stopped -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.subtitle = "Ready"
                }
                is ServiceState.DisabledNoCollection -> {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.subtitle = "No List"
                }
                is ServiceState.DisabledPowerSave -> {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.subtitle = "Power Save"
                }
                is ServiceState.Paused -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.subtitle = "Paused (Battery)"
                }
            }
            tile.updateTile()
            Log.d(tag, "Tile updated: state=${tile.state}")
        }
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
            .setTitle("Wallpaper Changer")
            .setMessage(message)
            .setPositiveButton("Open App") { _, _ ->
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .create())
    }
}