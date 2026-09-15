package com.ninecsdev.wallpaperchanger.ui.backupscreen

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.ninecsdev.wallpaperchanger.data.backup.BackupExportState
import com.ninecsdev.wallpaperchanger.data.backup.BackupExporter
import com.ninecsdev.wallpaperchanger.data.backup.BackupMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject



/** The export half of the Backup screen. */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupExporter: BackupExporter
) : ViewModel(), BackupActions {

    // Which kind of archive the next export writes. Not persisted as it is a per-backup decision
    private val _portable = MutableStateFlow(false)
    val portable: StateFlow<Boolean> = _portable.asStateFlow()

    val exportState: StateFlow<BackupExportState> = backupExporter.state

    override fun setPortable(portable: Boolean) {
        _portable.value = portable
    }

    override fun cancelExport() = backupExporter.cancel()

    override fun acknowledgeExport() = backupExporter.acknowledge()

    /** Starts writing the archive the user has just chosen a place for. */
    fun export(target: Uri) {
        backupExporter.start(
            target = target,
            mode = if (_portable.value) BackupMode.PORTABLE else BackupMode.DEVICE
        )
    }
}
