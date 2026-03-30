package com.ninecsdev.wallpaperchanger.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.d("BootReceiver", "Phone rebooted. Checking service state...")
        WallpaperRepository.initialize(context)

        // For now it always starts on boot, TODO: let the user choose if it wants to start on boot in the future
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!WallpaperRepository.shouldStartOnBoot()) {
                    Log.d("BootReceiver", "Start on boot is disabled. Doing nothing.")
                    return@launch
                }

                if (!WallpaperRepository.isServiceRunning()) {
                    Log.d("BootReceiver", "Service was not active. Doing nothing.")
                    return@launch
                }

                Log.i("BootReceiver", "Service was active. Restarting...")
                try {
                    context.startForegroundService(Intent(context, WallpaperService::class.java))
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        e is ForegroundServiceStartNotAllowedException) {
                        Log.w("BootReceiver", "Cannot start service: app is not exempted from battery optimization.", e)
                    } else {
                        Log.e("BootReceiver", "Failed to start service on boot.", e)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
