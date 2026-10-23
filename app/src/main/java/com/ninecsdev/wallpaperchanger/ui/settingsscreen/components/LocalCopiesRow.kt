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
import com.ninecsdev.wallpaperchanger.ui.components.SettingsToggleRow
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * The "keep local copies" toggle.
 *
 * Without `READ_MEDIA_IMAGES` every pick is internalized, so the toggle shows the effective
 * (forced-on) value; the stored preference is untouched, and tapping the toggle requests the
 * permission instead of changing the setting. From the partial "selected photos" state that
 * request re-shows the system dialog (select more / allow all).
 */
@Composable
internal fun LocalCopiesRow(
    checked: Boolean,
    hasMediaAccess: Boolean,
    hasPartialMediaAccess: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRequestMediaAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsToggleRow(
        title = stringResource(R.string.settings_keep_local_copies_title),
        subtitle = when {
            hasMediaAccess -> stringResource(R.string.settings_keep_local_copies_subtitle)
            hasPartialMediaAccess -> stringResource(R.string.settings_keep_local_copies_partial_subtitle)
            else -> stringResource(R.string.settings_keep_local_copies_locked_subtitle)
        },
        checked = checked,
        onCheckedChange = { enabled ->
            if (hasMediaAccess) onCheckedChange(enabled) else onRequestMediaAccess()
        },
        infoDialogTitle = stringResource(R.string.settings_keep_local_copies_dialog_title),
        infoDialogBody = stringResource(R.string.settings_keep_local_copies_dialog_body),
        modifier = modifier
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LocalCopiesRowPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            LocalCopiesRow(
                checked = true,
                hasMediaAccess = true,
                hasPartialMediaAccess = false,
                onCheckedChange = {},
                onRequestMediaAccess = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "No media access", backgroundColor = 0xFF000000)
@Composable
private fun LocalCopiesRowLockedPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            LocalCopiesRow(
                checked = true,
                hasMediaAccess = false,
                hasPartialMediaAccess = false,
                onCheckedChange = {},
                onRequestMediaAccess = {}
            )
        }
    }
}
