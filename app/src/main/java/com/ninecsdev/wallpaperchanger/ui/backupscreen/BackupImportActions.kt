package com.ninecsdev.wallpaperchanger.ui.backupscreen

/** ViewModel-owned intents of the restore wizard, implemented by [BackupImportViewModel]. */
interface BackupImportActions {
    fun onArchivePicked(uri: String)
    fun onReviewConfirmed()
    fun onMediaAccessSettled()
    fun onFolderPicked(treeUri: String)
    fun onFolderSkipped()
    fun onDismissed()
}