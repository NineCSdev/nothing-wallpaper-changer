package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ninecsdev.wallpaperchanger.ui.components.rememberMediaAccessGatedAction

/**
 * Stateful entry point for the Collection List screen: collects state and wires the
 * ViewModel into the stateless [CollectionListScreen], composing the callbacks that
 * pair a ViewModel call with a launcher, navigation, or service side effect.
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
    onLaunchPhotosPicker: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Render nothing until the real state has loaded (see CollectionViewModel.uiState).
    val loadedUiState = uiState ?: return

    // We record the target here, close the modal, and defer the navigation until the modal
    // has actually left composition.
    var pendingViewImagesId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(pendingViewImagesId, loadedUiState.editingCollection) {
        val target = pendingViewImagesId
        if (target != null && loadedUiState.editingCollection == null) {
            pendingViewImagesId = null
            onViewImages(target)
        }
    }

    CollectionListScreen(
        uiState = loadedUiState,
        actions = viewModel,
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
        onCreateCollection = { name, rule ->
            viewModel.finalizeCollection(name, rule) { shouldStartService ->
                viewModel.toggleCreateModal(false)
                if (shouldStartService) onStartService()
            }
        },
        onDeleteCollection = {
            viewModel.deleteEditingCollection { wasActive ->
                if (wasActive) onStopService()
            }
        },
        onViewImages = {
            loadedUiState.editingCollection?.let { pendingViewImagesId = it.id }
            viewModel.closeEditModal()
        }
    )
}
