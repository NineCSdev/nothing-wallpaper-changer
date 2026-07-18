package com.ninecsdev.wallpaperchanger.ui.collectionscreen.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Wraps a collection tile with the long-press context menu (Pin/Unpin · Set active · Edit).
 *
 * The menu opens as a floating [DropdownMenu] at the finger's position: the last pointer-down
 * offset is observed on the [PointerEventPass.Initial] pass (so the tile's own click handling is
 * untouched), and the tile passes the provided `onLongClick` to its long-press gesture.
 */
// TODO tests: see vault note tests/Pinned Collections Tests
@Composable
internal fun CollectionTileContextMenu(
    isPinned: Boolean,
    isActive: Boolean,
    onTogglePin: () -> Unit,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    tile: @Composable (onLongClick: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var tileHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .onSizeChanged { tileHeight = with(density) { it.height.toDp() } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    pressOffset = with(density) {
                        DpOffset(down.position.x.toDp(), down.position.y.toDp())
                    }
                }
            }
    ) {
        tile { expanded = true }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // The menu anchors below the tile by default; subtracting the tile height
            // repositions it at the remembered press point.
            offset = DpOffset(pressOffset.x, pressOffset.y - tileHeight),
            containerColor = NothingBlack,
            shape = RoundedCornerShape(CardCornerRadius),
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.15f))
        ) {
            ContextMenuItem(
                label = stringResource(if (isPinned) R.string.collection_menu_unpin else R.string.collection_menu_pin),
                onClick = { expanded = false; onTogglePin() }
            )
            ContextMenuItem(
                label = stringResource(R.string.collection_menu_set_active),
                enabled = !isActive,
                onClick = { expanded = false; onSetActive() }
            )
            ContextMenuItem(
                label = stringResource(R.string.collection_menu_edit),
                onClick = { expanded = false; onEdit() }
            )
        }
    }
}

@Composable
private fun ContextMenuItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = NothingType.metaLabel,
                color = NothingWhite.copy(alpha = if (enabled) 1f else 0.3f)
            )
        },
        enabled = enabled,
        onClick = onClick
    )
}
