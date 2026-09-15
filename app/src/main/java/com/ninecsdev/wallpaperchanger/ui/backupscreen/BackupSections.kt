package com.ninecsdev.wallpaperchanger.ui.backupscreen

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.backup.BackupExportState
import com.ninecsdev.wallpaperchanger.data.backup.BackupExportSummary
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.components.NothingProgressBar
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.components.SettingsToggleRow
import com.ninecsdev.wallpaperchanger.ui.components.SettingsSection
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * The export half: what an archive is, whether every image goes into it, and the button that asks
 * the user where to put it.
 *
 * The option sits directly above the button it changes, and the button is the primary one on the
 * screen: writing a backup is what the user came here to do.
 *
 * While an export is running the button is replaced by its progress, as the work outlives this
 * screen leaving and coming back finds it still going.
 */
@Composable
internal fun BackupExportSection(
    portable: Boolean,
    exportState: BackupExportState,
    onPortableChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSection(
        label = stringResource(R.string.backup_section_export),
        modifier = modifier,
        showDivider = false
    ) {
        SettingsRowHeader(
            title = stringResource(R.string.backup_export_title),
            subtitle = stringResource(R.string.backup_export_subtitle),
            infoDialogTitle = stringResource(R.string.backup_export_dialog_title),
            infoDialogBody = stringResource(R.string.backup_export_dialog_body)
        )

        SettingsToggleRow(
            title = stringResource(R.string.backup_export_portable_title),
            subtitle = stringResource(R.string.backup_export_portable_subtitle),
            checked = portable,
            onCheckedChange = onPortableChange,
            enabled = exportState !is BackupExportState.Running
        )

        when (exportState) {
            is BackupExportState.Running -> ExportProgress(exportState, onCancel)
            is BackupExportState.Finished -> ExportOutcome(exportState.summary, onAcknowledge)
            is BackupExportState.Failed -> ExportFailure(onAcknowledge)
            is BackupExportState.Interrupted -> ExportInterrupted(exportState.archiveName, onExport, onAcknowledge)
            BackupExportState.Idle -> NothingButton(text = stringResource(R.string.backup_export_action), onClick = onExport)
        }
    }
}

@Composable
private fun ExportProgress(state: BackupExportState.Running, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.backup_export_running, state.done, state.total),
                style = NothingType.metaLabel,
                color = NothingWhite.copy(alpha = 0.6f)
            )
            TextButton(onClick = onCancel) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = NothingType.actionEmphasis,
                    color = NothingRed
                )
            }
        }
        // Indeterminate until the snapshot has been read and the image count is known.
        NothingProgressBar(done = state.done, total = state.total)
    }
}

@Composable
private fun ExportOutcome(summary: BackupExportSummary, onAcknowledge: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(
                R.string.backup_export_done,
                Formatter.formatFileSize(LocalContext.current, summary.bytes)
            ),
            style = NothingType.metaLabel,
            color = NothingWhite
        )
        // Worth saying out loud: those images restore unavailable, which is not what the user asked for.
        if (summary.unreadable > 0) {
            Text(
                text = stringResource(R.string.backup_export_unreadable, summary.unreadable),
                style = NothingType.caption,
                color = NothingWhite.copy(alpha = 0.5f)
            )
        }
        NothingButton(
            text = stringResource(R.string.settings_info_dialog_ok),
            onClick = onAcknowledge,
            variant = NothingButtonVariant.SECONDARY
        )
    }
}

@Composable
private fun ExportFailure(onAcknowledge: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.backup_export_failed),
            style = NothingType.errorBanner,
            color = NothingRed
        )
        NothingButton(
            text = stringResource(R.string.settings_info_dialog_ok),
            onClick = onAcknowledge,
            variant = NothingButtonVariant.SECONDARY
        )
    }
}

/**
 * Shown on the run *after* an export died with the app: the user has a file they believe is a
 * backup, and this is the only chance to say otherwise before they act on that belief.
 */
@Composable
private fun ExportInterrupted(archiveName: String?, onExport: () -> Unit, onAcknowledge: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.backup_export_interrupted),
            style = NothingType.errorBanner,
            color = NothingRed
        )
        Text(
            text = stringResource(R.string.backup_export_interrupted_body),
            style = NothingType.caption,
            color = NothingWhite.copy(alpha = 0.5f)
        )

        if (archiveName != null) {
            Text(
                text = stringResource(R.string.backup_export_interrupted_file, archiveName),
                style = NothingType.caption,
                color = NothingWhite.copy(alpha = 0.5f)
            )
        }

        NothingButton(
            text = stringResource(R.string.backup_export_action),
            onClick = onExport
        )
        TextButton(onClick = onAcknowledge) {
            Text(
                text = stringResource(R.string.settings_info_dialog_ok),
                style = NothingType.actionEmphasis,
                color = NothingWhite.copy(alpha = 0.6f)
            )
        }
    }
}

/** The restore half: one button that hands the user over to the wizard, which owns every decision. */
@Composable
internal fun BackupRestoreSection(
    enabled: Boolean,
    onChooseArchive: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSection(label = stringResource(R.string.backup_section_restore), modifier = modifier) {
        SettingsRowHeader(
            title = stringResource(R.string.backup_import_title),
            subtitle = stringResource(R.string.backup_import_subtitle)
        )
        NothingButton(
            text = stringResource(R.string.backup_import_choose),
            onClick = onChooseArchive,
            enabled = enabled,
            variant = NothingButtonVariant.SECONDARY
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BackupSectionsPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            BackupExportSection(
                portable = false,
                exportState = BackupExportState.Idle,
                onPortableChange = {},
                onExport = {},
                onCancel = {},
                onAcknowledge = {}
            )
            BackupRestoreSection(enabled = true, onChooseArchive = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, name = "Export running")
@Composable
private fun BackupExportRunningPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            BackupExportSection(
                portable = true,
                exportState = BackupExportState.Running(done = 41, total = 120),
                onPortableChange = {},
                onExport = {},
                onCancel = {},
                onAcknowledge = {}
            )
            BackupRestoreSection(enabled = false, onChooseArchive = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, name = "Export interrupted")
@Composable
private fun BackupExportInterruptedPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            BackupExportSection(
                portable = false,
                exportState = BackupExportState.Interrupted("NWC-backup-2026-09-13.nwcbak"),
                onPortableChange = {},
                onExport = {},
                onCancel = {},
                onAcknowledge = {}
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, name = "Export finished")
@Composable
private fun BackupExportFinishedPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            BackupExportSection(
                portable = false,
                exportState = BackupExportState.Finished(
                    BackupExportSummary(unreadable = 2, bytes = 31_457_280)
                ),
                onPortableChange = {},
                onExport = {},
                onCancel = {},
                onAcknowledge = {}
            )
        }
    }
}
