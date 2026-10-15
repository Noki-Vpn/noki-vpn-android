package com.noki.vpn

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAuthPurposeStateTest {
    @Test
    fun `new attempt and invalidation reject stale async results`() {
        val handle = SavedStateHandle()
        val state = TelegramAuthPurposeState(handle)

        val first = state.begin(TelegramAuthPurpose.LOGIN)
        val second = state.begin(TelegramAuthPurpose.LINK)

        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
        assertEquals(TelegramAuthPurpose.LINK, state.purpose)

        state.invalidate()

        assertFalse(state.isCurrent(second))
        assertNull(state.currentAttemptId)
        assertNull(state.purpose)
    }

    @Test
    fun `restored Telegram purpose cannot keep an abandoned attempt active`() {
        for (purpose in listOf(TelegramAuthPurpose.LOGIN, TelegramAuthPurpose.LINK)) {
            val handle = SavedStateHandle()
            val previous = TelegramAuthPurposeState(handle)
            val previousAttempt = previous.begin(purpose)

            val restored = TelegramAuthPurposeState(handle)

            assertEquals(purpose, restored.interruptedPurpose)
            assertNull(restored.purpose)
            assertNull(restored.currentAttemptId)
            assertFalse(restored.isCurrent(previousAttempt))
            val currentAttempt = restored.begin(purpose)
            assertFalse(restored.isCurrent(previousAttempt))
            assertTrue(restored.isCurrent(currentAttempt))
        }
    }

    @Test
    fun `new attempt with the same purpose invalidates earlier work`() {
        val state = TelegramAuthPurposeState(SavedStateHandle())

        val first = state.begin(TelegramAuthPurpose.LOGIN)
        val second = state.begin(TelegramAuthPurpose.LOGIN)

        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
    }
}
