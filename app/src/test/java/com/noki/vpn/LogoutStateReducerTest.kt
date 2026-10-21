package com.noki.vpn

import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutStateReducerTest {
    @Test
    fun logoutFromPendingCredentialsClearsPasswordsAndDoesNotReenterRegistration() {
        val pending = AppUiState(
            isAuthenticated = true,
            userProfile = UserProfile(credentialsSetupRequired = true),
            screenStack = listOf(AppDestination.REGISTRATION),
            authStep = AuthStep.REGISTRATION_PROFILE,
            registrationForm = RegistrationFormState(password = "secret123", passwordRepeat = "secret123"),
            profile = VlessProfile(uuid = "primary-secret"),
        )

        val loggedOut = AuthStepReducer.requireCredentialsSetup(LogoutStateReducer.reduce(pending), pending)

        assertFalse(loggedOut.isAuthenticated)
        assertFalse(loggedOut.userProfile.credentialsSetupRequired)
        assertEquals(listOf(AppDestination.LOGIN), loggedOut.screenStack)
        assertEquals(RegistrationFormState(), loggedOut.registrationForm)
        assertEquals("", loggedOut.profile.uuid)
    }

    @Test
    fun logoutClearsAuthenticatedOverlayDiscovery() {
        val result = LogoutStateReducer.reduce(AppUiState(isAuthenticated = true,
            ruForeignNodes = listOf(RuForeignNode("overlay", "NL overlay", "NL"))))
        assertEquals(emptyList<RuForeignNode>(), result.ruForeignNodes)
    }
    @Test
    fun logoutClearsTelegramFailureAndPendingLink() {
        val telegramStates = listOf(
            TelegramLoginState.Error("Telegram did not finish"),
            TelegramLoginState.LaunchingSdk(TelegramAuthPurpose.LINK),
            TelegramLoginState.Exchanging(TelegramAuthPurpose.LINK),
            TelegramLoginState.Authenticated,
        )
        telegramStates.forEach { telegramState ->
            val result = LogoutStateReducer.reduce(
                AppUiState(isAuthenticated = true, telegramLoginState = telegramState),
            )

            assertEquals(TelegramLoginState.Idle, result.telegramLoginState)
        }
    }

    @Test
    fun logoutClearsInFlightAccountDeletionDialog() {
        val current = AppUiState(
            isAuthenticated = true,
            dialog = AppDialog.DeleteAccount(isDeleting = true),
        )

        val result = LogoutStateReducer.reduce(current)

        assertNull(result.dialog)
    }

    @Test
    fun logoutDoesNotPrefillOwnerEmail() {
        val current = AppUiState(
            isAuthenticated = true,
            userProfile = UserProfile(email = "owner@example.com", hasRealEmail = true),
        )

        val result = LogoutStateReducer.reduce(current)

        assertEquals("", result.loginForm.email)
    }

    @Test
    fun logoutClearsAndroidUpdateOperationAndMetadata() {
        val current = AppUiState(
            isAuthenticated = true,
            isAndroidUpdateAvailable = true,
            hasUnreadAppNotifications = true,
            androidUpdate = AndroidUpdateUiState(
                isRequired = true,
                isChecking = true,
                isDownloading = true,
                currentVersionName = "0.9.172",
                update = AndroidUpdateInfo(
                    versionCode = 173,
                    versionName = "0.9.173",
                    architecture = "arm64-v8a",
                    apkUrl = "https://example.invalid/noki.apk",
                ),
                error = "stale error",
            ),
        )

        val result = LogoutStateReducer.reduce(current)

        assertFalse(result.isAndroidUpdateAvailable)
        assertFalse(result.hasUnreadAppNotifications)
        assertFalse(result.androidUpdate.isChecking)
        assertFalse(result.androidUpdate.isDownloading)
        assertTrue(result.androidUpdate.requiresUpdate)
        assertEquals("0.9.172", result.androidUpdate.currentVersionName)
        assertNull(result.androidUpdate.update)
        assertNull(result.androidUpdate.error)
    }

    @Test
    fun logoutClearsAvatarTransientState() {
        val result = LogoutStateReducer.reduce(
            AppUiState(
                isAuthenticated = true,
                isUploadingAvatar = true,
                avatarUploadMessage = "Uploading",
                pendingAvatarCropUri = "content://picker/avatar",
            ),
        )

        assertFalse(result.isUploadingAvatar)
        assertNull(result.avatarUploadMessage)
        assertNull(result.pendingAvatarCropUri)
    }

    @Test
    fun logoutClearsInviteAndIncySessionState() {
        val result = LogoutStateReducer.reduce(
            AppUiState(
                isAuthenticated = true,
                inviteDeviceForm = InviteDeviceFormState(
                    inviteCode = "ABC123",
                    generatedInviteCode = "SECRET",
                    isLoading = true,
                ),
                incyDevices = IncyDevicesUiState(
                    nameInput = "User A device",
                    selectedDeviceId = "incy-a",
                    isManageDialogVisible = true,
                    isLoading = true,
                ),
            ),
        )

        assertEquals(InviteDeviceFormState(), result.inviteDeviceForm)
        assertEquals(IncyDevicesUiState(), result.incyDevices)
    }

    @Test
    fun logoutClearsDiagnosticLogUploadState() {
        val result = LogoutStateReducer.reduce(
            AppUiState(
                isAuthenticated = true,
                isUploadingLogs = true,
                logUploadMessage = "Sending logs",
            ),
        )

        assertFalse(result.isUploadingLogs)
        assertNull(result.logUploadMessage)
    }
}
