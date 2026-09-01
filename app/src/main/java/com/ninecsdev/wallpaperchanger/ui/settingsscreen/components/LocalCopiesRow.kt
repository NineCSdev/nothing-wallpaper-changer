package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.ui.components.SettingsToggleRow
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * The "keep local copies" toggle and the space those copies take.
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
    usage: StorageUsage?,
    onCheckedChange: (Boolean) -> Unit,
    onRequestMediaAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
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
            infoDialogBody = stringResource(R.string.settings_keep_local_copies_dialog_body)
        )

        StorageUsageLine(usage = usage)
    }
}

/** [usage] is null until the directory walk finishes; a dash placeholder shows meanwhile. */
@Composable
private fun StorageUsageLine(usage: StorageUsage?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.settings_storage_usage_title),
            style = NothingType.metaLabel,
            color = NothingWhite.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f)
        )

        Text(
            text = usage?.let {
                stringResource(
                    R.string.settings_storage_usage_value,
                    Formatter.formatFileSize(LocalContext.current, it.totalBytes),
                    pluralStringResource(
                        R.plurals.settings_storage_usage_images,
                        it.fileCount,
                        it.fileCount
                    )
                )
            } ?: "—",
            style = NothingType.metaLabel,
            color = NothingWhite.copy(alpha = 0.4f)
        )
    }
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
                usage = StorageUsage(totalBytes = 148_897_792, fileCount = 87),
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
                usage = null,
                onCheckedChange = {},
                onRequestMediaAccess = {}
            )
        }
    }
}
