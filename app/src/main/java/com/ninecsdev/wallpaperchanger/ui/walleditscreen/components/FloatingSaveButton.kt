package com.ninecsdev.wallpaperchanger.ui.walleditscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Floating circular save button anchored to the bottom-center of the editor.
 * Visible only when the controls panel is hidden and there are unsaved changes.
 */
@Composable
internal fun FloatingSaveButton(
    onSave: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) NothingWhite else NothingWhite.copy(alpha = 0.2f))
        ) {
            Icon(
                painter = painterResource(R.drawable.icon_save),
                contentDescription = stringResource(R.string.cd_save),
                tint = if (enabled) NothingBlack else NothingWhite.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Preview(name = "Floating Save Button – Enabled", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun FloatingSaveButtonEnabledPreview() {
    WallpaperChangerTheme {
        FloatingSaveButton(
            onSave = {},
            enabled = true,
        )
    }
}

@Preview(name = "Floating Save Button – Disabled", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun FloatingSaveButtonDisabledPreview() {
    WallpaperChangerTheme {
        FloatingSaveButton(
            onSave = {},
            enabled = false,
        )
    }
}
