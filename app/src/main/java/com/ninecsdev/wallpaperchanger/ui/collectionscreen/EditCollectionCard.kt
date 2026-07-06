package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.ui.components.CropRuleSelector
import com.ninecsdev.wallpaperchanger.ui.components.ConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.components.NothingTextField
import com.ninecsdev.wallpaperchanger.ui.components.ProcessingOverlay
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Card pop-up for editing or deleting a collection.
 */
@Composable
fun EditCollectionCard(
    collection: WallpaperCollection,
    isProcessing: Boolean = false,
    onDismiss: () -> Unit,
    onEdit: (String, CropRule, RotationFrequency) -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
    onSyncClick: () -> Unit,
    onViewImages: () -> Unit
) {
    var nameText by remember { mutableStateOf(collection.name) }
    var selectedRule by remember { mutableStateOf(collection.defaultCropRule) }
    var selectedRotationFrequency by remember { mutableStateOf(collection.rotationFrequency) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = if (isProcessing) ({}) else onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clickable(enabled = false) { },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = NothingBlack),
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.2f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.padding(24.dp)) {

                    EditCardHeader(
                        collectionType = collection.type,
                        onSyncClick = onSyncClick,
                        onDismiss = onDismiss
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    NothingTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = stringResource(R.string.edit_list_field_name)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    CropRuleSelector(
                        selectedRule = selectedRule,
                        onRuleSelected = { selectedRule = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    RotationFrequencySelector(
                        selectedFrequency = selectedRotationFrequency,
                        onFrequencySelected = { selectedRotationFrequency = it }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    ManagementButtons(
                        onDeleteRequest = { showDeleteConfirmation = true },
                        onViewImages = {
                            onDismiss()
                            onViewImages()
                        }
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    EditCardActions(
                        isActive = collection.isActive,
                        isChanged = nameText != collection.name ||
                            selectedRule != collection.defaultCropRule ||
                            selectedRotationFrequency != collection.rotationFrequency,
                        onSetActive = onSetActive,
                        onSave = {
                            onEdit(nameText, selectedRule, selectedRotationFrequency)
                            onDismiss()
                        },
                        onDismiss = onDismiss
                    )
                }

                if (isProcessing) {
                    ProcessingOverlay(
                        message = stringResource(R.string.edit_list_processing),
                        modifier = Modifier.matchParentSize()
                    )
                }

                if(showDeleteConfirmation) {
                    ConfirmationOverlay(
                        title = stringResource(R.string.edit_list_delete_title),
                        message = stringResource(R.string.edit_list_delete_message),
                        onConfirm = {
                            showDeleteConfirmation = false
                            onDelete()
                        },
                        onCancel = { showDeleteConfirmation = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun RotationFrequencySelector(
    selectedFrequency: RotationFrequency,
    onFrequencySelected: (RotationFrequency) -> Unit
) {
    Text(
        text = stringResource(R.string.edit_list_rotation_title),
        style = MaterialTheme.typography.labelSmall,
        color = NothingWhite.copy(alpha = 0.7f),
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TimerOptionButton(
            label = stringResource(R.string.edit_list_rotation_per_lock),
            selected = selectedFrequency == RotationFrequency.PER_LOCK,
            onClick = { onFrequencySelected(RotationFrequency.PER_LOCK) },
            modifier = Modifier.weight(1f)
        )
        TimerOptionButton(
            label = stringResource(R.string.edit_list_rotation_hourly),
            selected = selectedFrequency == RotationFrequency.HOURLY,
            onClick = { onFrequencySelected(RotationFrequency.HOURLY) },
            modifier = Modifier.weight(1f)
        )
        TimerOptionButton(
            label = stringResource(R.string.edit_list_rotation_daily),
            selected = selectedFrequency == RotationFrequency.PER_DAY,
            onClick = { onFrequencySelected(RotationFrequency.PER_DAY) },
            modifier = Modifier.weight(1f)
        )
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
        border = BorderStroke(1.dp, NothingWhite.copy(alpha = if (selected) 0.8f else 0.3f)),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NothingWhite.copy(alpha = if (selected) 1f else 0.7f),
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EditCardHeader(
    collectionType: CollectionType,
    onSyncClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.edit_list_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = NothingWhite
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (collectionType == CollectionType.FOLDER) {
                IconButton(onClick = onSyncClick) {
                    Icon(
                        painter = painterResource(R.drawable.icon_sync),
                        contentDescription = stringResource(R.string.cd_sync),
                        tint = NothingWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.cd_close), tint = NothingWhite)
            }
        }
    }
}

@Composable
private fun ManagementButtons(
    onDeleteRequest: () -> Unit,
    onViewImages: () -> Unit
) {
    Column {
        OutlinedButton(
            onClick = onViewImages,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.3f))
        ) {
            Icon(painterResource(R.drawable.icon_collection), null, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.edit_list_action_manage_images), fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onDeleteRequest,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = NothingRed),
            border = BorderStroke(1.dp, NothingRed.copy(alpha = 0.4f))
        ) {
            Text(stringResource(R.string.edit_list_action_delete), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun EditCardActions(
    isActive: Boolean,
    isChanged: Boolean,
    onSetActive: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { onSetActive(); onDismiss() },
            modifier = Modifier.weight(1.2f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = NothingWhite
            ),
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = if (isActive) 0.1f else 0.4f)),
            shape = RoundedCornerShape(8.dp),
            enabled = !isActive
        ) {
            Text(
                if (isActive) stringResource(R.string.edit_list_action_currently_active) else stringResource(R.string.edit_list_action_set_active),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Button(
            onClick = onSave,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = NothingWhite, contentColor = NothingBlack),
            shape = RoundedCornerShape(8.dp),
            enabled = isChanged
        ) {
            Text(stringResource(R.string.edit_list_action_save), fontWeight = FontWeight.Black)
        }
    }
}

@Preview(name = "Folder Collection", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun EditCollectionCardPreview() {
    MaterialTheme {
        EditCollectionCard(
            collection = WallpaperCollection(
                id = 1,
                name = "Amoled Dark",
                type = CollectionType.FOLDER
            ),
            onDismiss = {},
            onEdit = { _, _, _ -> },
            onDelete = {},
            onSetActive = {},
            onSyncClick = {},
            onViewImages = {}
        )
    }
}

@Preview(name = "Manual Collection", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun EditCollectionCardManualPreview() {
    MaterialTheme {
        Box(Modifier.padding(16.dp)) {
            EditCollectionCard(
                collection = WallpaperCollection(
                    id = 2,
                    name = "Custom Favorites",
                    type = CollectionType.MANUAL
                ),
                onDismiss = {},
                onEdit = { _, _, _ -> },
                onDelete = {},
                onSetActive = {},
                onSyncClick = {},
                onViewImages = {}
            )
        }
    }
}

@Preview(name = "Active Collection", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun EditCollectionCardActivePreview() {
    MaterialTheme {
        EditCollectionCard(
            collection = WallpaperCollection(
                id = 1,
                name = "Amoled Dark",
                type = CollectionType.FOLDER,
                isActive = true
            ),
            onDismiss = {},
            onEdit = { _, _, _ -> },
            onDelete = {},
            onSetActive = {},
            onSyncClick = {},
            onViewImages = {}
        )
    }
}

@Preview(name = "Syncing", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun EditCollectionCardSyncingPreview() {
    MaterialTheme {
        Box(Modifier.padding(16.dp)) {
            EditCollectionCard(
                collection = WallpaperCollection(
                    id = 1,
                    name = "Amoled Nature",
                    type = CollectionType.FOLDER
                ),
                isProcessing = true,
                onDismiss = {},
                onEdit = { _, _, _ -> },
                onDelete = {},
                onSetActive = {},
                onSyncClick = {},
                onViewImages = {}
            )
        }
    }
}
