package com.ninecsdev.wallpaperchanger.ui.mainscreen.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.SettingsToggleRow
import com.ninecsdev.wallpaperchanger.ui.components.WallpaperThumbnail
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

private const val THUMBNAIL_WIDTH_DP = 60
private const val THUMBNAIL_HEIGHT_DP = 80

/**  Fraction of the screen width the thumbnail occupies for [EditableWallpaperImage]. Approximated. */
private const val THUMBNAIL_DECODE_FRACTION = 0.2f

/**
 * Component for configuring the default wallpaper.
 * Expands to show the selected wallpaper, change it, edit its framing and apply it now.
 */
@Composable
internal fun DefaultWallpaperCard(
    revertToDefault: Boolean,
    defaultWallpaper: WallpaperImage?,
    isApplying: Boolean,
    onToggleRevert: (Boolean) -> Unit,
    onSelectDefaultClick: () -> Unit,
    onEditDefaultClick: () -> Unit,
    onApplyDefaultClick: () -> Unit
) {
    NothingOutlinedCard {
        SettingsToggleRow(
            title = stringResource(R.string.label_default_wallpaper),
            subtitle = stringResource(R.string.label_revert_on_stop),
            checked = revertToDefault,
            onCheckedChange = onToggleRevert,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )

        AnimatedVisibility(
            visible = revertToDefault,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                NothingCardDivider()
                DefaultCardContent(defaultWallpaper, onSelectDefaultClick, onEditDefaultClick)

                if (defaultWallpaper != null) {
                    NothingCardDivider()
                    TextButton(
                        onClick = onApplyDefaultClick,
                        enabled = !isApplying && defaultWallpaper.isAvailable,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = NothingWhite)
                    ) {
                        Text(
                            text = stringResource(R.string.action_apply_default_now),
                            style = NothingType.actionEmphasis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultCardContent(
    wallpaper: WallpaperImage?,
    onSelectClick: () -> Unit,
    onEditClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DefaultThumbnail(wallpaper)

        Spacer(modifier = Modifier.width(20.dp))

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.label_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingWhite.copy(alpha = 0.3f)
                )
                Text(
                    text = if (wallpaper != null) stringResource(R.string.label_ready) else stringResource(R.string.label_not_set),
                    style = NothingType.labelStrong,
                    color = if (wallpaper != null) NothingWhite else NothingRed.copy(alpha = 0.8f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (wallpaper != null) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            painter = painterResource(R.drawable.icon_edit),
                            contentDescription = stringResource(R.string.cd_edit_wallpaper),
                            tint = NothingWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                TextButton(
                    onClick = onSelectClick,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = NothingWhite)
                ) {
                    Text(
                        text = if (wallpaper != null) stringResource(R.string.action_change) else stringResource(R.string.action_select),
                        style = NothingType.actionEmphasis
                    )
                }
            }
        }
    }
}

/** The thumbnail, framed the way the wallpaper itself will be. Not interactive. */
@Composable
private fun DefaultThumbnail(wallpaper: WallpaperImage?) {
    WallpaperThumbnail(
        wallpaper = wallpaper,
        modifier = Modifier.size(width = THUMBNAIL_WIDTH_DP.dp, height = THUMBNAIL_HEIGHT_DP.dp),
        decodeFraction = THUMBNAIL_DECODE_FRACTION
    )
}

private fun previewWallpaper(editParams: EditParams? = null) = WallpaperImage(
    id = 1,
    collectionId = 1,
    uri = "content://media/external/images/media/1".toUri(),
    editParams = editParams,
    isDefault = true
)

@Preview(name = "Enabled", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewDefaultCardEnabled() {
    WallpaperChangerTheme {
        Box(Modifier.padding(16.dp)) {
            DefaultWallpaperCard(
                revertToDefault = true,
                defaultWallpaper = previewWallpaper(),
                isApplying = false,
                onToggleRevert = {},
                onSelectDefaultClick = {},
                onEditDefaultClick = {},
                onApplyDefaultClick = {}
            )
        }
    }
}

@Preview(name = "Edited", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewDefaultCardEdited() {
    WallpaperChangerTheme {
        Box(Modifier.padding(16.dp)) {
            DefaultWallpaperCard(
                revertToDefault = true,
                defaultWallpaper = previewWallpaper(EditParams(zoom = 1.5f, offsetX = 0f, offsetY = 0f)),
                isApplying = false,
                onToggleRevert = {},
                onSelectDefaultClick = {},
                onEditDefaultClick = {},
                onApplyDefaultClick = {}
            )
        }
    }
}

@Preview(name = "Not set", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun DefaultWallpaperCardPreview() {
    WallpaperChangerTheme {
        Box(Modifier.padding(16.dp)) {
            DefaultWallpaperCard(
                revertToDefault = true,
                defaultWallpaper = null,
                isApplying = false,
                onToggleRevert = {},
                onSelectDefaultClick = {},
                onEditDefaultClick = {},
                onApplyDefaultClick = {}
            )
        }
    }
}

@Preview(name = "Disabled", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewDefaultCardDisabled() {
    WallpaperChangerTheme {
        Box(Modifier.padding(16.dp)) {
            DefaultWallpaperCard(
                revertToDefault = false,
                defaultWallpaper = null,
                isApplying = false,
                onToggleRevert = {},
                onSelectDefaultClick = {},
                onEditDefaultClick = {},
                onApplyDefaultClick = {}
            )
        }
    }
}
