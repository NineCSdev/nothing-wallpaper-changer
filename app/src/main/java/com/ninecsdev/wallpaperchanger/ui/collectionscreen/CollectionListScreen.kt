package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.resolveDisplayName
import com.ninecsdev.wallpaperchanger.ui.components.CollectionGridItem
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.components.StatusLed
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ImportSummarySnackbarEffect
import com.ninecsdev.wallpaperchanger.ui.components.overlay.NothingSnackbarHost
import com.ninecsdev.wallpaperchanger.ui.mainscreen.components.getVisualsForState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.components.CollectionTileContextMenu
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.components.CreateCollectionCard
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.components.EditCollectionCard
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.components.SortDropdown

/**
 * Screen for displaying all the user's collections in a
 * 2-column grid with a collection creation button at the bottom.
 * Tapping a tile opens its images ([onCollectionClick]); long-pressing opens the
 * context menu (Pin/Unpin · Set active · Edit, the latter via [onEditCollection]).
 * Owns and renders the CreateCollectionCard and EditCollectionCard modals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionListScreen(
    uiState: CollectionUiState,
    actions: CollectionListActions,
    onCollectionClick: (Long) -> Unit,
    onEditCollection: (Long) -> Unit,
    onBackClick: () -> Unit,
    // Composites wired in [CollectionListRoute]: they pair a ViewModel call with a
    // launcher, navigation, or service-lifecycle side effect this screen can't own.
    onFolderSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onCreateCollection: (name: String, rule: CropRule) -> Unit,
    onDeleteCollection: () -> Unit,
    onViewImages: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    ImportSummarySnackbarEffect(uiState.importSummary, snackbarHostState, actions::clearImportSummary)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { NothingSnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.collections_title),
                            style = NothingType.titleCaps
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = NothingWhite
                            )
                        }
                    },
                    actions = {
                        val (ledColor, _) = getVisualsForState(uiState.serviceState)
                        StatusLed(
                            color = ledColor,
                            isPulsing = uiState.serviceState is ServiceState.Loading || uiState.serviceState is ServiceState.Stopping,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NothingBlack,
                        titleContentColor = NothingWhite
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { actions.toggleCreateModal(true) },
                    containerColor = NothingWhite,
                    contentColor = NothingBlack,
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_collection))
                }
            },
            containerColor = NothingBlack
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
                ) {
                    SortDropdown(
                        selected = uiState.sortOrder,
                        onSelected = actions::setSortOrder
                    )
                }

                if (uiState.allCollections.isEmpty()) {
                    EmptyCollectionsView()
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.allCollections, key = { it.id }) { collection ->
                            // Tap opens the collection's images; long-press opens the context menu.
                            CollectionTileContextMenu(
                                isPinned = collection.isPinned,
                                isActive = collection.isActive,
                                onTogglePin = { actions.togglePinned(collection.id) },
                                onSetActive = { actions.setActiveCollection(collection.id) },
                                onEdit = { onEditCollection(collection.id) }
                            ) { onLongClick ->
                                CollectionGridItem(
                                    name = collection.resolveDisplayName(LocalContext.current),
                                    state = uiState.previewStates[collection.id]
                                        ?: CollectionPreviewState(),
                                    onClick = { onCollectionClick(collection.id) },
                                    onLongClick = onLongClick,
                                    isPinned = collection.isPinned
                                )
                            }
                        }
                    }
                }
            }
        }

        // Cards that appear on top of the screen
        if (uiState.isShowingCreateModal) {
            CreateCollectionCard(
                isProcessing = uiState.isProcessing,
                hasError = uiState.createError,
                hasPendingFolder = uiState.hasPendingFolder,
                hasPendingPhotos = uiState.hasPendingPhotos,
                onDismiss = { actions.toggleCreateModal(false) },
                onFolderSelect = onFolderSelect,
                onPhotosSelect = onPhotosSelect,
                onCreateClick = onCreateCollection
            )
        }

        uiState.editingCollection?.let { collection ->
            EditCollectionCard(
                collection = collection,
                isProcessing = uiState.isProcessing,
                onDismiss = actions::closeEditModal,
                onEdit = actions::updateEditingCollection,
                onSetActive = actions::setActiveEditingCollection,
                onDelete = onDeleteCollection,
                onSyncClick = actions::syncEditingCollection,
                onViewImages = onViewImages
            )
        }
    }
}

@Composable
private fun EmptyCollectionsView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.collections_empty_title),
            style = NothingType.titleCaps,
            color = NothingWhite.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.collections_empty_hint),
            style = NothingType.metaLabel,
            color = NothingWhite.copy(alpha = 0.2f)
        )
    }
}

@Preview(showSystemUi = true, name = "Populated", backgroundColor = 0xFF000000)
@Composable
fun CollectionListScreenPopulatedPreview() {
    WallpaperChangerTheme {
        CollectionListScreen(
            uiState = CollectionUiState(
                allCollections = listOf(
                    WallpaperCollection(id = 1, name = "AMOLED HIGH", type = CollectionType.FOLDER),
                    WallpaperCollection(id = 2, name = "NATURE PACK", type = CollectionType.FOLDER),
                    WallpaperCollection(id = 3, name = "Favourites", type = CollectionType.MANUAL, isFavorites = true),
                    WallpaperCollection(id = 4, name = "MINIMALISM", type = CollectionType.FOLDER)
                ),
                previewStates = mapOf(
                    1L to CollectionPreviewState(previewUris = emptyList(), totalCount = 12),
                    2L to CollectionPreviewState(previewUris = emptyList(), totalCount = 4),
                    3L to CollectionPreviewState(previewUris = emptyList(), totalCount = 2),
                    4L to CollectionPreviewState(previewUris = emptyList(), totalCount = 2)
                )
            ),
            actions = PreviewCollectionListActions,
            onCollectionClick = {},
            onEditCollection = {},
            onBackClick = {},
            onFolderSelect = {},
            onPhotosSelect = {},
            onCreateCollection = { _, _ -> },
            onDeleteCollection = {},
            onViewImages = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Empty", backgroundColor = 0xFF000000)
@Composable
fun CollectionListScreenEmptyPreview() {
    WallpaperChangerTheme {
        CollectionListScreen(
            uiState = CollectionUiState(),
            actions = PreviewCollectionListActions,
            onCollectionClick = {},
            onEditCollection = {},
            onBackClick = {},
            onFolderSelect = {},
            onPhotosSelect = {},
            onCreateCollection = { _, _ -> },
            onDeleteCollection = {},
            onViewImages = {}
        )
    }
}

/** No-op actions for previews. */
private object PreviewCollectionListActions : CollectionListActions {
    override fun setSortOrder(order: CollectionSortOrder) {}
    override fun toggleCreateModal(show: Boolean) {}
    override fun closeEditModal() {}
    override fun updateEditingCollection(newName: String, cropRule: CropRule, rotationFrequency: RotationFrequency) {}
    override fun setActiveEditingCollection() {}
    override fun syncEditingCollection() {}
    override fun clearImportSummary() {}
    override fun togglePinned(collectionId: Long) {}
    override fun setActiveCollection(collectionId: Long) {}
}
