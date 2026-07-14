package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful entry point for the Settings screen: owns the ViewModel, collects state,
 * and wires it into the stateless [SettingsScreen]. Navigation is received as
 * parameters so this composable stays independent of the nav graph.
 */
@Composable
fun SettingsRoute(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()

    // Render nothing until the real state has loaded (see SettingsViewModel.uiState).
    val loadedUiState = uiState ?: return

    SettingsScreen(
        uiState = loadedUiState,
        storageUsage = storageUsage,
        actions = viewModel,
        onBackClick = onBack
    )
}
