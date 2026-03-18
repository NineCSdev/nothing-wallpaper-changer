package com.ninecsdev.wallpaperchanger.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.model.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service responsible for keeping the [ScreenOffReceiver] alive,
 * coordinating the whole app and creating and managing the notification.
 */
class WallpaperService : Service() {

    private val tag = "WallpaperService"
    private val channelId = "WallpaperServiceChannel"
    private val notificationId = 1

    private var screenOffReceiver: BroadcastReceiver? = null
    private var systemEventReceiver: BroadcastReceiver? = null
    private var nothingFocusModeReceiver: BroadcastReceiver? = null
    // SupervisorJob ensures one failing task doesn't kill the whole service scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_STOP_SERVICE = "com.ninecsdev.wallpaperchanger.ACTION_STOP_SERVICE"

        /** In-memory flag that resets on process death (reboots). */
        @Volatile var isAlive = false
            private set

        /**
         * Nothing OS Focus Mode broadcast actions. Nothing OS (Phone 1 / Phone 2) fires one of
         * these when the user enables or disables Focus Mode via Quick Settings or the
         * Digital Wellbeing settings screen.
         *
         * The `com.nothing.wellbeing` action is used on Nothing OS 1.x/2.x (Phone 1).
         * The `com.nothing.settings` variant is seen on some Nothing OS 2.5+ builds.
         * Both carry a boolean extra [EXTRA_NOTHING_FOCUS_MODE_ENABLED] (true = active).
         */
        const val ACTION_NOTHING_FOCUS_MODE_CHANGED =
            "com.nothing.wellbeing.action.FOCUS_MODE_STATE_CHANGED"
        const val ACTION_NOTHING_FOCUS_MODE_CHANGED_ALT =
            "com.nothing.settings.action.FOCUS_MODE_STATE_CHANGED"
        const val ACTION_NOTHING_FOCUS_MODE_CHANGED_V2 =
            "com.nothing.wellbeing.action.FOCUS_MODE_CHANGED"
        const val ACTION_NOTHING_FOCUS_MODE_CHANGED_V3 =
            "com.nothing.settings.action.FOCUS_MODE_CHANGED"
        const val ACTION_GOOGLE_FOCUS_MODE_CHANGED =
            "com.google.android.apps.wellbeing.action.FOCUS_MODE_CHANGED"
        const val ACTION_GOOGLE_FOCUS_MODE_STATE_CHANGED =
            "com.google.android.apps.wellbeing.action.FOCUS_MODE_STATE_CHANGED"
        const val EXTRA_NOTHING_FOCUS_MODE_ENABLED = "enabled"

        private val FOCUS_MODE_ACTIONS = listOf(
            ACTION_NOTHING_FOCUS_MODE_CHANGED,
            ACTION_NOTHING_FOCUS_MODE_CHANGED_ALT,
            ACTION_NOTHING_FOCUS_MODE_CHANGED_V2,
            ACTION_NOTHING_FOCUS_MODE_CHANGED_V3,
            ACTION_GOOGLE_FOCUS_MODE_CHANGED,
            ACTION_GOOGLE_FOCUS_MODE_STATE_CHANGED
        )

        private val EXTRA_NOTHING_FOCUS_MODE_CANDIDATES = listOf(
            EXTRA_NOTHING_FOCUS_MODE_ENABLED,
            "isEnabled",
            "focus_mode_enabled",
            "focusModeEnabled",
            "is_focus_mode_enabled",
            "focus_enabled",
            "focus_mode_state",
            "focus_state",
            "focusModeState",
            "mode",
            "state",
            "status"
        )
    }

    private fun parseBooleanLike(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Int -> value != 0
            is Long -> value != 0L
            is String -> {
                val normalized = value.trim().lowercase()
                when {
                    normalized.isBlank() -> null
                    normalized == "1" ||
                        normalized == "true" ||
                        normalized == "on" ||
                        normalized == "enabled" ||
                        normalized == "active" ||
                        normalized.contains("focus") ||
                        normalized.contains("dnd") ||
                        normalized.contains("zen") -> true
                    normalized == "0" ||
                        normalized == "false" ||
                        normalized == "off" ||
                        normalized == "disabled" ||
                        normalized == "inactive" ||
                        normalized == "none" ||
                        normalized.contains("disable") ||
                        normalized.contains("inactive") -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun readTypedExtra(extras: Bundle, key: String): Any? {
        if (!extras.containsKey(key)) return null

        // Use typed reads to avoid deprecated raw Bundle.get(key).
        try {
            extras.getString(key)?.let { return it }
        } catch (_: Exception) {
        }
        try {
            return extras.getBoolean(key)
        } catch (_: Exception) {
        }
        try {
            return extras.getInt(key)
        } catch (_: Exception) {
        }
        try {
            return extras.getLong(key)
        } catch (_: Exception) {
        }

        return null
    }

    private fun extractNothingFocusEnabled(intent: Intent): Boolean? {
        val extras = intent.extras ?: return null

        for (key in EXTRA_NOTHING_FOCUS_MODE_CANDIDATES) {
            val rawValue = readTypedExtra(extras, key) ?: continue
            val parsed = parseBooleanLike(rawValue)
            if (parsed != null) return parsed
        }

        // OEM payloads can rename keys; scan likely focus/state keys as a fallback.
        for (key in extras.keySet()) {
            val normalized = key.lowercase()
            if (!normalized.contains("focus") &&
                !normalized.contains("enabled") &&
                !normalized.contains("state") &&
                !normalized.contains("status")) {
                continue
            }
            val parsed = parseBooleanLike(readTypedExtra(extras, key))
            if (parsed != null) return parsed
        }

        return null
    }

    private suspend fun applyDefaultWallpaper() {
        val uri = WallpaperRepository.getDefaultWallpaperUri() ?: return

        Log.d(tag, "Applying default wallpaper...")

        withContext(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        WallpaperManager.getInstance(this@WallpaperService).setBitmap(
                            bitmap,
                            null,
                            true,
                            WallpaperManager.FLAG_LOCK
                        )
                        Log.i(tag, "Successfully applied default wallpaper.")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Fallback failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        Log.d(tag, "Service Created")
        WallpaperRepository.initialize(this)
        createNotificationChannel()

        screenOffReceiver = ScreenOffReceiver()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_EXPORTED)

        // Listen for Nothing OS Focus Mode state changes so that isDndActive() has an accurate
        // in-memory flag the moment Focus Mode is toggled — before the next screen-off event.
        nothingFocusModeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val active = extractNothingFocusEnabled(intent)
                    ?: WallpaperRepository.getDndDiagnosticSnapshot(failSafeWhenUnknown = true).let { diagnostic ->
                        Log.w(
                            tag,
                            "Nothing Focus broadcast payload not recognized (action=${intent.action}, " +
                                "keys=${intent.extras?.keySet()?.joinToString() ?: "none"}). " +
                                "Inferred active=${diagnostic.active} from system state " +
                                "(branch=${diagnostic.branch}, details=${diagnostic.details})."
                        )
                        diagnostic.active
                    }

                Log.d(tag, "Nothing OS Focus Mode broadcast received: enabled=$active (action=${intent.action})")
                WallpaperRepository.setNothingFocusModeActive(active)
            }
        }
        val nothingFocusFilter = IntentFilter().apply {
            FOCUS_MODE_ACTIONS.forEach { addAction(it) }
        }
        Log.d(tag, "Registered Focus Mode actions: ${FOCUS_MODE_ACTIONS.joinToString()}")
        registerReceiver(nothingFocusModeReceiver, nothingFocusFilter, RECEIVER_EXPORTED)

        systemEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = context.getSystemService(POWER_SERVICE) as PowerManager

                // This part stopped the app when battery was under 15% but as we now pause the app
                // it is no longer needed. Keep it in case i want to re-implement it
                /*if (intent.action == Intent.ACTION_BATTERY_LOW) {
                    handleStopCommand()
                    return
                }*/

                if (pm.isPowerSaveMode) {
                    pauseEngine()
                } else {
                    resumeEngine()
                }
            }
        }
        val systemFilter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        registerReceiver(systemEventReceiver, systemFilter, RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            handleStopCommand()
            return START_NOT_STICKY
        }

        try {
            startForeground(notificationId, createNotification("INITIALIZING..."))
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                e is ForegroundServiceStartNotAllowedException) {
                Log.w(tag, "Cannot start foreground: app not exempted from battery optimization.", e)
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }

        serviceScope.launch {
            WallpaperRepository.markServiceLoading()
            notifyUi()

            val state = WallpaperRepository.getServiceState()

            if (state !is ServiceState.DisabledNoCollection){
                WallpaperRepository.loadMagazine()
                WallpaperRepository.refillDiskBuffer()

                WallpaperRepository.markServiceRunning()
                notifyUi()

                val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
                updateNotification("Cycling: $activeName")
            }else{
                Log.w(tag, "Abort startup: No collection found.")
                WallpaperRepository.markServiceStopped()
                handleStopCommand()
            }
        }

        return START_STICKY
    }

    private fun handleStopCommand() {
        Log.i(tag, "Stopping service via command.")
        WallpaperRepository.markServiceStopped()
        notifyUi()

        serviceScope.launch {
            if (WallpaperRepository.shouldRevertToDefault()) {
                applyDefaultWallpaper()
            }
            stopSelf()
        }
    }

    /**
     * Pauses the wallpaper changing by unregistering the ScreenOffReceiver.
     * The foreground service stays alive so it can auto-resume.
     */
    private fun pauseEngine() {
        if (WallpaperRepository.serviceStateFlow.value is ServiceState.Paused) return
        Log.i(tag, "Pausing engine (Power Save ON)")
        WallpaperRepository.markServicePaused()

        screenOffReceiver?.let {
            unregisterReceiver(it)
            screenOffReceiver = null
        }

        serviceScope.launch {
            if (WallpaperRepository.shouldRevertToDefault()) {
                applyDefaultWallpaper()
            }
        }

        updateNotification("Paused (Power Save)")
        notifyUi()
    }

    /**
     * Resumes the wallpaper changing by re-registering the ScreenOffReceiver.
     */
    private fun resumeEngine() {
        if (WallpaperRepository.serviceStateFlow.value !is ServiceState.Paused) return
        Log.i(tag, "Resuming engine (Power Save OFF)")
        WallpaperRepository.markServiceRunning()

        screenOffReceiver = ScreenOffReceiver()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_EXPORTED)

        serviceScope.launch {
            val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
            updateNotification("Cycling: $activeName")
        }
        notifyUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlive = false
        Log.i(tag, "Service Destroyed. Cleaning up.")

        screenOffReceiver?.let { unregisterReceiver(it) }
        systemEventReceiver?.let { unregisterReceiver(it) }
        nothingFocusModeReceiver?.let { unregisterReceiver(it) }

        WallpaperRepository.clearMagazine()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notifyUi() = WallpaperRepository.notifyServiceStateChanged()

    // Notification management TODO: Move to separate class in the future.

    /**
     * Notification builder.
     */
    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, createNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId, 
            "Wallpaper Service Status", 
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent): IBinder? = null
}
