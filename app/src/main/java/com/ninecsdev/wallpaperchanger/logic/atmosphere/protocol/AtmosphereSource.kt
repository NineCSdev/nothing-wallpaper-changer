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
 * This object owns the file names and the format; nothing else should know either.
 */
object AtmosphereSource {

    private const val TAG = "AtmosphereSource"

    private const val FILE_NAME = "atmosphere_source.bin"
    private const val TEMP_FILE_NAME = "atmosphere_source.tmp"

    /** 'ATMO'. Guards against decoding an unrelated file left at the same path. */
    private const val MAGIC = 0x41544D4F
    private const val VERSION = 1

    /** A decoded container: the seeds, and the still-encoded image bytes. */
    class Payload(val seeds: List<VertexInfo>, val imageBytes: ByteArray)

    /** A read whose image bytes have been decoded. The caller owns [bitmap]. */
    class Decoded(val seeds: List<VertexInfo>, val bitmap: Bitmap)

    /** @param dir the app's `filesDir`. */
    fun file(dir: File): File = File(dir, FILE_NAME)

    /**
     * Replaces the current source. Returns false and leaves the existing file untouched on any
     * failure, which is what lets the engine keep rendering its last good source.
     *
     * @param imageBytes the encoded (WebP) fitted image, exactly as it should be uploaded.
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md (container round-trip)
    fun write(dir: File, seeds: List<VertexInfo>, imageBytes: ByteArray): Boolean {
        require(seeds.size == VertexInfo.SEED_COUNT) {
            "expected ${VertexInfo.SEED_COUNT} seeds, got ${seeds.size}"
        }

        return try {
            writeAtomically(File(dir, TEMP_FILE_NAME), file(dir)) { temp ->
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
            Log.e(TAG, "Failed to write atmosphere source", e)
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
    fun read(dir: File): Payload? {
        val source = file(dir)
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
            Log.e(TAG, "Failed to read atmosphere source", e)
            null
        }
    }
}
