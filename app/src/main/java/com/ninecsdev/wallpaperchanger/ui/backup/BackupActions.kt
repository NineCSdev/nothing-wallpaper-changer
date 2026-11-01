package com.ninecsdev.wallpaperchanger.ui.backup

interface BackupActions {
    fun setPortable(portable: Boolean)
    fun cancelExport()
    fun acknowledgeExport()
}