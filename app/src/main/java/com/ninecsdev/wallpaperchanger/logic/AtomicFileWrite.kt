package com.ninecsdev.wallpaperchanger.logic

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Replaces [destination] with [tempFile] in place, atomically when the filesystem supports it and
 * with a plain replace as fallback.
 *
 * Every file a background reader may open at any moment — the rotation buffer and the atmosphere
 * source — is written to a temp file and swapped in through here, so a crash mid-write can never
 * leave a half-written file behind for the next reader to decode.
 *
 * [tempFile] must live on the same filesystem as [destination], or the move degrades to a copy and
 * loses atomicity.
 */
fun replaceAtomically(tempFile: File, destination: File) {
    try {
        Files.move(tempFile.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tempFile.toPath(), destination.toPath(), REPLACE_EXISTING)
    }
}
