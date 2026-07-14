package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        onSelectDefaultClick = onLaunchDefaultWallpaperPicker
    )
}
