package com.ninecsdev.wallpaperchanger.ui.settingsscreen

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
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Informational row showing the space used by local copies (internalized wallpapers).
 * [usage] is null until the directory walk finishes; a dash placeholder shows meanwhile.
 */
@Composable
internal fun StorageUsageRow(
    usage: StorageUsage?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_storage_usage_title),
                style = NothingType.metaLabel,
                color = NothingWhite.copy(alpha = 0.4f)
            )
        }

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

@Preview
@Composable
private fun StorageUsageRowPreview() {
    WallpaperChangerTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            StorageUsageRow(
                usage = StorageUsage(totalBytes = 148_897_792, fileCount = 87)
            )
        }
    }
}
