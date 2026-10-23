package com.noki.vpn

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import com.noki.vpn.data.SUPPORT_ATTACHMENT_MAX_BYTES
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SupportFileProviderFixture::class])
class SupportChatFilesTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    @Test fun `startup removes abandoned owned files once and preserves other cache directories`() {
        val orphan = SupportChatFiles.destination(context).apply { writeText("old") }
        val part = File(orphan.path + ".part").apply { writeText("partial") }
        val unrelated = File(context.cacheDir, "other-cache").apply { writeText("keep") }
        assertTrue(SupportChatFiles.isAvailable(context, orphan.path))
        assertFalse(SupportChatFiles.isAvailable(context, unrelated.path))
        SupportChatFiles.prepareCache(context)
        assertFalse(SupportChatFiles.isAvailable(context, orphan.path))
        assertFalse(part.exists())
        assertTrue(unrelated.exists())
        val active = SupportChatFiles.destination(context).apply { writeText("current") }
        SupportChatFiles.prepareCache(context)
        assertTrue(SupportChatFiles.isAvailable(context, active.path))
    }

    @Test fun `picked content streams to a private file and export needs an explicit document URI`() = runBlocking {
        val source = File(SupportChatFiles.directory(context), "picked.txt").apply { writeText("private content") }
        val draft = SupportChatFiles.stage(context, uri(source).toString())
        assertEquals("picked.txt", draft.name)
        assertTrue(File(draft.path).name.matches(Regex("[0-9a-f-]{36}\\.bin")))
        assertEquals(source.readText(), File(draft.path).readText())
        assertEquals(source.length(), draft.size)
        val output = File(SupportChatFiles.directory(context), "saved.txt").apply { createNewFile() }
        SupportChatFiles.export(context, draft.path, uri(output).toString())
        assertEquals("private content", output.readText())
        SupportChatFiles.open(context, draft.path, draft.mime)
        val intent = shadowOf(context).nextStartedActivity
        assertEquals("content", intent.data?.scheme)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.toUri(0).contains("Bearer"))
        SupportChatFiles.delete(context, draft.path)
        assertFalse(File(draft.path).exists())
    }

    @Test fun `forty mebibyte boundary rejects oversize before copying and accepts empty ordinary files`() = runBlocking {
        val large = File(SupportChatFiles.directory(context), "large.bin")
        RandomAccessFile(large, "rw").use { it.setLength(SUPPORT_ATTACHMENT_MAX_BYTES + 1) }
        val before = SupportChatFiles.directory(context).listFiles()!!.size
        val error = runCatching { SupportChatFiles.stage(context, uri(large).toString()) }.exceptionOrNull()
        assertEquals("support_attachment_too_large", error?.message)
        assertEquals(before, SupportChatFiles.directory(context).listFiles()!!.size)
        val empty = File(SupportChatFiles.directory(context), "empty.txt").apply { createNewFile() }
        assertEquals(0L, SupportChatFiles.stage(context, uri(empty).toString()).size)
    }

    @Test fun `provider and cleanup cannot expose or delete other private app files`() {
        val other = File(context.cacheDir, "private-secret.txt").apply { writeText("private") }
        assertThrows(IllegalArgumentException::class.java) { SupportChatFiles.open(context, other.path, "text/plain") }
        val parser = context.resources.getXml(R.xml.noki_file_paths)
        val cachePaths = mutableListOf<String?>()
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "cache-path") cachePaths += parser.getAttributeValue(null, "path")
            parser.next()
        }
        assertEquals(listOf("support_chat/", "android_updates/"), cachePaths)
        SupportChatFiles.delete(context, other.path)
        assertTrue(other.isFile)
        val error = runCatching { runBlocking { SupportChatFiles.stage(context, "file://${other.path}") } }.exceptionOrNull()
        assertEquals("support_attachment_unavailable", error?.message)
    }

    @Test fun `executable and active content can only be explicitly saved`() {
        val file = SupportChatFiles.destination(context).apply { writeText("data") }
        for (mime in listOf("application/vnd.android.package-archive", "application/x-executable", "text/x-shellscript", "text/html", "image/svg+xml")) {
            assertFalse(com.noki.vpn.data.canOpenSupportAttachment(mime))
            val error = runCatching { SupportChatFiles.open(context, file.path, mime) }.exceptionOrNull()
            assertEquals("support_attachment_save_only", error?.message)
        }
        assertNull(shadowOf(context).nextStartedActivity)
        assertTrue(com.noki.vpn.data.canOpenSupportAttachment("application/pdf"))
        assertEquals("bad_name_.txt", SupportChatFiles.suggestedName("../bad:name?.txt"))
    }
}
