package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush.Companion.verticalGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Full-screen wallpaper preview overlay.
 * Tap anywhere to dismiss. Shows edited badge if applicable.
 * Features an edit button to navigate to the wallpaper editor.
 * Lets swipe back and forth between wallpapers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WallpaperPreviewOverlay(
    wallpapers: List<WallpaperImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onEdit: (WallpaperImage) -> Unit = {},
    onPageChanged: (WallpaperImage) -> Unit = {}
) {
    if (wallpapers.isEmpty()) return

    val safeInitialIndex = initialIndex.coerceIn(0, wallpapers.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeInitialIndex,
        pageCount = { wallpapers.size }
    )

    LaunchedEffect(safeInitialIndex, wallpapers.size) {
        if (pagerState.currentPage != safeInitialIndex) {
            pagerState.scrollToPage(safeInitialIndex)
        }
    }

    LaunchedEffect(pagerState, wallpapers) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                wallpapers.getOrNull(page)?.let(onPageChanged)
            }
    }

    val currentWallpaper = wallpapers.getOrNull(pagerState.currentPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(onDismiss) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        onDismiss()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val wallpaper = wallpapers[page]
            val displayUri = wallpaper.editedUri ?: wallpaper.uri
            AsyncImage(
                model = displayUri,
                contentDescription = stringResource(R.string.cd_wallpaper_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top scrim + labels
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    brush = verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    )
                )
                .padding(top = 64.dp, bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.preview_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = NothingWhite
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.preview_tap_to_exit),
                    style = MaterialTheme.typography.bodySmall,
                    color = NothingWhite.copy(alpha = 0.7f)
                )
            }
        }

        // Bottom scrim + edit button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    brush = verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
                .padding(bottom = 48.dp, top = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { currentWallpaper?.let(onEdit) },
                enabled = currentWallpaper != null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    painter = painterResource(R.drawable.icon_edit),
                    contentDescription = stringResource(R.string.cd_edit_wallpaper),
                    tint = NothingWhite,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Edited badge in the bottom-right
        if (currentWallpaper?.editedUri != null) {
            EditedBadge(
                size = 28,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Preview Overlay")
@Composable
fun WallpaperPreviewOverlayPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 101,
        uri = Uri.EMPTY,
        editedUri = null
    )

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            WallpaperPreviewOverlay(
                wallpapers = listOf(sampleWallpaper),
                initialIndex = 0,
                onDismiss = {}
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Preview Overlay (Edited)")
@Composable
fun WallpaperPreviewOverlayEditedPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 2L,
        collectionId = 101,
        uri = Uri.EMPTY,
        editedUri = Uri.EMPTY
    )

    MaterialTheme {
        WallpaperPreviewOverlay(
            wallpapers = listOf(sampleWallpaper),
            initialIndex = 0,
            onDismiss = {}
        )
    }
}
