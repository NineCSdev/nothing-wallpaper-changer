package com.ninecsdev.wallpaperchanger.ui.components

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereEntry
import com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereWallpaperService
import kotlinx.coroutines.launch

private const val TAG = "AtmosphereEntry"

/**
 * Returns an action that stages the atmosphere source through [stage], then opens the system
 * live-wallpaper screen on our engine. Once the user is back a snackbar on [snackbarHostState]
 * says the separate lock wallpaper is gone.
 */
@Composable
fun rememberAtmosphereEntry(
    stage: suspend () -> AtmosphereEntry,
    snackbarHostState: SnackbarHostState
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentStage by rememberUpdatedState(stage)
    val lockRemovedMessage = stringResource(R.string.atmosphere_lock_removed_snackbar)
    var lockRemovalPending by rememberSaveable { mutableStateOf(false) }

    // The system screen covers us fully, so coming back is a start
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (lockRemovalPending) {
            lockRemovalPending = false
            scope.launch { snackbarHostState.showSnackbar(lockRemovedMessage) }
        }
    }

    return remember(context, scope) {
        fun() {
            scope.launch {
                val entry = currentStage()
                if (!entry.sourceReady) {
                    Log.w(TAG, "No atmosphere source could be prepared")
                    return@launch
                }
                if (entry.lockWallpaperRemoved) lockRemovalPending = true

                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, AtmosphereWallpaperService::class.java)
                )
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "No live-wallpaper picker available", e)
                }
            }
        }
    }
}
