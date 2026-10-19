package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.AuthFlowApi
import com.noki.vpn.data.BackendAuthTokens
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.BackendException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthFlowCoordinatorTest {
    @Test
    fun validateLoginRequiresIdentifierAndPassword() {
        assertEquals(
            "Enter your email or username",
            AuthStepReducer.validateLogin(LoginFormState(email = "", password = "pass"), AppLanguage.EN),
        )
        assertEquals(
            "Введите пароль",
            AuthStepReducer.validateLogin(LoginFormState(email = "user1", password = ""), AppLanguage.RU),
        )
    }

    @Test
    fun loginBuildsAuthenticatedStateFromBackendTokenDeviceAndBootstrap() {
        val api = FakeAuthApi(
            loginTokens = BackendAuthTokens(
                accessToken = "token-1",
                refreshToken = "refresh-1",
                expiresInSeconds = 3600,
                refreshExpiresAt = "2026-07-16T00:00:00Z",
            ),
        )
        val coordinator = AuthFlowCoordinator(
            authApi = api,
            registerCurrentDevice = { token ->
                assertEquals("token-1", token)
                backendDevice()
            },
            bindTokensToRegisteredDevice = { tokens, device ->
                assertEquals("refresh-1", tokens.refreshToken)
                assertEquals("device-1", device.id)
                tokens.copy(accessToken = "token-1-bound", refreshToken = "refresh-1-bound")
            },
            syncBootstrap = { token, baseState ->
                assertEquals("token-1-bound", token)
                baseState.copy(userProfile = baseState.userProfile.copy(username = "user1"))
            },
        )

        val result = runBlocking {
            coordinator.login(
                form = LoginFormState(email = " user1 ", password = "example1"),
                baseState = AppUiState(),
                deviceId = "device-known",
            )
        }

        assertEquals("token-1-bound", result.tokens.accessToken)
        assertEquals("refresh-1-bound", result.tokens.refreshToken)
        assertEquals(listOf(AppDestination.HOME), result.state.screenStack)
        assertEquals("", result.state.loginForm.password)
        assertEquals("user1", result.state.userProfile.username)
        assertNull(result.state.inlineMessage)
        assertEquals("user1", api.loginEmail)
        assertEquals("device-known", api.loginDeviceId)
    }

    @Test
    fun registrationCreatesAccountThenBuildsAuthenticatedState() {
        val api = FakeAuthApi(
            loginTokens = BackendAuthTokens(
                accessToken = "token-2",
                refreshToken = "refresh-2",
                expiresInSeconds = 3600,
                refreshExpiresAt = "2026-07-16T00:00:00Z",
            ),
        )
        val coordinator = AuthFlowCoordinator(
            authApi = api,
            registerCurrentDevice = { backendDevice() },
            bindTokensToRegisteredDevice = { tokens, device ->
                assertEquals("refresh-2", tokens.refreshToken)
                assertEquals("device-1", device.id)
                tokens.copy(accessToken = "token-2-bound", refreshToken = "refresh-2-bound")
            },
            syncBootstrap = { _, baseState -> baseState },
        )

        val result = runBlocking {
            coordinator.register(
                form = RegistrationFormState(
                    username = " user1 ",
                    email = " user1@example.com ",
                    verificationCode = " 1234 ",
                    password = "example1",
                    passwordRepeat = "example1",
                ),
                baseState = AppUiState(),
                deviceId = "device-known",
            )
        }

        assertEquals("token-2-bound", result.tokens.accessToken)
        assertEquals("refresh-2-bound", result.tokens.refreshToken)
        assertEquals("user1", api.registerUsername)
        assertEquals("user1@example.com", api.registerEmail)
        assertEquals("1234", api.registerCode)
        assertEquals("device-known", api.loginDeviceId)
        assertEquals("", result.state.registrationForm.password)
        assertNull(result.state.inlineMessage)
    }

    @Test
    fun `restricted device id retries login once without device id`() {
        val api = FakeAuthApi(
            loginTokens = BackendAuthTokens(
                accessToken = "token",
                refreshToken = "refresh",
                expiresInSeconds = 3600,
            ),
            rejectDeviceStatus = 403,
        )
        val coordinator = AuthFlowCoordinator(
            authApi = api,
            registerCurrentDevice = { backendDevice() },
            syncBootstrap = { _, state -> state },
        )

        runBlocking {
            coordinator.login(
                form = LoginFormState(email = "user@example.com", password = "password"),
                baseState = AppUiState(),
                deviceId = "restricted-device",
            )
        }

        assertEquals(listOf("restricted-device", null), api.loginDeviceIds)
    }

    private class FakeAuthApi(
        private val loginTokens: BackendAuthTokens,
        private val rejectDeviceStatus: Int? = null,
    ) : AuthFlowApi {
        var loginEmail: String? = null
        var registerUsername: String? = null
        var registerEmail: String? = null
        var registerCode: String? = null
        var loginDeviceId: String? = null
        val loginDeviceIds = mutableListOf<String?>()

        override suspend fun login(email: String, password: String, deviceId: String?): BackendAuthTokens {
            loginEmail = email
            loginDeviceId = deviceId
            loginDeviceIds += deviceId
            if (deviceId != null && rejectDeviceStatus != null && loginDeviceIds.size == 1) {
                throw BackendException("device rejected", rejectDeviceStatus)
            }
            return loginTokens
        }

        override suspend fun register(
            username: String,
            email: String,
            password: String,
            verificationCode: String,
        ) {
            registerUsername = username
            registerEmail = email
            registerCode = verificationCode
        }
    }

    private fun backendDevice(): BackendDevice =
        BackendDevice(
            id = "device-1",
            deviceKey = "device-key",
            deviceName = "Phone",
            platform = "android",
            accessRole = "owner",
            isActive = true,
            lastSeenAt = null,
        )
}
