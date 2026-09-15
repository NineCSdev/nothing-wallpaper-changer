package com.ninecsdev.wallpaperchanger.ui.backupscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.backup.BackupExportState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/** Both halves of backup on one screen: writing an archive, and restoring from one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    exportState: BackupExportState,
    portable: Boolean,
    importState: BackupImportUiState,
    actions: BackupActions,
    importActions: BackupImportActions,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onChooseArchive: () -> Unit,
    onPickFolder: (initialUri: String?) -> Unit,
    onStartService: () -> Unit,
    onFinishImport: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.backup_screen_title),
                        style = NothingType.titleCaps
                    )
                },
                navigationIcon = {
                    if (importState.step !is BackupImportStep.Working) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = NothingWhite
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NothingBlack,
                    titleContentColor = NothingWhite
                )
            )
        },
        containerColor = NothingBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            when (val step = importState.step) {
                null -> {
                    BackupExportSection(
                        portable = portable,
                        exportState = exportState,
                        onPortableChange = actions::setPortable,
                        onExport = onExport,
                        onCancel = actions::cancelExport,
                        onAcknowledge = actions::acknowledgeExport
                    )
                    // Both halves read the same install, so a restore waits for a write to finish
                    BackupRestoreSection(
                        enabled = exportState !is BackupExportState.Running,
                        onChooseArchive = onChooseArchive
                    )
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BackupWizard(
                        step = step,
                        actions = importActions,
                        onChooseArchive = onChooseArchive,
                        onPickFolder = onPickFolder,
                        onStartService = onStartService,
                        onFinish = onFinishImport
                    )
                }
            }
        }
    }
}

// Previews

@Preview(showSystemUi = true, name = "Backup", backgroundColor = 0xFF000000)
@Composable
private fun BackupScreenPreview() {
    WallpaperChangerTheme {
        BackupScreen(
            exportState = BackupExportState.Idle,
            portable = false,
            importState = BackupImportUiState(),
            actions = PreviewBackupActions,
            importActions = PreviewImportActions,
            onBack = {},
            onExport = {},
            onChooseArchive = {},
            onPickFolder = {},
            onStartService = {},
            onFinishImport = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Restore review", backgroundColor = 0xFF000000, locale = "es")
@Composable
private fun BackupScreenReviewPreview() {
    WallpaperChangerTheme {
        BackupScreen(
            exportState = BackupExportState.Idle,
            portable = false,
            importState = BackupImportUiState(step = BackupImportStep.Review(PreviewImportPlan)),
            actions = PreviewBackupActions,
            importActions = PreviewImportActions,
            onBack = {},
            onExport = {},
            onChooseArchive = {},
            onPickFolder = {},
            onStartService = {},
            onFinishImport = {}
        )
    }
}

private object PreviewBackupActions : BackupActions {
    override fun setPortable(portable: Boolean) {}
    override fun cancelExport() {}
    override fun acknowledgeExport() {}
}
