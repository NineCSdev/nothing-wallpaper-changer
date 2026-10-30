package com.ninecsdev.wallpaperchanger.ui.backupscreen

interface BackupActions {
    fun setPortable(portable: Boolean)
    fun cancelExport()
    fun acknowledgeExport()
}