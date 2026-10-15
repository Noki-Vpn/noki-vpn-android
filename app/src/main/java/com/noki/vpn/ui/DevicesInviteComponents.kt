package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noki.vpn.data.AppLanguage
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.prismalBlur
import com.styropyr0.prismal.effects.colorControls
import com.styropyr0.prismal.effects.prismalLens
import com.styropyr0.prismal.effects.vibrancy
import com.styropyr0.prismal.specular.PrismalSpecular
import kotlinx.coroutines.delay

@Composable
internal fun DeviceActionCardSurface(
    enabled: Boolean,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    contentAlignment: Alignment,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(devicesDp(18f, scale))
    val buttonColor = DevicesBgLighter
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(devicesDp(50f, scale))
            .then(
                if (liveGlassEnabled && backdrop != null) {
                    Modifier.drawPrismalGlass(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = { vibrancy() },
                        specular = {
                            PrismalSpecular.Default.copy(
                                width = 0.5.dp,
                                blurRadius = 0.25.dp,
                                alpha = 0.18f,
                            )
                        },
                        onDrawSurface = {
                            drawRect(buttonColor.copy(alpha = 0.75f))
                        },
                    )
                } else {
                    Modifier
                        .clip(shape)
                        .background(
                            glassSurfaceColor(buttonColor.copy(alpha = 0.75f), liveGlassEnabled),
                            shape,
                        )
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = devicesDp(20f, scale)),
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
internal fun DeviceInviteCard(
    isLoading: Boolean,
    language: AppLanguage,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onClick: () -> Unit,
) {
    DeviceActionCardSurface(
        enabled = !isLoading,
        scale = scale,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        contentAlignment = Alignment.CenterStart,
        onClick = onClick,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(devicesDp(5f, scale)),
            horizontalAlignment = Alignment.Start,
        ) {
            DevicesText(
                text = if (isLoading) {
                    tr(language, "Создаем код...", "Creating code...")
                } else {
                    tr(language, "Создать код для подключения устройства", "Create code to connect device")
                },
                fontSize = 13f,
                lineHeight = 19.2f,
                color = DevicesTextPrimary,
                scale = scale,
                modifier = Modifier,
            )
        }
    }
}

@Composable
internal fun DeviceInviteDialog(
    inviteCode: String,
    expiresAt: String?,
    language: AppLanguage,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val copyInteraction = remember { MutableInteractionSource() }
    var copyNoticeVisible by remember(inviteCode) { mutableStateOf(false) }
    var copyNoticeVersion by remember(inviteCode) { mutableIntStateOf(0) }

    LaunchedEffect(copyNoticeVersion, inviteCode) {
        if (copyNoticeVersion > 0) {
            copyNoticeVisible = true
            delay(1300)
            copyNoticeVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(devicesDp(24f, scale))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
        )
        Column(
            modifier = Modifier
                .offset(y = devicesDp(14f, scale))
                .width(devicesDp(350f, scale))
                .clip(shape)
                .then(
                    if (liveGlassEnabled && backdrop != null) {
                        Modifier.drawPrismalGlass(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                colorControls(
                                    saturation = 1.5f,
                                    contrast = 1f,
                                    brightness = -0.1f,
                                )
                                prismalBlur(devicesDp(40f, scale).toPx())
                                prismalLens(
                                    devicesDp(8f, scale).toPx(),
                                    devicesDp(10f, scale).toPx(),
                                )
                            },
                            specular = {
                                PrismalSpecular.Default.copy(alpha = 0.22f)
                            },
                            onDrawSurface = {
                                drawRect(DevicesBgLighter.copy(alpha = 0.42f))
                            },
                        )
                    } else {
                        Modifier.background(
                            glassSurfaceColor(DevicesBgLighter.copy(alpha = 0.42f), liveGlassEnabled),
                            shape,
                        )
                    },
                )
                .then(
                    if (liveGlassEnabled) {
                        Modifier.background(DevicesBgLighter.copy(alpha = 0.42f), shape)
                    } else {
                        Modifier
                    },
                )
                .border(BorderStroke(1.dp, DevicesStroke.copy(alpha = 0.9f)), shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(devicesDp(20f, scale)),
            verticalArrangement = Arrangement.spacedBy(devicesDp(14f, scale)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DevicesText(
                text = tr(language, "Код приглашения", "Invite code"),
                fontSize = 16f,
                lineHeight = 20f,
                color = DevicesTextPrimary,
                fontWeight = FontWeight.SemiBold,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            DevicesText(
                text = tr(
                    language,
                    "Введите этот код на другом устройстве или отсканируйте QR-код",
                    "Enter this code on another device or scan the QR code",
                ),
                fontSize = 11f,
                lineHeight = 15f,
                color = DevicesTextSecondary,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(devicesDp(48f, scale))
                    .clip(RoundedCornerShape(devicesDp(18f, scale)))
                    .background(DevicesBgSoft)
                    .border(
                        BorderStroke(1.dp, DevicesAccentPrimary.copy(alpha = 0.45f)),
                        RoundedCornerShape(devicesDp(18f, scale)),
                    )
                    .clickable(
                        interactionSource = copyInteraction,
                        indication = null,
                    ) {
                        clipboardManager.setText(AnnotatedString(inviteCode))
                        copyNoticeVersion += 1
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (copyNoticeVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = -devicesDp(25f, scale))
                            .clip(RoundedCornerShape(devicesDp(12f, scale)))
                            .background(DevicesBgSoft.copy(alpha = 0.96f))
                            .border(
                                BorderStroke(1.dp, DevicesAccentPrimary.copy(alpha = 0.45f)),
                                RoundedCornerShape(devicesDp(12f, scale)),
                            )
                            .padding(
                                horizontal = devicesDp(12f, scale),
                                vertical = devicesDp(5f, scale),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        DevicesText(
                            text = tr(language, "Скопировано в буфер", "Copied to clipboard"),
                            fontSize = 11f,
                            lineHeight = 13f,
                            color = DevicesTextPrimary,
                            scale = scale,
                            modifier = Modifier,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                DevicesText(
                    text = inviteCode,
                    fontSize = 22f,
                    lineHeight = 24f,
                    color = DevicesTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    scale = scale,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            InviteQrBlock(inviteCode = inviteCode, scale = scale)
            DevicesText(
                text = inviteExpiresText(expiresAt, language),
                fontSize = 11f,
                lineHeight = 14f,
                color = DevicesTextSecondary,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            DevicesDialogButton(
                text = tr(language, "Закрыть", "Close"),
                scale = scale,
                isPrimary = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
internal fun InviteQrBlock(
    inviteCode: String,
    scale: Float,
) {
    val qrImage = remember(inviteCode) { inviteCodeQrBitmap(inviteCode, 512) }
    val qrShape = RoundedCornerShape(devicesDp(24f, scale))
    Box(
        modifier = Modifier
            .width(devicesDp(210f, scale))
            .height(devicesDp(210f, scale))
            .clip(qrShape)
            .background(Color.White, qrShape)
            .padding(devicesDp(12f, scale)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = qrImage,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

internal fun inviteExpiresText(
    expiresAt: String?,
    language: AppLanguage,
): String {
    return if (expiresAt.isNullOrBlank()) {
        tr(language, "Код действует ограниченное время", "The code is valid for a limited time")
    } else {
        tr(language, "Код действует 30 минут", "The code is valid for 30 minutes")
    }
}
