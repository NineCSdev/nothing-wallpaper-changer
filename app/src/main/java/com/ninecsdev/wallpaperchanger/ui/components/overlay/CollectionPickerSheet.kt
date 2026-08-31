package com.ninecsdev.wallpaperchanger.ui.components.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.components.CollectionGridItem
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.components.NothingBottomSheet
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * One pickable collection in a [CollectionPickerSheet].
 * A neutral shape so each caller can map its own model (collections, transfer targets) into it.
 */
data class CollectionPickerItem(
    val id: Long,
    val name: String,
    val previewState: CollectionPreviewState,
    val isActive: Boolean = false,
    val isPinned: Boolean = false
)

/**
 * The app's "pick a collection" surface: a [NothingBottomSheet] with a title and a 2-column grid
 * of [CollectionGridItems][com.ninecsdev.wallpaperchanger.ui.components.CollectionGridItem].
 * Tapping an item acts immediately and scrim tap, swipe-down and system back all dismiss via [onDismiss].
 *
 * Shared by the main screen's active-collection picker and the copy/move transfer target picker;
 * [emptyContent] lets each caller supply its own zero-items treatment.
 */
// TODO tests: see vault note tests/Collection Picker Tests.md
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPickerSheet(
    title: String,
    items: List<CollectionPickerItem>,
    onItemClick: (CollectionPickerItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    emptyContent: @Composable ColumnScope.() -> Unit = {}
) {
    NothingBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        sheetState = sheetState
    ) { hideThen ->
        // The click runs after the slide-out, so the caller's own teardown doesn't cut it short
        CollectionPickerContent(title, items, { item -> hideThen { onItemClick(item) } }, emptyContent)
    }
}

@Composable
private fun CollectionPickerContent(
    title: String,
    items: List<CollectionPickerItem>,
    onItemClick: (CollectionPickerItem) -> Unit,
    emptyContent: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = title,
            style = NothingType.titleCaps,
            color = NothingWhite,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (items.isEmpty()) {
            emptyContent()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(items, key = { it.id }) { item ->
                    CollectionGridItem(
                        name = item.name,
                        state = item.previewState,
                        onClick = { onItemClick(item) },
                        isPinned = item.isPinned,
                        isActive = item.isActive
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Populated Sheet", backgroundColor = 0xFF000000)
@Composable
private fun CollectionPickerSheetPopulatedPreview() {
    val items = listOf(
        CollectionPickerItem(1, "Nature", CollectionPreviewState(emptyList(), 12), isActive = true),
        CollectionPickerItem(2, "Architecture", CollectionPreviewState(emptyList(), 5)),
        CollectionPickerItem(3, "Abstract", CollectionPreviewState(emptyList(), 8), isPinned = true),
        CollectionPickerItem(4, "Minimal", CollectionPreviewState(emptyList(), 0))
    )
    WallpaperChangerTheme {
        CollectionPickerSheet(
            title = "TRANSFER TO",
            items = items,
            onItemClick = {},
            onDismiss = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Empty Sheet", backgroundColor = 0xFF000000)
@Composable
private fun CollectionPickerSheetEmptyPreview() {
    WallpaperChangerTheme {
        CollectionPickerSheet(
            title = "TRANSFER TO",
            items = emptyList(),
            onItemClick = {},
            onDismiss = {},
            emptyContent = {
                Text(
                    text = "No other collections available",
                    style = NothingType.metaLabel,
                    color = NothingWhite.copy(alpha = 0.7f),
                    modifier = Modifier
                        .padding(32.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }
        )
    }
}
