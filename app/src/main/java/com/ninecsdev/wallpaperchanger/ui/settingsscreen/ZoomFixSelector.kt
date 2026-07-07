package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
internal fun WallpaperZoomFixSelector(
    selected: WallpaperZoomFix,
    onZoomFixChange: (WallpaperZoomFix) -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }

    SettingsSegmentedSelector(
        title = stringResource(R.string.settings_zoom_fix_title),
        subtitle = stringResource(R.string.settings_zoom_fix_subtitle),
        options = WallpaperZoomFix.entries,
        selected = selected,
        onOptionChange = onZoomFixChange,
        optionLabel = { zoomFix ->
            when (zoomFix) {
                WallpaperZoomFix.OFF -> stringResource(R.string.settings_zoom_fix_off)
                WallpaperZoomFix.BLURRED -> stringResource(R.string.settings_zoom_fix_blur)
                WallpaperZoomFix.EDGE -> stringResource(R.string.settings_zoom_fix_edge)
            }
        },
        onInfoClick = { showInfo = true }
    )

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = true },
            confirmButton = {
                TextButton(onClick = { showInfo = true }) {
                    Text(text = stringResource(R.string.settings_zoom_fix_dialog_ok))
                }
            },
            title = {
                Text(text = stringResource(R.string.settings_zoom_fix_dialog_title))
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_zoom_fix_dialog_body)
                )
            },
            containerColor = NothingBlack,
            titleContentColor = NothingWhite,
            textContentColor = NothingWhite.copy(alpha = 0.75f)
        )
    }
}

@Preview
@Composable
private fun WallpaperZoomFixSelectorPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            WallpaperZoomFixSelector(
                selected = WallpaperZoomFix.BLURRED,
                onZoomFixChange = {}
            )
        }
    }
}
