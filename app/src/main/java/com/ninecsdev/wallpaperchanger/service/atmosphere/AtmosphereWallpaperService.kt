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

/**
 * The live wallpaper. Owns the GL surface, the lock/unlock state machine, and the reload channel.
 *
 * This service renders whatever [AtmosphereSource] holds and is nudged when that changes.
 */
class AtmosphereWallpaperService : GLWallpaperService() {

    companion object {
        private const val TAG = "AtmosphereWallpaper"

        /**
         * Engine-internal.
         *
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

        /** Refresh rate the morph votes its own surface to, for the morph's duration; 0 disables it */
        private const val PIN_MORPH_FRAME_RATE_HZ = 120f

        /** Identifies the deferred lock reset on the handler, so it can be canceled by token. */
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

        /** Owns whether an unlock morphs or settles silently. */
        private val lockPresence = LockPresence()

        /**
         * Set in [onDestroy] so work already queued on the main looper can tell it is running
         * against a torn-down engine. Main-thread confined: every reader and the writer run there.
         */
        private var destroyed = false

        /** Feeds one observation in and carries out whatever comes back, in order. */
        private fun dispatch(event: LockEvent) {
            for (decision in lockPresence.on(event, SystemClock.uptimeMillis())) {
                when (decision) {
                    is LockDecision.SetLocked -> renderer.setLocked(decision.locked, decision.animate)
                    LockDecision.SettleNow -> {
                        // Ending the replay first is what lets the settle stick
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
                    Intent.ACTION_SCREEN_OFF -> dispatch(LockEvent.ScreenOff)

                    // The event a fast toggle cannot skip. Visibility takes ~190 ms to drop after the panel goes off,
                    // so a toggle quicker leaves the effect up behind the keyguard; reconciling here is what catches it.
                    Intent.ACTION_SCREEN_ON -> dispatch(LockEvent.ScreenOn(keyguardManager.isKeyguardLocked))

                    // Backstop for COMMAND_KEYGUARD_GOING_AWAY on a platform that does not send
                    // it, at the cost of a morph that starts a beat into the unlock
                    Intent.ACTION_USER_PRESENT -> dispatch(LockEvent.Unlocked)

                    AtmosphereProtocol.ACTION_RELOAD -> reloadSource(intent.getBooleanExtra(AtmosphereProtocol.EXTRA_FROM_ROTATION, false))
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
                addAction(AtmosphereProtocol.ACTION_RELOAD)
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
            // A preview never sits behind a keyguard, and this seed is the only lock event it gets.
            dispatch(LockEvent.Created(!isPreview && keyguardManager.isKeyguardLocked))

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
            if (action == COMMAND_KEYGUARD_GOING_AWAY) dispatch(LockEvent.Unlocked)
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            // Before super, which draws one last frame before parking the GL thread: settling
            // first makes that frame the settled state rather than a half-finished morph.
            if (!visible) dispatch(LockEvent.BecameInvisible)

            super.onVisibilityChanged(visible)

            if (!visible) return

            if (isPreview) {
                // Being looked at again: replay preview
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

                sendBroadcast(Intent(AtmosphereProtocol.ACTION_SOURCE_REQUESTED).setPackage(packageName))
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
            val (screenWidth, screenHeight) = ImageProcessingUtils.getWallpaperCanvasSize(this@AtmosphereWallpaperService)
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
                sendBroadcast(Intent(AtmosphereProtocol.ACTION_DISPLAYED).setPackage(packageName))
            }
        }
    }
}
