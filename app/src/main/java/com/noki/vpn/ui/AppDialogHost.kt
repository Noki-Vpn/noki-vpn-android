package com.noki.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noki.vpn.AppDestination
import com.noki.vpn.AppDialog
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.showsInstallAppearanceDialog

@Composable
internal fun AppDialogHost(
    state: AppUiState,
    viewModel: MainViewModel,
    onOpenVpnSettings: () -> Unit,
) {
    if (showsInstallAppearanceDialog(state)) {
        InstallAppearanceDialog(state, viewModel)
        return
    }
    when (state.dialog) {
        AppDialog.FreeTrafficLimitReached -> LimitNoticeDialog(
            title = tr(state.personalizationSettings.language, "Трафик закончился", "Traffic limit reached"),
            message = tr(
                state.personalizationSettings.language,
                "Бесплатные 0.5 Gb израсходованы. Чтобы продолжить пользоваться VPN, подключите подписку.",
                "Your free 0.5 GB is used up. Choose a subscription to keep using VPN.",
            ),
            primaryText = tr(state.personalizationSettings.language, "Выбрать тариф", "Choose plan"),
            secondaryText = tr(state.personalizationSettings.language, "Позже", "Later"),
            onDismiss = viewModel::dismissDialog,
            onPrimary = viewModel::confirmDialog,
        )
        AppDialog.DeviceLimitReached -> LimitNoticeDialog(
            title = tr(state.personalizationSettings.language, "Устройство вне лимита", "Device outside limit"),
            message = tr(
                state.personalizationSettings.language,
                "Это устройство не входит в лимит текущего тарифа. Проверьте подключенные устройства или освободите место.",
                "This device is outside the current plan limit. Check connected devices or free a slot.",
            ),
            primaryText = tr(state.personalizationSettings.language, "Устройства", "Devices"),
            secondaryText = tr(state.personalizationSettings.language, "Позже", "Later"),
            onDismiss = viewModel::dismissDialog,
            onPrimary = viewModel::confirmDialog,
        )
        AppDialog.EmptySelectedApps -> LimitNoticeDialog(
            title = tr(state.personalizationSettings.language, "Не выбраны приложения", "No apps selected"),
            message = tr(
                state.personalizationSettings.language,
                "Для режима «Только отмеченные» выберите приложения, которые должны идти через VPN.",
                "Choose apps that should use the VPN in Selected only mode.",
            ),
            primaryText = tr(state.personalizationSettings.language, "Выбор приложений", "Choose apps"),
            secondaryText = tr(state.personalizationSettings.language, "Закрыть", "Close"),
            onDismiss = viewModel::dismissDialog,
            onPrimary = viewModel::confirmDialog,
        )
        AppDialog.VpnConflict -> LimitNoticeDialog(
            title = tr(state.personalizationSettings.language, "Другой VPN уже работает", "Another VPN is active"),
            message = tr(
                state.personalizationSettings.language,
                "Отключите другой VPN или постоянное VPN-подключение, чтобы запустить Noki.",
                "Disable the other VPN or always-on VPN connection before starting Noki.",
            ),
            primaryText = tr(state.personalizationSettings.language, "Открыть настройки VPN", "Open VPN settings"),
            secondaryText = tr(state.personalizationSettings.language, "Отмена", "Cancel"),
            onDismiss = viewModel::dismissDialog,
            onPrimary = onOpenVpnSettings,
        )
        AppDialog.AccessDenied -> if (
            state.currentDestination != AppDestination.SETTINGS &&
            state.currentDestination != AppDestination.SECURITY
        ) {
            LimitNoticeDialog(
                title = tr(state.personalizationSettings.language, "Нет доступа", "No access"),
                message = tr(
                    state.personalizationSettings.language,
                    "На данном устройстве нет доступа к этому действию.",
                    "This device does not have access to this action.",
                ),
                primaryText = tr(state.personalizationSettings.language, "Понятно", "OK"),
                secondaryText = tr(state.personalizationSettings.language, "Закрыть", "Close"),
                onDismiss = viewModel::dismissDialog,
                onPrimary = viewModel::dismissDialog,
            )
        }
        else -> Unit
    }
}

@Composable
private fun InstallAppearanceDialog(state: AppUiState, viewModel: MainViewModel) {
    val language = state.personalizationSettings.language
    BackHandler(onBack = viewModel::advanceInstallOnboarding)
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.14f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = viewModel::advanceInstallOnboarding),
    ) {
        val metrics = nokiAdaptiveMetrics(maxWidth)
        val shape = RoundedCornerShape(metrics.dp(24f))
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = metrics.dp(110f))
                .width(metrics.contentWidth)
                .clip(shape)
                .background(NokiBgLighter, shape)
                .border(BorderStroke(1.dp, NokiStroke), shape)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(metrics.dp(20f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.dp(16f)),
        ) {
            Text(
                tr(language, "Ваш интерфейс", "Your interface"),
                color = NokiTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = metrics.sp(20f),
                textAlign = TextAlign.Center,
            )
            GlassSegmentedControl(
                labelFontSize = metrics.sp(14f),
                labelLineHeight = metrics.sp(17f),
                capsulePadding = metrics.dp(5f),
                labels = listOf("Full Glass", "Simple"),
                selectedIndex = if (fullGlassEnabled(state.personalizationSettings.glassMode)) 0 else 1,
                onSelectedIndexChanged = { viewModel.setGlassMode(glassModeForFullGlassEnabled(it == 0)) },
                modifier = Modifier.fillMaxWidth().height(metrics.dp(60f)),
                activeBlur = 0.dp,
                lensRefractionHeight = metrics.dp(24f),
                lensRefractionAmount = metrics.dp(24f),
                depthEffectEnabled = true,
                maxPressedScaleX = 1.1f,
                liveGlassEnabled = fullGlassEnabled(state.personalizationSettings.glassMode),
            )
            Text(
                tr(language, "Оцените оформление на главном экране. Позже его можно изменить в настройках.",
                    "Try the look on your home screen. You can change it later in settings."),
                color = NokiTextSecondary,
                fontFamily = ManropeFontFamily,
                fontSize = metrics.sp(12f),
                lineHeight = metrics.sp(18f),
                textAlign = TextAlign.Center,
            )
            AuthInlineError(error = state.inlineMessage)
            AuthPrimaryButton(
                text = tr(language, "Готово", "Done"),
                loading = false,
                modifier = Modifier.fillMaxWidth().height(metrics.dp(48f)),
                onClick = viewModel::advanceInstallOnboarding,
            )
        }
    }
}

@Composable
private fun LimitNoticeDialog(
    title: String,
    message: String,
    primaryText: String,
    secondaryText: String,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .width(330.dp)
                .clip(shape)
                .background(Color(0xFF07111A).copy(alpha = 0.96f), shape)
                .border(BorderStroke(1.dp, Color(0xFF29404E).copy(alpha = 0.9f)), shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = Color(0xFFF4FBFF),
                fontSize = 18.sp,
                lineHeight = 21.6.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = message,
                color = Color(0xFF9FB6C5),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            FreeTrafficDialogButton(
                text = primaryText.ifBlank { secondaryText },
                background = Color(0xFF132635),
                textColor = Color(0xFFF4FBFF),
                modifier = Modifier.fillMaxWidth(),
                onClick = onPrimary,
            )
        }
    }
}

@Composable
private fun FreeTrafficDialogButton(
    text: String,
    background: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(background, shape)
            .border(BorderStroke(1.dp, Color(0xFF29404E).copy(alpha = 0.9f)), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
