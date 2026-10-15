package com.noki.vpn.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noki.vpn.R
import com.noki.vpn.data.AppLanguage
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.prismalBlur
import com.styropyr0.prismal.effects.colorControls
import com.styropyr0.prismal.effects.prismalLens
import com.styropyr0.prismal.effects.vibrancy
import com.styropyr0.prismal.specular.PrismalSpecular

@Composable
internal fun RemoveAllDevicesButton(
    language: AppLanguage,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onClick: () -> Unit,
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
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(start = devicesDp(20f, scale)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(devicesDp(15f, scale)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.devices_remove_all_icon),
                contentDescription = null,
                modifier = Modifier
                    .width(devicesDp(23f, scale))
                    .height(devicesDp(25f, scale)),
            )
            DevicesText(
                text = tr(
                    language,
                    "Удалить все устройства кроме текущего",
                    "Remove all devices except current",
                ),
                fontSize = 13f,
                lineHeight = 19.2f,
                color = DevicesError,
                scale = scale,
                modifier = Modifier,
            )
        }
    }
}

@Composable
internal fun DevicesConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    scale: Float,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
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
                .width(devicesDp(330f, scale))
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
                .padding(
                    horizontal = devicesDp(20f, scale),
                    vertical = devicesDp(20f, scale),
                ),
            verticalArrangement = Arrangement.spacedBy(devicesDp(18f, scale)),
            horizontalAlignment = Alignment.Start,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(devicesDp(10f, scale)),
                horizontalAlignment = Alignment.Start,
            ) {
                DevicesText(
                    text = title,
                    fontSize = 16f,
                    lineHeight = 20f,
                    color = DevicesTextPrimary,
                    scale = scale,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
                DevicesText(
                    text = message,
                    fontSize = 11f,
                    lineHeight = 15f,
                    color = DevicesTextSecondary,
                    scale = scale,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(devicesDp(10f, scale)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DevicesDialogButton(
                    text = dismissText,
                    scale = scale,
                    isPrimary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                DevicesDialogButton(
                    text = confirmText,
                    scale = scale,
                    isPrimary = true,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
internal fun DevicesDialogButton(
    text: String,
    scale: Float,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(devicesDp(18f, scale))
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "DevicesDialogButtonScale",
    )
    val baseBackground = if (isPrimary) DevicesError.copy(alpha = 0.16f) else DevicesBgSoft
    val baseBorder = if (isPrimary) DevicesError.copy(alpha = 0.45f) else DevicesStroke.copy(alpha = 0.8f)
    val textColor = if (isPrimary) DevicesError else DevicesTextPrimary
    Box(
        modifier = modifier
            .height(devicesDp(46f, scale))
            .graphicsLayer {
                scaleX = buttonScale
                scaleY = buttonScale
                alpha = if (enabled) 1f else 0.45f
            }
            .clip(shape)
            .background(if (pressed) baseBackground.copy(alpha = 0.9f) else baseBackground)
            .border(BorderStroke(1.dp, baseBorder), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DevicesText(
            text = text,
            fontSize = 14f,
            lineHeight = 16.8f,
            color = textColor,
            scale = scale,
            fontWeight = FontWeight.Medium,
            modifier = Modifier,
            textAlign = TextAlign.Center,
        )
    }
}
