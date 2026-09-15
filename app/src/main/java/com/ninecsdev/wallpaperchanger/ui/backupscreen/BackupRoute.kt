package com.ninecsdev.wallpaperchanger.ui.backupscreen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.backup.BackupFormat
import com.ninecsdev.wallpaperchanger.ui.components.mediaAccessPermissions
import java.time.LocalDate

/**
 * Stateful entry point for the Backup screen: the export half, the restore wizard, and the four
 * system round-trips between them.
 *
 * The pickers live here rather than in the activity because every one of them answers a question
 * this screen is currently asking, and the wizard's own saved state is what carries the answer
 * across the recreation any of them can cause.
 */
@Composable
fun BackupRoute(
    onBack: () -> Unit,
    onStartService: () -> Unit
) {
    val viewModel: BackupViewModel = hiltViewModel()
    val importViewModel: BackupImportViewModel = hiltViewModel()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val portable by viewModel.portable.collectAsStateWithLifecycle()
    val importState by importViewModel.uiState.collectAsStateWithLifecycle()

    // The user chooses where the archive goes, and the export starts once the document exists
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)
    ) { target -> target?.let { viewModel.export(it) } }

    val exportFileName = stringResource(
        R.string.backup_export_default_name,
        LocalDate.now().toString()
    ) + ".${BackupFormat.EXTENSION}"

    // Cancelling leaves the user wherever they already were
    val archiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importViewModel.onArchivePicked(it.toString()) } }

    // If denied the archive's references simply cannot be verified, and they restore unavailable
    val mediaAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { importViewModel.onMediaAccessSettled() }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        // Cancelling the picker is not an answer; the prompt stays up.
        uri?.let { importViewModel.onFolderPicked(it.toString()) }
    }

    LaunchedEffect(importState.needsMediaAccess) {
        if (importState.needsMediaAccess) mediaAccessLauncher.launch(mediaAccessPermissions())
    }

    // Backing out of the wizard closes the wizard; backing out of the screen leaves the screen
    val back: () -> Unit = {
        if (importState.step != null) importViewModel.onDismissed() else onBack()
    }

    // A restore in flight has no way back
    BackHandler(enabled = importState.step is BackupImportStep.Working) { }

    BackupScreen(
        exportState = exportState,
        portable = portable,
        importState = importState,
        actions = viewModel,
        importActions = importViewModel,
        onBack = back,
        onExport = { exportLauncher.launch(exportFileName) },
        // The extension is ours, so no provider will filter on a type for it
        onChooseArchive = { archiveLauncher.launch(arrayOf("*/*")) },
        // Pre-seeding the picker at the folder the collection came to make the lossless case one tap
        onPickFolder = { initialUri -> folderLauncher.launch(initialUri?.toUri()) },
        onStartService = onStartService,
        // A finished restore so it hands the user back to the app
        onFinishImport = {
            importViewModel.onDismissed()
            onBack()
        }
    )
}
