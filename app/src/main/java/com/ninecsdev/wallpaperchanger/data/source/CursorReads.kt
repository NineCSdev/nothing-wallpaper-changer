package com.ninecsdev.wallpaperchanger.data.source

import android.database.Cursor

/** Null when the provider omitted the column (index -1) or the value is null in this row. */
internal fun Cursor.stringAt(column: Int): String? = column.takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

internal fun Cursor.longAt(column: Int): Long? = column.takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
