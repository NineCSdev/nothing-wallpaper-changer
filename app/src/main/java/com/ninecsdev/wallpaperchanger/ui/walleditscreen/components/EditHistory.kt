package com.ninecsdev.wallpaperchanger.ui.walleditscreen.components

import androidx.compose.runtime.mutableStateListOf
import com.ninecsdev.wallpaperchanger.model.EditParams

/**
 * Undo stack for one editor session. Entries are whole [EditParams] snapshots, so an input that
 * moves several values at once (a pinch, a double tap, fit height) undoes as one step.
 *
 * Callers [commit] when an input ends; the last entry is where [undo] returns to.
 */
internal class EditHistory {
    private val states = mutableStateListOf<EditParams>()

    /** Starts the session over from [start], the saved params. */
    fun reset(start: EditParams) {
        states.clear()
        states += start
    }

    fun commit(current: EditParams) {
        val last = states.lastOrNull()
        if (last == null || !last.matches(current)) states += current
    }

    fun canUndo(current: EditParams): Boolean = states.size > 1 || states.lastOrNull()?.matches(current) == false

    /** Reverts a change not yet committed, otherwise steps back one commit. */
    fun undo(current: EditParams): EditParams? {
        val last = states.lastOrNull() ?: return null
        if (last.matches(current) && states.size > 1) states.removeAt(states.lastIndex)
        return states.last()
    }

    private fun EditParams.matches(other: EditParams) = matchesEditParams(this, other.zoom, other.offsetX, other.offsetY)
}
