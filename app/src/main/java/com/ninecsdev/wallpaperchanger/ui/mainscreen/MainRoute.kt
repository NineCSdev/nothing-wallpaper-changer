package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninecsdev.wallpaperchanger.ui.components.hasPartialMediaAccess
import com.ninecsdev.wallpaperchanger.ui.components.mediaAccessPermissions
import com.ninecsdev.wallpaperchanger.ui.components.openAppSettings

/**
 * Stateful entry point for the main screen: collects state and wires the ViewModel
 * into the stateless [MainScreen]. Navigation and service/activity callbacks are
 * received as parameters so this composable stays independent of the nav graph.
 *
 * [viewModel] is passed in (not resolved here) because it is activity-scoped and
 * shared with the activity's result launchers.
 */
@Composable
fun MainRoute(
    viewModel: MainViewModel,
    onOpenCollections: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onLaunchDefaultWallpaperPicker: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-grant request for the media-access banner; the result (either way) re-snapshots the
    // permission, and a grant re-runs the availability sweep so references self-heal.
    val mediaAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshMediaAccess() }

    // Permission state has no callback; re-check whenever the user comes back from system settings.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMediaAccess()
        onPauseOrDispose { }
    }

    // Render nothing until the real state has loaded (see MainViewModel.uiState).
    val loadedUiState = uiState ?: return

    MainScreen(
        uiState = loadedUiState,
        onStartClick = onStartService,
        onStopClick = onStopService,
        onSelectCollectionClick = { viewModel.setPickerSheetOpen(true) },
        onPickCollection = { id ->
            viewModel.setActiveCollection(id)
            viewModel.setPickerSheetOpen(false)
        },
        onDismissPicker = { viewModel.setPickerSheetOpen(false) },
        onOpenCollectionsClick = onOpenCollections,
        onSettingsClick = onOpenSettings,
        onToggleRevert = viewModel::setRevertToDefault,
        onSelectDefaultClick = onLaunchDefaultWallpaperPicker,
        onGrantMediaAccess = {
            // From the partial "selected photos" state a re-request only re-opens the
            // manage-selection sheet; full access (what references need) lives in app settings.
            if (context.hasPartialMediaAccess()) context.openAppSettings()
            else mediaAccessLauncher.launch(mediaAccessPermissions())
        }
    )
}
