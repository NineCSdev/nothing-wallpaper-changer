package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * The on-disk hand-off between the app and the live-wallpaper engine.
 *
 * The engine is started by the system at boot and recreated on every surface teardown, both of
 * which can happen with the app's own process absent, so a durable file is the source of truth.
 * The image and its six seeds live in **one container**, written to a temp file and moved into
 * place with a single rename.
 *
 * This object owns the path and the format; nothing else should know either.
 */
object AtmosphereSource {

    private const val TAG = "AtmosphereSource"

    private const val FILE_NAME = "atmosphere_source.bin"
    private const val TEMP_FILE_NAME = "atmosphere_source.tmp"

    /** 'ATMO'. Guards against decoding an unrelated file left at the same path. */
    private const val MAGIC = 0x41544D4F
    private const val VERSION = 1

    /** Sent after a successful [write]. Namespaced, and registered `RECEIVER_NOT_EXPORTED`. */
    const val ACTION_RELOAD = "com.ninecsdev.wallpaperchanger.action.ATMOSPHERE_RELOAD"

    /** A decoded container: the seeds, and the still-encoded image bytes. */
    class Payload(val seeds: List<VertexInfo>, val imageBytes: ByteArray)

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    /**
     * Replaces the current source. Returns false and leaves the existing file untouched on any
     * failure, which is what lets the engine keep rendering its last good source.
     *
     * @param imageBytes the encoded (WebP) fitted image, exactly as it should be uploaded.
     */
    fun write(context: Context, seeds: List<VertexInfo>, imageBytes: ByteArray): Boolean {
        require(seeds.size == AtmosphereConstants.SEED_COUNT) {
            "expected ${AtmosphereConstants.SEED_COUNT} seeds, got ${seeds.size}"
        }

        val temp = File(context.filesDir, TEMP_FILE_NAME)
        return try {
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
                    out.writeInt(seed.swatchColor)
                    out.writeInt(seed.population)
                }
                out.writeInt(imageBytes.size)
                out.write(imageBytes)
            }
            moveIntoPlace(temp, file(context))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write atmosphere source", e)
            false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Reads the current source, or null if it is absent, truncated or of an unknown version. */
    fun read(context: Context): Payload? {
        val source = file(context)
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
                if (count != AtmosphereConstants.SEED_COUNT) {
                    Log.w(TAG, "Expected ${AtmosphereConstants.SEED_COUNT} seeds, found $count; ignoring")
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
                        swatchColor = input.readInt(),
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

    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }
}
