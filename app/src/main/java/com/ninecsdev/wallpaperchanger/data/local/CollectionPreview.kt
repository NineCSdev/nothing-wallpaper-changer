package com.ninecsdev.wallpaperchanger.data.local

/**
 * What a collection grid item is drawn from: its newest few thumbnail uris and its total image
 * count. A projection, not a domain type why it lives here.
 *
 * [previewUris] is capped by the caller's limit and ordered newest-first.
 * [imageCount] is the whole collection, so it is routinely larger.
 */
data class CollectionPreview(
    val previewUris: List<String> = emptyList(),
    val imageCount: Int = 0
)
