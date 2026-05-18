package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import android.net.Uri
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.ProcessingOverlay
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Full-screen wallpaper editor.
 *
 * The wallpaper fills the entire screen as the editing canvas.
 * Zooming and moving the image is supported via a pinch-to-zoom gesture.
 *
 * A collapsible bottom panel provides precision sliders and save/cancel actions.
 *
 * Zoom = 1.0 means the image covers/fills the screen.
 * Zoom can go below 1.0 (down to 0.5) to show more of the image with black bars.
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

    // Source image aspect ratio, needed to compute Fit-to-Cover scale boost
    var imageAspectRatio by remember { mutableFloatStateOf(1f) }

    // Controls panel visibility
    var showControls by remember { mutableStateOf(false) }

    // Restore saved edit params when wallpaper loads
    LaunchedEffect(wallpaper) {
        wallpaper?.let { wp ->
            zoom = wp.editZoom ?: 1f
            offsetX = wp.editOffsetX ?: 0f
            offsetY = wp.editOffsetY ?: 0f
        }
    }

    // Navigate back (to preview) when save completes successfully
    LaunchedEffect(uiState.saveComplete) {
        if (uiState.saveComplete) onBack()
    }

    // Gesture state, pinch and drag update zoom and offset together
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(0.5f, 5f)

        val panSensitivity = 0.003f / zoom
        offsetX = (offsetX + panChange.x * panSensitivity).coerceIn(-1f, 1f)
        offsetY = (offsetY + panChange.y * panSensitivity).coerceIn(-1f, 1f)
    }

    if (wallpaper == null) {
        // Loading state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NothingBlack),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isLoading) {
                Text(
                    text = "LOADING...",
                    style = MaterialTheme.typography.labelLarge,
                    color = NothingWhite.copy(alpha = 0.4f),
                    letterSpacing = 2.sp
                )
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().background(NothingBlack).clipToBounds()) {
            // Full-screen wallpaper canvas
            //
            // We use ContentScale.Fit so the FULL image is rendered (no clipping).
            // Then graphicsLayer scales up from Fit to Cover (matching the preview)
            // and applies user zoom + pan on top. This way panning reveals the
            // edges that would normally be cropped, instead of showing black.
            AsyncImage(
                model = wallpaper.uri,
                contentDescription = "Wallpaper being edited",
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val intrinsic = state.painter.intrinsicSize
                    if (intrinsic.width > 0 && intrinsic.height > 0) {
                        imageAspectRatio = intrinsic.width / intrinsic.height
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state = transformState)
                    .graphicsLayer {
                        val viewAspect = size.width / size.height

                        // ContentScale.Fit scales to fit within bounds.
                        // To match Cover (fill), we need an additional scale:
                        //   Cover/Fit = max(vW/iW, vH/iH) / min(vW/iW, vH/iH)
                        val fitToCoverRatio = if (imageAspectRatio > viewAspect) {
                            imageAspectRatio / viewAspect
                        } else {
                            viewAspect / imageAspectRatio
                        }

                        val totalScale = fitToCoverRatio * zoom
                        scaleX = totalScale
                        scaleY = totalScale

                        // Compute actual image size after Fit + graphicsLayer scale.
                        // Wide image: Fit fills width -> rendered = (viewW, viewW/imgAspect)
                        // Tall image: Fit fills height -> rendered = (viewH*imgAspect, viewH)
                        val (fitW, fitH) = if (imageAspectRatio > viewAspect) {
                            size.width to (size.width / imageAspectRatio)
                        } else {
                            (size.height * imageAspectRatio) to size.height
                        }
                        val imgScaledW = fitW * totalScale
                        val imgScaledH = fitH * totalScale

                        // Pan range = how much the image overflows the viewport
                        val maxPanX = ((imgScaledW - size.width) / 2f).coerceAtLeast(0f)
                        val maxPanY = ((imgScaledH - size.height) / 2f).coerceAtLeast(0f)

                        translationX = offsetX * maxPanX
                        translationY = offsetY * maxPanY
                    }
            )

            // Top bar overlay with gradient scrim
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
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
                    // Back button
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NothingWhite
                        )
                    }

                    Text(
                        text = "EDIT WALLPAPER",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = NothingWhite,
                        modifier = Modifier.weight(1f)
                    )

                    // Reset button, only visible when an edit exists
                    if (wallpaper.editedUri != null) {
                        IconButton(onClick = onReset) {
                            Icon(
                                painter = painterResource(R.drawable.icon_undo),
                                contentDescription = "Reset edit",
                                tint = NothingWhite,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Toggle controls panel
                    IconButton(onClick = { showControls = !showControls }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = if (showControls) "Hide controls" else "Show controls",
                            tint = if (showControls) NothingWhite else NothingWhite.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Bottom controls panel (collapsible)
            AnimatedVisibility(
                visible = showControls,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                ControlsPanel(
                    zoom = zoom,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    onZoomChange = { zoom = it.coerceIn(0.5f, 5f) },
                    onOffsetXChange = { offsetX = it.coerceIn(-1f, 1f) },
                    onOffsetYChange = { offsetY = it.coerceIn(-1f, 1f) },
                    onSave = { onSave(zoom, offsetX, offsetY) },
                    onCancel = onBack
                )
            }

            // When controls are hidden, show a floating save button
            if (!showControls) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Small floating save pill
                    IconButton(
                        onClick = { onSave(zoom, offsetX, offsetY) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(NothingWhite)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.icon_save),
                            contentDescription = "Save",
                            tint = NothingBlack,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }

    // Saving overlay
    AnimatedVisibility(
        visible = uiState.isSaving,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        ProcessingOverlay(message = "SAVING EDIT...")
    }

    // Error overlay
    AnimatedVisibility(
        visible = uiState.saveError,
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
                text = "SAVE FAILED — TRY AGAIN",
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
        editedUri = Uri.EMPTY
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