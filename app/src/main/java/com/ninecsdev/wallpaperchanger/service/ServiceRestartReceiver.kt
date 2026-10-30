package com.ninecsdev.wallpaperchanger.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts [WallpaperService] after boot (only if start-on-boot is enabled) or an app update
 * (a package replace kills the process without `onDestroy`). Both gate on
 * [AppDataStore.isServiceDesired], the persisted intent that, unlike `service_running`, survives
 * an ungraceful kill.
 */
@AndroidEntryPoint
class ServiceRestartReceiver : BroadcastReceiver() {
    private val tag = "ServiceRestartReceiver"

    @Inject lateinit var appDataStore: AppDataStore

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        Log.d(tag, "Received $action. Checking whether to restart service...")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val restart = shouldRestartService(
                    action = action,
                    startOnBoot = appDataStore.shouldStartOnBoot(),
                    desired = appDataStore.isServiceDesired()
                )
                if (restart) {
                    startServiceSafely(context, action)
                } else {
                    Log.d(tag, "Not restarting service: gating conditions not met for $action.")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startServiceSafely(context: Context, action: String) {
        Log.i(tag, "Service was active. Restarting after $action...")
        try {
            context.startForegroundService(Intent(context, WallpaperService::class.java))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Defensive only: not expected on a stock platform.
            Log.w(tag, "System refused the background foreground-service start after $action.", e)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start service after $action.", e)
        }
    }

    companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,       // "Cold" boot (turning on the phone)
            ACTION_QUICKBOOT_POWERON,           // Restarting boot
            Intent.ACTION_MY_PACKAGE_REPLACED   // Updating the app
        )

        /** Pure and side-effect-free for unit testing; every path requires [desired]. */
        internal fun shouldRestartService(
            action: String,
            startOnBoot: Boolean,
            desired: Boolean
        ): Boolean {
            return desired && when (action) {
                Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON -> startOnBoot
                Intent.ACTION_MY_PACKAGE_REPLACED -> true
                else -> false
            }
        }
    }
}
