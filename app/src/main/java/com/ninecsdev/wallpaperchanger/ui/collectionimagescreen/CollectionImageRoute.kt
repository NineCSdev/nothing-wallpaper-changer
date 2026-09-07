package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.transferSummaryText
import com.ninecsdev.wallpaperchanger.ui.components.rememberMediaAccessGatedAction
import com.ninecsdev.wallpaperchanger.ui.components.overlay.importSummaryText

/**
 * Stateful entry point for the Collection Image screen: owns the ViewModel, collects
 * state, and hosts the photo-picker launchers whose results feed back into it.
 * Navigation is received as parameters so this composable stays independent of the
 * nav graph.
 */
@Composable
fun CollectionImageRoute(
    onBack: () -> Unit,
    onEditWallpaper: (WallpaperImage) -> Unit
) {
    val viewModel: CollectionImageViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addWallpapers(uris)
        }
    }

    // Single-select picker for re-linking an unavailable image; uri is null if canceled.
    val relinkPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.onRelinkPicked(uri)
    }

    // Add-images goes through the media-access pre-prompt so reference mode gets its in-context permission ask.
    val gatedAddWallpapers = rememberMediaAccessGatedAction {
        imagePickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // The snackbar host is owned here, not by the screen, because this is where notices arrive
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val relinkFailedMessage = stringResource(R.string.relink_failed_snackbar)

    LaunchedEffect(Unit) {
        viewModel.notices.collect { notice ->
            val message = when (notice) {
                is CollectionImageNotice.Import -> importSummaryText(context, notice.result)
                CollectionImageNotice.RelinkFailed -> relinkFailedMessage
                is CollectionImageNotice.Transfer -> transferSummaryText(context, notice.summary)
            }
            if (message.isNotBlank()) snackbarHostState.showSnackbar(message)
        }
    }

    CollectionImageScreen(
        uiState = uiState,
        actions = viewModel,
        snackbarHostState = snackbarHostState,
        onBackClick = onBack,
        onAddWallpapers = gatedAddWallpapers,
        onEditWallpaper = onEditWallpaper,
        onRelinkConfirm = {
            relinkPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    )
}
