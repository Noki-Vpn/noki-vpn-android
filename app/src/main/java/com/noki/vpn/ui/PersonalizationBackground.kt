package com.noki.vpn.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.Animatable
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import com.noki.vpn.data.PersonalizationBackgroundStore
import java.io.File

@Composable
internal fun PersonalizationBackgroundMedia(name: String?) {
    val context = LocalContext.current
    val file = remember(context, name) { PersonalizationBackgroundStore(context).resolve(name) } ?: return
    key(name) {
        if (file.extension == "video") {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val video = remember(file, lifecycle) { BackgroundVideo(file, lifecycle) }
            DisposableEffect(video) {
                lifecycle.addObserver(video)
                onDispose { lifecycle.removeObserver(video); video.release() }
            }
            AndroidView(factory = { video.view(it) }, modifier = Modifier.fillMaxSize())
        } else {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            var animation by remember(file) { mutableStateOf<Animatable?>(null) }
            val request = remember(context, file) {
                ImageRequest.Builder(context).data(file).repeatCount(-1).build()
            }
            DisposableEffect(animation, lifecycle) {
                val drawable = animation
                fun update() {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) drawable?.start()
                    else drawable?.stop()
                }
                val observer = LifecycleEventObserver { _, _ -> update() }
                lifecycle.addObserver(observer)
                update()
                onDispose { lifecycle.removeObserver(observer); drawable?.stop() }
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop,
                onSuccess = { animation = it.result.image.asDrawable(context.resources) as? Animatable },
                modifier = Modifier.fillMaxSize())
        }
    }
}

private class BackgroundVideo(private val file: File, private val lifecycle: Lifecycle) :
    TextureView.SurfaceTextureListener, LifecycleEventObserver {
    private var texture: TextureView? = null
    private var surface: Surface? = null
    private var prepared = false
    private var loading = false
    private var released = false
    private val player = MediaPlayer().apply {
        isLooping = true
        setVolume(0f, 0f)
        setOnPreparedListener {
            prepared = true
            crop()
            resumeIfVisible()
        }
        setOnVideoSizeChangedListener { _, _, _ -> crop() }
        setOnErrorListener { _, _, _ -> prepared = false; true }
    }

    fun view(context: android.content.Context) = TextureView(context).also {
        texture = it
        it.isOpaque = false
        it.surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        surface = Surface(value)
        player.setSurface(surface)
        if (!loading) {
            loading = true
            runCatching { player.setDataSource(file.absolutePath); player.prepareAsync() }
        } else resumeIfVisible()
        crop()
    }

    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = crop()
    override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
        if (!released) {
            pause()
            player.setSurface(null)
        }
        surface?.release()
        surface = null
        return true
    }

    private fun crop() {
        val view = texture ?: return
        if (released || !prepared || view.width == 0 || view.height == 0 || player.videoWidth == 0 || player.videoHeight == 0) return
        val ratio = (player.videoWidth.toFloat() / player.videoHeight) / (view.width.toFloat() / view.height)
        view.setTransform(Matrix().apply {
            setScale(if (ratio > 1f) ratio else 1f, if (ratio < 1f) 1f / ratio else 1f,
                view.width / 2f, view.height / 2f)
        })
    }

    private fun resumeIfVisible() {
        if (!released && prepared && surface != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            player.start()
        }
    }

    private fun pause() {
        if (!released && prepared) player.pause()
    }

    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_RESUME) resumeIfVisible()
        if (event == Lifecycle.Event.ON_PAUSE) pause()
    }

    fun release() {
        if (released) return
        released = true
        texture?.surfaceTextureListener = null
        player.release()
        surface?.release()
        surface = null
        texture = null
    }
}
