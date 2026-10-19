package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noki.vpn.AppUiState
import com.noki.vpn.data.AppLanguage

@Composable
internal fun AdditionalDeviceInfo(
    remainingDevices: Int,
    language: AppLanguage,
    scale: Float,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(devicesDp(5f, scale), Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        DevicesText(
            text = additionalDeviceTitle(remainingDevices, language),
            fontSize = 14f,
            lineHeight = 16.8f,
            color = DevicesTextPrimary,
            fontWeight = FontWeight.Medium,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
        )
        DevicesText(
            text = tr(
                language,
                "Когда места в тарифе закончатся, новые устройства попадут в доп. устройства",
                "When the plan is full, new devices are added to additional devices",
            ),
            fontSize = 11f,
            lineHeight = 12.5f,
            color = DevicesTextSecondary,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
        )
    }
}

@Composable
internal fun DeviceIconTile(
    platformHint: String,
    scale: Float,
    modifier: Modifier = Modifier,
    size: Float = 48f,
) {
    val isDesktop = platformHint.contains("Windows", ignoreCase = true) ||
        platformHint.contains("macOS", ignoreCase = true) ||
        platformHint.contains("PC", ignoreCase = true)
    Box(
        modifier = modifier
            .size(devicesDp(size, scale))
            .clip(RoundedCornerShape(devicesDp(14f, scale)))
            .background(DevicesAccentSecondary)
            .border(
                BorderStroke(1.dp, DevicesStroke.copy(alpha = 0.5f)),
                RoundedCornerShape(devicesDp(14f, scale)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isDesktop) {
            MonitorGlyph(scale = scale)
        } else {
            PhoneGlyph(scale = scale)
        }
    }
}

@Composable
internal fun PhoneGlyph(scale: Float) {
    Box(
        modifier = Modifier
            .width(devicesDp(18f, scale))
            .height(devicesDp(30f, scale))
            .clip(RoundedCornerShape(devicesDp(4f, scale)))
            .background(DevicesBgLighter),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = devicesDp(4f, scale))
                .width(devicesDp(6f, scale))
                .height(devicesDp(2f, scale))
                .clip(RoundedCornerShape(devicesDp(1f, scale)))
                .background(DevicesAccentSecondary),
        )
    }
}

@Composable
internal fun MonitorGlyph(scale: Float) {
    Box(
        modifier = Modifier
            .width(devicesDp(27f, scale))
            .height(devicesDp(25f, scale)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(devicesDp(26f, scale))
                .height(devicesDp(16f, scale))
                .clip(RoundedCornerShape(devicesDp(3f, scale)))
                .background(DevicesBgLighter),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(devicesDp(14f, scale))
                .height(devicesDp(4f, scale))
                .clip(RoundedCornerShape(devicesDp(2f, scale)))
                .background(DevicesBgLighter),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = devicesDp(7f, scale))
                .width(devicesDp(5f, scale))
                .height(devicesDp(5f, scale))
                .background(DevicesBgLighter),
        )
    }
}

@Composable
internal fun DeviceInfo(
    title: String,
    subtitle: String?,
    titleFontSize: Float,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(devicesDp(3f, scale)),
        horizontalAlignment = Alignment.Start,
    ) {
        DevicesText(
            text = title,
            fontSize = titleFontSize,
            lineHeight = 21.6f,
            color = DevicesTextPrimary,
            fontWeight = FontWeight.SemiBold,
            scale = scale,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!subtitle.isNullOrBlank()) {
            DevicesText(
                text = subtitle.replace("•", "·"),
                fontSize = 11f,
                lineHeight = 13.2f,
                color = DevicesTextSecondary,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun DevicesText(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    color: Color,
    scale: Float,
    modifier: Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        color = color,
        fontFamily = ManropeFontFamily,
        fontWeight = fontWeight,
        fontSize = devicesSp(fontSize, scale),
        lineHeight = devicesSp(lineHeight, scale),
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier,
    )
}

internal fun primaryDeviceCount(state: AppUiState): Int =
    state.devices.count { it.isActive && it.trafficPool != "shared" } +
        state.incyDevices.devices.count { it.trafficPool != "shared" }

internal fun deviceLimit(state: AppUiState): Int {
    return state.userProfile.primaryDeviceLimit ?: state.plans.firstOrNull { plan ->
        plan.code.equals(state.userProfile.selectedPlanCodeRaw, ignoreCase = true) ||
            plan.tier.equals(state.userProfile.selectedPlanCodeRaw, ignoreCase = true) ||
            state.userProfile.selectedPlanCodeRaw.startsWith(plan.tier, ignoreCase = true)
    }?.devices
        ?: 1
}

internal fun additionalDeviceTitle(remainingDevices: Int, language: AppLanguage): String {
    if (remainingDevices <= 0) {
        return tr(language, "Лимит устройств исчерпан", "Device limit reached")
    }
    return if (language == AppLanguage.RU) {
        val word = when {
            remainingDevices % 10 == 1 && remainingDevices % 100 != 11 -> "устройство"
            remainingDevices % 10 in 2..4 && remainingDevices % 100 !in 12..14 -> "устройства"
            else -> "устройств"
        }
        "Можно подключить ещё $remainingDevices $word"
    } else {
        val word = if (remainingDevices == 1) "device" else "devices"
        "You can connect $remainingDevices more $word"
    }
}

internal fun devicePlatformLabel(subtitle: String?): String? {
    if (subtitle.isNullOrBlank()) {
        return null
    }
    return subtitle
        // Keep compatibility with subtitles persisted by older clients with broken UTF-8 decoding.
        .replace("\u0432\u0402\u045E", "•")
        .replace("\u0412\u00B7", "·")
        .split("•", "·")
        .firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

internal fun devicesDp(value: Float, scale: Float): Dp = (value * scale).dp

internal fun devicesSp(value: Float, scale: Float) = (value * scale).sp
