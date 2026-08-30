package com.ninecsdev.wallpaperchanger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.RotationCoordinator
import com.ninecsdev.wallpaperchanger.logic.RotationScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Both screen transitions, in one receiver.
 *
 * Screen-off is itself a rotation trigger: it waits out the lock animation, gives up if the user
 * woke the device during that wait, and asks [RotationCoordinator] for one rotation. Screen-on only
 * feeds [RotationScheduler], which arms its timer while the screen is on.
 *
 * One receiver rather than two so both signals share a single registration — the alternative is an
 * invariant kept by four call sites in [WallpaperService] remembering to move them together.
 */
@AndroidEntryPoint
class ScreenStateReceiver(
    /**
     * Structured scope provided by the owning [WallpaperService].
     * Using the service's scope ensures coroutines are canceled when the
     * service is destroyed, preventing leaked work.
     */
    private val serviceScope: CoroutineScope
) : BroadcastReceiver() {

    private val tag = "ScreenStateReceiver"

    @Inject lateinit var appDataStore: AppDataStore
    @Inject lateinit var rotationCoordinator: RotationCoordinator
    @Inject lateinit var rotationScheduler: RotationScheduler

    companion object {
        /**
         * Set at broadcast receipt and held across the settle delay, so mashing the power button
         * queues one swap rather than several.
         */
        private val isSwapPending = AtomicBoolean(false)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        when (intent?.action) {
            Intent.ACTION_SCREEN_ON -> rotationScheduler.setScreenOn(true)
            Intent.ACTION_SCREEN_OFF -> {
                rotationScheduler.setScreenOn(false)
                rotateOnScreenOff(context)
            }
        }
    }

    private fun rotateOnScreenOff(context: Context) {
        if (!isSwapPending.compareAndSet(false, true)) {
            Log.d(tag, "A swap is already pending. Skipping.")
            return
        }

        val pendingResult = goAsync()
        val broadcastFinished = AtomicBoolean(false)
        fun finishBroadcast() {
            if (broadcastFinished.compareAndSet(false, true)) pendingResult.finish()
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Configurable delay (default 250ms for Nothing Phone animation)
                delay(appDataStore.getScreenOffDelay())

                // Safety check: if the user woke the screen during the delay abort
                if (powerManager.isInteractive) {
                    Log.w(tag, "Screen woke up. Aborting.")
                    return@launch
                }

                // Releases the broadcast as soon as the image is applied or delivered
                rotationCoordinator.rotateOnce(tag, onApplied = ::finishBroadcast)
            } finally {
                isSwapPending.set(false)
                finishBroadcast()
            }
        }
    }
}
