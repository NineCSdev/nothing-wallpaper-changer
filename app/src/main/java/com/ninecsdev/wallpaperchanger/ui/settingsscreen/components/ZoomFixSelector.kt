package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

@Composable
internal fun WallpaperZoomFixSelector(
    selected: WallpaperZoomFix,
    onZoomFixChange: (WallpaperZoomFix) -> Unit
) {
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
        infoDialogTitle = stringResource(R.string.settings_zoom_fix_dialog_title),
        infoDialogBody = stringResource(R.string.settings_zoom_fix_dialog_body)
    )
}

@Preview
@Composable
private fun WallpaperZoomFixSelectorPreview() {
    WallpaperChangerTheme {
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
