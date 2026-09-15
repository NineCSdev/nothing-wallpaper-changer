package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/** A Nothing-styled linear progress bar over a [done] of [total] count. Indeterminate until [total] is known. */
@Composable
internal fun NothingProgressBar(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    if (total > 0) {
        LinearProgressIndicator(
            progress = { done.toFloat() / total },
            modifier = modifier.fillMaxWidth(),
            color = NothingWhite,
            trackColor = NothingWhite.copy(alpha = 0.15f)
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            color = NothingWhite,
            trackColor = NothingWhite.copy(alpha = 0.15f)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NothingProgressBarPreview() {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NothingProgressBar(done = 41, total = 120)
            NothingProgressBar(done = 0, total = 0)
        }
    }
}
