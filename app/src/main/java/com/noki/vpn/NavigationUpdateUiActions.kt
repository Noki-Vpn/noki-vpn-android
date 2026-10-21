package com.noki.vpn

import com.noki.vpn.data.AppInfo
import com.noki.vpn.data.InstallOnboardingStep
import com.noki.vpn.ui.PrimaryNavigationPolicy
import com.noki.vpn.vpn.VpnRuntimeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun isCurrentAndroidUpdateOperation(
    owner: Job,
    current: Job?,
    isSessionCurrent: Boolean,
): Boolean = current === owner && owner.isActive && isSessionCurrent

internal fun nextAndroidUpdateRevision(current: Long): Long =
    if (current == Long.MAX_VALUE) 0L else current + 1L

internal fun AppUiRuntime.advanceAndroidUpdateRevision(): Long {
    androidUpdateRevision = nextAndroidUpdateRevision(androidUpdateRevision)
    return androidUpdateRevision
}

private fun AppUiRuntime.isCurrentAndroidUpdateOperation(
    owner: Job,
    attempt: AuthSessionAttempt,
): Boolean = isCurrentAndroidUpdateOperation(
    owner = owner,
    current = androidUpdateJob,
    isSessionCurrent = authSessionCoordinator.isCurrent(attempt),
)

internal fun AppUiRuntime.setPendingVpnStartMode(mode: VpnRuntimeMode) {
    pendingVpnStartModeState.mode = mode
}

internal fun requireStartupFlow(state: AppUiState, previous: AppUiState = state): AppUiState {
    if (!state.isReady || state.currentDestination == AppDestination.SPLASH) return state
    val authenticated = AuthStepReducer.requireCredentialsSetup(state, previous)
    if (!authenticated.isAuthenticated) {
        return if (AppDestination.ONBOARDING in authenticated.screenStack) {
            authenticated.copy(screenStack = listOf(AppDestination.LOGIN), dialog = null)
        } else authenticated
    }
    if (authenticated.userProfile.credentialsSetupRequired ||
        authenticated.installOnboardingStep == InstallOnboardingStep.COMPLETE
    ) return authenticated
    if (authenticated.installOnboardingStep == InstallOnboardingStep.APPEARANCE) {
        return authenticated.copy(screenStack = listOf(AppDestination.HOME), dialog = null)
    }
    val selectingApps = authenticated.installOnboardingStep == InstallOnboardingStep.ROUTING &&
        authenticated.screenStack == listOf(AppDestination.ONBOARDING, AppDestination.APP_FILTER)
    return if (selectingApps) authenticated else authenticated.copy(
        screenStack = listOf(AppDestination.ONBOARDING),
        dialog = null,
    )
}

internal fun showsInstallAppearanceDialog(state: AppUiState): Boolean =
    state.isReady && state.installOnboardingStep == InstallOnboardingStep.APPEARANCE &&
        state.isAuthenticated && !state.userProfile.credentialsSetupRequired &&
        state.currentDestination == AppDestination.HOME

internal fun AppUiRuntime.advanceInstallOnboarding() {
    val canAdvance = if (uiState.installOnboardingStep == InstallOnboardingStep.APPEARANCE) {
        showsInstallAppearanceDialog(uiState)
    } else uiState.currentDestination == AppDestination.ONBOARDING
    if (!uiState.isReady || !uiState.isAuthenticated || uiState.userProfile.credentialsSetupRequired || !canAdvance) return
    val next = when (uiState.installOnboardingStep) {
        InstallOnboardingStep.CONSENT -> InstallOnboardingStep.ROUTING
        InstallOnboardingStep.ROUTING -> InstallOnboardingStep.APPEARANCE
        InstallOnboardingStep.APPEARANCE -> InstallOnboardingStep.COMPLETE
        InstallOnboardingStep.COMPLETE -> return
    }
    saveInstallOnboardingStep(next)
}

private fun AppUiRuntime.saveInstallOnboardingStep(step: InstallOnboardingStep) {
    try {
        repository.saveInstallOnboardingStep(step)
        uiState = uiState.copy(
            installOnboardingStep = step,
            screenStack = listOf(if (step == InstallOnboardingStep.COMPLETE || step == InstallOnboardingStep.APPEARANCE) {
                if (uiState.isAuthenticated) AppDestination.HOME else AppDestination.LOGIN
            } else AppDestination.ONBOARDING),
            inlineMessage = null,
        )
    } catch (_: Exception) {
        uiState = uiState.copy(inlineMessage = tr(
            uiState.personalizationSettings.language,
            "Не удалось сохранить настройку. Попробуйте ещё раз.",
            "Couldn't save your choice. Please try again.",
        ))
    }
}

internal fun AppUiRuntime.goBack() {
    if (uiState.isAuthenticated && !uiState.userProfile.credentialsSetupRequired &&
        (uiState.installOnboardingStep == InstallOnboardingStep.CONSENT ||
            uiState.installOnboardingStep == InstallOnboardingStep.ROUTING)
    ) {
        if (uiState.currentDestination == AppDestination.APP_FILTER) {
            uiState = uiState.copy(screenStack = listOf(AppDestination.ONBOARDING), dialog = null)
        }
        return
    }
    if (showsInstallAppearanceDialog(uiState)) {
        advanceInstallOnboarding()
        return
    }
    if (!uiState.isAuthenticated && sessionOperationJob != null) return
    if (uiState.userProfile.credentialsSetupRequired) {
        if (sessionOperationJob != null) return
        val staleUsernameCheck = registrationUsernameCheckJob
        registrationUsernameCheckJob = null
        staleUsernameCheck?.cancel()
        registrationWorkflow.invalidate()
        if (uiState.authStep == AuthStep.REGISTRATION_PASSWORD) {
            uiState = AuthStepReducer.previousRegistrationStep(uiState)
        } else {
            logout()
        }
        return
    }
    if (uiState.currentDestination == AppDestination.PASSWORD_RECOVERY) {
        if (uiState.passwordRecoveryForm.isSubmitting) return
        val recoveryPurpose = uiState.passwordRecoveryPurpose
        val staleCooldown = passwordRecoveryCooldownJob
        passwordRecoveryCooldownJob = null
        staleCooldown?.cancel()
        val staleOperation = passwordRecoveryOperationJob
        passwordRecoveryOperationJob = null
        staleOperation?.cancel()
        accountRecoveryWorkflow.invalidate()
        uiState = uiState.copy(
            passwordRecoveryForm = PasswordRecoveryFormState(),
            passwordRecoveryPurpose = PasswordRecoveryPurpose.LOGIN,
            screenStack = uiState.screenStack
                .dropLast(1)
                .ifEmpty {
                    listOf(
                        if (recoveryPurpose == PasswordRecoveryPurpose.ACCOUNT_SECURITY) {
                            AppDestination.SECURITY
                        } else {
                            AppDestination.LOGIN
                        },
                    )
                },
            inlineMessage = null,
        )
        return
    }
    if (uiState.currentDestination == AppDestination.ACCOUNT_CREDENTIAL_CHANGE) {
        accountSecurityUiWorkflow.invalidate()
        val reduced = AccountSecurityStateReducer.back(uiState.accountSecurityState)
        uiState = if (reduced.action == null) {
            uiState.copy(
                accountSecurityState = reduced,
                screenStack = uiState.screenStack
                    .dropLast(1)
                    .ifEmpty { listOf(AppDestination.SECURITY) },
                inlineMessage = null,
            )
        } else {
            uiState.copy(accountSecurityState = reduced, inlineMessage = null)
        }
        return
    }
    if (!uiState.isAuthenticated) {
        if (uiState.currentDestination == AppDestination.LOGIN && uiState.authStep == AuthStep.EMAIL_LOGIN) {
            uiState = AuthStepReducer.showWelcome(uiState)
            return
        }
        if (uiState.currentDestination == AppDestination.REGISTRATION) {
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
            if (uiState.authStep == AuthStep.REGISTRATION_EMAIL) {
                val staleCooldown = registrationCodeCooldownJob
                registrationCodeCooldownJob = null
                staleCooldown?.cancel()
                uiState = AuthStepReducer.showWelcome(uiState).copy(
                    screenStack = listOf(AppDestination.LOGIN),
                )
            } else {
                uiState = AuthStepReducer.previousRegistrationStep(uiState)
            }
            return
        }
    }
    if (uiState.screenStack.size <= 1) return
    uiState = uiState.copy(screenStack = uiState.screenStack.dropLast(1), inlineMessage = null)
}

internal fun AppUiRuntime.openScreen(destination: AppDestination) {
    navigate(destination, replaceStack = false)
}

internal fun AppUiRuntime.openTopLevelScreen(destination: AppDestination) {
    if (!PrimaryNavigationPolicy.isTopLevelDestination(destination)) return
    if (uiState.dialog != null) return
    navigate(destination, replaceStack = true)
}

internal fun navigationStackAfterOpen(
    currentStack: List<AppDestination>,
    destination: AppDestination,
    replaceStack: Boolean,
): List<AppDestination> = when {
    replaceStack -> listOf(destination)
    currentStack.lastOrNull() == destination -> currentStack
    else -> currentStack + destination
}

private fun AppUiRuntime.navigate(
    destination: AppDestination,
    replaceStack: Boolean,
) {
    if (!uiState.isAuthenticated && sessionOperationJob != null) return
    if (destination == AppDestination.SECURITY) {
        refreshAndroidUpdateStatus()
    }
    if (isCurrentDeviceInvited() && destination in setOf(AppDestination.DEVICES, AppDestination.EXTRA_DEVICES)) {
        uiState = uiState.copy(dialog = AppDialog.AccessDenied, inlineMessage = null)
        return
    }
    if (destination == AppDestination.APP_FILTER) {
        ensureInstalledAppsLoaded()
    }
    val stateBeforeNavigation = if (destination == AppDestination.SETTINGS) {
        SettingsPreparedStatePolicy.withPreparedSettingsState(uiState)
    } else {
        uiState
    }
    val nextStack = navigationStackAfterOpen(
        currentStack = stateBeforeNavigation.screenStack,
        destination = destination,
        replaceStack = replaceStack,
    )
    if (nextStack == stateBeforeNavigation.screenStack) {
        uiState = stateBeforeNavigation.copy(inlineMessage = null)
        return
    }
    uiState = stateBeforeNavigation.copy(
        screenStack = nextStack,
        inlineMessage = null,
    )
}

internal fun AppUiRuntime.markAndroidUpdateAvailable() {
    refreshAndroidUpdateStatus()
}

internal fun AppUiRuntime.refreshAndroidUpdateStatus(installWhenAvailable: Boolean = false) {
    if (androidUpdateJob != null) return
    val attempt = authSessionCoordinator.attempt()
    if (attempt == null) {
        val updateState = androidUpdateCoordinator.unauthenticatedState().retainRequirement(uiState.androidUpdate)
        uiState = uiState.copy(
            isAndroidUpdateAvailable = false,
            androidUpdate = updateState,
        )
        return
    }
    val language = uiState.personalizationSettings.language
    val operationRevision = advanceAndroidUpdateRevision()
    val checkingState = uiState.androidUpdate.copy(
        isChecking = true,
        currentVersionName = repository.currentAppVersionName(),
        error = null,
    )
    uiState = uiState.copy(androidUpdate = checkingState)

    val job = scope.launch(start = CoroutineStart.LAZY) {
        val owner = currentCoroutineContext()[Job]
            ?: error("Android update coroutine has no Job")
        var availableUpdate: AndroidUpdateInfo? = null
        try {
            withContext(Dispatchers.IO) {
                androidUpdateCoordinator.clearSameOrOlderCachedApks()
            }
            val updateState = androidUpdateCoordinator.loadState(
                fallbackState = checkingState,
                language = language,
            )
            if (isCurrentAndroidUpdateOperation(owner, attempt)) {
                uiState = uiState.copy(
                    isAndroidUpdateAvailable = updateState.update != null,
                    androidUpdate = updateState.retainRequirement(uiState.androidUpdate),
                )
                availableUpdate = updateState.update
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrentAndroidUpdateOperation(owner, attempt)) {
                uiState = uiState.copy(
                    androidUpdate = uiState.androidUpdate.copy(
                        isChecking = false,
                        error = AppErrorMapper.readableNetworkError(language, error),
                    ),
                )
            }
        } finally {
            if (androidUpdateJob === owner) {
                androidUpdateJob = null
                if (androidUpdateRevision == operationRevision) {
                    advanceAndroidUpdateRevision()
                }
                uiState = uiState.copy(
                    androidUpdate = uiState.androidUpdate.copy(isChecking = false),
                )
                if (installWhenAvailable && availableUpdate != null && owner.isActive &&
                    authSessionCoordinator.isCurrent(attempt) && uiState.androidUpdate.requiresUpdate &&
                    uiState.androidUpdate.update == availableUpdate
                ) {
                    installAndroidUpdate()
                }
            }
        }
    }
    androidUpdateJob = job
    job.start()
}

internal fun AppUiRuntime.installAndroidUpdate() {
    if (androidUpdateJob != null || uiState.androidUpdate.isDownloading) return
    val update = uiState.androidUpdate.update ?: run {
        refreshAndroidUpdateStatus(installWhenAvailable = true)
        return
    }
    val language = uiState.personalizationSettings.language
    val attempt = authSessionCoordinator.attempt()
    if (attempt == null) {
        uiState = uiState.copy(
            androidUpdate = uiState.androidUpdate.copy(
                error = tr(language, "Войдите в аккаунт, чтобы скачать обновление", "Sign in to download update"),
            ),
        )
        return
    }
    val permissionMessage = androidUpdateCoordinator.requestInstallPermissionIfNeeded(language)
    if (permissionMessage != null) {
        uiState = uiState.copy(
            androidUpdate = uiState.androidUpdate.copy(
                error = permissionMessage,
            ),
        )
        return
    }
    if (uiState.androidUpdate.isReadyToInstall) {
        scope.launch {
            if (!androidUpdateCoordinator.launchReadyUpdate(explicit = true)) {
                uiState = uiState.copy(androidUpdate = uiState.androidUpdate.copy(
                    error = tr(language, "Не удалось открыть установщик APK", "Failed to open APK installer"),
                ))
            }
        }
    } else {
        AndroidUpdateWorker.enqueue(application, update, repository.load().backendDeviceId)
        uiState = uiState.copy(androidUpdate = uiState.androidUpdate.copy(isDownloading = true, error = null))
    }
}


internal fun AppUiRuntime.showCurrentDeviceAccessDenied() {
    uiState = uiState.copy(dialog = AppDialog.AccessDenied, inlineMessage = null)
}

internal suspend fun loadInstalledAppsOrEmpty(
    load: suspend () -> List<AppInfo>,
): List<AppInfo> = try {
    load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}

internal fun AppUiRuntime.ensureInstalledAppsLoaded() {
    if (uiState.installedApps.isNotEmpty() || uiState.isLoadingInstalledApps) return
    uiState = uiState.copy(isLoadingInstalledApps = true)
    scope.launch {
        val apps = loadInstalledAppsOrEmpty {
            withContext(Dispatchers.IO) { repository.loadInstalledApps() }
        }
        uiState = uiState.copy(
            installedApps = apps,
            isLoadingInstalledApps = false,
        )
    }
}

internal fun AppUiRuntime.clearMessage() {
    uiState = uiState.copy(inlineMessage = null)
}
