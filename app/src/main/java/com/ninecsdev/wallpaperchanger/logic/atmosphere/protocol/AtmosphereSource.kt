package com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.ninecsdev.wallpaperchanger.logic.writeAtomically
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The engine can start with the app's process absent, so a durable file is the source of truth.
 * Image and seeds live in one container, swapped in with a single rename. A **staged** container
 * is written at render time (keeping the palette scan off the screen-off path) and *promoted* to
 * the live source on delivery. Owns the file names and format.
 */
object AtmosphereSource {

    private const val TAG = "AtmosphereSource"

    private const val FILE_NAME = "atmosphere_source.bin"

    // In `filesDir`, not the cache, so the platform cannot delete it under storage pressure
    private const val PENDING_FILE_NAME = "atmosphere_pending.bin"

    /** 'ATMO'. */
    private const val MAGIC = 0x41544D4F
    private const val VERSION = 1

    private val writeLock = Any()

    /** Distinct per destination so two writers cannot share one. */
    private fun tempFor(dir: File, destination: File) = File(dir, "${destination.name}.tmp")

    class Payload(val seeds: List<VertexInfo>, val imageBytes: ByteArray)

    /** The caller owns [bitmap]. */
    class Decoded(val seeds: List<VertexInfo>, val bitmap: Bitmap)

    fun file(dir: File): File = File(dir, FILE_NAME)

    fun pendingFile(dir: File): File = File(dir, PENDING_FILE_NAME)

    /** On failure returns false and leaves the existing file untouched, so the engine keeps its last good source. */
    fun write(dir: File, seeds: List<VertexInfo>, imageBytes: ByteArray): Boolean = writeContainer(dir, file(dir), seeds, imageBytes)

    fun writePending(dir: File, seeds: List<VertexInfo>, imageBytes: ByteArray): Boolean = writeContainer(dir, pendingFile(dir), seeds, imageBytes)

    /** Returns false when nothing is staged, so a buffer prepared for the other delivery mode is never published. */
    fun promotePending(dir: File): Boolean {
        val pending = pendingFile(dir)
        if (!pending.exists()) {
            Log.w(TAG, "No staged delivery to promote")
            return false
        }

        return try {
            synchronized(writeLock) {
                val destination = file(dir)
                // A copy, not a move: the engine confirms display asynchronously and may never confirm
                writeAtomically(tempFor(dir, destination), destination) { temp ->
                    pending.copyTo(temp, overwrite = true)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote the staged delivery", e)
            false
        }
    }

    fun clearPending(dir: File) {
        val pending = pendingFile(dir)
        if (pending.exists() && !pending.delete()) {
            Log.w(TAG, "Could not delete the staged atmosphere delivery")
        }
    }

    private fun writeContainer(
        dir: File,
        destination: File,
        seeds: List<VertexInfo>,
        imageBytes: ByteArray
    ): Boolean {
        require(seeds.size == VertexInfo.SEED_COUNT) {
            "expected ${VertexInfo.SEED_COUNT} seeds, got ${seeds.size}"
        }

        return try {
            synchronized(writeLock) {
                writeAtomically(tempFor(dir, destination), destination) { temp ->
                    DataOutputStream(temp.outputStream().buffered()).use { out ->
                        out.writeInt(MAGIC)
                        out.writeInt(VERSION)
                        out.writeInt(seeds.size)
                        for (seed in seeds) {
                            out.writeInt(seed.x)
                            out.writeInt(seed.y)
                            out.writeInt(seed.bitmapWidth)
                            out.writeInt(seed.bitmapHeight)
                            out.writeInt(seed.pixelColor)
                            out.writeInt(seed.population)
                        }
                        out.writeInt(imageBytes.size)
                        out.write(imageBytes)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${destination.name}", e)
            false
        }
    }

    /** Null if unreadable or undecodable; never a reason to blank the screen, callers keep what is loaded. */
    fun readDecoded(dir: File): Decoded? {
        val payload = read(dir) ?: run {
            Log.w(TAG, "No readable source; keeping whatever is loaded")
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(payload.imageBytes, 0, payload.imageBytes.size)
        if (bitmap == null) {
            Log.w(TAG, "Source image failed to decode; keeping whatever is loaded")
            return null
        }
        return Decoded(payload.seeds, bitmap)
    }

    /** Reads the current source, or null if it is absent, truncated or of an unknown version. */
    fun read(dir: File): Payload? = readContainer(file(dir))

    fun readPending(dir: File): Payload? = readContainer(pendingFile(dir))

    private fun readContainer(source: File): Payload? {
        if (!source.exists()) return null

        return try {
            DataInputStream(source.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) {
                    Log.w(TAG, "Bad magic; ignoring source file")
                    return null
                }
                val version = input.readInt()
                if (version != VERSION) {
                    Log.w(TAG, "Unsupported source version $version; ignoring")
                    return null
                }

                val count = input.readInt()
                if (count != VertexInfo.SEED_COUNT) {
                    Log.w(TAG, "Expected ${VertexInfo.SEED_COUNT} seeds, found $count; ignoring")
                    return null
                }

                val seeds = ArrayList<VertexInfo>(count)
                repeat(count) {
                    seeds += VertexInfo(
                        x = input.readInt(),
                        y = input.readInt(),
                        bitmapWidth = input.readInt(),
                        bitmapHeight = input.readInt(),
                        pixelColor = input.readInt(),
                        population = input.readInt()
                    )
                }

                val imageLength = input.readInt()
                if (imageLength <= 0) {
                    Log.w(TAG, "Source carries no image bytes; ignoring")
                    return null
                }
                val imageBytes = ByteArray(imageLength)
                input.readFully(imageBytes)

                Payload(seeds, imageBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ${source.name}", e)
            null
        }
    }
}
