package com.ninecsdev.wallpaperchanger.data.backup

import android.util.Log
import com.ninecsdev.wallpaperchanger.model.enums.AppCollectionRole
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/** The `.nwcbak` archive: a zip holding one manifest and one entry per image whose bytes travel. */
object BackupFormat {
    const val EXTENSION = "nwcbak"

    /** Opaque on purpose: no file manager offers to unpack it and nothing goes looking for pictures. */
    const val MIME_TYPE = "application/octet-stream"

    const val MANIFEST_ENTRY = "manifest.json"
    const val MANIFEST_MAX_BYTES = 16 * 1024 * 1024
    const val IMAGE_DIR = "images/"

    /** The last entry an export writes; absence means the export did not finalize and the archive must not be used. Holds a [BackupCompletion]. */
    const val COMPLETE_ENTRY = "complete"

    /** Matches the names [imageEntry] produces, and nothing else. Archive entry names are hostile input. */
    private val IMAGE_ENTRY_PATTERN = Regex("""images/\d{4,}\.[A-Za-z0-9]{1,5}""")

    fun isValidImageEntry(entryName: String): Boolean = IMAGE_ENTRY_PATTERN.matches(entryName)

    /** Independent of the app's version and the Room schema's. */
    const val CURRENT_VERSION = 1

    /** [Locale.ROOT] so digits are never localized. */
    fun imageEntry(fileIndex: Int, extension: String): String = String.format(Locale.ROOT, "$IMAGE_DIR%04d.%s", fileIndex, extension)
}

enum class BackupMode {
    DEVICE,
    PORTABLE
}

/** Flat arrays cross-referenced by index. */
@Serializable
data class BackupManifest(
    val formatVersion: Int = BackupFormat.CURRENT_VERSION,
    val appVersionCode: Long = 0,
    /** Diagnostic only; nothing reads it to decide anything. */
    val roomSchemaVersion: Int = 0,
    val exportedAt: Long = 0,
    val mode: BackupMode = BackupMode.DEVICE,
    val collections: List<BackupCollection>,
    val files: List<BackupFile>,
    val memberships: List<BackupMembership>,
    val exclusions: List<BackupExclusion>,
    val settings: BackupSettings = BackupSettings()
)

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
 * [payload] names this file's zip entry when its bytes travel; absent means reference-only. The fingerprint
 * ([displayName], [sizeBytes], [modifiedAt]) stops a reference resolving to the wrong picture.
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

/** [displayName] is the only thing that can re-key a tombstone onto a folder picked at a new location. */
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
 * All nullable: absent means the archive predates the setting, and the importer leaves the local value alone.
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

/** The contents of [BackupFormat.COMPLETE_ENTRY]; [unreadable] names image entries whose source died part-way through the copy. */
@Serializable
data class BackupCompletion(val unreadable: List<String> = emptyList())

/** Decoding never fails: the record only narrows what an archive claims, so losing it reads as nothing to disown. */
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

@Serializable
private data class FormatProbe(val formatVersion: Int = 0)

/**
 * A newer version is refused rather than guessed at. An older one is read directly, which holds while
 * every past version is a prefix of the current shape.
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

    /** Failure is an [ImportRefused] carrying why the archive cannot be read. */
    fun decode(raw: String): Result<BackupManifest> {
        // Probed first so an unsupported manifest, which can run to megabytes, is never fully parsed
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
