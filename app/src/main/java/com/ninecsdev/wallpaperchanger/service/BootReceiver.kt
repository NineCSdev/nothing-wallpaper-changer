package com.ninecsdev.wallpaperchanger.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    private  val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isBootAction(action)) return

        Log.d(tag, "Phone rebooted. Checking service state...")
        WallpaperRepository.initialize(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!WallpaperRepository.shouldStartOnBoot()) {
                    Log.d(tag, "Start on boot is disabled. Doing nothing.")
                    return@launch
                }

                if (!WallpaperRepository.isServiceRunning()) {
                    Log.d(tag, "Service was not active. Doing nothing.")
                    return@launch
                }

                startServiceSafely(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isBootAction(action: String): Boolean {
        return action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON"
    }

    private fun startServiceSafely(context: Context) {
        Log.i(tag, "Service was active. Restarting...")
        try {
            context.startForegroundService(Intent(context, WallpaperService::class.java))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(tag, "Cannot start service: app is not exempted from battery optimization.", e)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start service on boot.", e)
        }
    }
}
