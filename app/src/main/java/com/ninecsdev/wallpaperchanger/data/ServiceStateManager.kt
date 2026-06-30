package com.ninecsdev.wallpaperchanger.data

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.service.ServiceLifecycleTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the service lifecycle state machine for the UI and tile consumers.
 *
 * Responsibilities:
 * - Holding the in-memory [serviceStateFlow] and [serviceEvent] broadcast
 * - Resolving the authoritative [ServiceState] from in-memory + persisted + live flags
 * - Persisting the running flag to [AppDataStore] on state transitions
 */
@Singleton
class ServiceStateManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val dao: WallpaperDao,
    private val lifecycleTracker: ServiceLifecycleTracker
) {
    private companion object {
        const val TAG = "ServiceStateManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _serviceEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits whenever service state changes. UI and TileService follow this. */
    val serviceEvent: SharedFlow<Unit> = _serviceEvent.asSharedFlow()

    private val _serviceStateFlow = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    /** Current in-memory service state. */
    val serviceStateFlow: StateFlow<ServiceState> = _serviceStateFlow.asStateFlow()

    /**
     * Emits a signal to all UI consumers that service state has changed.
     */
    fun notifyServiceStateChanged() {
        _serviceEvent.tryEmit(Unit)
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
     * Resolves the authoritative [ServiceState] by reconciling:
     * - The current in-memory state
     * - The persisted "was running" flag from DataStore
     * - The live [ServiceLifecycleTracker.isAlive] flag
     * - The system power-save mode
     * - Whether an active collection exists
     */
    suspend fun getServiceState(): ServiceState {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode ?: false
        val hasActiveCollection = withContext(Dispatchers.IO) { dao.getActiveCollection() != null }
        if (!hasActiveCollection) return ServiceState.DisabledNoCollection

        val currentState = _serviceStateFlow.value
        val isPersistedRunning = withContext(Dispatchers.IO) { appDataStore.isServiceRunning() }
        val isServiceAlive = lifecycleTracker.isAlive.value
        val isServiceMarkedActive = isServiceAlive || isPersistedRunning
        val batterySaverPolicy = withContext(Dispatchers.IO) { appDataStore.getBatterySaverPolicy() }
        val stoppedState = if (isPowerSave && batterySaverPolicy != BatterySaverPolicy.IGNORE) ServiceState.DisabledPowerSave else ServiceState.Stopped

        return when {
            currentState is ServiceState.Loading -> ServiceState.Loading
            currentState is ServiceState.Stopping -> ServiceState.Stopping
            currentState is ServiceState.Running || currentState is ServiceState.Paused -> {
                if (isServiceMarkedActive) {
                    currentState
                } else {
                    persistRunningState(false)
                    stoppedState
                }
            }
            currentState is ServiceState.Stopped -> {
                if (isPersistedRunning && !isServiceAlive) {
                    persistRunningState(false)
                }
                stoppedState
            }
            isServiceMarkedActive -> ServiceState.Running
            else -> stoppedState // Should not be reachable, when statement asks for it
        }
    }

    private fun updateServiceState(state: ServiceState) {
        if (_serviceStateFlow.value == state) return
        _serviceStateFlow.value = state
        notifyServiceStateChanged()
        Log.d(TAG, "Service state → $state")
    }

    private fun persistRunningState(isRunning: Boolean) {
        scope.launch { appDataStore.setServiceRunning(isRunning) }
    }
}
