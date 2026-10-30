package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting

/** ViewModel-owned intents only; navigation, launcher and service-lifecycle composites stay as parameters on the screen and route. */
// TODO tests: see vault note tests/screen-actions-routes.md
interface CollectionListActions {
    fun setSortOrder(order: CollectionSortOrder)
    fun toggleCreateModal(show: Boolean)
    fun closeEditModal()
    fun renameEditingCollection(newName: String)
    fun setEditingCropRule(rule: CropRule)
    fun setEditingRotationPolicy(setting: CollectionRotationSetting)
    fun clearEditingCollectionDefault()
    fun syncEditingCollection()
    fun restoreRemovedImages()

    fun togglePinned(collectionId: Long)
    fun setActiveCollection(collectionId: Long)
}
