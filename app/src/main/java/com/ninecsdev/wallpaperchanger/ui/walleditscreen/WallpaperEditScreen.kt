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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.DeleteConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.components.ProcessingOverlay
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

// Constants and helpers to leave the UI code more readable
private const val MinZoom = 0.5f
private const val MaxZoom = 5f
private const val MinOffset = -1f
private const val MaxOffset = 1f
private const val PanSensitivity = 0.003f
private const val PanSensitivityXMultiplier = 1.25f
private fun coerceZoom(value: Float): Float = value.coerceIn(MinZoom, MaxZoom)

private fun coerceOffset(value: Float): Float = value.coerceIn(MinOffset, MaxOffset)


private fun calculateFitHeightZoom(
    imageAspectRatio: Float,
    viewAspect: Float,
): Float {
    if (imageAspectRatio <= 0f || viewAspect <= 0f) return 1f

    return if (imageAspectRatio > viewAspect) {
        imageAspectRatio / viewAspect
    } else {
        1f
    }
}

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

    var initialZoom by remember { mutableFloatStateOf(1f) }
    var initialOffsetX by remember { mutableFloatStateOf(0f) }
    var initialOffsetY by remember { mutableFloatStateOf(0f) }

    // Controls panel visibility
    var showControls by remember { mutableStateOf(false) }

    // Discard confirmation dialog visibility
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Restore saved edit params when wallpaper loads
    LaunchedEffect(wallpaper) {
        wallpaper?.let { wp ->
            val savedZoom = wp.editParams?.zoom ?: 1f
            val savedOffsetX = wp.editParams?.offsetX ?: 0f
            val savedOffsetY = wp.editParams?.offsetY ?: 0f

            zoom = savedZoom
            offsetX = savedOffsetX
            offsetY = savedOffsetY
            initialZoom = savedZoom
            initialOffsetX = savedOffsetX
            initialOffsetY = savedOffsetY
        }
    }

    HandleExit(shouldExit = uiState.shouldExit, onBack = onBack)

    val setZoom: (Float) -> Unit = { zoom = coerceZoom(it) }
    val setOffsetX: (Float) -> Unit = { offsetX = coerceOffset(it) }
    val setOffsetY: (Float) -> Unit = { offsetY = coerceOffset(it) }

    val hasUnsavedChanges = wallpaper != null && (
        !isCloseEnough(zoom, initialZoom) ||
            !isCloseEnough(offsetX, initialOffsetX) ||
            !isCloseEnough(offsetY, initialOffsetY)
        )
    val isSaveEnabled = hasUnsavedChanges && !uiState.isSaving
    val undoChanges = {
        zoom = initialZoom
        offsetX = initialOffsetX
        offsetY = initialOffsetY
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
        DeleteConfirmationOverlay(
            title = stringResource(R.string.edit_discard_title),
            message = stringResource(R.string.edit_discard_message),
            confirmLabel = stringResource(R.string.edit_discard_confirm),
            cancelLabel = stringResource(R.string.edit_discard_cancel),
            onConfirm = { showDiscardDialog = false; onBack() },
            onCancel = { showDiscardDialog = false }
        )
    }
}

//TODO: Extract the composable into separate files for readability

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
                val transform = calculateTransform(
                    viewWidth = size.width,
                    viewHeight = size.height,
                    imageAspectRatio = imageAspectRatio,
                    zoom = zoom,
                    offsetX = offsetX,
                    offsetY = offsetY
                )

                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.translationX
                translationY = transform.translationY
            }
    )
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
                style = MaterialTheme.typography.labelLarge,
                color = NothingWhite.copy(alpha = 0.4f),
                letterSpacing = 2.sp
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

        WallpaperEditTopBar(
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
private fun WallpaperEditTopBar(
    hasSavedEdits: Boolean,
    hasUnsavedChanges: Boolean,
    showControls: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onResetSaved: () -> Unit,
    onToggleControls: () -> Unit,
    onFitHeight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canResetSaved = !hasUnsavedChanges && hasSavedEdits
    val resetEnabled = hasUnsavedChanges || canResetSaved

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.7f),
                        Color.Transparent
                    )
                )
            )
            .padding(top = 40.dp, bottom = 24.dp, start = 4.dp, end = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = NothingWhite
                )
            }

            Text(
                text = stringResource(R.string.edit_screen_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = NothingWhite,
                modifier = Modifier.weight(1f)
            )

            if (resetEnabled) {
                IconButton(
                    onClick = {
                        when {
                            hasUnsavedChanges -> onUndo()
                            canResetSaved -> onResetSaved()
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.icon_undo),
                        contentDescription = when {
                            hasUnsavedChanges -> stringResource(R.string.cd_undo_changes)
                            canResetSaved -> stringResource(R.string.cd_reset_edit)
                            else -> stringResource(R.string.cd_reset_edit)
                        },
                        tint = NothingWhite,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            IconButton(onClick = onFitHeight) {
                Icon(
                    painter = painterResource(R.drawable.icon_fit_height),
                    contentDescription = stringResource(R.string.cd_fit_height),
                    tint = NothingWhite,
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onToggleControls) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = if (showControls) stringResource(R.string.cd_hide_controls) else stringResource(R.string.cd_show_controls),
                    tint = if (showControls) NothingWhite else NothingWhite.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

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

@Composable
private fun FloatingSaveButton(
    onSave: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) NothingWhite else NothingWhite.copy(alpha = 0.2f))
        ) {
            Icon(
                painter = painterResource(R.drawable.icon_save),
                contentDescription = stringResource(R.string.cd_save),
                tint = if (enabled) NothingBlack else NothingWhite.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
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
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = NothingWhite,
                modifier = Modifier
                    .background(
                        color = NothingWhite.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

// Previews

@Preview(showSystemUi = true, name = "Wallpaper Editor", backgroundColor = 0xFF000000,
    showBackground = false
)
@Composable
private fun WallpaperEditScreenPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 1,
        uri = Uri.EMPTY
    )

    MaterialTheme {
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

    MaterialTheme {
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

    MaterialTheme {
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

    MaterialTheme {
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
    MaterialTheme {
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