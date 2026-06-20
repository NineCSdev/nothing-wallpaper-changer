package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
fun BootToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_autostart_title),
                style = MaterialTheme.typography.bodySmall,
                color = NothingWhite,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = stringResource(R.string.settings_autostart_subtitle),
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

@Preview
@Composable
private fun BootTogglePreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            BootToggle(
                checked = true,
                onCheckedChange = {}
            )
        }
    }
}