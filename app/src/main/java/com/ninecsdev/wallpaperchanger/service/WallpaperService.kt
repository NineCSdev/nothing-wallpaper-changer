package com.ninecsdev.wallpaperchanger.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.ServiceStateManager
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.WallpaperApplier
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.ServiceState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground Service responsible for keeping the [ScreenOffReceiver] alive,
 * coordinating the whole app and creating and managing the notification.
 */
@AndroidEntryPoint
class WallpaperService : Service() {

    private val tag = "WallpaperService"
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var repository: WallpaperRepository
    @Inject lateinit var rotationEngine: RotationEngine
    @Inject lateinit var wallpaperApplier: WallpaperApplier
    @Inject lateinit var lifecycleTracker: ServiceLifecycleTracker
    @Inject lateinit var serviceStateManager: ServiceStateManager
    @Inject lateinit var appDataStore: AppDataStore

    private var screenOffReceiver: BroadcastReceiver? = null
    private var systemEventReceiver: BroadcastReceiver? = null
    // SupervisorJob ensures one failing task doesn't kill the whole service scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_STOP_SERVICE = "com.ninecsdev.wallpaperchanger.ACTION_STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleTracker.markAlive()
        Log.d(tag, "Service Created")
        notificationHelper.createChannel()

        registerScreenOffReceiver()

        systemEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = context.getSystemService(POWER_SERVICE) as PowerManager

                serviceScope.launch {
                    val policy = appDataStore.getBatterySaverPolicy()

                    if (pm.isPowerSaveMode) {
                        when (policy) {
                            BatterySaverPolicy.STOP -> handleStopCommand()
                            BatterySaverPolicy.PAUSE -> pauseEngine()
                            BatterySaverPolicy.IGNORE -> { /* keep running normally */ }
                        }
                    } else {
                        // Only resume if we were paused by battery saver
                        if (policy == BatterySaverPolicy.PAUSE) {
                            resumeEngine()
                        }
                    }
                }
            }
        }
        val systemFilter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        registerReceiver(systemEventReceiver, systemFilter, RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            handleStopCommand()
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notificationHelper.buildInitializingNotification()
            )
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
            serviceStateManager.markServiceLoading()
            notifyUi()

            val state = serviceStateManager.getServiceState()

            if (state !is ServiceState.DisabledNoCollection){
                rotationEngine.loadMagazine()
                rotationEngine.refillDiskBuffer()

                serviceStateManager.markServiceRunning()
                notifyUi()

                val activeName = repository.getActiveCollectionOnce()?.name
                notificationHelper.showCycling(activeName)
            }else{
                Log.w(tag, "Abort startup: No collection found.")
                serviceStateManager.markServiceStopped()
                handleStopCommand()
            }
        }

        return START_STICKY
    }

    private fun handleStopCommand() {
        Log.i(tag, "Stopping service via command.")
        serviceStateManager.markServiceStopped()
        notifyUi()

        serviceScope.launch {
            if (appDataStore.shouldRevertToDefault()) {
                wallpaperApplier.applyDefaultWallpaper()
            }
            stopSelf()
        }
    }

    /**
     * Pauses the wallpaper changing by unregistering the ScreenOffReceiver.
     * The foreground service stays alive so it can auto-resume.
     */
    private fun pauseEngine() {
        if (serviceStateManager.serviceStateFlow.value is ServiceState.Paused) return
        Log.i(tag, "Pausing engine (Power Save ON)")
        serviceStateManager.markServicePaused()

        unregisterScreenOffReceiver()

        serviceScope.launch {
            if (appDataStore.shouldRevertToDefault()) {
                wallpaperApplier.applyDefaultWallpaper()
            }
        }

        notificationHelper.showPausedPowerSave()
        notifyUi()
    }

    /**
     * Resumes the wallpaper changing by re-registering the ScreenOffReceiver.
     */
    private fun resumeEngine() {
        if (serviceStateManager.serviceStateFlow.value !is ServiceState.Paused) return
        Log.i(tag, "Resuming engine (Power Save OFF)")
        serviceStateManager.markServiceRunning()

        registerScreenOffReceiver()

        serviceScope.launch {
            val activeName = repository.getActiveCollectionOnce()?.name
            notificationHelper.showCycling(activeName)
        }
        notifyUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleTracker.markDead()
        Log.i(tag, "Service Destroyed. Cleaning up.")

        unregisterScreenOffReceiver()
        systemEventReceiver?.let { unregisterReceiver(it) }

        rotationEngine.clearMagazine()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notifyUi() = serviceStateManager.notifyServiceStateChanged()

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return

        val receiver = ScreenOffReceiver()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        receiver.serviceScope = serviceScope
        screenOffReceiver = receiver
    }

    private fun unregisterScreenOffReceiver() {
        val receiver = screenOffReceiver ?: return
        unregisterReceiver(receiver)
        screenOffReceiver = null
    }

    override fun onBind(intent: Intent): IBinder? = null
}
