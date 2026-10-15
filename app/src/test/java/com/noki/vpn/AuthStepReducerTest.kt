package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.UserProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStepReducerTest {
    @Test
    fun pendingCredentialsRouteRestoredAccountAndNavigationToExistingProfileStep() {
        val restored = AppUiState(
            isAuthenticated = true,
            userProfile = UserProfile(username = "telegram_user", credentialsSetupRequired = true),
            telegramLoginState = TelegramLoginState.Error("Telegram did not complete sign-in", TelegramAuthPurpose.LINK),
        )
        for (destination in AppDestination.entries) {
            val gated = AuthStepReducer.requireCredentialsSetup(restored.copy(screenStack = listOf(destination)))

            assertEquals(listOf(AppDestination.REGISTRATION), gated.screenStack)
            assertEquals(AuthStep.REGISTRATION_PROFILE, gated.authStep)
            assertEquals("telegram_user", gated.registrationForm.username)
            assertTrue(gated.isAuthenticated)
            assertEquals(restored.telegramLoginState, gated.telegramLoginState)
        }
    }

    @Test
    fun pendingCredentialsRetainPasswordStepAndFormWhenNavigationIsAttempted() {
        val form = RegistrationFormState(username = "chosen_name", password = "secret123", passwordRepeat = "secret123")
        val pending = AppUiState(
            isAuthenticated = true,
            userProfile = UserProfile(credentialsSetupRequired = true),
            authStep = AuthStep.REGISTRATION_PASSWORD,
            screenStack = listOf(AppDestination.REGISTRATION, AppDestination.HOME),
            registrationForm = form,
        )

        val gated = AuthStepReducer.requireCredentialsSetup(pending)
        val profile = AuthStepReducer.previousRegistrationStep(gated)

        assertEquals(listOf(AppDestination.REGISTRATION), gated.screenStack)
        assertEquals(AuthStep.REGISTRATION_PASSWORD, gated.authStep)
        assertEquals(form, gated.registrationForm)
        assertEquals(AuthStep.REGISTRATION_PROFILE, profile.authStep)
    }

    @Test
    fun completedCredentialsClearSecretsAndOpenHomeWhileOrdinarySessionsKeepTheirRoute() {
        val pending = AppUiState(
            isAuthenticated = true,
            userProfile = UserProfile(credentialsSetupRequired = true),
            screenStack = listOf(AppDestination.REGISTRATION),
            authStep = AuthStep.REGISTRATION_PASSWORD,
            registrationForm = RegistrationFormState(password = "secret123", passwordRepeat = "secret123", isLoading = true),
        )
        val completed = AuthStepReducer.requireCredentialsSetup(
            pending.copy(userProfile = pending.userProfile.copy(credentialsSetupRequired = false, hasPassword = true)),
            pending,
        )
        val ordinary = completed.copy(screenStack = listOf(AppDestination.HOME, AppDestination.SECURITY))
        val loggedOut = pending.copy(isAuthenticated = false, screenStack = listOf(AppDestination.LOGIN))

        assertEquals(listOf(AppDestination.HOME), completed.screenStack)
        assertEquals(RegistrationFormState(), completed.registrationForm)
        assertEquals(AuthStep.WELCOME, completed.authStep)
        assertEquals(ordinary, AuthStepReducer.requireCredentialsSetup(ordinary))
        assertEquals(loggedOut, AuthStepReducer.requireCredentialsSetup(loggedOut, pending))
    }

    @Test
    fun defaultAuthStepIsWelcomeSoCodeLoginCanStayOnFirstScreen() {
        assertEquals(AuthStep.WELCOME, AppUiState().authStep)
    }

    @Test
    fun welcomeCanOpenEmailLoginWithoutLosingTypedInviteCode() {
        val state = AppUiState(inviteDeviceForm = InviteDeviceFormState(inviteCode = "ABC123"))

        val updated = AuthStepReducer.showEmailLogin(state)

        assertEquals(AuthStep.EMAIL_LOGIN, updated.authStep)
        assertEquals("ABC123", updated.inviteDeviceForm.inviteCode)
        assertEquals(null, updated.inlineMessage)
    }

    @Test
    fun authStepsPreserveEmailTypedInTheCurrentForm() {
        val email = "same-as-profile@example.com"
        val state = AppUiState(
            currentDeviceAccessRole = "invited",
            userProfile = UserProfile(email = email, hasRealEmail = true),
            loginForm = LoginFormState(email = email),
            registrationForm = RegistrationFormState(email = email),
        )

        assertEquals(email, AuthStepReducer.showEmailLogin(state).loginForm.email)
        assertEquals(email, AuthStepReducer.startRegistration(state).registrationForm.email)
    }

    @Test
    fun newRecoveryAndAccountEmailFlowsDoNotCopyEmailFromOtherState() {
        val recoveryActions = File("src/main/java/com/noki/vpn/PasswordRecoveryUiActions.kt").readText()
        val accountActions = File("src/main/java/com/noki/vpn/AccountSecurityUiActions.kt").readText()

        assertTrue(recoveryActions.contains("passwordRecoveryForm = PasswordRecoveryFormState()"))
        assertFalse(recoveryActions.contains("ownerAuthEmailPrefill"))
        assertFalse(accountActions.contains("val email = uiState.userProfile.email"))
        assertFalse(accountActions.contains("PasswordRecoveryFormState(email = email)"))
    }

    @Test
    fun registrationEmailStepRequiresValidEmailThenMovesToCode() {
        val invalid = AppUiState(
            authStep = AuthStep.REGISTRATION_EMAIL,
            registrationForm = RegistrationFormState(email = "bad"),
        )

        val invalidTransition = AuthStepReducer.nextRegistrationStep(
            current = invalid,
            language = AppLanguage.EN,
            isValidEmail = { false },
        )

        val invalidUpdate = invalidTransition as AuthStepReducer.RegistrationStepTransition.UpdateState
        assertEquals(AuthStep.REGISTRATION_EMAIL, invalidUpdate.state.authStep)
        assertEquals("Enter a valid email", invalidUpdate.state.registrationForm.error)

        val valid = invalid.copy(registrationForm = invalid.registrationForm.copy(email = "user@example.com"))
        val validTransition = AuthStepReducer.nextRegistrationStep(
            current = valid,
            language = AppLanguage.EN,
            isValidEmail = { true },
        )

        val validUpdate = validTransition as AuthStepReducer.RegistrationStepTransition.UpdateState
        assertEquals(AuthStep.REGISTRATION_CODE, validUpdate.state.authStep)
        assertEquals(null, validUpdate.state.registrationForm.error)
    }

    @Test
    fun registrationValidationUsesRussianTextWhenLanguageIsRussian() {
        val state = AppUiState(
            authStep = AuthStep.REGISTRATION_EMAIL,
            registrationForm = RegistrationFormState(email = "bad"),
        )

        val transition = AuthStepReducer.nextRegistrationStep(
            current = state,
            language = AppLanguage.RU,
            isValidEmail = { false },
        )

        val update = transition as AuthStepReducer.RegistrationStepTransition.UpdateState
        assertEquals("Введите корректный e-mail", update.state.registrationForm.error)
    }

    @Test
    fun registrationStepsEndWithSubmitOnlyAfterPasswordStepIsValid() {
        val state = AppUiState(
            authStep = AuthStep.REGISTRATION_PASSWORD,
            registrationForm = RegistrationFormState(
                username = "ykino",
                email = "user@example.com",
                verificationCode = "1234",
                password = "00000000",
                passwordRepeat = "00000000",
            ),
        )

        val transition = AuthStepReducer.nextRegistrationStep(
            current = state,
            language = AppLanguage.EN,
            isValidEmail = { true },
        )

        val submit = transition as AuthStepReducer.RegistrationStepTransition.Submit
        assertEquals(state.registrationForm, submit.form)
        assertTrue(submit.state.registrationForm.isLoading)
        assertEquals(AuthStep.REGISTRATION_PASSWORD, submit.state.authStep)
    }

    @Test
    fun registrationBackMovesOneStepAtATime() {
        val state = AppUiState(authStep = AuthStep.REGISTRATION_PASSWORD)

        val profile = AuthStepReducer.previousRegistrationStep(state)
        val code = AuthStepReducer.previousRegistrationStep(profile)
        val email = AuthStepReducer.previousRegistrationStep(code)

        assertEquals(AuthStep.REGISTRATION_PROFILE, profile.authStep)
        assertEquals(AuthStep.REGISTRATION_CODE, code.authStep)
        assertEquals(AuthStep.REGISTRATION_EMAIL, email.authStep)
    }

    @Test
    fun registrationBackClearsCancelledResendState() {
        val state = AppUiState(
            authStep = AuthStep.REGISTRATION_CODE,
            registrationForm = RegistrationFormState(
                email = "user@example.com",
                isCodeSending = true,
                codeCooldownSeconds = 30,
            ),
        )

        val email = AuthStepReducer.previousRegistrationStep(state)

        assertEquals(AuthStep.REGISTRATION_EMAIL, email.authStep)
        assertFalse(email.registrationForm.isCodeSending)
        assertEquals(30, email.registrationForm.codeCooldownSeconds)
    }

    @Test
    fun leavingRegistrationClearsAbandonedCodeRequestState() {
        val sending = AppUiState(
            authStep = AuthStep.REGISTRATION_EMAIL,
            registrationForm = RegistrationFormState(
                email = "user@example.com",
                verificationCode = "123456",
                codeSent = true,
                isCodeSending = true,
                codeCooldownSeconds = 30,
                isLoading = true,
            ),
        )

        val reopened = AuthStepReducer.startRegistration(AuthStepReducer.showWelcome(sending))

        assertFalse(reopened.registrationForm.isLoading)
        assertFalse(reopened.registrationForm.isCodeSending)
        assertFalse(reopened.registrationForm.codeSent)
        assertEquals(0, reopened.registrationForm.codeCooldownSeconds)
        assertEquals("", reopened.registrationForm.verificationCode)
    }
}
