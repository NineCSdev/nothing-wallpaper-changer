package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import com.ninecsdev.wallpaperchanger.data.source.SourceDetails
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components.FilterDimension

// Navigation and launcher-backed callbacks (back, add, edit, relink picker) stay out of this contract.
// TODO tests: see vault note tests/screen-actions-routes.md
interface CollectionImageActions {
    fun openPreview(wallpaper: WallpaperImage)
    fun closePreview()
    suspend fun sourceDetails(wallpaper: WallpaperImage): SourceDetails

    fun toggleFilter()
    fun toggleFilterChip(dimension: FilterDimension, answer: Boolean)
    fun clearFilter()

    fun enterSelectionMode(id: Long)
    fun toggleSelection(id: Long)
    fun exitSelectionMode()
    fun deleteSelectedWallpapers()

    fun requestTransfer(mode: TransferMode)
    fun transferToCollection(target: TransferTarget)
    fun requestNewTransferTarget()
    fun transferToNewCollection(name: String)
    fun cancelTransfer()

    fun toggleFavoriteSelected()
    fun toggleFavorite(wallpaper: WallpaperImage)

    fun toggleCollectionDefaultSelected()
    fun toggleCollectionDefault(wallpaper: WallpaperImage)

    fun requestRelink(wallpaper: WallpaperImage)
    fun cancelRelink()
}
