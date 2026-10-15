package com.noki.vpn.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.UUID

internal class AuthTokenRefresher(
    private val store: AtomicAuthSettingsStore,
    private val api: AuthRefreshApi,
    private val onRefreshFailure: (Throwable) -> Unit = {},
    private val onRevocationPending: () -> Unit = {},
) {
    suspend fun refreshStoredTokens(): BackendAuthTokens? = refreshStoredTokensIfExpiringBefore(null)

    suspend fun refreshExpiringStoredTokens(nowMillis: Long = System.currentTimeMillis()): BackendAuthTokens? =
        refreshStoredTokensIfExpiringBefore(nowMillis / 1_000L + REFRESH_AHEAD_SECONDS)

    private suspend fun refreshStoredTokensIfExpiringBefore(expiryCutoffSeconds: Long?): BackendAuthTokens? {
        val observed = store.load()
        val observedRefreshToken = observed.backendRefreshToken?.takeIf { it.isNotBlank() } ?: return null
        return refreshMutex.withLock {
            val current = store.load()
            val storedRefreshToken = current.backendRefreshToken?.takeIf { it.isNotBlank() } ?: return@withLock null
            if (expiryCutoffSeconds != null) {
                if (!current.isAuthenticated || current.backendDeviceId.isBlank()) return@withLock null
                if (current.backendRefreshRequestId == null &&
                    !accessTokenExpiresBy(current.backendAccessToken, expiryCutoffSeconds)) return@withLock null
            }
            if (
                storedRefreshToken != observedRefreshToken ||
                current.backendAccessToken != observed.backendAccessToken
            ) {
                return@withLock current.toAuthTokens(storedRefreshToken)
            }
            try {
                refreshAndPersist(current, storedRefreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onRefreshFailure(error)
                throw error
            }
        }
    }

    private suspend fun refreshAndPersist(
        current: StoredSettings,
        storedRefreshToken: String,
    ): BackendAuthTokens? {
        val requestId = current.backendRefreshRequestId ?: UUID.randomUUID().toString()
        var requestStaged = false
        val staged = store.updateSettings { latest ->
            if (latest.hasSameAuthSessionAs(current)) {
                requestStaged = true
                latest.copy(backendRefreshRequestId = requestId)
            } else {
                latest
            }
        }
        if (!requestStaged) {
            return if (staged.hasDifferentAuthTokenBundleFrom(current)) {
                staged.toAuthTokensOrNull()
            } else {
                null
            }
        }
        val tokens = api.refreshAuthToken(
            refreshToken = storedRefreshToken,
            deviceId = current.backendDeviceId.takeIf { it.isNotBlank() },
            requestId = requestId,
        )
        val nextRefreshToken = tokens.refreshToken?.takeIf { it.isNotBlank() } ?: storedRefreshToken
        val commit = store.commitRefreshedAuth(current, tokens, nextRefreshToken)
        val committed = commit.committed
        val updated = commit.settings
        if (!committed) runCatching(onRevocationPending)
        if (!committed && updated.backendRefreshRequestId == requestId) {
            store.updateSettings { latest ->
                if (latest.backendRefreshRequestId == requestId) {
                    latest.copy(backendRefreshRequestId = null)
                } else {
                    latest
                }
            }
        }
        return if (committed) {
            tokens.copy(refreshToken = nextRefreshToken)
        } else if (updated.hasDifferentAuthTokenBundleFrom(current)) {
            updated.toAuthTokensOrNull()
        } else {
            null
        }
    }

    suspend fun refreshTokens(
        refreshToken: String,
        deviceId: String?,
    ): BackendAuthTokens = api.refreshAuthToken(
        refreshToken,
        deviceId,
        UUID.randomUUID().toString(),
    )

    suspend fun revokeToken(refreshToken: String) {
        api.revokeRefreshToken(refreshToken)
    }

    private fun StoredSettings.toAuthTokens(refreshToken: String): BackendAuthTokens? =
        toAuthTokensOrNull()?.copy(refreshToken = refreshToken)

    private fun StoredSettings.toAuthTokensOrNull(): BackendAuthTokens? {
        if (!isAuthenticated) return null
        val accessToken = backendAccessToken?.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = backendRefreshToken?.takeIf { it.isNotBlank() } ?: return null
        return BackendAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = backendAccessTokenExpiresInSeconds,
            refreshExpiresAt = backendRefreshExpiresAt,
        )
    }

    private fun StoredSettings.hasDifferentAuthTokenBundleFrom(other: StoredSettings): Boolean =
        isAuthenticated != other.isAuthenticated ||
            backendAccessToken != other.backendAccessToken ||
            backendRefreshToken != other.backendRefreshToken ||
            backendAccessTokenExpiresInSeconds != other.backendAccessTokenExpiresInSeconds ||
            backendRefreshExpiresAt != other.backendRefreshExpiresAt

    private fun accessTokenExpiresBy(token: String?, cutoffSeconds: Long): Boolean {
        // JWT payload is only a scheduling hint; the API still validates authentication.
        val expiresAt = runCatching {
            val payload = checkNotNull(token).split('.').also { check(it.size == 3) }[1]
            JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).getLong("exp")
        }.getOrNull()
        return expiresAt == null || expiresAt <= cutoffSeconds
    }

    private companion object {
        const val REFRESH_AHEAD_SECONDS = 5 * 60L
        val refreshMutex = Mutex()
    }
}

internal interface PendingLogoutRevocationStore {
    fun loadPendingLogoutRevocations(): List<String>
    fun removePendingLogoutRevocation(refreshToken: String)
}

internal object PendingLogoutRevocationCodec {
    fun encode(refreshTokens: List<String>): String = JSONArray(
        refreshTokens.normalized(),
    ).toString()

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                repeat(json.length()) { index -> add(json.optString(index)) }
            }.normalized()
        }.getOrDefault(emptyList())
    }

    private fun List<String>.normalized(): List<String> =
        asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
}

internal class PendingLogoutRevocationCoordinator(
    private val store: PendingLogoutRevocationStore,
    private val revoke: suspend (String) -> Unit,
) {
    suspend fun retryAll(): Boolean {
        val pending = store.loadPendingLogoutRevocations()
        for (refreshToken in pending) {
            val terminal = try {
                revoke(refreshToken)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: BackendException) {
                error.statusCode in TERMINAL_STATUS_CODES
            } catch (_: Exception) {
                false
            }
            if (!terminal) return false
            store.removePendingLogoutRevocation(refreshToken)
        }
        return store.loadPendingLogoutRevocations().isEmpty()
    }

    private companion object {
        val TERMINAL_STATUS_CODES = setOf(400, 401, 404, 410)
    }
}

class PendingLogoutRevocationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val repository = SettingsRepository(applicationContext)
            val backendApi = BackendApiClient()
            val completed = PendingLogoutRevocationCoordinator(
                store = repository,
                revoke = backendApi::revokeRefreshToken,
            ).retryAll()
            if (completed) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "pending_logout_revocation"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingLogoutRevocationWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    backoffPolicy = BackoffPolicy.EXPONENTIAL,
                    backoffDelay = 10,
                    timeUnit = TimeUnit.SECONDS,
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
