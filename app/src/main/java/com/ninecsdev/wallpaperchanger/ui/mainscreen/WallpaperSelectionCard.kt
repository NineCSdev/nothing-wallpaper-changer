package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Component for choosing the active collection.
 * Shows a preview of the first 3 wallpapers of the collection.
 */
@Composable
fun WallpaperSelectionCard(
    activeCollection: WallpaperCollection?,
    previewImages: List<WallpaperImage>,
    totalImages: Int,
    onSelectFolderClick: () -> Unit
) {
    val displayName = activeCollection?.name ?: "SELECT LIST"

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.outlinedCardColors(
            containerColor = NothingBlack,
            contentColor = NothingWhite
        ),
        border = BorderStroke(2.dp, NothingWhite.copy(alpha = 0.5f))
    ) {
        Column {
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
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = NothingWhite,
                        letterSpacing = 1.sp
                    )
                }

                TextButton(
                    onClick = onSelectFolderClick,
                    colors = ButtonDefaults.textButtonColors(contentColor = NothingWhite),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (activeCollection != null) "CHANGE" else "SELECT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            HorizontalDivider(
                color = NothingWhite.copy(alpha = 0.5f),
                thickness = 2.dp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displayImages = previewImages.take(3)

                displayImages.forEach { image ->
                    NothingThumbnail(
                        uri = image.uri,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.75f)
                    )
                }

                // Placeholders for empty collections and loading
                if (displayImages.size < 3) {
                    repeat(3 - displayImages.size) {
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
                        totalImages > 3 -> "+${totalImages - 3}"
                        totalImages == 0 -> "0"
                        else -> ""
                    }

                    Text(
                        text = countText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NothingWhite.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Preview(name = "Selection: Active Folder", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewSelectionCardActive() {
    MaterialTheme {
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