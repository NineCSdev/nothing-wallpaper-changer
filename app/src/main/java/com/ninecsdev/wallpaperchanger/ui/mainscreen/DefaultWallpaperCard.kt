package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Component for configuring the default wallpaper.
 * Expands to show the selected wallpaper and change it.
 */
@Composable
fun DefaultWallpaperCard(
    revertToDefault: Boolean,
    defaultUri: Uri?,
    onToggleRevert: (Boolean) -> Unit,
    onSelectDefaultClick: () -> Unit
) {
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
            DefaultCardHeader(revertToDefault, onToggleRevert)

            if (revertToDefault) {
                HorizontalDivider(
                    color = NothingWhite.copy(alpha = 0.5f),
                    thickness = 2.dp
                )
                DefaultCardContent(defaultUri, onSelectDefaultClick)
            }
        }
    }
}

@Composable
private fun DefaultCardHeader(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.label_default_wallpaper),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = stringResource(R.string.label_revert_on_stop),
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.4f),
                letterSpacing = 0.5.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = NothingBlack,
                checkedTrackColor = NothingWhite,
                uncheckedThumbColor = NothingWhite,
                uncheckedTrackColor = NothingBlack,
                uncheckedBorderColor = NothingWhite.copy(alpha = 0.5f)
            )
        )
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
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uri != null) NothingWhite else Color.Red.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold
                )
            }

            TextButton(
                onClick = onSelectClick,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = NothingWhite)
            ) {
                Text(
                    text = if (uri != null) stringResource(R.string.action_change) else stringResource(R.string.action_select),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Preview(name = "Enabled", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewDefaultCardEnabled() {
    MaterialTheme {
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
    MaterialTheme {
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
    MaterialTheme {
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