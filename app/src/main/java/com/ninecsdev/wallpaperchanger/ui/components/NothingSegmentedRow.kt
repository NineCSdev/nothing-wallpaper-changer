package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingDarkGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.SmallCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import androidx.compose.foundation.layout.size

/**
 * The shared segmented single-choice row: equal-width tiles where the selected one fills
 * white with black content and the rest sit as dark tiles with a faint border. Used by the
 * crop-rule and rotation-frequency selectors so every instant-apply choice row reads the same.
 *
 * [optionContent] draws each tile's content (icon or text) and receives the tile's selected
 * state so it can flip its tint/color to [NothingBlack] on the white fill.
 */
@Composable
internal fun <T> NothingSegmentedRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionContent: @Composable (option: T, isSelected: Boolean) -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(SmallCornerRadius))
                    .background(if (isSelected) NothingWhite else NothingDarkGray)
                    .border(
                        1.dp,
                        if (isSelected) NothingWhite else NothingWhite.copy(alpha = 0.1f),
                        RoundedCornerShape(SmallCornerRadius)
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                optionContent(option, isSelected)
            }
        }
    }
}

@Preview(name = "Segmented Row – text", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NothingSegmentedRowTextPreview() {
    WallpaperChangerTheme {
        Box(Modifier.background(NothingBlack).padding(16.dp)) {
            NothingSegmentedRow(
                options = listOf("PER LOCK", "EVERY 1H", "DAILY"),
                selected = "EVERY 1H",
                onSelect = {}
            ) { label, isSelected ->
                Text(
                    text = label,
                    style = NothingType.labelStrong,
                    color = if (isSelected) NothingBlack else NothingWhite.copy(alpha = 0.9f),
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(name = "Segmented Row – icons", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NothingSegmentedRowIconPreview() {
    WallpaperChangerTheme {
        Box(Modifier.background(NothingBlack).padding(16.dp)) {
            NothingSegmentedRow(
                options = listOf(0, 1),
                selected = 0,
                onSelect = {}
            ) { _, isSelected ->
                Icon(
                    painter = painterResource(R.drawable.icon_crop_center),
                    contentDescription = null,
                    tint = if (isSelected) NothingBlack else NothingWhite,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
