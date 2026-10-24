package com.ninecsdev.wallpaperchanger.ui.mainscreen.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.resolveDisplayName
import com.ninecsdev.wallpaperchanger.ui.components.overlay.CollectionPickerItem
import com.ninecsdev.wallpaperchanger.ui.components.overlay.CollectionPickerSheet
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.mainscreen.CollectionPickerSheetState

/**
 * The main screen's active-collection picker: wires [com.ninecsdev.wallpaperchanger.ui.mainscreen.CollectionPickerSheetState] into the shared
 * [CollectionPickerSheet]. The currently active collection is marked, tapping any tile picks it
 * immediately, and the leading "new collection" tile hands off to [onCreateCollection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActiveCollectionPickerSheet(
    state: CollectionPickerSheetState,
    activeCollectionId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    onCreateCollection: () -> Unit
) {
    val context = LocalContext.current

    CollectionPickerSheet(
        title = stringResource(R.string.label_select_collection),
        items = state.collections.map { collection ->
            CollectionPickerItem(
                id = collection.id,
                name = collection.resolveDisplayName(context),
                previewState = state.previewStates[collection.id] ?: CollectionPreviewState(),
                isActive = collection.id == activeCollectionId,
                isPinned = collection.isPinned
            )
        },
        onItemClick = { onPick(it.id) },
        onDismiss = onDismiss,
        onCreateNew = onCreateCollection
    )
}
