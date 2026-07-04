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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.ui.components.StatusLed
import com.ninecsdev.wallpaperchanger.ui.mainscreen.getVisualsForState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.R

/**
 * Screen for displaying all the user's collections in a
 * 2-column grid with a collection creation button at the bottom.
 * Owns and renders the CreateListCard and EditCollectionCard modals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionListScreen(
    uiState: CollectionUiState,
    onCollectionClick: (Long) -> Unit,
    onSortOrderChange: (CollectionSortOrder) -> Unit,
    onAddClick: () -> Unit,
    onBackClick: () -> Unit,
    // Create modal callbacks
    onDismissCreateModal: () -> Unit,
    onFolderSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onCreateCollection: (name: String, rule: CropRule) -> Unit,
    // Edit modal callbacks
    onDismissEditModal: () -> Unit,
    onEditCollection: (newName: String, rule: CropRule, freq: RotationFrequency) -> Unit,
    onSetActiveCollection: () -> Unit,
    onDeleteCollection: () -> Unit,
    onSyncCollection: () -> Unit,
    onViewImages: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.collections_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
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
                    onClick = onAddClick,
                    containerColor = NothingWhite,
                    contentColor = NothingBlack,
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_list))
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
                        onSelected = onSortOrderChange
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
                            CollectionGridItem(
                                collection = collection,
                                state = uiState.previewStates[collection.id] ?: CollectionPreviewState(),
                                onClick = { onCollectionClick(collection.id) }
                            )
                        }
                    }
                }
            }
        }

        // Cards that appear on top of the screen
        if (uiState.isShowingCreateModal) {
            CreateListCard(
                isProcessing = uiState.isProcessing,
                hasPendingFolder = uiState.hasPendingFolder,
                hasPendingPhotos = uiState.hasPendingPhotos,
                onDismiss = onDismissCreateModal,
                onFolderSelect = onFolderSelect,
                onPhotosSelect = onPhotosSelect,
                onCreateClick = onCreateCollection
            )
        }

        uiState.editingCollection?.let { collection ->
            EditCollectionCard(
                collection = collection,
                isProcessing = uiState.isProcessing,
                onDismiss = onDismissEditModal,
                onEdit = onEditCollection,
                onSetActive = onSetActiveCollection,
                onDelete = onDeleteCollection,
                onSyncClick = onSyncCollection,
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
            style = MaterialTheme.typography.labelLarge,
            color = NothingWhite.copy(alpha = 0.4f),
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.collections_empty_hint),
            style = MaterialTheme.typography.labelSmall,
            color = NothingWhite.copy(alpha = 0.2f),
            letterSpacing = 1.sp
        )
    }
}

@Preview(showSystemUi = true, name = "Populated", backgroundColor = 0xFF000000)
@Composable
fun CollectionListScreenPopulatedPreview() {
    MaterialTheme {
        CollectionListScreen(
            uiState = CollectionUiState(
                allCollections = listOf(
                    WallpaperCollection(id = 1, name = "AMOLED HIGH", type = CollectionType.FOLDER),
                    WallpaperCollection(id = 2, name = "NATURE PACK", type = CollectionType.FOLDER),
                    WallpaperCollection(id = 3, name = "FAVORITES", type = CollectionType.MANUAL),
                    WallpaperCollection(id = 4, name = "MINIMALISM", type = CollectionType.FOLDER)
                ),
                previewStates = mapOf(
                    1L to CollectionPreviewState(previewUris = emptyList(), totalCount = 12),
                    2L to CollectionPreviewState(previewUris = emptyList(), totalCount = 4),
                    3L to CollectionPreviewState(previewUris = emptyList(), totalCount = 2),
                    4L to CollectionPreviewState(previewUris = emptyList(), totalCount = 2)
                )
            ),
            onCollectionClick = {},
            onSortOrderChange = {},
            onAddClick = {},
            onBackClick = {},
            onDismissCreateModal = {},
            onFolderSelect = {},
            onPhotosSelect = {},
            onCreateCollection = { _, _ -> },
            onDismissEditModal = {},
            onEditCollection = { _, _, _ -> },
            onSetActiveCollection = {},
            onDeleteCollection = {},
            onSyncCollection = {},
            onViewImages = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Empty", backgroundColor = 0xFF000000)
@Composable
fun CollectionListScreenEmptyPreview() {
    MaterialTheme {
        CollectionListScreen(
            uiState = CollectionUiState(),
            onCollectionClick = {},
            onSortOrderChange = {},
            onAddClick = {},
            onBackClick = {},
            onDismissCreateModal = {},
            onFolderSelect = {},
            onPhotosSelect = {},
            onCreateCollection = { _, _ -> },
            onDismissEditModal = {},
            onEditCollection = { _, _, _ -> },
            onSetActiveCollection = {},
            onDeleteCollection = {},
            onSyncCollection = {},
            onViewImages = {}
        )
    }
}
