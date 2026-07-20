package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.hasPartialMediaAccess
import com.ninecsdev.wallpaperchanger.ui.components.mediaAccessPermissions
import com.ninecsdev.wallpaperchanger.ui.components.openAppSettings
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
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
    val context = LocalContext.current
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()

    // Permission state has no callback; re-check whenever the user comes back from system settings.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMediaAccess()
        onPauseOrDispose { }
    }

    // Tapping the locked keep-local-copies toggle asks for the permission instead of doing nothing.
    val mediaAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshMediaAccess() }

    // From the partial "selected photos" state the toggle explains before jumping to app settings
    // (a bare context switch with no warning would be jarring); the system dialog is the
    // explanation for the denied state.
    var showPartialPrompt by remember { mutableStateOf(false) }
    if (showPartialPrompt) {
        ConfirmationOverlay(
            title = stringResource(R.string.media_access_prompt_partial_title),
            message = stringResource(R.string.media_access_prompt_partial_message),
            confirmLabel = stringResource(R.string.media_access_prompt_partial_allow),
            cancelLabel = stringResource(R.string.action_cancel),
            accentColor = NothingWhite,
            onConfirm = {
                showPartialPrompt = false
                context.openAppSettings()
            },
            onCancel = { showPartialPrompt = false }
        )
    }

    // Render nothing until the real state has loaded (see SettingsViewModel.uiState).
    val loadedUiState = uiState ?: return

    SettingsScreen(
        uiState = loadedUiState,
        storageUsage = storageUsage,
        actions = viewModel,
        onBackClick = onBack,
        onRequestMediaAccess = {
            // From the partial "selected photos" state a re-request only re-opens the
            // manage-selection sheet; upgrading to full access lives in app settings.
            if (context.hasPartialMediaAccess()) showPartialPrompt = true
            else mediaAccessLauncher.launch(mediaAccessPermissions())
        }
    )
}
