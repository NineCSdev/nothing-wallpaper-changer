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
 * Restarts [WallpaperService] after system events that kill the process without a graceful
 * [WallpaperService.onDestroy]:
 *
 * - **Boot** (`BOOT_COMPLETED` / `QUICKBOOT_POWERON`): restarts only if the user enabled
 *   start-on-boot and the service was last left on.
 * - **App update** (`MY_PACKAGE_REPLACED`): a package replace kills the process without
 *   `onDestroy`. Restarts whenever the service was last left on.
 *
 * Both paths gate on [AppDataStore.isServiceDesired], the persisted user intent that,
 * unlike `service_running`, survives an ungraceful kill (not self-healed by
 * [ServiceStateManager][com.ninecsdev.wallpaperchanger.data.ServiceStateManager])
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
            Log.w(tag, "Cannot start service: app is not exempted from battery optimization.", e)
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

        /**
         * Pure restart-gating decision, kept side-effect-free so it is trivially unit-testable
         * without Android dependencies.
         *
         * Every path requires [desired], the persisted intent flag that survives an
         * ungraceful kill.
         */
        internal fun shouldRestartService(
            action: String,
            startOnBoot: Boolean,
            desired: Boolean
        ): Boolean {
            if (!desired) return false
            return when (action) {
                Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON -> startOnBoot
                Intent.ACTION_MY_PACKAGE_REPLACED -> true
                else -> false
            }
        }
    }
}
