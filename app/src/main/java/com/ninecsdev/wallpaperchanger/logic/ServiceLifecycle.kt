package com.ninecsdev.wallpaperchanger.logic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.model.LifecycleVerdict
import com.ninecsdev.wallpaperchanger.model.ServiceIntent
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
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

/**
 * The single authority on the rotation service's lifecycle: what state it is in, and which
 * transitions are legal from there.
 *
 * Callers state a [ServiceIntent] and obey the [LifecycleVerdict].
 *
 * Responsibilities:
 * - Judging intents against the current state ([onIntent])
 * - Deriving the authoritative [serviceState]
 * - Emitting [lifecycleActions] when the battery-saver decision changes under a live service
 * - Persisting the running flag on transitions, and self-healing a stale-true `service_running`
 *   flag (after a crash/kill without onDestroy). `service_desired` flag is deliberately left
 *   untouched (records the user's last intent and drives [ServiceRestartReceiver][com.ninecsdev.wallpaperchanger.service.ServiceRestartReceiver]).
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _lifecycleIntent = MutableStateFlow<ServiceState>(ServiceState.Stopped)

    /**
     * Raw lifecycle intent, unreconciled. Exposed for the foreground notification, which renders
     * the service's own view of itself; every other consumer collects [serviceState].
     */
    val lifecycleIntent: StateFlow<ServiceState> = _lifecycleIntent.asStateFlow()

    private var startTimeoutJob: Job? = null

    /** Reactive view of `PowerManager.isPowerSaveMode`, backed by the system broadcast. */
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

    /**
     * What the battery-saver rule currently says, as one hot value.
     *
     * This is the only subscription to the power-save broadcast in the app, and the only place the
     * rule is evaluated: the running-service action ([lifecycleActions]), the startup gate and the
     * `DisabledPowerSave` state all read [batterySaverAction].
     *
     * `null` means "not resolved yet". Consumers that must not act on a guess filter it out.
     */
    private val powerSaveAction: StateFlow<LifecycleVerdict?> =
        combine(powerSaveModeFlow(), appDataStore.batterySaverPolicyFlow()) { isPowerSave, policy ->
            batterySaverAction(isPowerSave, policy)
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * What a live service should do when the battery-saver decision changes under it.
     *
     * Transitions only: the first value is dropped. Collected for as long as the service lives.
     */
    val lifecycleActions: Flow<LifecycleVerdict> = powerSaveAction.filterNotNull().drop(1).onEach(::recordAction)

    /** Whether the battery-saver rule is currently blocking the service in any way. */
    private val powerSaveBlockingFlow: Flow<Boolean> =
        powerSaveAction.filterNotNull()
            .map { it != LifecycleVerdict.RunActive }
            .distinctUntilChanged()

    /**
     * Single authoritative service state. Every UI and tile consumer collects this flow.
     *
     * Kept hot for the process lifetime ([SharingStarted.Eagerly]) so [StateFlow.value] is always
     * current for the tile's synchronous reads.
     */
    val serviceState: StateFlow<ServiceState> = combine(
        _lifecycleIntent,
        appDataStore.serviceRunningFlow(),
        lifecycleTracker.isAlive,
        powerSaveBlockingFlow,
        dao.observeActiveCollection().map { it != null }.distinctUntilChanged()
    ) { raw, persistedRunning, isAlive, powerSaveBlocking, hasActiveCollection ->
        resolve(raw, persistedRunning, isAlive, powerSaveBlocking, hasActiveCollection)
    }.stateIn(scope, SharingStarted.Eagerly, ServiceState.Stopped)

    init {
        // Self-heal the persisted running flag: if it says "running" but the service is not actually alive
        // (e.g. after a crash/kill without onDestroy), clear it. Idempotent with the normal stop sequence.
        combine(appDataStore.serviceRunningFlow(), lifecycleTracker.isAlive) { persisted, alive ->
            persisted && !alive
        }.distinctUntilChanged()
            .onEach { staleRunning -> if (staleRunning) appDataStore.setServiceRunning(false) }
            .launchIn(scope)
    }

    /**
     * Judges one intent and moves the state if it is legal.
     *
     * The two facts that must not come from a possibly-seed flow  are read
     * fresh by the caller and passed in with [ServiceIntent.ServiceStarting].
     */
    // TODO tests: see vault note tests/Service Lifecycle Intent Tests.md
    fun onIntent(intent: ServiceIntent): LifecycleVerdict {
        val verdict = judge(intent)
        Log.d(TAG, "$intent → $verdict")
        return verdict
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

    /**
     * A surface asked for the service. Shows `Loading` immediately so the tap registers visibly,
     * and arms the timeout that clears it if the service never arrives.
     */
    private fun onStartRequested(): LifecycleVerdict {
        if (_lifecycleIntent.value is ServiceState.Stopping) return LifecycleVerdict.Rejected(REJECT_STILL_STOPPING)
        if (lifecycleTracker.isAlive.value) return LifecycleVerdict.NoChange

        updateLifecycleIntent(ServiceState.Loading)
        armStartTimeout()
        return LifecycleVerdict.Accepted
    }

    /**
     * The startup gate. Refuses a start that overlaps a teardown, and applies the battery-saver
     * rule to a service that may be coming up while the saver is already on.
     */
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

    /**
     * The engine settled into the mode it was told to run in. Re-reads the rule rather than
     * trusting the startup verdict.
     */
    private fun onEngineReady(): LifecycleVerdict {
        // Falls back to RunActive only if the rule has still not resolved
        val action = powerSaveAction.value ?: LifecycleVerdict.RunActive
        recordAction(action)
        return action
    }

    /** Moves the state to whatever a running-or-starting verdict implies. */
    private fun recordAction(action: LifecycleVerdict) {
        when (action) {
            is LifecycleVerdict.RunPaused -> {
                persistRunningState(true)
                updateLifecycleIntent(ServiceState.Paused)
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

    /** The battery-saver rule, in one place. */
    internal fun batterySaverAction(
        isPowerSave: Boolean,
        policy: BatterySaverPolicy
    ): LifecycleVerdict = when {
        !isPowerSave -> LifecycleVerdict.RunActive
        policy == BatterySaverPolicy.IGNORE -> LifecycleVerdict.RunActive
        policy == BatterySaverPolicy.PAUSE -> LifecycleVerdict.RunPaused
        else -> LifecycleVerdict.Abort
    }

    /** Pure resolution of the authoritative [ServiceState] from the lifecycle intent and the runtime signals. */
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
            raw is ServiceState.Running || raw is ServiceState.Paused -> if (isServiceMarkedActive) raw else stoppedState
            raw is ServiceState.Stopped -> stoppedState
            isServiceMarkedActive -> ServiceState.Running
            else -> stoppedState
        }
    }

    /** Clears an optimistic `Loading` if the service it was promising never comes alive. */
    private fun armStartTimeout() {
        startTimeoutJob?.cancel()
        startTimeoutJob = scope.launch {
            val started = withTimeoutOrNull(START_TIMEOUT_MS) {
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

    /**
     * Persists both the running flag (used for UI-state resolution and self-heal) and the
     * desired-intent flag (used by ServiceRestartReceiver). They diverge only when the
     * self-heal above clears a stale `service_running`
     */
    private fun persistRunningState(isRunning: Boolean) {
        scope.launch {
            appDataStore.setServiceRunning(isRunning)
            appDataStore.setServiceDesired(isRunning)
        }
    }
}
