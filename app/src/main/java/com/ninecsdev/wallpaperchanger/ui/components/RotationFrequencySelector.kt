package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

@Composable
fun RotationFrequencySelector(
    selectedFrequency: RotationFrequency,
    onFrequencySelected: (RotationFrequency) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "ROTATION FREQUENCY",
            style = MaterialTheme.typography.labelSmall,
            color = NothingWhite.copy(alpha = 0.7f),
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TimerOptionButton(
                label = "PER LOCK",
                selected = selectedFrequency == RotationFrequency.PER_LOCK,
                onClick = { onFrequencySelected(RotationFrequency.PER_LOCK) },
                modifier = Modifier.weight(1f)
            )
            TimerOptionButton(
                label = "EVERY 1H",
                selected = selectedFrequency == RotationFrequency.HOURLY,
                onClick = { onFrequencySelected(RotationFrequency.HOURLY) },
                modifier = Modifier.weight(1f)
            )
            TimerOptionButton(
                label = "DAILY",
                selected = selectedFrequency == RotationFrequency.PER_DAY,
                onClick = { onFrequencySelected(RotationFrequency.PER_DAY) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TimerOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
        border = BorderStroke(1.dp, NothingWhite.copy(alpha = if (selected) 0.8f else 0.3f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NothingWhite.copy(alpha = if (selected) 1f else 0.7f)
        )
    }
}
