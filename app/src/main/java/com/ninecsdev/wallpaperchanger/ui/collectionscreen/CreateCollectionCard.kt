package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.components.NothingTextField
import com.ninecsdev.wallpaperchanger.ui.theme.NothingDarkGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingDarkGraySelected
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Card pop-up for creating a new collection.
 */
@Composable
internal fun CreateCollectionCard(
    isProcessing: Boolean = false,
    hasPendingFolder: Boolean,
    hasPendingPhotos: Boolean,
    onDismiss: () -> Unit,
    onFolderSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onCreateClick: (String, CropRule) -> Unit
) {
    var collectionName by remember { mutableStateOf("") }
    var selectedRule by remember { mutableStateOf(CropRule.CENTER) }

    Dialog(
        onDismissRequest = if (isProcessing) ({}) else onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CreateCollectionCardContent(
            isProcessing = isProcessing,
            hasPendingFolder = hasPendingFolder,
            hasPendingPhotos = hasPendingPhotos,
            collectionName = collectionName,
            onCollectionNameChange = { collectionName = it },
            selectedRule = selectedRule,
            onRuleSelected = { selectedRule = it },
            onDismiss = onDismiss,
            onFolderSelect = onFolderSelect,
            onPhotosSelect = onPhotosSelect,
            onCreateClick = { onCreateClick(collectionName, selectedRule) }
        )
    }
}

@Composable
private fun CreateCollectionCardContent(
    isProcessing: Boolean,
    hasPendingFolder: Boolean,
    hasPendingPhotos: Boolean,
    collectionName: String,
    onCollectionNameChange: (String) -> Unit,
    selectedRule: CropRule,
    onRuleSelected: (CropRule) -> Unit,
    onDismiss: () -> Unit,
    onFolderSelect: () -> Unit,
    onPhotosSelect: () -> Unit,
    onCreateClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NothingBlack),
        border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.2f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CreateCardHeader(onDismiss)

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SourceButton(
                        modifier = Modifier.weight(1f),
                        icon = painterResource(R.drawable.icon_filled_folder),
                        label = stringResource(R.string.create_collection_label_folder),
                        isSelected = hasPendingFolder,
                        onClick = onFolderSelect
                    )
                    SourceButton(
                        modifier = Modifier.weight(1f),
                        icon = painterResource(R.drawable.icon_collection),
                        label = stringResource(R.string.create_collection_label_photos),
                        isSelected = hasPendingPhotos,
                        onClick = onPhotosSelect
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                NothingTextField(
                    value = collectionName,
                    onValueChange = onCollectionNameChange,
                    label = stringResource(R.string.create_collection_field_name)
                )

                Spacer(modifier = Modifier.height(16.dp))

                CropRuleSelector(
                    selectedRule = selectedRule,
                    onRuleSelected = onRuleSelected
                )

                Spacer(modifier = Modifier.height(16.dp))

                CreateCardActions(
                    onDismiss = onDismiss,
                    isProcessing = isProcessing,
                    enabled = collectionName.isNotBlank() && (hasPendingFolder || hasPendingPhotos),
                    onCreate = onCreateClick
                )
            }

            if (isProcessing) {
                ProcessingOverlay(
                    message = stringResource(R.string.create_collection_processing),
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

@Composable
private fun CreateCardHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.create_collection_title),
            color = NothingWhite,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = null, tint = NothingWhite)
        }
    }
}

@Composable
private fun SourceButton(
    modifier: Modifier,
    icon: Painter,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) NothingWhite else NothingWhite.copy(alpha = 0.1f)
    val backgroundColor = if (isSelected) NothingDarkGraySelected else NothingDarkGray

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = if (isSelected) NothingWhite else NothingWhite.copy(0.5f),
            modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) NothingWhite else NothingWhite.copy(0.5f)
        )
    }
}

@Composable
private fun CreateCardActions(
    isProcessing: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NothingButton(
            text = stringResource(R.string.create_collection_action_cancel),
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            enabled = !isProcessing,
            variant = NothingButtonVariant.SECONDARY
        )
        NothingButton(
            text = stringResource(R.string.create_collection_action_create),
            onClick = onCreate,
            modifier = Modifier.weight(1f),
            enabled = enabled && !isProcessing,
            variant = NothingButtonVariant.PRIMARY
        )
    }
}

// Previews

@Preview(name = "CreateCollectionCard – Idle", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewCreateCollectionCardIdle() {
    MaterialTheme {
        CreateCollectionCardContent(
            isProcessing = false,
            hasPendingFolder = false,
            hasPendingPhotos = false,
            collectionName = "",
            onCollectionNameChange = {},
            selectedRule = CropRule.CENTER,
            onRuleSelected = {},
            onDismiss = {},
            onFolderSelect = {},
            onPhotosSelect = {},
            onCreateClick = {}
        )
    }
}

@Preview(name = "CreateCollectionCard – Folder Selected", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewCreateCollectionCardFolderSelected() {
    MaterialTheme {
        CreateCollectionCardContent(
            isProcessing = false,
            hasPendingFolder = true,
            hasPendingPhotos = false,
            collectionName = "",
            onCollectionNameChange = {},
            selectedRule = CropRule.CENTER,
            onRuleSelected = {},
            onDismiss = {},
            onFolderSelect = {},
            onPhotosSelect = {},
            onCreateClick = {}
        )
    }
}

@Preview(name = "CreateCollectionCard – Ready to Create", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewCreateCollectionCardReady() {
    MaterialTheme {
        CreateCollectionCardContent(
            isProcessing = false,
            hasPendingFolder = true,
            hasPendingPhotos = true,
            collectionName = "Morning Vibes",
            onCollectionNameChange = {},
            selectedRule = CropRule.FIT,
            onRuleSelected = {},
            onDismiss = {},
            onFolderSelect = {},
            onPhotosSelect = {},
            onCreateClick = {}
        )
    }
}

@Preview(name = "CreateCollectionCard – Processing", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewCreateCollectionCardProcessing() {
    MaterialTheme {
        CreateCollectionCardContent(
            isProcessing = true,
            hasPendingFolder = true,
            hasPendingPhotos = false,
            collectionName = "Morning Vibes",
            onCollectionNameChange = {},
            selectedRule = CropRule.CENTER,
            onRuleSelected = {},
            onDismiss = {},
            onFolderSelect = {},
            onPhotosSelect = {},
            onCreateClick = {}
        )
    }
}