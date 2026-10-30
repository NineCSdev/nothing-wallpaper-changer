package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.source.SourceDetails
import com.ninecsdev.wallpaperchanger.logic.CropPlacement
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.intrinsicAspectRatio
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.previewFlightModifier
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.wallpaperEditThumbCacheKey
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.wallpaperThumbCacheKey
import com.ninecsdev.wallpaperchanger.ui.components.EditableWallpaperImage
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

private const val CHROME_ENTER_DELAY_MILLIS = 100L

private val PAGE_SPACING = 16.dp

// How far the backdrop and chrome fade at the far end of a way out; the image stays opaque
private const val DISMISS_SCRIM_FADE = 0.7f

/** Longest the close waits for its departure bounds before going anyway. A few frames, not a feel. */
private const val LEAVE_FRAMING_TIMEOUT_MILLIS = 100L

/**
 * A flight needs both ends registered under the same key before it starts, so the page showing
 * [sharedWallpaperId] keeps its flight modifier while open (inert while settled).
 * Chrome: a tap toggles it, a gesture hides it, the image crossing back into rest restores it; while
 * the details sheet is up, taps and the close button close the sheet instead.
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
    cropRule: CropRule = WallpaperCollection.DEFAULT_CROP_RULE,
    framedByRule: Boolean = false,
    onToggleFramedByRule: () -> Unit = {},
    onToggleFavorite: (WallpaperImage) -> Unit = {},
    onToggleDefault: (WallpaperImage) -> Unit = {},
    onDismiss: () -> Unit,
    onEdit: (WallpaperImage) -> Unit = {},
    onPageChanged: (WallpaperImage) -> Unit = {},
    loadSourceDetails: suspend (WallpaperImage) -> SourceDetails = { SourceDetails() }
) {
    if (wallpapers.isEmpty()) return

    val safeInitialIndex = initialIndex.coerceIn(0, wallpapers.lastIndex)
    val dismiss by rememberUpdatedState(onDismiss)

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
                delay(CHROME_ENTER_DELAY_MILLIS.milliseconds)
            }
            chromeVisible = true
        } else {
            chromeVisible = false
        }
    }

    // Recreate the pager when a new open pins a different start page, e.g. reopening another wallpaper mid-fade-out
    key(safeInitialIndex) {
        val pagerState = rememberPagerState(
            initialPage = safeInitialIndex,
            pageCount = { wallpapers.size }
        )
        val zoomState = rememberPreviewZoomState(initialPage = safeInitialIndex)
        val detailsSheet = rememberPreviewDetailsSheetState()

        fun closeSheetOr(action: () -> Unit) = if (detailsSheet.isShown) detailsSheet.close() else action()

        val acceptsGestures = rememberUpdatedState(
            transition.currentState == EnterExitState.Visible &&
                transition.targetState == EnterExitState.Visible &&
                !zoomState.leaveRequested
        )

        // Settled page, not current, so nothing changes mid-swipe
        LaunchedEffect(pagerState, wallpapers) {
            snapshotFlow { pagerState.settledPage }
                .collect { page ->
                    if (!acceptsGestures.value) return@collect
                    wallpapers.getOrNull(page)?.let(onPageChanged)
                }
        }

        // Zoom belongs to the page you are on, not to the photo, so leaving resets it
        LaunchedEffect(pagerState, zoomState) {
            snapshotFlow { pagerState.settledPage }.collect { zoomState.handOverTo(it) }
        }

        // Leaving waits for the frozen values to have been laid out, because that layout is what
        // the close flies from: start it earlier and the flight reads the full screen rect
        LaunchedEffect(zoomState.leaveRequested) {
            if (zoomState.leaveRequested) {
                withTimeoutOrNull(LEAVE_FRAMING_TIMEOUT_MILLIS.milliseconds) {
                    snapshotFlow { zoomState.framedForLeave }.first { it }
                }
                dismiss()
            }
        }

        // A gesture that left froze this state mid-shrink for the close to unwind, so an open
        // that reuses it must seed from rest rather than from wherever the last one was abandoned
        LaunchedEffect(zoomState, transition.targetState) {
            if (transition.targetState == EnterExitState.Visible) {
                zoomState.reset()
                detailsSheet.reset()
            }
        }

        BackHandler(enabled = detailsSheet.isShown && acceptsGestures.value) { detailsSheet.close() }

        // The restore half of the chrome rule: the crossing back into rest, not the resting state
        LaunchedEffect(zoomState) {
            snapshotFlow { zoomState.isAtRest }
                .drop(1)
                .collect { atRest -> if (atRest) chromeVisible = true }
        }

        val currentWallpaper = wallpapers.getOrNull(pagerState.currentPage)

        // FIT already shows the whole photo, so framing by it changes nothing on screen
        val uneditedFraming = cropRule.takeIf { framedByRule && it != CropRule.FIT }

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
                userScrollEnabled = acceptsGestures.value,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val wallpaper = wallpapers[page]
                val flightModifier = if (wallpaper.id != sharedWallpaperId) Modifier
                else with(sharedTransitionScope) {
                    previewFlightModifier(key = wallpaper.id, animatedVisibilityScope = animatedVisibilityScope)
                }
                PreviewPage(
                    wallpaper = wallpaper,
                    isCurrent = zoomState.owns(page),
                    acceptsGestures = acceptsGestures.value,
                    zoomState = zoomState,
                    detailsSheet = detailsSheet,
                    flightModifier = flightModifier,
                    knownAspectRatios = knownAspectRatios,
                    uneditedFraming = uneditedFraming,
                    cropRule = cropRule,
                    loadSourceDetails = loadSourceDetails,
                    onTap = { closeSheetOr { chromeVisible = !chromeVisible } },
                    onDoubleTap = { focus ->
                        closeSheetOr {
                            zoomState.onDoubleTap(focus)
                            chromeVisible = !zoomState.isAtRest
                        }
                    },
                    onGestureStart = { chromeVisible = false }
                )
            }

            PreviewChrome(
                visible = chromeVisible,
                sharedTransitionScope = sharedTransitionScope,
                fadeProgress = { zoomState.fadeProgress },
                detailsSheet = detailsSheet,
                wallpaper = currentWallpaper,
                page = pagerState.currentPage + 1,
                pageCount = wallpapers.size,
                cropRule = cropRule,
                framedByRule = framedByRule,
                isFavorited = currentWallpaper?.fileId in favoriteFileIds,
                isCollectionDefault = currentWallpaper != null && currentWallpaper.id == defaultWallpaperId,
                onClose = { closeSheetOr { zoomState.requestLeave() } },
                onToggleFramedByRule = onToggleFramedByRule,
                onToggleFavorite = onToggleFavorite,
                onEdit = onEdit,
                onToggleDefault = onToggleDefault,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** One [zoomState] serves the whole pager, so only the [current][isCurrent] page reads it. */
@Suppress("ModifierParameter") // flightModifier goes on the image, not on the page's root
@Composable
private fun PreviewPage(
    wallpaper: WallpaperImage,
    isCurrent: Boolean,
    acceptsGestures: Boolean,
    zoomState: PreviewZoomState,
    detailsSheet: PreviewDetailsSheetState,
    flightModifier: Modifier,
    knownAspectRatios: MutableMap<Long, Float>,
    uneditedFraming: CropRule?,
    cropRule: CropRule,
    loadSourceDetails: suspend (WallpaperImage) -> SourceDetails,
    onTap: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onGestureStart: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // The detector sits on this untransformed Box and the transform goes on the image below.
        // A detector inside the zoomed layer reads each drag divided by the zoom
        Box(
            modifier = Modifier
                .fillMaxSize()
                // A zoomed page is larger than its slot, without this it draws over the page
                // sliding in. Lifted while leaving (the close flies to a grid cell outside these bounds).
                .then(if (isCurrent && !zoomState.leaveRequested) Modifier.clipToBounds() else Modifier)
                .then(
                    if (!acceptsGestures || !isCurrent) Modifier else Modifier.pointerInput(zoomState) {
                        detectPreviewGestures(
                            state = zoomState,
                            onTap = onTap,
                            onDoubleTap = onDoubleTap,
                            onGestureStart = onGestureStart,
                            details = detailsSheet
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            // Pan is clamped against the image's laid-out rect
            val measureContent = Modifier.onSizeChanged {
                if (isCurrent) zoomState.onContentSize(it.toSize())
            }
            val framing = Modifier.previewFraming(zoomState, active = isCurrent && !zoomState.gestureActive)
            val transform = Modifier.previewGestureTransform(zoomState, active = isCurrent && zoomState.gestureActive)
            // Order is load-bearing: framing sets the laid-out bounds the close flight departs from,
            // so it comes before the flight modifier, and transform is draw-only, so it comes after
            val pageContent = measureContent.then(framing).then(flightModifier).then(transform)
            if (wallpaper.editParams == null) {
                UneditedPreviewImage(
                    wallpaper = wallpaper,
                    knownAspectRatios = knownAspectRatios,
                    framing = uneditedFraming,
                    modifier = pageContent
                )
            } else {
                // Self-crops at any bounds, so this end is simply the full screen
                EditableWallpaperImage(
                    wallpaper = wallpaper,
                    contentDescription = stringResource(R.string.cd_wallpaper_preview),
                    placeholderMemoryCacheKey = wallpaperEditThumbCacheKey(wallpaper.uriString),
                    modifier = Modifier.fillMaxSize().then(pageContent)
                )
            }
        }

        if (detailsSheet.isShown) {
            PreviewDetailsSheet(
                state = detailsSheet,
                wallpaper = wallpaper,
                cropRule = cropRule,
                loadSourceDetails = loadSourceDetails,
                reportsHeight = isCurrent
            )
        }
    }
}

/**
 * The shared element is sized to the image's fit rect (not the full screen) and both flight ends
 * use Crop. The aspect is seeded from the grid thumbnail so the flight targets the right rect from
 * frame one, and falls back to Fit until the image reports its own.
 */
@Composable
private fun UneditedPreviewImage(
    wallpaper: WallpaperImage,
    knownAspectRatios: MutableMap<Long, Float>,
    framing: CropRule?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val (decodeWidth, decodeHeight) = remember { ImageProcessingUtils.getWallpaperCanvasSize(context) }
    val framingScale = remember(framing) { framing?.let(::CropRuleContentScale) }
    val framingAlignment = remember(framing) {
        framing?.let { rule ->
            val bias = CropPlacement.horizontalBias(rule)
            CropRuleAlignment { bias }
        }
    }
    var imageAspect by remember(wallpaper.uriString) { mutableStateOf(knownAspectRatios[wallpaper.id]) }
    val aspect = imageAspect

    // The flight renders only this content, so the grid thumbnail's cached bitmap
    // stands in until the full-res decode lands (without it the flight starts blank)
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(wallpaper.uriString)
            .size(decodeWidth, decodeHeight)
            // Covering the canvas is the resolution a cropped wallpaper is applied at,
            // so zooming in never looks softer than the real wallpaper
            .scale(Scale.FILL)
            .placeholderMemoryCacheKey(wallpaperThumbCacheKey(wallpaper.uriString))
            .build(),
        contentDescription = stringResource(R.string.cd_wallpaper_preview),
        contentScale = when {
            framingScale != null -> framingScale
            aspect == null -> ContentScale.Fit
            else -> ContentScale.Crop
        },
        alignment = framingAlignment ?: Alignment.Center,
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
        // Sizing sets the flight's target bounds and inside them the image just crop-fills.
        // Framed by the rule it fills the screen like an edited page, so zoom and pan
        // clamp against the screen and the close departs from what is shown
        modifier = if (aspect == null || framing != null) Modifier.fillMaxSize().then(modifier)
                   else Modifier.aspectRatio(aspect).then(modifier)
    )
}

@Composable
private fun OverlayPreviewHost(wallpaper: WallpaperImage) {
    WallpaperChangerTheme {
        SharedTransitionLayout {
            AnimatedVisibility(visibleState = remember { MutableTransitionState(true) }) {
                WallpaperPreviewOverlay(
                    wallpapers = listOf(wallpaper),
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

@Preview(showSystemUi = true, name = "Wallpaper Preview Overlay")
@Composable
fun WallpaperPreviewOverlayPreview() {
    OverlayPreviewHost(WallpaperImage(id = 1L, collectionId = 101, uriString = ""))
}

@Preview(showSystemUi = true, name = "Wallpaper Preview Overlay (Edited)")
@Composable
fun WallpaperPreviewOverlayEditedPreview() {
    OverlayPreviewHost(
        WallpaperImage(
            id = 2L,
            collectionId = 101,
            uriString = "",
            editParams = EditParams(zoom = 1.5f, offsetX = 0f, offsetY = 0f)
        )
    )
}
