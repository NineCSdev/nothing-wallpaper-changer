package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.source.SourceDetails
import com.ninecsdev.wallpaperchanger.logic.CropPlacement
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.resolveCollectionDisplayName
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.CropRuleAlignment
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.FilterButton
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.FilterChipRow
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.FilterDimension
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.FilterNoMatches
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.NewCollectionNameDialog
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.SelectionActionBar
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.WallpaperPreviewOverlay
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.components.EditableWallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.GridThumbnail
import com.ninecsdev.wallpaperchanger.ui.components.EditedBadge
import com.ninecsdev.wallpaperchanger.ui.components.FavoriteBadge
import com.ninecsdev.wallpaperchanger.ui.components.UnavailableBadge
import com.ninecsdev.wallpaperchanger.ui.components.overlay.CollectionPickerItem
import com.ninecsdev.wallpaperchanger.ui.components.overlay.CollectionPickerSheet
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.components.overlay.NothingSnackbarHost
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

internal const val GRID_COLUMNS = 3

// The selection pill's 16dp inset, 64dp height and some breathing room
private val SelectionPillClearance = 88.dp

private const val FILTER_ROW_KEY = "filter-row"
private const val GRID_HEADER_ITEMS = 1

/** Memory-cache key of a grid thumbnail, reused as the preview's placeholder. Keyed by uri, not id: wallpapers sharing a file share the thumbnail. */
internal fun wallpaperThumbCacheKey(uri: String) = "wallpaper-thumb-$uri"

// Separate key: edited tiles are sized to the screen-aspect frame, so their bitmaps differ
internal fun wallpaperEditThumbCacheKey(uri: String) = "wallpaper-edit-thumb-$uri"

internal fun AsyncImagePainter.State.Success.intrinsicAspectRatio(): Float? {
    val size = painter.intrinsicSize
    return if (size.width > 0f && size.height > 0f) size.width / size.height else null
}

// The library default is near-instant on device; no-bounce keeps the landing clean
internal val PreviewFlightBoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = PREVIEW_FLIGHT_STIFFNESS,
        visibilityThreshold = Rect.VisibilityThreshold
    )
}

private const val PREVIEW_FLIGHT_STIFFNESS = 1000f // how fast the flight plays

// Short enough that badges are gone before the flight has traveled, and land rather than drift in on return
private val CellChromeFade = tween<Float>(durationMillis = 120)

/** The grid cell and the overlay page must use the same [key] for the shared-element flight to run. */
@Suppress("ModifierFactoryExtensionFunction") // Receiver is SharedTransitionScope, not Modifier, intentional.
@Composable
internal fun SharedTransitionScope.previewFlightModifier(
    key: Any,
    animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = Modifier.sharedElement(
    rememberSharedContentState(key = key),
    animatedVisibilityScope = animatedVisibilityScope,
    boundsTransform = PreviewFlightBoundsTransform
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionImageScreen(
    uiState: CollectionImageUiState,
    actions: CollectionImageActions,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onAddWallpapers: () -> Unit,
    onEditWallpaper: (WallpaperImage) -> Unit,
    onRelinkConfirm: () -> Unit
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    SharedTransitionLayout {
        val sharedScope = this

        fun wallpaperIndex(id: Long?) = uiState.visibleWallpapers.indexOfFirst { it.id == id }.coerceAtLeast(0)

        // Driven locally so the close can play its shared-element flight before previewWallpaper clears
        val previewVisibleState = remember { MutableTransitionState(uiState.previewWallpaper != null) }

        // Pinned at open so page swipes never yank the pager back to the tapped page
        var pagerStartIndex by remember { mutableIntStateOf(wallpaperIndex(uiState.previewWallpaper?.id)) }
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()

        // Stops the preview falling back to fullscreen framing until the full-res image decodes
        val knownAspectRatios = remember { mutableStateMapOf<Long, Float>() }

        var previewFramedByRule by rememberSaveable { mutableStateOf(false) }

        val currentPreview by rememberUpdatedState(uiState.previewWallpaper)
        val currentActions by rememberUpdatedState(actions)
        val currentWallpapers by rememberUpdatedState(uiState.visibleWallpapers)

        // The flip fires the reverse flight: the viewed wallpaper's cell enters as the overlay exits
        val dismissPreview = {
            if (previewVisibleState.targetState) {
                previewVisibleState.targetState = false
            }
        }

        LaunchedEffect(uiState.previewWallpaper?.id) {
            val wallpaper = uiState.previewWallpaper
            if (wallpaper != null && !previewVisibleState.targetState) {
                pagerStartIndex = wallpaperIndex(wallpaper.id)
                previewFramedByRule = false
                previewVisibleState.targetState = true
            } else if (wallpaper == null && previewVisibleState.targetState) {
                // Previewed wallpaper vanished (e.g. deleted by a sync): close with a plain fade
                previewVisibleState.targetState = false
            }
        }

        // Commit the close to the ViewModel only once the fade and the flight have both settled
        LaunchedEffect(Unit) {
            snapshotFlow { previewVisibleState.isIdle && !sharedScope.isTransitionActive }
                .collect { settled ->
                    if (settled && !previewVisibleState.currentState && currentPreview != null) {
                        currentActions.closePreview()
                    }
                }
        }

        // Back order: selection mode, then preview, then navigate back
        // TODO: Do a better handling of this
        BackHandler(enabled = uiState.isSelectionMode) {
            actions.exitSelectionMode()
        }
        BackHandler(enabled = previewVisibleState.targetState) {
            dismissPreview()
        }

        val selectionPillClearance by animateDpAsState(
            targetValue = if (uiState.isSelectionMode) SelectionPillClearance else 0.dp,
            label = "selectionPillClearance"
        )

        Scaffold(
            snackbarHost = {
                NothingSnackbarHost(
                    snackbarHostState,
                    modifier = Modifier.padding(bottom = selectionPillClearance)
                )
            },
            topBar = {
                TopAppBar(
                    title = {
                        if (uiState.isSelectionMode) {
                            Text(
                                text = stringResource(R.string.image_screen_count_selected, uiState.selectedIds.size),
                                style = NothingType.titleCaps
                            )
                        } else {
                            val title = resolveCollectionDisplayName(
                                LocalContext.current, uiState.collectionName, uiState.isFavoritesCollection
                            )
                            Text(
                                text = title.uppercase(),
                                style = NothingType.titleCaps,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        if (uiState.isSelectionMode) {
                            IconButton(onClick = actions::exitSelectionMode) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_exit_selection),
                                    tint = NothingWhite
                                )
                            }
                        } else {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back),
                                    tint = NothingWhite
                                )
                            }
                        }
                    },
                    actions = {
                        // Stays visible but inert while selecting, so a filtered selection still says so
                        if (uiState.showsFilterButton && (!uiState.isSelectionMode || uiState.filter != null)) {
                            FilterButton(
                                isActive = uiState.filter != null,
                                enabled = !uiState.isSelectionMode,
                                onClick = {
                                    if (uiState.filter == null) scope.launch { gridState.animateScrollToItem(0) }
                                    actions.toggleFilter()
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NothingBlack,
                        titleContentColor = NothingWhite
                    )
                )
            },
            floatingActionButton = {
                if (!uiState.isSelectionMode) {
                    FloatingActionButton(
                        onClick = onAddWallpapers,
                        containerColor = NothingWhite,
                        contentColor = NothingBlack,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_wallpapers))
                    }
                }
            },
            containerColor = NothingBlack
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.wallpapers.isEmpty() && !uiState.isLoading) {
                    EmptyCollectionState(
                        topPadding = padding.calculateTopPadding(),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(GRID_COLUMNS),
                        state = gridState,
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 8.dp + selectionPillClearance,
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Always composed, so it stays index 0 and the scroll anchor while it expands
                        item(key = FILTER_ROW_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            AnimatedVisibility(
                                visible = uiState.filter != null,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                FilterChipRow(
                                    chips = uiState.filterChips,
                                    enabled = !uiState.isSelectionMode,
                                    onChipClick = { actions.toggleFilterChip(it.dimension, it.answer) },
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }
                        items(uiState.visibleWallpapers, key = { it.id }) { wallpaper ->
                            val isSelected = wallpaper.id in uiState.selectedIds
                            val isCurrentPreview = wallpaper.id == uiState.previewWallpaper?.id
                            WallpaperThumbnail(
                                wallpaper = wallpaper,
                                isSelected = isSelected,
                                isSelectionMode = uiState.isSelectionMode,
                                isFavorite = wallpaper.fileId in uiState.favoriteFileIds,
                                // Only during open/close flights, never while the preview is settled open
                                sharesPreviewBounds = isCurrentPreview && (
                                    !(previewVisibleState.currentState && previewVisibleState.targetState) ||
                                        sharedScope.isTransitionActive
                                    ),
                                hiddenForPreview = isCurrentPreview && previewVisibleState.targetState,
                                previewHorizontalBias = if (isCurrentPreview && previewFramedByRule) CropPlacement.horizontalBias(uiState.collectionCropRule)
                                                        else 0f,
                                onAspectRatioResolved = { ratio ->
                                    if (wallpaper.id !in knownAspectRatios) {
                                        knownAspectRatios[wallpaper.id] = ratio
                                    }
                                },
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        actions.toggleSelection(wallpaper.id)
                                    } else if (!wallpaper.isAvailable) {
                                        actions.requestRelink(wallpaper)
                                    } else {
                                        actions.openPreview(wallpaper)
                                    }
                                },
                                onLongClick = {
                                    if (!uiState.isSelectionMode) {
                                        actions.enterSelectionMode(wallpaper.id)
                                    }
                                }
                            )
                        }
                        if (uiState.visibleWallpapers.isEmpty() && uiState.wallpapers.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                FilterNoMatches(onClear = actions::clearFilter)
                            }
                        }
                    }

                    // Keeps the viewed wallpaper's thumbnail composed so dismissing has a flight target not covered by the top bar
                    LaunchedEffect(uiState.previewWallpaper?.id) {
                        val wallpaper = uiState.previewWallpaper ?: return@LaunchedEffect
                        // Wait for the open to settle: scrolling mid-flight would move its origin
                        snapshotFlow {
                            previewVisibleState.currentState && previewVisibleState.targetState &&
                                !sharedScope.isTransitionActive
                        }.first { it }
                        val index = currentWallpapers.indexOfFirst { it.id == wallpaper.id }
                        if (index < 0) return@LaunchedEffect
                        val info = gridState.layoutInfo.visibleItemsInfo.find { it.key == wallpaper.id }
                        val safelyVisible = info != null &&
                            info.offset.y >= 0 &&
                            info.offset.y + info.size.height <= gridState.layoutInfo.viewportEndOffset
                        if (!safelyVisible) {
                            // scrollToItem aligns to the content area start, which contentPadding keeps below the top bar
                            gridState.scrollToItem(index + GRID_HEADER_ITEMS)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = uiState.isSelectionMode,
                    enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = padding.calculateBottomPadding() + 16.dp)
                ) {
                    SelectionActionBar(
                        isAllFavorites = uiState.isSelectionAllFavorites,
                        hasSingleSelection = uiState.selectedWallpaper != null,
                        isSelectedDefault = uiState.isSelectionTheDefault,
                        onFavoriteClick = actions::toggleFavoriteSelected,
                        onEditClick = { uiState.selectedWallpaper?.let(onEditWallpaper) },
                        onDeleteClick = { showDeleteConfirmation = true },
                        onCopyClick = { actions.requestTransfer(TransferMode.COPY) },
                        onMoveClick = { actions.requestTransfer(TransferMode.MOVE) },
                        onSetDefaultClick = actions::toggleCollectionDefaultSelected
                    )
                }
            }
        }

        AnimatedVisibility(
            visibleState = previewVisibleState,
            enter = fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = fadeOut(animationSpec = tween(durationMillis = 200))
        ) {
            WallpaperPreviewOverlay(
                wallpapers = uiState.visibleWallpapers,
                initialIndex = pagerStartIndex,
                sharedTransitionScope = sharedScope,
                animatedVisibilityScope = this,
                sharedWallpaperId = uiState.previewWallpaper?.id,
                knownAspectRatios = knownAspectRatios,
                favoriteFileIds = uiState.favoriteFileIds,
                defaultWallpaperId = uiState.defaultWallpaperId,
                cropRule = uiState.collectionCropRule,
                framedByRule = previewFramedByRule,
                onToggleFramedByRule = { previewFramedByRule = !previewFramedByRule },
                onToggleFavorite = actions::toggleFavorite,
                onToggleDefault = actions::toggleCollectionDefault,
                onDismiss = dismissPreview,
                onEdit = onEditWallpaper,
                onPageChanged = actions::openPreview,
                loadSourceDetails = actions::sourceDetails
            )
        }
    }

    if (showDeleteConfirmation) {
        val selectedCount = uiState.selectedIds.size
        ConfirmationOverlay(
            title = if (selectedCount == 1) stringResource(R.string.image_screen_delete_single_title)
                    else stringResource(R.string.image_screen_delete_multiple_title),
            message = if (selectedCount == 1) stringResource(R.string.image_screen_delete_single_message)
                    else stringResource(R.string.image_screen_delete_multiple_message),
            onConfirm = {
                showDeleteConfirmation = false
                if (selectedCount > 0) actions.deleteSelectedWallpapers()
            },
            onCancel = { showDeleteConfirmation = false }
        )
    }

    if (uiState.isNamingNewTarget) {
        NewCollectionNameDialog(
            onConfirm = actions::transferToNewCollection,
            onDismiss = actions::cancelTransfer
        )
    } else if (uiState.transferMode != null) {
        CollectionPickerSheet(
            title = stringResource(
                if (uiState.transferMode == TransferMode.COPY) R.string.transfer_dialog_title_copy
                else R.string.transfer_dialog_title_move
            ),
            items = uiState.transferTargets.map { target ->
                CollectionPickerItem(
                    id = target.collectionId,
                    name = target.name,
                    previewState = CollectionPreviewState(target.previewFiles, target.imageCount),
                    isPinned = target.isPinned
                )
            },
            onItemClick = { item ->
                uiState.transferTargets
                    .find { it.collectionId == item.id }
                    ?.let(actions::transferToCollection)
            },
            onDismiss = actions::cancelTransfer,
            onCreateNew = actions::requestNewTransferTarget
        )
    }

    if (uiState.relinkTarget != null) {
        ConfirmationOverlay(
            title = stringResource(R.string.relink_dialog_title),
            message = stringResource(R.string.relink_dialog_message),
            confirmLabel = stringResource(R.string.relink_dialog_confirm),
            cancelLabel = stringResource(R.string.relink_dialog_cancel),
            accentColor = NothingWhite,
            onConfirm = onRelinkConfirm,
            onCancel = actions::cancelRelink
        )
    }
}

@Composable
private fun EmptyCollectionState(
    topPadding: Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(top = topPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.image_screen_empty_title),
            style = NothingType.titleCaps,
            color = NothingWhite.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.image_screen_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = NothingWhite.copy(alpha = 0.25f)
        )
    }
}



/**
 * The cell carries the flight modifier only while the preview opens or closes
 * ([sharesPreviewBounds]), so pager swipes in a settled preview cannot re-match a cell and fire
 * stray flights. On open the modifier is registered a frame before the cell hides
 * ([hiddenForPreview]), since flights need a pre-registered origin.
 */
@Composable
private fun SharedTransitionScope.WallpaperThumbnail(
    wallpaper: WallpaperImage,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isFavorite: Boolean,
    sharesPreviewBounds: Boolean,
    hiddenForPreview: Boolean,
    previewHorizontalBias: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAspectRatioResolved: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        AnimatedVisibility(
            visible = !hiddenForPreview,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val flightModifier = if (!sharesPreviewBounds) Modifier
            else previewFlightModifier(
                key = wallpaper.id,
                animatedVisibilityScope = this@AnimatedVisibility
            )
            // A close from a crop-rule-framed page departs at that page's alignment and settles centred as it lands
            val horizontalBias by transition.animateFloat(
                transitionSpec = {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PREVIEW_FLIGHT_STIFFNESS)
                },
                label = "previewHorizontalBias"
            ) { state -> if (state == EnterExitState.Visible) 0f else previewHorizontalBias }
            val alignment = remember { CropRuleAlignment { horizontalBias } }
            Box(modifier = flightModifier.fillMaxSize()) {
                val imageModifier = Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(RoundedCornerShape(if (isSelectionMode && isSelected) 8.dp else 0.dp))
                    .then(if (sharesPreviewBounds) Modifier else Modifier.background(NothingGray))
                // Both branches render a centered crop at any bounds, so the shared-element
                // bounds animation (square cell to overlay rect) is itself the crop-rect morph
                if (wallpaper.editParams == null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(GridThumbnail(wallpaper.fileId, wallpaper.uriString, wallpaper.sourceType))
                            .memoryCacheKey(wallpaperThumbCacheKey(wallpaper.uriString))
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = alignment,
                        onSuccess = { state ->
                            state.intrinsicAspectRatio()?.let(onAspectRatioResolved)
                        },
                        modifier = imageModifier
                    )
                } else {
                    EditableWallpaperImage(
                        wallpaper = wallpaper,
                        contentDescription = null,
                        decodeFraction = 1f / GRID_COLUMNS,
                        memoryCacheKey = wallpaperEditThumbCacheKey(wallpaper.uriString),
                        fromGridThumbnail = true,
                        modifier = imageModifier
                    )
                }
            }
        }

        // Corner chrome, outside the flight so they don't follow the wallpaper to its full-screen state
        AnimatedVisibility(
            visible = !hiddenForPreview && !sharesPreviewBounds,
            enter = fadeIn(CellChromeFade),
            exit = fadeOut(CellChromeFade),
            modifier = Modifier.matchParentSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (wallpaper.editParams != null) {
                    EditedBadge(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    )
                }

                if (!wallpaper.isAvailable) {
                    UnavailableBadge(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    )
                }

                if (isFavorite) {
                    FavoriteBadge(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    )
                }

                if (isSelectionMode && isSelected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(1.dp)
                            .border(3.dp, NothingWhite, RoundedCornerShape(5.dp))
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(NothingWhite),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_selected),
                            tint = NothingBlack,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showSystemUi = true, name = "Collection Images", backgroundColor = 0xFF000000)
@Composable
fun CollectionImageScreenPreview() {
    val sampleWallpapers = (1L..9L).map { id ->
        WallpaperImage(
            id = id,
            collectionId = 1,
            uriString = "",
            editParams = if (id == 3L || id == 7L) EditParams(zoom = 1.5f, offsetX = 0f, offsetY = 0f) else null
        )
    }

    WallpaperChangerTheme {
        CollectionImageScreen(
            uiState = CollectionImageUiState(
                collectionName = "AMOLED HIGH",
                wallpapers = sampleWallpapers,
                isLoading = false
            ),
            actions = PreviewCollectionImageActions,
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onAddWallpapers = {},
            onEditWallpaper = {},
            onRelinkConfirm = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Selection Mode", backgroundColor = 0xFF000000)
@Composable
fun CollectionImageScreenSelectionPreview() {
    val sampleWallpapers = (1L..6L).map { id ->
        WallpaperImage(
            id = id,
            collectionId = 1,
            uriString = ""
        )
    }

    WallpaperChangerTheme {
        CollectionImageScreen(
            uiState = CollectionImageUiState(
                collectionName = "FAVORITES",
                wallpapers = sampleWallpapers,
                isLoading = false,
                isSelectionMode = true,
                selectedIds = setOf(2L, 4L)
            ),
            actions = PreviewCollectionImageActions,
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onAddWallpapers = {},
            onEditWallpaper = {},
            onRelinkConfirm = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Empty Collection", backgroundColor = 0xFF000000)
@Composable
fun CollectionImageScreenEmptyPreview() {
    WallpaperChangerTheme {
        CollectionImageScreen(
            uiState = CollectionImageUiState(
                collectionName = "EMPTY COLLECTION",
                wallpapers = emptyList(),
                isLoading = false
            ),
            actions = PreviewCollectionImageActions,
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onAddWallpapers = {},
            onEditWallpaper = {},
            onRelinkConfirm = {}
        )
    }
}

private object PreviewCollectionImageActions : CollectionImageActions {
    override fun openPreview(wallpaper: WallpaperImage) {}
    override fun closePreview() {}
    override suspend fun sourceDetails(wallpaper: WallpaperImage) = SourceDetails()
    override fun toggleFilter() {}
    override fun toggleFilterChip(dimension: FilterDimension, answer: Boolean) {}
    override fun clearFilter() {}
    override fun enterSelectionMode(id: Long) {}
    override fun toggleSelection(id: Long) {}
    override fun exitSelectionMode() {}
    override fun deleteSelectedWallpapers() {}
    override fun requestTransfer(mode: TransferMode) {}
    override fun transferToCollection(target: TransferTarget) {}
    override fun requestNewTransferTarget() {}
    override fun transferToNewCollection(name: String) {}
    override fun cancelTransfer() {}
    override fun toggleFavoriteSelected() {}
    override fun toggleFavorite(wallpaper: WallpaperImage) {}
    override fun toggleCollectionDefaultSelected() {}
    override fun toggleCollectionDefault(wallpaper: WallpaperImage) {}
    override fun requestRelink(wallpaper: WallpaperImage) {}
    override fun cancelRelink() {}
}
