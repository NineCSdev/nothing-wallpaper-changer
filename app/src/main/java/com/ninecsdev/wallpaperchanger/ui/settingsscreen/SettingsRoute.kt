package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereWallpaperService
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Neither state has a system callback. Media access is re-checked on resume, since a permission
    // dialog only pauses us. The engine is re-checked on start: the live-wallpaper picker covers us
    // fully, and an entering destination is started at its first frame but resumed only after the transition.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMediaAccess()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(Unit) {
        viewModel.refreshAtmosphereEngineActive()
        onStopOrDispose { }
    }

    // The snackbar host is owned here because this is where notices arrive.
    val snackbarHostState = remember { SnackbarHostState() }
    val exitClearedMessage = stringResource(R.string.settings_atmosphere_exit_cleared_snackbar)
    val exitFailedMessage = stringResource(R.string.settings_atmosphere_exit_failed_snackbar)
    val lockRemovedMessage = stringResource(R.string.settings_atmosphere_lock_removed_snackbar)

    LaunchedEffect(Unit) {
        viewModel.notices.collect { notice ->
            snackbarHostState.showSnackbar(
                when (notice) {
                    AtmosphereNotice.EXIT_CLEARED -> exitClearedMessage
                    AtmosphereNotice.EXIT_FAILED -> exitFailedMessage
                    AtmosphereNotice.LOCK_WALLPAPER_REMOVED -> lockRemovedMessage
                }
            )
        }
    }

    val mediaAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshMediaAccess() }

    // From the partial state the toggle explains before jumping to app settings; the system dialog explains the denied state.
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

    // Render nothing until the real state has loaded.
    val loadedUiState = uiState ?: return

    SettingsScreen(
        uiState = loadedUiState,
        actions = viewModel,
        snackbarHostState = snackbarHostState,
        onBackClick = onBack,
        onRequestMediaAccess = {
            // From the partial "selected photos" state a re-request only re-opens the
            // manage-selection sheet; upgrading to full access lives in app settings.
            if (context.hasPartialMediaAccess()) showPartialPrompt = true
            else mediaAccessLauncher.launch(mediaAccessPermissions())
        },
        onSetAtmosphere = {
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
        },
        onOpenBackup = onOpenBackup
    )
}
