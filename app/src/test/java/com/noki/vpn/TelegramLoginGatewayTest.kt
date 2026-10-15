package com.noki.vpn

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Process
import android.os.UserHandle
import androidx.lifecycle.SavedStateHandle
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [TelegramLoginGatewayTest.DomainVerificationShadow::class])
class TelegramLoginGatewayTest {
    @Test
    fun `new runtime releases an abandoned link and stale callback cannot clear a newer live flow`() {
        val handle = SavedStateHandle()
        val previousAttempt = TelegramAuthPurposeState(handle).begin(TelegramAuthPurpose.LINK)
        val scope = CoroutineScope(Job().apply { cancel() })
        val runtime = AppUiRuntime(RuntimeEnvironment.getApplication(), handle, scope)
        assertEquals(null, runtime.telegramAuthPurposeState.currentAttemptId)
        assertFalse(runtime.telegramAuthPurposeState.isCurrent(previousAttempt))
        runtime.uiState = AppUiState(isReady = true, isAuthenticated = true)

        val liveAttempt = runtime.telegramAuthPurposeState.begin(TelegramAuthPurpose.LINK)
        val request = runtime.telegramLoginGateway.begin()
        runtime.telegramLoginGateway.markExternalFlowStarted(request.clientState)
        runtime.uiState = runtime.uiState.copy(telegramLoginState = TelegramLoginState.LaunchingSdk(TelegramAuthPurpose.LINK))
        runtime.handleTelegramLoginCallback(runtime.telegramLoginGateway.consumeCallback("old-code", "S".repeat(43), null))

        assertTrue(runtime.telegramAuthPurposeState.isCurrent(liveAttempt))
        assertEquals(TelegramLoginState.LaunchingSdk(TelegramAuthPurpose.LINK), runtime.uiState.telegramLoginState)
        assertTrue(runtime.telegramLoginGateway.consumeCallback("current-code", request.clientState, null)
            is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `native launch falls back when callback domain is missing or unverified`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val manager = domainVerification(activity)
        val gateway = TelegramLoginGateway()
        for (hosts in listOf(
            emptyMap(),
            mapOf("app601443831-login.tg.dev" to DomainVerificationUserState.DOMAIN_STATE_NONE),
            mapOf("another-login.tg.dev" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED),
        )) {
            manager.state = domainState(activity, allowed = true, hosts = hosts)
            assertFalse(gateway.openNative(activity, "tg://oauth?token=native-request"))
            assertEquals(null, shadowOf(activity).nextStartedActivity)
        }
        manager.state = null
        assertFalse(gateway.openNative(activity, "tg://oauth?token=native-request"))
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `disabled supported links falls back even for a verified callback`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        domainVerification(activity).state = domainState(
            activity, allowed = false,
            hosts = mapOf("app601443831-login.tg.dev" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED),
        )
        assertFalse(TelegramLoginGateway().openNative(activity, "tg://oauth?token=native-request"))
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `native launch uses verified or explicitly selected callback domain`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (state in listOf(DomainVerificationUserState.DOMAIN_STATE_VERIFIED, DomainVerificationUserState.DOMAIN_STATE_SELECTED)) {
            domainVerification(activity).state = domainState(
                activity, allowed = true, hosts = mapOf("app601443831-login.tg.dev" to state),
            )
            assertTrue(TelegramLoginGateway().openNative(activity, "tg://oauth?token=native-request"))
            assertEquals("tg://oauth?token=native-request", shadowOf(activity).nextStartedActivity.dataString)
        }
    }

    @Test
    fun `unavailable domain verification service falls back`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        domainVerification(activity).unavailable = true
        assertFalse(TelegramLoginGateway().openNative(activity, "tg://oauth?token=native-request"))
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `native return fallback retains PKCE and accepts only the current browser state`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val entropy = ByteArray(32) { 7 }
        val gateway = TelegramLoginGateway(randomBytes = { entropy.copyOf() })
        val request = gateway.begin()
        assertTrue(gateway.markExternalFlowStarted(request.clientState))
        assertFalse(gateway.openNative(activity, "tg://oauth?token=native-request"))
        assertTrue(gateway.completeExternalFlow(request.clientState))
        assertTrue(gateway.markExternalFlowStarted(request.clientState, browser = true))
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("late-native-code", null, null))
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeBrowserCallback("S".repeat(43), null))
        assertEquals(
            TelegramLoginCallbackResult.BrowserState(
                request.clientState, Base64.getUrlEncoder().withoutPadding().encodeToString(entropy),
            ),
            gateway.consumeBrowserCallback(request.clientState, null),
        )
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeBrowserCallback(request.clientState, null))
    }

    @Test
    @Config(sdk = [28])
    fun `older Android preserves native launch without domain verification API`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(TelegramLoginGateway().openNative(activity, "tg://oauth?token=native-request"))
        assertEquals("tg://oauth?token=native-request", shadowOf(activity).nextStartedActivity.dataString)
    }

    private fun domainVerification(activity: Activity): DomainVerificationShadow {
        val manager = activity.getSystemService(DomainVerificationManager::class.java)
            ?: Shadow.newInstanceOf(DomainVerificationManager::class.java).also {
                Shadow.extract<ShadowContextImpl>(activity.baseContext).setSystemService(Context.DOMAIN_VERIFICATION_SERVICE, it)
            }
        return Shadow.extract(manager)
    }

    private fun domainState(activity: Activity, allowed: Boolean, hosts: Map<String, Int>): DomainVerificationUserState =
        ReflectionHelpers.callConstructor(
            DomainVerificationUserState::class.java,
            ClassParameter.from(UUID::class.java, UUID.randomUUID()),
            ClassParameter.from(String::class.java, activity.packageName),
            ClassParameter.from(UserHandle::class.java, Process.myUserHandle()),
            ClassParameter.from(Boolean::class.javaPrimitiveType, allowed),
            ClassParameter.from(Map::class.java, hosts),
        )

    @Implements(value = DomainVerificationManager::class, minSdk = 31)
    class DomainVerificationShadow {
        var state: DomainVerificationUserState? = null
        var unavailable = false

        @Implementation
        fun getDomainVerificationUserState(packageName: String): DomainVerificationUserState? {
            check(!unavailable) { "domain_service_unavailable" }
            return state?.takeIf { it.packageName == packageName }
        }
    }

    @Test
    fun `native code without state can be verified using the active PKCE session`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 8 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)

        val result = gateway.consumeCallback("native-code", null, null)

        assertTrue(result is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `a native candidate must not consume the verifier before server verification`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 8 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)

        gateway.consumeCallback("first-code", request.clientState, null)
        val second = gateway.consumeCallback("genuine-code", request.clientState, null)

        assertTrue(second is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `resume timeout cannot expire during native PKCE verification`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 8 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        val lease = checkNotNull(gateway.resumeTimeoutLease())
        gateway.consumeCallback("native-code", request.clientState, null)

        assertFalse(gateway.expireExternalFlow(lease))
    }

    @Test
    fun `begin creates S256 challenge and callback consumes verifier once`() {
        val entropy = ByteArray(32) { 7 }
        val gateway = TelegramLoginGateway(randomBytes = { entropy.copyOf() })

        val request = gateway.begin()
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        val result = gateway.consumeCallback(
            code = "authorization-code",
            state = request.clientState,
            error = null,
        )

        assertEquals(expectedChallenge, request.codeChallenge)
        assertNotEquals(request.codeChallenge, request.clientState)
        assertEquals(
            TelegramLoginCallbackResult.AuthorizationCode(
                code = "authorization-code",
                codeVerifier = verifier,
            ),
            result,
        )
        assertTrue(gateway.finishNativeCallback(result as TelegramLoginCallbackResult.AuthorizationCode, success = true))
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback(code = "second-code", state = request.clientState, error = null),
        )
    }

    @Test
    fun `missing state native callbacks cannot enter a browser or unlaunched flow`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("code-before-launch", null, null))
        gateway.markExternalFlowStarted(request.clientState, browser = true)
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("native-code", null, null))
        assertTrue(gateway.consumeBrowserCallback(request.clientState, null) is TelegramLoginCallbackResult.BrowserState)
    }

    @Test
    fun `rejected candidate leaves genuine native callback usable and duplicate does not exchange`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        val stale = gateway.consumeCallback("stale-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("stale-code", null, null))
        val real = gateway.consumeCallback("real-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertTrue(gateway.finishNativeCallback(stale, success = false))
        assertEquals(null, gateway.failedNativeCallbackTimeoutLease())
        assertTrue(gateway.finishNativeCallback(real, success = true))
        assertFalse(gateway.finishNativeCallback(stale, success = true))
        assertEquals(null, gateway.resumeTimeoutLease())
    }

    @Test
    fun `native candidate concurrency is bounded and rejected candidate releases a slot`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        val first = gateway.consumeCallback("first-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        gateway.consumeCallback("second-code", null, null)
        assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("third-code", null, null))
        assertTrue(gateway.finishNativeCallback(first, success = false))
        assertTrue(gateway.consumeCallback("third-code", null, null) is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `older native exchange cannot finish a newer login`() {
        var seed = 1
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { seed++.toByte() } })
        val first = gateway.begin()
        gateway.markExternalFlowStarted(first.clientState)
        val old = gateway.consumeCallback("old-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        val oldLease = checkNotNull(gateway.resumeTimeoutLease())
        val current = gateway.begin()
        assertFalse(gateway.expireExternalFlow(oldLease))
        gateway.markExternalFlowStarted(current.clientState)
        assertFalse(gateway.finishNativeCallback(old, success = true))
        val real = gateway.consumeCallback("new-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertTrue(gateway.finishNativeCallback(real, success = true))
    }

    @Test
    fun `failed native timeout waits for foreground and cannot beat another candidate`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        gateway.markHostStopped()
        val candidate = gateway.consumeCallback("bad-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertTrue(gateway.finishNativeCallback(candidate, success = false))
        assertEquals(null, gateway.failedNativeCallbackTimeoutLease())
        val lease = checkNotNull(gateway.resumeTimeoutLease())
        val real = gateway.consumeCallback("real-code", null, null) as TelegramLoginCallbackResult.AuthorizationCode
        assertFalse(gateway.expireExternalFlow(lease))
        assertTrue(gateway.finishNativeCallback(real, success = true))
    }

    @Test
    fun `native blank state error and malformed code cannot consume a session`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 7 } })
        val request = gateway.begin()
        gateway.markExternalFlowStarted(request.clientState)
        for (candidate in listOf(
            gateway.consumeCallback("code", "", null),
            gateway.consumeCallback(null, null, "access_denied"),
            gateway.consumeCallback("", null, null),
            gateway.consumeCallback("x".repeat(2049), null, null),
        )) {
            assertEquals(TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale), candidate)
        }
        assertTrue(gateway.consumeCallback("valid-code", null, null) is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `new begin invalidates previous PKCE session`() {
        var seed = 1
        val gateway = TelegramLoginGateway(
            randomBytes = { ByteArray(32) { seed++.toByte() } },
        )

        val first = gateway.begin()
        val second = gateway.begin()
        val result = gateway.consumeCallback("authorization-code", second.clientState, null)

        assertNotEquals(first.codeChallenge, second.codeChallenge)
        assertTrue(result is TelegramLoginCallbackResult.AuthorizationCode)
    }

    @Test
    fun `cancellation and explicit cancel clear active session`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 3 } })
        val first = gateway.begin()

        assertEquals(
            TelegramLoginCallbackResult.Cancelled,
            gateway.consumeCallback(code = null, state = first.clientState, error = "access_denied"),
        )
        val second = gateway.begin()
        gateway.cancel()
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback(code = "authorization-code", state = second.clientState, error = null),
        )
    }

    @Test
    fun `browser callback returns opaque state with verifier and consumes session once`() {
        val entropy = ByteArray(32) { 9 }
        val gateway = TelegramLoginGateway(randomBytes = { entropy.copyOf() })
        val request = gateway.begin()

        val result = gateway.consumeBrowserCallback(state = request.clientState, error = null)

        assertEquals(
            TelegramLoginCallbackResult.BrowserState(
                state = request.clientState,
                codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy),
            ),
            result,
        )
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeBrowserCallback(state = request.clientState, error = null),
        )
    }

    @Test
    fun `mismatched callback cannot consume the current session`() {
        var seed = 1
        val gateway = TelegramLoginGateway(
            randomBytes = { ByteArray(32) { seed++.toByte() } },
        )
        val stale = gateway.begin()
        val current = gateway.begin()

        assertFalse(gateway.cancel(stale.clientState))
        assertFalse(gateway.markExternalFlowStarted(stale.clientState))
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("stale-code", stale.clientState, null),
        )
        assertTrue(
            gateway.consumeCallback("current-code", current.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }

    @Test
    fun `activity destruction cancels only pre external preparation`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 6 } })
        val preparing = gateway.begin()

        assertTrue(gateway.cancelPreparationIfPending())
        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("late-code", preparing.clientState, null),
        )

        val external = gateway.begin()
        assertTrue(gateway.markExternalFlowStarted(external.clientState))
        assertFalse(gateway.cancelPreparationIfPending())
        assertTrue(
            gateway.consumeCallback("current-code", external.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }

    @Test
    fun `stale callback cannot reset external flow timeout`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 5 } })
        val current = gateway.begin()
        gateway.markExternalFlowStarted(current.clientState)
        gateway.markHostStopped()

        assertEquals(
            TelegramLoginCallbackResult.Failure(TelegramCallbackFailure.Stale),
            gateway.consumeCallback("foreign-code", "F".repeat(43), null),
        )
        val staleTimeoutLease = checkNotNull(gateway.resumeTimeoutLease())
        val currentTimeoutLease = checkNotNull(gateway.resumeTimeoutLease())
        assertFalse(gateway.expireExternalFlow(staleTimeoutLease))
        assertTrue(currentTimeoutLease !== staleTimeoutLease)
        assertTrue(
            gateway.consumeCallback("current-code", current.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }

    @Test
    fun `old resume timeout cannot cancel a restarted login`() {
        var seed = 1
        val gateway = TelegramLoginGateway(
            randomBytes = { ByteArray(32) { seed++.toByte() } },
        )
        val stale = gateway.begin()
        gateway.markExternalFlowStarted(stale.clientState)
        gateway.markHostStopped()
        val staleTimeoutLease = checkNotNull(gateway.resumeTimeoutLease())

        gateway.cancel()
        val current = gateway.begin()
        gateway.markExternalFlowStarted(current.clientState)

        assertFalse(gateway.expireExternalFlow(staleTimeoutLease))
        assertTrue(
            gateway.consumeCallback("current-code", current.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }

    @Test
    fun `external flow lifecycle survives host recreation on retained gateway`() {
        val gateway = TelegramLoginGateway(randomBytes = { ByteArray(32) { 5 } })
        val request = gateway.begin()
        assertTrue(gateway.markExternalFlowStarted(request.clientState))
        gateway.markHostStopped()

        val timeoutLease = checkNotNull(gateway.resumeTimeoutLease())
        assertTrue(gateway.completeExternalFlow(request.clientState))
        assertFalse(gateway.expireExternalFlow(timeoutLease))

        assertTrue(
            gateway.consumeCallback("current-code", request.clientState, null) is
                TelegramLoginCallbackResult.AuthorizationCode,
        )
    }
}
