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
import android.util.Log
import android.view.Surface
import com.ninecsdev.wallpaperchanger.BuildConfig
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereSource
import java.util.concurrent.Executors

/**
 * The live wallpaper. Owns the GL surface, the lock/unlock state machine, and the reload channel.
 *
 * This service renders whatever [AtmosphereSource] holds and is nudged when that changes.
 */
class AtmosphereWallpaperService : GLWallpaperService() {

    companion object {
        private const val TAG = "AtmosphereWallpaper"

        /** Same-app broadcast the host sends after replacing the source container on disk. */
        const val ACTION_RELOAD = "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_RELOAD"

        /**
         * Boolean extra on [ACTION_RELOAD]: true when the reload is a rotation delivery (whose
         * display should advance the magazine), false for non-rotation reloads (revert-to-default,
         * settings re-renders). Absent is treated as false.
         */
        const val EXTRA_FROM_ROTATION = "from_rotation"

        /**
         * Same-app broadcast the engine sends back once a **rotation** delivery has actually been
         * adopted. The host's rotation service advances the magazine on this, so a delivery that is
         * queued and never shown doesn't consume a wallpaper.
         */
        const val ACTION_DISPLAYED = "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_DISPLAYED"

        /**
         * Same-app broadcast the engine sends when it starts and finds no source on disk. The app
         * answers by rendering one and delivering it; see
         * [AtmosphereSourceRequestReceiver].
         */
        const val ACTION_SOURCE_REQUESTED =
            "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_SOURCE_REQUESTED"

        /**
         * Holds the renderer at one frame so a still can be compared against the real effect.
         * Debug builds only:
         * `adb shell am broadcast -a <ACTION_SEEK> --ei frame 40`, or a negative frame to resume.
         */
        const val ACTION_SEEK = "com.ninecsdev.wallpaperchanger.action.ATMOSPHERE_SEEK"
        const val EXTRA_FRAME = "frame"

        /**
         * The command the shell sends an engine when the keyguard begins to disappear, and the
         * signal the morph starts on.
         */
        private const val COMMAND_KEYGUARD_GOING_AWAY = "android.wallpaper.keyguardgoingaway"

        /**
         * Delay between screen-off and returning to the lock visual.
         *
         * The reset rewinds the frame counter and re-rolls the blob layout, and the panel is still
         * fading when `ACTION_SCREEN_OFF` arrives — so doing it immediately shows the home screen
         * visibly changing on the way out. Waiting for the fade to finish hides it. Unlock latency
         * is unaffected: this only ever runs while the screen is going dark, and a wake inside the
         * window cancels it.
         */
        private const val LOCK_DELAY_MS = 300L

        /**
         * Refresh rate the morph votes its own surface to, for the morph's duration; 0 disables it
         */
        private const val PIN_MORPH_FRAME_RATE_HZ = 120f

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

        /**
         * Single-threaded on purpose: deliveries are serialized, so a burst of reloads decodes one
         * at a time instead of piling several full-size bitmaps into memory at once.
         */
        private val decodeExecutor = Executors.newSingleThreadExecutor()
        private val handler = Handler(Looper.getMainLooper())
        private val keyguardManager by lazy {
            getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        }

        private val powerManager by lazy {
            getSystemService(POWER_SERVICE) as PowerManager
        }

        /** Deferred by [LOCK_DELAY_MS] so the reset lands after the panel is dark. */
        private val lockRunnable = Runnable { renderer.setLocked(true) }

        /**
         * Set in [onDestroy] so work already queued on the main looper can tell it is running
         * against a torn-down engine. Main-thread confined: every reader and the writer run there.
         */
        private var destroyed = false

        /**
         * Leaves the lock visual, morphing only if there is somebody to watch it.
         */
        private fun unlock() = renderer.setLocked(false, animate = isVisible)

        /**
         * True when a swap cannot be seen: the engine is not being shown *and* the device is not
         * interactive.
         */
        // Both halves are needed, and they disagree exactly when it matters. Pressing power marks
        // the device non-interactive immediately while the visibility callback arrives a beat
        // later, and on a fast off/on that gap is precisely when a delivery lands.
        private fun isPanelDark(): Boolean = !isVisible && !powerManager.isInteractive

        private val systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        handler.removeCallbacks(lockRunnable)
                        handler.postDelayed(lockRunnable, LOCK_DELAY_MS)
                    }

                    // A wake inside the lock delay: the reset would now be visible, and
                    // onVisibilityChanged re-reads the keyguard on the way in regardless.
                    Intent.ACTION_SCREEN_ON -> handler.removeCallbacks(lockRunnable)

                    // Backstop for COMMAND_KEYGUARD_GOING_AWAY on a platform that does not send
                    // it, at the cost of a morph that starts a beat into the unlock.
                    Intent.ACTION_USER_PRESENT -> unlock()

                    ACTION_RELOAD ->
                        reloadSource(intent.getBooleanExtra(EXTRA_FROM_ROTATION, false))
                }
            }
        }

        /**
         * Separate from [systemReceiver] because it has to be **exported** to be reachable at all:
         * `adb shell` broadcasts as the shell UID, and a not-exported receiver only accepts its own
         * app and protected system broadcasts. Debug builds only, so nothing exported ships.
         */
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
            // GLEngine already requests a GLES 3 context and pins dirty-mode rendering; setting
            // either here as well is how they came to disagree.
            setRenderer(renderer)

            val filter = IntentFilter().apply {
                // Preview doesn't care about these intents
                if (!isPreview) {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                addAction(ACTION_RELOAD)
            }
            // Not exported: the reload action is ours and nothing else should be able to drive it.
            // The three screen actions are protected system broadcasts, which the platform
            // delivers to a not-exported receiver regardless.
            registerReceiver(systemReceiver, filter, RECEIVER_NOT_EXPORTED)

            if (BuildConfig.DEBUG) {
                registerReceiver(
                    seekReceiver, IntentFilter(ACTION_SEEK), RECEIVER_EXPORTED
                )
            }

            // Seed the visual from the real keyguard rather than assuming locked.
            // No morph: this is the state the device is already in, not a transition into it.
            val startLocked = !isPreview && keyguardManager.isKeyguardLocked
            // Created behind the keyguard is the boot case, and seeding the lock visual is also
            // what arms the first unlock after a reboot to morph.
            renderer.setLocked(startLocked, animate = false)

            // A preview engine plays the morph on repeat instead. Stopped on the way out of visibility
            if (isPreview) renderer.startMorphLoop()
        }

        /**
         * Wallpaper commands from the shell. [COMMAND_KEYGUARD_GOING_AWAY] is the one that
         * matters; everything else is passed through untouched.
         */
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (action == COMMAND_KEYGUARD_GOING_AWAY) unlock()
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (!visible) {
                // Before super, which draws one last frame before parking the GL thread: settling
                // first makes that frame the settled state rather than a half-finished morph.
                // Ending the replay first is what lets that settle stick.
                renderer.stopMorphLoop()
                renderer.settleNow()
            }
            super.onVisibilityChanged(visible)

            if (!visible) return

            if (isPreview) {
                // Being looked at again: replay preview
                renderer.startMorphLoop()
                return
            }

            if (keyguardManager.isKeyguardLocked) {
                // A wake onto the lock screen: show the photo the keyguard will be dismissed from.
                renderer.setLocked(true, animate = false)
            } else {
                // Either a wake into an unlocked world, or a return to a home screen that never
                // locked. Which one it is decides itself: only the first has a lock visual to
                // leave, and only a transition can morph.
                unlock()
            }
        }

        override fun onDestroy() {
            pinMorphFrameRate(false)
            destroyed = true
            handler.removeCallbacks(lockRunnable)
            runCatching { unregisterReceiver(systemReceiver) }
            if (BuildConfig.DEBUG) runCatching { unregisterReceiver(seekReceiver) }
            // Stops decodes that have not started; ones already past the decode are caught by the
            // `destroyed` guard in the hand-off below.
            decodeExecutor.shutdownNow()
            // Queued before super, which stops the GL thread. The context takes the GL objects
            // with it either way; this just does it in the right order when the thread is still
            // alive to run it.
            queueGlEvent { renderer.release() }
            super.onDestroy()
        }

        /**
         * Decodes the new source off the GL thread and hands it over. The renderer decides *when*
         * to adopt it; see [AtmosphereRenderer.queueSource].
         *
         * A failure here leaves whatever is already loaded on screen. Nothing in this path is
         * allowed to produce a blank wallpaper.
         *
         * Ends with [renderPendingWork].
         */
        private fun reloadSource(fromRotation: Boolean) {
            decodeAndQueue(fromRotation) {
                AtmosphereSource.readDecoded(this@AtmosphereWallpaperService.filesDir)
            }
        }

        /**
         * The hand-off every source goes through, whatever produced it: [decode] runs on
         * [decodeExecutor], the result is adopted on the main looper, and a decode that outlived
         * the engine recycles its bitmap instead of handing it to a dead renderer.
         *
         * A null [decode] leaves whatever is already loaded on screen.
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

        /**
         * The renderer came up with no source to load. Called on the **GL thread**, so it hops to
         * the main looper first.
         *
         * A preview engine asks too. It is the same black screen there, and the request is
         * answered the same way.
         */
        private fun onSourceMissing() {
            handler.post {
                if (destroyed) return@post

                sendBroadcast(Intent(ACTION_SOURCE_REQUESTED).setPackage(packageName))
                loadFallbackSource()
            }
        }

        /**
         * Draws the built-in wallpaper into a source and queues it, off the GL thread.
         *
         * Sized from the surface when the holder already knows, and from the display otherwise.
         */
        private fun loadFallbackSource() {
            val frame = surfaceHolder?.surfaceFrame
            val (screenWidth, screenHeight) =
                ImageProcessingUtils.getScreenDimensions(this@AtmosphereWallpaperService)
            val width = frame?.width()?.takeIf { it > 0 } ?: screenWidth
            val height = frame?.height()?.takeIf { it > 0 } ?: screenHeight

            // Not a rotation: it must show at once, and it must never advance the magazine.
            decodeAndQueue(fromRotation = false) {
                AtmosphereFallbackSource.load(this@AtmosphereWallpaperService, width, height)
            }
        }

        /**
         * A morph started or ended. Called on the **GL thread**, so it hops to the main looper
         * before touching the surface.
         *
         * Both edges come from the renderer rather than from [unlock] because only the renderer
         * knows whether a morph actually began: `setLocked(false)` on an already-unlocked engine
         * is a no-op, and pinning there would leave the vote held forever.
         */
        private fun onMorphActive(active: Boolean) {
            // Guard against a preview reaching here
            if (isPreview) return
            handler.post { if (!destroyed) pinMorphFrameRate(active) }
        }

        /**
         * Holds the panel at [PIN_MORPH_FRAME_RATE_HZ] across the morph, or releases the vote.
         *
         * [Surface.CHANGE_FRAME_RATE_ALWAYS] on purpose: the point is to take the mode change we
         * would otherwise get mid-morph and move it to the morph boundary.
         */
        private fun pinMorphFrameRate(pin: Boolean) {
            // Safety check to disable this
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

        /**
         * The renderer has committed a queued source to the screen.
         *
         * Called on the **GL thread**, so it hops to the main looper before broadcasting. Only a
         * rotation delivery advances the magazine, and never from a preview engine.
         */
        private fun onSourceAdopted(fromRotation: Boolean) {
            if (!fromRotation || isPreview) return
            handler.post {
                if (destroyed) return@post
                sendBroadcast(Intent(ACTION_DISPLAYED).setPackage(packageName))
            }
        }
    }
}
