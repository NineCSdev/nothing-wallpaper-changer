package com.ninecsdev.wallpaperchanger.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Resets to false on process death (Hilt singletons are process-scoped). Lives next to [ServiceLifecycle], its only reader. */
@Singleton
class ServiceLifecycleTracker @Inject constructor() {

    private val _isAlive = MutableStateFlow(false)

    /** Between `WallpaperService.onCreate` and `onDestroy`. */
    val isAlive: StateFlow<Boolean> = _isAlive.asStateFlow()

    fun markAlive() {
        _isAlive.value = true
    }

    fun markDead() {
        _isAlive.value = false
    }
}
