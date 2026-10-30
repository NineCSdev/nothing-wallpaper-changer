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
            // Offset notifications are deliberately not requested: no renderer pans, so every page
            // swipe would repaint a bit-identical frame.
        }

        /**
         * Attaches [renderer] and pins dirty-mode rendering: renderers re-arm themselves with
         * [requestRender] while animating, so a continuous loop would redraw identical frames forever.
         */
        fun setRenderer(renderer: GLSurfaceView.Renderer) {
            glSurfaceView?.setRenderer(renderer)
            glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        /**
         * Runs [action] on the GL thread. Best-effort: the thread checks its exit flag before
         * draining the queue, so anything queued after the exit request may never run.
         */
        protected fun queueGlEvent(action: Runnable) {
            glSurfaceView?.queueEvent(action)
        }

        fun requestRender() {
            glSurfaceView?.requestRender()
        }

        /**
         * Draws one frame even when the GL thread is paused, then re-arms the pause. Keeps work
         * queued while the screen is off (a delivered wallpaper's upload) off the first visible frame.
         */
        fun renderPendingWork() {
            pauseHandler.removeCallbacks(pauseRunnable)
            glSurfaceView?.onResume()
            renderThenReArmPause()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                pauseHandler.removeCallbacks(pauseRunnable)
                glSurfaceView?.onResume()
            } else {
                // Pausing the instant we go invisible leaves a stale frame latched in the surface
                // that the compositor flashes on wake; present a fresh one first.
                renderThenReArmPause()
            }
        }

        /** Presents one frame and, while nobody is looking, re-arms the deferred pause. */
        private fun renderThenReArmPause() {
            glSurfaceView?.requestRender()
            pauseHandler.removeCallbacks(pauseRunnable)
            if (!isVisible) pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
        }

        override fun onDestroy() {
            super.onDestroy()
            pauseHandler.removeCallbacks(pauseRunnable)
            // onPause() alone parks the GL thread but never ends it.
            glSurfaceView?.detach()
            glSurfaceView = null
        }

        // No onSurface* overrides, deliberately: the view registers itself on the engine's holder
        // (see getHolder below), so forwarding would deliver every event twice.

        private inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            init {
                setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                setEGLContextClientVersion(3)
                // Without it the context is rebuilt on every return to the home screen.
                preserveEGLContextOnPause = true
            }

            override fun getHolder(): SurfaceHolder = this@GLEngine.surfaceHolder

            /** There is no window to detach from, so teardown is invoked directly. */
            fun detach() = onDetachedFromWindow()
        }
    }
}
