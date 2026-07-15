package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.SettingsToggleRow
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Component for configuring the default wallpaper.
 * Expands to show the selected wallpaper and change it.
 */
@Composable
internal fun DefaultWallpaperCard(
    revertToDefault: Boolean,
    defaultUri: Uri?,
    onToggleRevert: (Boolean) -> Unit,
    onSelectDefaultClick: () -> Unit
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
                DefaultCardContent(defaultUri, onSelectDefaultClick)
            }
        }
    }
}

@Composable
private fun DefaultCardContent(
    uri: Uri?,
    onSelectClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NothingThumbnail(
            uri = uri,
            modifier = Modifier.size(width = 60.dp, height = 80.dp)
        )

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
                    text = if (uri != null) stringResource(R.string.label_ready) else stringResource(R.string.label_not_set),
                    style = NothingType.labelStrong,
                    color = if (uri != null) NothingWhite else NothingRed.copy(alpha = 0.8f)
                )
            }

            TextButton(
                onClick = onSelectClick,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = NothingWhite)
            ) {
                Text(
                    text = if (uri != null) stringResource(R.string.action_change) else stringResource(R.string.action_select),
                    style = NothingType.actionEmphasis
                )
            }
        }
    }
}

@Preview(name = "Enabled", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewDefaultCardEnabled() {
    WallpaperChangerTheme {
        Box(Modifier.padding(16.dp)) {
            DefaultWallpaperCard(
                revertToDefault = true,
                defaultUri = "content://media/external/images/media/1".toUri(),
                onToggleRevert = {},
                onSelectDefaultClick = {}
            )
        }
    }
}

@Preview(name = "Not set",showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun DefaultWallpaperCardPreview() {
    WallpaperChangerTheme {
        Box(Modifier.padding(16.dp)) {
            DefaultWallpaperCard(
                revertToDefault = true,
                defaultUri = null,
                onToggleRevert = {},
                onSelectDefaultClick = {}
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
                defaultUri = null,
                onToggleRevert = {},
                onSelectDefaultClick = {}
            )
        }
    }
}