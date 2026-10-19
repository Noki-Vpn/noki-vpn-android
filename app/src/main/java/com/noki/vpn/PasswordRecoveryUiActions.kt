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

private const val PASSWORD_RECOVERY_COOLDOWN_TICK_MS = 1_000L

private fun AppUiRuntime.isCurrentPasswordRecoveryOperation(
    ownerJob: Job,
    workflowOwner: WorkflowOwner,
    requestKey: String,
): Boolean = passwordRecoveryOperationJob === ownerJob &&
    ownerJob.isActive &&
    accountRecoveryWorkflow.accepts(workflowOwner, requestKey)

private fun AppUiRuntime.requireCurrentPasswordRecoveryOperation(
    ownerJob: Job,
    workflowOwner: WorkflowOwner,
    requestKey: String,
) {
    if (!isCurrentPasswordRecoveryOperation(ownerJob, workflowOwner, requestKey)) {
        throw CancellationException("Password recovery operation is no longer current")
    }
}

internal fun AppUiRuntime.startPasswordRecoveryCooldown(seconds: Int) {
    val staleCooldown = passwordRecoveryCooldownJob
    passwordRecoveryCooldownJob = null
    staleCooldown?.cancel()
    if (seconds <= 0) {
        uiState = uiState.copy(
            passwordRecoveryForm = uiState.passwordRecoveryForm.copy(codeCooldownSeconds = 0),
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
            ?: error("Password recovery cooldown coroutine has no Job")
        try {
            while (ownerJob.isActive && passwordRecoveryCooldownJob === ownerJob) {
                val remaining = cooldownRemainingSeconds(
                    deadlineElapsedMs = deadlineElapsedMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                if (uiState.passwordRecoveryForm.codeCooldownSeconds != remaining) {
                    uiState = uiState.copy(
                        passwordRecoveryForm = uiState.passwordRecoveryForm.copy(
                            codeCooldownSeconds = remaining,
                        ),
                    )
                }
                if (remaining == 0) return@launch
                delay(PASSWORD_RECOVERY_COOLDOWN_TICK_MS)
            }
        } finally {
            if (passwordRecoveryCooldownJob === ownerJob) passwordRecoveryCooldownJob = null
        }
    }
    passwordRecoveryCooldownJob = job
    job.start()
}

internal fun AppUiRuntime.openPasswordRecovery() {
    val staleCooldown = passwordRecoveryCooldownJob
    passwordRecoveryCooldownJob = null
    staleCooldown?.cancel()
    val staleOperation = passwordRecoveryOperationJob
    passwordRecoveryOperationJob = null
    staleOperation?.cancel()
    accountRecoveryWorkflow.invalidate()
    val stack = if (uiState.currentDestination == AppDestination.PASSWORD_RECOVERY) {
        uiState.screenStack
    } else {
        uiState.screenStack + AppDestination.PASSWORD_RECOVERY
    }
    uiState = uiState.copy(
        passwordRecoveryForm = PasswordRecoveryFormState(),
        passwordRecoveryPurpose = PasswordRecoveryPurpose.LOGIN,
        screenStack = stack,
        inlineMessage = null,
    )
}

internal fun AppUiRuntime.updatePasswordRecoveryEmail(value: String) {
    if (uiState.passwordRecoveryForm.isSubmitting) return
    val staleCooldown = passwordRecoveryCooldownJob
    passwordRecoveryCooldownJob = null
    staleCooldown?.cancel()
    val staleOperation = passwordRecoveryOperationJob
    passwordRecoveryOperationJob = null
    staleOperation?.cancel()
    accountRecoveryWorkflow.invalidate()
    uiState = uiState.copy(
        passwordRecoveryForm = uiState.passwordRecoveryForm.copy(
            email = value,
            verificationCode = "",
            showCodeField = false,
            codeSent = false,
            isCodeSending = false,
            codeCooldownSeconds = 0,
            password = "",
            passwordRepeat = "",
            passwordStepVisible = false,
            isSubmitting = false,
            error = null,
        ),
    )
}

internal fun AppUiRuntime.updatePasswordRecoveryCode(value: String) {
    if (uiState.passwordRecoveryForm.isSubmitting) return
    uiState = uiState.copy(
        passwordRecoveryForm = uiState.passwordRecoveryForm.copy(
            verificationCode = value,
            error = null,
        ),
    )
}

internal fun AppUiRuntime.updatePasswordRecoveryPassword(value: String) {
    if (uiState.passwordRecoveryForm.isSubmitting) return
    uiState = uiState.copy(
        passwordRecoveryForm = uiState.passwordRecoveryForm.copy(
            password = value,
            error = null,
        ),
    )
}

internal fun AppUiRuntime.updatePasswordRecoveryPasswordRepeat(value: String) {
    if (uiState.passwordRecoveryForm.isSubmitting) return
    uiState = uiState.copy(
        passwordRecoveryForm = uiState.passwordRecoveryForm.copy(
            passwordRepeat = value,
            error = null,
        ),
    )
}

internal fun AppUiRuntime.requestPasswordRecoveryCode() {
    if (passwordRecoveryOperationJob != null) return
    val language = uiState.personalizationSettings.language
    when (val transition = AccountRecoveryStateReducer.requestCode(
        current = uiState,
        language = language,
        isValidEmail = ::isValidEmailAddress,
    )) {
        AccountRecoveryStateReducer.CodeRequestTransition.Noop -> return
        is AccountRecoveryStateReducer.CodeRequestTransition.UpdateState -> uiState = transition.state
        is AccountRecoveryStateReducer.CodeRequestTransition.Send -> {
            val email = transition.form.email.trim()
            val purpose = transition.state.passwordRecoveryPurpose
            val requestKey = "send:$email"
            val workflowOwner = accountRecoveryWorkflow.begin(requestKey)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val ownerJob = currentCoroutineContext()[Job]
                    ?: error("Password recovery code request coroutine has no Job")
                try {
                    val cooldownSeconds = if (purpose == PasswordRecoveryPurpose.ACCOUNT_SECURITY) {
                        authSessionCoordinator.run { token ->
                            accountSecurityCoordinator().sendEmailCode(
                                context = accountSecurityContext(token), email = email,
                                currentEmail = null, currentVerificationCode = null,
                                purpose = "account_password_change",
                            )
                        }
                    } else accountRecoveryWorkflow.sendCode(transition.form.email)
                    if (isCurrentPasswordRecoveryOperation(ownerJob, workflowOwner, requestKey)) {
                        uiState = AccountRecoveryStateReducer.codeRequestSent(uiState, cooldownSeconds, language)
                        startPasswordRecoveryCooldown(cooldownSeconds)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (isCurrentPasswordRecoveryOperation(ownerJob, workflowOwner, requestKey)) {
                        uiState = AccountRecoveryStateReducer.codeRequestFailed(
                            current = uiState,
                            error = AppErrorMapper.readablePasswordRecoveryError(language, error),
                        )
                    }
                } finally {
                    if (passwordRecoveryOperationJob === ownerJob) {
                        passwordRecoveryOperationJob = null
                    }
                }
            }
            passwordRecoveryOperationJob = job
            uiState = transition.state
            job.start()
        }
    }
}

internal fun AppUiRuntime.submitPasswordRecovery() {
    if (passwordRecoveryOperationJob != null) return
    val language = uiState.personalizationSettings.language
    when (val transition = AccountRecoveryStateReducer.submit(
        current = uiState,
        language = language,
        isValidEmail = ::isValidEmailAddress,
    )) {
        is AccountRecoveryStateReducer.SubmitTransition.UpdateState -> uiState = transition.state
        is AccountRecoveryStateReducer.SubmitTransition.VerifyCode -> {
            val key = "verify:${transition.form.email.trim()}:${transition.form.verificationCode.trim()}"
            val purpose = transition.state.passwordRecoveryPurpose
            val workflowOwner = accountRecoveryWorkflow.begin(key)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val ownerJob = currentCoroutineContext()[Job]
                    ?: error("Password recovery code verification coroutine has no Job")
                try {
                    if (purpose == PasswordRecoveryPurpose.ACCOUNT_SECURITY) {
                        authSessionCoordinator.run { token ->
                            accountSecurityCoordinator().verifyPasswordCode(
                                accountSecurityContext(token),
                                transition.form.email.trim(),
                                transition.form.verificationCode.trim(),
                            )
                        }
                    } else accountRecoveryWorkflow.verifyCode(transition.form)
                    if (isCurrentPasswordRecoveryOperation(ownerJob, workflowOwner, key)) {
                        val staleCooldown = passwordRecoveryCooldownJob
                        passwordRecoveryCooldownJob = null
                        staleCooldown?.cancel()
                        uiState = AccountRecoveryStateReducer.codeVerified(uiState)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (isCurrentPasswordRecoveryOperation(ownerJob, workflowOwner, key)) {
                        uiState = AccountRecoveryStateReducer.codeVerificationFailed(
                            current = uiState,
                            error = AppErrorMapper.readablePasswordRecoveryError(language, error),
                        )
                    }
                } finally {
                    if (passwordRecoveryOperationJob === ownerJob) {
                        passwordRecoveryOperationJob = null
                    }
                }
            }
            passwordRecoveryOperationJob = job
            uiState = transition.state
            job.start()
        }
        is AccountRecoveryStateReducer.SubmitTransition.ResetPassword -> {
            val key = "reset:${transition.form.email.trim()}:${transition.form.verificationCode.trim()}"
            val workflowOwner = accountRecoveryWorkflow.begin(key)
            val purpose = transition.state.passwordRecoveryPurpose
            val returnStack = transition.state.screenStack
                .dropLast(1)
                .ifEmpty { listOf(AppDestination.SECURITY) }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val ownerJob = currentCoroutineContext()[Job]
                    ?: error("Password reset coroutine has no Job")
                try {
                    val changedUser = if (purpose == PasswordRecoveryPurpose.ACCOUNT_SECURITY) {
                        val attempt = authSessionCoordinator.attempt() ?: throw BackendException("auth_required", 401)
                        authSessionCoordinator.run(attempt) { token ->
                            accountSecurityCoordinator().changePassword(
                                context = accountSecurityContext(token),
                                authAttempt = attempt,
                                currentPassword = null,
                                newPassword = transition.form.password,
                                verificationCode = transition.form.verificationCode.trim(),
                            ).user
                        }
                    } else {
                        accountRecoveryWorkflow.resetPassword(transition.form)
                        null
                    }
                    requireCurrentPasswordRecoveryOperation(ownerJob, workflowOwner, key)
                    if (changedUser != null) {
                        applyAccountSecurityUser(changedUser)
                        uiState = uiState.copy(
                            passwordRecoveryForm = PasswordRecoveryFormState(),
                            passwordRecoveryPurpose = PasswordRecoveryPurpose.LOGIN,
                            screenStack = returnStack,
                            inlineMessage = null,
                        )
                        startAppNotificationPolling()
                        syncFcmTokenIfAvailable()
                        maybeUploadLogsAutomatically()
                    } else {
                        uiState = AccountRecoveryStateReducer.passwordReset(uiState, language)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (isCurrentPasswordRecoveryOperation(ownerJob, workflowOwner, key)) {
                        uiState = AccountRecoveryStateReducer.passwordResetFailed(
                            current = uiState,
                            error = AppErrorMapper.readablePasswordRecoveryError(language, error),
                        )
                    }
                } finally {
                    if (passwordRecoveryOperationJob === ownerJob) {
                        passwordRecoveryOperationJob = null
                    }
                }
            }
            passwordRecoveryOperationJob = job
            uiState = transition.state
            job.start()
        }
    }
}
