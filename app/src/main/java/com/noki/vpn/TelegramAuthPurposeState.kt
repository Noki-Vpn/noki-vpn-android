package com.noki.vpn

import androidx.lifecycle.SavedStateHandle

internal class TelegramAuthPurposeState(
    private val savedStateHandle: SavedStateHandle,
) {
    var purpose: TelegramAuthPurpose?
        get() = savedStateHandle.get<String>(KEY_PURPOSE)?.let { raw ->
            runCatching { TelegramAuthPurpose.valueOf(raw) }.getOrNull()
        }
        set(value) {
            savedStateHandle[KEY_PURPOSE] = value?.name
        }

    val currentAttemptId: Long?
        get() = purpose?.let { savedStateHandle.get<Long>(KEY_ATTEMPT_ID) ?: 0L }

    // Purpose survives process death, but the gateway's PKCE session does not.
    val interruptedPurpose: TelegramAuthPurpose? = purpose?.also { invalidate() }

    fun begin(purpose: TelegramAuthPurpose): Long {
        val nextAttemptId = nextAttemptId()
        savedStateHandle[KEY_ATTEMPT_ID] = nextAttemptId
        this.purpose = purpose
        return nextAttemptId
    }

    fun isCurrent(attemptId: Long): Boolean =
        purpose != null && currentAttemptId == attemptId

    fun invalidate() {
        savedStateHandle[KEY_ATTEMPT_ID] = nextAttemptId()
        purpose = null
    }

    private fun nextAttemptId(): Long {
        val current = savedStateHandle.get<Long>(KEY_ATTEMPT_ID) ?: 0L
        return if (current == Long.MAX_VALUE) Long.MIN_VALUE else current + 1L
    }

    private companion object {
        const val KEY_PURPOSE = "telegram_auth_purpose"
        const val KEY_ATTEMPT_ID = "telegram_auth_attempt_id"
    }
}
