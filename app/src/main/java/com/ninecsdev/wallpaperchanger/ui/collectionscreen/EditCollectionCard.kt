package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.CollectionType
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.ui.components.CropRuleSelector
import com.ninecsdev.wallpaperchanger.ui.components.NothingTextField
import com.ninecsdev.wallpaperchanger.ui.components.ProcessingOverlay
import com.ninecsdev.wallpaperchanger.ui.components.RotationFrequencySelector
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
    onEdit: (String, CropRule, RotationFrequency, Boolean) -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
    onSyncClick: () -> Unit
) {
    var nameText by remember { mutableStateOf(collection.name) }
    var selectedRule by remember { mutableStateOf(collection.defaultCropRule) }
    var selectedRotationFrequency by remember { mutableStateOf(collection.rotationFrequency) }
    var skipOnDnd by remember { mutableStateOf(collection.skipOnDnd) }

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
                        label = "LIST NAME"
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

                    Spacer(modifier = Modifier.height(16.dp))

                    FollowFocusSelector(
                        followFocus = skipOnDnd,
                        onToggle = { skipOnDnd = it }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    ManagementButtons(onDelete = onDelete)

                    Spacer(modifier = Modifier.height(32.dp))

                    EditCardActions(
                        isActive = collection.isActive,
                        isChanged = nameText != collection.name ||
                            selectedRule != collection.defaultCropRule ||
                            selectedRotationFrequency != collection.rotationFrequency ||
                            skipOnDnd != collection.skipOnDnd,
                        onSetActive = onSetActive,
                        onSave = {
                            onEdit(nameText, selectedRule, selectedRotationFrequency, skipOnDnd)
                            onDismiss()
                        },
                        onDismiss = onDismiss
                    )
                }

                if (isProcessing) {
                    ProcessingOverlay(
                        message = "UPDATING LIST...",
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
        }
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
            text = "MANAGE LIST",
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
                        contentDescription = "Sync",
                        tint = NothingWhite.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close", tint = NothingWhite)
            }
        }
    }
}

@Composable
private fun ManagementButtons(onDelete: () -> Unit) {
    Column {
        OutlinedButton(
            onClick = { /* TODO: Open Image Grid Screen remember to modify the alpha on colors */ },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite.copy(alpha = 0.1f)),
            border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.3f))
        ) {
            Icon(painterResource(R.drawable.icon_collection), null, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text("MANAGE IMAGES", fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = NothingRed),
            border = BorderStroke(1.dp, NothingRed.copy(alpha = 0.4f))
        ) {
            Text("DELETE LIST", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                if (isActive) "CURRENTLY ACTIVE" else "SET AS ACTIVE",
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
            Text("SAVE", fontWeight = FontWeight.Black)
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
            onEdit = { _, _, _, _ -> },
            onDelete = {},
            onSetActive = {},
            onSyncClick = {}
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
                onEdit = { _, _, _, _ -> },
                onDelete = {},
                onSetActive = {},
                onSyncClick = {}
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
            onEdit = { _, _, _, _ -> },
            onDelete = {},
            onSetActive = {},
            onSyncClick = {}
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
                onEdit = { _, _, _, _ -> },
                onDelete = {},
                onSetActive = {},
                onSyncClick = {}
            )
        }
    }
}
