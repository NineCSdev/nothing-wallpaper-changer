package com.ninecsdev.wallpaperchanger.data.source

import android.database.Cursor

/**
 * Reads one column, or null when the provider did not supply it. A missing column comes back as
 * index -1, and a column that exists can still be null in this row.
 */
internal fun Cursor.stringAt(column: Int): String? = column.takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

internal fun Cursor.longAt(column: Int): Long? = column.takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
