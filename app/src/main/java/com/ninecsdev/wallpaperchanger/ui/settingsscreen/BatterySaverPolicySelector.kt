package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.model.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
fun BatterySaverPolicySelector(
    selected: BatterySaverPolicy,
    onPolicyChange: (BatterySaverPolicy) -> Unit
) {
    Column {
        Text(
            text = "BATTERY SAVER POLICY",
            style = MaterialTheme.typography.bodySmall,
            color = NothingWhite,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = "WHAT TO DO WHEN BATTERY SAVER ACTIVATES",
            style = MaterialTheme.typography.labelSmall,
            color = NothingWhite.copy(alpha = 0.4f),
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BatterySaverPolicy.entries.forEach { policy ->
                val isSelected = policy == selected
                val label = when (policy) {
                    BatterySaverPolicy.STOP -> "STOP"
                    BatterySaverPolicy.PAUSE -> "PAUSE"
                    BatterySaverPolicy.IGNORE -> "IGNORE"
                }

                TextButton(
                    onClick = { onPolicyChange(policy) },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) NothingWhite else NothingWhite.copy(alpha = 0.25f)
                    ),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (isSelected) NothingWhite.copy(alpha = 0.12f)
                        else NothingBlack,
                        contentColor = if (isSelected) NothingWhite
                        else NothingWhite.copy(alpha = 0.5f)
                    ),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun BatterySaverPolicySelectorPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp)
        ) {
            BatterySaverPolicySelector(
                selected = BatterySaverPolicy.STOP,
                onPolicyChange = {}
            )
        }
    }
}