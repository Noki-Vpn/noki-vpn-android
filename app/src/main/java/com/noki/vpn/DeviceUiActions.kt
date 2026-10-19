package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.AuthRefreshRejectedException
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.AndroidDeviceInfo
import com.noki.vpn.data.BootstrapStateMapper
import com.noki.vpn.data.DeviceActionCoordinator
import com.noki.vpn.data.DeviceIdentity

internal fun AppUiRuntime.updateInviteDeviceCode(value: String) {
    if (uiState.inviteDeviceForm.isLoading || sessionOperationJob != null) return
    uiState = uiState.copy(
        inviteDeviceForm = uiState.inviteDeviceForm.copy(
            inviteCode = InviteCodeFormatter.format(value),
            error = null,
        ),
    )
}

internal fun AppUiRuntime.dismissGeneratedDeviceInvite() {
    if (!cancelReplaceableDeviceOperation()) return
    uiState = uiState.copy(
        inviteDeviceForm = uiState.inviteDeviceForm.copy(
            generatedInviteCode = null,
            generatedInviteExpiresAt = null,
            error = null,
        ),
    )
}

internal fun AppUiRuntime.createDeviceInvite() {
    if (uiState.inviteDeviceForm.isLoading) return
    val language = uiState.personalizationSettings.language
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        onStarted = {
            uiState = uiState.copy(
                inviteDeviceForm = uiState.inviteDeviceForm.copy(isLoading = true, error = null),
            )
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token ->
                backendApi.createDeviceInvite(
                    token = token,
                    currentDeviceId = backendDeviceId.ifBlank { null },
                    currentDeviceKey = backendDeviceKey.ifBlank { null },
                )
            }
        },
        onSuccess = { invite ->
            uiState = uiState.copy(
                inviteDeviceForm = uiState.inviteDeviceForm.copy(
                    generatedInviteCode = invite.inviteCode,
                    generatedInviteExpiresAt = invite.expiresAt,
                    isLoading = false,
                    error = null,
                ),
                inlineMessage = tr(language, "Код приглашения создан", "Invite code created"),
            )
        },
        onFailure = { error ->
            if (error is AuthRefreshRejectedException) {
                logout()
            } else {
                uiState = uiState.copy(
                    inviteDeviceForm = uiState.inviteDeviceForm.copy(
                        isLoading = false,
                        error = AppErrorMapper.readableNetworkError(language, error),
                    ),
                )
            }
        },
    )
}

internal fun AppUiRuntime.acceptDeviceInvite() {
    acceptDeviceInviteCode(uiState.inviteDeviceForm.inviteCode)
}

internal fun AppUiRuntime.acceptScannedInviteCode(rawValue: String) {
    if (
        uiState.inviteDeviceForm.isLoading ||
        sessionOperationJob != null ||
        telegramAuthPurposeState.currentAttemptId != null
    ) {
        return
    }
    val code = InviteCodeFormatter.extract(rawValue)
    val nextStack = if (uiState.currentDestination == AppDestination.INVITE_QR_SCANNER && uiState.screenStack.size > 1) {
        uiState.screenStack.dropLast(1)
    } else {
        uiState.screenStack
    }
    uiState = uiState.copy(
        inviteDeviceForm = uiState.inviteDeviceForm.copy(inviteCode = code),
        screenStack = nextStack,
    )
    acceptDeviceInviteCode(code)
}

internal fun AppUiRuntime.acceptDeviceInviteCode(rawInviteCode: String) {
    val form = uiState.inviteDeviceForm
    if (form.isLoading || sessionOperationJob != null || telegramAuthPurposeState.currentAttemptId != null) return
    val language = uiState.personalizationSettings.language
    val inviteCode = InviteCodeFormatter.format(rawInviteCode)
    if (inviteCode.length < 6) {
        uiState = uiState.copy(
            inviteDeviceForm = form.copy(
                error = tr(language, "Введите код приглашения", "Enter invite code"),
            ),
        )
        return
    }
    val workflowOwner = beginDeviceWorkflow()
    launchSessionOperation(
        onStarted = {
            uiState = uiState.copy(
                inviteDeviceForm = form.copy(inviteCode = inviteCode, isLoading = true, error = null),
            )
        },
        operation = {
            backendApi.acceptDeviceInvite(
                inviteCode = inviteCode,
                deviceKey = backendDeviceKey.ifBlank { null },
                deviceId = backendDeviceId.ifBlank { null },
                deviceName = AndroidDeviceInfo.deviceName(),
                publicKey = DeviceIdentity.publicKeyBase64(),
                deviceClaims = DeviceIdentity.deviceClaims(application),
            )
        },
        isCurrent = {
            deviceWorkflow.accepts(workflowOwner) &&
                !uiState.isAuthenticated &&
                uiState.inviteDeviceForm.inviteCode == inviteCode
        },
        onSuccess = { accepted ->
            vpnCommands.stopAndRevokeTemporary()
            authSessionCoordinator.commit(accepted.tokens)
            backendDeviceId = accepted.device.id
            backendDeviceKey = accepted.device.deviceKey
            backendDeviceAccessRole = accepted.device.accessRole.ifBlank { "invited" }
            syncedDevices = listOf(accepted.device)
            val preparedState = uiState.copy(
                isAuthenticated = true,
                inviteDeviceForm = InviteDeviceFormState(),
                screenStack = listOf(AppDestination.HOME),
                currentDeviceAccessRole = backendDeviceAccessRole,
                inlineMessage = tr(language, "Устройство подключено", "Device connected"),
            )
            persistDeviceSession()
            applyAndPersist(preparedState)
            startAppNotificationPolling()
            syncFcmTokenIfAvailable()
            launchBackendRefresh(BackendRefreshTrigger.Initial)
        },
        onFailure = { error ->
            uiState = uiState.copy(
                inviteDeviceForm = uiState.inviteDeviceForm.copy(
                    isLoading = false,
                    error = AppErrorMapper.readableInviteError(language, error),
                ),
            )
        },
    )
}

internal fun AppUiRuntime.requestRemoveDevice(deviceId: String) {
    uiState = uiState.copy(dialog = AppDialog.RemoveDevice(deviceId))
}

internal fun AppUiRuntime.requestRenameDevice(deviceId: String) {
    uiState = uiState.copy(dialog = AppDialog.RenameDevice(deviceId))
}

internal fun AppUiRuntime.renameDevice(deviceId: String, value: String) {
    val name = value.trim().replace(Regex("\\s+"), " ").take(80)
    if (name.isBlank()) return
    val language = uiState.personalizationSettings.language
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        onStarted = { uiState = uiState.copy(inlineMessage = null) },
        operation = { attempt ->
            deviceActionCoordinator(attempt).renameDevice(
                context = deviceActionContext(attempt.accessToken),
                deviceId = deviceId,
                name = name,
            )
        },
        onSuccess = { result ->
            uiState = uiState.copy(dialog = null)
            applyDeviceListActionResult(
                result = result,
                language = language,
                successMessage = tr(language, "Имя устройства сохранено", "Device name saved"),
                isNameOperation = true,
            )
        },
        onFailure = { error ->
            uiState = uiState.copy(
                inlineMessage = AppErrorMapper.readableDeviceNameError(language, error),
            )
        },
    )
}

internal fun AppUiRuntime.refreshIncyDevices() {
    if (!uiState.isAuthenticated || uiState.incyDevices.isLoading) return
    val launched = launchAuthenticatedDeviceOperation(
        replaceActive = false,
        allowsReplacement = true,
        onStarted = {
            uiState = uiState.copy(incyDevices = uiState.incyDevices.copy(isLoading = true, error = null))
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token -> backendApi.listIncyDevices(token) }
        },
        onSuccess = { devices ->
            uiState = uiState.copy(incyDevices = uiState.incyDevices.copy(devices = devices, isLoading = false))
        },
        onFailure = ::handleIncyDeviceFailure,
    )
    if (!launched && !uiState.incyDevices.isLoading && uiState.incyDevices.error.isNullOrBlank()) {
        val language = uiState.personalizationSettings.language
        uiState = uiState.copy(
            incyDevices = uiState.incyDevices.copy(
                error = tr(
                    language,
                    "Не удалось обновить список. Повторите попытку.",
                    "Couldn't refresh the list. Please try again.",
                ),
            ),
        )
    }
}

internal fun AppUiRuntime.showCreateIncyDevice() {
    if (!cancelReplaceableDeviceOperation()) return
    uiState = uiState.copy(
        incyDevices = uiState.incyDevices.copy(
            nameInput = "",
            importLink = null,
            v2raynSubscriptionUrl = null,
            isCreateDialogVisible = true,
            isManageDialogVisible = false,
            error = null,
        ),
    )
}

internal fun AppUiRuntime.updateIncyDeviceName(value: String) {
    uiState = uiState.copy(incyDevices = uiState.incyDevices.copy(nameInput = value.take(80), error = null))
}

internal fun AppUiRuntime.createIncyDevice() {
    if (uiState.incyDevices.isLoading && !sessionOperationAllowsReplacement) return
    val rawName = uiState.incyDevices.nameInput
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        onStarted = {
            uiState = uiState.copy(incyDevices = uiState.incyDevices.copy(isLoading = true, error = null))
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token ->
                val workflow = IncyDeviceWorkflow(object : IncyDeviceGateway {
                    override suspend fun create(name: String) =
                        backendApi.createIncyDevice(token, name)
                })
                workflow.create(rawName)
            }
        },
        onSuccess = { result ->
            val devices = (uiState.incyDevices.devices.filterNot { it.id == result.device.id } + result.device)
            uiState = uiState.copy(
                incyDevices = uiState.incyDevices.copy(
                    devices = devices,
                    nameInput = result.device.name,
                    selectedDeviceId = result.device.id,
                    importLink = result.importLink,
                    v2raynSubscriptionUrl = result.v2raynSubscriptionUrl,
                    isCreateDialogVisible = false,
                    isManageDialogVisible = true,
                    isLoading = false,
                ),
            )
        },
        onFailure = { error -> handleIncyDeviceFailure(error, isNameOperation = true) },
    )
}

internal fun AppUiRuntime.openIncyDevice(deviceId: String) {
    val device = uiState.incyDevices.devices.firstOrNull { it.id == deviceId } ?: return
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = true,
        targetId = deviceId,
        onStarted = {
            uiState = uiState.copy(
                incyDevices = uiState.incyDevices.copy(
                    selectedDeviceId = deviceId,
                    nameInput = device.name,
                    importLink = null,
                    v2raynSubscriptionUrl = null,
                    isManageDialogVisible = true,
                    isCreateDialogVisible = false,
                    isLoading = true,
                    error = null,
                ),
            )
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token -> backendApi.getIncyImportLink(token, deviceId) }
        },
        onSuccess = { links ->
            uiState = uiState.copy(
                incyDevices = uiState.incyDevices.copy(
                    importLink = links.importLink,
                    v2raynSubscriptionUrl = links.v2raynSubscriptionUrl,
                    isLoading = false,
                ),
            )
        },
        onFailure = ::handleIncyDeviceFailure,
    )
}

internal fun AppUiRuntime.renameIncyDevice() {
    if (uiState.incyDevices.isLoading && !sessionOperationAllowsReplacement) return
    val deviceId = uiState.incyDevices.selectedDeviceId ?: return
    val name = uiState.incyDevices.nameInput.trim()
    if (name.isEmpty()) return
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        targetId = deviceId,
        onStarted = {
            uiState = uiState.copy(incyDevices = uiState.incyDevices.copy(isLoading = true, error = null))
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token -> backendApi.renameIncyDevice(token, deviceId, name) }
        },
        onSuccess = { updated ->
            uiState = uiState.copy(
                incyDevices = uiState.incyDevices.copy(
                    devices = uiState.incyDevices.devices.map { if (it.id == updated.id) updated else it },
                    nameInput = updated.name,
                    isLoading = false,
                ),
            )
        },
        onFailure = { error -> handleIncyDeviceFailure(error, isNameOperation = true) },
    )
}

internal fun AppUiRuntime.reissueIncyDevice() {
    if (uiState.incyDevices.isLoading && !sessionOperationAllowsReplacement) return
    val deviceId = uiState.incyDevices.selectedDeviceId ?: return
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        targetId = deviceId,
        onStarted = {
            uiState = uiState.copy(incyDevices = uiState.incyDevices.copy(isLoading = true, error = null))
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token ->
                backendApi.reissueIncyDevice(token, deviceId) to backendApi.listIncyDevices(token)
            }
        },
        onSuccess = { (links, devices) ->
            uiState = uiState.copy(
                incyDevices = uiState.incyDevices.copy(
                    devices = devices,
                    importLink = links.importLink,
                    v2raynSubscriptionUrl = links.v2raynSubscriptionUrl,
                    isLoading = false,
                ),
            )
        },
        onFailure = ::handleIncyDeviceFailure,
    )
}

internal fun AppUiRuntime.deleteIncyDevice() {
    if (uiState.incyDevices.isLoading && !sessionOperationAllowsReplacement) return
    val deviceId = uiState.incyDevices.selectedDeviceId ?: return
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        targetId = deviceId,
        onStarted = {
            uiState = uiState.copy(incyDevices = uiState.incyDevices.copy(isLoading = true, error = null))
        },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token -> backendApi.deleteIncyDevice(token, deviceId) }
        },
        onSuccess = {
            uiState = uiState.copy(
                incyDevices = uiState.incyDevices.copy(
                    devices = uiState.incyDevices.devices.filterNot { it.id == deviceId },
                    selectedDeviceId = null,
                    importLink = null,
                    v2raynSubscriptionUrl = null,
                    isManageDialogVisible = false,
                    isLoading = false,
                ),
            )
        },
        onFailure = ::handleIncyDeviceFailure,
    )
}

internal fun AppUiRuntime.dismissIncyDeviceDialog() {
    if (!cancelReplaceableDeviceOperation()) return
    uiState = uiState.copy(
        incyDevices = uiState.incyDevices.copy(
            isCreateDialogVisible = false,
            isManageDialogVisible = false,
            selectedDeviceId = null,
            importLink = null,
            v2raynSubscriptionUrl = null,
            isLoading = false,
            error = null,
        ),
    )
}

internal fun <T> AppUiRuntime.launchAuthenticatedDeviceOperation(
    replaceActive: Boolean,
    allowsReplacement: Boolean,
    targetId: String? = null,
    onStarted: () -> Unit,
    operation: suspend (AuthSessionAttempt) -> T,
    onSuccess: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean {
    if (telegramAuthPurposeState.currentAttemptId != null) return false
    val attempt = authSessionCoordinator.attempt()
    if (attempt == null) {
        onFailure(BackendException("auth_required", 401))
        return false
    }
    val existing = sessionOperationJob
    if (existing != null) {
        if (!replaceActive || !sessionOperationAllowsReplacement) return false
        cancelReplaceableDeviceOperation()
    }
    val workflowOwner = beginDeviceWorkflow()
    return launchSessionOperation(
        allowsReplacement = allowsReplacement,
        onStarted = onStarted,
        operation = { operation(attempt) },
        isCurrent = {
            authSessionCoordinator.isCurrent(attempt) &&
                deviceWorkflow.accepts(workflowOwner) &&
                (targetId == null || uiState.incyDevices.selectedDeviceId == targetId)
        },
        onSuccess = onSuccess,
        onFailure = onFailure,
    )
}

internal fun AppUiRuntime.cancelReplaceableDeviceOperation(): Boolean {
    val stale = sessionOperationJob ?: return true
    if (!sessionOperationAllowsReplacement) return false
    sessionOperationJob = null
    sessionOperationAllowsReplacement = false
    deviceWorkflow.invalidate()
    stale.cancel()
    uiState = uiState.copy(
        inviteDeviceForm = uiState.inviteDeviceForm.copy(isLoading = false),
        incyDevices = uiState.incyDevices.copy(isLoading = false),
    )
    return true
}

private fun AppUiRuntime.handleIncyDeviceFailure(error: Throwable, isNameOperation: Boolean = false) {
    if (error is AuthRefreshRejectedException) {
        logout()
        return
    }
    uiState = uiState.copy(
        incyDevices = uiState.incyDevices.copy(
            isLoading = false,
            error = if (isNameOperation) {
                AppErrorMapper.readableDeviceNameError(uiState.personalizationSettings.language, error)
            } else {
                AppErrorMapper.readableNetworkError(uiState.personalizationSettings.language, error)
            },
        ),
    )
}

internal fun AppUiRuntime.setDeviceFullAccess(deviceId: String, fullAccess: Boolean) {
    val language = uiState.personalizationSettings.language
    launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        onStarted = { uiState = uiState.copy(inlineMessage = null) },
        operation = { attempt ->
            deviceActionCoordinator(attempt).setFullAccess(
                context = deviceActionContext(attempt.accessToken),
                deviceId = deviceId,
                fullAccess = fullAccess,
            )
        },
        onSuccess = { result ->
            applyDeviceListActionResult(
                result = result,
                language = language,
                successMessage = tr(
                    language,
                    if (fullAccess) "Устройству выдан полный доступ" else "Полный доступ снят",
                    if (fullAccess) "Full access granted to device" else "Full access removed",
                ),
            )
        },
        onFailure = { error ->
            uiState = uiState.copy(
                inlineMessage = AppErrorMapper.readableNetworkError(language, error),
            )
        },
    )
}

internal fun AppUiRuntime.requestDeviceTrafficPoolChange(deviceId: String, isIncy: Boolean) {
    if (isCurrentDeviceInvited()) {
        showCurrentDeviceAccessDenied()
        return
    }
    if (!cancelReplaceableDeviceOperation()) return
    val pool = if (isIncy) uiState.incyDevices.devices.firstOrNull { it.id == deviceId }?.trafficPool
        else uiState.devices.firstOrNull { it.id == deviceId }?.trafficPool
    if (pool == null) return
    uiState = uiState.copy(dialog = AppDialog.MoveDevice(deviceId,
        if (pool == "shared") "primary" else "shared", isIncy), inlineMessage = null)
}

internal fun AppUiRuntime.confirmDeviceTrafficPoolChange(dialog: AppDialog.MoveDevice) {
    if (dialog.isLoading || uiState.dialog != dialog) return
    if (dialog.isIncy) moveIncyDeviceTrafficPool(dialog) else setDeviceTrafficPool(dialog)
}

private fun AppUiRuntime.setDeviceTrafficPool(dialog: AppDialog.MoveDevice) {
    val language = uiState.personalizationSettings.language
    val launched = launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        onStarted = { uiState = uiState.copy(dialog = dialog.copy(isLoading = true, error = null), inlineMessage = null) },
        operation = { attempt ->
            authSessionCoordinator.run(attempt) { token ->
                backendApi.setDeviceTrafficPool(token, dialog.deviceId, dialog.trafficPool, backendDeviceId.ifBlank { null }, backendDeviceKey.ifBlank { null })
            }
        },
        onSuccess = { devices ->
            uiState = uiState.copy(dialog = null)
            applyDeviceListActionResult(DeviceActionCoordinator.ActionResult.Success(devices), language, tr(language, "Группа устройства изменена", "Device group changed"))
            launchBackendRefresh(BackendRefreshTrigger.Initial)
        },
        onFailure = { error -> handleDeviceTrafficPoolFailure(dialog, error) },
    )
    if (!launched && uiState.dialog == dialog) showDeviceTrafficPoolBusy(dialog)
}

private fun AppUiRuntime.moveIncyDeviceTrafficPool(dialog: AppDialog.MoveDevice) {
    val device = uiState.incyDevices.devices.firstOrNull { it.id == dialog.deviceId } ?: return
    val launched = launchAuthenticatedDeviceOperation(
        replaceActive = true,
        allowsReplacement = false,
        targetId = device.id,
        onStarted = { uiState = uiState.copy(dialog = dialog.copy(isLoading = true, error = null),
            incyDevices = uiState.incyDevices.copy(isLoading = true, error = null)) },
        operation = { attempt -> authSessionCoordinator.run(attempt) { token -> backendApi.setIncyDeviceTrafficPool(token, device.id, dialog.trafficPool) } },
        onSuccess = { updated ->
            uiState = uiState.copy(dialog = null, incyDevices = uiState.incyDevices.copy(
                devices = uiState.incyDevices.devices.map { if (it.id == updated.id) updated else it },
                isLoading = false,
                isManageDialogVisible = false,
                selectedDeviceId = null,
                importLink = null,
                v2raynSubscriptionUrl = null,
            ))
            launchBackendRefresh(BackendRefreshTrigger.Initial)
        },
        onFailure = { error -> handleDeviceTrafficPoolFailure(dialog, error) },
    )
    if (!launched && uiState.dialog == dialog) showDeviceTrafficPoolBusy(dialog)
}

private fun AppUiRuntime.showDeviceTrafficPoolBusy(dialog: AppDialog.MoveDevice) {
    uiState = uiState.copy(dialog = dialog.copy(error = tr(uiState.personalizationSettings.language,
        "Дождитесь завершения текущего действия и повторите попытку", "Wait for the current operation to finish and try again")))
}

private fun AppUiRuntime.handleDeviceTrafficPoolFailure(dialog: AppDialog.MoveDevice, error: Throwable) {
    if (error is AuthRefreshRejectedException) logout()
    else uiState = uiState.copy(
        dialog = dialog.copy(isLoading = false, error = AppErrorMapper.readableNetworkError(uiState.personalizationSettings.language, error)),
        incyDevices = uiState.incyDevices.copy(isLoading = false),
    )
}

internal fun AppUiRuntime.requestLogoutOthers() {
    uiState = uiState.copy(dialog = AppDialog.LogoutOthers)
}

internal fun AppUiRuntime.deviceActionCoordinator(attempt: AuthSessionAttempt? = null): DeviceActionCoordinator {
    return DeviceActionCoordinator(
        api = backendApi,
        refreshContextAfterUnauthorized = refresh@{
            val activeAttempt = attempt ?: return@refresh null
            val retryAttempt = authSessionCoordinator.retryAfterUnauthorized(activeAttempt)
                ?: return@refresh null
            val stored = repository.load()
            backendDeviceId = stored.backendDeviceId
            backendDeviceKey = stored.backendDeviceKey
            backendDeviceAccessRole = stored.backendDeviceAccessRole.ifBlank { "owner" }
            deviceActionContext(retryAttempt.accessToken)
        },
    )
}

internal fun AppUiRuntime.deviceActionContext(token: String): DeviceActionCoordinator.DeviceContext {
    return DeviceActionCoordinator.DeviceContext(
        token = token,
        currentDeviceId = backendDeviceId.ifBlank { null },
        currentDeviceKey = backendDeviceKey.ifBlank { null },
    )
}

internal fun AppUiRuntime.beginDeviceWorkflow(): WorkflowOwner {
    return deviceWorkflow.begin(
        DeviceSessionSnapshot(
            id = backendDeviceId,
            key = backendDeviceKey,
            role = backendDeviceAccessRole,
        ),
    )
}

internal fun AppUiRuntime.applyDeviceListActionResult(
    result: DeviceActionCoordinator.ActionResult<List<BackendDevice>>,
    language: AppLanguage,
    successMessage: String,
    isNameOperation: Boolean = false,
) {
    when (result) {
        is DeviceActionCoordinator.ActionResult.Success -> {
            syncedDevices = result.value
            uiState = uiState.copy(
                devices = BootstrapStateMapper.mapDevices(
                    result.value,
                    language,
                    backendDeviceId,
                    backendDeviceKey,
                ),
                inlineMessage = successMessage,
            )
        }
        is DeviceActionCoordinator.ActionResult.LogoutRequired -> logout()
        is DeviceActionCoordinator.ActionResult.Failure -> {
            uiState = uiState.copy(inlineMessage = if (isNameOperation) {
                AppErrorMapper.readableDeviceNameError(language, result.error)
            } else {
                AppErrorMapper.readableNetworkError(language, result.error)
            })
        }
    }
}

internal fun AppUiRuntime.isDeviceLimitError(error: Throwable): Boolean {
    return AppErrorMapper.isDeviceLimitError(error)
}

internal fun AppUiRuntime.deviceLimitMessage(language: AppLanguage): String {
    return AppErrorMapper.deviceLimitMessage(language)
}

internal suspend fun AppUiRuntime.ensureCurrentDeviceRegistered(
    token: String,
    persistState: Boolean = true,
): BackendDevice {
    suspend fun register(deviceId: String?): BackendDevice {
        return backendApi.registerDevice(
            token = token,
            deviceKey = backendDeviceKey,
            deviceId = deviceId,
            deviceName = AndroidDeviceInfo.deviceName(),
            publicKey = DeviceIdentity.publicKeyBase64(),
            deviceClaims = DeviceIdentity.deviceClaims(application),
            platform = "android",
        )
    }

    val device = try {
        register(backendDeviceId.ifBlank { null })
    } catch (error: Throwable) {
        val shouldRetryWithoutDeviceId =
            backendDeviceId.isNotBlank() &&
                error is BackendException &&
                error.statusCode in setOf(400, 403, 404, 409) &&
                !isDeviceLimitError(error)
        if (!shouldRetryWithoutDeviceId) throw error
        backendDeviceId = ""
        register(null)
    }
    backendDeviceId = device.id
    backendDeviceKey = device.deviceKey
    backendDeviceAccessRole = device.accessRole.ifBlank { "owner" }
    syncedDevices = (syncedDevices.filterNot { it.deviceKey == device.deviceKey } + device)
    if (persistState) {
        persistDeviceSession()
    }
    return device
}

internal fun AppUiRuntime.isCurrentDeviceInvited(): Boolean {
    return backendDeviceAccessRole.equals("invited", ignoreCase = true) ||
        uiState.currentDeviceAccessRole.equals("invited", ignoreCase = true)
}
