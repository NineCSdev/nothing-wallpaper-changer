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

/**
 * The permission set every photo-access request launches: on Android 14+ both `READ_MEDIA_IMAGES`
 * and `READ_MEDIA_VISUAL_USER_SELECTED` (so a "select photos" choice lands as a proper partial
 * grant), on Android 13 just `READ_MEDIA_IMAGES`.
 */
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
 * True while the app holds only the Android 14+ partial "selected photos" grant. UI-side twin of
 * [WallpaperSources.hasPartialMediaAccess][com.ninecsdev.wallpaperchanger.data.source.WallpaperSources.hasPartialMediaAccess]
 * for call sites that only have a [Context].
 */
fun Context.hasPartialMediaAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Opens this app's system settings page — the only reliable route from the partial "selected
 * photos" grant to full photo access: re-requesting the permission from that state just re-opens
 * the manage-selection sheet (select more photos), with no dependable "allow all" option.
 */
fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
    )
}

/** Which pre-prompt the gate is currently showing, if any. */
private enum class MediaAccessPrompt { DENIED, PARTIAL }

/**
 * Wraps a photo-pick [launchPicker] action with the in-context `READ_MEDIA_IMAGES` pre-prompt
 * the returned lambda launches the picker directly while the full permission is held,
 * and otherwise shows a dialog explaining why access is worth granting.
 * Has a distinct copy for the Android 14+ partial "selected photos" state, where confirming opens
 * the app's system settings page (see [openAppSettings] for why not a re-request) and the pick is
 * abandoned — the user re-taps after upgrading. Every other outcome still ends in the picker:
 * denied-state "allow" goes through the system permission request first, the cancel actions skip
 * straight to it, and the pick is internalized
 * [WallpaperSources.acquirePicked][com.ninecsdev.wallpaperchanger.data.source.WallpaperSources.acquirePicked]
 * falls back on the missing permission, so denial never blocks the feature.
 *
 * Shared by the create-collection photos pick and the add-images pick; the single-image relink
 * pick deliberately skips the gate (rare path, silent internalize is fine).
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
