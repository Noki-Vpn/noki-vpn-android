package com.noki.vpn

import com.noki.vpn.data.BackendException
import com.noki.vpn.data.AuthRefreshRejectedException
import com.noki.vpn.data.BackendSupportChat
import com.noki.vpn.data.BackendSupportMessage
import com.noki.vpn.data.BackendSupportAttachment
import com.noki.vpn.data.SupportAttachmentDraft
import com.noki.vpn.data.SUPPORT_ATTACHMENT_MAX_BYTES
import com.noki.vpn.data.SUPPORT_ATTACHMENT_MAX_COUNT
import com.noki.vpn.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.util.UUID

data class SupportChatState(
    val isOpen: Boolean = false,
    val messages: List<BackendSupportMessage> = emptyList(),
    val nextBefore: Long? = null,
    val hasLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isSending: Boolean = false,
    val draft: String = "",
    val error: String? = null,
    val sendError: String? = null,
    val pendingAttachments: List<SupportAttachmentDraft> = emptyList(),
    val isPreparingAttachment: Boolean = false,
    val attachmentFiles: Map<Long, String> = emptyMap(),
    val attachmentLoading: Set<Long> = emptySet(),
    val attachmentErrors: Map<Long, String> = emptyMap(),
    val saveRequest: SupportAttachmentSaveRequest? = null,
    val savePickerPending: Boolean = false,
    val isSavingAttachment: Boolean = false,
    val fileError: String? = null,
)

internal enum class SupportAttachmentAction { Preview, Open, Save }
private data class PendingSupportSend(val content: String, val attachmentPaths: List<String>, val id: String)

/** Account-bound state; the server owns the conversation and its persisted history. */
internal class SupportChatWorkflow(
    private val scope: CoroutineScope,
    private val currentState: () -> AppUiState,
    private val publishState: (AppUiState) -> Unit,
    private val currentAuthAttempt: () -> AuthSessionAttempt?,
    private val isCurrent: (AuthSessionAttempt) -> Boolean,
    private val load: suspend (AuthSessionAttempt, Long?) -> BackendSupportChat,
    private val send: suspend (AuthSessionAttempt, String, String, List<SupportAttachmentDraft>) -> BackendSupportMessage,
    private val stageAttachment: suspend (String) -> SupportAttachmentDraft,
    private val downloadAttachment: suspend (AuthSessionAttempt, BackendSupportAttachment) -> String,
    private val openAttachment: (String, String) -> Unit,
    private val exportAttachment: suspend (String, String) -> Unit,
    private val deleteFile: (String) -> Unit,
    private val isFileAvailable: (String) -> Boolean,
) {
    private var generation = 0L
    private var historyGeneration = 0L
    private var conversationId: Long? = null
    private var accountEpoch: Long? = null
    private var loadJob: Job? = null
    private var sendJob: Job? = null
    private var pendingSend: PendingSupportSend? = null
    private var pickerAttempt: AuthSessionAttempt? = null
    private var stageJob: Job? = null
    private var exportJob: Job? = null
    private val fileJobs = mutableMapOf<Long, Job>()
    private val state get() = currentState().supportChat

    private fun update(value: SupportChatState) = publishState(currentState().copy(supportChat = value))

    fun open() {
        val attempt = currentAuthAttempt() ?: return
        if (accountEpoch != attempt.epoch) invalidate()
        accountEpoch = attempt.epoch
        update(state.copy(isOpen = true))
    }

    fun close() {
        pickerAttempt = null
        update(state.copy(isOpen = false))
    }

    fun beginAttachmentPick(): Boolean {
        if (!state.isOpen || state.isSending || state.isPreparingAttachment || state.pendingAttachments.size >= SUPPORT_ATTACHMENT_MAX_COUNT) return false
        pickerAttempt = currentAuthAttempt() ?: return false
        return true
    }

    fun selectAttachments(uris: List<String>) {
        val attempt = pickerAttempt.also { pickerAttempt = null } ?: return
        if (uris.isEmpty() || !isCurrent(attempt) || !state.isOpen) return
        if (uris.size + state.pendingAttachments.size > SUPPORT_ATTACHMENT_MAX_COUNT) {
            update(state.copy(fileError = readable(IllegalArgumentException("support_attachment_count"))))
            return
        }
        val owner = generation
        update(state.copy(isPreparingAttachment = true, fileError = null))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val staged = mutableListOf<SupportAttachmentDraft>()
            var retained = false
            try {
                for (uri in uris.distinct()) {
                    staged += stageAttachment(uri)
                    require(staged.sumOf { it.size } + state.pendingAttachments.sumOf { it.size } <= SUPPORT_ATTACHMENT_MAX_BYTES) {
                        "support_attachment_too_large"
                    }
                }
                if (owner != generation || !isCurrent(attempt)) return@launch
                update(state.copy(pendingAttachments = state.pendingAttachments + staged, isPreparingAttachment = false))
                retained = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owner == generation && isCurrent(attempt)) update(state.copy(isPreparingAttachment = false,
                    fileError = readable(if (error.message == "support_attachment_too_large") error else IllegalStateException("support_attachment_unavailable", error))))
            } finally {
                if (!retained) staged.forEach { deleteFile(it.path) }
                if (owner == generation) stageJob = null
            }
        }
        stageJob = job
        job.start()
    }

    fun removeAttachment(path: String) {
        if (state.isSending || state.isPreparingAttachment) return
        state.pendingAttachments.firstOrNull { it.path == path }?.let { deleteFile(it.path) }
        update(state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.path == path }, fileError = null))
    }

    fun loadAttachment(attachment: BackendSupportAttachment, action: SupportAttachmentAction) {
        if (attachment.id in fileJobs) return
        if (action == SupportAttachmentAction.Save && (state.saveRequest != null || state.isSavingAttachment)) return
        if (state.messages.none { message -> message.id == attachment.messageId && attachment in message.attachments }) return
        val attempt = currentAuthAttempt() ?: return
        val owner = generation
        val historyOwner = historyGeneration
        update(state.copy(attachmentLoading = state.attachmentLoading + attachment.id, attachmentErrors = state.attachmentErrors - attachment.id))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val path = state.attachmentFiles[attachment.id]?.takeIf(isFileAvailable)
                    ?: downloadAttachment(attempt, attachment)
                if (owner != generation || historyOwner != historyGeneration || !isCurrent(attempt)) { deleteFile(path); return@launch }
                update(state.copy(attachmentFiles = state.attachmentFiles + (attachment.id to path), attachmentLoading = state.attachmentLoading - attachment.id))
                when (action) {
                    SupportAttachmentAction.Preview -> Unit
                    SupportAttachmentAction.Open -> openAttachment(path, attachment.mime)
                    SupportAttachmentAction.Save -> update(state.copy(
                        saveRequest = SupportAttachmentSaveRequest(path, attachment.name, attachment.mime), savePickerPending = true, fileError = null))
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (owner == generation && historyOwner == historyGeneration && isCurrent(attempt)) update(state.copy(
                    attachmentLoading = state.attachmentLoading - attachment.id,
                    attachmentFiles = if (error.message == "support_attachment_unavailable") state.attachmentFiles - attachment.id else state.attachmentFiles,
                    attachmentErrors = state.attachmentErrors + (attachment.id to readable(error))))
            } finally { if (owner == generation && historyOwner == historyGeneration) fileJobs.remove(attachment.id) }
        }
        fileJobs[attachment.id] = job
        job.start()
    }

    fun markSavePickerLaunched() = update(state.copy(savePickerPending = false))

    fun saveAttachmentTo(uri: String?) {
        val request = state.saveRequest ?: return
        update(state.copy(saveRequest = null, savePickerPending = false))
        if (uri == null) return
        val attempt = currentAuthAttempt() ?: return
        val owner = generation
        val historyOwner = historyGeneration
        update(state.copy(isSavingAttachment = true, fileError = null))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                exportAttachment(request.path, uri)
                if (owner == generation && historyOwner == historyGeneration && isCurrent(attempt)) update(state.copy(isSavingAttachment = false))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owner == generation && historyOwner == historyGeneration && isCurrent(attempt)) update(state.copy(isSavingAttachment = false,
                    fileError = tr(currentState().personalizationSettings.language, "Не удалось сохранить файл. Нажмите «Сохранить» ещё раз.", "Could not save the file. Tap Save again.")))
            } finally { if (owner == generation && historyOwner == historyGeneration) exportJob = null }
        }
        exportJob = job
        job.start()
    }

    fun updateDraft(value: String) {
        update(state.copy(draft = value.take(4_000), sendError = null))
    }

    fun refresh(older: Boolean = false) {
        if (loadJob != null) return
        val before = if (older) state.nextBefore ?: return else null
        val attempt = currentAuthAttempt() ?: return
        val owner = generation
        update(state.copy(isLoading = !older, isLoadingOlder = older, error = null))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = load(attempt, before)
                if (owner != generation || !isCurrent(attempt)) return@launch
                val conversationChanged = conversationId != null && conversationId != page.conversationId
                if (conversationChanged) clearHistory()
                conversationId = page.conversationId
                val missedPage = !older && state.messages.isNotEmpty() && page.messages.isNotEmpty() &&
                    page.messages.none { fresh -> state.messages.any { it.id == fresh.id } } &&
                    page.messages.maxOf { it.id } > state.messages.maxOf { it.id }
                update(state.copy(
                    messages = merge(if (missedPage) emptyList() else state.messages, page.messages),
                    nextBefore = if (conversationChanged || older || !state.hasLoaded || missedPage) page.nextBefore else state.nextBefore,
                    hasLoaded = true,
                    isLoading = false,
                    isLoadingOlder = false,
                    error = null,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owner == generation && isCurrent(attempt)) update(state.copy(
                    isLoading = false, isLoadingOlder = false, error = readable(error),
                ))
            } finally {
                if (owner == generation) loadJob = null
            }
        }
        loadJob = job
        job.start()
    }

    fun submit() {
        val submittedDraft = state.draft
        val content = submittedDraft.trim()
        val attachments = state.pendingAttachments
        if (sendJob != null || state.isPreparingAttachment || (content.isEmpty() && attachments.isEmpty())) return
        val attempt = currentAuthAttempt() ?: return
        val owner = generation
        val historyOwner = historyGeneration
        // Retrying an uncertain delivery must reuse the idempotency key.
        val paths = attachments.map { it.path }
        val request = pendingSend?.takeIf { it.content == content && it.attachmentPaths == paths }
            ?: PendingSupportSend(content, paths, UUID.randomUUID().toString()).also { pendingSend = it }
        update(state.copy(isSending = true, sendError = null))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val message = send(attempt, request.content, request.id, attachments)
                if (owner != generation || historyOwner != historyGeneration || !isCurrent(attempt)) return@launch
                pendingSend = null
                val files = if (message.attachments.size == attachments.size) message.attachments.zip(attachments)
                    .filter { (remote, local) -> remote.size == local.size && remote.mime == local.mime }
                    .associate { (remote, local) -> remote.id to local.path } else emptyMap()
                attachments.filter { it.path !in files.values }.forEach { deleteFile(it.path) }
                update(state.copy(messages = merge(state.messages, listOf(message)),
                    draft = if (state.draft == submittedDraft) "" else state.draft, isSending = false, sendError = null,
                    pendingAttachments = emptyList(), attachmentFiles = state.attachmentFiles + files))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owner == generation && historyOwner == historyGeneration && isCurrent(attempt)) update(state.copy(isSending = false, sendError = readable(error)))
            } finally {
                if (owner == generation && historyOwner == historyGeneration) sendJob = null
            }
        }
        sendJob = job
        job.start()
    }

    private fun clearHistory() {
        historyGeneration++
        sendJob?.cancel()
        exportJob?.cancel()
        fileJobs.values.forEach { it.cancel() }
        fileJobs.clear()
        (state.attachmentFiles.values + listOfNotNull(state.saveRequest?.path)).distinct().forEach(deleteFile)
        sendJob = null
        pendingSend = null
        exportJob = null
        update(state.copy(messages = emptyList(), nextBefore = null, isSending = false, sendError = null,
            attachmentFiles = emptyMap(), attachmentLoading = emptySet(), attachmentErrors = emptyMap(),
            saveRequest = null, savePickerPending = false, isSavingAttachment = false, fileError = null))
    }

    fun invalidate() {
        generation++
        clearHistory()
        conversationId = null
        loadJob?.cancel()
        stageJob?.cancel()
        state.pendingAttachments.forEach { deleteFile(it.path) }
        loadJob = null
        pickerAttempt = null
        stageJob = null
        accountEpoch = null
        update(SupportChatState())
    }

    private fun readable(error: Exception): String {
        val language = currentState().personalizationSettings.language
        if (error.message == "support_attachment_too_large") return tr(language, "Общий размер вложений — не больше 40 МБ.", "Attachments must total 40 MB or less.")
        if (error.message == "support_attachment_count") return tr(language, "Можно прикрепить до 10 файлов.", "You can attach up to 10 files.")
        if (error is android.content.ActivityNotFoundException) return tr(language, "Нет приложения для этого файла. Нажмите «Сохранить».", "No app can open this file. Tap Save.")
        if (error is TimeoutCancellationException) return tr(language, "Скачивание заняло слишком много времени. Повторите попытку.", "The download timed out. Please try again.")
        if (error.message == "support_attachment_unavailable") return tr(language, "Не удалось открыть файл. Выберите его ещё раз.", "Could not open the file. Select it again.")
        if (error.message == "support_attachment_save_only") return tr(language, "Этот формат можно только сохранить.", "This file type can only be saved.")
        if (error is AuthRefreshRejectedException ||
            error is BackendException && error.statusCode == 401 && error.message == "auth_required") {
            return tr(language, "Сессия истекла. Войдите в аккаунт снова.", "Your session expired. Sign in again.")
        }
        val status = (error as? BackendException)?.statusCode
        return when (status) {
            401 -> tr(language, "Не удалось подтвердить доступ к чату. Повторите попытку.", "Could not verify access to chat. Please try again.")
            403 -> tr(language, "Чат недоступен для этой сессии.", "Chat is unavailable for this session.")
            429 -> tr(language, "Слишком много запросов. Подождите немного и повторите.", "Too many requests. Wait a moment and try again.")
            400, 422 -> tr(language, "Сообщение не принято. Проверьте текст и повторите.", "Your message was not accepted. Check the text and try again.")
            502, 503, 504 -> tr(language, "Чат временно недоступен. Повторите через несколько секунд.", "Chat is temporarily unavailable. Try again in a few seconds.")
            else -> if (status != null) tr(language, "Не удалось связаться с поддержкой. Попробуйте ещё раз.", "Could not reach support. Please try again.")
                else AppErrorMapper.readableNetworkError(language, error)
        }
    }

    private fun merge(previous: List<BackendSupportMessage>, incoming: List<BackendSupportMessage>) =
        (previous + incoming).associateBy { it.id }.values.sortedWith(compareBy({ it.createdAt }, { it.id }))
}
