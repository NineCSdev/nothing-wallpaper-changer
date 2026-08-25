package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereSourceProvisioner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Answers [AtmosphereWallpaperService.ACTION_SOURCE_REQUESTED] by rendering a source and handing it
 * to the engine.
 *
 * **Manifest-declared on purpose.** The engine can be running with this app's process dead.
 *
 * Desired wallpaper mode has no say here, for the reason in [AtmosphereSourceProvisioner.provision].
 */
@AndroidEntryPoint
class AtmosphereSourceRequestReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "AtmosphereSourceRequest"
    }

    @Inject lateinit var provisioner: AtmosphereSourceProvisioner

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AtmosphereWallpaperService.ACTION_SOURCE_REQUESTED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!provisioner.provision()) {
                    // A fresh install with no collection and no default wallpaper
                    Log.i(TAG, "Nothing to deliver; the engine keeps its fallback")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not provision a source for the engine", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
