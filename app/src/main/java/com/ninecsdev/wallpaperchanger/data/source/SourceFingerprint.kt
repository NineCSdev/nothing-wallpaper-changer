package com.ninecsdev.wallpaperchanger.data.source

/**
 * Metadata to recognize a file again, deliberately not a content hash, which would make every export
 * read every referenced image end to end. A null field is "the provider did not say".
 */
data class SourceFingerprint(
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    /** Milliseconds. */
    val modifiedAt: Long? = null
) {
    val isEmpty: Boolean
        get() = displayName == null && sizeBytes == null && modifiedAt == null

    /** The same file: every field both sides carry agrees, modification time included, and at least one is carried. */
    // TODO tests: see vault note tests/Backup Archive Tests.md (the cells of this rule)
    fun isUnchangedFrom(other: SourceFingerprint): Boolean = agrees(other, compareModifiedAt = true)

    /** The same picture wherever it now lives: like [isUnchangedFrom] but ignoring modification time. */
    fun identifies(other: SourceFingerprint): Boolean = agrees(other, compareModifiedAt = false)

    private fun agrees(other: SourceFingerprint, compareModifiedAt: Boolean): Boolean {
        var compared = 0
        if (displayName != null && other.displayName != null) {
            if (displayName != other.displayName) return false
            compared++
        }
        if (sizeBytes != null && other.sizeBytes != null) {
            if (sizeBytes != other.sizeBytes) return false
            compared++
        }
        if (compareModifiedAt && modifiedAt != null && other.modifiedAt != null) {
            if (modifiedAt != other.modifiedAt) return false
            compared++
        }
        return compared > 0
    }
}
