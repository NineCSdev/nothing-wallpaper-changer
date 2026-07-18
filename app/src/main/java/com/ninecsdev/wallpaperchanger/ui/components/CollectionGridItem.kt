package com.ninecsdev.wallpaperchanger.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Data state for the Grid Item for performance
 **/
data class CollectionPreviewState(
    val previewUris: List<Uri> = emptyList(),
    val totalCount: Int = 0
)

/**
 * A square grid item representing a wallpaper collection.
 * Shows the first 4 wallpapers in a 2x2 with the 4th blurred out.
 * Shared between the collections screen and the collection picker sheet.
 */
@Composable
fun CollectionGridItem(
    name: String,
    state: CollectionPreviewState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    isActive: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Container for the 2x2 grid
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(color = NothingGray)
                .then(
                    if (isActive) Modifier.border(2.dp, NothingWhite, RoundedCornerShape(16.dp))
                    else Modifier
                )
        ) {
            GridContent(state.previewUris, state.totalCount)

            // Pinned marker (system/Favourites) collections, top-left over the preview.
            if (isPinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(NothingWhite),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.icon_pinned),
                        contentDescription = stringResource(R.string.cd_favorites_collection),
                        tint = NothingBlack,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Active-collection marker, top-right over the preview only for when selecting active from main screen.
            if (isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(NothingWhite),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_active_collection),
                        tint = NothingBlack,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Name of the collection
        Text(
            text = name.uppercase(),
            style = NothingType.metaLabel,
            color = NothingWhite.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Internal grid logic to separate layout from data handling.
 */
@Composable
private fun GridContent(uris: List<Uri>, totalCount: Int) {
    val cornerRadius = 16.dp

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f)) {
            ThumbnailSlot(
                uri = uris.getOrNull(0),
                shape = RoundedCornerShape(topStart = cornerRadius),
                modifier = Modifier.weight(1f)
            )
            ThumbnailSlot(
                uri = uris.getOrNull(1),
                shape = RoundedCornerShape(topEnd = cornerRadius),
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.weight(1f)) {
            ThumbnailSlot(
                uri = uris.getOrNull(2),
                shape = RoundedCornerShape(bottomStart = cornerRadius),
                modifier = Modifier.weight(1f)
            )

            // The count/blurred wallpaper slot
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
            ) {
                ThumbnailSlot(
                    uri = uris.getOrNull(3),
                    shape = RoundedCornerShape(bottomEnd = cornerRadius),
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(
                            radius = if (totalCount > 4) 6.dp else 0.dp,
                            edgeTreatment = BlurredEdgeTreatment(RoundedCornerShape(0.dp))
                        )
                        .clipToBounds()
                )

                if (totalCount > 4) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(bottomEnd = cornerRadius))
                            .background(NothingBlack.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+${totalCount - 3}",
                            color = NothingWhite,
                            style = NothingType.badgeCaps
                        )
                    }
                }
            }
        }
    }
}



@Preview(name = ">4 wallpapers",showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CollectionGridItemMoreThan4Preview() {
    WallpaperChangerTheme {
        Box(modifier = Modifier
            .padding(24.dp)
            .width(160.dp)) {
            CollectionGridItem(
                name = "AMOLED",
                state = CollectionPreviewState(previewUris = emptyList(),totalCount = 12),
                onClick = {}
            )
        }
    }
}

@Preview(name = "4 wallpapers",showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CollectionGridItemLessOr4Preview() {
    WallpaperChangerTheme {
        Box(modifier = Modifier
            .padding(24.dp)
            .width(160.dp)) {
            CollectionGridItem(
                name = "AMOLED",
                state = CollectionPreviewState(previewUris = emptyList(),totalCount = 4),
                onClick = {},
                isPinned = true
            )
        }
    }
}
