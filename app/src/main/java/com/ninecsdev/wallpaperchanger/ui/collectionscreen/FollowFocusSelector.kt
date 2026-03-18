package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Toggle row that gates the "FOCUS MODE AWARE" switch behind a DND-permission
 * rationale dialog whenever `ACCESS_NOTIFICATION_POLICY` has not yet been granted.
 *
 * - Tapping the switch ON when permission is missing shows [DndPermissionDialog].
 * - Pressing **OK** in the dialog navigates the user to the system Notification
 *   Policy Access settings page so they can grant the permission manually.
 * - Pressing **Cancel** (or dismissing the dialog) reverts the switch to OFF and
 *   shows a brief Toast informing the user that Focus Mode aware has not been set.
 */
@Composable
fun FollowFocusSelector(
    followFocus: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }

    if (showPermissionDialog) {
        DndPermissionDialog(
            onConfirm = {
                showPermissionDialog = false
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                )
            },
            onDismiss = {
                showPermissionDialog = false
                onToggle(false)
                Toast.makeText(
                    context,
                    "DND access not granted. Focus Mode aware has not been set.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = followFocus,
                onValueChange = { newValue ->
                    if (newValue) {
                        val nm = context.getSystemService(NotificationManager::class.java)
                        onToggle(true)
                        if (nm?.isNotificationPolicyAccessGranted == false) {
                            showPermissionDialog = true
                        }
                    } else {
                        onToggle(false)
                    }
                },
                role = Role.Switch
            )
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "FOCUS MODE AWARE",
                color = NothingWhite,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            )
            Text(
                text = "DO NOT ROTATE IN FOCUS/DND",
                color = NothingWhite.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Switch(
            checked = followFocus,
            onCheckedChange = null,
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

    Text(
        text = "TIP: Focus Mode detection varies by OEM. Grant DND access for best accuracy.",
        color = NothingWhite.copy(alpha = 0.55f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun DndPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = NothingBlack),
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "PERMISSION REQUIRED",
                    color = NothingWhite,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Focus Mode detection requires Do Not Disturb access. " +
                        "You will be taken to your device settings where you need to " +
                        "enable this permission manually.",
                    color = NothingWhite.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NothingButton(
                        text = "CANCEL",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        variant = NothingButtonVariant.SECONDARY
                    )
                    NothingButton(
                        text = "OK",
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
