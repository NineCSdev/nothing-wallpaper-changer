package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

private const val DISABLED_ALPHA = 0.4f

/** Inverted while the filter is on. */
@Composable
internal fun FilterButton(
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(CircleShape)
                .background(if (isActive) NothingWhite else Color.Transparent)
        ) {
            Icon(
                painter = painterResource(R.drawable.icon_filter),
                contentDescription = stringResource(R.string.cd_filter),
                tint = if (isActive) NothingBlack else NothingWhite,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun FilterChipRow(
    chips: List<FilterChip>,
    enabled: Boolean,
    onChipClick: (FilterChip) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
    ) {
        items(chips, key = { "${it.dimension}-${it.answer}" }) { chip ->
            FilterChipPill(chip = chip, enabled = enabled, onClick = { onChipClick(chip) })
        }
    }
}

@Composable
private fun FilterChipPill(
    chip: FilterChip,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    val content = if (chip.isSelected) NothingBlack else NothingWhite
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .border(
                width = 1.dp,
                color = if (chip.isSelected) NothingWhite else NothingWhite.copy(alpha = 0.3f),
                shape = shape
            )
            .background(if (chip.isSelected) NothingWhite else NothingBlack)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text = chip.label(), style = NothingType.rowLabel, color = content)
        Spacer(Modifier.width(6.dp))
        Text(
            text = chip.count.toString(),
            style = NothingType.rowLabel.copy(fontWeight = FontWeight.Normal),
            color = content.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun FilterChip.label(): String = stringResource(
    when (dimension) {
        FilterDimension.AVAILABLE -> if (answer) R.string.filter_available else R.string.filter_unavailable
        FilterDimension.EDITED -> if (answer) R.string.filter_edited else R.string.filter_not_edited
        FilterDimension.FAVORITE -> if (answer) R.string.filter_favorites else R.string.filter_not_favorites
        FilterDimension.LOCAL_COPY -> if (answer) R.string.filter_local_copies else R.string.filter_references
        FilterDimension.FROM_FOLDER -> if (answer) R.string.filter_from_folder else R.string.filter_added_by_you
    }
)

@Composable
internal fun FilterNoMatches(
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 48.dp)
    ) {
        Text(
            text = stringResource(R.string.filter_no_matches),
            style = NothingType.titleCaps,
            color = NothingWhite.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(24.dp))
        NothingButton(
            text = stringResource(R.string.filter_clear),
            onClick = onClear,
            variant = NothingButtonVariant.SECONDARY
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun FilterChipRowPreview() {
    WallpaperChangerTheme {
        FilterChipRow(
            chips = listOf(
                FilterChip(FilterDimension.AVAILABLE, answer = true, count = 120, isSelected = false),
                FilterChip(FilterDimension.AVAILABLE, answer = false, count = 8, isSelected = true),
                FilterChip(FilterDimension.EDITED, answer = true, count = 12, isSelected = false),
                FilterChip(FilterDimension.EDITED, answer = false, count = 116, isSelected = false)
            ),
            enabled = true,
            onChipClick = {}
        )
    }
}
