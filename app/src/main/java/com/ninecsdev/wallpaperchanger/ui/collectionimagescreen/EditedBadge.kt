package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Small rounded white box with a pencil icon.
 * Indicates that a wallpaper has a custom edited URI.
 */
@Composable
internal fun EditedBadge(modifier: Modifier = Modifier, size: Int = 14) {
    StatusBadge(
        backgroundColor = NothingWhite,
        modifier = modifier,
        size = size
    ) {
        Icon(
            painter = painterResource(R.drawable.icon_edit),
            contentDescription = stringResource(R.string.cd_edited_wallpaper),
            tint = NothingBlack,
            modifier = Modifier.size(size.dp)
        )
    }
}

@Preview(name = "Edited Badge preview", backgroundColor = 0xFF000000)
@Composable
private fun EditedBadgePreview() {
    Surface(
        color = NothingBlack,
        modifier = Modifier.padding(16.dp)
    ) {
        EditedBadge()
    }
}
