package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninecsdev.wallpaperchanger.ui.components.overlay.importSummaryText
import com.ninecsdev.wallpaperchanger.ui.components.rememberMediaAccessGatedAction

/**
 * Stateful entry point for the Collection List screen: collects state and notices, and wires
 * the ViewModel into the stateless [CollectionListScreen], composing the callbacks that pair a
 * ViewModel call with a launcher or navigation side effect.
 *
 * [viewModel] is passed in (not resolved here) because it must be the activity-scoped
 * instance: MainActivity's picker-result launchers push the picked folder/photos and
 * reopen the create modal on it.
 */
@Composable
fun CollectionListRoute(
    viewModel: CollectionViewModel,
    onBack: () -> Unit,
    onViewImages: (Long) -> Unit,
    onLaunchFolderPicker: () -> Unit,
    onLaunchPhotosPicker: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The snackbar host is owned here, not by the screen, because this is where notices arrive
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.notices.collect { result ->
            val message = importSummaryText(context, result)
            if (message.isNotBlank()) snackbarHostState.showSnackbar(message)
        }
    }

    // Render nothing until the real state has loaded (see CollectionViewModel.uiState).
    val loadedUiState = uiState ?: return

    CollectionListScreen(
        uiState = loadedUiState,
        actions = viewModel,
        snackbarHostState = snackbarHostState,
        onCollectionClick = onViewImages,
        onEditCollection = viewModel::openEditModal,
        onBackClick = onBack,
        onFolderSelect = {
            viewModel.toggleCreateModal(false)
            onLaunchFolderPicker()
        },
        onPhotosSelect = rememberMediaAccessGatedAction {
            viewModel.toggleCreateModal(false)
            onLaunchPhotosPicker()
        },
        onCreateCollection = viewModel::finalizeCollection,
        onDeleteCollection = viewModel::deleteEditingCollection
    )
}
