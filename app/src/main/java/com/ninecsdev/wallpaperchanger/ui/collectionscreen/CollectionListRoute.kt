package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        }
    )
}
