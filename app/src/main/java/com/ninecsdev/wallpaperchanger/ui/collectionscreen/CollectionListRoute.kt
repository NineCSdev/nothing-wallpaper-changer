package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful entry point for the Collection List screen: collects state and wires the
 * ViewModel into the stateless [CollectionListScreen], composing the callbacks that
 * pair a ViewModel call with a launcher, navigation, or service side effect.
 *
 * [viewModel] is passed in (not resolved here) because it is activity-scoped and
 * shared with the main screen, which sets picker mode on it before navigating here.
 */
@Composable
fun CollectionListRoute(
    viewModel: CollectionViewModel,
    onBack: () -> Unit,
    onCollectionPicked: (Long) -> Unit,
    onViewImages: (Long) -> Unit,
    onLaunchFolderPicker: () -> Unit,
    onLaunchPhotosPicker: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Render nothing until the real state has loaded (see CollectionViewModel.uiState).
    val loadedUiState = uiState ?: return

    CollectionListScreen(
        uiState = loadedUiState,
        actions = viewModel,
        onCollectionClick = { id ->
            if (loadedUiState.isPickerMode) {
                onCollectionPicked(id)
            } else {
                viewModel.openEditModal(id)
            }
        },
        onBackClick = onBack,
        onFolderSelect = {
            viewModel.toggleCreateModal(false)
            onLaunchFolderPicker()
        },
        onPhotosSelect = {
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
            loadedUiState.editingCollection?.let { onViewImages(it.id) }
        }
    )
}
