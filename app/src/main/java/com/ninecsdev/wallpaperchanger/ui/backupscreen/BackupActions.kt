package com.ninecsdev.wallpaperchanger.ui.backupscreen

/** ViewModel-owned intents of the export half, implemented by [BackupViewModel]. */
interface BackupActions {
    fun setPortable(portable: Boolean)
    fun cancelExport()
    fun acknowledgeExport()
}