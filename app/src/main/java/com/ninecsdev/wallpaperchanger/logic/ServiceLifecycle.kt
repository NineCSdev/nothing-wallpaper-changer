package com.ninecsdev.wallpaperchanger.logic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.model.LifecycleVerdict
import com.ninecsdev.wallpaperchanger.model.ServiceIntent
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.service.WallpaperService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * The single authority on the rotation service's lifecycle: callers state a [ServiceIntent] and obey
 * the [LifecycleVerdict]. Also persists the running flag and self-heals a stale-true `service_running`
 * (crash/kill without onDestroy). `service_desired` is left untouched: it records the user's last
 * intent and drives the restart receiver.
 */
@Singleton
class ServiceLifecycle @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    dao: WallpaperDao,
    private val lifecycleTracker: ServiceLifecycleTracker
) {
    private companion object {
        const val TAG = "ServiceLifecycle"

        /** How long an optimistic `Loading` may stand before it is cleared. */
        const val START_TIMEOUT_MS = 5_000L
        const val REJECT_STILL_STOPPING = "service is still stopping"

        /** The system-wide DND level. Zero is off; other values are partial filters we count as DND. */
        const val ZEN_MODE_SETTING = "zen_mode"
        const val ZEN_MODE_OFF = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _lifecycleIntent = MutableStateFlow<ServiceState>(ServiceState.Stopped)

    /** Raw and unreconciled: only the foreground notification renders this; every other consumer collects [serviceState]. */
    val lifecycleIntent: StateFlow<ServiceState> = _lifecycleIntent.asStateFlow()

    private var startTimeoutJob: Job? = null

    private fun powerSaveModeFlow(): Flow<Boolean> = callbackFlow {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(powerManager?.isPowerSaveMode ?: false)
            }
        }
        appContext.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
        trySend(powerManager?.isPowerSaveMode ?: false)
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun dndActiveFlow(): Flow<Boolean> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(isDndActive())
            }
        }
        appContext.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ZEN_MODE_SETTING),
            false,
            observer
        )
        trySend(isDndActive())
        awaitClose { appContext.contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private fun isDndActive(): Boolean = Settings.Global.getInt(appContext.contentResolver, ZEN_MODE_SETTING, ZEN_MODE_OFF) != ZEN_MODE_OFF

    /** [battery] is kept separately because only the power-save half can also block a *stopped* service from starting. */
    private data class LifecycleRules(
        val battery: LifecycleVerdict,
        val effective: LifecycleVerdict
    )

    /**
     * The only subscription to the power-save broadcast and the DND setting, and the only place either
     * rule is evaluated. Null means "not resolved yet": consumers that must not act on a guess filter it out.
     */
    private val rules: StateFlow<LifecycleRules?> = combine(
        powerSaveModeFlow(),
        appDataStore.batterySaverPolicyFlow(),
        dndActiveFlow(),
        appDataStore.skipOnDndFlow()
    ) { isPowerSave, policy, isDnd, skipOnDnd ->
        LifecycleRules(
            battery = batterySaverAction(isPowerSave, policy),
            effective = lifecycleAction(isPowerSave, policy, isDnd, skipOnDnd)
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /** Transitions only (the first value is dropped), collected for as long as the service lives. */
    val lifecycleActions: Flow<LifecycleVerdict> =
        rules.filterNotNull()
            .map { it.effective }
            .distinctUntilChanged()
            .drop(1)
            .onEach(::recordAction)

    private val powerSaveBlockingFlow: Flow<Boolean> =
        rules.filterNotNull()
            .map { it.battery != LifecycleVerdict.RunActive }
            .distinctUntilChanged()

    /**
     * Null means "not resolved yet"; consumers that must not act on a guess filter it out.
     * Hot for the process lifetime so [StateFlow.value] is current for the tile's reads.
     */
    val serviceState: StateFlow<ServiceState?> = combine(
        _lifecycleIntent,
        appDataStore.serviceRunningFlow(),
        lifecycleTracker.isAlive,
        powerSaveBlockingFlow,
        dao.observeActiveCollection().map { it != null }.distinctUntilChanged()
    ) { raw, persistedRunning, isAlive, powerSaveBlocking, hasActiveCollection ->
        resolve(raw, persistedRunning, isAlive, powerSaveBlocking, hasActiveCollection)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Clears a stale persisted "running" (crash/kill without onDestroy); idempotent with the normal stop
        combine(appDataStore.serviceRunningFlow(), lifecycleTracker.isAlive) { persisted, alive ->
            persisted && !alive
        }.distinctUntilChanged()
            .onEach { staleRunning -> if (staleRunning) appDataStore.setServiceRunning(false) }
            .launchIn(scope)
    }

    /** The facts that must not come from a possibly stale flow are read fresh by the caller and passed in with [ServiceIntent.ServiceStarting]. */
    // TODO tests: see vault note tests/Service Lifecycle Intent Tests.md
    fun onIntent(intent: ServiceIntent): LifecycleVerdict {
        val verdict = judge(intent)
        Log.d(TAG, "$intent → $verdict")
        return verdict
    }

    /** Unlike [ServiceIntent.ActiveCollectionDeleted] this actually stops the service (needed for the backup import). No-op if it is dead. */
    // TODO: Breaks this class contract but I need to think more about it
    fun requestStop() {
        if (!lifecycleTracker.isAlive.value) return
        try {
            appContext.startService(
                Intent(appContext, WallpaperService::class.java).apply {
                    action = WallpaperService.ACTION_STOP_SERVICE
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not command the service to stop", e)
        }
    }

    private fun judge(intent: ServiceIntent): LifecycleVerdict = when (intent) {
        is ServiceIntent.StartRequested -> onStartRequested()
        is ServiceIntent.ServiceStarting -> onServiceStarting(intent.hasActiveCollection, intent.policy)
        is ServiceIntent.EngineReady -> onEngineReady()
        is ServiceIntent.TeardownBegan -> onTeardownBegan()
        is ServiceIntent.TeardownFinished -> {
            persistRunningState(false)
            updateLifecycleIntent(ServiceState.Stopped)
            LifecycleVerdict.Accepted
        }
        is ServiceIntent.ActiveCollectionDeleted -> {
            // Clears the user's standing intent only
            scope.launch { appDataStore.setServiceDesired(false) }
            LifecycleVerdict.Accepted
        }
    }

    /** Shows `Loading` immediately so the tap registers, and arms the timeout that clears it if the service never arrives. */
    private fun onStartRequested(): LifecycleVerdict {
        if (_lifecycleIntent.value is ServiceState.Stopping) return LifecycleVerdict.Rejected(REJECT_STILL_STOPPING)
        if (lifecycleTracker.isAlive.value) return LifecycleVerdict.NoChange

        updateLifecycleIntent(ServiceState.Loading)
        armStartTimeout()
        return LifecycleVerdict.Accepted
    }

    /** Refuses a start that overlaps a teardown, and applies the battery-saver rule to a service coming up while the saver is on. */
    private fun onServiceStarting(
        hasActiveCollection: Boolean,
        policy: BatterySaverPolicy
    ): LifecycleVerdict {
        if (_lifecycleIntent.value is ServiceState.Stopping) return LifecycleVerdict.Rejected(REJECT_STILL_STOPPING)
        if (!hasActiveCollection) return LifecycleVerdict.Abort

        val isPowerSave = (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager) ?.isPowerSaveMode ?: false
        val action = batterySaverAction(isPowerSave, policy)
        if (action is LifecycleVerdict.Abort) return action

        updateLifecycleIntent(ServiceState.Loading)
        return action
    }

    /** Re-reads the rule rather than trusting the startup verdict. */
    private fun onEngineReady(): LifecycleVerdict {
        // Falls back to RunActive only if the rule has still not resolved
        val action = rules.value?.effective ?: LifecycleVerdict.RunActive
        recordAction(action)
        return action
    }

    private fun recordAction(action: LifecycleVerdict) {
        when (action) {
            is LifecycleVerdict.RunPaused -> {
                persistRunningState(true)
                updateLifecycleIntent(ServiceState.Paused)
            }
            is LifecycleVerdict.RunPausedQuiet -> {
                persistRunningState(true)
                updateLifecycleIntent(ServiceState.PausedDnd)
            }
            is LifecycleVerdict.RunActive -> {
                persistRunningState(true)
                updateLifecycleIntent(ServiceState.Running)
            }
            // Abort: the caller tears down, which arrives back here as TeardownBegan
            else -> Unit
        }
    }

    private fun onTeardownBegan(): LifecycleVerdict {
        if (_lifecycleIntent.value is ServiceState.Stopping) return LifecycleVerdict.NoChange
        updateLifecycleIntent(ServiceState.Stopping)
        return LifecycleVerdict.Accepted
    }

    /** Clears an optimistic `Loading` if the service it was promising never comes alive. */
    private fun armStartTimeout() {
        startTimeoutJob?.cancel()
        startTimeoutJob = scope.launch {
            val started = withTimeoutOrNull(START_TIMEOUT_MS.milliseconds) {
                lifecycleTracker.isAlive.first { it }
            }
            if (started == null && _lifecycleIntent.value is ServiceState.Loading) {
                Log.w(TAG, "Service never started within ${START_TIMEOUT_MS}ms. Clearing Loading.")
                updateLifecycleIntent(ServiceState.Stopped)
            }
        }
    }

    private fun updateLifecycleIntent(state: ServiceState) {
        if (_lifecycleIntent.value == state) return
        _lifecycleIntent.value = state
        Log.d(TAG, "Service state → $state")
    }

    /** Running and desired diverge only when the init self-heal clears a stale `service_running`. */
    private fun persistRunningState(isRunning: Boolean) {
        scope.launch {
            appDataStore.setServiceRunning(isRunning)
            appDataStore.setServiceDesired(isRunning)
        }
    }
}

internal fun batterySaverAction(
    isPowerSave: Boolean,
    policy: BatterySaverPolicy
): LifecycleVerdict = when {
    !isPowerSave -> LifecycleVerdict.RunActive
    policy == BatterySaverPolicy.IGNORE -> LifecycleVerdict.RunActive
    policy == BatterySaverPolicy.PAUSE -> LifecycleVerdict.RunPaused
    else -> LifecycleVerdict.Abort
}

internal fun quietHoursAction(
    isDnd: Boolean,
    skipOnDnd: Boolean
): LifecycleVerdict = if (isDnd && skipOnDnd) LifecycleVerdict.RunPausedQuiet else LifecycleVerdict.RunActive

/** A battery-saver verdict other than [LifecycleVerdict.RunActive] wins outright; DND only decides when battery saver has nothing to say. */
internal fun lifecycleAction(
    isPowerSave: Boolean,
    policy: BatterySaverPolicy,
    isDnd: Boolean,
    skipOnDnd: Boolean
): LifecycleVerdict {
    val battery = batterySaverAction(isPowerSave, policy)
    return if (battery != LifecycleVerdict.RunActive) battery else quietHoursAction(isDnd, skipOnDnd)
}

/** Liveness signals only ever demote: they strip a running claim the service no longer backs, never raise a stopped one. */
internal fun resolve(
    raw: ServiceState,
    persistedRunning: Boolean,
    isAlive: Boolean,
    powerSaveBlocking: Boolean,
    hasActiveCollection: Boolean
): ServiceState {
    if (!hasActiveCollection) return ServiceState.DisabledNoCollection

    val isServiceMarkedActive = isAlive || persistedRunning
    val stoppedState = if (powerSaveBlocking) ServiceState.DisabledPowerSave else ServiceState.Stopped

    return when {
        raw is ServiceState.Loading -> ServiceState.Loading
        raw is ServiceState.Stopping -> ServiceState.Stopping
        raw is ServiceState.Running || raw is ServiceState.Paused || raw is ServiceState.PausedDnd -> if (isServiceMarkedActive) raw else stoppedState
        else -> stoppedState
    }
}
