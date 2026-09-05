package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.intrinsicAspectRatio
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.previewFlightModifier
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.wallpaperEditThumbCacheKey
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.wallpaperThumbCacheKey
import com.ninecsdev.wallpaperchanger.ui.components.EditableWallpaperImage
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long after the open flight starts the chrome (overlay UI) begins fading in.
 */
private const val CHROME_ENTER_DELAY_MILLIS = 100L

/** Gap left between pages, visible mid-swipe */
private val PAGE_SPACING = 16.dp

/** How far the backdrop and the chrome fade at the far end of a way out. Image keeps full opacity */
private const val DISMISS_SCRIM_FADE = 0.7f

/** How much the image shrinks as a dismiss drag is pulled, so it reads as being taken away */
private const val DISMISS_DRAG_SHRINK = 0.2f

/** Longest the close waits for its departure bounds before going anyway. A few frames, not a feel. */
private const val LEAVE_FRAMING_TIMEOUT_MILLIS = 100L

/** Shared by all three pieces of chrome, so they leave together */
private val ChromeExitFade = fadeOut(animationSpec = tween(durationMillis = 100))

/**
 * Full-screen wallpaper preview overlay, browsed like a gallery: pinch to zoom, drag a zoomed
 * image around, swipe between wallpapers, and tap to hide or show the chrome.
 * Shows an edited badge if applicable, an edit button that navigates to the wallpaper editor,
 * and a star that makes the shown image its collection's default wallpaper.
 *
 * **Leaving** is the close button, system back, a pinch-in released below the exit threshold, or
 * a downward drag.
 *
 * Opened/closed with a shared-element zoom. A flight needs both ends registered
 * under the same key *before* it starts, so the page showing [sharedWallpaperId]
 * keeps its flight modifier (via [previewFlightModifier]) for the whole time the
 * preview is open: while settled it does nothing (the grid cell only registers
 * during open/close), but the close flight can start from it instantly.
 *
 * **Chrome visibility is one boolean driven by three events**
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
    defaultWallpaperId: Long? = null,
    onToggleFavorite: (WallpaperImage) -> Unit = {},
    onToggleDefault: (WallpaperImage) -> Unit = {},
    onDismiss: () -> Unit,
    onEdit: (WallpaperImage) -> Unit = {},
    onPageChanged: (WallpaperImage) -> Unit = {}
) {
    if (wallpapers.isEmpty()) return

    val safeInitialIndex = initialIndex.coerceIn(0, wallpapers.lastIndex)
    val dismiss by rememberUpdatedState(onDismiss)
    val context = LocalContext.current
    val (decodeWidth, decodeHeight) = remember { ImageProcessingUtils.getWallpaperCanvasSize(context) }

    val transition = animatedVisibilityScope.transition

    // Held above the pager's key() so a reopen seeds it rather than a rebuild resetting it
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
        val zoomState = rememberPreviewZoomState(initialPage = safeInitialIndex)

        // Reported on the settled page, not current one to stay mid-swipe
        LaunchedEffect(pagerState, wallpapers) {
            snapshotFlow { pagerState.settledPage }
                .collect { page ->
                    wallpapers.getOrNull(page)?.let(onPageChanged)
                }
        }

        // Zoom belongs to the page you are on, not to the photo so leaving resets it
        LaunchedEffect(pagerState, zoomState) {
            snapshotFlow { pagerState.settledPage }.collect { zoomState.handOverTo(it) }
        }

        // Leaving waits for the frozen values to have been laid out, because that layout is what
        // the close flies from: start it earlier and the flight reads the full screen rect
        LaunchedEffect(zoomState.leaveRequested) {
            if (zoomState.leaveRequested) {
                withTimeoutOrNull(LEAVE_FRAMING_TIMEOUT_MILLIS) {
                    snapshotFlow { zoomState.framedForLeave }.first { it }
                }
                dismiss()
            }
        }

        // Once the overlay is on its way out it stops taking touches at all
        val acceptsGestures = transition.targetState == EnterExitState.Visible && !zoomState.leaveRequested

        // A gesture that left froze this state mid-shrink for the close to unwind, so an open
        // that reuses it must seed from rest rather than from wherever the last one was abandoned
        LaunchedEffect(zoomState, transition.targetState) {
            if (transition.targetState == EnterExitState.Visible) zoomState.reset()
        }

        // The restore half of the chrome rule: the crossing back into rest, not the resting state
        LaunchedEffect(zoomState) {
            snapshotFlow { zoomState.isAtRest }
                .drop(1)
                .collect { atRest -> if (atRest) chromeVisible = true }
        }

        val currentWallpaper = wallpapers.getOrNull(pagerState.currentPage)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        color = NothingBlack,
                        alpha = 1f - zoomState.fadeProgress * DISMISS_SCRIM_FADE
                    )
                }
                .onSizeChanged { zoomState.onViewportSize(it.toSize()) },
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                pageSpacing = PAGE_SPACING,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val wallpaper = wallpapers[page]
                val isCurrent = zoomState.owns(page)
                val imageModifier = when {
                    wallpaper.id != sharedWallpaperId -> Modifier
                    else -> with(sharedTransitionScope) {
                        previewFlightModifier(
                            key = wallpaper.id,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                }
                // The detector sits on this untransformed Box and the transform goes on the image below.
                // A detector inside the zoomed layer reads each drag divided by the zoom
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // A zoomed page is larger than its slot, without this it draws over the page
                        // sliding in. Lifted while leaving (the close flies to a grid cell outside these bounds).
                        .then(
                            if (isCurrent && !zoomState.leaveRequested) Modifier.clipToBounds()
                            else Modifier
                        )
                        .then(
                            if (!acceptsGestures || !isCurrent) Modifier else Modifier.pointerInput(zoomState) {
                                detectPreviewGestures(
                                    state = zoomState,
                                    onTap = { chromeVisible = !chromeVisible },
                                    onDoubleTap = { focus ->
                                        zoomState.onDoubleTap(focus)
                                        chromeVisible = !zoomState.isAtRest
                                    },
                                    onGestureStart = { chromeVisible = false }
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Pan is clamped against the image's laid-out rect
                    val measureContent = Modifier.onSizeChanged {
                        if (isCurrent) zoomState.onContentSize(it.toSize())
                    }
                    // One state serves the pager, so only the page it belongs to reads it, they are mutually exclusive
                    val framing = Modifier.previewFraming(
                        zoomState,
                        active = isCurrent && !zoomState.gestureActive
                    )
                    val transform = Modifier.previewGestureTransform(
                        zoomState,
                        active = isCurrent && zoomState.gestureActive
                    )
                    // Order intencional "framing" must come before the flight modifier
                    // because it is what sets the laid-out bounds the close departs from, and
                    // "transform" after it because it is draw-only.
                    val pageContent = measureContent.then(framing).then(imageModifier).then(transform)
                    if (wallpaper.editParams == null) {
                        // The shared element is sized to the image's fit rect (not the full screen)
                        // and both flight ends use Crop. The aspect is seeded from the grid thumbnail
                        // so the flight targets the right rect from frame one, falls back to Fit
                        var imageAspect by remember(wallpaper.uriString) {
                            mutableStateOf(knownAspectRatios[wallpaper.id])
                        }
                        val aspect = imageAspect
                        // The flight renders only this content, so the grid thumbnail's cached bitmap
                        // stands in until the full-res decode lands (without it the flight starts blank)
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(wallpaper.uriString)
                                .size(decodeWidth, decodeHeight)
                                .placeholderMemoryCacheKey(wallpaperThumbCacheKey(wallpaper.uriString))
                                .build(),
                            contentDescription = stringResource(R.string.cd_wallpaper_preview),
                            contentScale = if (aspect == null) ContentScale.Fit else ContentScale.Crop,
                            onSuccess = { state ->
                                val resolved = state.intrinsicAspectRatio()
                                if (resolved != null) {
                                    val current = imageAspect
                                    // The seeded ratio can be a rounding pixel off; don't retarget an in-flight morph over <1%
                                    if (current == null || abs(resolved - current) > current * 0.01f) {
                                        imageAspect = resolved
                                        knownAspectRatios[wallpaper.id] = resolved
                                    }
                                }
                            },
                            // Sizing sets the flight's target bounds and inside them the image just crop-fills
                            modifier = if (aspect == null) Modifier.fillMaxSize().then(pageContent)
                                       else Modifier.aspectRatio(aspect).then(pageContent)
                        )
                    } else {
                        // Same crop-rect morph, placeholder, and sizing-before-element
                        // but self-crops at any bounds, so this end is simply the full screen.
                        EditableWallpaperImage(
                            wallpaper = wallpaper,
                            contentDescription = stringResource(R.string.cd_wallpaper_preview),
                            placeholderMemoryCacheKey = wallpaperEditThumbCacheKey(wallpaper.uriString),
                            modifier = Modifier.fillMaxSize().then(pageContent)
                        )
                    }
                }
            }

            // renderInSharedTransitionScopeOverlay lifts the chrome above the image so its fade stay visible
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(with(sharedTransitionScope) {
                        Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)
                    })
                    // Inside the overlay hand-off as the chrome fades out with the backdrop on the way out
                    .graphicsLayer { alpha = 1f - zoomState.fadeProgress }
            ) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + ChromeExitFade,
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    PreviewTopBar(
                        page = pagerState.currentPage + 1,
                        pageCount = wallpapers.size,
                        onClose = { zoomState.requestLeave() }
                    )
                }

                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + ChromeExitFade,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    PreviewActionBar(
                        wallpaper = currentWallpaper,
                        isFavorited = currentWallpaper?.fileId in favoriteFileIds,
                        isCollectionDefault = currentWallpaper != null &&
                            currentWallpaper.id == defaultWallpaperId,
                        onToggleFavorite = onToggleFavorite,
                        onEdit = onEdit,
                        onToggleDefault = onToggleDefault
                    )
                }

                AnimatedVisibility(
                    visible = chromeVisible && currentWallpaper?.editParams != null,
                    enter = fadeIn(),
                    exit = ChromeExitFade,
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    EditedBadge(size = 28, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

/**
 * The pan/zoom of a gesture in progress, as a draw-time transform to stay cheap. Invisible to layout,
 * which is why it hands straight over to [previewFraming] the moment the fingers leave.
 */
private fun Modifier.previewGestureTransform(state: PreviewZoomState, active: Boolean): Modifier =
    if (!active) this else this.graphicsLayer {
        val scale = state.shownScale
        scaleX = scale
        scaleY = scale
        translationX = state.pan.x
        translationY = state.pan.y + state.dismissTranslation
    }

/**
 * The scale the image is actually drawn at: the pinch's own zoom, pulled in as a dismiss drag
 * carries it away. Read by both halves of the transform, which have to agree exactly
 */
private val PreviewZoomState.shownScale: Float
    get() = zoom * (1f - DISMISS_DRAG_SHRINK * dismissProgress)

/**
 * The same pan/zoom, in *layout*: the image is measured and placed at the size and position it is
 * being shown at, while still reporting its full rect outward so nothing around it moves.
 *
 * This is the half the close needs. A shared element flies from the bounds its content was **laid out** at,
 * and it draws only the end that is arriving: on the way out that is the grid cell, which has no pan or zoom
 * of its own. So a draw-time transform on the preview side is not merely mistimed on the way out, it is never
 * consulted, and the flight departs from the untransformed full-screen rect however carefully
 * that transform is animated.
 */
private fun Modifier.previewFraming(state: PreviewZoomState, active: Boolean): Modifier =
    if (!active) this else this.layout { measurable, constraints ->
        val scale = state.shownScale
        // Transparent unless there is something to say
        val idle = scale == 1f && state.pan == Offset.Zero && state.dismissTranslation == 0f
        if (idle || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val asIs = measurable.measure(constraints)
            return@layout layout(asIs.width, asIs.height) {
                asIs.place(0, 0)
                state.onFramed()
            }
        }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeable = measurable.measure(
            Constraints.fixed((width * scale).roundToInt(), (height * scale).roundToInt())
        )
        layout(width, height) {
            placeable.place(
                ((width - placeable.width) / 2f + state.pan.x).roundToInt(),
                ((height - placeable.height) / 2f + state.pan.y + state.dismissTranslation).roundToInt()
            )
            state.onFramed()
        }
    }

@Composable
private fun PreviewTopBar(
    page: Int,
    pageCount: Int,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NothingBlack)
            .statusBarsPadding()
            .padding(bottom = 6.dp)
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(48.dp)
                .clip(CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_close),
                tint = NothingWhite,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = stringResource(R.string.preview_page_indicator, page, pageCount),
            style = NothingType.titleCaps,
            color = NothingWhite,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun PreviewActionBar(
    wallpaper: WallpaperImage?,
    isFavorited: Boolean,
    isCollectionDefault: Boolean,
    onToggleFavorite: (WallpaperImage) -> Unit,
    onEdit: (WallpaperImage) -> Unit,
    onToggleDefault: (WallpaperImage) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 48.dp, top = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Favourite toggle.
            PreviewActionButton(
                onClick = { wallpaper?.let(onToggleFavorite) },
                enabled = wallpaper != null
            ) {
                Icon(
                    imageVector = if (isFavorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(R.string.cd_favorite_wallpapers),
                    tint = if (isFavorited) NothingRed else NothingWhite,
                    modifier = Modifier.size(20.dp)
                )
            }

            PreviewActionButton(
                onClick = { wallpaper?.let(onEdit) },
                enabled = wallpaper != null
            ) {
                Icon(
                    painter = painterResource(R.drawable.icon_edit),
                    contentDescription = stringResource(R.string.cd_edit_wallpaper),
                    tint = NothingWhite,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Collection default toggle
            PreviewActionButton(
                onClick = { wallpaper?.let(onToggleDefault) },
                enabled = wallpaper != null
            ) {
                Icon(
                    painter = painterResource(
                        if (isCollectionDefault) R.drawable.icon_star else R.drawable.icon_star_outline
                    ),
                    contentDescription = stringResource(R.string.cd_collection_default),
                    tint = NothingWhite,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviewActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(NothingBlack.copy(alpha = 0.5f))
    ) {
        content()
    }
}

@Preview(showSystemUi = true, name = "Wallpaper Preview Overlay")
@Composable
fun WallpaperPreviewOverlayPreview() {
    val sampleWallpaper = WallpaperImage(
        id = 1L,
        collectionId = 101,
        uriString = ""
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
        uriString = "",
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
