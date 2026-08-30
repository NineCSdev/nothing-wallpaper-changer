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
import com.ninecsdev.wallpaperchanger.data.ServiceLifecycleTracker
import com.ninecsdev.wallpaperchanger.data.ServiceStateManager
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.WallpaperApplier
import com.ninecsdev.wallpaperchanger.logic.RotationCoordinator
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereProtocol
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.ServiceState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Inject lateinit var rotationCoordinator: RotationCoordinator

    private var screenOffReceiver: BroadcastReceiver? = null
    private var systemEventReceiver: BroadcastReceiver? = null
    private var atmosphereDisplayedReceiver: BroadcastReceiver? = null
    private var notificationJob: Job? = null
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
        registerReceiver(systemEventReceiver, systemFilter, RECEIVER_NOT_EXPORTED)

        registerAtmosphereDisplayedReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notificationHelper.build(requireNotNull(notificationHelper.textFor(NotificationContent.Initializing)))
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

        if (intent?.action == ACTION_STOP_SERVICE) {
            handleStopCommand()
            return START_NOT_STICKY
        }

        // If the service is still in the middle of its teardown, ignore the re-start
        // to avoid the stop→start race condition where onDestroy clears the new instance's state.
        if (serviceStateManager.rawServiceState.value is ServiceState.Stopping) {
            Log.w(tag, "Start ignored: service is still stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            serviceStateManager.markServiceLoading()

            if (repository.getActiveCollectionOnce() == null) {
                Log.w(tag, "Abort startup: No collection found.")
                serviceStateManager.markServiceStopped()
                handleStopCommand()
                return@launch
            }

            // Battery Saver may already be active on a cold start (e.g. boot restart),
            // so the policy must be evaluated here rather than only on the next transition.
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val policy = appDataStore.getBatterySaverPolicy()

            if (pm.isPowerSaveMode && policy == BatterySaverPolicy.STOP) {
                Log.w(tag, "Abort startup: Battery Saver active with STOP policy.")
                handleStopCommand()
                return@launch
            }

            // Subscribes the engine to the active collection's images and blocks until the first
            // buffer is prepared, so we only mark Running once a wallpaper is ready to apply.
            rotationEngine.start(serviceScope)

            startNotificationCollector()

            if (pm.isPowerSaveMode && policy == BatterySaverPolicy.PAUSE) {
                pauseEngine()
            } else {
                serviceStateManager.markServiceRunning()
            }
        }

        return START_STICKY
    }

    /**
     * Renders the foreground notification as a function of the service's own state and the active
     * collection, so a collection switch or rename shows up without waiting for a restart.
     */
    // TODO tests: see vault note tests/Notification Rendering Tests.md
    private fun startNotificationCollector() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            combine(
                serviceStateManager.rawServiceState,
                repository.activeCollectionFlow()
            ) { state, collection -> notificationContentFor(state, collection) }
                .map { notificationHelper.textFor(it) }
                .distinctUntilChanged()
                .collect { text -> text?.let(notificationHelper::post) }
        }
    }

    private fun handleStopCommand() {
        Log.i(tag, "Stopping service via command.")
        serviceStateManager.markServiceStopping()
        // Belt and braces with the Hidden mapping: no notify() can arrive once teardown starts,
        // which would otherwise outlive stopForeground(STOP_FOREGROUND_REMOVE) as an undismissable
        // notification with no service behind it.
        notificationJob?.cancel()

        serviceScope.launch {
            withContext(NonCancellable) {
                if (appDataStore.shouldRevertToDefault()) {
                    wallpaperApplier.applyDefaultWallpaper()
                }
            }
            stopSelf()
        }
    }

    /**
     * Pauses the wallpaper changing by unregistering the ScreenOffReceiver.
     * The foreground service stays alive so it can auto-resume.
     */
    private fun pauseEngine() {
        if (serviceStateManager.rawServiceState.value is ServiceState.Paused) return
        Log.i(tag, "Pausing engine (Power Save ON)")
        serviceStateManager.markServicePaused()

        unregisterScreenOffReceiver()

        serviceScope.launch {
            withContext(NonCancellable) {
                if (appDataStore.shouldRevertToDefault()) {
                    wallpaperApplier.applyDefaultWallpaper()
                }
            }
        }
    }

    /**
     * Resumes the wallpaper changing by re-registering the ScreenOffReceiver.
     */
    private fun resumeEngine() {
        if (serviceStateManager.rawServiceState.value !is ServiceState.Paused) return
        Log.i(tag, "Resuming engine (Power Save OFF)")
        serviceStateManager.markServiceRunning()

        registerScreenOffReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleTracker.markDead()
        Log.i(tag, "Service Destroyed. Cleaning up.")

        unregisterScreenOffReceiver()
        systemEventReceiver?.let { unregisterReceiver(it) }
        atmosphereDisplayedReceiver?.let { unregisterReceiver(it) }

        // Cancel the scope first so the engine's reactive collector stops before we clear its state.
        serviceScope.cancel()
        rotationEngine.clearMagazine()
        stopForeground(STOP_FOREGROUND_REMOVE)

        serviceStateManager.markServiceStopped()
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return

        val receiver = ScreenOffReceiver(serviceScope)
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        screenOffReceiver = receiver
    }

    /**
     * Listens for the atmosphere engine's "I actually showed the delivered image" signal and hands
     * it to [RotationCoordinator], which owns the deferred second half of that rotation. In
     * atmosphere mode display is asynchronous and may never happen (a fast toggle holds the image),
     * so advancement can't ride along with delivery; it waits for this confirmation instead.
     */
    private fun registerAtmosphereDisplayedReceiver() {
        if (atmosphereDisplayedReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                serviceScope.launch { rotationCoordinator.confirmDeferredDisplay() }
            }
        }
        registerReceiver(
            receiver,
            IntentFilter(AtmosphereProtocol.ACTION_DISPLAYED),
            RECEIVER_NOT_EXPORTED
        )
        atmosphereDisplayedReceiver = receiver
    }

    private fun unregisterScreenOffReceiver() {
        val receiver = screenOffReceiver ?: return
        unregisterReceiver(receiver)
        screenOffReceiver = null
    }

    override fun onBind(intent: Intent): IBinder? = null
}
