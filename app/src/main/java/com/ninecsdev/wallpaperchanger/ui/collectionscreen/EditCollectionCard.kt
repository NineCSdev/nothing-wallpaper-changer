package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.components.NothingTextField
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.SmallCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

/**
 * Card pop-up for editing or deleting a collection.
 */
@Composable
internal fun EditCollectionCard(
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
        EditCollectionCardContent(
            collection = collection,
            isProcessing = isProcessing,
            nameText = nameText,
            onNameChange = { nameText = it },
            selectedRule = selectedRule,
            onRuleSelected = { selectedRule = it },
            selectedRotationFrequency = selectedRotationFrequency,
            onFrequencySelected = { selectedRotationFrequency = it },
            showDeleteConfirmation = showDeleteConfirmation,
            onDeleteRequest = { showDeleteConfirmation = true },
            onDeleteConfirm = {
                showDeleteConfirmation = false
                onDelete()
            },
            onDeleteCancel = { showDeleteConfirmation = false },
            onDismiss = onDismiss,
            onSyncClick = onSyncClick,
            onViewImages = onViewImages,
            onSetActive = onSetActive,
            onSave = { onEdit(nameText, selectedRule, selectedRotationFrequency) }
        )
    }
}

@Composable
private fun EditCollectionCardContent(
    collection: WallpaperCollection,
    isProcessing: Boolean,
    nameText: String,
    onNameChange: (String) -> Unit,
    selectedRule: CropRule,
    onRuleSelected: (CropRule) -> Unit,
    selectedRotationFrequency: RotationFrequency,
    onFrequencySelected: (RotationFrequency) -> Unit,
    showDeleteConfirmation: Boolean,
    onDeleteRequest: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    onDismiss: () -> Unit,
    onSyncClick: () -> Unit,
    onViewImages: () -> Unit,
    onSetActive: () -> Unit,
    onSave: () -> Unit
) {
    NothingDialogCard(
        isProcessing = isProcessing,
        processingMessage = stringResource(R.string.edit_collection_processing),
        modifier = Modifier.clickable(enabled = false) { },
        overlay = {
            if (showDeleteConfirmation) {
                ConfirmationOverlay(
                    title = stringResource(R.string.edit_collection_delete_title),
                    message = stringResource(R.string.edit_collection_delete_message),
                    onConfirm = onDeleteConfirm,
                    onCancel = onDeleteCancel
                )
            }
        }
    ) {
        EditCardHeader(
            collectionType = collection.type,
            onSyncClick = onSyncClick,
            onDismiss = onDismiss
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Rename is blocked for the Favourites collection as its name is a localized resource.
        // The field is hidden here and the repository guards the rename regardless.
        if (!collection.isFavorites) {
            NothingTextField(
                value = nameText,
                onValueChange = onNameChange,
                label = stringResource(R.string.edit_collection_field_name)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        CropRuleSelector(
            selectedRule = selectedRule,
            onRuleSelected = onRuleSelected
        )

        Spacer(modifier = Modifier.height(16.dp))

        RotationFrequencySelector(
            selectedFrequency = selectedRotationFrequency,
            onFrequencySelected = onFrequencySelected
        )

        Spacer(modifier = Modifier.height(24.dp))

        ManagementButtons(
            onDeleteRequest = onDeleteRequest,
            onViewImages = onViewImages
        )

        Spacer(modifier = Modifier.height(32.dp))

        EditCardActions(
            isActive = collection.isActive,
            isChanged = nameText != collection.name ||
                selectedRule != collection.defaultCropRule ||
                selectedRotationFrequency != collection.rotationFrequency,
            onSetActive = { onSetActive(); onDismiss() },
            onSave = { onSave(); onDismiss() },
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun RotationFrequencySelector(
    selectedFrequency: RotationFrequency,
    onFrequencySelected: (RotationFrequency) -> Unit
) {
    Text(
        text = stringResource(R.string.edit_collection_rotation_title),
        style = NothingType.rowLabel,
        color = NothingWhite.copy(alpha = 0.7f)
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TimerOptionButton(
            label = stringResource(R.string.edit_collection_rotation_per_lock),
            selected = selectedFrequency == RotationFrequency.PER_LOCK,
            onClick = { onFrequencySelected(RotationFrequency.PER_LOCK) },
            modifier = Modifier.weight(1f)
        )
        TimerOptionButton(
            label = stringResource(R.string.edit_collection_rotation_hourly),
            selected = selectedFrequency == RotationFrequency.HOURLY,
            onClick = { onFrequencySelected(RotationFrequency.HOURLY) },
            modifier = Modifier.weight(1f)
        )
        TimerOptionButton(
            label = stringResource(R.string.edit_collection_rotation_daily),
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
        shape = RoundedCornerShape(SmallCornerRadius),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
        border = BorderStroke(1.dp, NothingWhite.copy(alpha = if (selected) 0.8f else 0.3f)),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(
            text = label,
            style = NothingType.labelStrong,
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
            text = stringResource(R.string.edit_collection_title),
            style = NothingType.titleCaps,
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
            shape = RoundedCornerShape(SmallCornerRadius),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.3f))
        ) {
            Icon(painterResource(R.drawable.icon_collection), null, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.edit_collection_action_manage_images), style = NothingType.dialogButton)
        }

        Spacer(modifier = Modifier.height(12.dp))

        NothingButton(
            text = stringResource(R.string.edit_collection_action_delete),
            onClick = onDeleteRequest,
            variant = NothingButtonVariant.DANGER
        )
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
            shape = RoundedCornerShape(SmallCornerRadius),
            enabled = !isActive
        ) {
            Text(
                if (isActive) stringResource(R.string.edit_collection_action_currently_active) else stringResource(R.string.edit_collection_action_set_active),
                style = NothingType.labelStrong
            )
        }

        Button(
            onClick = onSave,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = NothingWhite, contentColor = NothingBlack),
            shape = RoundedCornerShape(SmallCornerRadius),
            enabled = isChanged
        ) {
            Text(stringResource(R.string.edit_collection_action_save), style = NothingType.badgeCaps)
        }
    }
}

// Previews

private val previewFolderCollection = WallpaperCollection(
    id = 1,
    name = "Amoled Dark",
    type = CollectionType.FOLDER
)

private val previewManualCollection = WallpaperCollection(
    id = 2,
    name = "Custom Favorites",
    type = CollectionType.MANUAL
)

/** Simulates the Dialog scrim so previews look identical to the in-app experience. */
@Composable
private fun DialogScrim(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Preview(
    name = "Folder Collection",
    showBackground = true,
    backgroundColor = 0xFF1A1A2E,
    device = "spec:width=411dp,height=891dp,dpi=420"
)
@Composable
private fun PreviewEditCollectionCardFolder() {
    WallpaperChangerTheme {
        DialogScrim {
            EditCollectionCardContent(
                collection = previewFolderCollection,
                isProcessing = false,
                nameText = previewFolderCollection.name,
                onNameChange = {},
                selectedRule = previewFolderCollection.defaultCropRule,
                onRuleSelected = {},
                selectedRotationFrequency = previewFolderCollection.rotationFrequency,
                onFrequencySelected = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onViewImages = {},
                onSetActive = {},
                onSave = {}
            )
        }
    }
}

@Preview(
    name = "Manual Collection",
    showBackground = true,
    backgroundColor = 0xFF1A1A2E,
    device = "spec:width=411dp,height=891dp,dpi=420"
)
@Composable
private fun PreviewEditCollectionCardManual() {
    WallpaperChangerTheme {
        DialogScrim {
            EditCollectionCardContent(
                collection = previewManualCollection,
                isProcessing = false,
                nameText = previewManualCollection.name,
                onNameChange = {},
                selectedRule = previewManualCollection.defaultCropRule,
                onRuleSelected = {},
                selectedRotationFrequency = previewManualCollection.rotationFrequency,
                onFrequencySelected = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onViewImages = {},
                onSetActive = {},
                onSave = {}
            )
        }
    }
}

@Preview(
    name = "Active Collection",
    showBackground = true,
    backgroundColor = 0xFF1A1A2E,
    device = "spec:width=411dp,height=891dp,dpi=420"
)
@Composable
private fun PreviewEditCollectionCardActive() {
    WallpaperChangerTheme {
        DialogScrim {
            EditCollectionCardContent(
                collection = previewFolderCollection.copy(isActive = true),
                isProcessing = false,
                nameText = previewFolderCollection.name,
                onNameChange = {},
                selectedRule = previewFolderCollection.defaultCropRule,
                onRuleSelected = {},
                selectedRotationFrequency = previewFolderCollection.rotationFrequency,
                onFrequencySelected = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onViewImages = {},
                onSetActive = {},
                onSave = {}
            )
        }
    }
}

@Preview(
    name = "Processing",
    showBackground = true,
    backgroundColor = 0xFF1A1A2E,
    device = "spec:width=411dp,height=891dp,dpi=420"
)
@Composable
private fun PreviewEditCollectionCardProcessing() {
    WallpaperChangerTheme {
        DialogScrim {
            EditCollectionCardContent(
                collection = previewFolderCollection,
                isProcessing = true,
                nameText = previewFolderCollection.name,
                onNameChange = {},
                selectedRule = previewFolderCollection.defaultCropRule,
                onRuleSelected = {},
                selectedRotationFrequency = previewFolderCollection.rotationFrequency,
                onFrequencySelected = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onViewImages = {},
                onSetActive = {},
                onSave = {}
            )
        }
    }
}
