package com.noki.vpn

import android.content.SharedPreferences
import com.noki.vpn.data.InstallOnboardingStep
import com.noki.vpn.data.SettingsMarkersStore
import com.noki.vpn.data.UserProfile
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallOnboardingTest {
    @Test
    fun `every installation step waits for successful authentication without hiding errors`() {
        InstallOnboardingStep.entries.forEach { step ->
            listOf(AppDestination.LOGIN, AppDestination.REGISTRATION, AppDestination.PASSWORD_RECOVERY,
                AppDestination.INVITE_DEVICE, AppDestination.SUPPORT).forEach { destination ->
                val state = AppUiState(isReady = true, installOnboardingStep = step,
                    screenStack = listOf(destination), inlineMessage = "auth error",
                    loginForm = LoginFormState(error = "login error"),
                    registrationForm = RegistrationFormState(error = "registration error"))

                assertEquals(state, requireStartupFlow(state))
                assertFalse(showsInstallAppearanceDialog(state))
            }
        }
    }

    @Test
    fun `successful authentication starts at the persisted incomplete step`() {
        listOf(InstallOnboardingStep.CONSENT, InstallOnboardingStep.ROUTING,
            InstallOnboardingStep.APPEARANCE).forEach { step ->
            val login = AppUiState(isReady = true, installOnboardingStep = step,
                screenStack = listOf(AppDestination.LOGIN))
            val state = login.copy(isAuthenticated = true, screenStack = listOf(AppDestination.HOME))

            val gated = requireStartupFlow(state, login)

            assertEquals(step, gated.installOnboardingStep)
            assertEquals(if (step == InstallOnboardingStep.APPEARANCE) AppDestination.HOME
                else AppDestination.ONBOARDING, gated.currentDestination)
        }
    }

    @Test
    fun `routing reuses app selection and keeps unrelated destinations gated`() {
        val selecting = AppUiState(isReady = true, isAuthenticated = true,
            installOnboardingStep = InstallOnboardingStep.ROUTING,
            screenStack = listOf(AppDestination.ONBOARDING, AppDestination.APP_FILTER))

        assertEquals(selecting, requireStartupFlow(selecting))
        assertEquals(listOf(AppDestination.ONBOARDING), requireStartupFlow(selecting.copy(
            screenStack = listOf(AppDestination.SETTINGS),
        )).screenStack)
        assertEquals(listOf(AppDestination.ONBOARDING), requireStartupFlow(selecting.copy(
            installOnboardingStep = InstallOnboardingStep.CONSENT,
        )).screenStack)
    }

    @Test
    fun `Telegram credentials finish before every startup step and survive session restoration`() {
        InstallOnboardingStep.entries.forEach { step ->
            val pending = AppUiState(isReady = true, isAuthenticated = true,
                userProfile = UserProfile(username = "telegram_user", credentialsSetupRequired = true),
                installOnboardingStep = step,
                screenStack = listOf(AppDestination.HOME))

            val restored = requireStartupFlow(pending)
            assertEquals(listOf(AppDestination.REGISTRATION), restored.screenStack)
            assertEquals(AuthStep.REGISTRATION_PROFILE, restored.authStep)
            assertEquals("telegram_user", restored.registrationForm.username)
            assertFalse(showsInstallAppearanceDialog(restored))
            assertEquals(AppDestination.REGISTRATION, requireStartupFlow(restored.copy(
                screenStack = listOf(AppDestination.SETTINGS),
            )).currentDestination)
            val password = restored.copy(authStep = AuthStep.REGISTRATION_PASSWORD,
                registrationForm = restored.registrationForm.copy(password = "password", error = "setup error"))
            assertEquals(password, requireStartupFlow(password, restored))
            val completedCredentials = requireStartupFlow(password.copy(
                userProfile = password.userProfile.copy(credentialsSetupRequired = false),
            ), password)
            assertEquals(step, completedCredentials.installOnboardingStep)
            assertEquals(if (step == InstallOnboardingStep.CONSENT || step == InstallOnboardingStep.ROUTING)
                AppDestination.ONBOARDING else AppDestination.HOME, completedCredentials.currentDestination)
            assertEquals(step == InstallOnboardingStep.APPEARANCE, showsInstallAppearanceDialog(completedCredentials))
        }
    }

    @Test
    fun `losing authentication hides incomplete startup without losing progress`() {
        listOf(InstallOnboardingStep.CONSENT, InstallOnboardingStep.ROUTING,
            InstallOnboardingStep.APPEARANCE).forEach { step ->
            val active = requireStartupFlow(AppUiState(isReady = true, isAuthenticated = true,
                installOnboardingStep = step, screenStack = listOf(AppDestination.HOME)))
            val loggedOut = requireStartupFlow(LogoutStateReducer.reduce(active), active)
            assertEquals(AppDestination.LOGIN, loggedOut.currentDestination)
            assertEquals(step, loggedOut.installOnboardingStep)
            assertFalse(showsInstallAppearanceDialog(loggedOut))
            val stale = requireStartupFlow(active.copy(isAuthenticated = false,
                screenStack = listOf(AppDestination.ONBOARDING, AppDestination.APP_FILTER)), active)
            assertEquals(listOf(AppDestination.LOGIN), stale.screenStack)
            assertEquals(step, stale.installOnboardingStep)
        }
    }

    @Test
    fun `authenticated appearance uses real home until the installation step is complete`() {
        val pending = AppUiState(isReady = true, isAuthenticated = true,
            installOnboardingStep = InstallOnboardingStep.APPEARANCE,
            screenStack = listOf(AppDestination.SETTINGS))
        val home = requireStartupFlow(pending)
        assertEquals(listOf(AppDestination.HOME), home.screenStack)
        assertTrue(showsInstallAppearanceDialog(home))
        assertFalse(showsInstallAppearanceDialog(home.copy(installOnboardingStep = InstallOnboardingStep.COMPLETE)))
        assertEquals(AppDestination.SETTINGS, requireStartupFlow(pending.copy(
            installOnboardingStep = InstallOnboardingStep.COMPLETE,
        )).currentDestination)
    }

    @Test
    fun `completed installation preserves login and normal authenticated navigation`() {
        listOf(AppDestination.LOGIN, AppDestination.HOME, AppDestination.SETTINGS).forEach { destination ->
            val state = AppUiState(isReady = true, isAuthenticated = destination != AppDestination.LOGIN,
                installOnboardingStep = InstallOnboardingStep.COMPLETE, screenStack = listOf(destination))
            assertEquals(state, requireStartupFlow(state))
        }
    }

    @Test
    fun `splash waits for persisted installation progress before gating`() {
        val state = AppUiState(isReady = true, screenStack = listOf(AppDestination.SPLASH),
            isAuthenticated = true, userProfile = UserProfile(credentialsSetupRequired = true))
        assertEquals(state, requireStartupFlow(state))
    }

    @Test
    fun `installation progress restores independently of accounts and routine marker cleanup`() {
        val values = mutableMapOf<String, String>()
        val preferences = preferences(values)
        val store = SettingsMarkersStore(preferences, appVersionCode = { 1L })
        assertEquals(InstallOnboardingStep.CONSENT, store.loadInstallOnboardingStep())

        store.saveInstallOnboardingStep(InstallOnboardingStep.ROUTING)
        values["settings"] = "different-account"
        store.clearVpnRuntimeState()
        store.clearLastRegisteredFcmTokenHash()
        store.clearAndroidUpdateAvailable()
        val restored = SettingsMarkersStore(preferences, appVersionCode = { 2L })
        assertEquals(InstallOnboardingStep.ROUTING, restored.loadInstallOnboardingStep())

        restored.saveInstallOnboardingStep(InstallOnboardingStep.APPEARANCE)
        assertEquals(InstallOnboardingStep.APPEARANCE, store.loadInstallOnboardingStep())
        restored.saveInstallOnboardingStep(InstallOnboardingStep.COMPLETE)
        values.remove("settings")
        assertEquals(InstallOnboardingStep.COMPLETE, store.loadInstallOnboardingStep())
    }

    @Test
    fun `unknown or failed consent marker never becomes accepted`() {
        val values = mutableMapOf("install_onboarding_step" to "unknown")
        val store = SettingsMarkersStore(preferences(values, commitSucceeds = false), appVersionCode = { 1L })
        assertEquals(InstallOnboardingStep.CONSENT, store.loadInstallOnboardingStep())
        assertTrue(runCatching { store.saveInstallOnboardingStep(InstallOnboardingStep.ROUTING) }.isFailure)
        assertEquals(InstallOnboardingStep.CONSENT, store.loadInstallOnboardingStep())
    }

    @Test
    fun `completed mini appearance upgrades once without repeating consent or routing`() {
        val values = mutableMapOf("install_onboarding_step" to "COMPLETE")
        val preferences = preferences(values)
        val store = SettingsMarkersStore(preferences, appVersionCode = { 263L })

        assertEquals(InstallOnboardingStep.APPEARANCE, store.loadInstallOnboardingStep())
        val home = requireStartupFlow(AppUiState(isReady = true, isAuthenticated = true,
            installOnboardingStep = store.loadInstallOnboardingStep(), screenStack = listOf(AppDestination.HOME)))
        assertEquals(AppDestination.HOME, home.currentDestination)
        assertTrue(showsInstallAppearanceDialog(home))

        store.saveInstallOnboardingStep(InstallOnboardingStep.COMPLETE)
        val restored = SettingsMarkersStore(preferences, appVersionCode = { 263L })
        assertEquals(InstallOnboardingStep.COMPLETE, restored.loadInstallOnboardingStep())
        assertFalse(showsInstallAppearanceDialog(home.copy(installOnboardingStep = restored.loadInstallOnboardingStep())))
    }

    private fun preferences(values: MutableMap<String, String>, commitSucceeds: Boolean = true): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, String?>()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { proxy, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "putString" -> { pending[editorArgs!![0] as String] = editorArgs[1] as String?; proxy }
                            "remove" -> { pending[editorArgs!![0] as String] = null; proxy }
                            "commit", "apply" -> {
                                if (commitSucceeds) pending.forEach { (key, value) ->
                                    if (value == null) values.remove(key) else values[key] = value
                                }
                                if (editorMethod.name == "commit") commitSucceeds else null
                            }
                            else -> error(editorMethod.name)
                        }
                    }
                }
                else -> error(method.name)
            }
        } as SharedPreferences
}
