package com.ninecsdev.wallpaperchanger.data.source

/**
 * Enough of a file's metadata to recognize it again. Not a content hash because it would force every
 * export to read every referenced image end to end and this metadata should be enough.
 *
 * Used to decide whether a uri still points at the image it pointed at when it was written down.
 *
 * A null field is "the provider did not say".
 */
data class SourceFingerprint(
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    /** In milliseconds */
    val modifiedAt: Long? = null
) {
    val isEmpty: Boolean
        get() = displayName == null && sizeBytes == null && modifiedAt == null

    /**
     * True when this is still the **same file**: every field both sides carry agrees, modification
     * time included, and they agree on at least one. The question a *reference* has to answer.
     */
    // TODO tests: see vault note tests/Backup Archive Tests.md (the cells of this rule)
    fun isUnchangedFrom(other: SourceFingerprint): Boolean = agrees(other, compareModifiedAt = true)

    /**
     * True when this is **the same picture**, wherever it now lives: name and size agree, and they
     * agree on at least one. The question a *folder rebase* has to answer.
     */
    fun identifies(other: SourceFingerprint): Boolean = agrees(other, compareModifiedAt = false)

    /** True when both fingerprints have metadata and is the same, false otherwise */
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
