package com.ninecsdev.wallpaperchanger.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the service lifecycle state machine for the UI and tile consumers.
 *
 * Responsibilities:
 * - Holding the raw in-memory lifecycle intent ([rawServiceState]) set by the service
 * - Deriving a single authoritative [serviceState] flow by reconciling that intent with
 *   the persisted running flag, live service liveness, power-save mode, battery policy,
 *   and active-collection availability
 * - Persisting the running flag to [AppDataStore] on state transitions, and self-healing
 *   a stale-true `service_running` flag (after a crash/kill without onDestroy) so the
 *   resolved [serviceState] does not keep reporting `Running` for a dead service. The
 *   parallel `service_desired` flag is deliberately left untouched — it records the user's
 *   last intent and drives [ServiceRestartReceiver][com.ninecsdev.wallpaperchanger.service.ServiceRestartReceiver].
 */
@Singleton
class ServiceStateManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    dao: WallpaperDao,
    lifecycleTracker: ServiceLifecycleTracker
) {
    private companion object {
        const val TAG = "ServiceStateManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _serviceStateFlow = MutableStateFlow<ServiceState>(ServiceState.Stopped)

    /**
     * Raw in-memory lifecycle intent (Loading / Stopping / Running / Paused / Stopped) set by
     * `WallpaperService`. Exposed only for the service's own lifecycle guards; UI/tile consumers
     * must collect the resolved [serviceState] instead.
     */
    val rawServiceState: StateFlow<ServiceState> = _serviceStateFlow.asStateFlow()

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

    /** Whether power-save mode should block the service, given the user's battery policy. */
    private val powerSaveBlockingFlow: Flow<Boolean> =
        combine(powerSaveModeFlow(), appDataStore.batterySaverPolicyFlow()) { isPowerSave, policy ->
            isPowerSave && policy != BatterySaverPolicy.IGNORE
        }.distinctUntilChanged()

    /**
     * Single authoritative service state. Every UI and tile consumer collects this flow.
     *
     * Kept hot for the process lifetime ([SharingStarted.Eagerly]) so [StateFlow.value] is always
     * current for the tile's synchronous reads, and the single power-save receiver replaces the
     * per-consumer receivers rather than adding one.
     */
    val serviceState: StateFlow<ServiceState> = combine(
        _serviceStateFlow,
        appDataStore.serviceRunningFlow(),
        lifecycleTracker.isAlive,
        powerSaveBlockingFlow,
        dao.observeActiveCollection().map { it != null }.distinctUntilChanged()
    ) { raw, persistedRunning, isAlive, powerSaveBlocking, hasActiveCollection ->
        resolve(raw, persistedRunning, isAlive, powerSaveBlocking, hasActiveCollection)
    }.stateIn(scope, SharingStarted.Eagerly, ServiceState.Stopped)

    init {
        // Self-heal the persisted running flag: if it says "running" but the service is not
        // actually alive (e.g. after a crash/kill without onDestroy), clear it so the resolved
        // serviceState stops reporting Running for a dead service. Only service_running is
        // cleared; service_desired is left intact so a service the user never chose to stop is
        // still restarted on the next boot/update. Idempotent with the normal stop sequence.
        combine(appDataStore.serviceRunningFlow(), lifecycleTracker.isAlive) { persisted, alive ->
            persisted && !alive
        }.distinctUntilChanged()
            .onEach { staleRunning -> if (staleRunning) appDataStore.setServiceRunning(false) }
            .launchIn(scope)
    }

    fun markServiceLoading() {
        updateServiceState(ServiceState.Loading)
    }

    fun markServiceStopping() {
        updateServiceState(ServiceState.Stopping)
    }

    fun markServiceRunning() {
        persistRunningState(true)
        updateServiceState(ServiceState.Running)
    }

    fun markServicePaused() {
        persistRunningState(true)
        updateServiceState(ServiceState.Paused)
    }

    fun markServiceStopped() {
        persistRunningState(false)
        updateServiceState(ServiceState.Stopped)
    }

    /**
     * Pure resolution of the authoritative [ServiceState] from the raw lifecycle intent and the
     * reconciled runtime signals.
     */
    private fun resolve(
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
            raw is ServiceState.Running || raw is ServiceState.Paused ->
                if (isServiceMarkedActive) raw else stoppedState
            raw is ServiceState.Stopped -> stoppedState
            isServiceMarkedActive -> ServiceState.Running
            else -> stoppedState
        }
    }

    private fun updateServiceState(state: ServiceState) {
        if (_serviceStateFlow.value == state) return
        _serviceStateFlow.value = state
        Log.d(TAG, "Service state → $state")
    }

    /**
     * Persists both the running flag (used for UI-state resolution and self-heal) and the
     * desired-intent flag (used by ServiceRestartReceiver). They diverge only when the
     * self-heal above clears a stale `service_running` without touching `service_desired`,
     * so the user's last intent survives an ungraceful kill such as a package replace.
     */
    private fun persistRunningState(isRunning: Boolean) {
        scope.launch {
            appDataStore.setServiceRunning(isRunning)
            appDataStore.setServiceDesired(isRunning)
        }
    }
}
