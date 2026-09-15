package com.ninecsdev.wallpaperchanger.ui.backupscreen

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportFailure
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportPlan
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportProgress
import com.ninecsdev.wallpaperchanger.data.backup.BackupRecordStore
import com.ninecsdev.wallpaperchanger.data.backup.BackupImporter
import com.ninecsdev.wallpaperchanger.data.backup.FolderDecision
import com.ninecsdev.wallpaperchanger.data.backup.ImportRefused
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the restore wizard: which step is showing, and what the user has decided so far.
 *
 * **The decisions survive the SAF round-trips.** The archive's uri and every folder answer live
 * in the [SavedStateHandle]. The commit runs on [ApplicationScope], because a restore that has
 * started destroying the old install must not be abandoned halfway by the user leaving the screen.
 */
@HiltViewModel
class BackupImportViewModel @Inject constructor(
    private val importer: BackupImporter,
    private val importRecord: BackupRecordStore,
    private val wallpaperSources: WallpaperSources,
    private val savedState: SavedStateHandle,
    @param:ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel(), BackupImportActions {

    private companion object {
        const val TAG = "BackupImportViewModel"
        const val KEY_ARCHIVE = "backup_archive_uri"
        const val KEY_DECISIONS = "backup_folder_decisions"

        /** One decision, as `<collection index>|<answer>`. */
        const val DECISION_SEPARATOR = '|'
        /** The answer that means "skip". */
        const val SKIPPED = ""
    }

    // Seeded from saved state rather than empty: a wizard that was mid-restore before a recreation
    // must not blink back to the Backup screen's own body while the manifest is re-read
    private val _uiState = MutableStateFlow(
        BackupImportUiState(step = savedState.get<String>(KEY_ARCHIVE)?.let { BackupImportStep.Reading })
    )
    val uiState: StateFlow<BackupImportUiState> = _uiState.asStateFlow()

    /** Held only for the life of this wizard; re-read from the archive after a recreation */
    private var plan: BackupImportPlan? = null

    init {
        savedState.get<String>(KEY_ARCHIVE)?.let { archive ->
            viewModelScope.launch {
                if (importRecord.isCommitting()) {
                    importRecord.setCommitting(false)
                    fail(ImportRefused(BackupImportFailure.Interrupted))
                } else {
                    readPlan(archive, resuming = true)
                }
            }
        }
    }

    override fun onArchivePicked(uri: String) {
        // "Choose a different file" swaps the archive in place, so we need to ask the decisions again
        val replaced = savedState.get<String>(KEY_ARCHIVE)?.takeIf { it != uri }
        savedState[KEY_ARCHIVE] = uri
        clearDecisions()
        viewModelScope.launch {
            replaced?.let {
                wallpaperSources.unpinGrant(it)
                runCatching { wallpaperSources.releasePersistedGrant(it) }
                    .onFailure { error -> Log.w(TAG, "Could not release the replaced archive's grant", error) }
            }
            // Persisting the grant is what lets the wizard survive the activity recreation a SAF
            // picker can cause. A provider that will not offer one costs us that, not the restore.
            runCatching { wallpaperSources.takePersistedGrant(uri) }
                .onFailure { Log.w(TAG, "No persistable grant for the chosen archive", it) }
            wallpaperSources.pinGrant(uri)
            readPlan(uri, resuming = false)
        }
    }

    private suspend fun readPlan(archive: String, resuming: Boolean) {
        _uiState.update { it.copy(step = BackupImportStep.Reading) }
        importer.readPlan(archive.toUri())
            .onSuccess { parsed ->
                plan = parsed
                // A recreation lands back wherever the answers so far put us, not at the start
                if (resuming && decisions().isNotEmpty()) advanceFromFolders(parsed) else review(parsed)
            }
            .onFailure { error -> fail(error) }
    }

    private fun review(parsed: BackupImportPlan) {
        _uiState.update { it.copy(step = BackupImportStep.Review(parsed)) }
    }

    override fun onReviewConfirmed() {
        _uiState.update { it.copy(needsMediaAccess = true) }
    }

    override fun onMediaAccessSettled() {
        _uiState.update { it.copy(needsMediaAccess = false) }
        plan?.let { advanceFromFolders(it) }
    }

    override fun onFolderPicked(treeUri: String) = decide(treeUri)

    override fun onFolderSkipped() = decide(SKIPPED)

    private fun decide(answer: String) {
        val current = (_uiState.value.step as? BackupImportStep.Folder) ?: return
        val recorded = savedState.get<ArrayList<String>>(KEY_DECISIONS) ?: ArrayList()

        // One question per archived folder, not per collection
        val sharing = plan?.folders.orEmpty()
            .filter { it.folderKey == current.prompt.folderKey }
            .map { it.collectionIndex }
        savedState[KEY_DECISIONS] = ArrayList(recorded + sharing.map { "$it$DECISION_SEPARATOR$answer" })
        plan?.let { advanceFromFolders(it) }
    }

    /** Shows the next undecided folder, or starts the restore once every one has an answer. */
    private fun advanceFromFolders(parsed: BackupImportPlan) {
        val decisions = decisions()
        val next = parsed.folders.indexOfFirst { it.collectionIndex !in decisions }
        if (next >= 0) {
            val answered = parsed.folders.filter { it.collectionIndex in decisions }.mapTo(mutableSetOf()) { it.folderKey }
            _uiState.update {
                it.copy(
                    step = BackupImportStep.Folder(
                        prompt = parsed.folders[next],
                        index = answered.size + 1,
                        total = parsed.folders.distinctBy { folder -> folder.folderKey }.size
                    )
                )
            }
        } else {
            begin(parsed, decisions)
        }
    }

    private fun begin(parsed: BackupImportPlan, decisions: Map<Int, FolderDecision>) {
        val archive = savedState.get<String>(KEY_ARCHIVE)?.toUri() ?: return
        _uiState.update { it.copy(step = BackupImportStep.Working(BackupImportProgress(0, 0))) }

        applicationScope.launch {
            val resolved = importer.resolve(archive, parsed.manifest, decisions)
                .getOrElse { error -> return@launch fail(error) }

            importer.commit(resolved) { progress ->
                _uiState.update { it.copy(step = BackupImportStep.Working(progress)) }
            }
                .onSuccess { summary ->
                    // Saved state outlives the process, so a committed restore that is still
                    // resumable is one the next launch replays in full, unannounced
                    releaseArchive()
                    _uiState.update { it.copy(step = BackupImportStep.Done(summary)) }
                }
                .onFailure { error -> fail(error) }
        }
    }

    private fun fail(error: Throwable) {
        Log.w(TAG, "Restore stopped", error)
        val failure = (error as? ImportRefused)?.failure ?: BackupImportFailure.Unexpected(error.message ?: error::class.java.simpleName)
        _uiState.update { it.copy(step = BackupImportStep.Failed(failure), needsMediaAccess = false) }
    }

    /** Closes the wizard and hands the screen back to the Backup screen's own body. */
    override fun onDismissed() {
        if (_uiState.value.step is BackupImportStep.Working) return
        _uiState.value = BackupImportUiState()
        plan = null
        releaseArchive()
    }

    /** Forgets the archive and hands its grant back.*/
    private fun releaseArchive() {
        val archive = savedState.get<String>(KEY_ARCHIVE) ?: return
        savedState.remove<String>(KEY_ARCHIVE)
        clearDecisions()
        applicationScope.launch {
            wallpaperSources.unpinGrant(archive)
            wallpaperSources.releasePersistedGrant(archive)
        }
    }

    private fun decisions(): Map<Int, FolderDecision> =
        savedState.get<ArrayList<String>>(KEY_DECISIONS).orEmpty().mapNotNull { recorded ->
            val index = recorded.substringBefore(DECISION_SEPARATOR).toIntOrNull() ?: return@mapNotNull null
            // A tree uri can hold the separator itself, so the answer is everything after the first.
            val answer = recorded.substringAfter(DECISION_SEPARATOR)
            index to if (answer == SKIPPED) FolderDecision.Skipped else FolderDecision.Picked(answer)
        }.toMap()

    private fun clearDecisions() {
        savedState.remove<ArrayList<String>>(KEY_DECISIONS)
    }
}
