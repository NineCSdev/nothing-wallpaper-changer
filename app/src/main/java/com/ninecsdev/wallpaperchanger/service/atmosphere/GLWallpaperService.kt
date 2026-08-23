package com.ninecsdev.wallpaperchanger.service.atmosphere

/*
 * Adapted from NOSAtmosphereEffect by Saad Ullah Khan
 * https://github.com/saad-khan-rind/NOSAtmosphereEffect (MIT License)
 * Substantially modified for Nothing Wallpaper Changer — see THIRD-PARTY-NOTICES.md.
 */

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper

abstract class GLWallpaperService : WallpaperService() {

    open inner class GLEngine : Engine() {
        private var glSurfaceView: WallpaperGLSurfaceView? = null
        private val pauseHandler = Handler(Looper.getMainLooper())
        private val pauseRunnable = Runnable { glSurfaceView?.onPause() }

        /** Grace period between going invisible and pausing the GL thread. */
        private val pauseDelayMs = 80L

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.OPAQUE)
            glSurfaceView = WallpaperGLSurfaceView(this@GLWallpaperService)
            // Offset notifications are deliberately NOT requested, and nothing here forwards
            // them. No renderer pans, so every launcher page swipe would fire onOffsetsChanged ->
            // requestRender and repaint a bit-identical frame — the whole atmosphere pipeline
            // (blob field, its blur, and the composite) for nothing. Enable this together with a
            // renderer that actually uses the offset.
        }

        fun setRenderer(renderer: GLSurfaceView.Renderer) {
            glSurfaceView?.setRenderer(renderer)
            glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        fun requestRender() {
            glSurfaceView?.requestRender()
        }

        /**
         * Draws one frame even when the GL thread was already paused after going invisible, then
         * re-arms the pause.
         *
         * Without this, work queued while the screen is off (a delivered wallpaper waiting to be
         * uploaded) sits until [onVisibilityChanged] resumes the thread — so the whole upload
         * lands on the *first visible frame* and stalls the wake. Rendering it here keeps that cost
         * in the dark. Safe to call while paused: the engine's surface outlives visibility, and
         * `preserveEGLContextOnPause` keeps the context across the pause/resume.
         */
        fun renderPendingWork() {
            pauseHandler.removeCallbacks(pauseRunnable)
            glSurfaceView?.onResume()
            glSurfaceView?.requestRender()
            if (!isVisible) pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                pauseHandler.removeCallbacks(pauseRunnable)
                glSurfaceView?.onResume()
            } else {
                // Draw one more frame in the renderer's current state, then pause a few
                // frames later instead of immediately. Pausing the GL thread the instant
                // we go invisible leaves a stale frame latched in the surface, which the
                // compositor flashes on the next wake. Present a fresh frame first.
                glSurfaceView?.requestRender()
                pauseHandler.removeCallbacks(pauseRunnable)
                pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            pauseHandler.removeCallbacks(pauseRunnable)
            glSurfaceView?.onPause()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            glSurfaceView?.surfaceChanged(holder, format, width, height)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            glSurfaceView?.surfaceCreated(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            glSurfaceView?.surfaceDestroyed(holder)
        }

        inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            init {
                setEGLConfigChooser(8, 8, 8, 0, 16, 0)
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
            }

            override fun getHolder(): SurfaceHolder {
                return this@GLEngine.surfaceHolder
            }
        }
    }

    /**
     * Builds a **fresh** renderer. Called once per engine (each engine owns its own GL context, so
     * renderers are never shared between them) — this is a factory, not an accessor. Subclasses may
     * narrow the return type so their engine can hold the concrete renderer without a cast.
     */
    abstract fun createRenderer(): GLSurfaceView.Renderer
}
