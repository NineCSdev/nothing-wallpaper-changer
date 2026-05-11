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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.model.LockscreenZoomFix
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
fun LockscreenZoomFixSelector(
    selected: LockscreenZoomFix,
    onZoomFixChange: (LockscreenZoomFix) -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }

    SettingsSegmentedSelector(
        title = "LOCKSCREEN ZOOM FIX",
        subtitle = "FIXES NOTHING OS WALLPAPER AUTOZOOM",
        options = LockscreenZoomFix.entries,
        selected = selected,
        onOptionChange = onZoomFixChange,
        optionLabel = { zoomFix ->
            when (zoomFix) {
                LockscreenZoomFix.OFF -> "OFF"
                LockscreenZoomFix.BLURRED -> "BLUR"
                LockscreenZoomFix.EDGE -> "EDGE"
            }
        },
        onInfoClick = { showInfo = true }
    )

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = "OK")
                }
            },
            title = {
                Text(text = "Lockscreen zoom fix")
            },
            text = {
                Text(
                    text = "Some phones's OS (such as Nothing) zoom lockscreen wallpapers after they are applied " +
                            "for atmosphere/parallax effect. This adds hidden padding so the zoom crops padding " +
                            "first. Use Blur for most wallpapers or Edge for sharper borders."
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
private fun LockscreenZoomFixSelectorPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            LockscreenZoomFixSelector(
                selected = LockscreenZoomFix.BLURRED,
                onZoomFixChange = {}
            )
        }
    }
}
