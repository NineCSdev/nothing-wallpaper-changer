package com.ninecsdev.wallpaperchanger.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt-managed tracker for the [WallpaperService] lifecycle.
 *
 * Note: Replaces the static WallpaperService.isAlive flag, making the dependency
 * explicit and removing coupling between the data layer and the service layer and
 * removing a possible race condition when previously readying the value in onCreate/onDelete.
 *
 * The value resets to `false` on process death (same semantics as the
 * previous static flag) because Hilt singletons are scoped to the process.
 */
@Singleton
class ServiceLifecycleTracker @Inject constructor() {

    private val _isAlive = MutableStateFlow(false)

    /** Whether [WallpaperService] is currently alive (between onCreate and onDestroy). */
    val isAlive: StateFlow<Boolean> = _isAlive.asStateFlow()

    /** Called from [WallpaperService.onCreate]. */
    fun markAlive() {
        _isAlive.value = true
    }

    /** Called from [WallpaperService.onDestroy]. */
    fun markDead() {
        _isAlive.value = false
    }
}
