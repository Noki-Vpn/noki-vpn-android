package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationStateReducerTest {
    @Test
    fun completedCredentialsConflictRequestsRefreshWhileUsernameConflictKeepsItsValidationMessage() {
        assertEquals(
            "Registration is already complete. Refreshing your account",
            AppErrorMapper.readableRegistrationError(AppLanguage.EN, BackendException("Credentials setup is not required", 409)),
        )
        assertEquals(
            "This username is taken, try another",
            AppErrorMapper.readableRegistrationError(AppLanguage.EN, BackendException("Username already exists", 409)),
        )
        assertEquals(
            "Registration could not be completed: conflicting data. Please try again",
            AppErrorMapper.readableRegistrationError(AppLanguage.EN, BackendException("Device registration conflict", 409)),
        )
    }

    @Test
    fun pendingCredentialsUseExistingPasswordSubmissionWithoutEmailOrCode() {
        val pending = AppUiState(
            isAuthenticated = true,
            userProfile = UserProfile(credentialsSetupRequired = true),
            authStep = AuthStep.REGISTRATION_PASSWORD,
            registrationForm = validForm().copy(email = "", verificationCode = ""),
        )

        val transition = AuthStepReducer.nextRegistrationStep(pending, AppLanguage.EN) {
            error("Credentials setup must not validate an email")
        } as AuthStepReducer.RegistrationStepTransition.Submit

        assertEquals(pending.registrationForm, transition.form)
        assertTrue(transition.state.registrationForm.isLoading)
        val ordinary = pending.copy(userProfile = pending.userProfile.copy(credentialsSetupRequired = false))
        val rejected = RegistrationStateReducer.submit(ordinary, AppLanguage.EN) { false }
            as RegistrationStateReducer.SubmitTransition.UpdateState
        assertEquals("Enter your email", rejected.state.registrationForm.error)
    }

    @Test
    fun pendingCredentialsKeepUsernameLengthAndPasswordConfirmationValidation() {
        val pending = AppUiState(
            isAuthenticated = true,
            userProfile = UserProfile(credentialsSetupRequired = true),
            registrationForm = validForm().copy(email = "", verificationCode = ""),
        )
        val cases = listOf(
            pending.registrationForm.copy(username = "ab") to "Username must be at least 3 characters",
            pending.registrationForm.copy(password = "short") to "Password must be at least 8 characters",
            pending.registrationForm.copy(passwordRepeat = "different") to "Passwords do not match",
        )

        for ((form, message) in cases) {
            val update = RegistrationStateReducer.submit(pending.copy(registrationForm = form), AppLanguage.EN) { false }
                as RegistrationStateReducer.SubmitTransition.UpdateState
            assertEquals(message, update.state.registrationForm.error)
        }
    }

    @Test
    fun usernameInputRemovesCyrillicAndKeepsSupportedAscii() {
        assertEquals("john_1", RegistrationStateReducer.sanitizeUsernameInput("john_И1"))
    }

    @Test
    fun emailChangeResetsCodeStateAndClearsError() {
        val state = AppUiState(
            registrationForm = RegistrationFormState(
                email = "old@example.com",
                verificationCode = "1234",
                codeSent = true,
                codeCooldownSeconds = 20,
                error = "error",
            ),
        )

        val updated = RegistrationStateReducer.emailChanged(state, "new@example.com")

        assertEquals("new@example.com", updated.registrationForm.email)
        assertEquals("", updated.registrationForm.verificationCode)
        assertEquals(false, updated.registrationForm.codeSent)
        assertEquals(0, updated.registrationForm.codeCooldownSeconds)
        assertEquals(null, updated.registrationForm.error)
    }

    @Test
    fun codeRequestShowsValidationErrorForInvalidEmail() {
        val state = AppUiState(registrationForm = RegistrationFormState(email = "bad"))

        val transition = RegistrationStateReducer.requestCode(
            current = state,
            language = AppLanguage.EN,
            isValidEmail = { false },
        )

        val update = transition as RegistrationStateReducer.CodeRequestTransition.UpdateState
        assertEquals("Enter a valid email", update.state.registrationForm.error)
    }

    @Test
    fun codeRequestStartsSendingForValidEmail() {
        val state = AppUiState(registrationForm = RegistrationFormState(email = " user@example.com "))

        val transition = RegistrationStateReducer.requestCode(
            current = state,
            language = AppLanguage.EN,
            isValidEmail = { true },
        )

        val send = transition as RegistrationStateReducer.CodeRequestTransition.Send
        assertEquals(" user@example.com ", send.form.email)
        assertTrue(send.state.registrationForm.isCodeSending)
        assertEquals(null, send.state.inlineMessage)
    }

    @Test
    fun codeRequestExplainsCooldownOrSendingState() {
        val sending = AppUiState(registrationForm = RegistrationFormState(email = "user@example.com", isCodeSending = true))
        val cooldown = AppUiState(registrationForm = RegistrationFormState(email = "user@example.com", codeCooldownSeconds = 10))

        val sendingUpdate = RegistrationStateReducer.requestCode(sending, AppLanguage.EN) { true }
            as RegistrationStateReducer.CodeRequestTransition.UpdateState
        val cooldownUpdate = RegistrationStateReducer.requestCode(cooldown, AppLanguage.EN) { true }
            as RegistrationStateReducer.CodeRequestTransition.UpdateState

        assertEquals("The code is already being sent", sendingUpdate.state.registrationForm.error)
        assertEquals("The code has already been sent. Try again in 10s", cooldownUpdate.state.registrationForm.error)
    }

    @Test
    fun codeRequestReturnsToExistingCodeWithoutResendingAfterBack() {
        val codeStep = AppUiState(
            authStep = AuthStep.REGISTRATION_CODE,
            registrationForm = RegistrationFormState(
                email = "user@example.com",
                codeSent = true,
                codeCooldownSeconds = 30,
            ),
        )
        val emailStep = AuthStepReducer.previousRegistrationStep(codeStep)

        val transition = RegistrationStateReducer.requestCode(emailStep, AppLanguage.EN) { true }

        val update = transition as RegistrationStateReducer.CodeRequestTransition.UpdateState
        assertEquals(AuthStep.REGISTRATION_CODE, update.state.authStep)
        assertEquals(30, update.state.registrationForm.codeCooldownSeconds)
    }

    @Test
    fun codeRequestSuccessStoresCooldownAndMessage() {
        val state = AppUiState(
            registrationForm = RegistrationFormState(email = "user@example.com", isCodeSending = true),
        )

        val updated = RegistrationStateReducer.codeRequestSent(state, cooldownSeconds = 60, language = AppLanguage.EN)

        assertEquals(false, updated.registrationForm.isCodeSending)
        assertEquals(true, updated.registrationForm.codeSent)
        assertEquals(60, updated.registrationForm.codeCooldownSeconds)
        assertEquals("The code was sent to your email", updated.inlineMessage)
    }

    @Test
    fun submitShowsValidationErrorWhenFormIsInvalid() {
        val state = AppUiState(registrationForm = RegistrationFormState(email = "bad"))

        val transition = RegistrationStateReducer.submit(
            current = state,
            language = AppLanguage.EN,
            isValidEmail = { false },
        )

        val update = transition as RegistrationStateReducer.SubmitTransition.UpdateState
        assertEquals("Choose a username", update.state.registrationForm.error)
    }

    @Test
    fun submitStartsRegistrationForValidForm() {
        val form = validForm()
        val state = AppUiState(registrationForm = form)

        val transition = RegistrationStateReducer.submit(
            current = state,
            language = AppLanguage.EN,
            isValidEmail = { true },
        )

        val register = transition as RegistrationStateReducer.SubmitTransition.Register
        assertEquals(form, register.form)
        assertTrue(register.state.registrationForm.isLoading)
        assertEquals(null, register.state.inlineMessage)
    }

    @Test
    fun submitFailureClearsLoadingAndStoresError() {
        val state = AppUiState(registrationForm = validForm().copy(isLoading = true))

        val updated = RegistrationStateReducer.registrationFailed(state, "Network error")

        assertEquals(false, updated.registrationForm.isLoading)
        assertEquals("Network error", updated.registrationForm.error)
    }

    @Test
    fun usernameConflictAtCompletionReturnsToUsernameStep() {
        val state = AppUiState(
            authStep = AuthStep.REGISTRATION_PASSWORD,
            registrationForm = validForm().copy(isLoading = true),
        )

        val updated = RegistrationStateReducer.registrationFailed(state, "Username is taken", usernameConflict = true)

        assertEquals(AuthStep.REGISTRATION_PROFILE, updated.authStep)
        assertEquals(state.registrationForm.username, updated.registrationForm.username)
        assertEquals("Username is taken", updated.registrationForm.error)
        assertEquals(false, updated.registrationForm.isLoading)
    }

    private fun validForm(): RegistrationFormState =
        RegistrationFormState(
            username = "user1",
            email = "user1@example.com",
            verificationCode = "1234",
            password = "example1",
            passwordRepeat = "example1",
        )
}
