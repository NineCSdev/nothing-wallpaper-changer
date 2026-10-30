package com.ninecsdev.wallpaperchanger.logic

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Files a background reader may open at any moment go through here, so a crash mid-write never
 * leaves a half-written file. [tempFile] must be on the same filesystem as [destination], or the
 * move degrades to a non-atomic copy.
 */
fun replaceAtomically(tempFile: File, destination: File) {
    try {
        Files.move(tempFile.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tempFile.toPath(), destination.toPath(), REPLACE_EXISTING)
    }
}

/** Runs [write] against [tempFile], swaps it onto [destination], and deletes the temp file on failure. */
inline fun writeAtomically(tempFile: File, destination: File, write: (File) -> Unit) {
    try {
        write(tempFile)
        replaceAtomically(tempFile, destination)
    } finally {
        // No-op on success: the move already consumed tempFile
        tempFile.delete()
    }
}
