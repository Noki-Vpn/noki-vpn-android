package com.noki.vpn

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.AvatarCachePolicy
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.BackendException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.math.max
import kotlin.math.min

internal data class AvatarCropRequest(
    val sourceUri: String,
    val previewWidthPx: Float,
    val previewHeightPx: Float,
    val cropCircleSizePx: Float,
    val cropScale: Float,
    val cropOffsetX: Float,
    val cropOffsetY: Float,
    val rotationQuarterTurns: Int = 0,
) {
    private val quarterTurns: Int get() = Math.floorMod(rotationQuarterTurns, 4)

    private fun imageScale(width: Int, height: Int): Float {
        require(width > 0 && height > 0)
        val rotatedWidth = if (quarterTurns % 2 == 0) width else height
        val rotatedHeight = if (quarterTurns % 2 == 0) height else width
        val fit = min(previewWidthPx / rotatedWidth, previewHeightPx / rotatedHeight)
        val cover = max(cropCircleSizePx / rotatedWidth, cropCircleSizePx / rotatedHeight)
        return max(fit, cover) * (cropScale.takeIf(Float::isFinite)?.coerceIn(1f, 5f) ?: 1f)
    }

    fun clampOffsets(width: Int, height: Int): AvatarCropRequest {
        val scale = imageScale(width, height)
        val rotatedWidth = if (quarterTurns % 2 == 0) width else height
        val rotatedHeight = if (quarterTurns % 2 == 0) height else width
        val maxX = max(0f, (rotatedWidth * scale - cropCircleSizePx) / 2f)
        val maxY = max(0f, (rotatedHeight * scale - cropCircleSizePx) / 2f)
        return copy(
            cropOffsetX = (cropOffsetX.takeIf(Float::isFinite) ?: 0f).coerceIn(-maxX, maxX),
            cropOffsetY = (cropOffsetY.takeIf(Float::isFinite) ?: 0f).coerceIn(-maxY, maxY),
        )
    }

    // Shared by preview and JPEG export: source pixels -> preview coordinates.
    fun imageMatrix(width: Int, height: Int): Matrix {
        val crop = clampOffsets(width, height)
        val scale = imageScale(width, height)
        return Matrix().apply {
            postTranslate(-width / 2f, -height / 2f)
            postRotate(quarterTurns * 90f)
            postScale(scale, scale)
            postTranslate(previewWidthPx / 2f + crop.cropOffsetX, previewHeightPx / 2f + crop.cropOffsetY)
        }
    }
}

internal fun interface ProfileAvatarLoader {
    suspend fun cachedBackendAvatarUri(
        token: String,
        backendAvatarUrl: String?,
        fallbackAvatarUri: String?,
    ): String?
}

internal object AvatarBitmapDecoder {
    private const val MAX_DECODED_SIDE = 2_048
    private const val MAX_DECODED_PIXELS = 4_194_304L

    fun inSampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "avatar_open_failed" }
        var sample = 1
        while (true) {
            val sampledWidth = (width.toLong() + sample - 1L) / sample
            val sampledHeight = (height.toLong() + sample - 1L) / sample
            if (
                sampledWidth <= MAX_DECODED_SIDE &&
                sampledHeight <= MAX_DECODED_SIDE &&
                sampledWidth * sampledHeight <= MAX_DECODED_PIXELS
            ) {
                return sample
            }
            check(sample <= Int.MAX_VALUE / 2) { "avatar_open_failed" }
            sample *= 2
        }
    }

    fun decode(openStream: () -> InputStream?): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openStream() ?: throw IllegalStateException("avatar_open_failed")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = try {
            inSampleSize(bounds.outWidth, bounds.outHeight)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("avatar_open_failed")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("avatar_open_failed")
    }
}

internal class ProfileAvatarCoordinator(
    private val app: Application,
    private val backendApi: BackendApiClient,
) : ProfileAvatarLoader {
    private val cacheMutex = Mutex()

    suspend fun uploadCroppedAvatar(
        token: String,
        request: AvatarCropRequest,
    ): String {
        val bytes = withContext(Dispatchers.IO) {
            croppedAvatarJpegBytes(request)
        }
        val avatarUrl = backendApi.uploadAvatar(
            token = token,
            fileName = "avatar.jpg",
            mimeType = "image/jpeg",
            bytes = bytes,
        )
        return cacheUploadedAvatar(avatarUrl, bytes)
    }

    suspend fun deleteAvatar(token: String) {
        backendApi.deleteAvatar(token)
        cacheMutex.withLock {
            withContext(Dispatchers.IO) { clearAvatarCache() }
        }
    }

    override suspend fun cachedBackendAvatarUri(
        token: String,
        backendAvatarUrl: String?,
        fallbackAvatarUri: String?,
    ): String? {
        val avatarUrl = backendAvatarUrl?.takeIf { it.isNotBlank() } ?: return null
        return try {
            withTimeoutOrNull(5_000L) {
                cacheMutex.withLock {
                    val target = avatarCacheFile(avatarUrl)
                    if (target.length() <= 0L) {
                        backendApi.downloadProfileAvatar(
                            token = token,
                            avatarUrl = avatarUrl,
                            destination = target,
                        )
                    }
                    target.takeIf { it.length() > 0L }?.toUri()?.toString()
                        ?: throw IllegalStateException("avatar_open_failed")
                }
            } ?: fallbackAvatarUri?.takeIf { it.startsWith("file:", ignoreCase = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is BackendException && error.statusCode == 401) throw error
            fallbackAvatarUri?.takeIf { cached -> cached.startsWith("file:", ignoreCase = true) }
        }
    }

    fun readableAvatarError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        return when (error.message) {
            "avatar_file_too_large" -> tr(language, "Файл больше 5 МБ", "File is larger than 5 MB")
            "avatar_open_failed" -> tr(language, "Не удалось открыть файл", "Could not open the file")
            else -> AppErrorMapper.readableNetworkError(language, error)
        }
    }

    private fun avatarCacheFile(avatarUrl: String): File {
        return File(app.cacheDir, "profile_avatar/${AvatarCachePolicy.cacheFileName(avatarUrl)}")
    }

    private fun clearAvatarCache() {
        File(app.cacheDir, "profile_avatar").deleteRecursively()
    }

    private suspend fun cacheUploadedAvatar(avatarUrl: String, bytes: ByteArray): String =
        cacheMutex.withLock {
            withContext(Dispatchers.IO) {
                clearAvatarCache()
                val target = avatarCacheFile(avatarUrl)
                val parent = checkNotNull(target.parentFile) { "avatar_cache_write_failed" }
                check(parent.isDirectory || parent.mkdirs()) { "avatar_cache_write_failed" }
                val temporary = File(parent, "${target.name}.part")
                try {
                    temporary.writeBytes(bytes)
                    Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
                    target.toUri().toString()
                } finally {
                    temporary.delete()
                }
            }
        }

    private fun croppedAvatarJpegBytes(request: AvatarCropRequest): ByteArray {
        val sourceUri = request.sourceUri.toUri()
        val source = AvatarBitmapDecoder.decode { app.contentResolver.openInputStream(sourceUri) }
        val outputSize = 512
        val previewWidth = request.previewWidthPx.takeIf { it.isFinite() && it > 0f } ?: source.width.toFloat()
        val previewHeight = request.previewHeightPx.takeIf { it.isFinite() && it > 0f } ?: source.height.toFloat()
        val cropCircle = request.cropCircleSizePx
            .takeIf { it.isFinite() && it > 0f }
            ?: minOf(previewWidth, previewHeight)
        val outputScale = outputSize / cropCircle
        val cropLeft = (previewWidth - cropCircle) / 2f
        val cropTop = (previewHeight - cropCircle) / 2f
        val output = createBitmap(outputSize, outputSize)
        val matrix = request.copy(
            previewWidthPx = previewWidth,
            previewHeightPx = previewHeight,
            cropCircleSizePx = cropCircle,
        ).imageMatrix(source.width, source.height).apply {
            postTranslate(-cropLeft, -cropTop)
            postScale(outputScale, outputScale)
        }
        return try {
            Canvas(output).drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            ByteArrayOutputStream().use { bytes ->
                check(output.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) { "avatar_encode_failed" }
                bytes.toByteArray()
            }
        } finally {
            source.recycle()
            output.recycle()
        }
    }

    private fun tr(
        language: AppLanguage,
        russian: String,
        english: String,
    ): String {
        return if (language == AppLanguage.RU) russian else english
    }
}
