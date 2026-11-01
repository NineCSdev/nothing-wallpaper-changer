package com.ninecsdev.wallpaperchanger.ui.setupscreen

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * [viewModel] is passed in because it is activity-scoped: the navigation host reads the gate from it
 * before choosing a start destination.
 */
@Composable
fun SetupRoute(
    viewModel: SetupViewModel,
    onLeave: () -> Unit,
    onAddWallpapers: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasCollections by viewModel.hasCollections.collectAsStateWithLifecycle()

    val leave = {
        viewModel.markSeen()
        onLeave()
    }

    LaunchedEffect(hasCollections) {
        if (hasCollections) leave()
    }

    // Render nothing until the real state has loaded.
    val loadedUiState = uiState ?: return

    val previous = loadedUiState.previousStep
    BackHandler(enabled = previous != null) {
        previous?.let(viewModel::goTo)
    }

    SetupScreen(
        uiState = loadedUiState,
        onSkip = leave,
        onNext = {
            if (loadedUiState.isLastDecision) {
                viewModel.markSeen()
                onAddWallpapers()
            } else {
                loadedUiState.nextStep?.let(viewModel::goTo)
            }
        },
        onBack = { previous?.let(viewModel::goTo) },
        onRestoreBackup = onRestoreBackup,
        onSelectMode = viewModel::setWallpaperMode,
        onSelectRotation = viewModel::setRotationPolicy,
        onSelectDestination = viewModel::setWallpaperDestination
    )
}
