package com.ninecsdev.wallpaperchanger.ui.components.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.components.CollectionGridItem
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
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
 * The app's "pick a collection" surface: a modal bottom sheet with a title and a 2-column grid
 * of [CollectionGridItems][com.ninecsdev.wallpaperchanger.ui.components.CollectionGridItem]. Tapping an item acts immediately
 * and scrim tap, swipe-down and system back all dismiss via [onDismiss].
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
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetCornerRadius = 28.dp
    val topBorderModifier = Modifier.topRoundedBorder(
        strokeWidth = 2.dp,
        color = NothingWhite.copy(alpha = 0.3f),
        cornerRadius = sheetCornerRadius
    )

    // The border is drawn inside the sheet's own content (rather than on ModalBottomSheet's
    // modifier) because that outer modifier attaches to the anchored/swipeable layout node
    // before it's offset into position, which made the border render up at the top of the
    // screen instead of tracing the visible card.
    if (LocalInspectionMode.current) {
        // In Preview, we wrap the content in a simulated BottomSheet because ModalBottomSheet
        // fails to render in the IDE.
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = NothingBlack,
                shape = sheetShape
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = topBorderModifier
                ) {
                    BottomSheetDefaults.DragHandle(color = NothingWhite.copy(alpha = 0.4f))
                    CollectionPickerContent(title, items, onItemClick, emptyContent)
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = NothingBlack,
            shape = sheetShape,
            dragHandle = null,
            modifier = modifier
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = topBorderModifier
            ) {
                BottomSheetDefaults.DragHandle(color = NothingWhite.copy(alpha = 0.4f))
                CollectionPickerContent(title, items, onItemClick, emptyContent)
            }
        }
    }
}

/** Internal content extracted for reuse between real sheet and preview mock. */
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

/**
 * Traces a border along the top edge, rounded top corners, and both sides of a shape matching
 * [RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)], leaving only the bottom
 * undrawn.
 */
private fun Modifier.topRoundedBorder(
    strokeWidth: Dp,
    color: Color,
    cornerRadius: Dp
): Modifier = this.drawWithContent {
    drawContent()
    val strokePx = strokeWidth.toPx()
    val inset = strokePx / 2f
    val radiusPx = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
    val path = Path().apply {
        moveTo(inset, size.height)
        lineTo(inset, radiusPx + inset)
        arcTo(
            rect = Rect(inset, inset, inset + radiusPx * 2, inset + radiusPx * 2),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        lineTo(size.width - inset - radiusPx, inset)
        arcTo(
            rect = Rect(size.width - inset - radiusPx * 2, inset, size.width - inset, inset + radiusPx * 2),
            startAngleDegrees = 270f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        lineTo(size.width - inset, size.height)
    }
    drawPath(path = path, color = color, style = Stroke(width = strokePx, cap = StrokeCap.Round))
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
