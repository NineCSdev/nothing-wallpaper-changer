package com.ninecsdev.wallpaperchanger.ui.setupscreen

import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode

enum class SetupStep { WELCOME, LOOK, ROTATION, DESTINATION }

data class SetupUiState(
    val step: SetupStep,
    val wallpaperMode: WallpaperMode,
    val rotationPolicy: RotationPolicy,
    val wallpaperDestination: WallpaperDestination
) {
    /** Atmosphere always covers both screens, so it has no destination to ask about. */
    val decisions: List<SetupStep>
        get() = listOfNotNull(
            SetupStep.LOOK,
            SetupStep.ROTATION,
            SetupStep.DESTINATION.takeIf { wallpaperMode == WallpaperMode.STATIC }
        )

    private val steps: List<SetupStep> get() = listOf(SetupStep.WELCOME) + decisions

    val isLastDecision: Boolean get() = step == decisions.last()

    val nextStep: SetupStep? get() = steps.getOrNull(steps.indexOf(step) + 1)

    val previousStep: SetupStep? get() = steps.getOrNull(steps.indexOf(step) - 1)
}
