package com.ninecsdev.wallpaperchanger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.RotationCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class ScreenOffReceiver(
    /**
     * Structured scope provided by the owning [WallpaperService].
     * Using the service's scope ensures coroutines are canceled when the
     * service is destroyed, preventing leaked work.
     */
    private val serviceScope: CoroutineScope
) : BroadcastReceiver() {

    private val tag = "ScreenOffReceiver"

    @Inject lateinit var appDataStore: AppDataStore
    @Inject lateinit var rotationCoordinator: RotationCoordinator

    companion object {
        /**
         * Set at broadcast receipt and held across the settle delay, so mashing the power button
         * queues one swap rather than several.
         */
        private val isSwapPending = AtomicBoolean(false)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_SCREEN_OFF) return

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
