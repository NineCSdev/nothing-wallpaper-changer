package com.ninecsdev.wallpaperchanger.logic

import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.policyOr
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the global policy so a caller holding only a collection can learn which cadence governs it.
 *
 * The rule itself is [policyOr] on the setting, this exists for the fetch.
 */
@Singleton
class RotationPolicyResolver @Inject constructor(
    private val appDataStore: AppDataStore
) {
    suspend fun effectivePolicyFor(collection: WallpaperCollection): RotationPolicy =
        collection.rotationPolicy.policyOr(appDataStore.getRotationPolicy())
}
