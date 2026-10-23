package com.noki.vpn.data

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import com.noki.vpn.SupportChatFiles
import com.noki.vpn.SupportFileProviderFixture
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SupportFileProviderFixture::class])
class PersonalizationBackgroundStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val store = PersonalizationBackgroundStore(context)
    private val directory get() = File(context.filesDir, "personalization_background")
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    @Test fun `photo is copied privately with an opaque name and deletion cannot escape the store`() = runBlocking {
        val source = File(SupportChatFiles.directory(context), "picked.png").apply {
            writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aT1EAAAAASUVORK5CYII="))
        }
        val name = store.stage(uri(source))
        assertTrue(PersonalizationBackgroundStore.isValidName(name))
        assertTrue(name.endsWith(".image"))
        val privateFile = store.resolve(name)!!
        assertArrayEquals(source.readBytes(), privateFile.readBytes())
        assertEquals(directory.canonicalFile, privateFile.parentFile.canonicalFile)
        assertNull(store.resolve("../${source.name}"))
        store.remove("../${source.name}")
        assertTrue(source.isFile)
        store.remove(name)
        assertNull(store.resolve(name))
        assertTrue(source.isFile)
    }

    @Test fun `failed and oversize imports remove their partial file and preserve the selected photo`() = runBlocking {
        directory.mkdirs()
        val selected = File(directory, "a41b08f0-426c-4ec1-93ae-e1ae549f535d.image").apply { writeText("current") }
        val invalid = File(SupportChatFiles.directory(context), "invalid.png").apply { writeBytes(byteArrayOf()) }
        assertTrue(runCatching { store.stage(uri(invalid)) }.isFailure)
        val large = File(SupportChatFiles.directory(context), "large.png")
        RandomAccessFile(large, "rw").use { it.setLength(PersonalizationBackgroundStore.MAX_BYTES + 1) }
        try {
            assertEquals("background_too_large", runCatching { store.stage(uri(large)) }.exceptionOrNull()?.message)
            assertEquals(listOf(selected.name), directory.listFiles()!!.map { it.name })
            assertEquals("current", selected.readText())
        } finally { large.delete(); selected.delete() }
        assertEquals("background_content_required", runCatching { store.stage(Uri.fromFile(invalid)) }.exceptionOrNull()?.message)
    }

    @Test fun `GIF remains intact after private import despite the opaque extension`() = runBlocking {
        val source = File(SupportChatFiles.directory(context), "animated.gif").apply {
            writeBytes(Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAAKAAAALAAAAAABAAEAAAICRAEAIfkEAAoAAAAsAAAAAAEAAQAAAgJEAQA7"))
        }
        val name = store.stage(uri(source))
        assertTrue(name.endsWith(".image"))
        assertArrayEquals(source.readBytes(), store.resolve(name)!!.readBytes())
        store.remove(name)
        assertTrue(source.isFile)
    }
}
