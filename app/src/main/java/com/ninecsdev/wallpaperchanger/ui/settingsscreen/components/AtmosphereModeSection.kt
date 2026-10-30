package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.components.SettingsToggleRow
import com.ninecsdev.wallpaperchanger.ui.components.StatusLed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGreen
import com.ninecsdev.wallpaperchanger.ui.theme.NothingOrange
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * The toggle is a boolean view of [WallpaperMode]; flipping it off while the engine is live is the deliberate exit path.
 * While atmosphere is desired but [engineActive] is false, the block surfaces the mismatch and offers the set-button;
 * a null [engineActive] means not yet checked and shows neither.
 */
@Composable
internal fun AtmosphereModeSection(
    selectedMode: WallpaperMode,
    engineActive: Boolean?,
    hasSource: Boolean,
    onModeChange: (WallpaperMode) -> Unit,
    onSetAtmosphere: () -> Unit
) {
    Column {
        SettingsToggleRow(
            title = stringResource(R.string.settings_wallpaper_mode_title),
            subtitle = stringResource(R.string.settings_wallpaper_mode_subtitle),
            checked = selectedMode == WallpaperMode.ATMOSPHERE,
            onCheckedChange = { checked ->
                onModeChange(if (checked) WallpaperMode.ATMOSPHERE else WallpaperMode.STATIC)
            },
            infoDialogTitle = stringResource(R.string.settings_wallpaper_mode_dialog_title),
            infoDialogBody = stringResource(R.string.settings_wallpaper_mode_dialog_body)
        )

        // Held back until liveness is known
        if (selectedMode == WallpaperMode.ATMOSPHERE && engineActive != null) {
            Spacer(modifier = Modifier.height(2.dp))

            if (engineActive) {
                StatusRow(
                    dotColor = NothingGreen,
                    text = stringResource(R.string.settings_atmosphere_active)
                )
            } else {
                StatusRow(
                    dotColor = NothingOrange,
                    text = stringResource(R.string.settings_atmosphere_not_set)
                )

                Spacer(modifier = Modifier.height(14.dp))

                NothingButton(
                    text = stringResource(R.string.settings_atmosphere_set_button),
                    onClick = onSetAtmosphere,
                    enabled = hasSource,
                    variant = NothingButtonVariant.PRIMARY
                )

                if (!hasSource) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_atmosphere_no_source_hint),
                        style = NothingType.caption,
                        color = NothingWhite.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    dotColor: Color,
    text: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLed(color = dotColor, isPulsing = false, modifier = Modifier.size(8.dp))
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = text,
            style = NothingType.caption,
            color = NothingWhite.copy(alpha = 0.7f)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AtmosphereModeSectionMismatchPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            AtmosphereModeSection(
                selectedMode = WallpaperMode.ATMOSPHERE,
                engineActive = false,
                hasSource = true,
                onModeChange = {},
                onSetAtmosphere = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Active", backgroundColor = 0xFF000000)
@Composable
private fun AtmosphereModeSectionActivePreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            AtmosphereModeSection(
                selectedMode = WallpaperMode.ATMOSPHERE,
                engineActive = true,
                hasSource = true,
                onModeChange = {},
                onSetAtmosphere = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "Active", backgroundColor = 0xFF000000)
@Composable
private fun AtmosphereModeSectionNoSourcePreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            AtmosphereModeSection(
                selectedMode = WallpaperMode.ATMOSPHERE,
                engineActive = false,
                hasSource = false,
                onModeChange = {},
                onSetAtmosphere = {}
            )
        }
    }
}
