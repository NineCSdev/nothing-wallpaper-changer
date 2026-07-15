package com.ninecsdev.wallpaperchanger.ui.components.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Loading overlay for giving feedback while app does heavy work.
 */
@Composable
fun ProcessingOverlay(
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.label_processing)
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NothingBlack.copy(alpha = 0.8f))
            // Important: Consume all clicks to prevent interaction with underlying UI, false so the user doesn't think that clicking does something
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                color = NothingWhite,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = message.uppercase(),
                style = NothingType.overline,
                color = NothingWhite
            )
        }
    }
}

@Preview(name = "Loading State")
@Composable
fun ProcessingOverlayPreview() {
    WallpaperChangerTheme {
        ProcessingOverlay(message = "Syncing Folder...")
    }
}