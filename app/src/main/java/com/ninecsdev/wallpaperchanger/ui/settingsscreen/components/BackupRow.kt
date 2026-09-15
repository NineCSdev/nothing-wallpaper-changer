package com.ninecsdev.wallpaperchanger.ui.settingsscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.backup.BackupExportState
import com.ninecsdev.wallpaperchanger.ui.components.NothingProgressBar
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * The way into the Backup screen, and the only place either half of backup shows up in Settings.
 *
 * An export that is still running, and an export from a previous run that didn't finish is shown
 * here as they are very essential information for the user.
 */
@Composable
internal fun BackupRow(
    exportState: BackupExportState,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interrupted = exportState is BackupExportState.Interrupted
    val running = exportState as? BackupExportState.Running

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenBackup),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsRowHeader(
                title = stringResource(R.string.settings_backup_title),
                subtitle = when {
                    running != null -> stringResource(R.string.backup_export_running, running.done, running.total)
                    interrupted -> stringResource(R.string.backup_export_interrupted)
                    else -> stringResource(R.string.settings_backup_subtitle)
                },
                modifier = Modifier.weight(1f),
                infoDialogTitle = stringResource(R.string.settings_backup_dialog_title),
                infoDialogBody = stringResource(R.string.settings_backup_dialog_body),
                subtitleColor = if (interrupted) NothingRed else NothingWhite.copy(alpha = 0.4f)
            )

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (interrupted) NothingRed else NothingWhite.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }

        if (running != null) {
            NothingProgressBar(done = running.done, total = running.total)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000,locale="es")
@Composable
private fun BackupRowPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            BackupRow(exportState = BackupExportState.Idle, onOpenBackup = {})
            BackupRow(exportState = BackupExportState.Running(done = 41, total = 120), onOpenBackup = {})
            BackupRow(exportState = BackupExportState.Interrupted(archiveName = null), onOpenBackup = {})
        }
    }
}
