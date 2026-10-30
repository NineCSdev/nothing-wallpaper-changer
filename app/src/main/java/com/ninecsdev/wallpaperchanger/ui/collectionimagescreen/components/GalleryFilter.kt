package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/** A yes/no question about a membership; each answer is one chip in the filter row. */
enum class FilterDimension {
    AVAILABLE, EDITED, FAVORITE, LOCAL_COPY, FROM_FOLDER;

    fun matches(image: WallpaperImage, favoriteFileIds: Set<Long>): Boolean = when (this) {
        AVAILABLE -> image.isAvailable
        EDITED -> image.editParams != null
        FAVORITE -> image.fileId in favoriteFileIds
        LOCAL_COPY -> image.sourceType == SourceType.INTERNALIZED
        FROM_FOLDER -> !image.isManuallyAdded
    }
}

/** One chip: [answer] is the side of [dimension] it keeps. */
data class FilterChip(val dimension: FilterDimension, val answer: Boolean, val count: Int, val isSelected: Boolean)

/** The answer chosen per dimension; dimensions combine with AND. */
typealias GalleryFilter = Map<FilterDimension, Boolean>

fun GalleryFilter.matches(image: WallpaperImage, favoriteFileIds: Set<Long>): Boolean =
    all { (dimension, answer) -> dimension.matches(image, favoriteFileIds) == answer }
