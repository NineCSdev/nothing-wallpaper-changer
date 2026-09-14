package com.ninecsdev.wallpaperchanger.data.backup

import android.util.Log
import com.ninecsdev.wallpaperchanger.model.enums.AppCollectionRole
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Shape of the `.nwcbak` archive: a zip holding one manifest and, for the images whose bytes
 * travel, one entry each.
 */
object BackupFormat {
    const val EXTENSION = "nwcbak"

    /** Opaque on purpose: no file manager offers to unpack it and nothing goes looking for pictures. */
    const val MIME_TYPE = "application/octet-stream"

    const val MANIFEST_ENTRY = "manifest.json"
    const val IMAGE_DIR = "images/"

    /**
     * The last entry an export writes. Absence means the export didn't finalize correctly, and we
     * shouldn't use it; its contents are a [BackupCompletion] naming the payloads that went wrong.
     */
    const val COMPLETE_ENTRY = "complete"

    /** Matches the names [imageEntry] produces, and nothing else. Archive entry names are hostile input. */
    private val IMAGE_ENTRY_PATTERN = Regex("""images/\d{4,}\.[A-Za-z0-9]{1,5}""")

    /** True when [entryName] is an image entry this format could have written */
    fun isValidImageEntry(entryName: String): Boolean = IMAGE_ENTRY_PATTERN.matches(entryName)

    /** The manifest's own version, independent of the app's and of the Room schema's */
    const val CURRENT_VERSION = 1

    /** Entry name for the bundled bytes of the file at [fileIndex] in the manifest. Formatted in [Locale.ROOT] */
    fun imageEntry(fileIndex: Int, extension: String): String = String.format(Locale.ROOT, "$IMAGE_DIR%04d.%s", fileIndex, extension)
}

/** Which bytes an archive carries */
enum class BackupMode {
    DEVICE,
    PORTABLE
}

/** The archive's logical content: what the install *is*. Flat arrays with integer refs. */
@Serializable
data class BackupManifest(
    val formatVersion: Int = BackupFormat.CURRENT_VERSION,
    val appVersionCode: Long = 0,
    /** Diagnostic only. Nothing reads it to decide anything, here for a bad-restore report. */
    val roomSchemaVersion: Int = 0,
    val exportedAt: Long = 0,
    val mode: BackupMode = BackupMode.DEVICE,
    val collections: List<BackupCollection>,
    val files: List<BackupFile>,
    val memberships: List<BackupMembership>,
    val exclusions: List<BackupExclusion>,
    val settings: BackupSettings = BackupSettings()
)

/** A [collection][com.ninecsdev.wallpaperchanger.model.WallpaperCollection]. [defaultMembership] indexes [BackupManifest.memberships] */
@Serializable
data class BackupCollection(
    val name: String,
    val type: String,
    val rootUri: String? = null,
    val defaultCropRule: String,
    val rotationPolicy: String,
    val isActive: Boolean = false,
    val isPinned: Boolean = false,
    val appRole: String? = null,
    val createdAt: Long = 0,
    val lastUsedAt: Long = 0,
    val defaultMembership: Int? = null
)

/**
 * One [physical image][com.ninecsdev.wallpaperchanger.model.WallpaperFile] in the file registry.
 *
 * [payload] names this file's zip entry when its bytes travel, absent means reference-only. The
 * fingerprint ([displayName], [sizeBytes], [modifiedAt]) stops a reference resolving to the wrong picture.
 */
@Serializable
data class BackupFile(
    val uri: String,
    val sourceType: String,
    val available: Boolean = true,
    val addedAt: Long = 0,
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
    val payload: String? = null
)

/** A [membership][com.ninecsdev.wallpaperchanger.model.Wallpaper]: one file's place in one collection, carrying that collection's edit of it. */
@Serializable
data class BackupMembership(
    val collection: Int,
    val file: Int,
    val editZoom: Float? = null,
    val editOffsetX: Float? = null,
    val editOffsetY: Float? = null,
    val manuallyAdded: Boolean = false,
    val isDefault: Boolean = false,
    val addedAt: Long = 0
)

/**
 * A [tombstone][com.ninecsdev.wallpaperchanger.model.FolderExclusion].
 *
 * [displayName] is the only thing that can re-key these onto a folder picked at a new location.
 */
@Serializable
data class BackupExclusion(
    val collection: Int,
    val uri: String,
    val displayName: String? = null,
    val editZoom: Float? = null,
    val editOffsetX: Float? = null,
    val editOffsetY: Float? = null,
    val excludedAt: Long = 0
)

/**
 * The user's settings. All nullable: absent means "this archive predates the setting", and the
 * importer leaves the local value alone.
 *
 * [screenOffDelayDeviceDefault] travels beside [screenOffDelayMs] to tell a tuned value from an untouched default.
 */
@Serializable
data class BackupSettings(
    val revertToDefault: Boolean? = null,
    val startOnBoot: Boolean? = null,
    val screenOffDelayMs: Long? = null,
    val screenOffDelayDeviceDefault: Long? = null,
    val compressionQualityHigh: Int? = null,
    val compressionQualityLow: Int? = null,
    val batterySaverPolicy: String? = null,
    val wallpaperZoomFix: Int? = null,
    val wallpaperDestination: String? = null,
    val wallpaperMode: String? = null,
    val keepLocalCopies: Boolean? = null,
    val rotationPolicy: String? = null,
    val skipOnDnd: Boolean? = null
)

internal fun BackupManifest.listedCollections(): Set<Int> = collections.indices.filterTo(mutableSetOf()) { collections[it].appRole != AppCollectionRole.DEFAULTS.name }

internal fun BackupManifest.reachableFiles(droppedCollections: Set<Int>): Set<Int> = memberships.filter { it.collection !in droppedCollections }.mapTo(mutableSetOf()) { it.file }

internal fun BackupManifest.countImages(inCollections: Set<Int>): Int =
    memberships.filter { !it.isDefault && it.collection in inCollections }
        .distinctBy { it.file }
        .size

/**
 * The archive's closing record, written as the contents of [BackupFormat.COMPLETE_ENTRY].
 *
 * [unreadable] names the image entries whose source died part-way through being copied.
 */
@Serializable
data class BackupCompletion(val unreadable: List<String> = emptyList())

/**
 * Reads and writes the completion record.
 *
 * Decoding never fails: the record only ever *narrows* what an archive claims, so losing it reads as
 * an archive with nothing to disown.
 */
object BackupCompletionCodec {
    private const val TAG = "BackupCompletionCodec"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(completion: BackupCompletion): String =
        json.encodeToString(BackupCompletion.serializer(), completion)

    fun decode(raw: String): BackupCompletion = try {
        json.decodeFromString(BackupCompletion.serializer(), raw)
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable completion record; assuming every payload is sound", e)
        BackupCompletion()
    }
}

/** Just enough of a manifest to learn whether we can read the rest of it. */
@Serializable
private data class FormatProbe(val formatVersion: Int = 0)

/**
 * Reads and writes `manifest.json`.
 *
 * A version newer than [BackupFormat.CURRENT_VERSION] is refused rather than guessed at. An older
 * one is read directly, which holds while every past version is a prefix of the current shape.
 */
// TODO tests: see vault note tests/Backup Archive Tests.md (round-trip and version refusal)
object BackupManifestCodec {
    private const val TAG = "BackupManifestCodec"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(manifest: BackupManifest): String = json.encodeToString(BackupManifest.serializer(), manifest)

    /** Fails with an [ImportRefused] carrying why the archive cannot be read. */
    fun decode(raw: String): Result<BackupManifest> {
        // Probed rather than parsed whole first: a manifest runs to megabytes, and this skips it
        val version = try {
            json.decodeFromString(FormatProbe.serializer(), raw).formatVersion
        } catch (e: SerializationException) {
            return refuse(BackupImportFailure.Malformed(e.message ?: "unparseable JSON"))
        }
        if (version <= 0) return refuse(BackupImportFailure.Malformed("no usable formatVersion"))
        if (version > BackupFormat.CURRENT_VERSION) return refuse(BackupImportFailure.NewerFormat(version))

        return try {
            Result.success(json.decodeFromString(BackupManifest.serializer(), raw))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Manifest v$version failed to decode", e)
            refuse(BackupImportFailure.Malformed(e.message ?: "unreadable manifest"))
        }
    }

    private fun refuse(failure: BackupImportFailure): Result<BackupManifest> = Result.failure(ImportRefused(failure))
}
