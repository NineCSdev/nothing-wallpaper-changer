package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereWallpaperService
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    // Media-access and atmosphere-engine states have no system callback; re-check on every resume
    // (the user may grant/revoke a permission, or set/replace the live wallpaper on the system picker).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMediaAccess()
        viewModel.refreshAtmosphereEngineActive()
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
        },
        onSetAtmosphere = {
            // Stage the source and take both screens first, then hand off to the system
            // live-wallpaper confirmation screen. onResume re-checks the engine state after.
            scope.launch {
                if (viewModel.enterAtmosphere()) {
                    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(context, AtmosphereWallpaperService::class.java)
                    )
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("SettingsRoute", "No live-wallpaper picker available", e)
                    }
                } else {
                    Log.w("SettingsRoute", "No atmosphere source could be prepared")
                }
            }
        }
    )
}
