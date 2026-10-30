package com.ninecsdev.wallpaperchanger.data.source

/** What the preview's details sheet shows about a file's source; null is "could not be read". */
data class SourceDetails(
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val folderName: String? = null
) {
    val isEmpty: Boolean
        get() = this == SourceDetails()
}
