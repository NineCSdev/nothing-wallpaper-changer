package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.opengl.GLES30
import android.opengl.Matrix
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The blob layer: five flat-filled shapes that appear at [AtmosphereConstants.FIRST_BLOB_FRAME],
 * grow, and drift across the screen. Each blob starts where its color lives in the photo and
 * travels to a random target re-rolled on every lock.
 */
internal class ShapeCreator {

    private companion object {
        /** Seeded once per process and only advanced, so sizes and path shape evolve slowly while the layout jumps on every lock. */
        val PROCESS_RANDOM = Random(System.currentTimeMillis() / 1000L)

        const val FLOATS_PER_POSITION = 2
    }

    private val outlines = Array(AtmosphereConstants.BLOB_COUNT) { BlobOutline() }

    /** Drift destinations in surface pixels, (x, y) pairs. */
    private val targets = FloatArray(AtmosphereConstants.BLOB_COUNT * 2)

    /** Per blob, per anchor, quantized to {2, 4, 6}. */
    private val steps = Array(AtmosphereConstants.BLOB_COUNT) {
        FloatArray(AtmosphereConstants.ANCHOR_COUNT)
    }

    private val baseAnchors = FloatArray(AtmosphereConstants.ANCHOR_COUNT * 2)

    /** 0 straight, 1 and 2 the two semicircular arcs. Shared by all blobs. */
    private var rotationMode = 0

    private var seeds: List<VertexInfo> = emptyList()
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var program = 0
    private var uMvpMatrix = 0
    private var uAlpha = 0
    private var uColor = 0
    private var vbo = 0

    private val positions = FloatArray(BlobOutline.MAX_STRIP_VERTICES * FLOATS_PER_POSITION)
    private val positionBuffer = AtmosphereGl.newFloatBuffer(positions.size)

    /** Reused by [positionAt] to avoid per-frame allocation. */
    private val blobPosition = FloatArray(2)

    private val mvp = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)

    private val positionBytes = positions.size * AtmosphereGl.BYTES_PER_FLOAT

    val isReady: Boolean get() = program != 0 && seeds.size == VertexInfo.SEED_COUNT

    fun onSurfaceCreated(vertexSource: String, fragmentSource: String) {
        program = AtmosphereGl.buildProgram(vertexSource, fragmentSource, "blob")
        if (program == 0) return
        uMvpMatrix = GLES30.glGetUniformLocation(program, "uMVPMatrix")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uColor = GLES30.glGetUniformLocation(program, "uColor")

        // Sized once at the worst case and only sub-loaded afterwards, so frames never reallocate.
        vbo = AtmosphereGl.createBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, positionBytes, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        AtmosphereGl.deleteBuffer(vbo)
        vbo = 0
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }

    fun setSeeds(newSeeds: List<VertexInfo>) {
        seeds = newSeeds
    }

    /** Re-rolls everything random and rewinds every outline to its base pentagon; called on entering the lock state. */
    fun reset(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (seeds.size != VertexInfo.SEED_COUNT) return

        // Re-seeded from the wall clock on every call, unlike PROCESS_RANDOM.
        val targetRandom = Random(System.currentTimeMillis() / 1000L)
        BlobPlacement.generate(width, height, AtmosphereConstants.BLOB_COUNT, targetRandom, targets)

        rotationMode = PROCESS_RANDOM.nextInt(3)

        val rate = AtmosphereConstants.renderRate(width)
        fillBaseAnchors(rate)
        for (s in 0 until AtmosphereConstants.BLOB_COUNT) {
            for (j in 0 until AtmosphereConstants.ANCHOR_COUNT) {
                steps[s][j] = ((PROCESS_RANDOM.nextInt(3) * 2 + 2).toFloat()) * rate
            }
            // Seed 0 is the background color; the blobs are 1..5.
            outlines[s].reset(baseAnchors, steps[s], seeds[s + 1].pixelColor)
        }
    }

    /** The base pentagon scaled about its centroid by the render rate. */
    private fun fillBaseAnchors(rate: Float) {
        for (i in 0 until AtmosphereConstants.ANCHOR_COUNT) {
            val dx = AtmosphereConstants.DEFAULT_VERTICES[i * 2] - AtmosphereConstants.DEFAULT_CENTROID_X
            val dy = AtmosphereConstants.DEFAULT_VERTICES[i * 2 + 1] - AtmosphereConstants.DEFAULT_CENTROID_Y
            baseAnchors[i * 2] = AtmosphereConstants.DEFAULT_CENTROID_X + dx * rate
            baseAnchors[i * 2 + 1] = AtmosphereConstants.DEFAULT_CENTROID_Y + dy * rate
        }
    }

    /**
     * Blends the blobs over the bound framebuffer; draws nothing below
     * [AtmosphereConstants.FIRST_BLOB_FRAME]. [isInit] seeks by replaying growth from pristine anchors.
     */
    fun draw(frame: Int, isInit: Boolean, width: Int, height: Int) {
        if (frame < AtmosphereConstants.FIRST_BLOB_FRAME) return
        if (!isReady) return
        if (width != surfaceWidth || height != surfaceHeight) return

        val travel = travelProgress(frame)
        val eased = AtmosphereConstants.ANIM_EASE.getInterpolation(travel)

        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uAlpha, alphaAt(frame))

        Matrix.orthoM(projection, 0, 0f, width.toFloat(), 0f, height.toFloat(), -1f, 1f)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, FLOATS_PER_POSITION, GLES30.GL_FLOAT, false, 0, 0)

        for (s in 0 until AtmosphereConstants.BLOB_COUNT) {
            drawBlob(s, frame, isInit, travel, eased, width, height)
        }

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawBlob(
        index: Int,
        frame: Int,
        isInit: Boolean,
        travel: Float,
        easedTravel: Float,
        width: Int,
        height: Int
    ) {
        val outline = outlines[index]
        outline.advanceTo(frame, isInit)

        val vertexCount = outline.tessellate(travel, positions)
        if (vertexCount < 3) return

        positionBuffer.position(0)
        positionBuffer.put(positions, 0, vertexCount * FLOATS_PER_POSITION)
        positionBuffer.position(0)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, 0,
            vertexCount * FLOATS_PER_POSITION * AtmosphereGl.BYTES_PER_FLOAT, positionBuffer
        )

        GLES30.glUniform3fv(uColor, 1, outline.color, 0)
        applyMvp(index, easedTravel, width, height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, vertexCount)
    }

    private fun applyMvp(index: Int, easedTravel: Float, width: Int, height: Int) {
        val seed = seeds[index + 1]
        val anchorX = (seed.x.toFloat() / seed.bitmapWidth) * width
        // Y is flipped into GL's origin: the photo's top row is the screen's top.
        val anchorY = (1f - seed.y.toFloat() / seed.bitmapHeight) * height

        val targetX = targets[index * 2]
        val targetY = targets[index * 2 + 1]

        positionAt(anchorX, anchorY, targetX, targetY, easedTravel)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model, 0,
            blobPosition[0] - AtmosphereConstants.DEFAULT_CENTROID_X,
            blobPosition[1] - AtmosphereConstants.DEFAULT_CENTROID_Y,
            0f
        )

        Matrix.multiplyMM(mvp, 0, projection, 0, model, 0)
        GLES30.glUniformMatrix4fv(uMvpMatrix, 1, false, mvp, 0)
    }

    /** Where the blob is at [progress] from anchor to target; all three modes end exactly on the target. */
    private fun positionAt(
        anchorX: Float,
        anchorY: Float,
        targetX: Float,
        targetY: Float,
        progress: Float
    ) {
        val dx = targetX - anchorX
        val dy = targetY - anchorY

        if (rotationMode == 0) {
            blobPosition[0] = anchorX + dx * progress
            blobPosition[1] = anchorY + dy * progress
            return
        }

        if (sqrt(dx * dx + dy * dy) == 0f) {
            blobPosition[0] = anchorX
            blobPosition[1] = anchorY
            return
        }

        // A half turn about the midpoint (radius half the distance) lands exactly on the target.
        val midX = (anchorX + targetX) * 0.5f
        val midY = (anchorY + targetY) * 0.5f
        val armX = anchorX - midX
        val armY = anchorY - midY

        val sweep = if (rotationMode == 1) -Math.PI.toFloat() else Math.PI.toFloat()
        val angle = sweep * progress
        val c = cos(angle)
        val s = sin(angle)

        blobPosition[0] = midX + armX * c - armY * s
        blobPosition[1] = midY + armX * s + armY * c
    }

    /** Linear 0..1; the caller applies the ease. */
    private fun travelProgress(frame: Int): Float = AtmosphereConstants.ramp(
        frame,
        from = AtmosphereConstants.FIRST_BLOB_FRAME,
        to = AtmosphereConstants.TOTAL_ANIM_FRAMES
    )

    private fun alphaAt(frame: Int): Float = AtmosphereConstants.easedRamp(
        frame,
        from = AtmosphereConstants.FIRST_BLOB_FRAME,
        to = AtmosphereConstants.RANDOMIZE_SHAPES_TRANSITION
    )
}
