package com.ninecsdev.wallpaperchanger.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

/** Android 14+ also asks for `READ_MEDIA_VISUAL_USER_SELECTED` so a "select photos" choice lands as a proper partial grant. */
fun mediaAccessPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
    } else {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    }

/**
 * True while only the Android 14+ partial "selected photos" grant is held; twin of
 * `WallpaperSources.hasPartialMediaAccess` for callers that only have a [Context].
 */
fun Context.hasPartialMediaAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
        PackageManager.PERMISSION_GRANTED

/** Re-requesting from the partial grant only re-opens the manage-selection sheet, so app settings is the reliable route to full access. */
fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
    )
}

private enum class MediaAccessPrompt { DENIED, PARTIAL }

/**
 * Wraps [launchPicker] in the `READ_MEDIA_IMAGES` pre-prompt. Every outcome ends in the picker except
 * the partial-grant prompt, which opens app settings and abandons the pick. Denial never blocks the
 * feature: an ungranted pick is internalized instead of referenced. The relink pick skips the gate.
 */
// TODO tests: check "MediaStore Transition Tests" vault note
@Composable
fun rememberMediaAccessGatedAction(launchPicker: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var prompt by remember { mutableStateOf<MediaAccessPrompt?>(null) }

    // Granted or denied, the pick continues, the permission only decides reference vs copy.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { launchPicker() }

    prompt?.let { activePrompt ->
        val partial = activePrompt == MediaAccessPrompt.PARTIAL
        ConfirmationOverlay(
            title = stringResource(
                if (partial) R.string.media_access_prompt_partial_title
                else R.string.media_access_prompt_title
            ),
            message = stringResource(
                if (partial) R.string.media_access_prompt_partial_message
                else R.string.media_access_prompt_message
            ),
            confirmLabel = stringResource(
                if (partial) R.string.media_access_prompt_partial_allow
                else R.string.media_access_prompt_allow
            ),
            cancelLabel = stringResource(
                if (partial) R.string.media_access_prompt_partial_skip
                else R.string.media_access_prompt_skip
            ),
            accentColor = NothingWhite,
            onConfirm = {
                prompt = null
                if (partial) context.openAppSettings()
                else permissionLauncher.launch(mediaAccessPermissions())
            },
            onCancel = {
                prompt = null
                launchPicker()
            }
        )
    }

    return {
        val granted = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
        when {
            granted -> launchPicker()
            context.hasPartialMediaAccess() -> prompt = MediaAccessPrompt.PARTIAL
            else -> prompt = MediaAccessPrompt.DENIED
        }
    }
}
