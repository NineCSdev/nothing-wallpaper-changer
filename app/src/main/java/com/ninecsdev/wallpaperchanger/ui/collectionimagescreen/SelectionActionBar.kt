package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Floating pill of bulk actions shown while selection mode is active
 * (Nothing-gallery style). Direct slots: favourite / edit / delete; copy and
 * move live behind the ⋮ overflow menu, which opens upward above the pill.
 *
 * Selection mode guarantees ≥ 1 selection, so every action is always
 * applicable except edit, which needs exactly one ([canEdit] — shown dimmed
 * otherwise). Move is omitted (not disabled) when [isMoveAvailable] is false:
 * folder-synced collections would re-add a moved-out image on the next sync pass.
 */
@Composable
internal fun SelectionActionBar(
    isAllFavorites: Boolean,
    canEdit: Boolean,
    isMoveAvailable: Boolean,
    onFavoriteClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onCopyClick: () -> Unit,
    onMoveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val pillShape = RoundedCornerShape(50)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(pillShape)
            .border(width = 1.dp, color = NothingWhite.copy(alpha = 0.15f), shape = pillShape)
            .background(NothingGray)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        IconButton(onClick = onFavoriteClick) {
            Icon(
                imageVector = if (isAllFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = stringResource(R.string.cd_favorite_wallpapers),
                tint = NothingWhite,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onEditClick, enabled = canEdit) {
            Icon(
                painter = painterResource(R.drawable.icon_edit),
                contentDescription = stringResource(R.string.cd_edit_wallpaper),
                // Dimmed, not hidden, on multi-select — the old top-bar treatment.
                tint = NothingWhite.copy(alpha = if (canEdit) 1f else 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onDeleteClick) {
            Icon(
                painter = painterResource(R.drawable.icon_delete),
                contentDescription = stringResource(R.string.cd_delete_wallpapers),
                tint = NothingWhite,
                modifier = Modifier.size(20.dp)
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.cd_selection_more),
                    tint = NothingWhite,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Anchored to the ⋮; flips above the pill automatically since
            // there's never room below. Styled to match SortDropdown's menu.
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                offset = DpOffset(0.dp, (-8).dp),
                shape = RoundedCornerShape(CardCornerRadius),
                containerColor = NothingBlack,
                border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.15f))
            ) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.icon_copy),
                            contentDescription = null,
                            tint = NothingWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.selection_menu_copy),
                            style = NothingType.dialogButton,
                            color = NothingWhite
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onCopyClick()
                    }
                )
                if (isMoveAvailable) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.icon_move),
                                contentDescription = null,
                                tint = NothingWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = {
                            Text(
                                text = stringResource(R.string.selection_menu_move),
                                style = NothingType.dialogButton,
                                color = NothingWhite
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onMoveClick()
                        }
                    )
                }
            }
        }
    }
}

// Previews

@Preview(name = "All enabled", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SelectionActionBarPreview() {
    WallpaperChangerTheme {
        Surface(color = NothingBlack, modifier = Modifier.padding(16.dp)) {
            SelectionActionBar(
                isAllFavorites = false,
                canEdit = true,
                isMoveAvailable = true,
                onFavoriteClick = {},
                onEditClick = {},
                onDeleteClick = {},
                onCopyClick = {},
                onMoveClick = {}
            )
        }
    }
}

@Preview(name = "Multi-select (edit disabled)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SelectionActionBarMultiPreview() {
    WallpaperChangerTheme {
        Surface(color = NothingBlack, modifier = Modifier.padding(16.dp)) {
            SelectionActionBar(
                isAllFavorites = true,
                canEdit = false,
                isMoveAvailable = false,
                onFavoriteClick = {},
                onEditClick = {},
                onDeleteClick = {},
                onCopyClick = {},
                onMoveClick = {}
            )
        }
    }
}
