package com.noki.vpn.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noki.vpn.AppUiState
import com.noki.vpn.R
import com.noki.vpn.SupportChatState
import com.noki.vpn.AppBroadcastNotifier
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendSupportMessage
import com.noki.vpn.data.BackendSupportAttachment
import com.noki.vpn.data.canOpenSupportAttachment
import com.noki.vpn.SupportChatFiles
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SupportScreen(
    state: AppUiState,
    sharedBackdrop: PrismalGlassLayer,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
    onOpenChat: () -> Unit = {},
    onCloseChat: () -> Unit = {},
    onRefreshChat: (Boolean) -> Unit = {},
    onDraftChanged: (String) -> Unit = {},
    onSendMessage: () -> Unit = {},
    onPickAttachment: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onPreviewAttachment: (BackendSupportAttachment) -> Unit = {},
    onOpenAttachment: (BackendSupportAttachment) -> Unit = {},
    onSaveAttachment: (BackendSupportAttachment) -> Unit = {},
) {
    val language = state.personalizationSettings.language
    val visibility = LocalSharedDestinationVisibility.current
    val active = visibility == SharedDestinationVisibility.FullyVisible || visibility == SharedDestinationVisibility.Incoming
    val effectiveGlass = liveGlassEnabled && visibility != SharedDestinationVisibility.Precomposing
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state.supportChat.isOpen, active, lifecycle, state.userProfile.backendUserId) {
        val owner = Any()
        fun updateVisibility() = AppBroadcastNotifier.setSupportChatVisibility(owner, state.userProfile.backendUserId,
            state.supportChat.isOpen && active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose { lifecycle.removeObserver(observer); AppBroadcastNotifier.setSupportChatVisibility(owner, "", false) }
    }
    LaunchedEffect(state.supportChat.isOpen, active, lifecycle) {
        if (state.supportChat.isOpen && active) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                onRefreshChat(false)
                delay(10_000)
            }
        }
    }
    BackHandler(enabled = state.supportChat.isOpen && active, onBack = onCloseChat)

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .then(if (showBackground) Modifier.background(SettingsBgBase) else Modifier)
            .statusBarsPadding(),
    ) {
        val metrics = nokiAdaptiveMetrics(maxWidth)
        if (showBackground) {
            Box(Modifier.fillMaxSize().then(if (effectiveGlass) Modifier.prismalGlassLayer(sharedBackdrop) else Modifier)) {
                HomeBackground(liveGlassEnabled = effectiveGlass)
            }
        }
        if (state.supportChat.isOpen) {
            SupportChatContent(
                chat = state.supportChat, language = language, metrics = metrics,
                backdrop = sharedBackdrop, liveGlassEnabled = effectiveGlass,
                onBack = onCloseChat, onRefresh = onRefreshChat,
                onDraftChanged = onDraftChanged, onSend = onSendMessage,
                onPickAttachment = onPickAttachment, onRemoveAttachment = onRemoveAttachment,
                onPreviewAttachment = onPreviewAttachment, onOpenAttachment = onOpenAttachment, onSaveAttachment = onSaveAttachment,
            )
        } else {
            SupportContactContent(state, metrics, sharedBackdrop, effectiveGlass, onOpenChat)
        }
    }
}

@Composable
private fun SupportContactContent(
    state: AppUiState,
    metrics: NokiAdaptiveMetrics,
    backdrop: PrismalGlassLayer,
    liveGlassEnabled: Boolean,
    onOpenChat: () -> Unit,
) {
    val language = state.personalizationSettings.language
    val context = LocalContext.current
    val email = "noki_support@ykino.tech"
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = metrics.contentStart),
    ) {
        SupportLabel(tr(language, "Поддержка", "Support"), 24,
            modifier = Modifier.fillMaxWidth().padding(top = metrics.dp(48f), start = 18.dp, end = 18.dp))
        Box(Modifier.fillMaxSize().padding(top = metrics.dp(96f), bottom = metrics.dp(110f)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(metrics.dp(14f)),
        ) {
            SupportChannelCard(
                title = tr(language, "Онлайн-чат", "Online chat"),
                primary = true, metrics = metrics, backdrop = backdrop, liveGlassEnabled = liveGlassEnabled,
                icon = { Icon(Icons.Rounded.Forum, null, tint = SettingsAccentPrimary, modifier = Modifier.size(26.dp)) },
                onClick = onOpenChat,
            )
            SupportChannelCard(
                title = "Telegram", subtitle = "@NokiSupport", metrics = metrics, backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                icon = { Icon(painterResource(R.drawable.support_telegram), null, tint = Color.Unspecified, modifier = Modifier.size(29.dp)) },
                onClick = { openTelegramSupport(context, language) },
            )
            SupportChannelCard(
                title = tr(language, "Электронная почта", "Email"), subtitle = email, metrics = metrics,
                backdrop = backdrop, liveGlassEnabled = liveGlassEnabled,
                icon = { Icon(painterResource(R.drawable.security_email_icon), null, tint = SettingsAccentSecondary, modifier = Modifier.size(27.dp)) },
                onClick = {
                    launchSupportIntent(context, Intent(Intent.ACTION_SENDTO, "mailto:$email".toUri()).putExtra(Intent.EXTRA_EMAIL, arrayOf(email)), language)
                },
            )
        }
        }
    }
}

@Composable
private fun SupportChannelCard(
    title: String,
    subtitle: String? = null,
    metrics: NokiAdaptiveMetrics,
    backdrop: PrismalGlassLayer,
    liveGlassEnabled: Boolean,
    primary: Boolean = false,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier.fillMaxWidth()
            .nokiSettingsActionGlassSurface(
                shape = shape, backdrop = backdrop, liveGlassEnabled = liveGlassEnabled,
                scale = metrics.contentScale,
                surfaceColor = if (primary) Color(0xFF173A3B).copy(alpha = 0.84f) else NokiSettingsActionGlassStyle.surfaceColor,
            )
            .border(0.7.dp, if (primary) SettingsAccentPrimary.copy(alpha = 0.3f) else SettingsStroke.copy(alpha = 0.55f), shape)
            .clip(shape).clickable(onClick = onClick)
            .heightIn(min = 90.dp).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.045f)), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SupportLabel(title, 18, FontWeight.SemiBold)
            if (subtitle != null) SupportLabel(subtitle, 14, color = SettingsTextSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = if (primary) SettingsAccentPrimary else SettingsTextMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SupportChatContent(
    chat: SupportChatState,
    language: AppLanguage,
    metrics: NokiAdaptiveMetrics,
    backdrop: PrismalGlassLayer,
    liveGlassEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: (Boolean) -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPreviewAttachment: (BackendSupportAttachment) -> Unit,
    onOpenAttachment: (BackendSupportAttachment) -> Unit,
    onSaveAttachment: (BackendSupportAttachment) -> Unit,
) {
    val canSend = (chat.draft.isNotBlank() || chat.pendingAttachments.isNotEmpty()) && !chat.isSending && !chat.isPreparingAttachment
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { !list.canScrollBackward } }
    var lastMessageId by remember { mutableLongStateOf(0L) }
    var unread by remember { mutableStateOf(false) }
    val latest = chat.messages.lastOrNull()
    LaunchedEffect(latest?.id) {
        if (latest != null && latest.id != lastMessageId) {
            if (lastMessageId == 0L || atBottom || !latest.isFromSupport) {
                list.scrollToItem(0)
            } else unread = true
            lastMessageId = latest.id
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) unread = false }
    Column(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding()
            .padding(horizontal = metrics.contentStart),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(language, "Назад к поддержке", "Back to support"), tint = SettingsTextPrimary)
            }
            SupportLabel(tr(language, "Поддержка Noki", "Noki support"), 18, FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.messages.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (chat.isLoading && !chat.hasLoaded) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = SettingsAccentPrimary, strokeWidth = 2.dp)
                        SupportLabel(tr(language, "Загружаем переписку", "Loading your conversation"), 15, color = SettingsTextSecondary)
                    } else if (chat.error == null) {
                        Icon(Icons.Rounded.Forum, null, tint = SettingsAccentPrimary, modifier = Modifier.size(40.dp))
                        SupportLabel(tr(language, "С чего начнём?", "How can we help?"), 24, FontWeight.SemiBold)
                        SupportLabel(tr(language, "Расскажите, что случилось. Ответ появится в этом чате.", "Tell us what happened. The reply will appear here."), 15, color = SettingsTextSecondary, textAlign = TextAlign.Center)
                    }
                }
            }
            LazyColumn(
                state = list, modifier = Modifier.fillMaxSize(), reverseLayout = true,
                contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(chat.messages.asReversed(), key = { it.id }) { message ->
                    SupportMessageBubble(message, metrics.contentWidth * 0.82f, chat, language,
                        onPreviewAttachment, onOpenAttachment, onSaveAttachment)
                }
                if (chat.nextBefore != null) item(key = "older") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { onRefresh(true) }, enabled = !chat.isLoadingOlder) {
                            if (chat.isLoadingOlder) CircularProgressIndicator(Modifier.size(18.dp), color = SettingsAccentPrimary, strokeWidth = 2.dp)
                            else SupportLabel(tr(language, "Предыдущие сообщения", "Earlier messages"), 14, color = SettingsAccentSecondary)
                        }
                    }
                }
            }
            if (unread && !atBottom) {
                TextButton(
                    onClick = { scope.launch { list.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                        .background(SettingsBgSoft, RoundedCornerShape(24.dp))
                        .border(1.dp, SettingsAccentPrimary.copy(alpha = 0.3f), RoundedCornerShape(24.dp)),
                ) {
                    SupportLabel(tr(language, "Новые сообщения", "New messages"), 14, color = SettingsAccentPrimary)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = SettingsAccentPrimary)
                }
            }
        }
        chat.error?.let { SupportChatError(it, language, onRetry = { onRefresh(false) }) }
        chat.sendError?.let { SupportChatError(it, language, onRetry = onSend) }
        chat.fileError?.let { SupportLabel(it, 13, color = SettingsError, modifier = Modifier.padding(top = 6.dp)) }
        if (chat.isPreparingAttachment || chat.isSavingAttachment) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = SettingsAccentPrimary, strokeWidth = 2.dp)
                SupportLabel(if (chat.isSavingAttachment) tr(language, "Сохраняем файл…", "Saving file…") else tr(language, "Подготавливаем файл…", "Preparing file…"), 13, color = SettingsTextSecondary)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(chat.pendingAttachments, key = { it.path }) { attachment ->
            Row(Modifier.width(220.dp).padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(SettingsBgSoft).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = SettingsAccentPrimary, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    SupportLabel(attachment.name, 13, FontWeight.Medium)
                    SupportLabel(android.text.format.Formatter.formatShortFileSize(LocalContext.current, attachment.size), 11, color = SettingsTextSecondary)
                }
                IconButton(onClick = { onRemoveAttachment(attachment.path) }, enabled = !chat.isSending, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, tr(language, "Убрать вложение", "Remove attachment"), tint = SettingsTextSecondary, modifier = Modifier.size(20.dp))
                }
            }
          }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 8.dp)
                .nokiSettingsPanelGlassSurface(RoundedCornerShape(26.dp), backdrop, liveGlassEnabled = liveGlassEnabled,
                    surfaceColor = SettingsBgLighter.copy(alpha = 0.94f), blurAndLensEnabled = false)
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onPickAttachment, enabled = !chat.isSending && !chat.isPreparingAttachment && chat.pendingAttachments.size < 10,
                modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.AttachFile, tr(language, "Прикрепить медиа или файл", "Attach media or a file"), tint = SettingsTextSecondary, modifier = Modifier.size(23.dp))
            }
            OutlinedTextField(
                value = chat.draft, onValueChange = onDraftChanged,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 160.dp),
                placeholder = { SupportLabel(tr(language, "Ваше сообщение", "Your message"), 16, color = SettingsTextMuted) },
                enabled = !chat.isSending, minLines = 1, maxLines = 5,
                textStyle = TextStyle(fontFamily = ManropeFontFamily, fontSize = 16.sp, lineHeight = 23.sp, color = SettingsTextPrimary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent, cursorColor = SettingsAccentPrimary,
                    disabledTextColor = SettingsTextPrimary,
                ),
            )
            IconButton(
                onClick = onSend, enabled = canSend,
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(if (canSend || chat.isSending) SettingsAccentPrimary else SettingsBgSoft),
            ) {
                if (chat.isSending) CircularProgressIndicator(Modifier.size(20.dp), color = SettingsBgBase, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.ArrowUpward, tr(language, "Отправить сообщение", "Send message"),
                    tint = if (canSend) SettingsBgBase else SettingsTextMuted, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun SupportMessageBubble(message: BackendSupportMessage, maxWidth: Dp, chat: SupportChatState, language: AppLanguage,
    onPreview: (BackendSupportAttachment) -> Unit, onOpen: (BackendSupportAttachment) -> Unit, onSave: (BackendSupportAttachment) -> Unit) {
    val context = LocalContext.current
    var copyMenu by remember(message.id) { mutableStateOf(false) }
    val fromSupport = message.isFromSupport
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = if (fromSupport) 14.dp else 4.dp, bottomStart = if (fromSupport) 4.dp else 14.dp)
    val time = remember(message.createdAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt * 1_000))
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (fromSupport) Alignment.Start else Alignment.End) {
        Column(
            Modifier.widthIn(max = maxWidth).clip(shape)
                .background(if (fromSupport) SettingsBgSoft else Color(0xFF19433F))
                .border(0.6.dp, if (fromSupport) SettingsStroke else SettingsAccentPrimary.copy(alpha = 0.16f), shape)
                .combinedClickable(onClick = { copyMenu = false }, onLongClick = { if (message.content.isNotBlank()) copyMenu = true },
                    onLongClickLabel = tr(language, "Копировать сообщение", "Copy message"))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (fromSupport) SupportLabel("Noki", 11, FontWeight.SemiBold, SettingsAccentPrimary)
            message.attachments.forEach { attachment ->
                SupportAttachmentTile(attachment, chat.attachmentFiles[attachment.id], attachment.id in chat.attachmentLoading,
                    chat.attachmentErrors[attachment.id], language, onPreview, onOpen, onSave)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.content.isNotBlank()) SupportLabel(message.content, 14, modifier = Modifier.weight(1f, fill = false))
                SupportLabel(time, 10, color = SettingsTextSecondary, modifier = Modifier.padding(bottom = 1.dp))
                if (!fromSupport) Icon(if (message.status == "read") Icons.Rounded.DoneAll else Icons.Rounded.Done,
                    tr(language, if (message.status == "read") "Прочитано поддержкой" else "Отправлено", if (message.status == "read") "Read by support" else "Sent"),
                    tint = if (message.status == "read") SettingsAccentPrimary else SettingsTextSecondary, modifier = Modifier.size(15.dp))
            }
            DropdownMenu(expanded = copyMenu, onDismissRequest = { copyMenu = false }) {
                DropdownMenuItem(text = { SupportLabel(tr(language, "Копировать", "Copy"), 14) }, onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Noki", message.content))
                    copyMenu = false
                })
            }
        }
    }
}

@Composable
private fun SupportAttachmentTile(attachment: BackendSupportAttachment, path: String?, loading: Boolean, error: String?, language: AppLanguage,
    onPreview: (BackendSupportAttachment) -> Unit, onOpen: (BackendSupportAttachment) -> Unit, onSave: (BackendSupportAttachment) -> Unit) {
    val context = LocalContext.current
    var imageFailed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(attachment.id) {
        if (attachment.hasImagePreview && path == null && !loading && error == null) onPreview(attachment)
    }
    Column(Modifier.width(220.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (attachment.hasImagePreview && path != null && !imageFailed) {
            val density = LocalDensity.current
            val widthPx = with(density) { 220.dp.roundToPx() }
            val heightPx = with(density) { 150.dp.roundToPx() }
            val request = remember(context, path, widthPx, heightPx) {
                ImageRequest.Builder(context).data(SupportChatFiles.uri(context, path)).size(widthPx, heightPx)
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
            }
            AsyncImage(model = request, contentDescription = attachment.name, onError = { imageFailed = true },
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !loading) { onOpen(attachment) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (attachment.type == "video") Icons.Rounded.Movie else Icons.AutoMirrored.Rounded.InsertDriveFile,
                null, tint = SettingsAccentPrimary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                SupportLabel(attachment.name, 13, FontWeight.Medium)
                SupportLabel(android.text.format.Formatter.formatShortFileSize(context, attachment.size), 11, color = SettingsTextSecondary)
            }
        }
        if (error != null) SupportLabel(error, 12, color = SettingsError)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onOpen(attachment) }, enabled = !loading && canOpenSupportAttachment(attachment.mime)) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = SettingsAccentPrimary, strokeWidth = 2.dp)
                else SupportLabel(if (!canOpenSupportAttachment(attachment.mime)) tr(language, "Только сохранение", "Save only") else if (error != null) tr(language, "Повторить", "Retry") else tr(language, "Открыть", "Open"), 12, color = SettingsAccentPrimary)
            }
            IconButton(onClick = { onSave(attachment) }, enabled = !loading, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Download, tr(language, "Сохранить ${attachment.name}", "Save ${attachment.name}"), tint = SettingsTextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SupportChatError(error: String, language: AppLanguage, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(SettingsBgSoft).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SupportLabel(error, 13, color = SettingsTextPrimary, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { SupportLabel(tr(language, "Повторить", "Retry"), 13, FontWeight.SemiBold, SettingsAccentPrimary) }
    }
}

@Composable
private fun SupportLabel(
    text: String,
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    color: Color = SettingsTextPrimary,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(text, modifier, color = color, fontFamily = ManropeFontFamily, fontWeight = weight,
        fontSize = size.sp, lineHeight = (size * 1.4f).sp, textAlign = textAlign)
}

private fun launchSupportIntent(context: Context, intent: Intent, language: AppLanguage): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: android.content.ActivityNotFoundException) {
    Toast.makeText(context, tr(language, "Не найдено приложение для открытия ссылки", "No app can open this link"), Toast.LENGTH_LONG).show()
    false
}

private fun openTelegramSupport(context: Context, language: AppLanguage) {
    val telegramUri = "tg://resolve?domain=NokiSupport".toUri()
    for (packageName in listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")) {
        val intent = Intent(Intent.ACTION_VIEW, telegramUri).setPackage(packageName)
        if (intent.resolveActivity(context.packageManager) != null && launchSupportIntent(context, intent, language)) return
    }
    val intent = Intent(Intent.ACTION_VIEW, telegramUri)
    if (intent.resolveActivity(context.packageManager) != null && launchSupportIntent(context, intent, language)) return
    launchSupportIntent(context, Intent(Intent.ACTION_VIEW, "https://t.me/NokiSupport".toUri()), language)
}
