package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ninecsdev.wallpaperchanger.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The live wallpaper. Owns the GL surface, the lock/unlock state machine, and the reload channel.
 *
 * This service renders whatever [AtmosphereSource] holds and is nudged when that changes.
 */
class AtmosphereWallpaperService : GLWallpaperService() {

    companion object {
        private const val TAG = "AtmosphereWallpaper"

        /**
         * Holds the renderer at one frame so a still can be compared against the real effect.
         * Debug builds only:
         * `adb shell am broadcast -a <ACTION_SEEK> --ei frame 40`, or a negative frame to resume.
         */
        const val ACTION_SEEK = "com.ninecsdev.wallpaperchanger.action.ATMOSPHERE_SEEK"
        const val EXTRA_FRAME = "frame"

        /**
         * How often to ask whether the keyguard is gone, once the screen is on.
         *
         * `ACTION_USER_PRESENT` is the semantically correct signal but arrives late relative to
         * the unlock animation, and late here eats the opening of the morph — the part where the
         * photo is still legible. Polling catches the edge sooner. It is bounded to screen-on, so
         * it cannot run in the background.
         */
        private const val KEYGUARD_POLL_MS = 50L

        fun componentName(context: Context): ComponentName =
            ComponentName(context, AtmosphereWallpaperService::class.java)
    }

    override fun onCreateEngine(): Engine = AtmosphereEngine()

    private inner class AtmosphereEngine : GLEngine() {

        private val renderer = AtmosphereRenderer(this@AtmosphereWallpaperService) { requestRender() }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val handler = Handler(Looper.getMainLooper())
        private val keyguardManager by lazy {
            getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        }

        private var screenOn = true

        private val keyguardPoll = object : Runnable {
            override fun run() {
                if (!screenOn) return
                // On a device with no secure lock screen this is false immediately, which is the
                // correct reading: there is no keyguard to dismiss.
                if (!keyguardManager.isKeyguardLocked) {
                    renderer.setLocked(false)
                    return
                }
                handler.postDelayed(this, KEYGUARD_POLL_MS)
            }
        }

        private val systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        handler.removeCallbacks(keyguardPoll)
                        // Early on purpose: this rewinds the counter and re-rolls the layout, and
                        // landing late would show the settled home state on the lock screen.
                        renderer.setLocked(true)
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        handler.post(keyguardPoll)
                    }

                    // Backstop for the poll, not the primary signal.
                    Intent.ACTION_USER_PRESENT -> renderer.setLocked(false)

                    AtmosphereSource.ACTION_RELOAD -> reloadSource()
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
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(AtmosphereSource.ACTION_RELOAD)
            }
            // Not exported: the reload action is ours and nothing else should be able to drive it.
            // The three screen actions are protected system broadcasts, which the platform
            // delivers to a not-exported receiver regardless.
            registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

            if (BuildConfig.DEBUG) {
                registerReceiver(
                    seekReceiver, IntentFilter(ACTION_SEEK), Context.RECEIVER_EXPORTED
                )
            }

            // The picker preview has no keyguard to dismiss, so show the effect rather than the
            // bare photo the lock state would give.
            if (isPreview) renderer.setLocked(false)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (!visible) handler.removeCallbacks(keyguardPoll)
        }

        override fun onDestroy() {
            handler.removeCallbacks(keyguardPoll)
            runCatching { unregisterReceiver(systemReceiver) }
            if (BuildConfig.DEBUG) runCatching { unregisterReceiver(seekReceiver) }
            scope.cancel()
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
         */
        private fun reloadSource() {
            scope.launch {
                val payload = AtmosphereSource.read(this@AtmosphereWallpaperService)
                if (payload == null) {
                    Log.w(TAG, "Reload requested but no readable source; keeping current")
                    return@launch
                }
                val bitmap = BitmapFactory.decodeByteArray(
                    payload.imageBytes, 0, payload.imageBytes.size
                )
                if (bitmap == null) {
                    Log.w(TAG, "Reload source failed to decode; keeping current")
                    return@launch
                }
                renderer.queueSource(payload.seeds, bitmap)
            }
        }
    }
}
