package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Component for choosing the active collection.
 * Shows a preview of the first 3 wallpapers of the collection.
 */
@Composable
internal fun WallpaperSelectionCard(
    activeCollection: WallpaperCollection?,
    previewImages: List<WallpaperImage>,
    totalImages: Int,
    onSelectFolderClick: () -> Unit
) {
    val displayName = activeCollection?.name ?: stringResource(R.string.label_select_collection)

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
                onClick = onSelectFolderClick,
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The ViewModel already truncates previewImages to PREVIEW_IMAGE_COUNT.
            previewImages.forEach { image ->
                NothingThumbnail(
                    uri = image.uri,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(0.75f)
                )
            }

            // Placeholders for empty collections and loading
            if (previewImages.size < PREVIEW_IMAGE_COUNT) {
                repeat(PREVIEW_IMAGE_COUNT - previewImages.size) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NothingWhite.copy(alpha = 0.05f))
                            .border(1.dp, NothingWhite.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                    )
                }
            }

            Box(
                modifier = Modifier.size(width = 45.dp, height = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                val countText = when {
                    activeCollection == null -> ""
                    totalImages > PREVIEW_IMAGE_COUNT -> "+${totalImages - PREVIEW_IMAGE_COUNT}"
                    totalImages == 0 -> "0"
                    else -> ""
                }

                Text(
                    text = countText,
                    style = NothingType.countBadge,
                    color = NothingWhite.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Preview(name = "Selection: Active Folder", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewSelectionCardActive() {
    WallpaperChangerTheme {
        Box(Modifier.padding(16.dp)) {
            WallpaperSelectionCard(
                activeCollection = WallpaperCollection(name = "Amoled Collection", type = CollectionType.FOLDER),
                previewImages = emptyList(),
                totalImages = 15,
                onSelectFolderClick = {}
            )
        }
    }
}