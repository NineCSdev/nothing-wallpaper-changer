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
 * the panel.
 *
 * Two things about this are easy to mistake for bugs and are neither.
 *
 * From [AtmosphereConstants.FIRST_BLOB_FRAME] the entire effect is composited, rasterised and
 * blurred in a **72-pixel-wide** buffer, and the blobs appear with their one-off growth kick on
 * that same frame. The blur itself does *not* jump there: its offset is in surface pixels, so the
 * ramp is continuous across the resolution change and only the amount of detail in the buffer
 * changes.
 *
 * And on the lock screen this renders as the **plain photo**. The effect is a home-screen state;
 * the morph is what plays when the keyguard is dismissed.
 */
internal class AtmosphereRenderer(
    private val context: Context,
    private val requestRender: () -> Unit,
    /** Whether the panel is genuinely dark right now, so a swap cannot be seen happening. */
    private val isPanelDark: () -> Boolean = { true },
    /**
     * Fired on the **GL thread** the moment a queued source is committed to the screen, with the
     * flag it was queued under. That instant is what the rotation advance keys off.
     */
    private val onSourceAdopted: (fromRotation: Boolean) -> Unit = {},
    /**
     * Fired on the **GL thread** on each edge of the morph which is what the frame-rate vote
     * hanging off it needs: a missed falling edge leaves the panel pinned.
     */
    private val onMorphActive: (active: Boolean) -> Unit = {},
    /**
     * Fired on the **GL thread** when there is no source on disk to load, which without an answer
     * means a black panel for as long as the engine stays set. See [loadSourceFromDisk].
     */
    private val onSourceMissing: () -> Unit = {}
) : GLSurfaceView.Renderer {

    private companion object {
        const val TAG = "AtmosphereRenderer"
        const val SHADER_DIR = "shaders/atmosphere"

        /**
         * Compensation for the platform zooming the wallpaper surface for parallax. 1.0 is a
         * no-op.
         *
         * Whether this device does that to *live* wallpapers is the one open question in the
         * design -- it is documented for static ones, and this constant is deliberately the only
         * thing that would need to move if it turns out to apply here too. It lives on the final
         * pass so that no padded pixels ever reach the palette.
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

        /**
         * Drops the names without deleting them, for when the context that owned them is already
         * gone. Deleting them would be addressing the *new* context's name space.
         */
        fun forget() {
            texA = 0
            texB = 0
            width = 0
            height = 0
        }
    }

    // Hand-off from other threads

    /** A source waiting to be adopted, and whether showing it should advance the rotation. */
    private class PendingSource(
        val seeds: List<VertexInfo>,
        val bitmap: Bitmap,
        val fromRotation: Boolean
    )

    private val pendingSource = AtomicReference<PendingSource?>(null)
    private val locked = AtomicBoolean(true)
    private val lockStateDirty = AtomicBoolean(false)

    /** Whether the pending unlock should play the morph or land on the settled frame directly. */
    private val animateUnlock = AtomicBoolean(true)

    /** Set from off the GL thread to cut a morph short; consumed in [applyPendingWork]. */
    private val settleRequested = AtomicBoolean(false)

    /** Whether the morph replays instead of ending; see [startMorphLoop]. */
    private val loopMorph = AtomicBoolean(false)

    /** Set to rewind the replay to the photo; consumed in [applyPendingWork]. */
    private val loopRestart = AtomicBoolean(false)

    /** Debug frame scrub. Below zero means "run normally". */
    private val pinnedFrame = AtomicInteger(-1)

    // GL objects

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

    // Animation state

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /**
     * [AtmosphereConstants.renderRate] and its eased twin depend on the surface width alone, and
     * the eased one costs a `PathInterpolator` lookup. Sampled on resize instead of every frame.
     */
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

    // External control

    /**
     * Hands over a new photo and its seeds.
     *
     * It is *not* applied here. A morph derives its blob anchors, its palette and its background
     * from one image, so swapping mid-flight would leave blobs anchored to a photo that is gone.
     *
     * When it *is* applied depends on [fromRotation]. A rotation delivery is a reveal and waits for
     * [isPanelDark], so the change is never seen happening. Anything else is a user-initiated action
     * whose result should appear at once and is adopted on the next frame.
     */
    fun queueSource(newSeeds: List<VertexInfo>, bitmap: Bitmap, fromRotation: Boolean) {
        pendingSource
            .getAndSet(PendingSource(newSeeds, bitmap, fromRotation))
            ?.bitmap?.recycle()
    }

    /**
     * @param isLocked true while the plain photo should be shown, false for the effect.
     * @param animate whether a false edge plays the morph. Pass false to land on the settled frame
     * with no transition — see [animateUnlock] for when that is the right answer.
     */
    fun setLocked(isLocked: Boolean, animate: Boolean = true) {
        if (locked.getAndSet(isLocked) != isLocked) {
            animateUnlock.set(animate)
            lockStateDirty.set(true)
            requestRender()
        }
    }

    /**
     * Abandons any morph in flight and jumps to the settled frame. For when unlock lands on an
     * open app, and how a replay is cut short.
     */
    fun settleNow() {
        if (settleRequested.compareAndSet(false, true)) requestRender()
    }

    /**
     * Replays the morph for as long as it is on, holding
     * [AtmosphereConstants.LOOP_DWELL_FRAMES] on the settled effect between passes. Calling it
     * again rewinds the pass in flight.
     */
    fun startMorphLoop() {
        loopMorph.set(true)
        loopRestart.set(true)
        requestRender()
    }

    /**
     * Ends the replay. The pass in flight finishes rather than being cut mid-morph; [settleNow]
     * is what abandons it.
     */
    fun stopMorphLoop() {
        loopMorph.set(false)
        // A restart that never reached a frame would otherwise still be waiting in
        // [applyPendingWork], and would re-arm the morph the settle is about to end.
        loopRestart.set(false)
    }

    /** Debug only: hold at [frameNumber], or pass a negative to resume. */
    fun pinFrame(frameNumber: Int) {
        pinnedFrame.set(frameNumber)
        requestRender()
    }

    // GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A surface can come back with a brand-new context, in which case every name we are
        // holding belongs to a context that no longer exists. Drop them all before allocating,
        // or the first frame binds textures that were never created here.
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

        // A surface can be recreated without the source changing; reload so we never come back
        // to a blank screen.
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

        // Self-clocked, not display-driven: the loop sleeps to a fixed floor and re-arms itself,
        // so the morph lasts the same ~2.96 s whatever the panel's refresh rate is.
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

        // Not animating means the settled frame, drawn as a seek; animating means step the frame
        // we are already on.
        if (!animating) frame = AtmosphereConstants.SETTLED_FRAME
        drawFrame(frame, isInit = !animating)

        if (animating) {
            if (loopPhotoHold > 0) {
                // Still on the photo; the morph has not started stepping yet.
                loopPhotoHold--
                requestRender()
            } else if (frame <= AtmosphereConstants.TOTAL_ANIM_FRAMES) {
                frame++
                requestRender()
            } else if (loopMorph.get()) {
                // Past the end with the replay on: dwell on the settled effect, then rewind.
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

    // Frame

    private fun drawFrame(frameNumber: Int, isInit: Boolean) {
        if (photoTexture == 0 || seeds.size != VertexInfo.SEED_COUNT) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        // The replay's photo hold wants exactly what the lock screen wants: the sharp photo full resolution
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

        // Pass 1+2 -- the photo, pulled toward the background color, with the blobs over it.
        AtmosphereGl.bindFramebuffer(fbo, targets.texA, w, h)
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        drawComposite(mixBlendAt(frameNumber))
        shapes.draw(frameNumber, isInit, surfaceWidth, surfaceHeight)

        // Pass 3+4 -- one separable Gaussian over the composite. The blur runs over photo and
        // blobs together, never over the blobs alone.
        val blurRate = AtmosphereConstants.easedRamp(
            frameNumber, to = AtmosphereConstants.BG_BLUR_GROWTH
        )
        val radius = (linearRenderRate * blurRate * AtmosphereConstants.MAX_BLUR_RADIUS).toInt()
        val offset = linearRenderRate * blurRate * AtmosphereConstants.MAX_BLUR_OFFSET

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(blurProgram)
        uploadKernel(radius)

        // The offset is in 1080-panel pixels, like every other absolute constant here, so it is
        // normalized by the **surface** -- not by the render target it happens to be sampling.
        // That is not a detail: at full ramp, 14 surface pixels is 0.013 UV, which is 0.93 texels
        // of the 72-wide raster, giving the sigma of ~9 texels the effect is built on. Dividing by
        // the render target instead makes one tap 14 texels wide, puts the outermost tap 5.8
        // texture-widths off the edge, and clamps the entire frame to a flat wash.
        AtmosphereGl.bindFramebuffer(fbo, targets.texB, w, h)
        drawBlurPass(targets.texA, radius, offset / surfaceWidth, 0f)

        AtmosphereGl.bindFramebuffer(fbo, targets.texA, w, h)
        drawBlurPass(targets.texB, radius, 0f, offset / surfaceHeight)

        // Pass 5 -- grain, and the magnify back to the panel that turns flat polygons into
        // gradients.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glUseProgram(grainProgram)
        AtmosphereGl.bindTexture0(targets.texA)
        GLES30.glUniform1f(uNoiseGrowth, noiseGrowthAt(frameNumber))
        GLES30.glUniform1f(uCounterScale, SURFACE_COUNTER_SCALE)
        quad.draw()
    }

    /** No blur, no grain, no background mix -- the untouched photo. */
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
     * Gaussian weights with **sigma = radius / 3**, normalized two-sided so the center tap plus
     * twice the sum of the rest is 1.
     *
     * Re-sent only when the quantized radius moves, which is about thirty times across the ramp
     * rather than every frame. The array is always uploaded at its full declared length.
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

    /** The one window with a linear ramp; every other eases. */
    private fun noiseGrowthAt(frameNumber: Int): Float =
        easedRenderRate * AtmosphereConstants.MAX_NOISE *
        AtmosphereConstants.ramp(frameNumber, to = AtmosphereConstants.NOISE_GROWTH)

    /**
     * Center-crop factors for the composite's texture coordinates. They depend only on the photo
     * and the surface, so this is sampled when either changes rather than on every frame.
     */
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

    // Source handling

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
        // Evaluated only with a source in hand: isPanelDark crosses a binder for PowerManager, and
        // that is not something to pay on every frame of a morph.
        if (pending.fromRotation && !isPanelDark()) return
        if (!pendingSource.compareAndSet(pending, null)) return

        adoptSource(pending.seeds, pending.bitmap)
        // A new photo rewinds the replay rather than ending it
        if (loopMorph.get()) {
            restartMorph()
        } else {
            frame = 0
            setAnimating(false)
        }
        // The image is committed to the screen as of this frame
        onSourceAdopted(pending.fromRotation)
    }

    /**
     * Rewinds the replay to the photo and re-rolls the blob layout, so no two passes are the same.
     * GL thread only, from both ends of [applyPendingWork] and from [onDrawFrame].
     */
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

    private fun adoptSource(newSeeds: List<VertexInfo>, bitmap: Bitmap) {
        try {
            // Upload first, swap second in case upload throws so we keep a working wallpaper
            val newTexture = AtmosphereGl.uploadPhoto(bitmap)
            AtmosphereGl.deleteTexture(photoTexture)
            photoTexture = newTexture
            photoWidth = bitmap.width
            photoHeight = bitmap.height
            seeds = newSeeds
            updateCoverScale()

            // Entry 0 is the background the whole composite is pulled toward, not a blob.
            AtmosphereGl.unpackRgb(newSeeds[0].pixelColor, bgColor)

            shapes.setSeeds(newSeeds)
            shapes.reset(surfaceWidth, surfaceHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adopt new source; keeping the previous one", e)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Reads whatever is on disk. Called on surface creation, which includes the very first start
     * at boot with the app's own process absent.
     *
     * A failure here is never a reason to blank the screen - whatever is already loaded stays.
     *
     * Finding *nothing* is a different case, and the one this reports [onSourceMissing].
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
