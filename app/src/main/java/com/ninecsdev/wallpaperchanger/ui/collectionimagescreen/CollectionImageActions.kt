package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * ViewModel-owned intents of the Collection Image screen, implemented by
 * [CollectionImageViewModel]. Navigation and launcher-backed callbacks (back, add
 * wallpapers, edit, relink picker) are deliberately not part of this contract; they
 * stay as plain parameters on [CollectionImageScreen] / [CollectionImageRoute].
 */
// TODO tests: see vault note tests/screen-actions-routes.md
interface CollectionImageActions {
    // Full-screen preview
    fun openPreview(wallpaper: WallpaperImage)
    fun closePreview()

    // Selection mode
    fun enterSelectionMode(id: Long)
    fun toggleSelection(id: Long)
    fun exitSelectionMode()
    fun deleteSelectedWallpapers()

    // Copy/move to another collection
    fun requestTransfer(mode: TransferMode)
    fun transferToCollection(target: TransferTarget)
    fun cancelTransfer()
    fun clearTransferSummary()

    // Favourites
    fun toggleFavoriteSelected()
    fun toggleFavorite(wallpaper: WallpaperImage)

    // Re-link unavailable image
    fun requestRelink(wallpaper: WallpaperImage)
    fun cancelRelink()
    fun clearRelinkFailed()

    // One-shot summaries
    fun clearImportSummary()
}
