package com.ninecsdev.wallpaperchanger.ui.collectionscreen.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.policyOr
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.resolveDisplayName
import com.ninecsdev.wallpaperchanger.ui.components.NothingBottomSheet
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.components.RotationPickerContent
import com.ninecsdev.wallpaperchanger.ui.components.SettingsRowHeader
import com.ninecsdev.wallpaperchanger.ui.components.WallpaperThumbnail
import com.ninecsdev.wallpaperchanger.ui.components.rotationSummary
import com.ninecsdev.wallpaperchanger.ui.components.overlay.ConfirmationOverlay
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGreen
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.SmallCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme

private const val DEFAULT_THUMBNAIL_WIDTH_DP = 30
private const val DEFAULT_THUMBNAIL_HEIGHT_DP = 40

/** Fraction of the screen width the default-wallpaper thumbnail occupies. Approximated. */
private const val DEFAULT_THUMBNAIL_DECODE_FRACTION = 0.1f
/**
 * Card pop-up for managing a collection.
 *
 * For folder collections there is a folder section with folder specific actions.
 */
// TODO tests: see vault note tests/Edit Collection Card Tests
@Composable
internal fun EditCollectionCard(
    collection: WallpaperCollection,
    imageCount: Int,
    isProcessing: Boolean = false,
    excludedCount: Int = 0,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onCropRuleSelected: (CropRule) -> Unit,
    onRotationPolicySelected: (CollectionRotationSetting) -> Unit,
    globalRotationPolicy: RotationPolicy,
    defaultWallpaper: WallpaperImage?,
    onClearDefault: () -> Unit,
    onDelete: () -> Unit,
    onSyncClick: () -> Unit,
    onRestoreRemoved: () -> Unit = {}
) {
    var nameText by remember(collection.id) { mutableStateOf(collection.name) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Commits a valid rename; blank or unchanged input reverts to the current name.
    val commitName = {
        val trimmed = nameText.trim()
        if (trimmed.isNotEmpty() && trimmed != collection.name) onRename(trimmed)
        else nameText = collection.name
    }
    val dismiss = { commitName(); onDismiss() }

    Dialog(
        onDismissRequest = if (isProcessing) ({}) else dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        EditCollectionCardContent(
            collection = collection,
            imageCount = imageCount,
            isProcessing = isProcessing,
            excludedCount = excludedCount,
            nameText = nameText,
            onNameChange = { nameText = it },
            onNameCommit = commitName,
            onCropRuleSelected = onCropRuleSelected,
            onRotationPolicySelected = onRotationPolicySelected,
            globalRotationPolicy = globalRotationPolicy,
            defaultWallpaper = defaultWallpaper,
            onClearDefault = onClearDefault,
            showDeleteConfirmation = showDeleteConfirmation,
            onDeleteRequest = { showDeleteConfirmation = true },
            onDeleteConfirm = {
                showDeleteConfirmation = false
                onDelete()
            },
            onDeleteCancel = { showDeleteConfirmation = false },
            onDismiss = dismiss,
            onSyncClick = onSyncClick,
            onRestoreRemoved = onRestoreRemoved
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditCollectionCardContent(
    collection: WallpaperCollection,
    imageCount: Int,
    isProcessing: Boolean,
    excludedCount: Int,
    nameText: String,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit,
    onCropRuleSelected: (CropRule) -> Unit,
    onRotationPolicySelected: (CollectionRotationSetting) -> Unit,
    globalRotationPolicy: RotationPolicy,
    defaultWallpaper: WallpaperImage?,
    onClearDefault: () -> Unit,
    showDeleteConfirmation: Boolean,
    onDeleteRequest: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    onDismiss: () -> Unit,
    onSyncClick: () -> Unit,
    onRestoreRemoved: () -> Unit
) {
    var showRotationPicker by remember(collection.id) { mutableStateOf(false) }
    val effectivePolicy = collection.rotationPolicy.policyOr(globalRotationPolicy)

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
            if (showRotationPicker) {
                NothingBottomSheet(onDismiss = { showRotationPicker = false }) { hideThen ->
                    RotationPickerContent(
                        title = stringResource(R.string.edit_collection_rotation_title),
                        policy = effectivePolicy,
                        // Picking a cadence here is the override
                        onPolicyChange = { onRotationPolicySelected(CollectionRotationSetting.Override(it)) },
                        onDone = { hideThen { showRotationPicker = false } },
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    ) {
        EditCardHeader(
            collection = collection,
            imageCount = imageCount,
            nameText = nameText,
            onNameChange = onNameChange,
            onNameCommit = onNameCommit,
            onDismiss = onDismiss
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingsRowHeader(
            title = stringResource(R.string.edit_collection_crop_title),
            subtitle = stringResource(cropRuleLabelRes(collection.defaultCropRule)),
            infoDialogTitle = stringResource(R.string.edit_collection_crop_info_title),
            infoDialogBody = stringResource(R.string.edit_collection_crop_info_body)
        )

        Spacer(modifier = Modifier.height(8.dp))

        CropRuleSelector(
            selectedRule = collection.defaultCropRule,
            onRuleSelected = onCropRuleSelected
        )

        Spacer(modifier = Modifier.height(16.dp))

        RotationSettingRow(
            setting = collection.rotationPolicy,
            effectivePolicy = effectivePolicy,
            onOpenPicker = { showRotationPicker = true },
            onOverrideChange = { override ->
                onRotationPolicySelected(
                    if (override) CollectionRotationSetting.Override(effectivePolicy)
                    else CollectionRotationSetting.FollowGlobal
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        DefaultWallpaperRow(wallpaper = defaultWallpaper, onClear = onClearDefault)

        if (collection.type == CollectionType.FOLDER) {
            Spacer(modifier = Modifier.height(24.dp))

            FolderMaintenanceSection(
                excludedCount = excludedCount,
                onSyncClick = onSyncClick,
                onRestoreRemoved = onRestoreRemoved
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        NothingButton(
            text = stringResource(R.string.edit_collection_action_delete),
            onClick = onDeleteRequest,
            variant = NothingButtonVariant.DANGER,
            leadingIcon = {
                Icon(painterResource(R.drawable.icon_delete), null, Modifier.size(18.dp))
            }
        )
    }
}

/**
 * Collection-identity header: the name as an in-place editable title (static for the
 * rename-blocked Favourites collection), a `TYPE · N IMAGES · ACTIVE` metadata line, and ✕.
 */
@Composable
private fun EditCardHeader(
    collection: WallpaperCollection,
    imageCount: Int,
    nameText: String,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EditableTitle(
                nameText = nameText,
                canRename = !collection.isFavorites,
                staticName = collection.resolveDisplayName(LocalContext.current),
                onNameChange = onNameChange,
                onNameCommit = onNameCommit
            )

            Spacer(modifier = Modifier.height(4.dp))

            CollectionMetadataLine(
                type = collection.type,
                imageCount = imageCount,
                isActive = collection.isActive
            )
        }

        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, stringResource(R.string.cd_close), tint = NothingWhite)
        }
    }
}

@Composable
private fun EditableTitle(
    nameText: String,
    canRename: Boolean,
    staticName: String,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit
) {
    if (!canRename) {
        Text(
            text = staticName,
            style = NothingType.createDialogTitle,
            color = NothingWhite,
            maxLines = 1
        )
        return
    }

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    // BasicTextField fills whatever width it is given, which would strand the pencil at the far
    // edge on short names. Sizing the field to the measured text keeps the pencil hugging it;
    // weight() then clamps it so long names still truncate instead of pushing the icon off-card.
    val textStyle = NothingType.createDialogTitle.copy(color = NothingWhite)
    val textMeasurer = rememberTextMeasurer()
    val fieldWidth = with(LocalDensity.current) {
        textMeasurer.measure(nameText, textStyle, maxLines = 1).size.width.toDp()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = nameText,
            onValueChange = onNameChange,
            textStyle = textStyle,
            cursorBrush = SolidColor(NothingWhite),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onNameCommit()
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .weight(1f, fill = false)
                // +2dp so the caret at the end of the text is not clipped.
                .width(fieldWidth + 2.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Tap-to-edit affordance: brightens while editing, and focuses the field when tapped.
        Icon(
            painter = painterResource(R.drawable.icon_edit),
            contentDescription = null,
            tint = NothingWhite.copy(alpha = if (isFocused) 0.9f else 0.4f),
            modifier = Modifier
                .size(18.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusRequester.requestFocus() }
        )
    }
}

@Composable
private fun CollectionMetadataLine(
    type: CollectionType,
    imageCount: Int,
    isActive: Boolean
) {
    val typeLabel = stringResource(
        if (type == CollectionType.FOLDER) R.string.edit_collection_meta_folder
        else R.string.edit_collection_meta_manual
    )
    val countLabel = pluralStringResource(R.plurals.edit_collection_meta_images, imageCount, imageCount)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "$typeLabel · $countLabel",
            style = NothingType.metaLabel,
            color = NothingWhite.copy(alpha = 0.45f)
        )
        if (isActive) {
            Text(
                text = "·",
                style = NothingType.metaLabel,
                color = NothingWhite.copy(alpha = 0.45f)
            )
            Text(
                text = stringResource(R.string.edit_collection_meta_active),
                style = NothingType.metaLabel,
                color = NothingGreen
            )
        }
    }
}

private fun cropRuleLabelRes(rule: CropRule): Int = when (rule) {
    CropRule.CENTER -> R.string.crop_rule_center
    CropRule.LEFT -> R.string.crop_rule_left
    CropRule.RIGHT -> R.string.crop_rule_right
    CropRule.FIT -> R.string.crop_rule_fit
}

@Composable
private fun RotationSettingRow(
    setting: CollectionRotationSetting,
    effectivePolicy: RotationPolicy,
    onOpenPicker: () -> Unit,
    onOverrideChange: (Boolean) -> Unit
) {
    val isOverride = setting is CollectionRotationSetting.Override
    val cadence = rotationSummary(effectivePolicy)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenPicker),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsRowHeader(
                title = stringResource(R.string.edit_collection_rotation_title),
                subtitle = stringResource(
                    if (isOverride) R.string.edit_collection_rotation_override
                    else R.string.edit_collection_rotation_following_global,
                    cadence
                ),
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = NothingWhite.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .height(28.dp)
                .width(1.dp)
                .background(NothingWhite.copy(alpha = 0.15f))
        )

        Spacer(modifier = Modifier.width(4.dp))

        Switch(
            checked = isOverride,
            onCheckedChange = onOverrideChange,
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

/**
 * The collection's default-wallpaper override, read-and-clear-only.
 *
 * Setting one happens on the image itself (the selection pill and the full-screen preview), so this
 * row only reports and clears. With no override it collapses to the title and "Following global".
 */
@Composable
private fun DefaultWallpaperRow(wallpaper: WallpaperImage?, onClear: () -> Unit) {
    if (wallpaper == null) {
        SettingsRowHeader(
            title = stringResource(R.string.edit_collection_default_title),
            subtitle = stringResource(R.string.edit_collection_default_following_global)
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WallpaperThumbnail(
            wallpaper = wallpaper,
            modifier = Modifier.size(width = DEFAULT_THUMBNAIL_WIDTH_DP.dp, height = DEFAULT_THUMBNAIL_HEIGHT_DP.dp),
            decodeFraction = DEFAULT_THUMBNAIL_DECODE_FRACTION
        )

        Spacer(modifier = Modifier.width(12.dp))

        SettingsRowHeader(
            title = stringResource(R.string.edit_collection_default_title),
            subtitle = stringResource(R.string.edit_collection_default_override),
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_clear_collection_default),
                tint = NothingWhite.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Folder-only maintenance actions: manual re-sync and, when [excludedCount] > 0, the
 * "Restore removed images (N)" recovery row (the row disappears once the count is 0).
 */
@Composable
private fun FolderMaintenanceSection(
    excludedCount: Int,
    onSyncClick: () -> Unit,
    onRestoreRemoved: () -> Unit
) {
    Column {
        SettingsRowHeader(
            title = stringResource(R.string.edit_collection_folder_actions_header),
            subtitle = null
        )

        Spacer(modifier = Modifier.height(8.dp))

        FolderActionButton(
            text = stringResource(R.string.edit_collection_action_sync),
            onClick = onSyncClick
        ) {
            Icon(painterResource(R.drawable.icon_sync), null, Modifier.size(18.dp))
        }

        if (excludedCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))

            FolderActionButton(
                text = stringResource(R.string.edit_collection_action_restore_removed, excludedCount),
                onClick = onRestoreRemoved
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FolderActionButton(
    text: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SmallCornerRadius),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NothingWhite),
        border = BorderStroke(1.dp, NothingWhite.copy(alpha = 0.3f))
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(text, style = NothingType.dialogButton)
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

/** Stand-in override for the previews; the row's shape is what they exercise, not the image. */
private val PREVIEW_DEFAULT_WALLPAPER = WallpaperImage(id = 1, collectionId = 1, uri = Uri.EMPTY)


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
                imageCount = 124,
                isProcessing = false,
                excludedCount = 0,
                nameText = previewFolderCollection.name,
                onNameChange = {},
                onNameCommit = {},
                onCropRuleSelected = {},
                onRotationPolicySelected = {},
                globalRotationPolicy = RotationPolicy.PerLock,
                defaultWallpaper = PREVIEW_DEFAULT_WALLPAPER,
                onClearDefault = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onRestoreRemoved = {}
            )
        }
    }
}

@Preview(
    name = "Folder Collection (removed images)",
    showBackground = true,
    backgroundColor = 0xFF1A1A2E,
    device = "spec:width=411dp,height=891dp,dpi=420"
)
@Composable
private fun PreviewEditCollectionCardFolderWithRestore() {
    WallpaperChangerTheme {
        DialogScrim {
            EditCollectionCardContent(
                collection = previewFolderCollection,
                imageCount = 124,
                isProcessing = false,
                excludedCount = 4,
                nameText = previewFolderCollection.name,
                onNameChange = {},
                onNameCommit = {},
                onCropRuleSelected = {},
                onRotationPolicySelected = {},
                globalRotationPolicy = RotationPolicy.PerLock,
                defaultWallpaper = null,
                onClearDefault = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onRestoreRemoved = {}
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
                imageCount = 12,
                isProcessing = false,
                excludedCount = 0,
                nameText = previewManualCollection.name,
                onNameChange = {},
                onNameCommit = {},
                onCropRuleSelected = {},
                onRotationPolicySelected = {},
                globalRotationPolicy = RotationPolicy.PerLock,
                defaultWallpaper = null,
                onClearDefault = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onRestoreRemoved = {}
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
                imageCount = 124,
                isProcessing = false,
                excludedCount = 0,
                nameText = previewFolderCollection.name,
                onNameChange = {},
                onNameCommit = {},
                onCropRuleSelected = {},
                onRotationPolicySelected = {},
                globalRotationPolicy = RotationPolicy.PerLock,
                defaultWallpaper = null,
                onClearDefault = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onRestoreRemoved = {}
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
                imageCount = 124,
                isProcessing = true,
                excludedCount = 0,
                nameText = previewFolderCollection.name,
                onNameChange = {},
                onNameCommit = {},
                onCropRuleSelected = {},
                onRotationPolicySelected = {},
                globalRotationPolicy = RotationPolicy.PerLock,
                defaultWallpaper = null,
                onClearDefault = {},
                showDeleteConfirmation = false,
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteCancel = {},
                onDismiss = {},
                onSyncClick = {},
                onRestoreRemoved = {}
            )
        }
    }
}
