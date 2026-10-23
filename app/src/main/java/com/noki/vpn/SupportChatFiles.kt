package com.noki.vpn

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.noki.vpn.data.SUPPORT_ATTACHMENT_MAX_BYTES
import com.noki.vpn.data.SupportAttachmentDraft
import com.noki.vpn.data.canOpenSupportAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class SupportAttachmentSaveRequest(val path: String, val name: String, val mime: String, val id: String = UUID.randomUUID().toString())

/** Only private temporary files; the workflow owns their account-bound lifetime. */
internal object SupportChatFiles {
    private var cachePrepared = false

    @Synchronized
    fun prepareCache(context: Context) {
        if (cachePrepared) return
        // The workflow's file references are memory-only; old process files have no owner.
        runCatching {
            val directory = directory(context).canonicalFile
            directory.listFiles()?.filter { it.isFile && it.canonicalFile.parentFile == directory &&
                it.name.matches(Regex("[0-9a-f-]{36}\\.bin(?:\\.part)?")) }?.forEach { it.delete() }
        }
        cachePrepared = true
    }

    fun directory(context: Context): File = File(context.cacheDir, "support_chat").apply { mkdirs() }

    fun destination(context: Context): File = File(directory(context), "${UUID.randomUUID()}.bin")

    fun suggestedName(name: String): String = safeName(name)

    fun isAvailable(context: Context, path: String): Boolean = runCatching { checkedFile(context, path) }.isSuccess

    fun uri(context: Context, path: String) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))

    suspend fun stage(context: Context, rawUri: String): SupportAttachmentDraft {
        var stagedFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val uri = rawUri.toUri()
                require(uri.scheme == "content") { "support_attachment_unavailable" }
                val resolver = context.contentResolver
                var name = "attachment"
                var declaredSize = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
                    }
                }
                require(declaredSize <= SUPPORT_ATTACHMENT_MAX_BYTES) { "support_attachment_too_large" }
                name = safeName(name)
                val mime = resolver.getType(uri)?.takeIf { '\r' !in it && '\n' !in it } ?: "application/octet-stream"
                val file = destination(context).also { stagedFile = it }
                var size = 0L
                val coroutine = currentCoroutineContext()
                (resolver.openInputStream(uri) ?: error("support_attachment_unavailable")).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            coroutine.ensureActive()
                            if (read < 0) break
                            size += read
                            require(size <= SUPPORT_ATTACHMENT_MAX_BYTES) { "support_attachment_too_large" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                file.setExecutable(false, false)
                SupportAttachmentDraft(file.path, name, mime, size)
            }
        } catch (error: Throwable) {
            // Includes cancellation on the dispatcher hand-off after the stream has finished.
            stagedFile?.delete()
            throw error
        }
    }

    fun delete(context: Context, path: String) {
        val file = File(path)
        if (file.canonicalFile.parentFile == directory(context).canonicalFile) file.delete()
    }

    fun open(context: Context, path: String, mime: String) {
        require(canOpenSupportAttachment(mime)) { "support_attachment_save_only" }
        val file = checkedFile(context, path)
        val uri = uri(context, path)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).apply {
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    suspend fun export(context: Context, path: String, rawUri: String) = withContext(Dispatchers.IO) {
        val source = checkedFile(context, path)
        val uri = rawUri.toUri()
        require(uri.scheme == "content") { "support_attachment_unavailable" }
        val coroutine = currentCoroutineContext()
        (context.contentResolver.openOutputStream(uri, "wt") ?: error("support_attachment_unavailable")).use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    coroutine.ensureActive()
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun checkedFile(context: Context, path: String): File = File(path).also {
        require(it.canonicalFile.parentFile == directory(context).canonicalFile && it.isFile) { "support_attachment_unavailable" }
    }

    private fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_").trim('.', ' ').take(96).ifBlank { "attachment" }
}
