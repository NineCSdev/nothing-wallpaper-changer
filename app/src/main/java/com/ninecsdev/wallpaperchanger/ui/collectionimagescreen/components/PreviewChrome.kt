package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.ui.components.EditedBadge
import com.ninecsdev.wallpaperchanger.ui.components.cropRuleIconRes
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/** Shared by all three pieces of chrome, so they leave together */
private val ChromeExitFade = fadeOut(animationSpec = tween(durationMillis = 100))

private val SHEET_RIDE_GAP = 16.dp

private val ACTION_BAR_BOTTOM_PADDING = 48.dp

private val EDITED_BADGE_PADDING = 24.dp

private val ChipColor = NothingBlack.copy(alpha = 0.5f)
private const val NOT_APPLICABLE_ALPHA = 0.4f

@Composable
internal fun PreviewChrome(
    visible: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    fadeProgress: () -> Float,
    detailsSheet: PreviewDetailsSheetState,
    wallpaper: WallpaperImage?,
    page: Int,
    pageCount: Int,
    cropRule: CropRule,
    framedByRule: Boolean,
    isFavorited: Boolean,
    isCollectionDefault: Boolean,
    onClose: () -> Unit,
    onToggleFramedByRule: () -> Unit,
    onToggleFavorite: (WallpaperImage) -> Unit,
    onEdit: (WallpaperImage) -> Unit,
    onToggleDefault: (WallpaperImage) -> Unit,
    modifier: Modifier = Modifier
) {
    // renderInSharedTransitionScopeOverlay lifts the chrome above the image so its fade stays visible
    Box(
        modifier = modifier
            .then(with(sharedTransitionScope) { Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f) })
            // Inside the overlay hand-off, so the chrome fades out with the backdrop on the way out
            .graphicsLayer { alpha = 1f - fadeProgress() }
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + ChromeExitFade,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            PreviewTopBar(
                page = page,
                pageCount = pageCount,
                cropRule = cropRule,
                framing = when {
                    wallpaper?.editParams != null -> PageFraming.EDIT
                    framedByRule -> PageFraming.CROP_RULE
                    else -> PageFraming.WHOLE_PHOTO
                },
                onClose = onClose,
                onToggleFramedByRule = onToggleFramedByRule
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + ChromeExitFade,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .ridesDetailsSheet(detailsSheet, ACTION_BAR_BOTTOM_PADDING)
        ) {
            PreviewActionBar(
                wallpaper = wallpaper,
                isFavorited = isFavorited,
                isCollectionDefault = isCollectionDefault,
                onToggleFavorite = onToggleFavorite,
                onEdit = onEdit,
                onToggleDefault = onToggleDefault
            )
        }

        AnimatedVisibility(
            visible = visible &&
                !sharedTransitionScope.isTransitionActive &&
                wallpaper?.editParams != null,
            enter = fadeIn(),
            exit = ChromeExitFade,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .graphicsLayer { alpha = 1f - (detailsSheet.shownHeight / EDITED_BADGE_PADDING.toPx()).coerceIn(0f, 1f) }
        ) {
            EditedBadge(size = 28, modifier = Modifier.padding(EDITED_BADGE_PADDING))
        }
    }
}

/** Lifts the bottom action bar [restGap] once the details sheet reaches it. A layer offset, so taps follow it. */
private fun Modifier.ridesDetailsSheet(sheet: PreviewDetailsSheetState, restGap: Dp): Modifier = graphicsLayer {
    translationY = -(sheet.shownHeight - (restGap - SHEET_RIDE_GAP).toPx()).coerceAtLeast(0f)
}

private enum class PageFraming { WHOLE_PHOTO, CROP_RULE, EDIT }

@Composable
private fun PreviewTopBar(
    page: Int,
    pageCount: Int,
    cropRule: CropRule,
    framing: PageFraming,
    onClose: () -> Unit,
    onToggleFramedByRule: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PreviewActionButton(onClick = onClose, enabled = true) {
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
                modifier = Modifier
                    .clip(CircleShape)
                    .background(ChipColor)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
            FramingToggle(cropRule = cropRule, framing = framing, onToggle = onToggleFramedByRule)
        }
    }
}

/**
 * Off it shows the whole-photo icon; on it shows the rule's own icon, white, so it names the rule
 * it frames by. On an edited page it shows off but dimmed, since the edit already frames it.
 */
@Composable
private fun FramingToggle(
    cropRule: CropRule,
    framing: PageFraming,
    onToggle: () -> Unit
) {
    val on = framing == PageFraming.CROP_RULE
    PreviewActionButton(
        onClick = onToggle,
        enabled = framing != PageFraming.EDIT,
        containerColor = if (on) NothingWhite else ChipColor,
        modifier = Modifier.alpha(if (framing == PageFraming.EDIT) NOT_APPLICABLE_ALPHA else 1f)
    ) {
        Icon(
            painter = painterResource(if (on) cropRuleIconRes(cropRule) else R.drawable.icon_fit_screen),
            contentDescription = stringResource(
                when (framing) {
                    PageFraming.WHOLE_PHOTO -> R.string.cd_preview_frame_by_crop_rule
                    PageFraming.CROP_RULE -> R.string.cd_preview_show_whole_photo
                    PageFraming.EDIT -> R.string.cd_preview_framed_by_edit
                }
            ),
            tint = if (on) NothingBlack else NothingWhite,
            modifier = Modifier.size(20.dp)
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
            .padding(bottom = ACTION_BAR_BOTTOM_PADDING, top = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
    modifier: Modifier = Modifier,
    containerColor: Color = ChipColor,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(containerColor)
    ) {
        content()
    }
}
