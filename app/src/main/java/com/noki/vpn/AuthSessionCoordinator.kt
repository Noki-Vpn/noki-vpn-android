package com.noki.vpn

import com.noki.vpn.data.AtomicAuthSettingsStore
import com.noki.vpn.data.AuthTokenRefresher
import com.noki.vpn.data.AuthRefreshRejectedException
import com.noki.vpn.data.BackendAuthTokens
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.StoredSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AuthSessionSnapshot(
    val accessToken: String?,
    val refreshToken: String?,
    val accessTokenExpiresInSeconds: Long?,
    val refreshTokenExpiresAt: String?,
)

internal data class AuthSessionAttempt(
    val accessToken: String,
    val epoch: Long,
)

internal interface AuthenticatedCallRunner {
    suspend fun <T> run(block: suspend (String) -> T): T
}

internal class AuthSessionCoordinator(
    private val store: AtomicAuthSettingsStore,
    private val refresher: AuthTokenRefresher,
    private val onRevocationPending: () -> Unit = {},
) : AuthenticatedCallRunner {
    private val stateLock = Any()
    private val refreshMutex = Mutex()
    private var sessionEpoch = 0L

    @Volatile
    private var session = AuthSessionSnapshot(null, null, null, null)

    fun restore(settings: StoredSettings) {
        val restored = settings.toAuthSessionSnapshot()
        synchronized(stateLock) {
            if (session != restored) {
                session = restored
            }
        }
    }

    fun snapshot(): AuthSessionSnapshot = session

    fun attempt(): AuthSessionAttempt? = synchronized(stateLock) {
        session.accessToken
            ?.takeIf { it.isNotBlank() }
            ?.let { accessToken -> AuthSessionAttempt(accessToken, sessionEpoch) }
    }

    fun isCurrent(attempt: AuthSessionAttempt): Boolean = synchronized(stateLock) {
        sessionEpoch == attempt.epoch
    }

    fun commit(tokens: BackendAuthTokens) {
        val revocationStaged = synchronized(stateLock) {
            commitLocked(tokens)
        }
        if (revocationStaged) runCatching(onRevocationPending)
    }

    fun commitIfCurrent(attempt: AuthSessionAttempt, tokens: BackendAuthTokens): Boolean {
        val revocationStaged = synchronized(stateLock) {
            if (sessionEpoch != attempt.epoch) return@synchronized null
            commitLocked(tokens)
        } ?: return false
        if (revocationStaged) runCatching(onRevocationPending)
        return true
    }

    fun clear() {
        synchronized(stateLock) {
            sessionEpoch += 1L
            val updated = store.clearAuthAndStageRefreshToken()
            session = updated.toAuthSessionSnapshot()
        }
    }

    override suspend fun <T> run(block: suspend (String) -> T): T {
        val attempt = attempt()
            ?: throw BackendException("auth_required", 401)
        return run(attempt, block)
    }

    suspend fun <T> run(
        attempt: AuthSessionAttempt,
        block: suspend (String) -> T,
    ): T {
        if (!isCurrent(attempt)) throw BackendException("auth_required", 401)
        return try {
            block(attempt.accessToken)
        } catch (error: BackendException) {
            if (error.statusCode != 401) throw error
            val retryAttempt = retryAfterUnauthorized(attempt) ?: throw BackendException("auth_required", 401)
            block(retryAttempt.accessToken)
        }
    }

    suspend fun retryAfterUnauthorized(attempt: AuthSessionAttempt): AuthSessionAttempt? =
        refreshMutex.withLock {
            val current = snapshotForEpoch(attempt.epoch) ?: return@withLock null
            current.accessToken
                ?.takeIf { it.isNotBlank() && it != attempt.accessToken }
                ?.let { accessToken -> return@withLock AuthSessionAttempt(accessToken, attempt.epoch) }

            val latest = store.load()
            val latestSnapshot = latest.toAuthSessionSnapshot()
            if (
                latest.isAuthenticated &&
                !latestSnapshot.accessToken.isNullOrBlank() &&
                latestSnapshot.accessToken != attempt.accessToken
            ) {
                return@withLock applyStoredSession(attempt.epoch, latest)
            }
            if (current.refreshToken.isNullOrBlank() && latestSnapshot.refreshToken.isNullOrBlank()) {
                return@withLock null
            }

            val refreshed = try {
                refresher.refreshStoredTokens()
            } catch (error: CancellationException) {
                throw error
            } catch (error: BackendException) {
                if (error.statusCode == 401) throw AuthRefreshRejectedException(error)
                throw error
            } catch (error: Throwable) {
                throw error
            } ?: return@withLock null
            val stored = store.load()
            if (stored.backendAccessToken != refreshed.accessToken || !stored.isAuthenticated) {
                return@withLock null
            }
            applyStoredSession(attempt.epoch, stored)
        }

    private fun snapshotForEpoch(expectedEpoch: Long): AuthSessionSnapshot? = synchronized(stateLock) {
        session.takeIf { sessionEpoch == expectedEpoch }
    }

    private fun applyStoredSession(
        expectedEpoch: Long,
        stored: StoredSettings,
    ): AuthSessionAttempt? = synchronized(stateLock) {
        if (sessionEpoch != expectedEpoch || !stored.isAuthenticated) return@synchronized null
        val restored = stored.toAuthSessionSnapshot()
        val accessToken = restored.accessToken?.takeIf { it.isNotBlank() } ?: return@synchronized null
        session = restored
        AuthSessionAttempt(accessToken, expectedEpoch)
    }

    suspend fun bindToDevice(
        tokens: BackendAuthTokens,
        deviceId: String,
    ): BackendAuthTokens {
        val refreshToken = tokens.refreshToken?.takeIf { it.isNotBlank() } ?: return tokens
        if (deviceId.isBlank()) return tokens
        return refresher.refreshTokens(refreshToken, deviceId).withFallbackRefreshToken(refreshToken)
    }

    suspend fun revokeProvisional(tokens: BackendAuthTokens) {
        tokens.refreshToken
            ?.takeIf { it.isNotBlank() }
            ?.let { refresher.revokeToken(it) }
    }

    private fun BackendAuthTokens.withFallbackRefreshToken(previous: String): BackendAuthTokens =
        copy(refreshToken = refreshToken?.takeIf { it.isNotBlank() } ?: previous)

    private fun commitLocked(tokens: BackendAuthTokens): Boolean {
        sessionEpoch += 1L
        val commit = store.replaceAuthAndStagePreviousRefreshToken(tokens)
        session = commit.settings.toAuthSessionSnapshot()
        return commit.previousRefreshTokenStaged
    }

    private fun StoredSettings.toAuthSessionSnapshot(): AuthSessionSnapshot =
        AuthSessionSnapshot(
            accessToken = backendAccessToken,
            refreshToken = backendRefreshToken,
            accessTokenExpiresInSeconds = backendAccessTokenExpiresInSeconds,
            refreshTokenExpiresAt = backendRefreshExpiresAt,
        )
}
