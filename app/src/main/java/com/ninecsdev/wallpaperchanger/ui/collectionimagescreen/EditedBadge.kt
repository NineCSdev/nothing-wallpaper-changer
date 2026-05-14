package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
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
fun EditedBadge(size: Int = 14, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size((size+8).dp)
            .clip(RoundedCornerShape(6.dp))
            .background(NothingWhite),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.icon_edit),
            contentDescription = "Edited",
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