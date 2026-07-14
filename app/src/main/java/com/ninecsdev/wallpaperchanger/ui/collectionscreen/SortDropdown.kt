package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Dropdown for choosing the collection sort order.
 */
@Composable
internal fun SortDropdown(
    selected: CollectionSortOrder,
    onSelected: (CollectionSortOrder) -> Unit,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    val shape = RoundedCornerShape(50)

    Box(
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(shape)
                .border(width = 1.dp, color = NothingWhite.copy(alpha = 0.5f), shape = shape)
                .background(NothingBlack)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.sort_by_prefix, selected.label()),
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                tint = NothingWhite.copy(alpha = 1f),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = NothingBlack,
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.15f))
        ) {
            CollectionSortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (option == selected)
                                NothingWhite
                            else
                                NothingWhite.copy(alpha = 0.6f),
                            fontWeight = if (option == selected) FontWeight.Black else FontWeight.Normal,
                            letterSpacing = 1.sp
                        )
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CollectionSortOrder.label(): String = when (this) {
    CollectionSortOrder.NAME -> stringResource(R.string.sort_order_name)
    CollectionSortOrder.LAST_USED -> stringResource(R.string.sort_order_last_used)
    CollectionSortOrder.DATE_CREATED -> stringResource(R.string.sort_order_date_created)
}

@Preview(name = "Name", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SortDropdownNamePreview() {
    MaterialTheme {
        Surface(color = NothingBlack, modifier = Modifier.padding(16.dp)) {
            SortDropdown(
                selected = CollectionSortOrder.NAME,
                onSelected = {}
            )
        }
    }
}

@Preview(name = "Creation date", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SortDropdownDatePreview() {
    MaterialTheme {
        Surface(color = NothingBlack, modifier = Modifier.padding(16.dp)) {
            SortDropdown(
                selected = CollectionSortOrder.DATE_CREATED,
                onSelected = {}
            )
        }
    }
}
@Preview(name = "Last used", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SortDropdownUsedPreview() {
    MaterialTheme {
        Surface(color = NothingBlack, modifier = Modifier.padding(16.dp)) {
            SortDropdown(
                selected = CollectionSortOrder.LAST_USED,
                onSelected = {}
            )
        }
    }
}