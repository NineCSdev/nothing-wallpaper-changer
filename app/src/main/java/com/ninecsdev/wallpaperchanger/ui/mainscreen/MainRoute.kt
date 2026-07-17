package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    onOpenCollections: (pickerMode: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onLaunchDefaultWallpaperPicker: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Re-grant request for the media-access banner; the result (either way) re-snapshots the
    // permission, and a grant re-runs the availability sweep so references self-heal.
    val mediaAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
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
        onSelectFolderClick = { onOpenCollections(true) },
        onOpenCollectionsClick = { onOpenCollections(false) },
        onSettingsClick = onOpenSettings,
        onToggleRevert = viewModel::setRevertToDefault,
        onSelectDefaultClick = onLaunchDefaultWallpaperPicker,
        onGrantMediaAccess = {
            mediaAccessLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
    )
}
