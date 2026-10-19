package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AccountSecurityStateReducerTest {
    @Test
    fun `email password and username validation follows backend contract`() {
        assertEquals(
            "Введите корректный e-mail",
            AccountSecurityStateReducer.validateEmail("bad", AppLanguage.RU),
        )
        assertEquals(
            "Пароли не совпадают",
            AccountSecurityStateReducer.validatePassword(
                newPassword = "new-password",
                confirmation = "different",
                language = AppLanguage.RU,
            ),
        )
        assertEquals(
            "Используйте латиницу, цифры, _, - или .",
            AccountSecurityStateReducer.validateUsername("имя", AppLanguage.RU),
        )
        assertNull(AccountSecurityStateReducer.validateUsername("new_name", AppLanguage.RU))
    }

    @Test
    fun `password action owns only direct set password fields`() {
        val state = AccountSecurityStateReducer.password(AccountSecurityUiState())

        assertEquals(AccountSecurityActionState.Password(), state.action)
    }

    @Test
    fun `back closes password and returns email from code step`() {
        val password = AccountSecurityUiState(
            action = AccountSecurityActionState.Password(newPassword = "new-password"),
        )
        val email = AccountSecurityUiState(
            action = AccountSecurityActionState.Email(
                email = "next@example.com",
                codeSent = true,
                verificationCode = "123456",
            ),
        )

        assertNull(AccountSecurityStateReducer.back(password).action)
        val emailBack = AccountSecurityStateReducer.back(email).action as AccountSecurityActionState.Email
        assertFalse(emailBack.codeSent)
        assertEquals("", emailBack.verificationCode)
    }

    @Test
    fun `dismiss removes transient action`() {
        val current = AccountSecurityUiState(
            action = AccountSecurityActionState.Username(username = "user1"),
        )

        assertEquals(AccountSecurityUiState(), AccountSecurityStateReducer.dismiss(current))
    }
}
