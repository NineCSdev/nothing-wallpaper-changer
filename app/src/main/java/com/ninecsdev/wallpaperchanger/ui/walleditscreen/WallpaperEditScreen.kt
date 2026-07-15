package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.logic.computeEditTransform
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ProcessingOverlay
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.SmallCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Full-screen wallpaper editor.
 *
 * The wallpaper fills the entire screen as the editing canvas.
 * Zooming and moving the image is supported via a pinch-to-zoom gesture.
 *
 * A collapsible bottom panel provides precision sliders and save/cancel actions.
 *
 * Zoom = 1.0 means the image fits the screen completely (letterboxed/pillarboxed if needed).
 * Zoom > 1.0 scales the image up (to cover and beyond).
 * Offset values are normalized to -1..1 representing the full available pan range.
 */
@Composable
fun WallpaperEditScreen(
    uiState: WallpaperEditUiState,
    onSave: (zoom: Float, offsetX: Float, offsetY: Float) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val wallpaper = uiState.wallpaper

    // Edit state, owned here so gestures update directly
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Saved params the current values are compared against (undo target / dirty check)
    var baseline by remember { mutableStateOf(EditParams(zoom = 1f, offsetX = 0f, offsetY = 0f)) }

    // Controls panel visibility
    var showControls by remember { mutableStateOf(false) }

    // Discard confirmation dialog visibility
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Restore saved edit params when wallpaper loads
    LaunchedEffect(wallpaper) {
        wallpaper?.let { wp ->
            val saved = wp.editParams ?: EditParams(zoom = 1f, offsetX = 0f, offsetY = 0f)
            zoom = saved.zoom
            offsetX = saved.offsetX
            offsetY = saved.offsetY
            baseline = saved
        }
    }

    HandleExit(shouldExit = uiState.shouldExit, onBack = onBack)

    val setZoom: (Float) -> Unit = { zoom = coerceZoom(it) }
    val setOffsetX: (Float) -> Unit = { offsetX = coerceOffset(it) }
    val setOffsetY: (Float) -> Unit = { offsetY = coerceOffset(it) }

    val hasUnsavedChanges = wallpaper != null &&
        !matchesEditParams(baseline, zoom, offsetX, offsetY)
    val isSaveEnabled = hasUnsavedChanges && !uiState.isSaving
    val undoChanges = {
        zoom = baseline.zoom
        offsetX = baseline.offsetX
        offsetY = baseline.offsetY
    }

    val guardedBack: () -> Unit = {
        if (hasUnsavedChanges) showDiscardDialog = true else onBack()
    }

    // Intercept the system back gesture when there are unsaved changes
    BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    if (wallpaper == null) {
        WallpaperLoadingState(isLoading = uiState.isLoading)
    } else {
        WallpaperEditContent(
            wallpaper = wallpaper,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
            hasUnsavedChanges = hasUnsavedChanges,
            isSaveEnabled = isSaveEnabled,
            showControls = showControls,
            onZoomChange = setZoom,
            onOffsetXChange = setOffsetX,
            onOffsetYChange = setOffsetY,
            onToggleControls = { showControls = !showControls },
            onSave = { onSave(zoom, offsetX, offsetY) },
            onUndo = undoChanges,
            onReset = onReset,
            onBack = guardedBack
        )
    }

    SavingOverlay(isSaving = uiState.isSaving)
    SaveErrorBanner(show = uiState.saveError)

    // Discard confirmation dialog
    if (showDiscardDialog) {
        ConfirmationOverlay(
            title = stringResource(R.string.edit_discard_title),
            message = stringResource(R.string.edit_discard_message),
            confirmLabel = stringResource(R.string.edit_discard_confirm),
            cancelLabel = stringResource(R.string.edit_discard_cancel),
            onConfirm = { showDiscardDialog = false; onBack() },
            onCancel = { showDiscardDialog = false }
        )
    }
}

@Composable
private fun HandleExit(
    shouldExit: Boolean,
    onBack: () -> Unit,
) {
    LaunchedEffect(shouldExit) {
        if (shouldExit) {
            onBack()
        }
    }
}

@Composable
private fun WallpaperCanvas(
    wallpaperUri: Uri,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    imageAspectRatio: Float,
    onImageAspectRatioChange: (Float) -> Unit,
    onViewAspectChange: (Float) -> Unit,
    onZoomChange: (Float) -> Unit,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Gesture state, pinch and drag update zoom and offset together
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextZoom = coerceZoom(zoom * zoomChange)
        val panSensitivity = PanSensitivity / nextZoom
        val nextOffsetX = coerceOffset(offsetX + panChange.x * panSensitivity * PanSensitivityXMultiplier)
        val nextOffsetY = coerceOffset(offsetY + panChange.y * panSensitivity)

        onZoomChange(nextZoom)
        onOffsetXChange(nextOffsetX)
        onOffsetYChange(nextOffsetY)
    }

    AsyncImage(
        model = wallpaperUri,
        contentDescription = stringResource(R.string.cd_wallpaper_being_edited),
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            val intrinsic = state.painter.intrinsicSize
            if (intrinsic.width > 0 && intrinsic.height > 0) {
                onImageAspectRatioChange(intrinsic.width / intrinsic.height)
            }
        },
        modifier = modifier
            .transformable(state = transformState)
            .onSizeChanged { size ->
                if (size.height > 0) {
                    onViewAspectChange(size.width.toFloat() / size.height)
                }
            }
            .graphicsLayer {
                val transform = computeEditTransform(
                    contentWidth = imageAspectRatio,
                    contentHeight = 1f,
                    containerWidth = size.width,
                    containerHeight = size.height,
                    zoom = zoom,
                    offsetX = offsetX,
                    offsetY = offsetY
                )

                scaleX = zoom
                scaleY = zoom
                translationX = transform.panX
                translationY = transform.panY
            }
    )
}

@Composable
private fun WallpaperLoadingState(isLoading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NothingBlack),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            Text(
                text = stringResource(R.string.edit_screen_loading),
                style = NothingType.overlayMessage,
                color = NothingWhite.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun WallpaperEditContent(
    wallpaper: WallpaperImage,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    hasUnsavedChanges: Boolean,
    isSaveEnabled: Boolean,
    showControls: Boolean,
    onZoomChange: (Float) -> Unit,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    onToggleControls: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var imageAspectRatio by remember { mutableFloatStateOf(1f) }
    var viewAspect by remember { mutableFloatStateOf(1f) }
    val onFitHeight = {
        val targetZoom = calculateFitHeightZoom(
            imageAspectRatio = imageAspectRatio,
            viewAspect = viewAspect
        )
        onZoomChange(coerceZoom(targetZoom))
        onOffsetXChange(0f)
        onOffsetYChange(0f)
    }

    Box(modifier = Modifier.fillMaxSize().background(NothingBlack).clipToBounds()) {
        WallpaperCanvas(
            wallpaperUri = wallpaper.uri,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
            imageAspectRatio = imageAspectRatio,
            onImageAspectRatioChange = { imageAspectRatio = it },
            onViewAspectChange = { viewAspect = it },
            onZoomChange = onZoomChange,
            onOffsetXChange = onOffsetXChange,
            onOffsetYChange = onOffsetYChange,
            modifier = Modifier.fillMaxSize()
        )

        EditTopBar(
            hasSavedEdits = wallpaper.editParams != null,
            hasUnsavedChanges = hasUnsavedChanges,
            showControls = showControls,
            onBack = onBack,
            onUndo = onUndo,
            onResetSaved = onReset,
            onToggleControls = onToggleControls,
            onFitHeight = onFitHeight,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        WallpaperEditControlsPanel(
            visible = showControls,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
            onZoomChange = onZoomChange,
            onOffsetXChange = onOffsetXChange,
            onOffsetYChange = onOffsetYChange,
            isSaveEnabled = isSaveEnabled,
            onSave = onSave,
            onCancel = onBack,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (!showControls && hasUnsavedChanges) {
            FloatingSaveButton(
                onSave = onSave,
                enabled = isSaveEnabled,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun SavingOverlay(isSaving: Boolean) {
    AnimatedVisibility(
        visible = isSaving,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        ProcessingOverlay(message = stringResource(R.string.edit_screen_saving))
    }
}

@Composable
private fun SaveErrorBanner(show: Boolean) {
    AnimatedVisibility(
        visible = show,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = stringResource(R.string.edit_screen_save_failed),
                style = NothingType.errorBanner,
                color = NothingWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(
                        color = NothingWhite.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(SmallCornerRadius)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * Animated wrapper that slides [ControlsPanel] in and out from the bottom
 * of the screen based on [visible].
 */
@Composable
private fun WallpaperEditControlsPanel(
    visible: Boolean,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    onZoomChange: (Float) -> Unit,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    isSaveEnabled: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        ControlsPanel(
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
            onZoomChange = onZoomChange,
            onOffsetXChange = onOffsetXChange,
            onOffsetYChange = onOffsetYChange,
            isSaveEnabled = isSaveEnabled,
            onSave = onSave,
            onCancel = onCancel
        )
    }
}

// Previews

@Preview(showSystemUi = true, name = "Wallpaper Editor", backgroundColor = 0xFF000000, showBackground = false)
@Composable
private fun WallpaperEditScreenPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 1,
        uri = Uri.EMPTY
    )

    WallpaperChangerTheme {
        WallpaperEditScreen(
            uiState = WallpaperEditUiState(
                wallpaper = sampleWallpaper,
                isLoading = false
            ),
            onSave = { _, _, _ -> },
            onReset = {},
            onBack = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Editor (Reset Available)", backgroundColor = 0xFF000000)
@Composable
private fun WallpaperEditScreenResetPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 1,
        uri = Uri.EMPTY,
        editParams = EditParams(zoom = 1.5f, offsetX = 0f, offsetY = 0f)
    )

    WallpaperChangerTheme {
        WallpaperEditScreen(
            uiState = WallpaperEditUiState(
                wallpaper = sampleWallpaper,
                isLoading = false
            ),
            onSave = { _, _, _ -> },
            onReset = {},
            onBack = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Editor (Saving)", backgroundColor = 0xFF000000)
@Composable
private fun WallpaperEditScreenSavingPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 1,
        uri = Uri.EMPTY
    )

    WallpaperChangerTheme {
        WallpaperEditScreen(
            uiState = WallpaperEditUiState(
                wallpaper = sampleWallpaper,
                isLoading = false,
                isSaving = true
            ),
            onSave = { _, _, _ -> },
            onReset = {},
            onBack = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Editor (Error)", backgroundColor = 0xFF000000)
@Composable
private fun WallpaperEditScreenErrorPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 1,
        uri = Uri.EMPTY
    )

    WallpaperChangerTheme {
        WallpaperEditScreen(
            uiState = WallpaperEditUiState(
                wallpaper = sampleWallpaper,
                isLoading = false,
                saveError = true
            ),
            onSave = { _, _, _ -> },
            onReset = {},
            onBack = {}
        )
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Editor (Loading)", backgroundColor = 0xFF000000)
@Composable
private fun WallpaperEditScreenLoadingPreview() {
    WallpaperChangerTheme {
        WallpaperEditScreen(
            uiState = WallpaperEditUiState(
                wallpaper = null,
                isLoading = true
            ),
            onSave = { _, _, _ -> },
            onReset = {},
            onBack = {}
        )
    }
}