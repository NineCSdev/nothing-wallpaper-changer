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
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.ServiceLifecycle
import com.ninecsdev.wallpaperchanger.logic.ServiceLifecycleTracker
import com.ninecsdev.wallpaperchanger.logic.WallpaperApplier
import com.ninecsdev.wallpaperchanger.logic.RotationCoordinator
import com.ninecsdev.wallpaperchanger.logic.RotationEngine
import com.ninecsdev.wallpaperchanger.logic.RotationScheduler
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereProtocol
import com.ninecsdev.wallpaperchanger.model.LifecycleVerdict
import com.ninecsdev.wallpaperchanger.model.ServiceIntent
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
 * Foreground Service responsible for keeping the [ScreenStateReceiver] alive,
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
    @Inject lateinit var serviceLifecycle: ServiceLifecycle
    @Inject lateinit var appDataStore: AppDataStore
    @Inject lateinit var rotationCoordinator: RotationCoordinator
    @Inject lateinit var rotationScheduler: RotationScheduler

    private var screenStateReceiver: BroadcastReceiver? = null
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

        startRotationTriggers()

        startLifecycleActionCollector()
        registerAtmosphereDisplayedReceiver()
    }

    /** Obeys the lifecycle authority for as long as the service lives */
    private fun startLifecycleActionCollector() {
        serviceScope.launch { serviceLifecycle.lifecycleActions.collect(::applyLifecycleAction) }
    }

    /** Runs the side effects one verdict calls for. This only touches the rotation triggers and teardown. */
    private fun applyLifecycleAction(action: LifecycleVerdict) {
        when (action) {
            is LifecycleVerdict.RunActive -> {
                Log.i(tag, "Engine active")
                startRotationTriggers()
            }
            // A pause suspends rotation and leaves the screen as it is
            is LifecycleVerdict.RunPaused -> {
                Log.i(tag, "Engine paused")
                stopRotationTriggers()
            }
            is LifecycleVerdict.RunPausedQuiet -> {
                Log.i(tag, "Engine paused (Do Not Disturb)")
                stopRotationTriggers()
            }
            is LifecycleVerdict.Abort -> handleStopCommand()
            else -> Unit
        }
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

        serviceScope.launch {
            val startup = serviceLifecycle.onIntent(
                ServiceIntent.ServiceStarting(
                    // Both facts are read fresh
                    hasActiveCollection = repository.getActiveCollectionOnce() != null,
                    policy = appDataStore.getBatterySaverPolicy()
                )
            )

            when (startup) {
                // Still tearing down
                is LifecycleVerdict.Rejected -> {
                    Log.w(tag, "Start refused: ${startup.reason}")
                    stopSelf()
                    return@launch
                }
                is LifecycleVerdict.Abort -> {
                    Log.w(tag, "Abort startup: nothing to rotate, or Battery Saver forbids it.")
                    handleStopCommand()
                    return@launch
                }
                else -> Unit
            }

            // Subscribes the engine to the active collection's images and blocks until the first
            // buffer is prepared, so we only report Running once a wallpaper is ready to apply
            rotationEngine.start(serviceScope)

            startNotificationCollector()

            // Re-asks as the engine took seconds to come up and Battery Saver may have arrived during them
            applyLifecycleAction(serviceLifecycle.onIntent(ServiceIntent.EngineReady))
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
                serviceLifecycle.lifecycleIntent,
                repository.activeCollectionFlow()
            ) { state, collection -> notificationContentFor(state, collection) }
                .map { notificationHelper.textFor(it) }
                .distinctUntilChanged()
                .collect { text -> text?.let(notificationHelper::post) }
        }
    }

    private fun handleStopCommand() {
        if (serviceLifecycle.onIntent(ServiceIntent.TeardownBegan) is LifecycleVerdict.NoChange) return
        Log.i(tag, "Stopping service via command.")
        // No notify() can arrive once teardown starts, which would otherwise outlive
        // stopForeground(STOP_FOREGROUND_REMOVE) as an undismissable notification with no service behind it
        notificationJob?.cancel()

        serviceScope.launch {
            revertToDefaultIfRequested()
            stopSelf()
        }
    }

    /**
     * Puts the user's chosen wallpaper back when rotation gives up the screen. Non-cancellable so a
     * scope teardown cannot leave the last rotated image standing.
     */
    private suspend fun revertToDefaultIfRequested() {
        withContext(NonCancellable) {
            if (appDataStore.shouldRevertToDefault()) {
                wallpaperApplier.applyDefaultWallpaper(useCollectionOverride = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleTracker.markDead()
        Log.i(tag, "Service Destroyed. Cleaning up.")

        stopRotationTriggers()
        atmosphereDisplayedReceiver?.let { unregisterReceiver(it) }

        // Cancel the scope first so the engine's reactive collector stops before we clear its state.
        serviceScope.cancel()
        rotationEngine.clearMagazine()
        stopForeground(STOP_FOREGROUND_REMOVE)

        serviceLifecycle.onIntent(ServiceIntent.TeardownFinished)
    }

    /** Starts both rotation triggers: the screen-state receiver and the interval scheduler. */
    private fun startRotationTriggers() {
        if (screenStateReceiver != null) return

        val receiver = ScreenStateReceiver(serviceScope)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        screenStateReceiver = receiver

        rotationScheduler.setScreenOn((getSystemService(POWER_SERVICE) as PowerManager).isInteractive)
        rotationScheduler.start(serviceScope)
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

    private fun stopRotationTriggers() {
        rotationScheduler.stop()

        val receiver = screenStateReceiver ?: return
        unregisterReceiver(receiver)
        screenStateReceiver = null
    }

    override fun onBind(intent: Intent): IBinder? = null
}
