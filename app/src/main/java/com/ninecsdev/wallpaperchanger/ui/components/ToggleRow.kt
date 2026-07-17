package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * A title/subtitle row with a trailing Nothing-styled [Switch]. Shared by the settings screen
 * and the main-screen default-wallpaper card. A disabled row keeps rendering its checked state.
 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowHeader(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = NothingBlack,
                checkedTrackColor = NothingWhite,
                uncheckedThumbColor = NothingWhite,
                uncheckedTrackColor = NothingBlack,
                uncheckedBorderColor = NothingWhite.copy(alpha = 0.5f),
                disabledCheckedThumbColor = NothingBlack,
                disabledCheckedTrackColor = NothingWhite.copy(alpha = 0.5f)
            )
        )
    }
}

@Preview
@Composable
private fun SettingsToggleRowPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            SettingsToggleRow(
                title = "TOGGLE TITLE",
                subtitle = "Toggle subtitle",
                checked = true,
                onCheckedChange = {}
            )
        }
    }
}

@Preview
@Composable
private fun SettingsToggleRowDisabledPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            SettingsToggleRow(
                title = "TOGGLE TITLE",
                subtitle = "Toggle subtitle",
                checked = true,
                enabled = false,
                onCheckedChange = {}
            )
        }
    }
}