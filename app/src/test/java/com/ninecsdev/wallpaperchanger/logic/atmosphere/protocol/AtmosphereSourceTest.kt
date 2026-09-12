package com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Pins the container format [AtmosphereSource] writes and the engine reads.
 *
 * Both sides depend on this file and neither owns it, and the engine opens it in a process that
 * can start at boot with the app absent. A change to the byte layout is invisible in a build.
 *
 * [AtmosphereSource.readDecoded] is not covered as it needs Android.
 */
class AtmosphereSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() { dir = temp.newFolder() }

    /** Seeds whose every field differs, so a transposed or dropped one cannot pass. */
    private fun seeds(): List<VertexInfo> = List(VertexInfo.SEED_COUNT) { i ->
        VertexInfo(
            x = 10 + i,
            y = 20 + i * 2,
            bitmapWidth = 1080 + i,
            bitmapHeight = 2400 + i,
            pixelColor = 0xFF000000.toInt() or (i * 0x112233),
            // Descending, as the extractor produces them.
            population = 6000 - i * 1000
        )
    }

    private fun imageBytes(length: Int) = ByteArray(length) { (it * 31 + 7).toByte() }

    /** Overwrites the big-endian int at [byteOffset], the only way to forge a header by hand. */
    private fun patchInt(file: File, byteOffset: Long, value: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(byteOffset)
            raf.writeInt(value)
        }
    }

    private fun truncateTo(file: File, length: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(length) }
    }

    private fun assertSeedsEqual(expected: List<VertexInfo>, actual: List<VertexInfo>) {
        assertEquals("seed count", expected.size, actual.size)
        expected.forEachIndexed { i, seed -> assertEquals("seed $i", seed, actual[i]) }
    }

    // ---- Round-trip ----

    @Test
    fun `seeds round-trip field for field`() {
        val written = seeds()
        assertTrue(AtmosphereSource.write(dir, written, imageBytes(64)))

        val read = AtmosphereSource.read(dir)
        assertNotNull("a container just written should read back", read)
        assertSeedsEqual(written, read!!.seeds)
    }

    @Test
    fun `entry zero stays entry zero`() {
        // Order is the whole contract: entry 0 becomes the background, 1..5 the blobs. Handing the
        // list back sorted, reversed or stably-but-differently would paint the photo's dominant
        // mass over everything instead of under it.
        val written = seeds().toMutableList()
        written[0] = written[0].copy(pixelColor = 0xFFABCDEF.toInt())
        AtmosphereSource.write(dir, written, imageBytes(16))

        val read = AtmosphereSource.read(dir)!!
        assertEquals(written[0], read.seeds[0])
        assertEquals(0xFFABCDEF.toInt(), read.seeds[0].pixelColor)
    }

    @Test
    fun `write does not sort the seeds it is given`() {
        // The ordering rule is the producer's to honour; this object transcribes.
        val unsorted = seeds().reversed()
        AtmosphereSource.write(dir, unsorted, imageBytes(16))

        assertSeedsEqual(unsorted, AtmosphereSource.read(dir)!!.seeds)
    }

    @Test
    fun `image bytes round-trip byte-identical at awkward lengths`() {
        for (length in listOf(1, 7, 255, 1023, 4097)) {
            val fresh = temp.newFolder()
            val bytes = imageBytes(length)
            AtmosphereSource.write(fresh, seeds(), bytes)

            assertArrayEquals("length $length", bytes, AtmosphereSource.read(fresh)!!.imageBytes)
        }
    }

    @Test
    fun `the live and staged containers are independent files`() {
        val liveBytes = imageBytes(32)
        val stagedBytes = imageBytes(48)
        AtmosphereSource.write(dir, seeds(), liveBytes)
        AtmosphereSource.writePending(dir, seeds().reversed(), stagedBytes)

        assertArrayEquals(liveBytes, AtmosphereSource.read(dir)!!.imageBytes)
        assertArrayEquals(stagedBytes, AtmosphereSource.readPending(dir)!!.imageBytes)
        assertSeedsEqual(seeds(), AtmosphereSource.read(dir)!!.seeds)
    }

    // ---- Refusal, not failure ----
    // Every malformed container returns null. The contract is that the reader keeps rendering
    // whatever it already holds, which a thrown exception out of a boot-time read would not give.

    @Test
    fun `an absent container reads as null`() {
        assertNull(AtmosphereSource.read(dir))
        assertNull(AtmosphereSource.readPending(dir))
    }

    @Test
    fun `bad magic is refused`() {
        AtmosphereSource.write(dir, seeds(), imageBytes(64))
        patchInt(AtmosphereSource.file(dir), 0, 0x4E4F5045)

        assertNull(AtmosphereSource.read(dir))
    }

    @Test
    fun `an unknown version is refused`() {
        AtmosphereSource.write(dir, seeds(), imageBytes(64))
        patchInt(AtmosphereSource.file(dir), 4, 99)

        assertNull(AtmosphereSource.read(dir))
    }

    @Test
    fun `a wrong seed count reads as null rather than a short list`() {
        AtmosphereSource.write(dir, seeds(), imageBytes(64))
        patchInt(AtmosphereSource.file(dir), 8, VertexInfo.SEED_COUNT - 1)

        assertNull(AtmosphereSource.read(dir))
    }

    @Test
    fun `truncation at any point is refused rather than thrown`() {
        val full = AtmosphereSource.file(dir)
        AtmosphereSource.write(dir, seeds(), imageBytes(256))
        val fullLength = full.length()

        // Mid-header, mid-seed-table, mid-image, and one byte short of complete.
        for (length in listOf(2L, 10L, 40L, fullLength - 200, fullLength - 1)) {
            AtmosphereSource.write(dir, seeds(), imageBytes(256))
            truncateTo(full, length)

            assertNull("truncated to $length of $fullLength", AtmosphereSource.read(dir))
        }
    }

    // ---- The write/read asymmetry ----

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong seed count throws on write`() {
        // Deliberately not symmetric with the read side: a producer with the wrong count is a bug
        // in our own code, while the reader is defending against a file it did not write.
        AtmosphereSource.write(dir, seeds().dropLast(1), imageBytes(16))
    }

    @Test
    fun `an empty image writes but does not read back`() {
        assertTrue(AtmosphereSource.write(dir, seeds(), ByteArray(0)))
        assertTrue(AtmosphereSource.file(dir).exists())

        assertNull(AtmosphereSource.read(dir))
    }

    // ---- Durability ----

    @Test
    fun `a failed write leaves the previous container intact`() {
        val good = imageBytes(64)
        AtmosphereSource.write(dir, seeds(), good)

        // Occupy the temp path with a directory, so opening it as a stream fails partway through.
        val blocker = File(dir, "atmosphere_source.tmp")
        assertTrue(blocker.mkdir())
        assertTrue(File(blocker, "occupied").createNewFile())

        assertFalse(AtmosphereSource.write(dir, seeds(), imageBytes(128)))
        assertArrayEquals(good, AtmosphereSource.read(dir)!!.imageBytes)
    }

    @Test
    fun `no temp file survives a successful write`() {
        AtmosphereSource.write(dir, seeds(), imageBytes(64))
        AtmosphereSource.writePending(dir, seeds(), imageBytes(64))
        AtmosphereSource.promotePending(dir)

        assertFalse(File(dir, "atmosphere_source.tmp").exists())
    }

    // ---- Staging lifecycle ----

    @Test
    fun `promoting with nothing staged fails and leaves the live source alone`() {
        val live = imageBytes(64)
        AtmosphereSource.write(dir, seeds(), live)

        // How a rotation prepared for the other delivery mode reports itself, rather than
        // publishing something framed for the wrong surface.
        assertFalse(AtmosphereSource.promotePending(dir))
        assertArrayEquals(live, AtmosphereSource.read(dir)!!.imageBytes)
    }

    @Test
    fun `promotion copies the staged container rather than consuming it`() {
        val staged = imageBytes(96)
        AtmosphereSource.writePending(dir, seeds(), staged)

        assertTrue(AtmosphereSource.promotePending(dir))

        // Display is confirmed asynchronously and may never be confirmed at all. Consuming here
        // would leave an unconfirmed delivery with nothing staged and nothing able to re-stage it,
        // and every later rotation would fail the same way.
        assertArrayEquals("staged", staged, AtmosphereSource.readPending(dir)!!.imageBytes)
        assertArrayEquals("live", staged, AtmosphereSource.read(dir)!!.imageBytes)
    }

    @Test
    fun `promotion replaces the live container wholesale`() {
        AtmosphereSource.write(dir, seeds(), imageBytes(64))
        val newSeeds = seeds().map { it.copy(pixelColor = it.pixelColor.inv()) }
        val newBytes = imageBytes(96)
        AtmosphereSource.writePending(dir, newSeeds, newBytes)

        AtmosphereSource.promotePending(dir)

        val live = AtmosphereSource.read(dir)!!
        assertSeedsEqual(newSeeds, live.seeds)
        assertArrayEquals(newBytes, live.imageBytes)
    }

    @Test
    fun `clearing the staged container leaves the live one`() {
        val live = imageBytes(64)
        AtmosphereSource.write(dir, seeds(), live)
        AtmosphereSource.writePending(dir, seeds(), imageBytes(96))

        AtmosphereSource.clearPending(dir)

        assertNull(AtmosphereSource.readPending(dir))
        assertArrayEquals(live, AtmosphereSource.read(dir)!!.imageBytes)
    }

    @Test
    fun `clearing nothing is a no-op`() {
        AtmosphereSource.clearPending(dir)

        assertFalse(AtmosphereSource.pendingFile(dir).exists())
    }
}
