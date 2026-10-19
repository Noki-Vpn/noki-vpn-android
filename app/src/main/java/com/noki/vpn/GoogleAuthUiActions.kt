package com.noki.vpn

import com.noki.vpn.data.AppLanguage

internal fun AppUiRuntime.beginGoogleLogin(): Boolean {
    if (
        uiState.isAuthenticated ||
        sessionOperationJob != null ||
        uiState.loginForm.isLoading ||
        TelegramLoginStateReducer.isActive(uiState.telegramLoginState)
    ) {
        return false
    }
    uiState = uiState.copy(
        authStep = AuthStep.WELCOME,
        screenStack = listOf(AppDestination.LOGIN),
        loginForm = uiState.loginForm.copy(isLoading = true, error = null),
        inlineMessage = null,
    )
    recordAppLog("auth", message = "google_login_begin")
    return true
}

internal fun AppUiRuntime.handleGoogleLoginResult(result: GoogleLoginResult) {
    when (result) {
        GoogleLoginResult.Cancelled -> {
            uiState = uiState.copy(loginForm = uiState.loginForm.copy(isLoading = false))
            recordAppLog("auth", message = "google_login_cancelled")
        }

        is GoogleLoginResult.Failure -> {
            uiState = uiState.copy(
                loginForm = uiState.loginForm.copy(isLoading = false),
                inlineMessage = readableGoogleLaunchError(
                    language = uiState.personalizationSettings.language,
                    code = result.code,
                ),
            )
            recordAppLog("auth", level = "error", message = "google_login_launch_fail", details = result.code)
        }

        is GoogleLoginResult.Success -> exchangeGoogleLogin(result.idToken)
    }
}

private fun AppUiRuntime.exchangeGoogleLogin(idToken: String) {
    if (uiState.isAuthenticated || sessionOperationJob != null) return
    val language = uiState.personalizationSettings.language
    val preparedState = uiState.copy(
        isAuthenticated = true,
        authStep = AuthStep.WELCOME,
        screenStack = listOf(AppDestination.HOME),
        loginForm = uiState.loginForm.copy(isLoading = false, error = null),
        inlineMessage = null,
    )
    recordAppLog("auth", message = "google_login_exchange_start")
    launchSessionOperation(
        onStarted = {},
        operation = {
            googleAuthCoordinator().login(
                idToken = idToken,
                deviceId = backendDeviceId.ifBlank { null },
                preparedState = preparedState,
            )
        },
        onSuccess = {
            recordAppLog("auth", message = "google_login_success")
            startAppNotificationPolling()
            syncFcmTokenIfAvailable()
            maybeUploadLogsAutomatically()
        },
        onFailure = { error ->
            recordAppLog(
                category = "auth",
                level = "error",
                message = "google_login_fail",
                errorType = AppErrorMapper.readableErrorType(error),
            )
            uiState = uiState.copy(
                loginForm = uiState.loginForm.copy(isLoading = false),
                inlineMessage = AppErrorMapper.readableGoogleAuthError(language, error),
            )
        },
    )
}

private fun readableGoogleLaunchError(language: AppLanguage, code: String): String = when (code) {
    "not_configured" -> tr(
        language,
        "Вход через Google пока не настроен",
        "Google sign-in is not configured yet",
    )
    "credential_unavailable" -> tr(
        language,
        "На устройстве нет доступного аккаунта Google",
        "No Google account is available on this device",
    )
    else -> tr(
        language,
        "Не удалось войти через Google. Попробуйте ещё раз",
        "Google sign-in failed. Try again",
    )
}

private fun tr(language: AppLanguage, russian: String, english: String): String =
    if (language == AppLanguage.RU) russian else english
