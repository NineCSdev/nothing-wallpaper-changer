package com.ninecsdev.wallpaperchanger.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val appDataStore: AppDataStore,
    repository: WallpaperRepository
) : ViewModel() {
    val showsSetup: StateFlow<Boolean?> = flow {
        emit(!appDataStore.isSetupSeen() && repository.getAllCollections().first().isEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A restore from the welcome screen brings collections back, which ends the setup. */
    val hasCollections: StateFlow<Boolean> = repository.getAllCollections()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Not saved: process death restarts from the welcome screen, with the choices already written
    private val step = MutableStateFlow(SetupStep.WELCOME)

    val uiState: StateFlow<SetupUiState?> = combine(
        step,
        appDataStore.wallpaperModeFlow(),
        appDataStore.rotationPolicyFlow(),
        appDataStore.wallpaperDestinationFlow(),
        ::SetupUiState
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun goTo(target: SetupStep) {
        step.value = target
    }

    // A fresh install cannot have the engine live, so the plain write never needs the exit path
    fun setWallpaperMode(mode: WallpaperMode) {
        viewModelScope.launch { appDataStore.setWallpaperMode(mode) }
    }

    fun setRotationPolicy(policy: RotationPolicy) {
        viewModelScope.launch { appDataStore.setRotationPolicy(policy) }
    }

    fun setWallpaperDestination(destination: WallpaperDestination) {
        viewModelScope.launch { appDataStore.setWallpaperDestination(destination) }
    }

    /** Written on leaving so a setup interrupted by process death shows again. */
    fun markSeen() {
        viewModelScope.launch { appDataStore.setSetupSeen() }
    }
}
