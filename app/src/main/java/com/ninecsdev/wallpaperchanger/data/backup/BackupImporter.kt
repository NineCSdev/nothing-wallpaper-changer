package com.ninecsdev.wallpaperchanger.data.backup

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.InstallSnapshot
import com.ninecsdev.wallpaperchanger.data.SnapshotExclusion
import com.ninecsdev.wallpaperchanger.data.SnapshotMembership
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.DeviceDefaults
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.data.source.FolderScanner
import com.ninecsdev.wallpaperchanger.data.source.SourceFingerprint
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.data.source.treeUriOf
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.logic.mapConcurrently
import com.ninecsdev.wallpaperchanger.logic.replaceAtomically
import com.ninecsdev.wallpaperchanger.logic.ServiceLifecycle
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.FolderExclusion
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.ServiceIntent
import com.ninecsdev.wallpaperchanger.model.Wallpaper
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperFile
import com.ninecsdev.wallpaperchanger.model.enums.AppCollectionRole
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupImportPlan(
    val manifest: BackupManifest,
    val archiveName: String?,
    val collections: Int,
    val images: Int,
    val folders: List<FolderPrompt>
)

/**
 * [bundledImages] are members whose bytes are inside the archive, so skipping the collection loses them.
 * [folderKey] lets two collections with the same rootUri share one [FolderDecision].
 */
data class FolderPrompt(
    val collectionIndex: Int,
    val name: String,
    val rootUri: String?,
    val images: Int,
    val bundledImages: Int,
    val folderKey: String
)

sealed interface FolderDecision {
    data class Picked(val treeUri: String) : FolderDecision
    data object Skipped : FolderDecision
}

/** All but [Interrupted] are refusals taken before anything of the user's was touched. */
sealed interface BackupImportFailure {
    data object NotAnArchive : BackupImportFailure
    data class NewerFormat(val formatVersion: Int) : BackupImportFailure
    data class Malformed(val detail: String) : BackupImportFailure
    /** The archive stops short of its completion marker. */
    data object Incomplete : BackupImportFailure
    /** The restore had already started replacing, so the old install may have been modified. */
    data object Interrupted : BackupImportFailure
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : BackupImportFailure
    data class Unexpected(val detail: String) : BackupImportFailure
}

data class BackupImportSummary(
    val collections: Int,
    val images: Int,
    val unavailable: Int,
    val skippedCollections: Int
)

data class BackupImportProgress(val done: Int, val total: Int)

/** The archive entry a file's bytes come from and the filename they get under `internal_wallpapers`. */
internal data class StagedPayload(val entryName: String, val stagedName: String)

internal data class ResolvedFile(
    val uri: String,
    val sourceType: SourceType,
    val available: Boolean,
    val verified: Boolean,
    val addedAt: Long,
    val payload: StagedPayload? = null
)

class ResolvedImport internal constructor(
    internal val archive: Uri,
    internal val manifest: BackupManifest,
    internal val files: List<ResolvedFile>,
    internal val exclusionUris: List<String>,
    internal val droppedCollections: Set<Int>,
    /** Collection index to the tree the user picked, which is not always the one the archive recorded. */
    internal val pickedRoots: Map<Int, String>
)

/**
 * Every decision is taken before anything is destroyed; only then does [commit] stop the service,
 * stage bytes and swap the rows in one transaction. Best effort, nothing dropped: an unverifiable
 * reference, a declined permission and a gone image all land unavailable.
 */
@Singleton
class BackupImporter @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: WallpaperRepository,
    private val appDataStore: AppDataStore,
    private val wallpaperRecordStore: WallpaperRecordStore,
    private val wallpaperSources: WallpaperSources,
    private val folderScanner: FolderScanner,
    private val serviceLifecycle: ServiceLifecycle,
    private val importRecord: BackupRecordStore
) {
    private companion object {
        const val TAG = "BackupImporter"
        const val STAGING_FOLDER = "import_staging"
        const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024
    }

    suspend fun readPlan(archive: Uri): Result<BackupImportPlan> = withContext(Dispatchers.IO) {
        val raw = try {
            readManifestEntry(archive)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read $archive as a backup archive", e)
            null
        } ?: return@withContext Result.failure(ImportRefused(BackupImportFailure.NotAnArchive))

        BackupManifestCodec.decode(raw).map { manifest ->
            planOf(manifest, archiveFingerprint(archive).displayName)
        }
    }

    private suspend fun archiveFingerprint(archive: Uri): SourceFingerprint =
        wallpaperSources.fingerprint(archive.toString(), SourceType.FOLDER_DOC)

    private fun readManifestEntry(archive: Uri): String? {
        val input = appContext.contentResolver.openInputStream(archive) ?: return null
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == BackupFormat.MANIFEST_ENTRY) return readCappedManifest(zip)
                zip.closeEntry()
            }
        }
    }

    /** The manifest entry's bytes, or null when it inflates past [BackupFormat.MANIFEST_MAX_BYTES]. */
    private fun readCappedManifest(zip: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val read = zip.read(buffer)
            if (read == -1) break
            if (out.size() + read > BackupFormat.MANIFEST_MAX_BYTES) {
                Log.w(TAG, "Manifest exceeds ${BackupFormat.MANIFEST_MAX_BYTES} bytes; refusing the archive")
                return null
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray().decodeToString()
    }

    private fun planOf(manifest: BackupManifest, archiveName: String?): BackupImportPlan {
        val bundledFiles = manifest.files.withIndex()
            .filter { (_, file) -> file.payload != null }
            .map { (index, _) -> index }
            .toSet()

        val listed = manifest.listedCollections()

        val folders = manifest.collections.withIndex()
            .filter { (_, collection) ->
                collection.type == CollectionType.FOLDER.name && collection.appRole == null
            }
            .map { (index, collection) ->
                val members = manifest.memberships.filter { it.collection == index && !it.isDefault }
                FolderPrompt(
                    collectionIndex = index,
                    name = collection.name,
                    rootUri = collection.rootUri,
                    images = members.size,
                    bundledImages = members.count { it.file in bundledFiles },
                    // A folder collection with no recorded root shares its answer with nothing
                    folderKey = collection.rootUri ?: "collection:$index"
                )
            }

        return BackupImportPlan(
            manifest = manifest,
            archiveName = archiveName,
            collections = listed.size,
            images = manifest.countImages(listed),
            folders = folders
        )
    }

    // TODO tests: see vault note tests/Backup Archive Tests.md (folder and exclusion rebasing)
    suspend fun resolve(
        archive: Uri,
        manifest: BackupManifest,
        decisions: Map<Int, FolderDecision>
    ): Result<ResolvedImport> = withContext(Dispatchers.IO) {
        try {
            val dropped = decisions.filterValues { it is FolderDecision.Skipped }.keys

            // Resolved by tree so two collections sharing a root are scanned once; concurrent because a restore can carry several
            val trees = decisions.mapNotNull { (collectionIndex, decision) ->
                val archivedTree = manifest.collections.getOrNull(collectionIndex)?.rootUri
                if (decision !is FolderDecision.Picked || archivedTree == null) null
                else archivedTree to decision.treeUri
            }.distinctBy { (archivedTree, _) -> archivedTree }
                .mapConcurrently(FINGERPRINT_CONCURRENCY) { (archivedTree, pickedTree) ->
                    archivedTree to rebaseTree(archivedTree, pickedTree)
                }.toMap()

            // A file only a skipped collection referenced would just get swept
            val reachable = manifest.reachableFiles(dropped)
            // Asked once; without it every MediaStore reference is unverifiable anyway
            val mediaAccess = wallpaperSources.hasMediaAccess()
            val files = manifest.files.withIndex().mapConcurrently(FINGERPRINT_CONCURRENCY) { (index, file) ->
                if (index in reachable) resolveFile(file, manifest.mode, trees, mediaAccess) else unreferenced(file)
            }
            val exclusionUris = manifest.exclusions.map { rebaseExclusion(it, manifest, trees) }
            val bundled = files.indices.filter { files[it].payload != null }
            val recorded = bundled.sumOf { manifest.files[it].sizeBytes ?: 0L }

            val needed = if (bundled.any { manifest.files[it].sizeBytes == null }) {
                maxOf(recorded, archiveFingerprint(archive).sizeBytes ?: recorded)
            } else { recorded }

            val free = allocatableBytes()
            if (needed + SPACE_MARGIN_BYTES > free) {
                return@withContext Result.failure(
                    ImportRefused(BackupImportFailure.NotEnoughSpace(needed + SPACE_MARGIN_BYTES, free))
                )
            }

            Result.success(
                ResolvedImport(
                    archive = archive,
                    manifest = manifest,
                    files = files,
                    exclusionUris = exclusionUris,
                    droppedCollections = dropped,
                    pickedRoots = decisions.mapNotNull { (index, decision) ->
                        (decision as? FolderDecision.Picked)?.let { index to it.treeUri }
                    }.toMap()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve the import", e)
            Result.failure(ImportRefused(BackupImportFailure.Unexpected(e.message ?: e::class.java.simpleName)))
        }
    }

    /** More than is free right now: the platform evicts other apps' clearable caches to meet a request this size. */
    private fun allocatableBytes(): Long = try {
        val storage = appContext.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(appContext.filesDir))
    } catch (e: Exception) {
        Log.w(TAG, "Could not ask for allocatable bytes; falling back to free space", e)
        appContext.filesDir.usableSpace
    }

    /** [isSameTree] is explicit so a failed scan (empty [candidates]) is not mistaken for the same-tree fast path. */
    private data class RebasedTree(
        val isSameTree: Boolean,
        val candidates: List<Pair<String, SourceFingerprint>> = emptyList()
    )

    private suspend fun rebaseTree(archivedTree: String, pickedTree: String): RebasedTree {
        wallpaperSources.takePersistedGrant(pickedTree)
        if (pickedTree == archivedTree) return RebasedTree(isSameTree = true)

        val scanned = try {
            folderScanner.scanDetailed(pickedTree)
        } catch (e: Exception) {
            Log.w(TAG, "Could not scan the folder picked for $archivedTree", e)
            emptyList()
        }
        return RebasedTree(isSameTree = false, candidates = scanned.map { it.uri to it.fingerprint })
    }

    private fun unreferenced(file: BackupFile) = ResolvedFile(file.uri, sourceTypeOf(file), available = false, verified = false, addedAt = file.addedAt)

    private fun sourceTypeOf(file: BackupFile): SourceType =
        file.sourceType.asEnum<SourceType>() ?: SourceType.FOLDER_DOC

    private suspend fun resolveFile(
        file: BackupFile,
        mode: BackupMode,
        trees: Map<String, RebasedTree>,
        mediaAccess: Boolean
    ): ResolvedFile {
        val sourceType = sourceTypeOf(file)
        val archived = SourceFingerprint(file.displayName, file.sizeBytes, file.modifiedAt)

        val reference: String? = when {
            !file.available -> null
            sourceType == SourceType.INTERNALIZED -> null
            sourceType == SourceType.MEDIA_STORE -> verifyMediaReference(file.uri, archived, mediaAccess)
            else -> rebaseDocument(file.uri, archived, trees)
        }

        if (reference != null) return ResolvedFile(reference, sourceType, available = true, verified = true, addedAt = file.addedAt)

        val entry = file.payload?.takeIf { BackupFormat.isValidImageEntry(it) }
        if (entry != null) {
            val staged = "img_${UUID.randomUUID()}.${entry.substringAfterLast('.')}"
            return ResolvedFile(
                uri = Uri.fromFile(File(internalDir, staged)).toString(),
                sourceType = SourceType.INTERNALIZED,
                // Provisional: staging flips this off if the payload turns out missing
                available = true,
                verified = true,
                addedAt = file.addedAt,
                payload = StagedPayload(entryName = entry, stagedName = staged)
            )
        }

        // Nothing resolved: kept as curated, marked unavailable
        if (mode == BackupMode.PORTABLE && file.available) {
            Log.w(TAG, "Portable archive carries no bytes for ${file.uri}; restoring it unavailable")
        }
        // Unverified: the uri may name a row on another device
        return ResolvedFile(file.uri, sourceType, available = false, verified = false, addedAt = file.addedAt)
    }

    private suspend fun verifyMediaReference(
        uri: String,
        archived: SourceFingerprint,
        mediaAccess: Boolean
    ): String? {
        if (archived.isEmpty || !mediaAccess) return null
        return uri.takeIf {
            wallpaperSources.fingerprint(it, SourceType.MEDIA_STORE).isUnchangedFrom(archived)
        }
    }

    /**
     * Null when the folder was skipped or the file is gone from it. Fingerprint rematching is the only thing
     * that gets a membership's edit onto the right image.
     */
    private fun rebaseDocument(
        uri: String,
        archived: SourceFingerprint,
        trees: Map<String, RebasedTree>
    ): String? {
        val tree = treeUriOf(uri)?.let { trees[it] } ?: return null
        if (tree.isSameTree) return uri
        return tree.candidates.firstOrNull { (_, fingerprint) -> fingerprint.identifies(archived) }?.first
    }

    private fun rebaseExclusion(
        exclusion: BackupExclusion,
        manifest: BackupManifest,
        trees: Map<String, RebasedTree>
    ): String {
        val archivedTree = manifest.collections.getOrNull(exclusion.collection)?.rootUri
        val tree = trees[archivedTree] ?: return exclusion.uri
        if (tree.isSameTree) return exclusion.uri
        val name = exclusion.displayName ?: return exclusion.uri
        return tree.candidates.firstOrNull { (_, fingerprint) -> fingerprint.displayName == name }?.first ?: exclusion.uri
    }

    /**
     * Staging comes first because it is the half that can still refuse; the rows then swap in a single
     * transaction and only afterwards do staged bytes move into place. Old internalized bytes are never deleted here.
     */
    suspend fun commit(
        resolved: ResolvedImport,
        onProgress: (BackupImportProgress) -> Unit
    ): Result<BackupImportSummary> = withContext(Dispatchers.IO) {
        val staging = File(appContext.filesDir, STAGING_FOLDER)
        var begun = false
        try {
            staging.deleteRecursively()
            staging.mkdirs()

            val staged = stage(resolved, staging, onProgress).getOrElse {
                staging.deleteRecursively()
                return@withContext Result.failure(it)
            }

            // Recorded before the first destructive step, to tell a restore interrupted by process death from a finished one
            begun = true
            importRecord.setCommitting(true)

            serviceLifecycle.requestStop()
            serviceLifecycle.onIntent(ServiceIntent.ActiveCollectionDeleted)
            // The record describes an install that no longer exists
            wallpaperRecordStore.clear()

            repository.replaceInstall(snapshotOf(resolved, staged))

            // Past the point of no return: everything below is repairable, so log instead of throwing
            runCatching { applySettings(resolved.manifest.settings) }
                .onFailure { Log.e(TAG, "Restored the rows but could not write the archive's settings", it) }
            runCatching { moveIntoPlace(staging) }
                .onFailure { Log.e(TAG, "Restored the rows but could not move the staged images into place", it) }
            runCatching { repository.reconcileStorage() }
                .onFailure { Log.e(TAG, "Restored the install but could not reconcile storage", it) }

            importRecord.setCommitting(false)
            Result.success(summaryOf(resolved, staged))
        } catch (e: Exception) {
            Log.e(TAG, "Backup import failed", e)
            runCatching { staging.deleteRecursively() }.onFailure { Log.e(TAG, "Could not sweep the staging folder after a failed import", it) }
            runCatching { importRecord.setCommitting(false) }.onFailure { Log.e(TAG, "Could not clear the committing flag after a failed import", it) }
            // Before the commit began the install is provably untouched; after it, rotation has been stopped
            val failure = if (begun) BackupImportFailure.Interrupted else BackupImportFailure.Unexpected(e.message ?: e::class.java.simpleName)
            Result.failure(ImportRefused(failure))
        }
    }

    /** One pass over the archive; refuses one that stops before its completion marker. Returns the indices of files that got bytes. */
    private fun stage(
        resolved: ResolvedImport,
        staging: File,
        onProgress: (BackupImportProgress) -> Unit
    ): Result<Set<Int>> {
        val wanted = resolved.files.withIndex()
            .mapNotNull { (index, file) -> file.payload?.let { it.entryName to (index to it.stagedName) } }
            .toMap()

        val written = mutableSetOf<Int>()
        var completion: BackupCompletion? = null
        var done = 0
        onProgress(BackupImportProgress(0, wanted.size))

        val input = appContext.contentResolver.openInputStream(resolved.archive)
            ?: return Result.failure(ImportRefused(BackupImportFailure.NotAnArchive))

        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == BackupFormat.COMPLETE_ENTRY) {
                        completion = BackupCompletionCodec.decode(zip.readBytes().decodeToString())
                    } else {
                        wanted[entry.name]?.let { (index, stagedName) ->
                            val target = File(staging, stagedName)
                            val bytes = FileOutputStream(target).use { out -> zip.copyTo(out, COPY_BUFFER_BYTES) }
                            // An empty entry is how an export records an unreadable image: the row still goes in, unavailable
                            if (bytes > 0) written += index else target.delete()
                            done++
                            onProgress(BackupImportProgress(done, wanted.size))
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Archive ended mid-stream; treating it as truncated", e)
            return Result.failure(ImportRefused(BackupImportFailure.Incomplete))
        }

        // Payloads the export disowned
        val disowned = completion?.unreadable.orEmpty().toSet()
        if (disowned.isNotEmpty()) {
            wanted.filterKeys { it in disowned }.values.forEach { (index, stagedName) ->
                written -= index
                File(staging, stagedName).delete()
            }
            Log.w(TAG, "Archive disowns ${disowned.size} payload(s); they restore unavailable")
        }

        return if (completion != null) {
            Result.success(written)
        } else {
            // Ended cleanly without the marker: truncated
            Result.failure(ImportRefused(BackupImportFailure.Incomplete))
        }
    }

    private fun snapshotOf(resolved: ResolvedImport, staged: Set<Int>): InstallSnapshot {
        val manifest = resolved.manifest

        // Skipped collections are dropped whole, so every index shifts
        val keptCollections = manifest.collections.indices.filter { it !in resolved.droppedCollections }
        val collectionAt = keptCollections.withIndex().associate { (position, idx) -> idx to position }

        val keptMemberships = manifest.memberships.withIndex()
            .filter { (_, membership) ->
                membership.collection in collectionAt && membership.file in resolved.files.indices
            }
        val membershipAt = keptMemberships.withIndex().associate { (position, entry) -> entry.index to position }

        return InstallSnapshot(
            collections = keptCollections.map { index ->
                collectionOf(manifest.collections[index], resolved.pickedRoots[index])
            },
            files = resolved.files.mapIndexed { index, file ->
                WallpaperFile(
                    uriString = file.uri,
                    sourceType = file.sourceType,
                    isAvailable = file.available && (file.payload == null || index in staged),
                    addedAt = file.addedAt,
                    isVerified = file.verified
                )
            },
            memberships = keptMemberships.map { (_, membership) ->
                SnapshotMembership(
                    collectionIndex = collectionAt.getValue(membership.collection),
                    fileIndex = membership.file,
                    wallpaper = Wallpaper(
                        collectionId = 0,
                        fileId = 0,
                        editParams = editParamsOf(membership.editZoom, membership.editOffsetX, membership.editOffsetY),
                        isManuallyAdded = membership.manuallyAdded,
                        isDefault = membership.isDefault,
                        addedAt = membership.addedAt
                    )
                )
            },
            exclusions = manifest.exclusions.mapIndexedNotNull { index, exclusion ->
                val collection = collectionAt[exclusion.collection] ?: return@mapIndexedNotNull null
                SnapshotExclusion(
                    collectionIndex = collection,
                    exclusion = FolderExclusion(
                        collectionId = 0,
                        uriString = resolved.exclusionUris[index],
                        editParams = editParamsOf(exclusion.editZoom, exclusion.editOffsetX, exclusion.editOffsetY),
                        excludedAt = exclusion.excludedAt
                    )
                )
            },
            collectionDefaults = keptCollections.mapIndexedNotNull { position, index ->
                val membership = manifest.collections[index].defaultMembership ?: return@mapIndexedNotNull null
                membershipAt[membership]?.let { position to it }
            }.toMap()
        )
    }

    /** [pickedRoot] overrides the archive's root: every document was just rebased into the folder the user chose. */
    private fun collectionOf(collection: BackupCollection, pickedRoot: String?) = WallpaperCollection(
        name = collection.name,
        type = collection.type.asEnum<CollectionType>() ?: CollectionType.MANUAL,
        isActive = collection.isActive,
        rootUriString = pickedRoot ?: collection.rootUri,
        defaultCropRule = collection.defaultCropRule.asEnum<CropRule>() ?: WallpaperCollection.DEFAULT_CROP_RULE,
        rotationPolicy = CollectionRotationSetting.decode(collection.rotationPolicy),
        createdAt = collection.createdAt,
        lastUsedAt = collection.lastUsedAt,
        isPinned = collection.isPinned,
        appRole = collection.appRole.asEnum<AppCollectionRole>()
    )

    private fun editParamsOf(zoom: Float?, offsetX: Float?, offsetY: Float?): EditParams? =
        if (zoom != null && offsetX != null && offsetY != null) EditParams(zoom, offsetX, offsetY) else null

    // Staged bytes only move once the rows that name them are committed
    private fun moveIntoPlace(staging: File) {
        if (!internalDir.exists()) internalDir.mkdirs()
        // Same filesystem, so the move is the atomic one rather than the copy fallback
        staging.listFiles()?.forEach { file -> replaceAtomically(file, File(internalDir, file.name)) }
        staging.deleteRecursively()
    }

    private val internalDir by lazy { File(appContext.filesDir, ImageInternalizer.INTERNAL_FOLDER) }

    private suspend fun applySettings(settings: BackupSettings) {
        settings.revertToDefault?.let { appDataStore.setRevertToDefault(it) }
        settings.startOnBoot?.let { appDataStore.setStartOnBoot(it) }
        settings.compressionQualityHigh?.let { appDataStore.setCompressionQualityHigh(it) }
        settings.compressionQualityLow?.let { appDataStore.setCompressionQualityLow(it) }
        settings.keepLocalCopies?.let { appDataStore.setKeepLocalCopies(it) }
        settings.skipOnDnd?.let { appDataStore.setSkipOnDnd(it) }
        settings.batterySaverPolicy.asEnum<BatterySaverPolicy>()?.let { appDataStore.setBatterySaverPolicy(it) }
        settings.wallpaperZoomFix?.let { appDataStore.setWallpaperZoomFix(WallpaperZoomFix.fromStoredValue(it)) }
        settings.wallpaperDestination.asEnum<WallpaperDestination>()?.let { appDataStore.setWallpaperDestination(it) }
        settings.wallpaperMode.asEnum<WallpaperMode>()?.let { appDataStore.setWallpaperMode(it) }
        settings.rotationPolicy?.let { appDataStore.setRotationPolicy(RotationPolicy.decode(it)) }
        // A delay matching the exporting device's default was never chosen: use this device's default instead
        settings.screenOffDelayMs?.let { delay ->
            val tuned = delay != settings.screenOffDelayDeviceDefault
            appDataStore.setScreenOffDelay(if (tuned) delay else DeviceDefaults.forThisDevice())
        }
    }

    private fun summaryOf(resolved: ResolvedImport, staged: Set<Int>): BackupImportSummary {
        val kept = resolved.manifest.listedCollections() - resolved.droppedCollections
        return BackupImportSummary(
            collections = kept.size,
            images = resolved.manifest.countImages(kept),
            unavailable = resolved.manifest.reachableFiles(resolved.droppedCollections).count { index ->
                val file = resolved.files.getOrNull(index) ?: return@count false
                !file.available || (file.payload != null && index !in staged)
            },
            skippedCollections = resolved.droppedCollections.size
        )
    }
}

class ImportRefused(val failure: BackupImportFailure) : IOException(failure.toString())

/** Null when the archive names a constant this build does not have. */
internal inline fun <reified E : Enum<E>> String?.asEnum(): E? =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } }