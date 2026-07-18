package com.ninecsdev.wallpaperchanger.ui.mainscreen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant

/**
 * Control panel buttons for starting and stopping the service.
 */
@Composable
internal fun ServiceControlButtons(
    isStartEnabled: Boolean,
    isStopEnabled: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isStopEnabled) {
            NothingButton(
                text = stringResource(R.string.action_stop),
                onClick = onStopClick,
                variant = NothingButtonVariant.SECONDARY
            )
        } else {
            NothingButton(
                text = stringResource(R.string.action_start),
                onClick = onStartClick,
                enabled = isStartEnabled,
                variant = NothingButtonVariant.PRIMARY
            )
        }
    }
}

@Preview(name = "State: Ready to Start", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewButtonsReady() {
    WallpaperChangerTheme {
        Column(Modifier.padding(16.dp)) {
            ServiceControlButtons(
                isStartEnabled = true,
                isStopEnabled = false,
                onStartClick = {},
                onStopClick = {}
            )
        }
    }
}

@Preview(name = "State: Service Running", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewButtonsRunning() {
    WallpaperChangerTheme {
        Column(Modifier.padding(16.dp)) {
            ServiceControlButtons(
                isStartEnabled = false,
                isStopEnabled = true,
                onStartClick = {},
                onStopClick = {}
            )
        }
    }
}

@Preview(name = "State: Setup Needed", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewButtonsDisabled() {
    WallpaperChangerTheme {
        Column(Modifier.padding(16.dp)) {
            ServiceControlButtons(
                isStartEnabled = false,
                isStopEnabled = false,
                onStartClick = {},
                onStopClick = {}
            )
        }
    }
}