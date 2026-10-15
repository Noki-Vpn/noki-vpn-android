package com.noki.vpn

import android.os.SystemClock
import com.noki.vpn.data.BackendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REGISTRATION_USERNAME_CHECK_DEBOUNCE_MS = 450L
private const val REGISTRATION_COOLDOWN_TICK_MS = 1_000L

internal fun cooldownRemainingSeconds(
    deadlineElapsedMs: Long,
    nowElapsedMs: Long,
): Int {
    val remainingMs = deadlineElapsedMs - nowElapsedMs
    if (remainingMs <= 0L) return 0
    return (((remainingMs - 1L) / 1_000L) + 1L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun AppUiRuntime.openRegistrationFlow() {
    val nextStack = when (uiState.currentDestination) {
        AppDestination.REGISTRATION -> uiState.screenStack
        AppDestination.LOGIN -> listOf(AppDestination.LOGIN, AppDestination.REGISTRATION)
        else -> uiState.screenStack + AppDestination.REGISTRATION
    }
    uiState = AuthStepReducer.startRegistration(uiState).copy(screenStack = nextStack)
}

internal fun AppUiRuntime.updateRegistrationUsername(value: String) {
    val staleUsernameCheck = registrationUsernameCheckJob
    registrationUsernameCheckJob = null
    staleUsernameCheck?.cancel()
    registrationWorkflow.invalidate()
    uiState = RegistrationStateReducer.usernameChanged(
        current = uiState,
        value = value,
        language = uiState.personalizationSettings.language,
    )
    scheduleRegistrationUsernameAvailabilityCheck()
}

internal fun AppUiRuntime.updateRegistrationEmail(value: String) {
    val staleCooldown = registrationCodeCooldownJob
    registrationCodeCooldownJob = null
    staleCooldown?.cancel()
    val staleCodeRequest = registrationCodeRequestJob
    registrationCodeRequestJob = null
    staleCodeRequest?.cancel()
    val staleCodeVerification = registrationCodeVerificationJob
    registrationCodeVerificationJob = null
    staleCodeVerification?.cancel()
    val staleUsernameCheck = registrationUsernameCheckJob
    registrationUsernameCheckJob = null
    staleUsernameCheck?.cancel()
    registrationWorkflow.invalidate()
    uiState = RegistrationStateReducer.emailChanged(uiState, value)
}

internal fun AppUiRuntime.scheduleRegistrationUsernameAvailabilityCheck() {
    if (uiState.authStep != AuthStep.REGISTRATION_PROFILE) return
    if (uiState.isAuthenticated && uiState.userProfile.credentialsSetupRequired) return
    val language = uiState.personalizationSettings.language
    val form = uiState.registrationForm
    val username = form.username.trim()
    if (RegistrationStateReducer.usernameValidationError(username, language) != null) return
    val requestKey = "username:$username"
    val workflowOwner = registrationWorkflow.begin(requestKey)
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("Registration username check coroutine has no Job")
        try {
            delay(REGISTRATION_USERNAME_CHECK_DEBOUNCE_MS)
            val beforeCheckForm = uiState.registrationForm
            if (
                registrationUsernameCheckJob !== ownerJob ||
                !registrationWorkflow.accepts(workflowOwner, requestKey) ||
                uiState.authStep != AuthStep.REGISTRATION_PROFILE ||
                beforeCheckForm.username.trim() != username
            ) {
                return@launch
            }
            uiState = uiState.copy(
                registrationForm = beforeCheckForm.copy(isLoading = true, error = null),
                inlineMessage = null,
            )
            val available = backendApi.checkRegistrationUsernameAvailable(username)
            val latestForm = uiState.registrationForm
            if (
                registrationUsernameCheckJob === ownerJob &&
                ownerJob.isActive &&
                registrationWorkflow.accepts(workflowOwner, requestKey) &&
                uiState.authStep == AuthStep.REGISTRATION_PROFILE &&
                latestForm.username.trim() == username
            ) {
                uiState = uiState.copy(
                    registrationForm = latestForm.copy(
                        isLoading = false,
                        error = if (available) {
                            null
                        } else {
                            AppErrorMapper.readableRegistrationUsernameError(
                                language,
                                BackendException("Username already exists", 409),
                            )
                        },
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val latestForm = uiState.registrationForm
            if (
                registrationUsernameCheckJob === ownerJob &&
                ownerJob.isActive &&
                registrationWorkflow.accepts(workflowOwner, requestKey) &&
                uiState.authStep == AuthStep.REGISTRATION_PROFILE &&
                latestForm.username.trim() == username
            ) {
                uiState = uiState.copy(
                    registrationForm = latestForm.copy(
                        isLoading = false,
                        error = if (error is BackendException && error.statusCode == 409) {
                            AppErrorMapper.readableRegistrationUsernameError(language, error)
                        } else {
                            null
                        },
                    ),
                )
            }
        } finally {
            if (registrationUsernameCheckJob === ownerJob) registrationUsernameCheckJob = null
        }
    }
    registrationUsernameCheckJob = job
    job.start()
}

internal fun AppUiRuntime.updateRegistrationVerificationCode(value: String) {
    val staleVerification = registrationCodeVerificationJob
    if (staleVerification != null) {
        registrationCodeVerificationJob = null
        staleVerification.cancel()
        registrationWorkflow.invalidate()
    }
    uiState = RegistrationStateReducer.verificationCodeChanged(uiState, value)
}

internal fun AppUiRuntime.updateRegistrationPassword(value: String) {
    if (uiState.registrationForm.isLoading) return
    uiState = RegistrationStateReducer.passwordChanged(uiState, value)
}

internal fun AppUiRuntime.requestRegistrationCode() {
    if (uiState.authStep == AuthStep.REGISTRATION_CODE && uiState.registrationForm.isLoading) {
        val staleVerification = registrationCodeVerificationJob
        registrationCodeVerificationJob = null
        staleVerification?.cancel()
        registrationWorkflow.invalidate()
        uiState = uiState.copy(
            registrationForm = uiState.registrationForm.copy(isLoading = false),
        )
    }
    val language = uiState.personalizationSettings.language
    when (val transition = RegistrationStateReducer.requestCode(
        current = uiState,
        language = language,
        isValidEmail = ::isValidEmailAddress,
    )) {
        RegistrationStateReducer.CodeRequestTransition.Noop -> return
        is RegistrationStateReducer.CodeRequestTransition.UpdateState -> uiState = transition.state
        is RegistrationStateReducer.CodeRequestTransition.Send -> {
            val email = transition.form.email.trim()
            val requestKey = "code:$email"
            val workflowOwner = registrationWorkflow.begin(requestKey)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val ownerJob = currentCoroutineContext()[Job]
                    ?: error("Registration code request coroutine has no Job")
                try {
                    val cooldownSeconds = backendApi.sendRegistrationCode(email)
                    val latestForm = uiState.registrationForm
                    if (registrationCodeRequestJob === ownerJob &&
                        ownerJob.isActive &&
                        registrationWorkflow.accepts(workflowOwner, requestKey) &&
                        latestForm.email.trim() == email
                    ) {
                        uiState = RegistrationStateReducer.codeRequestSent(uiState, cooldownSeconds, language).copy(
                            authStep = AuthStep.REGISTRATION_CODE,
                        )
                        startRegistrationCodeCooldown(cooldownSeconds)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val latestForm = uiState.registrationForm
                    if (registrationCodeRequestJob === ownerJob &&
                        ownerJob.isActive &&
                        registrationWorkflow.accepts(workflowOwner, requestKey) &&
                        latestForm.email.trim() == email
                    ) {
                        uiState = RegistrationStateReducer.codeRequestFailed(
                            current = uiState,
                            error = AppErrorMapper.readableRegistrationEmailError(language, error),
                        )
                    }
                } finally {
                    if (registrationCodeRequestJob === ownerJob) registrationCodeRequestJob = null
                }
            }
            registrationCodeRequestJob = job
            uiState = transition.state
            job.start()
        }
    }
}

internal fun AppUiRuntime.previousRegistrationStep() {
    goBack()
}

internal fun AppUiRuntime.startRegistrationCodeCooldown(seconds: Int) {
    val staleCooldown = registrationCodeCooldownJob
    registrationCodeCooldownJob = null
    staleCooldown?.cancel()
    if (seconds <= 0) {
        uiState = uiState.copy(
            registrationForm = uiState.registrationForm.copy(codeCooldownSeconds = 0),
        )
        return
    }
    val startedAt = SystemClock.elapsedRealtime()
    val durationMs = seconds.toLong() * 1_000L
    val deadlineElapsedMs = if (durationMs > Long.MAX_VALUE - startedAt) {
        Long.MAX_VALUE
    } else {
        startedAt + durationMs
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("Registration cooldown coroutine has no Job")
        try {
            while (ownerJob.isActive && registrationCodeCooldownJob === ownerJob) {
                val remaining = cooldownRemainingSeconds(
                    deadlineElapsedMs = deadlineElapsedMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                if (uiState.registrationForm.codeCooldownSeconds != remaining) {
                    uiState = uiState.copy(
                        registrationForm = uiState.registrationForm.copy(codeCooldownSeconds = remaining),
                    )
                }
                if (remaining == 0) return@launch
                delay(REGISTRATION_COOLDOWN_TICK_MS)
            }
        } finally {
            if (registrationCodeCooldownJob === ownerJob) registrationCodeCooldownJob = null
        }
    }
    registrationCodeCooldownJob = job
    job.start()
}

internal fun AppUiRuntime.updateRegistrationPasswordRepeat(value: String) {
    if (uiState.registrationForm.isLoading) return
    uiState = RegistrationStateReducer.passwordRepeatChanged(uiState, value)
}

internal fun AppUiRuntime.submitRegistration() {
    nextRegistrationStep()
}

internal fun AppUiRuntime.nextRegistrationStep() {
    if (sessionOperationJob != null || telegramAuthPurposeState.currentAttemptId != null) return
    val language = uiState.personalizationSettings.language
    if (uiState.authStep == AuthStep.REGISTRATION_CODE) {
        val form = uiState.registrationForm
        val verificationCode = form.verificationCode.trim()
        val validationError = RegistrationStateReducer.verificationCodeValidationError(
            verificationCode = verificationCode,
            language = language,
        )
        if (validationError != null) {
            uiState = uiState.copy(
                registrationForm = form.copy(
                    verificationCode = verificationCode,
                    isLoading = false,
                    error = validationError,
                ),
            )
            return
        }
        if (form.isLoading) return

        val email = form.email.trim()
        val requestKey = "verify_code:$email:$verificationCode"
        val staleVerification = registrationCodeVerificationJob
        registrationCodeVerificationJob = null
        staleVerification?.cancel()
        val workflowOwner = registrationWorkflow.begin(requestKey)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownerJob = currentCoroutineContext()[Job]
                ?: error("Registration code verification coroutine has no Job")
            try {
                backendApi.verifyRegistrationCode(email, verificationCode)
                val latestForm = uiState.registrationForm
                if (
                    registrationCodeVerificationJob === ownerJob &&
                    ownerJob.isActive &&
                    registrationWorkflow.accepts(workflowOwner, requestKey) &&
                    uiState.authStep == AuthStep.REGISTRATION_CODE &&
                    latestForm.email.trim() == email &&
                    latestForm.verificationCode.trim() == verificationCode
                ) {
                    uiState = uiState.copy(
                        authStep = AuthStep.REGISTRATION_PROFILE,
                        registrationForm = latestForm.copy(isLoading = false, error = null),
                        inlineMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val latestForm = uiState.registrationForm
                if (
                    registrationCodeVerificationJob === ownerJob &&
                    ownerJob.isActive &&
                    registrationWorkflow.accepts(workflowOwner, requestKey) &&
                    uiState.authStep == AuthStep.REGISTRATION_CODE &&
                    latestForm.email.trim() == email &&
                    latestForm.verificationCode.trim() == verificationCode
                ) {
                    uiState = uiState.copy(
                        registrationForm = latestForm.copy(
                            isLoading = false,
                            error = AppErrorMapper.readableRegistrationCodeError(language, error),
                        ),
                    )
                }
            } finally {
                if (registrationCodeVerificationJob === ownerJob) registrationCodeVerificationJob = null
            }
        }
        registrationCodeVerificationJob = job
        uiState = uiState.copy(
            registrationForm = form.copy(
                verificationCode = verificationCode,
                isLoading = true,
                error = null,
            ),
            inlineMessage = null,
        )
        job.start()
        return
    }
    if (uiState.authStep == AuthStep.REGISTRATION_PROFILE) {
        val form = uiState.registrationForm
        val username = form.username.trim()
        val validationError = RegistrationStateReducer.usernameValidationError(username, language)
        if (validationError != null) {
            uiState = uiState.copy(registrationForm = form.copy(username = username, error = validationError))
            return
        }
        if (form.isLoading) return
        val staleUsernameCheck = registrationUsernameCheckJob
        registrationUsernameCheckJob = null
        staleUsernameCheck?.cancel()
        if (uiState.isAuthenticated && uiState.userProfile.credentialsSetupRequired) {
            registrationWorkflow.invalidate()
            val transition = AuthStepReducer.nextRegistrationStep(
                current = uiState.copy(registrationForm = form.copy(username = username)),
                language = language,
                isValidEmail = ::isValidEmailAddress,
            )
            if (transition is AuthStepReducer.RegistrationStepTransition.UpdateState) uiState = transition.state
            return
        }
        val requestKey = "username-submit:$username"
        val workflowOwner = registrationWorkflow.begin(requestKey)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownerJob = currentCoroutineContext()[Job]
                ?: error("Registration username submit coroutine has no Job")
            try {
                val available = backendApi.checkRegistrationUsernameAvailable(username)
                val latestForm = uiState.registrationForm
                if (
                    registrationUsernameCheckJob !== ownerJob ||
                    !ownerJob.isActive ||
                    !registrationWorkflow.accepts(workflowOwner, requestKey) ||
                    uiState.authStep != AuthStep.REGISTRATION_PROFILE ||
                    latestForm.username.trim() != username
                ) {
                    return@launch
                }
                if (available) {
                    uiState = uiState.copy(
                        authStep = AuthStep.REGISTRATION_PASSWORD,
                        registrationForm = latestForm.copy(isLoading = false, error = null),
                        inlineMessage = null,
                    )
                } else {
                    uiState = uiState.copy(
                        registrationForm = latestForm.copy(
                            isLoading = false,
                            error = AppErrorMapper.readableRegistrationUsernameError(
                                language,
                                BackendException("Username already exists", 409),
                            ),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val latestForm = uiState.registrationForm
                if (
                    registrationUsernameCheckJob === ownerJob &&
                    ownerJob.isActive &&
                    registrationWorkflow.accepts(workflowOwner, requestKey) &&
                    uiState.authStep == AuthStep.REGISTRATION_PROFILE &&
                    latestForm.username.trim() == username
                ) {
                    uiState = uiState.copy(
                        registrationForm = latestForm.copy(
                            isLoading = false,
                            error = if (error is BackendException && error.statusCode == 409) {
                                AppErrorMapper.readableRegistrationUsernameError(language, error)
                            } else {
                                AppErrorMapper.readableRegistrationError(language, error)
                            },
                        ),
                    )
                }
            } finally {
                if (registrationUsernameCheckJob === ownerJob) registrationUsernameCheckJob = null
            }
        }
        registrationUsernameCheckJob = job
        uiState = uiState.copy(
            registrationForm = form.copy(username = username, isLoading = true, error = null),
            inlineMessage = null,
        )
        job.start()
        return
    }
    when (val transition = AuthStepReducer.nextRegistrationStep(
        current = uiState,
        language = language,
        isValidEmail = ::isValidEmailAddress,
    )) {
        is AuthStepReducer.RegistrationStepTransition.UpdateState -> uiState = transition.state
        is AuthStepReducer.RegistrationStepTransition.Submit -> {
            if (uiState.userProfile.credentialsSetupRequired) {
                val deviceId = backendDeviceId.ifBlank { null }
                val deviceKey = backendDeviceKey.ifBlank { null }
                launchAuthenticatedSessionOperation(
                    replaceDeviceRead = true,
                    onStarted = { uiState = transition.state },
                    operation = { attempt ->
                        authSessionCoordinator.run(attempt) { token ->
                            backendApi.setupAccountCredentials(
                                token = token,
                                username = transition.form.username.trim(),
                                password = transition.form.password,
                                currentDeviceId = deviceId,
                                currentDeviceKey = deviceKey,
                            )
                        }
                    },
                    onSuccess = { user ->
                        cancelBackendRefresh()
                        applyAccountSecurityUser(user)
                    },
                    onFailure = { error ->
                        uiState = RegistrationStateReducer.registrationFailed(
                            current = uiState,
                            error = AppErrorMapper.readableRegistrationError(language, error),
                        )
                        if (error is BackendException && error.statusCode == 409 &&
                            error.message == "Credentials setup is not required"
                        ) {
                            cancelBackendRefresh()
                            launchBackendRefresh(BackendRefreshTrigger.Initial)
                        }
                    },
                )
                return
            }
            launchSessionOperation(
                onStarted = { uiState = transition.state },
                operation = {
                    authFlowCoordinator().register(
                        form = transition.form,
                        baseState = transition.state,
                        language = language,
                        deviceId = backendDeviceId.ifBlank { null },
                    )
                },
                onSuccess = {
                    startAppNotificationPolling()
                    syncFcmTokenIfAvailable()
                },
                onFailure = { error ->
                    uiState = RegistrationStateReducer.registrationFailed(
                        current = uiState,
                        error = AppErrorMapper.readableRegistrationError(language, error),
                    )
                },
            )
        }
    }
}
