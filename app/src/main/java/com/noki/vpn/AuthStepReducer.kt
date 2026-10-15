package com.noki.vpn

import com.noki.vpn.data.AppLanguage

object AuthStepReducer {
    fun requireCredentialsSetup(state: AppUiState, previous: AppUiState = state): AppUiState {
        if (!state.isAuthenticated) return state
        if (!state.userProfile.credentialsSetupRequired) {
            return if (previous.userProfile.credentialsSetupRequired) {
                state.copy(
                    screenStack = listOf(AppDestination.HOME),
                    authStep = AuthStep.WELCOME,
                    registrationForm = RegistrationFormState(),
                    inlineMessage = null,
                )
            } else state
        }
        val setupStep = state.authStep == AuthStep.REGISTRATION_PROFILE ||
            state.authStep == AuthStep.REGISTRATION_PASSWORD
        if (setupStep && state.screenStack == listOf(AppDestination.REGISTRATION)) return state
        return state.copy(
            screenStack = listOf(AppDestination.REGISTRATION),
            authStep = if (setupStep) state.authStep else AuthStep.REGISTRATION_PROFILE,
            registrationForm = if (setupStep) state.registrationForm else RegistrationFormState(username = state.userProfile.username),
            inlineMessage = null,
        )
    }

    fun validateLogin(form: LoginFormState, language: AppLanguage): String? = when {
        form.email.isBlank() -> tr(language, "Введите e-mail или username", "Enter your email or username")
        form.password.isBlank() -> tr(language, "Введите пароль", "Enter your password")
        else -> null
    }

    sealed interface RegistrationStepTransition {
        data class UpdateState(val state: AppUiState) : RegistrationStepTransition
        data class Submit(
            val state: AppUiState,
            val form: RegistrationFormState,
        ) : RegistrationStepTransition
    }

    fun showWelcome(current: AppUiState): AppUiState {
        return current.copy(
            authStep = AuthStep.WELCOME,
            loginForm = current.loginForm.copy(
                error = null,
                isLoading = false,
            ),
            registrationForm = current.registrationForm.withAbandonedCodeRequestCleared(),
            inlineMessage = null,
        )
    }

    fun showEmailLogin(current: AppUiState): AppUiState {
        return current.copy(
            authStep = AuthStep.EMAIL_LOGIN,
            loginForm = current.loginForm.copy(
                error = null,
            ),
            inlineMessage = null,
        )
    }

    fun startRegistration(current: AppUiState): AppUiState {
        return current.copy(
            authStep = AuthStep.REGISTRATION_EMAIL,
            registrationForm = current.registrationForm.withAbandonedCodeRequestCleared(),
            inlineMessage = null,
        )
    }

    fun previousRegistrationStep(current: AppUiState): AppUiState {
        val previous = when (current.authStep) {
            AuthStep.REGISTRATION_PASSWORD -> AuthStep.REGISTRATION_PROFILE
            AuthStep.REGISTRATION_PROFILE -> AuthStep.REGISTRATION_CODE
            AuthStep.REGISTRATION_CODE -> AuthStep.REGISTRATION_EMAIL
            else -> AuthStep.REGISTRATION_EMAIL
        }
        return current.copy(
            authStep = previous,
            registrationForm = current.registrationForm.copy(
                error = null,
                isLoading = false,
                isCodeSending = false,
            ),
            inlineMessage = null,
        )
    }

    fun nextRegistrationStep(
        current: AppUiState,
        language: AppLanguage,
        isValidEmail: (String) -> Boolean,
    ): RegistrationStepTransition {
        val form = current.registrationForm
        return when (current.authStep) {
            AuthStep.REGISTRATION_EMAIL -> {
                if (!isValidEmail(form.email)) {
                    RegistrationStepTransition.UpdateState(
                        current.copy(registrationForm = form.copy(error = tr(language, "Введите корректный e-mail", "Enter a valid email"))),
                    )
                } else {
                    RegistrationStepTransition.UpdateState(
                        current.copy(
                            authStep = AuthStep.REGISTRATION_CODE,
                            registrationForm = form.copy(error = null),
                            inlineMessage = null,
                        ),
                    )
                }
            }
            AuthStep.REGISTRATION_CODE -> {
                val error = RegistrationStateReducer.verificationCodeValidationError(
                    verificationCode = form.verificationCode,
                    language = language,
                )
                RegistrationStepTransition.UpdateState(
                    if (error != null) {
                        current.copy(registrationForm = form.copy(error = error))
                    } else {
                        current.copy(
                            authStep = AuthStep.REGISTRATION_PROFILE,
                            registrationForm = form.copy(error = null),
                            inlineMessage = null,
                        )
                    },
                )
            }
            AuthStep.REGISTRATION_PROFILE -> {
                val error = RegistrationStateReducer.usernameValidationError(form.username, language)
                if (error != null) {
                    RegistrationStepTransition.UpdateState(
                        current.copy(registrationForm = form.copy(error = error)),
                    )
                } else {
                    RegistrationStepTransition.UpdateState(
                        current.copy(
                            authStep = AuthStep.REGISTRATION_PASSWORD,
                            registrationForm = form.copy(error = null),
                            inlineMessage = null,
                        ),
                    )
                }
            }
            AuthStep.REGISTRATION_PASSWORD -> {
                val submit = RegistrationStateReducer.submit(
                    current = current,
                    language = language,
                    isValidEmail = isValidEmail,
                )
                when (submit) {
                    is RegistrationStateReducer.SubmitTransition.UpdateState ->
                        RegistrationStepTransition.UpdateState(submit.state)
                    is RegistrationStateReducer.SubmitTransition.Register ->
                        RegistrationStepTransition.Submit(submit.state, submit.form)
                }
            }
            AuthStep.WELCOME,
            AuthStep.EMAIL_LOGIN -> RegistrationStepTransition.UpdateState(current)
        }
    }

    private fun tr(
        language: AppLanguage,
        russian: String,
        english: String,
    ): String {
        return if (language == AppLanguage.RU) russian else english
    }

    private fun RegistrationFormState.withAbandonedCodeRequestCleared(): RegistrationFormState = copy(
        verificationCode = "",
        codeSent = false,
        isLoading = false,
        isCodeSending = false,
        codeCooldownSeconds = 0,
        error = null,
    )
}
