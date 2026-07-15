package com.ninecsdev.wallpaperchanger.ui.components.overlay

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Snackbar host with the app's inverted white-on-black styling.
 * Drop-in replacement for the stock [SnackbarHost] in a Scaffold's snackbarHost slot.
 * Raised above the default position so it clears the screens' FAB.
 */
@Composable
fun NothingSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.padding(bottom = 30.dp)
    ) { data ->
        Snackbar(
            containerColor = NothingWhite,
            contentColor = NothingBlack,
            shape = RoundedCornerShape(CardCornerRadius)
        ) {
            Text(
                text = data.visuals.message.uppercase(),
                style = NothingType.overline
            )
        }
    }
}

@Preview(name = "Nothing Snackbar preview", backgroundColor = 0xFF000000)
@Composable
private fun NothingSnackbarPreview() {
    Surface(color = NothingBlack) {
        Snackbar(
            containerColor = NothingWhite,
            contentColor = NothingBlack,
            shape = RoundedCornerShape(CardCornerRadius),
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "3 ADDED AS REFERENCES • 1 SKIPPED",
                style = NothingType.overline
            )
        }
    }
}
