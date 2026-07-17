package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
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

    // Permission state has no callback; re-check whenever the user comes back from system settings.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMediaAccess()
        onPauseOrDispose { }
    }

    // Tapping the locked keep-local-copies toggle asks for the permission instead of doing nothing.
    val mediaAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshMediaAccess() }

    // Render nothing until the real state has loaded (see SettingsViewModel.uiState).
    val loadedUiState = uiState ?: return

    SettingsScreen(
        uiState = loadedUiState,
        storageUsage = storageUsage,
        actions = viewModel,
        onBackClick = onBack,
        onRequestMediaAccess = {
            mediaAccessLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
    )
}
