package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.ninecsdev.wallpaperchanger.BuildConfig
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereProtocol
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereSource
import java.util.concurrent.Executors

/** The live wallpaper: renders whatever [AtmosphereSource] holds and reloads when nudged. */
class AtmosphereWallpaperService : GLWallpaperService() {

    companion object {
        private const val TAG = "AtmosphereWallpaper"

        /**
         * Debug builds only: holds the renderer at one frame, e.g.
         * `adb shell am broadcast -a <ACTION_SEEK> --ei frame 40` (negative to resume).
         */
        const val ACTION_SEEK = "com.ninecsdev.wallpaperchanger.action.ATMOSPHERE_SEEK"
        const val EXTRA_FRAME = "frame"

        /** Sent to the engine when the keyguard begins to disappear; the morph starts on it. */
        private const val COMMAND_KEYGUARD_GOING_AWAY = "android.wallpaper.keyguardgoingaway"

        /** Refresh rate the morph votes its surface to for its duration; 0 disables the vote. */
        private const val PIN_MORPH_FRAME_RATE_HZ = 120f

        private val LOCK_RESET_TOKEN = Any()
    }

    override fun onCreateEngine(): Engine = AtmosphereEngine()

    private inner class AtmosphereEngine : GLEngine() {

        private val renderer = AtmosphereRenderer(
            context = this@AtmosphereWallpaperService,
            requestRender = { requestRender() },
            isPanelDark = ::isPanelDark,
            onSourceAdopted = ::onSourceAdopted,
            onMorphActive = ::onMorphActive,
            onSourceMissing = ::onSourceMissing
        )

        /** Single-threaded on purpose: a burst of reloads decodes one full-size bitmap at a time. */
        private val decodeExecutor = Executors.newSingleThreadExecutor()
        private val handler = Handler(Looper.getMainLooper())
        private val keyguardManager by lazy {
            getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        }

        private val powerManager by lazy {
            getSystemService(POWER_SERVICE) as PowerManager
        }

        private val lockPresence = LockPresence()

        /** Lets work already queued on the main looper detect a torn-down engine. Main-thread confined. */
        private var destroyed = false

        private fun dispatch(event: LockEvent) {
            for (decision in lockPresence.on(event, SystemClock.uptimeMillis())) {
                when (decision) {
                    is LockDecision.SetLocked -> renderer.setLocked(decision.locked, decision.animate)
                    LockDecision.SettleNow -> {
                        // Ending the replay first is what lets the settle stick.
                        renderer.stopMorphLoop()
                        renderer.settleNow()
                    }

                    is LockDecision.ScheduleLockReset ->
                        handler.postAtTime(
                            { dispatch(LockEvent.LockResetDue) },
                            LOCK_RESET_TOKEN,
                            decision.atMs
                        )

                    LockDecision.CancelLockReset -> handler.removeCallbacksAndMessages(LOCK_RESET_TOKEN)
                }
            }
        }

        // Both halves are needed: power-off marks the device non-interactive immediately while the
        // visibility callback arrives a beat later, and on a fast off/on a delivery can land in that gap.
        private fun isPanelDark(): Boolean = !isVisible && !powerManager.isInteractive

        private val systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> dispatch(LockEvent.ScreenOff)

                    // A fast toggle cannot skip this. Visibility takes ~190 ms to drop after the panel
                    // goes off, so a quicker toggle leaves the effect up behind the keyguard.
                    Intent.ACTION_SCREEN_ON -> dispatch(LockEvent.ScreenOn(keyguardManager.isKeyguardLocked))

                    // Backstop for COMMAND_KEYGUARD_GOING_AWAY; the morph then starts a beat late.
                    Intent.ACTION_USER_PRESENT -> dispatch(LockEvent.Unlocked)

                    AtmosphereProtocol.ACTION_RELOAD -> reloadSource(intent.getBooleanExtra(AtmosphereProtocol.EXTRA_FROM_ROTATION, false))
                }
            }
        }

        /** Exported (unlike [systemReceiver]) so `adb shell` can reach it; debug builds only. */
        private val seekReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_SEEK) return
                val frame = intent.getIntExtra(EXTRA_FRAME, -1)
                Log.i(TAG, "Frame scrub -> $frame")
                renderer.pinFrame(frame)
            }
        }

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // GLEngine already requests a GLES 3 context and pins dirty-mode rendering.
            setRenderer(renderer)

            val filter = IntentFilter().apply {
                if (!isPreview) {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                addAction(AtmosphereProtocol.ACTION_RELOAD)
            }
            // Not exported: the reload action is ours; the screen actions are protected system
            // broadcasts and reach a not-exported receiver regardless.
            registerReceiver(systemReceiver, filter, RECEIVER_NOT_EXPORTED)

            if (BuildConfig.DEBUG) {
                registerReceiver(
                    seekReceiver, IntentFilter(ACTION_SEEK), RECEIVER_EXPORTED
                )
            }

            // Seeds from the real keyguard, with no morph (not a transition). A preview is never
            // behind a keyguard, and this seed is the only lock event it gets.
            dispatch(LockEvent.Created(!isPreview && keyguardManager.isKeyguardLocked))

            // A preview engine replays the morph instead.
            if (isPreview) renderer.startMorphLoop()
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (action == COMMAND_KEYGUARD_GOING_AWAY) dispatch(LockEvent.Unlocked)
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            // Before super, which draws one last frame before parking the GL thread: that frame
            // should be the settled state, not a half-finished morph.
            if (!visible) dispatch(LockEvent.BecameInvisible)

            super.onVisibilityChanged(visible)

            if (!visible) return

            if (isPreview) {
                renderer.startMorphLoop()
                return
            }

            // After super, so the surface is live by the time a morph is asked for.
            dispatch(LockEvent.BecameVisible(keyguardManager.isKeyguardLocked))
        }

        override fun onDestroy() {
            pinMorphFrameRate(false)
            destroyed = true
            dispatch(LockEvent.Destroyed)
            runCatching { unregisterReceiver(systemReceiver) }
            if (BuildConfig.DEBUG) runCatching { unregisterReceiver(seekReceiver) }
            // Decodes already past the executor are caught by the `destroyed` guard in decodeAndQueue.
            decodeExecutor.shutdownNow()
            // Queued before super, which stops the GL thread, so release runs while it is alive.
            queueGlEvent { renderer.release() }
            super.onDestroy()
        }

        private fun reloadSource(fromRotation: Boolean) {
            decodeAndQueue(fromRotation) {
                AtmosphereSource.readDecoded(this@AtmosphereWallpaperService.filesDir)
            }
        }

        /**
         * [decode] runs on [decodeExecutor] and the result is queued on the main looper; a decode
         * that outlived the engine recycles its bitmap. A null result keeps what is on screen.
         */
        private fun decodeAndQueue(
            fromRotation: Boolean,
            decode: () -> AtmosphereSource.Decoded?
        ) {
            decodeExecutor.execute {
                val decoded = decode() ?: return@execute
                handler.post {
                    if (destroyed) {
                        decoded.bitmap.recycle()
                        return@post
                    }
                    renderer.queueSource(decoded.seeds, decoded.bitmap, fromRotation)
                    renderPendingWork()
                }
            }
        }

        /** Called on the GL thread, so it hops to the main looper. A preview engine asks too. */
        private fun onSourceMissing() {
            handler.post {
                if (destroyed) return@post

                sendBroadcast(Intent(AtmosphereProtocol.ACTION_SOURCE_REQUESTED).setPackage(packageName))
                loadFallbackSource()
            }
        }

        /** Sized from the surface when known, from the display otherwise. */
        private fun loadFallbackSource() {
            val frame = surfaceHolder?.surfaceFrame
            val (screenWidth, screenHeight) = ImageProcessingUtils.getWallpaperCanvasSize(this@AtmosphereWallpaperService)
            val width = frame?.width()?.takeIf { it > 0 } ?: screenWidth
            val height = frame?.height()?.takeIf { it > 0 } ?: screenHeight

            // Not a rotation: it must show at once, and it must never advance the magazine.
            decodeAndQueue(fromRotation = false) {
                AtmosphereFallbackSource.load(this@AtmosphereWallpaperService, width, height)
            }
        }

        /**
         * Called on the GL thread, so it hops to the main looper. Both edges come from the renderer
         * because only it knows whether a morph began: pinning on an already-unlocked engine would
         * hold the vote forever.
         */
        private fun onMorphActive(active: Boolean) {
            if (isPreview) return
            handler.post { if (!destroyed) pinMorphFrameRate(active) }
        }

        /**
         * Holds the panel at [PIN_MORPH_FRAME_RATE_HZ] across the morph, or releases the vote.
         * [Surface.CHANGE_FRAME_RATE_ALWAYS] on purpose: it moves the mode change to the morph boundary.
         */
        private fun pinMorphFrameRate(pin: Boolean) {
            if (PIN_MORPH_FRAME_RATE_HZ <= 0f) return

            val surface = surfaceHolder?.surface ?: return
            if (!surface.isValid) return
            try {
                surface.setFrameRate(
                    if (pin) PIN_MORPH_FRAME_RATE_HZ else 0f,
                    if (pin) Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                    else Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } catch (e: IllegalStateException) {
                // Surface torn down between the isValid check and the call; nothing to hold.
                Log.w(TAG, "setFrameRate($pin) on a dead surface", e)
            }
        }

        /** Called on the GL thread, so it hops to the main looper. Only a non-preview rotation delivery reports. */
        private fun onSourceAdopted(fromRotation: Boolean) {
            if (!fromRotation || isPreview) return
            handler.post {
                if (destroyed) return@post
                sendBroadcast(Intent(AtmosphereProtocol.ACTION_DISPLAYED).setPackage(packageName))
            }
        }
    }
}
