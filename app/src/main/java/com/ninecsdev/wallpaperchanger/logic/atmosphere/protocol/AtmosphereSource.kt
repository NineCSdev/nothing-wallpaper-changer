package com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.ninecsdev.wallpaperchanger.logic.writeAtomically
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The on-disk hand-off between the app and the live-wallpaper engine.
 *
 * The engine is started by the system at boot and recreated on every surface teardown, both of
 * which can happen with the app's own process absent, so a durable file is the source of truth.
 * The image and its six seeds live in **one container**, written to a temp file and moved into
 * place with a single rename.
 *
 * The same container is written twice over its life. A **staged** one is produced by the render
 * pipeline, and *promoting* it (copying it over the live source and telling the engine).
 * Staging it at render time keeps the palette scan off the screen-off path, and be quantized from
 * the unframed photo.
 *
 * This object owns the file names and the format; nothing else should know either.
 */
object AtmosphereSource {

    private const val TAG = "AtmosphereSource"

    private const val FILE_NAME = "atmosphere_source.bin"
    private const val TEMP_FILE_NAME = "atmosphere_source.tmp"

    /** The delivery the next rotation will publish, written by the render pipeline. */
    // Kept in `filesDir` rather than the cache so platform cannot delete it under storage pressure.
    private const val PENDING_FILE_NAME = "atmosphere_pending.bin"

    /** 'ATMO'. Guards against decoding an unrelated file left at the same path. */
    private const val MAGIC = 0x41544D4F
    private const val VERSION = 1

    /** A decoded container: the seeds, and the still-encoded image bytes. */
    class Payload(val seeds: List<VertexInfo>, val imageBytes: ByteArray)

    /** A read whose image bytes have been decoded. The caller owns [bitmap]. */
    class Decoded(val seeds: List<VertexInfo>, val bitmap: Bitmap)

    /**
     * The live source: what the engine reads.
     * @param dir the app's `filesDir`.
     */
    fun file(dir: File): File = File(dir, FILE_NAME)

    /**
     * The staged delivery: what [promotePending] will make live.
     * @param dir the app's `filesDir`.
     */
    fun pendingFile(dir: File): File = File(dir, PENDING_FILE_NAME)

    /**
     * Replaces the current source. Returns false and leaves the existing file untouched on any
     * failure, which is what lets the engine keep rendering its last good source.
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md (container round-trip)
    fun write(dir: File, seeds: List<VertexInfo>, imageBytes: ByteArray): Boolean =
        writeContainer(dir, file(dir), seeds, imageBytes)

    /** Stages the delivery the next rotation will publish, replacing any previously staged one. */
    fun writePending(dir: File, seeds: List<VertexInfo>, imageBytes: ByteArray): Boolean =
        writeContainer(dir, pendingFile(dir), seeds, imageBytes)

    /**
     * Makes the staged delivery the live one.
     *
     * Returns false when nothing is staged, how a rotation prepared for the other delivery mode
     * reports itself rather than publishing something framed for the wrong surface.
     */
    fun promotePending(dir: File): Boolean {
        val pending = pendingFile(dir)
        if (!pending.exists()) {
            Log.w(TAG, "No staged delivery to promote")
            return false
        }

        return try {
            // A copy, not a move. The engine confirms display asynchronously and may never confirm at
            // all. Consuming the staged file here would leave an unconfirmed delivery with nothing staged
            // and nothing to re-stage it, and every later rotation would fail the same way.
            writeAtomically(File(dir, TEMP_FILE_NAME), file(dir)) { temp ->
                pending.copyTo(temp, overwrite = true)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote the staged delivery", e)
            false
        }
    }

    /** Discards the staged delivery, if any. */
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
            writeAtomically(File(dir, TEMP_FILE_NAME), destination) { temp ->
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${destination.name}", e)
            false
        }
    }

    /**
     * [read] plus the bitmap decode, which every caller does and none of them differently.
     *
     * Returns null whenever the source is unreadable or the image does not decode.
     * That is never a reason to blank the screen: callers keep whatever is already loaded.
     */
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

    /** Reads the staged delivery, on the same terms as [read]. */
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
