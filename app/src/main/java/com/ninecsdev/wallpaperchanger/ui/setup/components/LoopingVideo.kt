package com.ninecsdev.wallpaperchanger.ui.setup.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect

/** A bundled silent clip, looped and center-cropped to the bounds; paused while the screen is. */
@Composable
internal fun LoopingVideo(
    @RawRes resId: Int,
    modifier: Modifier = Modifier
) {
    val player = remember(resId) { LoopingPlayer(resId) }

    LifecycleResumeEffect(player) {
        player.resume()
        onPauseOrDispose { player.pause() }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { context -> TextureView(context).also(player::attach) },
        modifier = modifier
    )
}

private class LoopingPlayer(@param:RawRes private val resId: Int) : TextureView.SurfaceTextureListener {
    private var view: TextureView? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var resumed = false

    fun attach(textureView: TextureView) {
        view = textureView
        textureView.surfaceTextureListener = this
    }

    fun resume() {
        resumed = true
        player?.start()
    }

    fun pause() {
        resumed = false
        player?.takeIf { it.isPlaying }?.pause()
    }

    fun release() {
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val context = view?.context ?: return
        val newSurface = Surface(texture)
        surface = newSurface
        player = MediaPlayer.create(context, resId)?.apply {
            setSurface(newSurface)
            isLooping = true
            setVolume(0f, 0f)
            cropToView(videoWidth, videoHeight)
            if (resumed) start()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        player?.let { cropToView(it.videoWidth, it.videoHeight) }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    // A TextureView stretches the frame to its bounds; scaling back by the ratio of ratios crops instead
    private fun cropToView(videoWidth: Int, videoHeight: Int) {
        val view = view ?: return
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        if (videoWidth == 0 || videoHeight == 0 || width == 0f || height == 0f) return

        val scale = maxOf(width / videoWidth, height / videoHeight)
        view.setTransform(
            Matrix().apply {
                setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f)
            }
        )
    }
}
