package com.ninecsdev.wallpaperchanger.model.enums

import androidx.annotation.StringRes
import com.ninecsdev.wallpaperchanger.R

/**
 * Sorting criteria available for the Collection List screen.
 */
enum class CollectionSortOrder(@param:StringRes val labelRes: Int) {
    NAME(R.string.sort_order_name),
    LAST_USED(R.string.sort_order_last_used),
    DATE_CREATED(R.string.sort_order_date_created)
}
