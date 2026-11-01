package com.ninecsdev.wallpaperchanger.ui.backup

interface BackupImportActions {
    fun onArchivePicked(uri: String)
    fun onReviewConfirmed()
    fun onMediaAccessSettled()
    fun onFolderPicked(treeUri: String)
    fun onFolderSkipped()
    fun onDismissed()
}