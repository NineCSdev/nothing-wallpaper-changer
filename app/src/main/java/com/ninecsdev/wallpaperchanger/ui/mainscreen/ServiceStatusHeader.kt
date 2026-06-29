package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.ui.components.StatusLed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Header for showing the app status.
 */
@Composable
fun ServiceStatusHeader(
    state: ServiceState,
    modifier: Modifier = Modifier
) {
    val (color, labelRes) = getVisualsForState(state)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.label_app_status),
            style = MaterialTheme.typography.labelSmall,
            color = NothingWhite.copy(alpha = 0.4f),
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLed(
                color = color,
                isPulsing = state is ServiceState.Loading || state is ServiceState.Stopping
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = NothingWhite,
                letterSpacing = 1.sp
            )
        }
    }
}

internal fun getVisualsForState(state: ServiceState): Pair<Color, Int> {
    return when (state) {
        is ServiceState.Running -> Color.Green to R.string.status_label_active
        is ServiceState.Stopped -> NothingRed to R.string.status_label_inactive
        is ServiceState.Stopping -> NothingRed to R.string.status_label_stopping
        is ServiceState.Paused -> Color(0xFFFFA500) to R.string.status_label_paused_battery
        is ServiceState.DisabledPowerSave -> Color(0xFFFFA500) to R.string.status_label_power_save_on
        is ServiceState.DisabledNoCollection -> NothingGray to R.string.status_label_setup_needed
        is ServiceState.Loading -> NothingWhite to R.string.status_label_initializing
    }
}

@Preview(name = "Running", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderRunning() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(state = ServiceState.Running)
        }
    }
}

@Preview(name = "Power Save", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderLoading() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(
                state = ServiceState.Loading,
            )
        }
    }
}

@Preview(name = "Stopped", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderStopped() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(state = ServiceState.Stopped)
        }
    }
}

@Preview(name = "No Collection", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderNoCollection() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(state = ServiceState.DisabledNoCollection)
        }
    }
}

@Preview(name = "Power Save", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderPowerSave() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(
                state = ServiceState.DisabledPowerSave,
            )
        }
    }
}