package com.noki.vpn

import android.net.Uri
import com.noki.vpn.data.AccentPalette
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BootstrapStateMapper
import com.noki.vpn.data.GlassMode
import com.noki.vpn.data.HomeLayoutVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

internal fun AppUiRuntime.setLanguage(language: AppLanguage) {
    val devices = if (syncedDevices.isNotEmpty()) {
        BootstrapStateMapper.mapDevices(syncedDevices, language, backendDeviceId, backendDeviceKey)
    } else {
        BootstrapStateMapper.initialDevices()
    }
    val locations = if (syncedLocations.isNotEmpty()) {
        BootstrapStateMapper.mapLocations(syncedLocations, language, clientLatencyByTarget)
    } else {
        BootstrapStateMapper.initialLocations()
    }
    val plans = BootstrapStateMapper.mapPlans(syncedPlans, language)
    applyAndPersist(
        uiState.copy(
            personalizationSettings = uiState.personalizationSettings.copy(language = language),
            plans = plans,
            devices = devices,
            locations = locations,
            usageBars = BootstrapStateMapper.initialUsageBars(),
        ),
    )
}

internal fun AppUiRuntime.setAccentPalette(accentPalette: AccentPalette) {
    applyAndPersist(
        uiState.copy(
            personalizationSettings = uiState.personalizationSettings.copy(accentPalette = accentPalette),
        ),
    )
}

internal fun AppUiRuntime.setHomeLayout(variant: HomeLayoutVariant) {
    applyAndPersist(
        uiState.copy(
            personalizationSettings = uiState.personalizationSettings.copy(homeLayoutVariant = variant),
        ),
    )
}

internal fun AppUiRuntime.setGlassMode(mode: GlassMode) {
    applyAndPersist(
        uiState.copy(
            personalizationSettings = uiState.personalizationSettings.copy(glassMode = mode),
        ),
    )
}

internal fun AppUiRuntime.setVpnStatusNotificationEnabled(enabled: Boolean) {
    applyAndPersist(
        uiState.copy(
            personalizationSettings = uiState.personalizationSettings.copy(vpnStatusNotificationEnabled = enabled),
        ),
    )
}

internal fun AppUiRuntime.beginAvatarCrop(uri: Uri?) {
    if (!uiState.isAuthenticated || avatarMutationJob != null) return
    if (denyAvatarEditIfInvited()) return
    val avatarUri = uri ?: return
    uiState = uiState.copy(
        pendingAvatarCropUri = avatarUri.toString(),
        avatarUploadMessage = null,
    )
}

internal fun AppUiRuntime.cancelAvatarCrop() {
    uiState = uiState.copy(pendingAvatarCropUri = null)
}

internal fun AppUiRuntime.uploadCroppedAvatar(
    sourceUri: String,
    previewWidthPx: Float,
    previewHeightPx: Float,
    cropCircleSizePx: Float,
    cropScale: Float,
    cropOffsetX: Float,
    cropOffsetY: Float,
    rotationQuarterTurns: Int,
) {
    val language = uiState.personalizationSettings.language
    if (denyAvatarEditIfInvited()) return
    if (avatarMutationJob != null) return
    val attempt = authSessionCoordinator.attempt()
    if (attempt == null || !uiState.isAuthenticated) {
        uiState = uiState.copy(
            avatarUploadMessage = tr(language, "Сначала войдите в аккаунт", "Please sign in first"),
        )
        return
    }

    launchAvatarMutation(
        attempt = attempt,
        language = language,
        progressMessage = tr(language, "Загрузка аватарки…", "Uploading avatar…"),
        successMessage = tr(language, "Аватарка обновлена", "Avatar updated"),
    ) { token ->
        profileAvatarCoordinator.uploadCroppedAvatar(
            token = token,
            request = AvatarCropRequest(
                sourceUri = sourceUri,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                cropCircleSizePx = cropCircleSizePx,
                cropScale = cropScale,
                cropOffsetX = cropOffsetX,
                cropOffsetY = cropOffsetY,
                rotationQuarterTurns = rotationQuarterTurns,
            ),
        )
    }
}

private fun AppUiRuntime.launchAvatarMutation(
    attempt: AuthSessionAttempt,
    language: AppLanguage,
    progressMessage: String,
    successMessage: String,
    mutate: suspend (String) -> String?,
) {
    cancelBackendRefresh()
    uiState = uiState.copy(
        isRefreshingData = false,
        isUploadingAvatar = true,
        avatarUploadMessage = progressMessage,
    )
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val owner = currentCoroutineContext()[Job] ?: error("Avatar mutation coroutine has no Job")
        try {
            val avatarUri = authSessionCoordinator.run(attempt, mutate)
            if (avatarMutationJob !== owner || !owner.isActive || !authSessionCoordinator.isCurrent(attempt)) {
                return@launch
            }
            repository.updateSettings { latest ->
                latest.copy(userProfile = latest.userProfile.copy(avatarUri = avatarUri))
            }
            applyAndPersist(
                uiState.copy(
                    userProfile = uiState.userProfile.copy(avatarUri = avatarUri),
                    avatarUploadMessage = successMessage,
                    pendingAvatarCropUri = null,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (avatarMutationJob === owner && owner.isActive && authSessionCoordinator.isCurrent(attempt)) {
                uiState = uiState.copy(
                    avatarUploadMessage = profileAvatarCoordinator.readableAvatarError(language, error),
                )
            }
        } finally {
            if (avatarMutationJob === owner) {
                avatarMutationJob = null
                uiState = uiState.copy(isUploadingAvatar = false)
                if (authSessionCoordinator.isCurrent(attempt)) {
                    launchBackendRefresh(BackendRefreshTrigger.Initial)
                }
            }
        }
    }
    avatarMutationJob = job
    job.start()
}

internal fun AppUiRuntime.deleteAvatar() {
    val language = uiState.personalizationSettings.language
    if (denyAvatarEditIfInvited()) return
    if (avatarMutationJob != null) return
    val attempt = authSessionCoordinator.attempt()
    if (attempt == null || !uiState.isAuthenticated) {
        uiState = uiState.copy(
            avatarUploadMessage = tr(language, "Сначала войдите в аккаунт", "Please sign in first"),
        )
        return
    }
    launchAvatarMutation(
        attempt = attempt,
        language = language,
        progressMessage = tr(language, "Удаление аватарки…", "Removing avatar…"),
        successMessage = tr(language, "Аватарка удалена", "Avatar removed"),
    ) { token ->
        profileAvatarCoordinator.deleteAvatar(token)
        null
    }
}

internal fun AppUiRuntime.denyAvatarEditIfInvited(): Boolean {
    if (!isCurrentDeviceInvited()) return false
    uiState = uiState.copy(
        dialog = AppDialog.AccessDenied,
        inlineMessage = null,
        pendingAvatarCropUri = null,
        isUploadingAvatar = false,
    )
    return true
}
