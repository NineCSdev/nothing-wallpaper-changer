package com.ninecsdev.wallpaperchanger.logic

import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single authority for "which rotation policy actually governs this collection".
 *
 * A collection either defers to the app-wide policy or carries its own override, and every reader
 * must resolve that the same way — otherwise a timer could wake for a cadence the gate then refuses.
 * Mirrors [WallpaperModeResolver][com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver]
 * for desired-vs-effective mode.
 */
@Singleton
class RotationPolicyResolver @Inject constructor(
    private val appDataStore: AppDataStore
) {
    suspend fun effectivePolicyFor(collection: WallpaperCollection): RotationPolicy =
        resolve(collection.rotationPolicy, appDataStore.getRotationPolicy())

    /** Pure resolution, for callers that already hold both halves (e.g. a combined flow). */
    fun resolve(
        setting: CollectionRotationSetting,
        globalPolicy: RotationPolicy
    ): RotationPolicy = when (setting) {
        CollectionRotationSetting.FollowGlobal -> globalPolicy
        is CollectionRotationSetting.Override -> setting.policy
    }
}
