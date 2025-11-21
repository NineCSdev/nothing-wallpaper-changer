package com.ninecsdev.wallpaperchanger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * A `TileService` that provides a Quick Settings tile to control the `WallpaperService`.
 *
 * This tile allows the user to quickly start or stop the wallpaper changing service
 * without opening the main application. The tile's state (active/inactive) and subtitle
 * reflect the current status of the service and whether a wallpaper folder has been selected.
 */
class WallpaperTileService : TileService() {

    // Logcat tag for debugging.
    private val tag = "WallpaperTileService"

    /**
     * A BroadcastReceiver that listens for state change announcements from the `WallpaperService`.
     * When the service starts or stops, it sends a broadcast, which is caught here to
     * trigger a UI update for the tile, ensuring it accurately reflects the service's state.
     */
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(tag, "Received broadcast: ${intent?.action}")
            // When a broadcast is received, call updateTile() to refresh the tile's state.
            updateTile()
        }
    }

    /**
     * Called by the system when the Quick Settings tile becomes visible to the user.
     * This is the ideal place to register receivers and perform initial setup.
     */
    override fun onStartListening() {
        super.onStartListening()
        Log.d(tag, "Tile is now listening")

        // Register receiver to listen for service state changes
        val intentFilter = IntentFilter().apply {
            addAction(WallpaperService.BROADCAST_SERVICE_STARTED)
            addAction(WallpaperService.BROADCAST_SERVICE_STOPPED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        registerReceiver(serviceStateReceiver, intentFilter, RECEIVER_NOT_EXPORTED)

        // Perform an initial update to set the correct state when the tile is first shown.
        updateTile()
    }

    /**
     * Called by the system when the Quick Settings tile is no longer visible.
     * This is where we should clean up resources, like unregistering the receiver,
     * to avoid memory leaks and unnecessary background work.
     */
    override fun onStopListening() {
        super.onStopListening()
        Log.d(tag, "Tile stopped listening")

        // Unregister the receiver to prevent memory leaks.
        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering receiver", e)
        }
    }

    /**
     * Called when the user taps the Quick Settings tile.
     * This method contains the core logic for toggling the `WallpaperService`.
     */
    override fun onClick() {
        super.onClick()

        // Get the current state from AppPreferences.
        val isServiceRunning = AppPreferences.isServiceRunning(this)
        val isPowerSaveMode = AppPreferences.isPowerSaveMode(this)
        val folderSelected = AppPreferences.getFolderUri(this) != null

        Log.d(tag, "Tile clicked. Service running: $isServiceRunning, Power Save: $isPowerSaveMode")

        // Pre-condition check: A folder must be selected to start the service.
        if (!folderSelected) {
            // Can't start service without a folder
            showTileMessage("No wallpaper folder selected")
            updateTile()
            return
        }

        // If Power Save Mode is enabled, the tile will be unclickable you we update the UI
        if (isPowerSaveMode && !isServiceRunning) {
            Log.d(tag, "Action blocked by Power Save Mode.")
            updateTile()
            return
        }

        // Toggle the service based on its current state.
        if (isServiceRunning) {
            // If service is running, send the stop command from quick settings.
            Log.d(tag, "Sending stop command to WallpaperService from quick settings")
            val intent = Intent(this, WallpaperService::class.java).apply {
                action = WallpaperService.ACTION_STOP_SERVICE
            }
            startService(intent)

            // Immediately update tile to show stopping state
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                subtitle = "Stopping..."
                updateTile()
            }
        } else {
            // If service is stopped, start it as a foreground service.
            Log.d(tag, "Starting wallpaper service from Quick Settings")
            val serviceIntent = Intent(this, WallpaperService::class.java)
            startForegroundService(serviceIntent)

            // Immediately update tile to show starting state
            qsTile?.apply {
                state = Tile.STATE_ACTIVE
                subtitle = "Starting..."
                updateTile()
            }
        }

        // Note: A full UI update will occur once the service broadcasts its new state.
    }

    /**
     * Centralized method to update the tile's visual state (icon, label, and subtitle).
     * This is called whenever the tile needs to reflect a change in the application's state.
     */
    private fun updateTile() {
        // qsTile is a nullable property of TileService. If it's null, we can't do anything.
        val tile = qsTile ?: return
        val isServiceRunning = AppPreferences.isServiceRunning(this)
        val isPowerSaveMode = AppPreferences.isPowerSaveMode(this)
        val folderSelected = AppPreferences.getFolderUri(this) != null

        Log.d(tag, "Updating tile - Service running: $isServiceRunning, Folder: $folderSelected")

        tile.label = "Wallpaper Changer"
        when {
            // Case 1: No folder has been selected in the app yet.
            !folderSelected -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "Setup needed"
            }
            // Case 2: Power Save Mode is enabled and the service is not running.
            isPowerSaveMode && !isServiceRunning -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "Power Save Mode"
            }
            // Case 3: Folder is selected and the service is actively running.
            isServiceRunning -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Active"
            }
            // Case 4: Folder is selected, but the service is stopped.
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Tap to start"
            }
        }

        // Apply the changes to the actual tile.
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
        // The dialog provides a quick shortcut for the user to open the app and fix the issue.
        showDialog(android.app.AlertDialog.Builder(this)
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