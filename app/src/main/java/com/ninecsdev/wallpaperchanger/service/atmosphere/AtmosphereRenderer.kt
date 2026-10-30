package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereSource
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The whole visual pipeline: photo -> blobs over it -> blur both together -> grain, magnified to
 * the panel. On the lock screen it renders the plain photo; the morph plays when the keyguard is
 * dismissed.
 *
 * From [AtmosphereConstants.FIRST_BLOB_FRAME] the effect runs in a 72-pixel-wide buffer. The blur
 * does not jump there: its offset is in surface pixels, so the ramp is continuous across the
 * resolution change.
 */
internal class AtmosphereRenderer(
    private val context: Context,
    private val requestRender: () -> Unit,
    /** Whether the panel is genuinely dark right now, so a swap cannot be seen happening. */
    private val isPanelDark: () -> Boolean = { true },
    /** Fired on the GL thread when a queued source is committed to the screen; the rotation advance keys off it. */
    private val onSourceAdopted: (fromRotation: Boolean) -> Unit = {},
    /** Fired on the GL thread on each edge of the morph; a missed falling edge leaves the frame-rate vote pinned. */
    private val onMorphActive: (active: Boolean) -> Unit = {},
    /** Fired on the GL thread when there is no source on disk; without an answer the panel stays black. */
    private val onSourceMissing: () -> Unit = {}
) : GLSurfaceView.Renderer {

    private companion object {
        const val TAG = "AtmosphereRenderer"
        const val SHADER_DIR = "shaders/atmosphere"

        /**
         * Compensation for the platform zooming the wallpaper surface for parallax; 1.0 is a no-op.
         * Applied in the final pass so no padded pixels reach the palette.
         */
        const val SURFACE_COUNTER_SCALE = 1.0f
    }

    /** A ping-pong pair of color attachments at one working resolution. */
    private class Targets {
        var texA = 0
        var texB = 0
        var width = 0
        var height = 0

        fun ensure(w: Int, h: Int) {
            if (width == w && height == h && texA != 0) return
            release()
            width = w
            height = h
            texA = AtmosphereGl.createFboTexture(w, h)
            texB = AtmosphereGl.createFboTexture(w, h)
        }

        fun release() {
            AtmosphereGl.deleteTexture(texA)
            AtmosphereGl.deleteTexture(texB)
            forget()
        }

        /** Drops the names without deleting them: their context is gone, and deleting would hit the new one's names. */
        fun forget() {
            texA = 0
            texB = 0
            width = 0
            height = 0
        }
    }

    /** A source waiting to be adopted, and whether showing it should advance the rotation. */
    private class PendingSource(
        val seeds: List<VertexInfo>,
        val bitmap: Bitmap,
        val fromRotation: Boolean
    )

    // Written off the GL thread, consumed in applyPendingWork.
    private val pendingSource = AtomicReference<PendingSource?>(null)
    private val locked = AtomicBoolean(true)
    private val lockStateDirty = AtomicBoolean(false)
    private val animateUnlock = AtomicBoolean(true)
    private val settleRequested = AtomicBoolean(false)
    private val loopMorph = AtomicBoolean(false)
    private val loopRestart = AtomicBoolean(false)

    /** Debug frame scrub. Below zero means "run normally". */
    private val pinnedFrame = AtomicInteger(-1)

    private var compositeProgram = 0
    private var blurProgram = 0
    private var grainProgram = 0

    private var uBgColor = 0
    private var uMixBlend = 0
    private var uCoverScale = 0

    private var uKernel = 0
    private var uBlurRadius = 0
    private var uBlurOffset = 0

    private var uNoiseGrowth = 0
    private var uCounterScale = 0

    private var fbo = 0
    private var photoTexture = 0
    private var photoWidth = 0
    private var photoHeight = 0

    private val quad = AtmosphereGl.FullScreenQuad()
    private val prerollTargets = Targets()
    private val effectTargets = Targets()
    private val shapes = ShapeCreator()

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // Depend on the surface width alone, so they are sampled on resize rather than every frame.
    private var linearRenderRate = 1f
    private var easedRenderRate = 1f
    private var frame = AtmosphereConstants.SETTLED_FRAME

    /** Only ever assigned through [setAnimating], so no edge of the morph goes unreported. */
    private var animating = false
    private var lastFrameAt = 0L

    /** Frames held on the settled effect so far in this pass of the replay. GL thread only. */
    private var loopDwell = 0

    /** Frames of the photo still owed before this pass of the replay morphs. GL thread only. */
    private var loopPhotoHold = 0

    private var seeds: List<VertexInfo> = emptyList()
    private val bgColor = FloatArray(3)

    private val coverScale = FloatArray(2)
    private val kernel = FloatArray(AtmosphereConstants.BLUR_RADIUS_LIMIT)
    private var uploadedKernelRadius = -1

    /**
     * Hands over a new photo and its seeds; applied on the GL thread, not here, because a morph
     * derives its anchors, palette and background from one image. A rotation delivery
     * ([fromRotation]) waits for [isPanelDark]; anything else is adopted on the next frame.
     */
    fun queueSource(newSeeds: List<VertexInfo>, bitmap: Bitmap, fromRotation: Boolean) {
        pendingSource
            .getAndSet(PendingSource(newSeeds, bitmap, fromRotation))
            ?.bitmap?.recycle()
    }

    /** [isLocked] shows the plain photo; unlocking plays the morph unless [animate] is false (lands settled). */
    fun setLocked(isLocked: Boolean, animate: Boolean = true) {
        if (locked.getAndSet(isLocked) != isLocked) {
            animateUnlock.set(animate)
            lockStateDirty.set(true)
            requestRender()
        }
    }

    /** Abandons any morph in flight and jumps to the settled frame. */
    fun settleNow() {
        if (settleRequested.compareAndSet(false, true)) requestRender()
    }

    /** Replays the morph until [stopMorphLoop]; calling it again rewinds the pass in flight. */
    fun startMorphLoop() {
        loopMorph.set(true)
        loopRestart.set(true)
        requestRender()
    }

    /** Ends the replay; the pass in flight finishes ([settleNow] abandons it). */
    fun stopMorphLoop() {
        loopMorph.set(false)
        // A restart not yet consumed would otherwise re-arm the morph the settle is about to end.
        loopRestart.set(false)
    }

    /** Debug only: hold at [frameNumber], or pass a negative to resume. */
    fun pinFrame(frameNumber: Int) {
        pinnedFrame.set(frameNumber)
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A surface can come back with a brand-new context; drop every held GL name before allocating.
        forgetContextObjects()

        quad.create()
        fbo = AtmosphereGl.createFramebuffer()

        val quadVert = AtmosphereGl.readAsset(context, "$SHADER_DIR/quad.vert")
        compositeProgram = AtmosphereGl.buildProgram(
            quadVert, AtmosphereGl.readAsset(context, "$SHADER_DIR/composite.frag"), "composite"
        )
        blurProgram = AtmosphereGl.buildProgram(
            quadVert, AtmosphereGl.readAsset(context, "$SHADER_DIR/blur.frag"), "blur"
        )
        grainProgram = AtmosphereGl.buildProgram(
            quadVert, AtmosphereGl.readAsset(context, "$SHADER_DIR/grain.frag"), "grain"
        )
        shapes.onSurfaceCreated(
            AtmosphereGl.readAsset(context, "$SHADER_DIR/blob.vert"),
            AtmosphereGl.readAsset(context, "$SHADER_DIR/blob.frag")
        )

        uBgColor = GLES30.glGetUniformLocation(compositeProgram, "uBgColor")
        uMixBlend = GLES30.glGetUniformLocation(compositeProgram, "uMixBlend")
        uCoverScale = GLES30.glGetUniformLocation(compositeProgram, "uCoverScale")

        uKernel = GLES30.glGetUniformLocation(blurProgram, "uKernel")
        uBlurRadius = GLES30.glGetUniformLocation(blurProgram, "uBlurRadius")
        uBlurOffset = GLES30.glGetUniformLocation(blurProgram, "uBlurOffset")

        uNoiseGrowth = GLES30.glGetUniformLocation(grainProgram, "uNoiseGrowth")
        uCounterScale = GLES30.glGetUniformLocation(grainProgram, "uCounterScale")

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        AtmosphereGl.bindSamplerToUnit0(compositeProgram, "s_TextureMap")
        AtmosphereGl.bindSamplerToUnit0(blurProgram, "screenTexture")
        AtmosphereGl.bindSamplerToUnit0(grainProgram, "uSampler")

        // The source may be unchanged across a surface recreation; reload so the screen is never blank.
        loadSourceFromDisk()
    }

    private fun forgetContextObjects() {
        photoTexture = 0
        photoWidth = 0
        photoHeight = 0
        fbo = 0
        prerollTargets.forget()
        effectTargets.forget()
        // The kernel is program state, and the program is about to be rebuilt.
        uploadedKernelRadius = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val dimensionsChanged = width != surfaceWidth || height != surfaceHeight
        surfaceWidth = width
        surfaceHeight = height
        linearRenderRate = AtmosphereConstants.renderRate(width)
        easedRenderRate = AtmosphereConstants.easedRenderRate(width)
        updateCoverScale()
        prerollTargets.ensure(
            (width / AtmosphereConstants.PREROLL_DOWNSCALE).coerceAtLeast(1),
            (height / AtmosphereConstants.PREROLL_DOWNSCALE).coerceAtLeast(1)
        )
        effectTargets.ensure(
            AtmosphereConstants.EFFECT_RASTER_WIDTH,
            (AtmosphereConstants.EFFECT_RASTER_WIDTH * height / width.coerceAtLeast(1))
                .coerceAtLeast(1)
        )
        if (dimensionsChanged) shapes.reset(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        applyPendingWork()

        // Self-clocked, not display-driven: the morph lasts ~2.96 s whatever the refresh rate.
        val elapsed = SystemClock.elapsedRealtime() - lastFrameAt
        if (elapsed < AtmosphereConstants.FRAME_INTERVAL_MS) {
            SystemClock.sleep(AtmosphereConstants.FRAME_INTERVAL_MS - elapsed)
        }
        lastFrameAt = SystemClock.elapsedRealtime()

        val pinned = pinnedFrame.get()
        if (pinned >= 0) {
            drawFrame(pinned, isInit = true)
            return
        }

        // Not animating draws the settled frame as a seek.
        if (!animating) frame = AtmosphereConstants.SETTLED_FRAME
        drawFrame(frame, isInit = !animating)

        if (animating) {
            if (loopPhotoHold > 0) {
                loopPhotoHold--
                requestRender()
            } else if (frame <= AtmosphereConstants.TOTAL_ANIM_FRAMES) {
                frame++
                requestRender()
            } else if (loopMorph.get()) {
                if (loopDwell < AtmosphereConstants.LOOP_DWELL_FRAMES) {
                    loopDwell++
                    requestRender()
                } else {
                    restartMorph()
                }
            } else {
                setAnimating(false)
            }
        }
    }

    private fun drawFrame(frameNumber: Int, isInit: Boolean) {
        if (photoTexture == 0 || seeds.size != VertexInfo.SEED_COUNT) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        // The replay's photo hold wants what the lock screen wants: the sharp full-resolution photo.
        if (locked.get() || loopPhotoHold > 0) {
            drawLockScreen()
            return
        }

        val targets = if (frameNumber < AtmosphereConstants.FIRST_BLOB_FRAME) {
            prerollTargets
        } else {
            effectTargets
        }
        val w = targets.width
        val h = targets.height

        AtmosphereGl.bindFramebuffer(fbo, targets.texA, w, h)
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        drawComposite(mixBlendAt(frameNumber))
        shapes.draw(frameNumber, isInit, surfaceWidth, surfaceHeight)

        // The blur runs over photo and blobs together, never the blobs alone.
        val blurRate = AtmosphereConstants.easedRamp(frameNumber, to = AtmosphereConstants.BG_BLUR_GROWTH)
        val radius = (linearRenderRate * blurRate * AtmosphereConstants.MAX_BLUR_RADIUS).toInt()
        val offset = linearRenderRate * blurRate * AtmosphereConstants.MAX_BLUR_OFFSET

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(blurProgram)
        uploadKernel(radius)

        // The offset is in panel pixels, so normalize by the surface, not the render target:
        // dividing by the target makes one tap 14 texels wide and clamps the frame to a flat wash.
        AtmosphereGl.bindFramebuffer(fbo, targets.texB, w, h)
        drawBlurPass(targets.texA, radius, offset / surfaceWidth, 0f)

        AtmosphereGl.bindFramebuffer(fbo, targets.texA, w, h)
        drawBlurPass(targets.texB, radius, 0f, offset / surfaceHeight)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glUseProgram(grainProgram)
        AtmosphereGl.bindTexture0(targets.texA)
        GLES30.glUniform1f(uNoiseGrowth, noiseGrowthAt(frameNumber))
        GLES30.glUniform1f(uCounterScale, SURFACE_COUNTER_SCALE)
        quad.draw()
    }

    /** The untouched photo: no blur, grain or background mix. */
    private fun drawLockScreen() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawComposite(0f)
    }

    private fun drawComposite(mixBlend: Float) {
        GLES30.glUseProgram(compositeProgram)
        AtmosphereGl.bindTexture0(photoTexture)
        GLES30.glUniform3f(uBgColor, bgColor[0], bgColor[1], bgColor[2])
        GLES30.glUniform1f(uMixBlend, mixBlend)
        GLES30.glUniform2f(uCoverScale, coverScale[0], coverScale[1])
        quad.draw()
    }

    private fun drawBlurPass(source: Int, radius: Int, offsetX: Float, offsetY: Float) {
        AtmosphereGl.bindTexture0(source)
        GLES30.glUniform1i(uBlurRadius, radius)
        GLES30.glUniform2f(uBlurOffset, offsetX, offsetY)
        quad.draw()
    }

    /**
     * Gaussian weights with sigma = radius / 3, normalized two-sided (center tap plus twice the
     * rest is 1). Re-sent only when the radius changes, always at the full declared length.
     */
    private fun uploadKernel(radius: Int) {
        if (radius == uploadedKernelRadius) return
        uploadedKernelRadius = radius

        kernel.fill(0f)
        if (radius > 0) {
            val sigma = radius / 3f
            val norm = 1f / sqrt(2f * Math.PI.toFloat() * sigma * sigma)
            val twoSigmaSquared = 2f * sigma * sigma
            kernel[0] = norm
            var sum = kernel[0]
            for (i in 1 until radius.coerceAtMost(AtmosphereConstants.BLUR_RADIUS_LIMIT)) {
                kernel[i] = norm * exp(-(i * i) / twoSigmaSquared)
                sum += 2f * kernel[i]
            }
            if (sum > 0f) {
                for (i in 0 until radius.coerceAtMost(AtmosphereConstants.BLUR_RADIUS_LIMIT)) {
                    kernel[i] /= sum
                }
            }
        }
        GLES30.glUniform1fv(uKernel, AtmosphereConstants.BLUR_RADIUS_LIMIT, kernel, 0)
    }

    private fun mixBlendAt(frameNumber: Int): Float = AtmosphereConstants.easedRamp(
        frameNumber,
        from = AtmosphereConstants.FIRST_BLOB_FRAME,
        to = AtmosphereConstants.BG_TEXTURE_TRANSITION
    )

    private fun noiseGrowthAt(frameNumber: Int): Float =
        easedRenderRate * AtmosphereConstants.MAX_NOISE *
        AtmosphereConstants.ramp(frameNumber, to = AtmosphereConstants.NOISE_GROWTH)

    /** Center-crop factors for the composite's texture coordinates; recomputed when photo or surface changes. */
    private fun updateCoverScale() {
        coverScale[0] = 1f
        coverScale[1] = 1f
        if (photoWidth <= 0 || photoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return

        val photoAspect = photoWidth.toFloat() / photoHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        if (surfaceAspect > photoAspect) {
            coverScale[1] = photoAspect / surfaceAspect
        } else {
            coverScale[0] = surfaceAspect / photoAspect
        }
    }

    private fun applyPendingWork() {
        if (settleRequested.getAndSet(false)) {
            loopPhotoHold = 0
            setAnimating(false)
        }

        if (lockStateDirty.getAndSet(false)) {
            loopPhotoHold = 0
            if (locked.get()) {
                frame = 0
                setAnimating(false)
                shapes.reset(surfaceWidth, surfaceHeight)
            } else if (animateUnlock.get()) {
                frame = 0
                setAnimating(true)
                lastFrameAt = 0L
            } else {
                setAnimating(false)
            }
        }

        if (loopRestart.getAndSet(false)) restartMorph()

        val pending = pendingSource.get() ?: return
        // Only with a source in hand: isPanelDark crosses a binder, too costly for every frame.
        if (pending.fromRotation && !isPanelDark()) return
        if (!pendingSource.compareAndSet(pending, null)) return

        val adopted = adoptSource(pending.seeds, pending.bitmap)
        if (loopMorph.get()) {
            restartMorph()
        } else {
            frame = 0
            setAnimating(false)
        }
        // Report only a source that was really taken.
        if (adopted) onSourceAdopted(pending.fromRotation)
    }

    /** Rewinds the replay to the photo and re-rolls the blob layout. GL thread only. */
    private fun restartMorph() {
        frame = 0
        loopDwell = 0
        loopPhotoHold = AtmosphereConstants.LOOP_PHOTO_HOLD_FRAMES
        lastFrameAt = 0L
        shapes.reset(surfaceWidth, surfaceHeight)
        setAnimating(true)
        requestRender()
    }

    /** Reports [onMorphActive] once per real transition; a repeated assignment is not an edge. */
    private fun setAnimating(value: Boolean) {
        if (animating == value) return
        animating = value
        onMorphActive(value)
    }

    /** Binds [bitmap] and [newSeeds] as the photo drawn; false means the previous photo is still on screen. */
    private fun adoptSource(newSeeds: List<VertexInfo>, bitmap: Bitmap): Boolean {
        return try {
            // Upload first, swap second: if the upload throws, the previous wallpaper stays.
            val newTexture = AtmosphereGl.uploadPhoto(bitmap)
            AtmosphereGl.deleteTexture(photoTexture)
            photoTexture = newTexture
            photoWidth = bitmap.width
            photoHeight = bitmap.height
            seeds = newSeeds
            updateCoverScale()

            AtmosphereGl.unpackRgb(newSeeds[0].pixelColor, bgColor)

            shapes.setSeeds(newSeeds)
            shapes.reset(surfaceWidth, surfaceHeight)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adopt new source; keeping the previous one", e)
            false
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Reads whatever is on disk (the app's own process may be absent at boot). A failure keeps
     * what is already loaded; finding nothing reports [onSourceMissing].
     */
    private fun loadSourceFromDisk() {
        val decoded = AtmosphereSource.readDecoded(context.filesDir)
        if (decoded == null) {
            Log.w(TAG, "No source on disk; asking for one")
            onSourceMissing()
            return
        }
        adoptSource(decoded.seeds, decoded.bitmap)
    }

    fun release() {
        shapes.release()
        prerollTargets.release()
        effectTargets.release()
        AtmosphereGl.deleteTexture(photoTexture)
        photoTexture = 0
        AtmosphereGl.deleteFramebuffer(fbo)
        fbo = 0
        quad.release()
        pendingSource.getAndSet(null)?.bitmap?.recycle()
    }
}
