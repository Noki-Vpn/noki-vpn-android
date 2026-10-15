package com.noki.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import com.noki.vpn.AppDestination
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.R
import com.noki.vpn.data.InstallOnboardingStep
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer

@Composable
internal fun InstallOnboardingScreen(state: AppUiState, viewModel: MainViewModel) {
    val language = state.personalizationSettings.language
    val step = state.installOnboardingStep
    if (step != InstallOnboardingStep.CONSENT && step != InstallOnboardingStep.ROUTING) return
    val consent = step == InstallOnboardingStep.CONSENT
    val liveGlassEnabled = state.personalizationSettings.glassMode.liveGlassEnabled
    val backdrop = rememberPrismalGlassLayer()
    val uriHandler = LocalUriHandler.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(NokiBgBase)) {
        val metrics = nokiAdaptiveMetrics(maxWidth)
        AuthBackground(
            backgroundRes = R.drawable.login_background,
            modifier = Modifier.then(if (liveGlassEnabled) {
                Modifier.prismalGlassLayer(backdrop)
            } else Modifier),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = metrics.contentStart, vertical = metrics.dp(28f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.login_logo_mark_vector),
                contentDescription = null,
                modifier = Modifier.size(metrics.dp(52f)),
            )
            Spacer(Modifier.height(metrics.dp(26f)))
            if (step == InstallOnboardingStep.ROUTING) {
                Text(
                    text = tr(language, "Настройка Noki", "Set up Noki"),
                    color = NokiAccentPrimary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(metrics.dp(12f)))
            }
            OnboardingText(
                text = if (consent) tr(language, "Добро пожаловать в Noki", "Welcome to Noki")
                    else tr(language, "Выберите приложения", "Choose your apps"),
                metrics = metrics,
                title = true,
            )
            Spacer(Modifier.height(metrics.dp(12f)))
            OnboardingText(
                text = if (consent) tr(language,
                        "Для начала работы примите условия использования и политику конфиденциальности.",
                        "To get started, accept the terms of use and privacy policy.")
                    else tr(language,
                        "Решите, какие приложения будут использовать VPN. Настройки можно изменить позже.",
                        "Decide which apps will use VPN. You can change this later in settings."),
                metrics = metrics,
            )
            Spacer(Modifier.height(metrics.dp(30f)))
            if (consent) {
                Column(
                    modifier = Modifier.fillMaxWidth().nokiSettingsPanelGlassSurface(
                        shape = RoundedCornerShape(metrics.dp(24f)),
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        scale = metrics.contentScale,
                    ).padding(metrics.dp(16f)),
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(4f)),
                ) {
                    listOf(
                        tr(language, "Условия использования", "Terms of use") to NokiTermsUrl,
                        tr(language, "Политика конфиденциальности", "Privacy policy") to NokiPrivacyUrl,
                    ).forEach { (label, url) ->
                        Box(
                            modifier = Modifier.fillMaxWidth().height(metrics.dp(48f))
                                .clickable(role = Role.Button) { uriHandler.openUri(url) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = NokiAccentPrimary,
                                fontFamily = ManropeFontFamily,
                                fontSize = metrics.sp(14f),
                                textAlign = TextAlign.Center,
                                textDecoration = TextDecoration.Underline,
                            )
                        }
                    }
                }
            } else {
                AdvancedFilterCard(
                    mode = state.filterMode,
                    language = language,
                    backdrop = backdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    onModeChanged = viewModel::updateFilterMode,
                    onConfigure = { viewModel.openScreen(AppDestination.APP_FILTER) },
                )
            }
            Spacer(Modifier.height(metrics.dp(30f)))
            AuthInlineError(error = state.inlineMessage)
            AuthPrimaryButton(
                text = if (consent) tr(language, "Принять и продолжить", "Accept and continue")
                    else tr(language, "Продолжить", "Continue"),
                loading = false,
                modifier = Modifier.fillMaxWidth().height(metrics.dp(60f)),
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                onClick = viewModel::advanceInstallOnboarding,
            )
        }
    }
}

@Composable
private fun OnboardingText(text: String, metrics: NokiAdaptiveMetrics, title: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = if (title) NokiTextPrimary else NokiTextSecondary,
        fontFamily = ManropeFontFamily,
        fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = metrics.sp(if (title) 26f else 14f),
        lineHeight = metrics.sp(if (title) 34f else 21f),
        textAlign = TextAlign.Center,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
    )
}
