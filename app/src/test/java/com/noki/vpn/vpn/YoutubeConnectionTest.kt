package com.noki.vpn.vpn

import com.noki.vpn.data.*
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YoutubeConnectionTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val id = "11111111-1111-4111-8111-111111111111"
    private val keyId = "22222222-2222-4222-8222-222222222222"
    private fun response(mode: String = "wireguard", status: String = "ready", time: Instant = now): JSONObject = JSONObject()
        .put("version", 1).put("mode", mode).put("status", status).put("grant_id", id)
        .put("generation", 1).put("expires_at", time.plusSeconds(120).toString()).put("revoke_token", "capability")
        .apply {
            if (status == "ready" && mode == "wireguard") {
                put("public_key_id", keyId)
                put("wireguard", JSONObject().put("endpoint", "203.0.113.5:51820")
                    .put("server_public_key", Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
                    .put("address", "10.88.0.2/32").put("dns", org.json.JSONArray().put("1.1.1.1"))
                    .put("mtu", 1280).put("allowed_ips", org.json.JSONArray().put("0.0.0.0/0")))
            }
            if (status == "ready" && mode == "selected_transport") {
                put("endpoint", endpoint())
                put("vpn_secret", "33333333-3333-4333-8333-333333333333")
            }
        }

    private fun endpoint(): JSONObject = JSONObject().put("code", "youtube-tls").put("entry_host", "youtube.example.test")
        .put("entry_port", 443).put("server_name", "youtube.example.test").put("proxy_type", "vless")
        .put("transport", "tcp").put("security", "tls")

    @Test fun naiveSelectedTransportRequiresAndCarriesItsOwnUsername() {
        val payload = response("selected_transport").put("endpoint", endpoint()
            .put("proxy_type", "naive").put("transport", "naive"))
        assertThrows(IllegalArgumentException::class.java) { BackendYoutubeAccessJsonParser.parse(payload, now) }
        val grant = BackendYoutubeAccessJsonParser.parse(payload.put("vpn_username", "grant-user"), now)
        val primary = EndpointSelector.profileFromCandidate(checkNotNull(grant.endpoint), "primary-secret", "primary-user")
        val runtime = YoutubeConnection.runtimeConfig(grant, primary, keyId, ByteArray(32)) as YoutubeConnectionConfig.SelectedTransport
        assertEquals("grant-user", runtime.profile.vpnUsername)
        assertEquals(grant.vpnSecret, runtime.profile.uuid)
    }

    @Test fun onlyReadyGrantHasRuntimeMaterial() {
        val ready = BackendYoutubeAccessJsonParser.parse(response(), now)
        val privateKey = ByteArray(32) { 9 }
        assertSame(privateKey, (YoutubeConnection.runtimeConfig(ready, null, keyId, privateKey) as YoutubeConnectionConfig.WireGuard).privateKey)
        assertThrows(IllegalArgumentException::class.java) { YoutubeConnection.runtimeConfig(ready, null, id, privateKey) }
        for (status in listOf("pending", "blocked")) {
            val payload = response(status = status)
            assertNull(BackendYoutubeAccessJsonParser.parse(payload, now).wireguard)
            payload.put("wireguard", response().getJSONObject("wireguard"))
            assertThrows(IllegalArgumentException::class.java) { BackendYoutubeAccessJsonParser.parse(payload, now) }
        }
        assertThrows(IllegalArgumentException::class.java) { BackendYoutubeAccessJsonParser.parse(response().put("mode", "reality"), now) }
        assertThrows(IllegalArgumentException::class.java) { BackendYoutubeAccessJsonParser.parse(response().put("expires_at", now.toString()), now) }
    }

    @Test fun selectedTransportMatchesActualFamilyAndUsesItsOwnCredential() {
        for ((kind, transport, security, mode) in listOf(
            listOf("vless", "tcp", "tls", ""), listOf("vless", "tcp", "reality", ""),
            listOf("vless", "xhttp", "reality", "stream-up"), listOf("hysteria", "hysteria", "tls", ""))) {
            val endpoint = endpoint().put("proxy_type", kind).put("transport", transport).put("security", security)
                .put("transport_mode", mode).put("path", if (transport == "xhttp") "/stream" else "/")
                .put("public_key", if (security == "reality") "reality-key" else JSONObject.NULL)
                .put("short_id", if (security == "reality") "abcdef" else JSONObject.NULL)
            val payload = response("selected_transport").put("endpoint", endpoint)
            val grant = BackendYoutubeAccessJsonParser.parse(payload, now)
            val primary = EndpointSelector.profileFromCandidate(checkNotNull(grant.endpoint), "primary-secret")
                .copy(host = "main.example.test", endpointCode = "main-exact-endpoint")
            val runtime = YoutubeConnection.runtimeConfig(grant, primary, keyId, ByteArray(32)) as YoutubeConnectionConfig.SelectedTransport
            assertEquals(grant.vpnSecret, runtime.profile.uuid)
            assertEquals("youtube.example.test", runtime.profile.host)
            assertThrows(IllegalArgumentException::class.java) { YoutubeConnection.runtimeConfig(grant, null, keyId, ByteArray(32)) }
            assertThrows(IllegalArgumentException::class.java) {
                YoutubeConnection.runtimeConfig(grant, primary.copy(transport = "unsupported"), keyId, ByteArray(32))
            }
        }
    }

    @Test fun identicalRenewalDoesNotRequestAnotherRuntimeReplacement() {
        val grant = BackendYoutubeAccessJsonParser.parse(response(), now)
        val a = YoutubeConnection.runtimeConfig(grant, null, keyId, ByteArray(32) { 9 })
        val b = YoutubeConnection.runtimeConfig(grant.copy(generation = 2, expiresAtEpochMillis = grant.expiresAtEpochMillis + 80_000),
            null, keyId, ByteArray(32) { 9 })
        assertTrue(YoutubeConnection.sameConfiguration(a, b))
        assertFalse(YoutubeConnection.sameConfiguration(a, null))
        assertEquals(80_000L, YoutubeConnection.renewDelayMillis(now.plusSeconds(120).toEpochMilli(), now.toEpochMilli()))
    }

    @Test fun expiredBlockedCreateResponseRequiresFreshAdmissionWhileLiveMaterialStillExpires() {
        val expired = response(status = "blocked").put("expires_at", now.minusSeconds(1).toString())
        val blocked = BackendYoutubeAccessJsonParser.parse(expired, now)
        assertTrue(YoutubeConnection.needsFreshAdmission(blocked, now.toEpochMilli()))
        assertFalse(YoutubeConnection.needsFreshAdmission(blocked.copy(expiresAtEpochMillis = now.plusSeconds(1).toEpochMilli()), now.toEpochMilli()))
        for (status in listOf("pending", "ready")) assertThrows(IllegalArgumentException::class.java) {
            BackendYoutubeAccessJsonParser.parse(response(status = status).put("expires_at", now.minusSeconds(1).toString()), now)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..5).map(YoutubeConnection::transientRetryDelayMillis))
    }

    @Test fun apiUsesSeparateGrantAndCapabilityRevocation() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        var nonceNumber = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = when {
                chain.request().url.encodedPath.endsWith("/devices/challenge") ->
                    JSONObject().put("device_id", id).put("nonce", "fresh-nonce-${++nonceNumber}").toString()
                chain.request().method == "DELETE" -> "{}"
                else -> response(time = Instant.now()).toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example", challengeSigner = { "signed:$it" })
        val grant = api.createYoutubeAccess("access", id, id, "proof", "nonce", "signature", keyId, "main-endpoint")
        api.pollYoutubeAccess("access", id, "proof", grant.grantId)
        api.renewYoutubeAccess("access", id, "proof", grant)
        api.revokeYoutubeAccess(grant.grantId, grant.revokeToken)
        assertEquals(listOf("POST", "POST", "GET", "POST", "POST", "DELETE"), requests.map { it.method })
        assertEquals("/v1/vpn/youtube-access", requests[0].url.encodedPath)
        val create = JSONObject(Buffer().also { checkNotNull(requests[0].body).writeTo(it) }.readUtf8())
        assertEquals(keyId, create.getString("public_key_id"))
        assertEquals("main-endpoint", create.getString("primary_endpoint_code"))
        assertFalse(create.has("private_key"))
        assertEquals(id, requests[2].url.queryParameter("device_id"))
        assertEquals("fresh-nonce-1", requests[2].header("X-Device-Nonce"))
        assertEquals("signed:fresh-nonce-1", requests[2].header("X-Device-Signature"))
        assertEquals("proof", requests[4].header("X-Device-Key"))
        assertEquals("/v1/vpn/youtube-access/$id/renew", requests[4].url.encodedPath)
        assertEquals("fresh-nonce-2", requests[4].header("X-Device-Nonce"))
        assertEquals("signed:fresh-nonce-2", requests[4].header("X-Device-Signature"))
        for (request in listOf(requests[1], requests[3])) {
            assertEquals("Bearer access", request.header("Authorization"))
            assertEquals(id, JSONObject(Buffer().also { checkNotNull(request.body).writeTo(it) }.readUtf8()).getString("device_id"))
        }
        assertEquals("capability", requests[5].header("X-Youtube-Revoke-Token"))
        assertNull(requests[5].header("Authorization"))
    }

    @Test fun retryAfterLostRenewResponseUsesAnotherSignedChallenge() = runBlocking {
        var nonceNumber = 0
        val renewals = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/devices/challenge")) {
                JSONObject().put("device_id", id).put("nonce", "retry-nonce-${++nonceNumber}").toString()
            } else {
                renewals += request
                if (renewals.size == 1) throw java.io.IOException("response lost")
                response(time = Instant.now()).put("generation", 2).put("status", "pending")
                    .apply { remove("wireguard"); remove("public_key_id") }.toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example", challengeSigner = { "signed:$it" })
        val grant = BackendYoutubeAccessJsonParser.parse(response(), now)
        assertTrue(runCatching { api.renewYoutubeAccess("access", id, "proof", grant) }.exceptionOrNull() is java.io.IOException)
        val retried = api.renewYoutubeAccess("access", id, "proof", grant)
        assertEquals(2, retried.generation)
        assertEquals(listOf("retry-nonce-1", "retry-nonce-2"), renewals.map { it.header("X-Device-Nonce") })
        assertEquals(listOf("signed:retry-nonce-1", "signed:retry-nonce-2"), renewals.map { it.header("X-Device-Signature") })
        assertTrue(renewals.all { JSONObject(Buffer().also { sink -> checkNotNull(it.body).writeTo(sink) }.readUtf8()).getInt("generation") == 1 })
    }

    @Test fun mainAndYoutubeProofsCannotOverwriteTheSingleBackendChallenge() = runBlocking {
        val issuedMain = CompletableDeferred<Unit>()
        val releaseMain = CompletableDeferred<Unit>()
        val issuedYoutube = CompletableDeferred<Unit>()
        val nonceNumber = java.util.concurrent.atomic.AtomicInteger()
        val currentNonce = java.util.concurrent.atomic.AtomicReference<String?>()
        val consumed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/devices/challenge")) {
                val nonce = "single-nonce-${nonceNumber.incrementAndGet()}"
                currentNonce.set(nonce)
                if (nonceNumber.get() == 2) issuedYoutube.complete(Unit)
                JSONObject().put("device_id", id).put("nonce", nonce).toString()
            } else {
                val nonce = if (request.method == "POST") JSONObject(Buffer().also { checkNotNull(request.body).writeTo(it) }
                    .readUtf8()).getString("device_nonce") else request.header("X-Device-Nonce")
                val active = currentNonce.get()
                check(active != null && active == nonce && currentNonce.compareAndSet(active, null)) { "Device challenge expired" }
                consumed += checkNotNull(nonce)
                response(time = Instant.now()).toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example", challengeSigner = { "signed:$it" })
        val main = async(start = CoroutineStart.UNDISPATCHED) {
            DeviceIdentity.withChallengeTransaction {
                val proof = api.createDeviceChallenge("access", id)
                issuedMain.complete(Unit)
                releaseMain.await()
                api.createYoutubeAccess("access", id, id, "proof", proof.nonce, "signed:${proof.nonce}", keyId, "main-endpoint")
            }
        }
        withTimeout(5_000) { issuedMain.await() }
        val youtube = async(start = CoroutineStart.UNDISPATCHED) { api.pollYoutubeAccess("access", id, "proof", id) }
        try {
            assertNull(withTimeoutOrNull(100) { issuedYoutube.await() })
        } finally {
            releaseMain.complete(Unit)
        }
        withTimeout(5_000) { main.await(); youtube.await() }
        assertEquals(listOf("single-nonce-1", "single-nonce-2"), consumed.toList())
    }

    @Test fun challengeTransactionUnlocksAfterFailureAndCancellation() = runBlocking {
        assertTrue(runCatching { DeviceIdentity.withChallengeTransaction { error("consume failed") } }.isFailure)
        val entered = CompletableDeferred<Unit>()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            DeviceIdentity.withChallengeTransaction {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        cancelled.cancelAndJoin()
        assertEquals("released", withTimeout(1_000) { DeviceIdentity.withChallengeTransaction { "released" } })
    }
}
