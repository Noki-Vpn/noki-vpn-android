package com.noki.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppInfo
import com.noki.vpn.data.DeviceSession
import com.noki.vpn.data.GlassMode
import com.noki.vpn.data.PersonalizationSettings
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.SecuritySettings
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSyncStateReducerTest {
    @Test
    fun mandatoryUpdateSurvivesLateRefreshAndOrdinaryDialogChanges() {
        val forced = updateInfo(versionCode = 78).copy(isForced = true)
        for (required in listOf(AndroidUpdateUiState(isRequired = true), AndroidUpdateUiState(update = forced))) {
            val latest = AppUiState(androidUpdate = required, dialog = AppDialog.AccessDenied)
            val refreshed = BackendSyncStateReducer.apply(latest, backendPatch(AndroidUpdateUiState()))
            assertTrue(refreshed.copy(dialog = null).androidUpdate.requiresUpdate)
            assertTrue(AndroidUpdateUiState(error = "offline").retainRequirement(refreshed.androidUpdate).requiresUpdate)
        }
        val busy = AppUiState(androidUpdate = AndroidUpdateUiState(isChecking = true))
        val startup = BackendSyncStateReducer.apply(busy, backendPatch(AndroidUpdateUiState(update = forced)))
        assertTrue(startup.androidUpdate.requiresUpdate)
        assertFalse(AndroidUpdateUiState(update = forced.copy(isForced = false)).requiresUpdate)
    }

    @Test
    fun patchAppliesBackendFieldsToLatestUiStateOnly() {
        val latest = AppUiState(
            screenStack = listOf(AppDestination.HOME, AppDestination.PERSONALIZATION),
            profile = VlessProfile(remark = "latest-profile"),
            filterMode = AppFilterMode.ONLY_SELECTED,
            selectedPackages = setOf("com.example.latest"),
            installedApps = listOf(AppInfo("Latest", "com.example.latest")),
            userProfile = UserProfile(username = "latest-user"),
            personalizationSettings = PersonalizationSettings(glassMode = GlassMode.SIMPLE),
            securitySettings = SecuritySettings(biometricEnabled = false),
            advancedSettings = AdvancedSettings(connectionLogsEnabled = false),
            connectionState = VpnConnectionState.CONNECTED,
            connectedAtMillis = 123L,
            connectionReason = "latest-connection",
            inlineMessage = "latest-message",
            loginForm = LoginFormState(email = "latest@example.com"),
            registrationForm = RegistrationFormState(username = "latest-registration"),
            passwordRecoveryForm = PasswordRecoveryFormState(email = "recover@example.com"),
            dialog = AppDialog.AccessDenied,
            alwaysOnInput = "latest-always-on",
            bypassInput = "latest-bypass",
            isUploadingAvatar = true,
            avatarUploadMessage = "latest-avatar-message",
            pendingAvatarCropUri = "content://latest-avatar",
        )
        val backendDevice = DeviceSession(
            id = "backend-device",
            title = "Backend device",
            subtitle = "online",
            isCurrent = true,
            isOnline = true,
        )
        val patch = BackendSyncPatch(
            userProfile = UserProfile(username = "backend-user"),
            devices = listOf(backendDevice),
            locations = emptyList(),
            plans = emptyList(),
            profile = VlessProfile(remark = "backend-profile"),
            currentDeviceAccessRole = "invited",
            androidUpdate = AndroidUpdateUiState(currentVersionName = "0.9.77"),
            ruForeignNodes = listOf(RuForeignNode("overlay", "NL overlay", "NL")),
        )

        val result = BackendSyncStateReducer.apply(latest, patch)

        assertTrue(result.isAuthenticated)
        assertEquals(patch.userProfile, result.userProfile)
        assertEquals(latest.profile, result.profile)
        assertEquals(listOf(backendDevice), result.devices)
        assertEquals(patch.ruForeignNodes, result.ruForeignNodes)
        assertEquals("invited", result.currentDeviceAccessRole)
        assertEquals(latest.screenStack, result.screenStack)
        assertEquals(latest.filterMode, result.filterMode)
        assertEquals(latest.selectedPackages, result.selectedPackages)
        assertEquals(latest.installedApps, result.installedApps)
        assertEquals(latest.personalizationSettings, result.personalizationSettings)
        assertEquals(latest.securitySettings, result.securitySettings)
        assertEquals(latest.advancedSettings, result.advancedSettings)
        assertEquals(latest.connectionState, result.connectionState)
        assertEquals(latest.connectedAtMillis, result.connectedAtMillis)
        assertEquals(latest.connectionReason, result.connectionReason)
        assertEquals(latest.inlineMessage, result.inlineMessage)
        assertEquals(latest.loginForm, result.loginForm)
        assertEquals(latest.registrationForm, result.registrationForm)
        assertEquals(latest.passwordRecoveryForm, result.passwordRecoveryForm)
        assertEquals(latest.dialog, result.dialog)
        assertEquals(latest.alwaysOnInput, result.alwaysOnInput)
        assertEquals(latest.bypassInput, result.bypassInput)
        assertEquals(latest.isUploadingAvatar, result.isUploadingAvatar)
        assertEquals(latest.avatarUploadMessage, result.avatarUploadMessage)
        assertEquals(latest.pendingAvatarCropUri, result.pendingAvatarCropUri)
    }

    @Test
    fun staleBootstrapCannotReplaceTheConnectedRouteIdentityButStillUpdatesAccountFields() {
        val endpoint = VpnEndpointOption(code = "active", nodeId = "active-exit")
        val active = AppUiState(
            connectionState = VpnConnectionState.CONNECTED,
            userProfile = UserProfile(selectedServerCode = "active-exit", actualCountryCode = "NL"),
            profile = VlessProfile(endpointCode = endpoint.code, host = "active.example"),
            endpointOptions = listOf(endpoint),
        )
        val patch = backendPatch(androidUpdate = AndroidUpdateUiState()).copy(
            userProfile = active.userProfile.copy(username = "backend-user", subscriptionStatus = "limited",
                trafficUsedGb = 42.0, selectedServerCode = "old-exit", actualCountryCode = "LV"),
            profile = VlessProfile(endpointCode = "old", host = "old.example"),
        )
        val result = BackendSyncStateReducer.apply(active, patch)
        assertEquals(patch.userProfile.copy(selectedServerCode = "active-exit", actualCountryCode = "NL"), result.userProfile)
        assertEquals(active.profile, result.profile)
        assertEquals(active.endpointOptions, result.endpointOptions)
        val disconnected = BackendSyncStateReducer.apply(active.copy(connectionState = VpnConnectionState.DISCONNECTED), patch)
        assertEquals(patch.userProfile, disconnected.userProfile)
        assertEquals(patch.profile, disconnected.profile)
    }

    @Test
    fun activeUpdateDownloadWinsOverBackendPatch() {
        val activeDownload = AndroidUpdateUiState(
            isDownloading = true,
            currentVersionName = "0.9.76",
            update = updateInfo(versionCode = 77),
        )
        val patch = backendPatch(
            androidUpdate = AndroidUpdateUiState(
                currentVersionName = "0.9.76",
                update = updateInfo(versionCode = 78),
            ),
        )

        val result = BackendSyncStateReducer.apply(
            latest = AppUiState(androidUpdate = activeDownload),
            patch = patch,
        )

        assertEquals(activeDownload, result.androidUpdate)
        assertTrue(result.isAndroidUpdateAvailable)
    }

    @Test
    fun activeUpdateCheckWinsOverBackendPatch() {
        val activeCheck = AndroidUpdateUiState(
            isChecking = true,
            currentVersionName = "0.9.76",
        )
        val patch = backendPatch(
            androidUpdate = AndroidUpdateUiState(
                currentVersionName = "0.9.76",
                update = updateInfo(versionCode = 78),
            ),
        )

        val result = BackendSyncStateReducer.apply(
            latest = AppUiState(androidUpdate = activeCheck),
            patch = patch,
        )

        assertEquals(activeCheck, result.androidUpdate)
        assertFalse(result.isAndroidUpdateAvailable)
    }

    @Test
    fun completedManualUpdateWinsOverBackendRequestThatOverlappedIt() {
        val manualResult = AndroidUpdateUiState(
            currentVersionName = "0.9.76",
            update = updateInfo(versionCode = 79),
        )
        val stalePatch = backendPatch(
            androidUpdate = AndroidUpdateUiState(
                currentVersionName = "0.9.76",
                update = updateInfo(versionCode = 78),
            ),
        )

        val result = BackendSyncStateReducer.apply(
            latest = AppUiState(androidUpdate = manualResult),
            patch = stalePatch,
            preserveAndroidUpdate = true,
        )

        assertEquals(manualResult, result.androidUpdate)
        assertTrue(result.isAndroidUpdateAvailable)
    }

    @Test
    fun ownershipRejectsReversedCompletionAndPreviousAuthEpoch() {
        val tracker = BackendSyncRequestTracker()
        val first = tracker.next(authEpoch = 7)
        val second = tracker.next(authEpoch = 7)

        assertFalse(tracker.isCurrent(first, currentAuthEpoch = 7))
        assertTrue(tracker.isCurrent(second, currentAuthEpoch = 7))
        assertFalse(tracker.isCurrent(second, currentAuthEpoch = 8))

        tracker.invalidate()

        assertFalse(tracker.isCurrent(second, currentAuthEpoch = 7))
    }

    @Test
    fun refreshArbitrationPreventsBackgroundWorkFromCancellingForegroundOrInitialSync() {
        assertFalse(
            BackendRefreshArbitrationPolicy.shouldStart(
                active = BackendRefreshTrigger.Initial,
                requested = BackendRefreshTrigger.Stats,
            ),
        )
        assertFalse(
            BackendRefreshArbitrationPolicy.shouldStart(
                active = BackendRefreshTrigger.UserRefresh,
                requested = BackendRefreshTrigger.Initial,
            ),
        )
        assertTrue(
            BackendRefreshArbitrationPolicy.shouldStart(
                active = BackendRefreshTrigger.Stats,
                requested = BackendRefreshTrigger.Initial,
            ),
        )
        assertTrue(
            BackendRefreshArbitrationPolicy.shouldStart(
                active = BackendRefreshTrigger.Initial,
                requested = BackendRefreshTrigger.UserRefresh,
            ),
        )
    }

    private fun backendPatch(androidUpdate: AndroidUpdateUiState) = BackendSyncPatch(
        userProfile = UserProfile(username = "backend-user"),
        devices = emptyList(),
        locations = emptyList(),
        plans = emptyList(),
        profile = VlessProfile(remark = "backend-profile"),
        currentDeviceAccessRole = "owner",
        androidUpdate = androidUpdate,
    )

    private fun updateInfo(versionCode: Long) = AndroidUpdateInfo(
        versionCode = versionCode,
        versionName = "0.9.$versionCode",
        architecture = "arm64-v8a",
        apkUrl = "https://example.invalid/noki.apk",
    )
}
