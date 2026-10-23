package com.noki.vpn.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class PersonalizationBackgroundStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "personalization_background")

    fun resolve(name: String?): File? = name?.takeIf(::isValidName)
        ?.let { File(directory, it) }?.takeIf { it.isFile }

    fun remove(name: String?) {
        resolve(name)?.let { check(it.delete()) { "background_remove_failed" } }
    }

    suspend fun stage(uri: Uri): String {
        var destination: File? = null
        try {
            return withContext(Dispatchers.IO) {
                require(uri.scheme == "content") { "background_content_required" }
                val mime = context.contentResolver.getType(uri).orEmpty()
                val video = mime.startsWith("video/")
                require(video || mime.startsWith("image/")) { "background_media_required" }
                check(directory.isDirectory || directory.mkdirs())
                val name = "${UUID.randomUUID()}.${if (video) "video" else "image"}"
                val file = File(directory, name).also { destination = it }
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("background_unreadable")
                input.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_BYTES) { "background_too_large" }
                            output.write(buffer, 0, count)
                        }
                        require(total > 0) { "background_empty" }
                    }
                }
                if (video) {
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(file.absolutePath)
                        require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
                        require((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
                    } finally { metadata.release() }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "background_invalid_image" }
                }
                name
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { destination?.delete() }
            throw error
        }
    }

    companion object {
        const val MAX_BYTES = 128L * 1024 * 1024
        fun isValidName(name: String): Boolean =
            name.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.(image|video)"))
    }
}
