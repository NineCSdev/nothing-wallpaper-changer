package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
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
    private val requestRender: () -> Unit
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

    private val pendingSource = AtomicReference<Pair<List<VertexInfo>, Bitmap>?>(null)
    private val locked = AtomicBoolean(true)
    private val lockStateDirty = AtomicBoolean(false)

    /** Debug frame scrub. Below zero means "run normally". */
    private val pinnedFrame = AtomicInteger(-1)

    // GL objects

    private var compositeProgram = 0
    private var blurProgram = 0
    private var grainProgram = 0

    private var uCompositeSampler = 0
    private var uBgColor = 0
    private var uMixBlend = 0
    private var uCoverScale = 0

    private var uBlurSampler = 0
    private var uKernel = 0
    private var uBlurRadius = 0
    private var uBlurOffset = 0

    private var uGrainSampler = 0
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
    private var frame = AtmosphereConstants.SETTLED_FRAME
    private var animating = false
    private var lastFrameAt = 0L

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
     * The swap happens on entering the lock state, or immediately if already locked -- a window
     * during which the panel is dark, so it is never visible either way.
     */
    fun queueSource(newSeeds: List<VertexInfo>, bitmap: Bitmap) {
        pendingSource.getAndSet(newSeeds to bitmap)?.second?.recycle()
        if (locked.get()) requestRender()
    }

    /**
     * @param isLocked true on screen-off, false once the keyguard is dismissed. The false edge is
     * what starts a morph.
     */
    fun setLocked(isLocked: Boolean) {
        if (locked.getAndSet(isLocked) != isLocked) {
            lockStateDirty.set(true)
            requestRender()
        }
    }

    /** Debug only: hold at [frameNumber], or pass a negative to resume. */
    fun pinFrame(frameNumber: Int) {
        pinnedFrame.set(frameNumber)
        requestRender()
    }

    fun hasSource(): Boolean = photoTexture != 0

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

        uCompositeSampler = GLES30.glGetUniformLocation(compositeProgram, "s_TextureMap")
        uBgColor = GLES30.glGetUniformLocation(compositeProgram, "uBgColor")
        uMixBlend = GLES30.glGetUniformLocation(compositeProgram, "uMixBlend")
        uCoverScale = GLES30.glGetUniformLocation(compositeProgram, "uCoverScale")

        uBlurSampler = GLES30.glGetUniformLocation(blurProgram, "screenTexture")
        uKernel = GLES30.glGetUniformLocation(blurProgram, "uKernel")
        uBlurRadius = GLES30.glGetUniformLocation(blurProgram, "uBlurRadius")
        uBlurOffset = GLES30.glGetUniformLocation(blurProgram, "uBlurOffset")

        uGrainSampler = GLES30.glGetUniformLocation(grainProgram, "uSampler")
        uNoiseGrowth = GLES30.glGetUniformLocation(grainProgram, "uNoiseGrowth")
        uCounterScale = GLES30.glGetUniformLocation(grainProgram, "uCounterScale")

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
        surfaceWidth = width
        surfaceHeight = height
        prerollTargets.ensure(
            (width / AtmosphereConstants.PREROLL_DOWNSCALE).coerceAtLeast(1),
            (height / AtmosphereConstants.PREROLL_DOWNSCALE).coerceAtLeast(1)
        )
        effectTargets.ensure(
            AtmosphereConstants.EFFECT_RASTER_WIDTH,
            (AtmosphereConstants.EFFECT_RASTER_WIDTH * height / width.coerceAtLeast(1))
                .coerceAtLeast(1)
        )
        shapes.reset(width, height)
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

        val arg: Int
        if (!animating) {
            frame = AtmosphereConstants.SETTLED_FRAME
            arg = AtmosphereConstants.SETTLED_FRAME
        } else {
            arg = -1
        }

        if (arg < 0) drawFrame(frame, isInit = false) else drawFrame(arg, isInit = true)

        if (animating) {
            if (frame <= AtmosphereConstants.TOTAL_ANIM_FRAMES) {
                frame++
                requestRender()
            } else {
                animating = false
            }
        }
    }

    // Frame

    private fun drawFrame(frameNumber: Int, isInit: Boolean) {
        if (photoTexture == 0 || seeds.size != AtmosphereConstants.SEED_COUNT) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        if (locked.get()) {
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
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        drawComposite(mixBlendAt(frameNumber))
        shapes.draw(frameNumber, isInit, surfaceWidth, surfaceHeight)

        // Pass 3+4 -- one separable Gaussian over the composite. The blur runs over photo and
        // blobs together, never over the blobs alone.
        val blurRate = AtmosphereConstants.ANIM_EASE.getInterpolation(
            (frameNumber.coerceAtMost(AtmosphereConstants.BG_BLUR_GROWTH) /
                AtmosphereConstants.BG_BLUR_GROWTH.toFloat()).coerceIn(0f, 1f)
        )
        val rate = AtmosphereConstants.renderRate(surfaceWidth, 0)
        val radius = (rate * blurRate * AtmosphereConstants.MAX_BLUR_RADIUS).toInt()
        val offset = rate * blurRate * AtmosphereConstants.MAX_BLUR_OFFSET

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
        AtmosphereGl.bindTextureUnit0(targets.texA, uGrainSampler)
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
        AtmosphereGl.bindTextureUnit0(photoTexture, uCompositeSampler)
        GLES30.glUniform3f(uBgColor, bgColor[0], bgColor[1], bgColor[2])
        GLES30.glUniform1f(uMixBlend, mixBlend)
        updateCoverScale()
        GLES30.glUniform2f(uCoverScale, coverScale[0], coverScale[1])
        quad.draw()
    }

    private fun drawBlurPass(source: Int, radius: Int, offsetX: Float, offsetY: Float) {
        AtmosphereGl.bindTextureUnit0(source, uBlurSampler)
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

        java.util.Arrays.fill(kernel, 0f)
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

    private fun mixBlendAt(frameNumber: Int): Float {
        val span = (
            AtmosphereConstants.BG_TEXTURE_TRANSITION - AtmosphereConstants.FIRST_BLOB_FRAME
            ).toFloat()
        val t = (
            (frameNumber.coerceAtMost(AtmosphereConstants.BG_TEXTURE_TRANSITION) -
                AtmosphereConstants.FIRST_BLOB_FRAME) / span
            ).coerceIn(0f, 1f)
        return AtmosphereConstants.ANIM_EASE.getInterpolation(t)
    }

    /** The one window with a linear ramp; every other eases. */
    private fun noiseGrowthAt(frameNumber: Int): Float {
        val rate = AtmosphereConstants.renderRate(surfaceWidth, 1)
        val t = (frameNumber / AtmosphereConstants.NOISE_GROWTH.toFloat()).coerceIn(0f, 1f)
        return rate * AtmosphereConstants.MAX_NOISE * t
    }

    /**
     * Center-crop factors for the composite's excoriates.
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
        val lockChanged = lockStateDirty.getAndSet(false)
        if (lockChanged) {
            if (locked.get()) {
                frame = 0
                animating = false
                shapes.reset(surfaceWidth, surfaceHeight)
            } else {
                frame = 0
                animating = true
                lastFrameAt = 0L
            }
        }

        // Consumed on entering the lock state, or on arrival if already locked. Both parties key
        // off screen-off and their order is undefined, so waiting for the *edge* specifically
        // would push every rotation a full cycle late.
        if (locked.get()) {
            pendingSource.getAndSet(null)?.let { (newSeeds, bitmap) ->
                adoptSource(newSeeds, bitmap)
                frame = 0
                animating = false
            }
        }
    }

    private fun adoptSource(newSeeds: List<VertexInfo>, bitmap: Bitmap) {
        try {
            AtmosphereGl.deleteTexture(photoTexture)
            photoTexture = AtmosphereGl.uploadPhoto(bitmap)
            photoWidth = bitmap.width
            photoHeight = bitmap.height
            seeds = newSeeds

            // Entry 0 is the background the whole composite is pulled toward, not a blob.
            val background = newSeeds[0].pixelColor
            bgColor[0] = ((background shr 16) and 0xFF) / 255f
            bgColor[1] = ((background shr 8) and 0xFF) / 255f
            bgColor[2] = (background and 0xFF) / 255f

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
     * A failure here is never a reason to blank the screen -- whatever is already loaded stays.
     */
    private fun loadSourceFromDisk() {
        val payload = AtmosphereSource.read(context) ?: return
        val bitmap = android.graphics.BitmapFactory
            .decodeByteArray(payload.imageBytes, 0, payload.imageBytes.size)
        if (bitmap == null) {
            Log.w(TAG, "Source image failed to decode; keeping whatever is loaded")
            return
        }
        adoptSource(payload.seeds, bitmap)
    }

    fun release() {
        shapes.release()
        prerollTargets.release()
        effectTargets.release()
        AtmosphereGl.deleteTexture(photoTexture)
        photoTexture = 0
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        quad.release()
        pendingSource.getAndSet(null)?.second?.recycle()
    }
}
