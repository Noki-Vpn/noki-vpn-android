package com.noki.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noki.vpn.data.AppLanguage
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.prismalBlur
import com.styropyr0.prismal.effects.colorControls
import com.styropyr0.prismal.effects.prismalLens
import com.styropyr0.prismal.specular.PrismalSpecular
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun Modifier.settingsDialogGlassSurface(
    shape: Shape,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    showBorder: Boolean = true,
): Modifier = clip(shape)
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
                    prismalBlur(settingsDp(40f, scale).toPx())
                    prismalLens(
                        settingsDp(8f, scale).toPx(),
                        settingsDp(10f, scale).toPx(),
                    )
                },
                specular = {
                    PrismalSpecular.Default.copy(alpha = 0.22f)
                },
                onDrawSurface = {
                    drawRect(SettingsBgLighter.copy(alpha = 0.42f))
                },
            )
        } else {
            Modifier.background(
                glassSurfaceColor(SettingsBgLighter.copy(alpha = 0.42f), liveGlassEnabled),
                shape,
            )
        },
    )
    .then(
        if (liveGlassEnabled) {
            Modifier.background(SettingsBgLighter.copy(alpha = 0.42f), shape)
        } else {
            Modifier
        },
    )
    .then(
        if (showBorder) {
            Modifier.border(BorderStroke(1.dp, SettingsStroke.copy(alpha = 0.9f)), shape)
        } else {
            Modifier
        },
    )

@Composable
internal fun SettingsLogoutConfirmDialog(
    language: AppLanguage,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SettingsConfirmDialog(
        title = tr(language, "Выйти из аккаунта?", "Sign out?"),
        message = tr(
            language,
            "Текущее устройство будет отвязано от аккаунта. Продолжить?",
            "The current device will be unlinked from the account. Continue?",
        ),
        dismissText = tr(language, "Отмена", "Cancel"),
        confirmText = tr(language, "Выйти", "Logout"),
        confirmIsDanger = true,
        scale = scale,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun SettingsConfirmDialog(
    title: String,
    message: String,
    dismissText: String,
    confirmText: String,
    confirmIsDanger: Boolean,
    confirmHoldDurationMillis: Int = 0,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(settingsDp(20f, scale))
        Column(
            modifier = Modifier
                .width(settingsDp(330f, scale))
                .settingsDialogGlassSurface(
                    shape = shape,
                    scale = scale,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(
                    horizontal = settingsDp(20f, scale),
                    vertical = settingsDp(20f, scale),
                ),
            verticalArrangement = Arrangement.spacedBy(settingsDp(18f, scale)),
            horizontalAlignment = Alignment.Start,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(settingsDp(10f, scale)),
                horizontalAlignment = Alignment.Start,
            ) {
                SettingsText(
                    text = title,
                    fontSize = 18f,
                    lineHeight = 21.6f,
                    letterSpacing = 0.18f,
                    color = SettingsTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    scale = scale,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
                SettingsText(
                    text = message,
                    fontSize = 12f,
                    lineHeight = 16f,
                    letterSpacing = 0.12f,
                    color = SettingsTextSecondary,
                    scale = scale,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(settingsDp(10f, scale)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsDialogButton(
                    text = dismissText,
                    scale = scale,
                    isDanger = false,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                SettingsDialogButton(
                    text = confirmText,
                    scale = scale,
                    isDanger = confirmIsDanger,
                    holdDurationMillis = confirmHoldDurationMillis,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
internal fun SettingsCompactInputDialog(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    dismissText: String,
    confirmText: String,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 64,
    error: String? = null,
    isLoading: Boolean = false,
    extraContent: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(settingsDp(20f, scale))
        Column(
            modifier = Modifier
                .width(settingsDp(330f, scale))
                .settingsDialogGlassSurface(
                    shape = shape,
                    scale = scale,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    showBorder = false,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(settingsDp(20f, scale)),
            verticalArrangement = Arrangement.spacedBy(settingsDp(18f, scale)),
        ) {
            SettingsCompactInputField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                scale = scale,
                enabled = !isLoading,
                maxLength = maxLength,
            )

            error?.takeIf { it.isNotBlank() }?.let { message ->
                SettingsText(
                    text = message,
                    fontSize = 12f,
                    lineHeight = 15f,
                    letterSpacing = 0.12f,
                    color = SettingsError,
                    scale = scale,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
            }

            extraContent?.invoke()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(settingsDp(10f, scale)),
            ) {
                SettingsDialogButton(
                    text = dismissText,
                    scale = scale,
                    isDanger = false,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f),
                    textColor = SettingsTextSecondary,
                    onClick = onDismiss,
                )
                SettingsDialogButton(
                    text = confirmText,
                    scale = scale,
                    isDanger = false,
                    enabled = !isLoading,
                    showProgress = isLoading,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
internal fun SettingsCompactInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    scale: Float,
    modifier: Modifier = Modifier,
    backgroundColor: Color = SettingsBgSoft,
    enabled: Boolean = true,
    maxLength: Int = 64,
) {
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = SettingsTextPrimary,
            fontFamily = ManropeFontFamily,
            fontSize = (14f * scale).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        cursorBrush = SolidColor(NokiAccentStrong),
        modifier = modifier
            .fillMaxWidth()
            .height(settingsDp(48f, scale))
            .clip(RoundedCornerShape(settingsDp(12f, scale)))
            .background(backgroundColor)
            .padding(
                horizontal = settingsDp(14f, scale),
                vertical = settingsDp(14f, scale),
            ),
        decorationBox = { inner ->
            Box {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        color = SettingsTextSecondary,
                        fontFamily = ManropeFontFamily,
                        fontSize = (14f * scale).sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
internal fun SettingsDialogButton(
    text: String,
    scale: Float,
    isDanger: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
    enabled: Boolean = true,
    showProgress: Boolean = false,
    holdDurationMillis: Int = 0,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(settingsDp(12f, scale))
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val holdProgress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed || holding) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "SettingsDialogButtonScale",
    )
    val background = if (isDanger) SettingsError.copy(alpha = 0.16f) else SettingsBgSoft
    val border = if (isDanger) SettingsError.copy(alpha = 0.45f) else SettingsStroke.copy(alpha = 0.8f)
    val resolvedTextColor = textColor ?: if (isDanger) SettingsError else SettingsTextPrimary
    val isHoldAction = holdDurationMillis > 0
    val interactionModifier = if (holdDurationMillis > 0) {
        Modifier.pointerInput(enabled, holdDurationMillis) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    holding = true
                    holdProgress.snapTo(0f)
                    var completed = false
                    coroutineScope {
                        val completion = launch {
                            holdProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = holdDurationMillis,
                                    easing = LinearEasing,
                                ),
                            )
                            completed = true
                            currentOnClick()
                        }
                        tryAwaitRelease()
                        completion.cancel()
                    }
                    holding = false
                    if (completed) {
                        holdProgress.snapTo(0f)
                    } else {
                        holdProgress.animateTo(0f, tween(durationMillis = 180))
                    }
                },
            )
        }
    } else {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    }
    Box(
        modifier = modifier
            .height(settingsDp(46f, scale))
            .graphicsLayer {
                scaleX = buttonScale
                scaleY = buttonScale
                alpha = if (enabled || showProgress) 1f else 0.5f
            }
            .clip(shape)
            .background(
                if (!isHoldAction && (pressed || holding)) background.copy(alpha = 0.9f) else background,
                shape,
            )
            .border(BorderStroke(1.dp, border), shape)
            .then(interactionModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (holdDurationMillis > 0 && !showProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = holdProgress.value
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(SettingsError.copy(alpha = 0.34f)),
            )
        }
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(settingsDp(18f, scale)),
                strokeWidth = settingsDp(2f, scale),
                color = resolvedTextColor,
            )
        } else {
            SettingsText(
                text = text,
                fontSize = 14f,
                lineHeight = 16.8f,
                letterSpacing = 0.14f,
                color = resolvedTextColor,
                fontWeight = FontWeight.Medium,
                scale = scale,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}
