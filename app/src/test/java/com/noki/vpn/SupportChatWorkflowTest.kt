package com.noki.vpn

import com.noki.vpn.data.SupportAttachmentDraft
import com.noki.vpn.data.SUPPORT_ATTACHMENT_MAX_BYTES
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendSupportChat
import com.noki.vpn.data.BackendSupportMessage
import com.noki.vpn.data.BackendSupportAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SupportChatWorkflowTest {
    @Test fun `typing while sending survives the acknowledgement and duplicate send stays blocked`() = runBlocking {
        val f = Fixture(this)
        val delivery = CompletableDeferred<BackendSupportMessage>()
        var requests = 0
        f.sendResponse = { content, _ ->
            requests++
            assertEquals("First", content)
            delivery.await()
        }
        f.workflow.open()
        f.workflow.updateDraft("First"); f.workflow.submit(); yield()
        assertTrue(f.state.supportChat.isSending)
        f.workflow.updateDraft("Next message")
        assertEquals("Next message", f.state.supportChat.draft)
        f.workflow.submit(); yield()
        assertEquals(1, requests)
        delivery.complete(message(4, "First")); yield()
        assertEquals("Next message", f.state.supportChat.draft)
        assertFalse(f.state.supportChat.isSending)
        f.sendResponse = { content, _ -> message(5, content) }
        f.workflow.submit(); yield()
        assertEquals("Next message", f.state.supportChat.messages.last().content)
        assertEquals("", f.state.supportChat.draft)
    }

    @Test fun `immediate completion does not leave a finished request blocking future requests`() {
        val f = Fixture(CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        f.workflow.open()
        f.workflow.refresh()
        f.loadResponse = { page(4) }
        f.workflow.refresh()
        assertEquals(listOf(4L), f.state.supportChat.messages.map { it.id })
        f.workflow.updateDraft("First"); f.workflow.submit()
        f.sendResponse = { text, _ -> message(5, text) }
        f.workflow.updateDraft("Second"); f.workflow.submit()
        assertEquals("Second", f.state.supportChat.messages.last().content)
        assertFalse(f.state.supportChat.isSending)
    }

    private fun message(id: Long, content: String = "Message $id") = BackendSupportMessage(id, content, "incoming", id)
    private fun page(vararg ids: Long, before: Long? = null) = BackendSupportChat(1, "open", ids.map { message(it) }, before)

    private inner class Fixture(scope: CoroutineScope) {
        var state = AppUiState(isAuthenticated = true)
        var epoch = 0L
        var loadResponse: suspend (Long?) -> BackendSupportChat = { page(2, 3, before = 2) }
        var sendResponse: suspend (String, String) -> BackendSupportMessage = { text, _ -> message(4, text) }
        var sendFile: List<SupportAttachmentDraft> = emptyList()
        var staged: suspend (String) -> SupportAttachmentDraft = { SupportAttachmentDraft("private/picked.txt", "picked.txt", "text/plain", 3) }
        var downloaded: suspend (BackendSupportAttachment) -> String = { "private/${it.id}.txt" }
        var fileAvailable: (String) -> Boolean = { true }
        val deleted = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val exported = mutableListOf<Pair<String, String>>()
        val workflow = SupportChatWorkflow(scope, { state }, { state = it },
            { AuthSessionAttempt("access", epoch) }, { it.epoch == epoch },
            { _, before -> loadResponse(before) }, { _, text, id, file -> sendFile = file; sendResponse(text, id) },
            { staged(it) }, { _, file -> downloaded(file) }, { path, _ -> opened += path },
            { path, uri -> exported += path to uri }, { deleted += it }, { fileAvailable(it) })
    }

    @Test fun `history merges canonical IDs and older pages without duplicates`() = runBlocking {
        val f = Fixture(this)
        f.workflow.open()
        f.workflow.refresh(); yield()
        assertEquals(listOf(2L, 3L), f.state.supportChat.messages.map { it.id })
        assertEquals(2L, f.state.supportChat.nextBefore)
        f.loadResponse = { before -> assertEquals(2L, before); page(1, 2) }
        f.workflow.refresh(older = true); yield()
        assertEquals(listOf(1L, 2L, 3L), f.state.supportChat.messages.map { it.id })
        assertNull(f.state.supportChat.nextBefore)
        f.loadResponse = { page(3, 4) }
        f.workflow.refresh(); yield()
        assertEquals(listOf(1L, 2L, 3L, 4L), f.state.supportChat.messages.map { it.id })
        assertNull(f.state.supportChat.nextBefore)
    }

    @Test fun `missed latest page offers all intervening history again`() = runBlocking {
        val f = Fixture(this)
        f.workflow.open(); f.workflow.refresh(); yield()
        f.loadResponse = { page(30, 31, before = 30) }
        f.workflow.refresh(); yield()
        assertEquals(listOf(30L, 31L), f.state.supportChat.messages.map { it.id })
        assertEquals(30L, f.state.supportChat.nextBefore)
    }

    @Test fun `send retry keeps draft and idempotency key and double tap submits once`() = runBlocking {
        val f = Fixture(this)
        val keys = mutableListOf<String>()
        f.workflow.open()
        f.workflow.updateDraft("  Помогите  ")
        f.sendResponse = { content, id ->
            assertEquals("Помогите", content)
            keys += id
            throw IOException("connection lost after sending")
        }
        f.workflow.submit(); f.workflow.submit(); yield()
        assertEquals(1, keys.size)
        assertEquals("  Помогите  ", f.state.supportChat.draft)
        assertNotNull(f.state.supportChat.sendError)
        assertFalse(f.state.supportChat.isSending)
        f.sendResponse = { content, id -> keys += id; message(4, content) }
        f.workflow.submit(); yield()
        assertEquals(keys[0], keys[1])
        assertEquals("", f.state.supportChat.draft)
        assertNull(f.state.supportChat.sendError)
        assertEquals(listOf(4L), f.state.supportChat.messages.map { it.id })
        f.workflow.updateDraft("   "); f.workflow.submit(); yield()
        assertEquals(2, keys.size)
    }

    @Test fun `failed load stops loading and can be retried`() = runBlocking {
        val f = Fixture(this)
        f.workflow.open()
        f.loadResponse = { throw BackendException("private upstream detail", 503) }
        f.workflow.refresh(); yield()
        assertFalse(f.state.supportChat.isLoading)
        assertFalse(f.state.supportChat.isLoadingOlder)
        assertNotNull(f.state.supportChat.error)
        assertFalse(f.state.supportChat.error!!.contains("private upstream"))
        f.loadResponse = { page(1) }
        f.workflow.refresh(); yield()
        assertTrue(f.state.supportChat.hasLoaded)
        assertNull(f.state.supportChat.error)
    }

    @Test fun `logout discards uncancellable history and send responses and account draft`() = runBlocking {
        val f = Fixture(this)
        val history = CompletableDeferred<BackendSupportChat>()
        val delivery = CompletableDeferred<BackendSupportMessage>()
        f.loadResponse = { withContext(NonCancellable) { history.await() } }
        f.sendResponse = { _, _ -> withContext(NonCancellable) { delivery.await() } }
        f.workflow.open(); f.workflow.refresh()
        f.workflow.updateDraft("private account text"); f.workflow.submit(); yield()
        f.workflow.invalidate(); f.epoch++
        history.complete(page(10)); delivery.complete(message(11)); yield(); yield()
        assertEquals(SupportChatState(), f.state.supportChat)
        f.workflow.open()
        assertTrue(f.state.supportChat.isOpen)
        assertTrue(f.state.supportChat.messages.isEmpty())
        assertEquals("", f.state.supportChat.draft)
    }

    @Test fun `attachment without caption retries the same file and UUID and clears after canonical success`() = runBlocking {
        val f = Fixture(this)
        val keys = mutableListOf<String>()
        f.workflow.open()
        assertTrue(f.workflow.beginAttachmentPick())
        f.workflow.selectAttachments(listOf("content://picker/file")); yield()
        assertTrue(f.state.supportChat.pendingAttachments.isNotEmpty())
        f.sendResponse = { content, id -> assertEquals("", content); keys += id; throw IOException("network lost") }
        f.workflow.submit(); yield()
        val picked = f.sendFile
        assertEquals("private/picked.txt", picked.single().path)
        assertTrue(f.state.supportChat.pendingAttachments.isNotEmpty())
        val attachment = BackendSupportAttachment(7, 4, "file", "picked.txt", 3, "text/plain")
        f.sendResponse = { content, id -> keys += id; message(4, content).copy(attachments = listOf(attachment)) }
        f.workflow.submit(); yield()
        assertEquals(keys[0], keys[1])
        assertEquals(picked, f.sendFile)
        assertTrue(f.state.supportChat.pendingAttachments.isEmpty())
        assertEquals("private/picked.txt", f.state.supportChat.attachmentFiles[7])
        f.workflow.invalidate()
        assertEquals(listOf("private/picked.txt"), f.deleted)
    }

    @Test fun `picker result from an old account is ignored and uncancellable downloaded files are deleted`() = runBlocking {
        val f = Fixture(this)
        f.workflow.open(); f.workflow.beginAttachmentPick()
        f.workflow.invalidate(); f.epoch++
        f.workflow.selectAttachments(listOf("content://old-account/file")); yield()
        assertTrue(f.state.supportChat.pendingAttachments.isEmpty())
        f.workflow.open()
        val attachment = BackendSupportAttachment(8, 4, "file", "reply.txt", 3, "text/plain")
        f.state = f.state.copy(supportChat = f.state.supportChat.copy(messages = listOf(message(4).copy(attachments = listOf(attachment)))))
        val gate = CompletableDeferred<String>()
        f.downloaded = { withContext(NonCancellable) { gate.await() } }
        f.workflow.loadAttachment(attachment, SupportAttachmentAction.Open); yield()
        f.workflow.invalidate(); f.epoch++
        gate.complete("private/old-account.txt"); yield(); yield()
        assertTrue(f.opened.isEmpty())
        assertTrue(f.state.supportChat.attachmentFiles.isEmpty())
        assertTrue(f.deleted.contains("private/old-account.txt"))
    }

    @Test fun `downloaded attachments open and save only from the current account history`() = runBlocking {
        val f = Fixture(this)
        f.workflow.open()
        val attachment = BackendSupportAttachment(8, 4, "file", "reply.txt", 3, "text/plain")
        f.workflow.loadAttachment(attachment, SupportAttachmentAction.Open); yield()
        assertTrue(f.opened.isEmpty())
        f.state = f.state.copy(supportChat = f.state.supportChat.copy(messages = listOf(message(4).copy(attachments = listOf(attachment)))))
        f.workflow.loadAttachment(attachment, SupportAttachmentAction.Preview); yield()
        assertEquals("private/8.txt", f.state.supportChat.attachmentFiles[8])
        f.workflow.loadAttachment(attachment, SupportAttachmentAction.Open); yield()
        assertEquals(listOf("private/8.txt"), f.opened)
        f.workflow.loadAttachment(attachment, SupportAttachmentAction.Save); yield()
        assertTrue(f.state.supportChat.savePickerPending)
        f.workflow.markSavePickerLaunched()
        f.workflow.saveAttachmentTo("content://documents/saved"); yield()
        assertEquals(listOf("private/8.txt" to "content://documents/saved"), f.exported)
        assertFalse(f.state.supportChat.isSavingAttachment)
        f.workflow.loadAttachment(attachment, SupportAttachmentAction.Save); yield()
        f.workflow.invalidate()
        f.workflow.saveAttachmentTo("content://documents/stale"); yield()
        assertEquals(1, f.exported.size)
    }
    @Test fun `attachment batches are bounded and partial staging is cleaned`() = runBlocking {
        val f = Fixture(this)
        f.workflow.open(); f.workflow.beginAttachmentPick()
        f.workflow.selectAttachments((1..11).map { "content://file/$it" }); yield()
        assertTrue(f.state.supportChat.pendingAttachments.isEmpty())
        assertNotNull(f.state.supportChat.fileError)
        f.staged = { uri -> SupportAttachmentDraft(uri, "file.mp4", "video/mp4", SUPPORT_ATTACHMENT_MAX_BYTES / 2 + 1) }
        f.workflow.beginAttachmentPick()
        f.workflow.selectAttachments(listOf("content://one", "content://two")); yield()
        assertTrue(f.state.supportChat.pendingAttachments.isEmpty())
        assertEquals(setOf("content://one", "content://two"), f.deleted.toSet())
        f.staged = { uri -> SupportAttachmentDraft(uri, "file.mp4", "video/mp4", 3) }
        f.workflow.beginAttachmentPick()
        f.workflow.selectAttachments((1..10).map { "content://file/$it" }); yield()
        assertEquals(10, f.state.supportChat.pendingAttachments.size)
        f.workflow.removeAttachment("content://file/1")
        assertEquals(9, f.state.supportChat.pendingAttachments.size)
    }

    @Test fun `evicted cached attachment downloads again before preview open or save`() = runBlocking {
        for (action in SupportAttachmentAction.entries) {
            val f = Fixture(this)
            f.workflow.open()
            val attachment = BackendSupportAttachment(8, 4, "file", "reply.txt", 3, "text/plain")
            f.state = f.state.copy(supportChat = f.state.supportChat.copy(
                messages = listOf(message(4).copy(attachments = listOf(attachment))),
                attachmentFiles = mapOf(8L to "private/evicted.txt")))
            f.fileAvailable = { it != "private/evicted.txt" }
            var downloads = 0
            f.downloaded = { downloads++; "private/new.txt" }
            f.workflow.loadAttachment(attachment, action); yield()
            assertEquals(1, downloads)
            assertEquals("private/new.txt", f.state.supportChat.attachmentFiles[8])
            when (action) {
                SupportAttachmentAction.Open -> assertEquals(listOf("private/new.txt"), f.opened)
                SupportAttachmentAction.Save -> assertEquals("private/new.txt", f.state.supportChat.saveRequest?.path)
                SupportAttachmentAction.Preview -> assertTrue(f.opened.isEmpty())
            }
        }
    }

}
