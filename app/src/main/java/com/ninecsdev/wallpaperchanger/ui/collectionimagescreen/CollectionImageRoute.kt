package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

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

    CollectionImageScreen(
        uiState = uiState,
        actions = viewModel,
        onBackClick = onBack,
        onAddWallpapers = {
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onEditWallpaper = onEditWallpaper,
        onRelinkConfirm = {
            relinkPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    )
}
