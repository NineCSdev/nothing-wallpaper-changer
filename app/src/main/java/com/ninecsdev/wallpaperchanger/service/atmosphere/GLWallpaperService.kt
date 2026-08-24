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

        /**
         * Attaches [renderer] and pins dirty-mode rendering.
         *
         * Dirty mode is not a caller's choice: a renderer here re-arms itself with
         * [requestRender] while it animates and idles completely between morphs, so a continuous
         * loop would redraw a bit-identical frame forever. Pinned here rather than left to each
         * service, which is how it once got set twice.
         */
        fun setRenderer(renderer: GLSurfaceView.Renderer) {
            glSurfaceView?.setRenderer(renderer)
            glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        /**
         * Runs [action] on the GL thread, where a live context is required — releasing GL objects
         * at teardown, principally.
         *
         * Best-effort: `GLSurfaceView`'s thread checks its exit flag *before* draining the event
         * queue, so anything queued after the exit request may never run. That is survivable for
         * releases, since the dying context takes its objects with it regardless.
         */
        protected fun queueGlEvent(action: Runnable) {
            glSurfaceView?.queueEvent(action)
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
            renderThenReArmPause()
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
            // `onPause()` alone parks the GL thread but never ends it. The picker opens and closes
            // preview engines freely, so that accumulated.
            glSurfaceView?.detach()
            glSurfaceView = null
        }

        // No onSurfaceCreated/Changed/Destroyed overrides here, deliberately.
        //
        // GLSurfaceView's constructor registers itself as a callback on whatever getHolder()
        // returns — which the subclass below points at the *engine's* holder. The engine already
        // dispatches to every callback on that holder, so the view is notified without our help.
        // Forwarding as well delivered each event twice, and a second surfaceChanged re-fires the
        // renderer's onSurfaceChanged with the same dimensions.

        private inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            init {
                setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                setEGLContextClientVersion(3)
                // Survives the pause/resume of an app switch. Without it the context is destroyed
                // and rebuilt on every return to the home screen, and the rebuild re-fires both
                // onSurfaceCreated and onSurfaceChanged.
                preserveEGLContextOnPause = true
            }

            override fun getHolder(): SurfaceHolder = this@GLEngine.surfaceHolder

            /** There is no window to detach from, so the teardown has to be invoked directly. */
            fun detach() = onDetachedFromWindow()
        }
    }
}
