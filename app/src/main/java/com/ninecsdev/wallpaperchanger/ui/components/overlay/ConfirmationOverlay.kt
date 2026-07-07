package com.ninecsdev.wallpaperchanger.ui.components.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Generic two-button confirmation dialog in the app's black-card styling.
 *
 * [accentColor] tints the border, title, and confirm button, it signals intent, so pass the
 * default [NothingRed] for destructive actions (delete, discard) and a neutral color like
 * [NothingWhite] for constructive/recovery ones (e.g. re-linking an unavailable image).
 */
@Composable
fun ConfirmationOverlay(
    modifier: Modifier = Modifier,
    title: String,
    message: String,
    confirmLabel: String = "DELETE",
    cancelLabel: String = "CANCEL",
    accentColor: Color = NothingRed,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = NothingBlack),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = accentColor
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = NothingWhite,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
                        border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.4f))
                    ) {
                        Text(cancelLabel, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = if (accentColor.luminance() > 0.5f) NothingBlack else NothingWhite
                        )
                    ) {
                        Text(confirmLabel, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Preview(name = "Confirmation Overlay", backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun ConfirmationOverlayPreview() {
    MaterialTheme {
        Scaffold(containerColor = NothingBlack){ padding ->
            ConfirmationOverlay(
                modifier = Modifier.padding(16.dp),
                onConfirm = {},
                onCancel = {},
                title = "Title",
                message = "Description",
                confirmLabel = "Confirm",
                cancelLabel = "Cancel"
            )
        }
    }
}
