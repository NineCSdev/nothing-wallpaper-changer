package com.ninecsdev.wallpaperchanger.ui.mainscreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import androidx.compose.ui.platform.LocalContext
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.resolveDisplayName
import com.ninecsdev.wallpaperchanger.ui.components.UnavailableBadge
import com.ninecsdev.wallpaperchanger.ui.components.WallpaperThumbnail
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

private val TileWidth = 81.dp
private val TileHeight = 108.dp

/** Share of the screen width one tile occupies; feeds Coil's decode sizing. */
private const val TILE_DECODE_FRACTION = 0.2f

/** Empty tiles standing in for a collection with no images, and for none selected at all. */
private const val PLACEHOLDER_TILE_COUNT = 4

/**
 * Component for choosing the active collection.
 * Scrolls through every wallpaper in it.
 */
@Composable
internal fun WallpaperSelectionCard(
    activeCollection: WallpaperCollection?,
    collectionImages: List<WallpaperImage>,
    onSelectCollectionClick: () -> Unit
) {
    val displayName = activeCollection?.resolveDisplayName(LocalContext.current)
        ?: stringResource(R.string.label_select_collection)

    NothingOutlinedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.icon_collection_outline),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = NothingWhite
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = displayName.uppercase(),
                    style = NothingType.rowTitle,
                    color = NothingWhite
                )
            }

            TextButton(
                onClick = onSelectCollectionClick,
                colors = ButtonDefaults.textButtonColors(contentColor = NothingWhite),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (activeCollection != null) stringResource(R.string.action_change) else stringResource(R.string.action_select),
                    style = NothingType.actionEmphasis
                )
            }
        }

        NothingCardDivider()

        val listState = remember(activeCollection?.id) { LazyListState() }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (collectionImages.isEmpty()) {
                items(PLACEHOLDER_TILE_COUNT) { PlaceholderTile() }
            } else {
                items(collectionImages, key = { it.id }) { image ->
                    WallpaperTile(image = image)
                }
            }
        }
    }
}

/**
 * One tile of the row: the wallpaper framed the way it will be applied.
 */
@Composable
private fun WallpaperTile(image: WallpaperImage) {
    Box(modifier = Modifier.size(width = TileWidth, height = TileHeight)) {
        WallpaperThumbnail(
            wallpaper = image,
            modifier = Modifier.fillMaxSize(),
            decodeFraction = TILE_DECODE_FRACTION
        )

        // The only badge the card carries: an unreadable source renders as a blank tile, so
        // nothing else here says why. An edit is visible in the framing, and a favourite is
        // not actionable from this card.
        if (!image.isAvailable) {
            UnavailableBadge(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            )
        }
    }
}

/** Empty slot shown when the collection has no images, or none is selected. */
@Composable
private fun PlaceholderTile() {
    Box(
        modifier = Modifier
            .size(width = TileWidth, height = TileHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(NothingWhite.copy(alpha = 0.05f))
            .border(1.dp, NothingWhite.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
    )
}

@Preview(name = "Selection: Active Folder", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewSelectionCardActive() {
    WallpaperChangerTheme {
        Box(Modifier.padding(16.dp)) {
            WallpaperSelectionCard(
                activeCollection = WallpaperCollection(name = "Amoled Collection", type = CollectionType.FOLDER, createdAt = 0L, lastUsedAt = 0L),
                collectionImages = List(8) { WallpaperImage(id = it.toLong(), collectionId = 1L, uriString = "") },
                onSelectCollectionClick = {}
            )
        }
    }
}