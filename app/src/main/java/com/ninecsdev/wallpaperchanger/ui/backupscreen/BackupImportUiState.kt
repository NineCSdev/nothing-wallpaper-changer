package com.ninecsdev.wallpaperchanger.ui.backupscreen

import com.ninecsdev.wallpaperchanger.data.backup.BackupImportFailure
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportPlan
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportProgress
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportSummary
import com.ninecsdev.wallpaperchanger.data.backup.FolderPrompt

/**
 * Where the restore wizard is.
 *
 * Everything up to and including [Folder] is decision-making that a back-press out costs nothing.
 * Only [Working] is past that line.
 */
sealed interface BackupImportStep {
    /** Reading the archive's manifest. */
    data object Reading : BackupImportStep
    /** What the archive holds, and what it is about to replace. */
    data class Review(val plan: BackupImportPlan) : BackupImportStep
    /** One folder collection awaiting a decision. [index] and [total] are 1-based for display. */
    data class Folder(val prompt: FolderPrompt, val index: Int, val total: Int) : BackupImportStep
    data class Working(val progress: BackupImportProgress) : BackupImportStep
    data class Done(val summary: BackupImportSummary) : BackupImportStep
    /** Refused or failed. Leaves the existing install untouched. */
    data class Failed(val failure: BackupImportFailure) : BackupImportStep
}

/**
 * Snapshot of the restore wizard.
 *
 * A null [step] is no restore under way.
 *
 * [needsMediaAccess] is raised once, between the review and the folder prompts: the references in a
 * device archive cannot be verified without `READ_MEDIA_IMAGES`, and unverified references restore
 * unavailable.
 */
data class BackupImportUiState(
    val step: BackupImportStep? = null,
    val needsMediaAccess: Boolean = false
)
