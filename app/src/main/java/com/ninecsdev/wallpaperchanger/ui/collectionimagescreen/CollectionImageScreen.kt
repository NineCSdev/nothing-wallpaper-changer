package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.walleditscreen.EditableWallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ImportSummarySnackbarEffect
import com.ninecsdev.wallpaperchanger.ui.components.overlay.NothingSnackbarHost
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

private const val GRID_COLUMNS = 3

/**
 * Bounds spring for the grid ↔ preview zoom. The library default felt
 * near-instant on device; no-bouncy keeps the landing clean and springs
 * redirect gracefully when a flight is interrupted mid-way.
 */
internal val PreviewFlightBoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 375f, // how fast the animation plays
        visibilityThreshold = Rect.VisibilityThreshold
    )
}

/**
 * Screen displaying all wallpapers inside a specific collection
 * as a 3-column grid of image thumbnails.
 *
 * Supports selection mode (long press), full-screen preview (tap, opened and
 * closed with a shared-element zoom between the grid cell and the preview),
 * adding wallpapers (FAB), and edit/delete actions (top bar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionImageScreen(
    uiState: CollectionImageUiState,
    onBackClick: () -> Unit,
    onAddWallpapers: () -> Unit,
    onWallpaperTap: (WallpaperImage) -> Unit,
    onUnavailableTap: (WallpaperImage) -> Unit = {},
    onWallpaperLongPress: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onExitSelectionMode: () -> Unit,
    onDeleteSelected: () -> Unit,
    onEditSelected: (WallpaperImage) -> Unit,
    onEditFromPreview: (WallpaperImage) -> Unit,
    onPreviewPageChanged: (WallpaperImage) -> Unit,
    onClosePreview: () -> Unit,
    onImportSummaryShown: () -> Unit = {},
    onRelinkConfirm: () -> Unit = {},
    onRelinkCancel: () -> Unit = {},
    onRelinkFailedShown: () -> Unit = {}
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    ImportSummarySnackbarEffect(uiState.importSummary, snackbarHostState, onImportSummaryShown)

    val relinkFailedMessage = stringResource(R.string.relink_failed_snackbar)
    LaunchedEffect(uiState.relinkFailed) {
        if (uiState.relinkFailed) {
            snackbarHostState.showSnackbar(relinkFailedMessage)
            onRelinkFailedShown()
        }
    }

    SharedTransitionLayout {
        val sharedScope = this

        // Returns the index of [id] in the current wallpaper list, coercing "not found" (-1)
        // to 0 so the pager always has a valid initial page. Used to pin pagerStartIndex at
        // open time and to update it when the open effect fires.
        fun wallpaperIndex(id: Long?) =
            uiState.wallpapers.indexOfFirst { it.id == id }.coerceAtLeast(0)

        // The preview overlay's enter/exit is driven locally (not by the ViewModel)
        // so the close can play its shared-element flight *before* previewWallpaper
        // is cleared. Starts visible when restored with an open preview (e.g. coming
        // back from the editor) so the restore doesn't replay the zoom.
        val previewVisibleState = remember {
            MutableTransitionState(uiState.previewWallpaper != null)
        }

        // Pager start page, pinned at open so page swipes (which update
        // previewWallpaper) never yank the pager back to the tapped page.
        var pagerStartIndex by remember {
            mutableIntStateOf(wallpaperIndex(uiState.previewWallpaper?.id))
        }

        val gridState = rememberLazyGridState()
        val currentPreview by rememberUpdatedState(uiState.previewWallpaper)
        val currentOnClosePreview by rememberUpdatedState(onClosePreview)

        // This flip fires the reverse flight: the currently viewed wallpaper's cell
        // enters exactly as the overlay exits (both ends carry their shared modifiers —
        // see sharesPreviewBounds below). WallpaperPreviewOverlay already stabilises
        // onDismiss via rememberUpdatedState + pointerInput(Unit), so no remember{} here.
        val dismissPreview = {
            if (previewVisibleState.targetState) {
                previewVisibleState.targetState = false
            }
        }

        // Open the preview with a flight from the tapped cell. Because this effect
        // runs a frame after previewWallpaper lands (and the shared modifiers key on
        // previewWallpaper), the flight's origin is registered *before* this flip —
        // shared-element flights only run when the origin pre-exists the flip.
        // If the previewed wallpaper vanishes underneath us (e.g. deleted by a
        // sync), close with a plain fade — there may be no thumbnail to fly into.
        LaunchedEffect(uiState.previewWallpaper?.id) {
            val wallpaper = uiState.previewWallpaper
            if (wallpaper != null && !previewVisibleState.targetState) {
                pagerStartIndex = wallpaperIndex(wallpaper.id)
                previewVisibleState.targetState = true
            } else if (wallpaper == null && previewVisibleState.targetState) {
                previewVisibleState.targetState = false
            }
        }

        // Once both the fade and the shared-element flight have settled after a
        // close, commit the close to the ViewModel — that clears previewWallpaper,
        // which in turn detaches the shared modifiers on both ends.
        LaunchedEffect(Unit) {
            snapshotFlow { previewVisibleState.isIdle && !sharedScope.isTransitionActive }
                .collect { settled ->
                    if (settled && !previewVisibleState.currentState && currentPreview != null) {
                        currentOnClosePreview()
                    }
                }
        }

        // Handle back press: exit selection mode first, then close preview, then navigate back
        // TODO: Do a better handling of this
        BackHandler(enabled = uiState.isSelectionMode) {
            onExitSelectionMode()
        }
        BackHandler(enabled = previewVisibleState.targetState) {
            dismissPreview()
        }

        Scaffold(
            snackbarHost = { NothingSnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        if (uiState.isSelectionMode) {
                            Text(
                                text = stringResource(R.string.image_screen_count_selected, uiState.selectedIds.size),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp
                            )
                        } else {
                            Text(
                                text = uiState.collectionName.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp
                            )
                        }
                    },
                    navigationIcon = {
                        if (uiState.isSelectionMode) {
                            IconButton(onClick = onExitSelectionMode) {
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
                        // Edit (pencil), enabled when exactly 1 is selected
                        val editAlpha = when {
                            uiState.isSelectionMode && uiState.selectedIds.size == 1 -> 1f
                            uiState.isSelectionMode -> 0.3f
                            else -> 0.15f
                        }
                        IconButton(
                            onClick = {
                                if (uiState.isSelectionMode && uiState.selectedIds.size == 1) {
                                    val id = uiState.selectedIds.first()
                                    uiState.wallpapers.find { it.id == id }?.let { onEditSelected(it) }
                                }
                            },
                            enabled = uiState.isSelectionMode && uiState.selectedIds.size == 1
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.icon_edit),
                                contentDescription = stringResource(R.string.cd_edit_wallpaper),
                                tint = NothingWhite.copy(alpha = editAlpha),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Delete (trash), enabled when ≥ 1 selected
                        val deleteAlpha = when {
                            uiState.isSelectionMode && uiState.selectedIds.isNotEmpty() -> 1f
                            uiState.isSelectionMode -> 0.3f
                            else -> 0.15f
                        }
                        IconButton(
                            onClick = {
                                if (uiState.isSelectionMode && uiState.selectedIds.isNotEmpty()) {
                                    showDeleteConfirmation = true
                                }
                            },
                            enabled = uiState.isSelectionMode && uiState.selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.icon_delete),
                                contentDescription = stringResource(R.string.cd_delete_wallpapers),
                                tint = NothingWhite.copy(alpha = deleteAlpha),
                                modifier = Modifier.size(20.dp)
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
                        bottom = padding.calculateBottomPadding() + 8.dp,
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.wallpapers, key = { it.id }) { wallpaper ->
                        val isSelected = wallpaper.id in uiState.selectedIds
                        val isCurrentPreview = wallpaper.id == uiState.previewWallpaper?.id
                        WallpaperThumbnail(
                            wallpaper = wallpaper,
                            isSelected = isSelected,
                            isSelectionMode = uiState.isSelectionMode,
                            // Shared only while the preview is opening or closing (or a
                            // flight is still running). While the preview sits settled —
                            // in particular while paging — no cell is shared, so a swipe
                            // can never re-match a cell and fire a stray flight.
                            // Attach shared bounds only while the preview is NOT fully settled
                            // open (i.e. during open/close flights). Once settled, no cell is
                            // shared, so pager swipes cannot re-match a cell and fire stray flights.
                            sharesPreviewBounds = isCurrentPreview && (
                                !(previewVisibleState.currentState && previewVisibleState.targetState) ||
                                    sharedScope.isTransitionActive
                                ),
                            hiddenForPreview = isCurrentPreview && previewVisibleState.targetState,
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    onToggleSelection(wallpaper.id)
                                } else if (!wallpaper.isAvailable) {
                                    onUnavailableTap(wallpaper)
                                } else {
                                    onWallpaperTap(wallpaper)
                                }
                            },
                            onLongClick = {
                                if (!uiState.isSelectionMode) {
                                    onWallpaperLongPress(wallpaper.id)
                                }
                            }
                        )
                    }
                }

                // Keep the currently viewed wallpaper's thumbnail composed while the
                // user swipes the pager (the grid is hidden behind the opaque overlay,
                // so the snap scroll is invisible) so dismissing always has a live
                // flight target that isn't covered by the opaque top bar.
                val density = LocalDensity.current
                LaunchedEffect(uiState.previewWallpaper?.id) {
                    val wallpaper = uiState.previewWallpaper ?: return@LaunchedEffect
                    if (!previewVisibleState.currentState) return@LaunchedEffect
                    val index = uiState.wallpapers.indexOfFirst { it.id == wallpaper.id }
                    if (index < 0) return@LaunchedEffect
                    val topSafePx = with(density) { (padding.calculateTopPadding() + 8.dp).toPx() }
                    val info = gridState.layoutInfo.visibleItemsInfo.find { it.key == wallpaper.id }
                    val safelyVisible = info != null &&
                        info.offset.y >= topSafePx &&
                        info.offset.y + info.size.height <= gridState.layoutInfo.viewportEndOffset
                    if (!safelyVisible) {
                        // scrollToItem aligns the item to the start of the content area,
                        // which the grid's contentPadding already keeps clear of the top bar.
                        gridState.scrollToItem(index)
                    }
                }
            }
        }

        // Full-screen preview overlay, opened/closed with a shared-element zoom.
        // Quick enter fade: the default spring's settling tail made the open feel
        // laggy (and the chrome waits for it to finish), while the flying image
        // covers the fade anyway. The exit keeps the default so the grid reveal
        // roughly tracks the shrink flight.
        AnimatedVisibility(
            visibleState = previewVisibleState,
            enter = fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = fadeOut()
        ) {
            WallpaperPreviewOverlay(
                wallpapers = uiState.wallpapers,
                initialIndex = pagerStartIndex,
                sharedTransitionScope = sharedScope,
                animatedVisibilityScope = this,
                sharedWallpaperId = uiState.previewWallpaper?.id,
                onDismiss = dismissPreview,
                onEdit = onEditFromPreview,
                onPageChanged = onPreviewPageChanged
            )
        }
    }

    // Delete confirmation
    if (showDeleteConfirmation) {
        val selectedCount = uiState.selectedIds.size
        ConfirmationOverlay(
            title = if (selectedCount == 1) stringResource(R.string.image_screen_delete_single_title)
                    else stringResource(R.string.image_screen_delete_multiple_title),
            message = if (selectedCount == 1) stringResource(R.string.image_screen_delete_single_message)
                    else stringResource(R.string.image_screen_delete_multiple_message),
            onConfirm = {
                showDeleteConfirmation = false
                if (selectedCount > 0) onDeleteSelected()
            },
            onCancel = { showDeleteConfirmation = false }
        )
    }

    // Re-link confirmation for a tapped unavailable image.
    if (uiState.relinkTarget != null) {
        ConfirmationOverlay(
            title = stringResource(R.string.relink_dialog_title),
            message = stringResource(R.string.relink_dialog_message),
            confirmLabel = stringResource(R.string.relink_dialog_confirm),
            cancelLabel = stringResource(R.string.relink_dialog_cancel),
            accentColor = NothingWhite,
            onConfirm = onRelinkConfirm,
            onCancel = onRelinkCancel
        )
    }
}

/**
 * Shown when a collection has no wallpapers yet.
 * Nudges the user to tap the FAB to add images.
 */
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
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
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
 * A single wallpaper cell inside the image grid.
 * Shows the edited version if available.
 * Supports tap (preview/toggle) and long-press (enter selection mode).
 *
 * Participates in the preview's shared-element zoom: the cell content hides while
 * its image is showing in the preview ([hiddenForPreview]), and carries the
 * sharedBounds modifier only while the preview is opening or closing
 * ([sharesPreviewBounds]). On open the cell's modifier is registered a frame
 * before it hides (flights need a pre-registered origin); on close the cell
 * re-enters as the overlay exits, so the image flies back into it. While the
 * preview is settled no cell is shared, so pager swipes cannot re-match a cell
 * and fire stray flights.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.WallpaperThumbnail(
    wallpaper: WallpaperImage,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    sharesPreviewBounds: Boolean,
    hiddenForPreview: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
            val flightModifier = if (sharesPreviewBounds) {
                Modifier.sharedBounds(
                    rememberSharedContentState(key = wallpaper.id),
                    animatedVisibilityScope = this@AnimatedVisibility,
                    boundsTransform = PreviewFlightBoundsTransform
                )
            } else {
                Modifier
            }
            Box(modifier = flightModifier.fillMaxSize()) {
                EditableWallpaperImage(
                    wallpaper = wallpaper,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp)
                        .clip(RoundedCornerShape(if (isSelectionMode && isSelected) 8.dp else 0.dp))
                        .background(NothingGray)
                )

                // Edited params badge (bottom-right of thumbnail)
                if (wallpaper.editParams != null) {
                    EditedBadge(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    )
                }

                // Unavailable badge (bottom-left of thumbnail)
                if (!wallpaper.isAvailable) {
                    UnavailableBadge(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                    )
                }

                // Selection indicator
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

// Previews

@Preview(showSystemUi = true, name = "Collection Images", backgroundColor = 0xFF000000)
@Composable
fun CollectionImageScreenPreview() {
    val sampleWallpapers = (1L..9L).map { id ->
        WallpaperImage(
            id = id,
            collectionId = 1,
            uri = Uri.EMPTY,
            editParams = if (id == 3L || id == 7L) EditParams(zoom = 1.5f, offsetX = 0f, offsetY = 0f) else null
        )
    }

    MaterialTheme {
        CollectionImageScreen(
            uiState = CollectionImageUiState(
                collectionName = "AMOLED HIGH",
                wallpapers = sampleWallpapers,
                isLoading = false
            ),
            onBackClick = {},
            onAddWallpapers = {},
            onWallpaperTap = {},
            onWallpaperLongPress = {},
            onToggleSelection = {},
            onExitSelectionMode = {},
            onDeleteSelected = {},
            onEditSelected = {},
            onEditFromPreview = {},
            onPreviewPageChanged = {},
            onClosePreview = {}
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
            uri = Uri.EMPTY
        )
    }

    MaterialTheme {
        CollectionImageScreen(
            uiState = CollectionImageUiState(
                collectionName = "FAVORITES",
                wallpapers = sampleWallpapers,
                isLoading = false,
                isSelectionMode = true,
                selectedIds = setOf(2L, 4L)
            ),
            onBackClick = {},
            onAddWallpapers = {},
            onWallpaperTap = {},
            onWallpaperLongPress = {},
            onToggleSelection = {},
            onExitSelectionMode = {},
            onDeleteSelected = {},
            onEditSelected = {},
            onEditFromPreview = {},
            onPreviewPageChanged = {},
            onClosePreview = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Empty Collection", backgroundColor = 0xFF000000)
@Composable
fun CollectionImageScreenEmptyPreview() {
    MaterialTheme {
        CollectionImageScreen(
            uiState = CollectionImageUiState(
                collectionName = "EMPTY COLLECTION",
                wallpapers = emptyList(),
                isLoading = false
            ),
            onBackClick = {},
            onAddWallpapers = {},
            onWallpaperTap = {},
            onWallpaperLongPress = {},
            onToggleSelection = {},
            onExitSelectionMode = {},
            onDeleteSelected = {},
            onEditSelected = {},
            onEditFromPreview = {},
            onPreviewPageChanged = {},
            onClosePreview = {}
        )
    }
}
