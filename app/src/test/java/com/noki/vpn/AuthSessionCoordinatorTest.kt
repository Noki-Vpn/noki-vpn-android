package com.noki.vpn

import com.noki.vpn.data.AuthRefreshApi
import com.noki.vpn.data.AuthRefreshRejectedException
import com.noki.vpn.data.AuthTokenRefresher
import com.noki.vpn.data.BackendAuthTokens
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.ControlledAuthRefreshApi
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.InMemoryAtomicStoredSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AuthSessionCoordinatorTest {
    @Test
    fun refreshEndpointUnauthorizedIsReportedSeparatelyFromResourceUnauthorized() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val refreshRejection = BackendException("invalid_refresh_token", 401)
        val api = object : AuthRefreshApi {
            override suspend fun refreshAuthToken(
                refreshToken: String,
                deviceId: String?,
                requestId: String?,
            ): BackendAuthTokens = throw refreshRejection
        }
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val result = runCatching {
            coordinator.run { throw BackendException("resource_unauthorized", 401) }
        }

        assertEquals(refreshRejection, result.exceptionOrNull()?.cause)
        assertTrue(store.load().isAuthenticated)
    }

    @Test
    fun repeatedResourceUnauthorizedDoesNotBecomeRefreshRejection() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = object : AuthRefreshApi {
            override suspend fun refreshAuthToken(
                refreshToken: String,
                deviceId: String?,
                requestId: String?,
            ): BackendAuthTokens = rotatedTokens()
        }
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val result = runCatching {
            coordinator.run { throw BackendException("resource_unauthorized", 401) }
        }

        assertTrue(result.exceptionOrNull() is BackendException)
        assertTrue(result.exceptionOrNull() !is AuthRefreshRejectedException)
        assertTrue(store.load().isAuthenticated)
        assertEquals("new-access", store.load().backendAccessToken)
    }

    @Test
    fun missingRefreshCredentialDoesNotBecomeRefreshRejection() = runBlocking {
        val settings = authenticatedSettings().copy(backendRefreshToken = null)
        val store = InMemoryAtomicStoredSettingsStore(settings)
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val result = runCatching {
            coordinator.run { throw BackendException("resource_unauthorized", 401) }
        }

        assertTrue(result.exceptionOrNull() is BackendException)
        assertTrue(result.exceptionOrNull() !is AuthRefreshRejectedException)
        assertEquals("auth_required", result.exceptionOrNull()?.message)
        assertTrue(store.load().isAuthenticated)
        assertEquals(0, api.callCount.get())
    }

    @Test
    fun transientRefreshFailureIsNotCollapsedIntoLogoutUnauthorized() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = object : AuthRefreshApi {
            override suspend fun refreshAuthToken(
                refreshToken: String,
                deviceId: String?,
                requestId: String?,
            ): BackendAuthTokens = throw IOException("offline")
        }
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val result = runCatching {
            coordinator.run { throw BackendException("unauthorized", 401) }
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(store.load().isAuthenticated)
        assertEquals("old-refresh", store.load().backendRefreshToken)
    }

    @Test
    fun restoredTokenRotationKeepsCapturedAttemptInCurrentSession() {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val coordinator = AuthSessionCoordinator(
            store,
            AuthTokenRefresher(store, ControlledAuthRefreshApi(rotatedTokens())),
        )
        coordinator.restore(store.load())
        val attempt = checkNotNull(coordinator.attempt())

        coordinator.restore(
            store.updateSettings { latest -> latest.copy(backendAccessToken = "service-access") },
        )

        assertTrue(coordinator.isCurrent(attempt))
    }

    @Test
    fun newLoginCommitInvalidatesCapturedAttempt() {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val coordinator = AuthSessionCoordinator(
            store,
            AuthTokenRefresher(store, ControlledAuthRefreshApi(rotatedTokens())),
        )
        coordinator.restore(store.load())
        val attempt = checkNotNull(coordinator.attempt())

        coordinator.commit(rotatedTokens())

        assertTrue(!coordinator.isCurrent(attempt))
    }

    @Test
    fun replacementTokensFromStaleAttemptCannotOverwriteNewLogin() {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val coordinator = AuthSessionCoordinator(
            store,
            AuthTokenRefresher(store, ControlledAuthRefreshApi(rotatedTokens())),
        )
        coordinator.restore(store.load())
        val staleAttempt = checkNotNull(coordinator.attempt())
        val newLoginTokens = BackendAuthTokens(
            accessToken = "new-login-access",
            refreshToken = "new-login-refresh",
            expiresInSeconds = 7_200,
            refreshExpiresAt = "new-login-expiry",
        )
        coordinator.commit(newLoginTokens)

        val committed = coordinator.commitIfCurrent(staleAttempt, rotatedTokens())

        assertTrue(!committed)
        assertEquals("new-login-access", coordinator.snapshot().accessToken)
        assertEquals("new-login-access", store.load().backendAccessToken)
    }

    @Test
    fun logoutInvalidatesCapturedAttempt() {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val coordinator = AuthSessionCoordinator(
            store,
            AuthTokenRefresher(store, ControlledAuthRefreshApi(rotatedTokens())),
        )
        coordinator.restore(store.load())
        val attempt = checkNotNull(coordinator.attempt())

        coordinator.clear()

        assertTrue(!coordinator.isCurrent(attempt))
    }

    @Test
    fun explicitAttemptCannotStartCallAfterNewLoginCommit() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val coordinator = AuthSessionCoordinator(
            store,
            AuthTokenRefresher(store, ControlledAuthRefreshApi(rotatedTokens())),
        )
        coordinator.restore(store.load())
        val attempt = checkNotNull(coordinator.attempt())
        var calls = 0
        coordinator.commit(rotatedTokens())

        val result = runCatching {
            coordinator.run(attempt) {
                calls += 1
                it
            }
        }

        assertTrue(result.exceptionOrNull() is BackendException)
        assertEquals(0, calls)
    }

    @Test
    fun restoringServiceRotatedSnapshotKeepsInFlightCallInSameSession() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val call = async {
            runCatching {
                coordinator.run { token ->
                    if (token == "old-access") throw BackendException("unauthorized", 401)
                    token
                }
            }
        }
        api.requestStarted.await()
        val serviceRotated = store.updateSettings { latest ->
            latest.copy(
                backendAccessToken = "service-access",
                backendRefreshToken = "service-refresh",
                backendAccessTokenExpiresInSeconds = 7_200,
                backendRefreshExpiresAt = "service-expiry",
            )
        }
        coordinator.restore(serviceRotated)
        api.releaseResponse.complete(Unit)

        val result = call.await()

        assertTrue(result.isSuccess)
        assertEquals("service-access", result.getOrNull())
        assertEquals("service-access", coordinator.snapshot().accessToken)
        assertEquals(1, api.callCount.get())
    }

    @Test
    fun committingNewLoginInvalidatesCallFromPreviousSession() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val call = async {
            runCatching {
                coordinator.run { token ->
                    if (token == "old-access") throw BackendException("unauthorized", 401)
                    token
                }
            }
        }
        api.requestStarted.await()
        coordinator.commit(
            BackendAuthTokens(
                accessToken = "new-login-access",
                refreshToken = "new-login-refresh",
                expiresInSeconds = 7_200,
                refreshExpiresAt = "new-login-expiry",
            ),
        )
        api.releaseResponse.complete(Unit)

        val result = call.await()

        assertTrue(result.exceptionOrNull() is BackendException)
        assertEquals("new-login-access", coordinator.snapshot().accessToken)
        assertEquals("new-login-access", store.load().backendAccessToken)
        assertEquals(1, api.callCount.get())
    }

    @Test
    fun committingNewLoginBeforeOldUnauthorizedResponsePreventsCrossSessionRetry() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())
        val firstCallStarted = CompletableDeferred<Unit>()
        val releaseUnauthorized = CompletableDeferred<Unit>()
        val seenTokens = mutableListOf<String>()

        val call = async {
            runCatching {
                coordinator.run { token ->
                    seenTokens += token
                    if (token == "old-access") {
                        firstCallStarted.complete(Unit)
                        releaseUnauthorized.await()
                        throw BackendException("unauthorized", 401)
                    }
                    token
                }
            }
        }
        firstCallStarted.await()
        coordinator.commit(
            BackendAuthTokens(
                accessToken = "new-login-access",
                refreshToken = "new-login-refresh",
                expiresInSeconds = 7_200,
                refreshExpiresAt = "new-login-expiry",
            ),
        )
        releaseUnauthorized.complete(Unit)

        val result = call.await()

        assertTrue(result.exceptionOrNull() is BackendException)
        assertEquals(listOf("old-access"), seenTokens)
        assertEquals("new-login-access", coordinator.snapshot().accessToken)
        assertEquals(listOf("old-refresh"), store.pendingLogoutRevocations())
        assertEquals(0, api.callCount.get())
    }

    @Test
    fun manualRetryRejectsAttemptCapturedBeforeNewLoginCommit() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())
        val attempt = checkNotNull(coordinator.attempt())

        coordinator.commit(
            BackendAuthTokens(
                accessToken = "new-login-access",
                refreshToken = "new-login-refresh",
                expiresInSeconds = 7_200,
                refreshExpiresAt = "new-login-expiry",
            ),
        )

        val retryAttempt = coordinator.retryAfterUnauthorized(attempt)

        assertNull(retryAttempt)
        assertEquals(0, api.callCount.get())
    }

    @Test
    fun manualRetryUsesServiceRotationWithinCapturedEpoch() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())
        val attempt = checkNotNull(coordinator.attempt())
        val serviceRotated = store.updateSettings { latest ->
            latest.copy(
                backendAccessToken = "service-access",
                backendRefreshToken = "service-refresh",
                backendAccessTokenExpiresInSeconds = 7_200,
                backendRefreshExpiresAt = "service-expiry",
            )
        }
        coordinator.restore(serviceRotated)

        val retryAttempt = coordinator.retryAfterUnauthorized(attempt)

        assertEquals("service-access", retryAttempt?.accessToken)
        assertEquals(attempt.epoch, retryAttempt?.epoch)
        assertEquals(0, api.callCount.get())
    }

    @Test
    fun restoringSameSnapshotDoesNotInvalidateInFlightRefresh() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val call = async {
            coordinator.run { token ->
                if (token == "old-access") throw BackendException("unauthorized", 401)
                token
            }
        }
        api.requestStarted.await()
        coordinator.restore(store.load())
        api.releaseResponse.complete(Unit)

        assertEquals("new-access", call.await())
        assertEquals("new-access", coordinator.snapshot().accessToken)
    }

    @Test
    fun cancellationDuringRefreshPropagatesToCaller() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = object : AuthRefreshApi {
            override suspend fun refreshAuthToken(
                refreshToken: String,
                deviceId: String?,
                requestId: String?,
            ): BackendAuthTokens = throw CancellationException("cancelled")
        }
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val result = runCatching {
            coordinator.run { throw BackendException("unauthorized", 401) }
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun concurrentUnauthorizedCallsRefreshOnceAndRetryWithRotatedToken() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())
        val oldTokenCalls = AtomicInteger(0)
        val bothOldTokenCallsStarted = CompletableDeferred<Unit>()

        suspend fun call(): String = coordinator.run { token ->
            if (token == "old-access") {
                if (oldTokenCalls.incrementAndGet() == 2) {
                    bothOldTokenCallsStarted.complete(Unit)
                }
                bothOldTokenCallsStarted.await()
                throw BackendException("unauthorized", 401)
            }
            token
        }

        val calls = listOf(async { call() }, async { call() })
        bothOldTokenCallsStarted.await()
        api.releaseResponse.complete(Unit)

        val results = calls.awaitAll()

        assertEquals(listOf("new-access", "new-access"), results)
        assertEquals(1, api.callCount.get())
        assertEquals("new-access", coordinator.snapshot().accessToken)
    }

    @Test
    fun clearInvalidatesRefreshThatCompletesAfterLogout() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings())
        val api = ControlledAuthRefreshApi(rotatedTokens())
        val coordinator = AuthSessionCoordinator(store, AuthTokenRefresher(store, api))
        coordinator.restore(store.load())

        val call = async {
            runCatching {
                coordinator.run { token ->
                    if (token == "old-access") throw BackendException("unauthorized", 401)
                    token
                }
            }
        }
        api.requestStarted.await()
        coordinator.clear()
        api.releaseResponse.complete(Unit)

        val result = call.await()

        assertTrue(result.exceptionOrNull() is BackendException)
        assertNull(coordinator.snapshot().accessToken)
        assertNull(store.load().backendAccessToken)
        assertNull(store.load().backendRefreshToken)
        assertEquals(listOf("old-refresh", "new-refresh"), store.pendingLogoutRevocations())
    }

    private fun authenticatedSettings() =
        DefaultStoredSettingsFactory.create().copy(
            isAuthenticated = true,
            backendAccessToken = "old-access",
            backendRefreshToken = "old-refresh",
            backendAccessTokenExpiresInSeconds = 60,
            backendRefreshExpiresAt = "old-expiry",
            backendDeviceId = "device-1",
        )

    private fun rotatedTokens() =
        BackendAuthTokens(
            accessToken = "new-access",
            refreshToken = "new-refresh",
            expiresInSeconds = 3_600,
            refreshExpiresAt = "new-expiry",
        )
}
