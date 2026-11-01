package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.ui.graphics.Color
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGreen
import com.ninecsdev.wallpaperchanger.ui.theme.NothingOrange
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

internal fun getVisualsForState(state: ServiceState): Pair<Color, Int> {
    return when (state) {
        is ServiceState.Running -> NothingGreen to R.string.status_label_active
        is ServiceState.Stopped -> NothingRed to R.string.status_label_inactive
        is ServiceState.Stopping -> NothingRed to R.string.status_label_stopping
        is ServiceState.Paused -> NothingOrange to R.string.status_label_paused_battery
        is ServiceState.PausedDnd -> NothingOrange to R.string.status_label_paused_dnd
        is ServiceState.DisabledPowerSave -> NothingOrange to R.string.status_label_power_save_on
        is ServiceState.DisabledNoCollection -> NothingGray to R.string.status_label_setup_needed
        is ServiceState.Loading -> NothingWhite to R.string.status_label_initializing
    }
}
