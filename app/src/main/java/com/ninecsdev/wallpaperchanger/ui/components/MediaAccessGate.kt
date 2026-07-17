package com.ninecsdev.wallpaperchanger.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Wraps a photo-pick [launchPicker] action with the in-context `READ_MEDIA_IMAGES` pre-prompt
 * the returned lambda launches the picker directly while the permission is held,
 * and otherwise shows a dialog explaining why access is worth granting.
 * Both dialog outcomes end in the picker — "allow" goes through the system permission request
 * first, "continue" skips it and the pick is internalized [WallpaperSources.acquirePicked][com.ninecsdev.wallpaperchanger.data.source.WallpaperSources.acquirePicked]
 * falls back on the missing permission, so denial never blocks the feature.
 *
 * Shared by the create-collection photos pick and the add-images pick; the single-image relink
 * pick deliberately skips the gate (rare path, silent internalize is fine).
 */
// TODO tests: check "MediaStore Transition Tests" vault note
@Composable
fun rememberMediaAccessGatedAction(launchPicker: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var showPrompt by remember { mutableStateOf(false) }

    // Granted or denied, the pick continues, the permission only decides reference vs copy.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { launchPicker() }

    if (showPrompt) {
        ConfirmationOverlay(
            title = stringResource(R.string.media_access_prompt_title),
            message = stringResource(R.string.media_access_prompt_message),
            confirmLabel = stringResource(R.string.media_access_prompt_allow),
            cancelLabel = stringResource(R.string.media_access_prompt_skip),
            accentColor = NothingWhite,
            onConfirm = {
                showPrompt = false
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            },
            onCancel = {
                showPrompt = false
                launchPicker()
            }
        )
    }

    return {
        val granted = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchPicker() else showPrompt = true
    }
}
