package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush.Companion.verticalGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * How long after the open flight starts the chrome (overlay UI) begins fading in.
 */
private const val CHROME_ENTER_DELAY_MILLIS = 100L

/**
 * Full-screen wallpaper preview overlay.
 * Tap anywhere to dismiss. Shows edited badge if applicable.
 * Features an edit button to navigate to the wallpaper editor.
 * Let's swipe back and forth between wallpapers.
 *
 * Opened/closed with a shared-element zoom. A flight needs both ends registered
 * under the same key *before* it starts, so the page showing [sharedWallpaperId]
 * keeps its flight modifier (via [previewFlightModifier]) for the whole time the
 * preview is open: while settled it does nothing (the grid cell only registers
 * during open/close), but the close flight can start from it instantly.
 *
 * The chrome (scrims, labels, edit button, badge) fades in [CHROME_ENTER_DELAY_MILLIS]
 * after the open starts and hides the instant a close starts.
 */
@Composable
internal fun WallpaperPreviewOverlay(
    wallpapers: List<WallpaperImage>,
    initialIndex: Int,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedWallpaperId: Long?,
    knownAspectRatios: MutableMap<Long, Float> = mutableMapOf(),
    favoriteFileIds: Set<Long> = emptySet(),
    onToggleFavorite: (WallpaperImage) -> Unit = {},
    onDismiss: () -> Unit,
    onEdit: (WallpaperImage) -> Unit = {},
    onPageChanged: (WallpaperImage) -> Unit = {}
) {
    if (wallpapers.isEmpty()) return

    val safeInitialIndex = initialIndex.coerceIn(0, wallpapers.lastIndex)
    val dismiss by rememberUpdatedState(onDismiss)

    val transition = animatedVisibilityScope.transition
    var chromeVisible by remember {
        mutableStateOf(
            transition.currentState == EnterExitState.Visible &&
                transition.targetState == EnterExitState.Visible
        )
    }

    LaunchedEffect(transition.targetState) {
        if (transition.targetState == EnterExitState.Visible) {
            if (transition.currentState != EnterExitState.Visible) {
                delay(CHROME_ENTER_DELAY_MILLIS)
            }
            chromeVisible = true
        } else {
            chromeVisible = false
        }
    }

    // Recreate the pager when a new open pins a different start page (covers
    // re-opening another wallpaper while the previous close is still fading out).
    key(safeInitialIndex) {
        val pagerState = rememberPagerState(
            initialPage = safeInitialIndex,
            pageCount = { wallpapers.size }
        )

        LaunchedEffect(pagerState, wallpapers) {
            snapshotFlow { pagerState.currentPage }
                .collect { page ->
                    wallpapers.getOrNull(page)?.let(onPageChanged)
                }
        }

        val currentWallpaper = wallpapers.getOrNull(pagerState.currentPage)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NothingBlack)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            dismiss()
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
                val imageModifier = when {
                    wallpaper.id != sharedWallpaperId -> Modifier
                    else -> with(sharedTransitionScope) {
                        previewFlightModifier(
                            key = wallpaper.id,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                }
                if (wallpaper.editParams == null) {
                    // The shared element is sized to the image's fit rect (not the
                    // full screen) and both flight ends use Crop, so the bounds
                    // animation square→fit-rect is itself the crop-rect morph. The
                    // aspect is seeded from the grid thumbnail so the flight targets
                    // the right rect from frame one; if never learned, fall back to
                    // fullscreen Fit until the full-res decode reports it
                    var imageAspect by remember(wallpaper.uri) {
                        mutableStateOf(knownAspectRatios[wallpaper.id])
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val aspect = imageAspect
                        // The flight renders only this content, so the grid
                        // thumbnail's cached bitmap stands in until the full-res
                        // decode lands (without it the flight starts blank)
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(wallpaper.uri)
                                .placeholderMemoryCacheKey(wallpaperThumbCacheKey(wallpaper.uri))
                                .build(),
                            contentDescription = stringResource(R.string.cd_wallpaper_preview),
                            contentScale = if (aspect == null) ContentScale.Fit else ContentScale.Crop,
                            onSuccess = { state ->
                                val resolved = state.intrinsicAspectRatio()
                                if (resolved != null) {
                                    val current = imageAspect
                                    // The seeded ratio can be a rounding pixel off; don't retarget an in-flight morph over <1%.
                                    if (current == null || abs(resolved - current) > current * 0.01f) {
                                        imageAspect = resolved
                                        knownAspectRatios[wallpaper.id] = resolved
                                    }
                                }
                            },
                            // Sizing must come BEFORE the shared element: it sets
                            // the flight's target bounds, and inside them the image
                            // just crop-fills. aspectRatio after the element instead
                            // letterboxed the image inside the flying bounds no morph
                            modifier = if (aspect == null) {
                                Modifier.fillMaxSize().then(imageModifier)
                            } else {
                                Modifier.aspectRatio(aspect).then(imageModifier)
                            }
                        )
                    }
                } else {
                    // Same crop-rect morph, placeholder, and sizing-before-element
                    // rules as above but self-crops at any bounds, so this end is simply the full screen.
                    EditableWallpaperImage(
                        wallpaper = wallpaper,
                        contentDescription = stringResource(R.string.cd_wallpaper_preview),
                        placeholderMemoryCacheKey = wallpaperEditThumbCacheKey(wallpaper.uri),
                        modifier = Modifier.fillMaxSize().then(imageModifier)
                    )
                }
            }

            // A flying shared element draws in an overlay layer above all normal content, which
            // hid the chrome's fades behind screen-covering images; renderInSharedTransitionScopeOverlay
            // lifts the chrome into that layer above the image so the fades stay visible
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = tween(durationMillis = 100)),
                modifier = Modifier
                    .matchParentSize()
                    .then(with(sharedTransitionScope) {
                        Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)
                    })
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top scrim + labels
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(
                                brush = verticalGradient(
                                    colors = listOf(
                                        NothingBlack.copy(alpha = 0.6f),
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
                                style = NothingType.titleCaps,
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
                                        NothingBlack.copy(alpha = 0.6f)
                                    )
                                )
                            )
                            .padding(bottom = 48.dp, top = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Favourite toggle.
                            val isFavorited = currentWallpaper?.fileId in favoriteFileIds
                            IconButton(
                                onClick = { currentWallpaper?.let(onToggleFavorite) },
                                enabled = currentWallpaper != null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(NothingBlack.copy(alpha = 0.5f))
                            ) {
                                Icon(
                                    imageVector = if (isFavorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = stringResource(R.string.cd_favorite_wallpapers),
                                    tint = if (isFavorited) NothingRed else NothingWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = { currentWallpaper?.let(onEdit) },
                                enabled = currentWallpaper != null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(NothingBlack.copy(alpha = 0.5f))
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.icon_edit),
                                    contentDescription = stringResource(R.string.cd_edit_wallpaper),
                                    tint = NothingWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Edited badge in the bottom-right
                    if (currentWallpaper?.editParams != null) {
                        EditedBadge(
                            size = 28,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Preview Overlay")
@Composable
fun WallpaperPreviewOverlayPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 101,
        uri = Uri.EMPTY
    )

    WallpaperChangerTheme {
        SharedTransitionLayout {
            AnimatedVisibility(visibleState = remember { MutableTransitionState(true) }) {
                WallpaperPreviewOverlay(
                    wallpapers = listOf(sampleWallpaper),
                    initialIndex = 0,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                    sharedWallpaperId = null,
                    onDismiss = {}
                )
            }
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
        editParams = EditParams(zoom = 1.5f, offsetX = 0f, offsetY = 0f)
    )

    WallpaperChangerTheme {
        SharedTransitionLayout {
            AnimatedVisibility(visibleState = remember { MutableTransitionState(true) }) {
                WallpaperPreviewOverlay(
                    wallpapers = listOf(sampleWallpaper),
                    initialIndex = 0,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                    sharedWallpaperId = null,
                    onDismiss = {}
                )
            }
        }
    }
}
