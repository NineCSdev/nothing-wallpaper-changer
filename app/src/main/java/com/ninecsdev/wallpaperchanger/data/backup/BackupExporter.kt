package com.ninecsdev.wallpaperchanger.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import com.ninecsdev.wallpaperchanger.BuildConfig
import com.ninecsdev.wallpaperchanger.data.InstallRows
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.DB_SCHEMA_VERSION
import com.ninecsdev.wallpaperchanger.data.local.DeviceDefaults
import com.ninecsdev.wallpaperchanger.data.source.SourceFingerprint
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.logic.mapConcurrently
import com.ninecsdev.wallpaperchanger.di.ApplicationScope
import com.ninecsdev.wallpaperchanger.model.WallpaperFile
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where a backup export has got to */
sealed interface BackupExportState {
    data object Idle : BackupExportState
    /** [done] of [total] images written */
    data class Running(val done: Int, val total: Int) : BackupExportState
    data class Finished(val summary: BackupExportSummary) : BackupExportState
    data class Failed(val detail: String) : BackupExportState
    /**
     * An export from a previous run of the app never finished, and the archive it left behind cannot
     * be trusted. [archiveName] is that file. Null when the name could no longer be read.
     */
    data class Interrupted(val archiveName: String?) : BackupExportState
}

data class BackupExportSummary(
    val unreadable: Int,
    val bytes: Long
)

/**
 * Writes the whole install to a `.nwcbak` archive at a location the user picked.
 *
 * Runs on [ApplicationScope] to continue even if the user navigates away. [state] is how a screen
 * watches one that is already running.
 */
@Singleton
class BackupExporter @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: WallpaperRepository,
    private val appDataStore: AppDataStore,
    private val wallpaperSources: WallpaperSources,
    private val exportRecord: BackupRecordStore,
    @param:ApplicationScope private val applicationScope: CoroutineScope
) {
    private companion object {
        const val TAG = "BackupExporter"
    }

    private val _state = MutableStateFlow<BackupExportState>(BackupExportState.Idle)
    val state: StateFlow<BackupExportState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        // If an export was left unfinished tell the user that backup is unusable
        applicationScope.launch {
            val abandoned = exportRecord.inFlightTarget()
            // An active job would have moved the state off Idle, so that check covers both
            if (abandoned != null && _state.value is BackupExportState.Idle) {
                _state.value = BackupExportState.Interrupted(exportRecord.inFlightName())
            }
        }
    }

    /** Starts writing an archive to [target], a document the caller has just created. A second call is ignored */
    fun start(target: Uri, mode: BackupMode) {
        if (job?.isActive == true) return

        _state.value = BackupExportState.Running(done = 0, total = 0)
        job = applicationScope.launch {
            try {
                // Named now, while the picker's grant is still alive: it dies with this process, and
                // the only run that reads this back is the one after the process did.
                val name = wallpaperSources.fingerprint(target.toString(), SourceType.FOLDER_DOC).displayName
                exportRecord.setInFlightTarget(target, name)
                val summary = write(target, mode)
                exportRecord.setInFlightTarget(null)
                _state.value = BackupExportState.Finished(summary)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { discardPartial(target) }
                _state.value = BackupExportState.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Backup export failed", e)
                discardPartial(target)
                _state.value = BackupExportState.Failed(e.message ?: e::class.java.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Returns the readout to [BackupExportState.Idle] once a screen has shown the outcome. */
    fun acknowledge() {
        if (_state.value is BackupExportState.Running) return
        // Only once it has been shown we return to Idle
        if (_state.value is BackupExportState.Interrupted) {
            applicationScope.launch { exportRecord.setInFlightTarget(null) }
        }
        _state.value = BackupExportState.Idle
    }

    private suspend fun write(target: Uri, mode: BackupMode): BackupExportSummary {
        val rows = repository.readInstallRows()
        val fingerprints = fingerprintAll(rows.files)

        // Entry name for every file whose bytes travel, positional so the manifest can name it; null for the rest
        val payloads = rows.files.mapIndexed { index, file ->
            if (!bundles(file, mode)) null
            else BackupFormat.imageEntry(index, extensionOf(fingerprints[index].displayName ?: file.uriString))
        }
        val bundling = payloads.withIndex().mapNotNull { (index, entry) -> entry?.let { index to it } }

        // Built before the output is opened: it reads the sources, and holding the user's chosen
        // document open across that would be for nothing.
        val encodedManifest = BackupManifestCodec.encode(buildManifest(rows, mode, fingerprints, payloads))

        var done = 0
        var bytes = 0L
        val unreadable = mutableListOf<String>()
        _state.value = BackupExportState.Running(done = 0, total = bundling.size)

        val output = appContext.contentResolver.openOutputStream(target) ?: throw IOException("Could not open the chosen backup file for writing")

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_ENTRY))
            zip.write(encodedManifest.toByteArray())
            zip.closeEntry()

            // Images are already WebP or JPEG so they are already pretty compressed, wouldn't win much
            zip.setLevel(Deflater.NO_COMPRESSION)
            bundling.forEach { (index, entry) ->
                // The copy itself is blocking, so nothing else in this loop would ever notice a cancel
                currentCoroutineContext().ensureActive()
                val written = copyInto(zip, entry, rows.files[index].uriString)
                if (written == null) unreadable += entry else bytes += written
                done++
                _state.value = BackupExportState.Running(done = done, total = bundling.size)
            }

            // The closing record, the chance to disown a payload
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(BackupFormat.COMPLETE_ENTRY))
            zip.write(BackupCompletionCodec.encode(BackupCompletion(unreadable)).toByteArray())
            zip.closeEntry()
        }

        return BackupExportSummary(unreadable = unreadable.size, bytes = bytes)
    }

    private suspend fun fingerprintAll(files: List<WallpaperFile>): List<SourceFingerprint> =
        files.mapConcurrently(FINGERPRINT_CONCURRENCY) {
            wallpaperSources.fingerprint(it.uriString, it.sourceType)
        }

    /** Whether this file's bytes travel. */
    private fun bundles(file: WallpaperFile, mode: BackupMode): Boolean =
        file.isAvailable && (file.sourceType == SourceType.INTERNALIZED || mode == BackupMode.PORTABLE)

    /**
     * Streams [uri] into the archive as [entryName]. Returns the bytes written, or null when the
     * source could not be read to the end.
     */
    private fun copyInto(zip: ZipOutputStream, entryName: String, uri: String): Long? {
        zip.putNextEntry(ZipEntry(entryName))
        val written = try {
            appContext.contentResolver.openInputStream(uri.toUri())?.use { it.copyTo(zip, COPY_BUFFER_BYTES) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not bundle $uri; it will restore unavailable", e)
            null
        }
        zip.closeEntry()
        return written
    }

    /** The source's extension, so a bundled JPEG is not misfiled as a WebP. */
    private fun extensionOf(nameOrUri: String): String =
        nameOrUri.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            ?: "img"

    private suspend fun buildManifest(
        rows: InstallRows,
        mode: BackupMode,
        fingerprints: List<SourceFingerprint>,
        payloads: List<String?>
    ): BackupManifest {
        val collectionIndex = rows.collections.withIndex().associate { (index, it) -> it.id to index }
        val fileIndex = rows.files.withIndex().associate { (index, it) -> it.id to index }
        val membershipIndex = rows.memberships.withIndex().associate { (index, it) -> it.id to index }
        // Read up front, and concurrently: a tombstone's name is the only thing that can re-key it
        // onto a folder picked at a new location, and there can be hundreds of them.
        val exclusionNames = rows.exclusions.mapConcurrently(FINGERPRINT_CONCURRENCY) {
            wallpaperSources.fingerprint(it.uriString, SourceType.FOLDER_DOC).displayName
        }

        return BackupManifest(
            formatVersion = BackupFormat.CURRENT_VERSION,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            roomSchemaVersion = DB_SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            mode = mode,
            collections = rows.collections.map { collection ->
                BackupCollection(
                    name = collection.name,
                    type = collection.type.name,
                    rootUri = collection.rootUriString,
                    defaultCropRule = collection.defaultCropRule.name,
                    rotationPolicy = collection.rotationPolicy.encode(),
                    isActive = collection.isActive,
                    isPinned = collection.isPinned,
                    appRole = collection.appRole?.name,
                    createdAt = collection.createdAt,
                    lastUsedAt = collection.lastUsedAt,
                    defaultMembership = collection.defaultWallpaperId?.let { membershipIndex[it] }
                )
            },
            files = rows.files.mapIndexed { index, file ->
                BackupFile(
                    uri = file.uriString,
                    sourceType = file.sourceType.name,
                    available = file.isAvailable,
                    addedAt = file.addedAt,
                    displayName = fingerprints[index].displayName,
                    sizeBytes = fingerprints[index].sizeBytes,
                    modifiedAt = fingerprints[index].modifiedAt,
                    payload = payloads[index]
                )
            },
            memberships = rows.memberships.mapNotNull { membership ->
                val collection = collectionIndex[membership.collectionId] ?: return@mapNotNull null
                val file = fileIndex[membership.fileId] ?: return@mapNotNull null
                BackupMembership(
                    collection = collection,
                    file = file,
                    editZoom = membership.editParams?.zoom,
                    editOffsetX = membership.editParams?.offsetX,
                    editOffsetY = membership.editParams?.offsetY,
                    manuallyAdded = membership.isManuallyAdded,
                    isDefault = membership.isDefault,
                    addedAt = membership.addedAt
                )
            },
            exclusions = rows.exclusions.mapIndexedNotNull { index, exclusion ->
                val collection = collectionIndex[exclusion.collectionId] ?: return@mapIndexedNotNull null
                BackupExclusion(
                    collection = collection,
                    uri = exclusion.uriString,
                    displayName = exclusionNames[index],
                    editZoom = exclusion.editParams?.zoom,
                    editOffsetX = exclusion.editParams?.offsetX,
                    editOffsetY = exclusion.editParams?.offsetY,
                    excludedAt = exclusion.excludedAt
                )
            },
            settings = readSettings()
        )
    }

    private suspend fun readSettings() = BackupSettings(
        revertToDefault = appDataStore.shouldRevertToDefault(),
        startOnBoot = appDataStore.shouldStartOnBoot(),
        screenOffDelayMs = appDataStore.getScreenOffDelay(),
        screenOffDelayDeviceDefault = DeviceDefaults.forThisDevice(),
        compressionQualityHigh = appDataStore.getCompressionQualityHigh(),
        compressionQualityLow = appDataStore.getCompressionQualityLow(),
        batterySaverPolicy = appDataStore.getBatterySaverPolicy().name,
        wallpaperZoomFix = appDataStore.getWallpaperZoomFix().storedValue,
        wallpaperDestination = appDataStore.getWallpaperDestination().name,
        wallpaperMode = appDataStore.getWallpaperMode().name,
        keepLocalCopies = appDataStore.getKeepLocalCopies(),
        rotationPolicy = appDataStore.getRotationPolicy().encode(),
        skipOnDnd = appDataStore.getSkipOnDnd()
    )

    /** Undoes a failed export: the half-written file goes, and so does the record that it existed. */
    private suspend fun discardPartial(target: Uri) {
        try {
            DocumentsContract.deleteDocument(appContext.contentResolver, target)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the partial archive at $target", e)
        }
        exportRecord.setInFlightTarget(null)
    }
}
