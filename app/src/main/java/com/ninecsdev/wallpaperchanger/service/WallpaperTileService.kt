package com.ninecsdev.wallpaperchanger.service

import android.app.AlertDialog
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.StringRes
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.logic.ServiceLifecycle
import com.ninecsdev.wallpaperchanger.model.LifecycleVerdict
import com.ninecsdev.wallpaperchanger.model.ServiceIntent
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Quick settings tile for instant control of the app.
 *
 * The tile is an active tile, so the panel never binds it.
 */
@AndroidEntryPoint
class WallpaperTileService : TileService() {

    private companion object {
        /** How long a tap waits for the state to resolve before it is given up on. */
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }

    private val tag = "WallpaperTileService"
    @Inject lateinit var serviceLifecycle: ServiceLifecycle
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()

        // Single source of truth: render the tile from the manager's derived state flow.
        // This replaces the old serviceEvent collection + separate power-save BroadcastReceiver.
        stateJob = serviceScope.launch {
            serviceLifecycle.serviceState.filterNotNull().collectLatest { render(it) }
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

    /** Handles the user tapping the tile. sA tap on a cold process arrives before the state has resolved. */
    override fun onClick() {
        super.onClick()

        // What the user actually tapped, read before anything repaints it
        val shown = qsTile?.takeIf { !it.subtitle.isNullOrBlank() }?.state

        val resolved = serviceLifecycle.serviceState.value
        if (resolved != null) {
            act(resolved, shown)
            return
        }

        serviceScope.launch {
            val state = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                serviceLifecycle.serviceState.filterNotNull().first()
            }
            if (state == null) {
                Log.w(tag, "Tap dropped: state did not resolve within ${RESOLVE_TIMEOUT_MS}ms")
            } else {
                act(state, shown)
            }
        }
    }

    /** Acts on [state], unless the tile was showing something [state] contradicts. */
    private fun act(state: ServiceState, shown: Int?) {
        if (shown != null && shown != tileStateFor(state)) {
            // The copy on screen outlived the process that painted it. Correct it and let the user choose again
            Log.d(tag, "Tap dropped: tile showed $shown, state is $state")
            render(state)
            return
        }

        when (state) {
            is ServiceState.DisabledNoCollection -> {
                showTileMessage(getString(R.string.tile_no_collection_message))
            }
            is ServiceState.DisabledPowerSave -> Unit
            is ServiceState.Stopping -> Unit
            is ServiceState.Loading -> Unit
            is ServiceState.Stopped -> {
                // Express the wish, the authority decides whether it is legal and shows the
                // tap immediately as Loading, self-healing if the service never arrives
                if (serviceLifecycle.onIntent(ServiceIntent.StartRequested) is LifecycleVerdict.Accepted) {
                    startForegroundService(Intent(this, WallpaperService::class.java))
                }
            }
            is ServiceState.Running, is ServiceState.Paused, is ServiceState.PausedDnd -> {
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
        tile.state = tileStateFor(state)
        tile.subtitle = getString(subtitleFor(state))

        tile.updateTile()
        Log.d(tag, "Tile updated: state=${tile.state}")
    }

    /**
     * The `Tile.STATE_*` a [ServiceState] paints as.
     */
    private fun tileStateFor(state: ServiceState): Int = when (state) {
        is ServiceState.Running, is ServiceState.Loading, is ServiceState.Paused, is ServiceState.PausedDnd -> Tile.STATE_ACTIVE
        is ServiceState.Stopping, is ServiceState.Stopped -> Tile.STATE_INACTIVE
        is ServiceState.DisabledNoCollection, is ServiceState.DisabledPowerSave -> Tile.STATE_UNAVAILABLE
    }

    @StringRes
    private fun subtitleFor(state: ServiceState): Int = when (state) {
        is ServiceState.Running -> R.string.tile_subtitle_active
        is ServiceState.Loading -> R.string.tile_subtitle_initializing
        is ServiceState.Stopping -> R.string.tile_subtitle_stopping
        is ServiceState.Stopped -> R.string.tile_subtitle_ready
        is ServiceState.DisabledNoCollection -> R.string.tile_subtitle_no_collection
        is ServiceState.DisabledPowerSave -> R.string.tile_subtitle_power_save
        is ServiceState.Paused -> R.string.tile_subtitle_paused
        is ServiceState.PausedDnd -> R.string.tile_subtitle_paused_dnd
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