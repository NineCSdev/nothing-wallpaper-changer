package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency

/**
 * ViewModel-owned intents of the Collection List screen, implemented by
 * [CollectionViewModel]. Navigation, launcher, and service-lifecycle composites
 * (collection click, folder/photos pickers, create/delete with their service
 * side effects) are deliberately not part of this contract; they stay as plain
 * parameters on [CollectionListScreen] / [CollectionListRoute].
 * TODO tests: see vault note tests/screen-actions-routes.md
 */
interface CollectionListActions {
    fun setSortOrder(order: CollectionSortOrder)
    fun toggleCreateModal(show: Boolean)
    fun closeEditModal()
    fun updateEditingCollection(newName: String, cropRule: CropRule, rotationFrequency: RotationFrequency)
    fun setActiveEditingCollection()
    fun syncEditingCollection()
    fun clearImportSummary()
}
