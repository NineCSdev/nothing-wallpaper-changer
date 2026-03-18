package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.model.DelayLabel
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Card for configuring service-wide settings like animation delay.
 */
@Composable
fun ServiceSettingsCard(
    delayLabel: DelayLabel,
    onDelaySelected: (DelayLabel) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.outlinedCardColors(
            containerColor = NothingBlack,
            contentColor = NothingWhite
        ),
        border = BorderStroke(2.dp, NothingWhite.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = "SERVICE SETTINGS",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = NothingWhite.copy(alpha = 0.2f))

            // Delay Setting
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    text = "ANIMATION DELAY",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MATCH YOUR LOCKSCREEN TRANSITION",
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingWhite.copy(alpha = 0.4f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DelayOption(
                        label = "250MS",
                        isSelected = delayLabel == DelayLabel.SHORT,
                        onClick = { onDelaySelected(DelayLabel.SHORT) },
                        modifier = Modifier.weight(1f)
                    )
                    DelayOption(
                        label = "500MS",
                        isSelected = delayLabel == DelayLabel.MEDIUM,
                        onClick = { onDelaySelected(DelayLabel.MEDIUM) },
                        modifier = Modifier.weight(1f)
                    )
                    DelayOption(
                        label = "1000MS",
                        isSelected = delayLabel == DelayLabel.LONG,
                        onClick = { onDelaySelected(DelayLabel.LONG) },
                        modifier = Modifier.weight(1f)
                    )
                }

            }
        }
    }
}

@Composable
private fun DelayOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) NothingWhite else NothingBlack,
            contentColor = if (isSelected) NothingBlack else NothingWhite
        ),
        border = BorderStroke(1.dp, NothingWhite.copy(alpha = if (isSelected) 1f else 0.3f)),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
        )
    }
}
