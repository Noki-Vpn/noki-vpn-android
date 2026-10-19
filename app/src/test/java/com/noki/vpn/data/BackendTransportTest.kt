package com.noki.vpn.data

import com.noki.vpn.BuildConfig
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendTransportTest {
    @Test
    fun `device traffic pools travel independently of account access rights`() = runBlocking {
        val requests = mutableListOf<Request>()
        val deviceJson = """{"id":"device","device_key":"key","device_name":"Phone","platform":"android",
            "access_role":"owner","is_active":true,"traffic_pool":"shared","name":"Laptop"}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val sent = chain.request()
            requests += sent
            val response = if (sent.url.encodedPath == "/v1/devices/device/traffic-pool") "[$deviceJson]" else deviceJson
            Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")

        val native = api.setDeviceTrafficPool("access", "device", "shared", "current", "proof").single()
        assertEquals("shared", native.trafficPool)
        assertEquals("owner", native.accessRole)
        assertEquals("shared", api.setIncyDeviceTrafficPool("access", "device", "shared").trafficPool)

        assertEquals(listOf("PATCH", "PATCH"), requests.map { it.method })
        assertEquals(listOf("/v1/devices/device/traffic-pool", "/v1/incy/devices/device/traffic-pool"),
            requests.map { it.url.encodedPath })
        for (sent in requests) {
            val payload = Buffer().also { sent.body!!.writeTo(it) }.readUtf8().let(::JSONObject)
            assertEquals("shared", payload.getString("traffic_pool"))
            assertFalse(payload.has("access_role"))
            assertEquals("Bearer access", sent.header("Authorization"))
        }
        assertEquals("current", requests.first().header("X-Device-Id"))
        assertEquals("proof", requests.first().header("X-Device-Key"))
    }

    @Test
    fun `device creation omits traffic pool and accepts server assigned primary or shared`() = runBlocking {
        for (assignedPool in listOf("primary", "shared")) {
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val sent = chain.request()
                requests += sent
                val response = if (sent.url.encodedPath == "/v1/devices/invites") {
                    """{"invite_code":"ABC123","expires_at":"2026-10-19T12:00:00Z"}"""
                } else {
                    """{"id":"device","name":"Laptop","traffic_pool":"$assignedPool",
                        "import_link":"incy://crypt1/test"}"""
                }
                Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = BackendApiClient(client, "https://api.example")

            assertEquals("ABC123", api.createDeviceInvite("access", "current", "proof").inviteCode)
            assertEquals(assignedPool, api.createIncyDevice("access", "Laptop").device.trafficPool)

            assertEquals(listOf("/v1/devices/invites", "/v1/incy/devices"), requests.map { it.url.encodedPath })
            requests.forEach { sent ->
                assertEquals("POST", sent.method)
                assertEquals("Bearer access", sent.header("Authorization"))
                val payload = Buffer().also { sent.body!!.writeTo(it) }.readUtf8().let(::JSONObject)
                assertFalse(payload.has("traffic_pool"))
                val expectedKeys = if (sent.url.encodedPath == "/v1/incy/devices") setOf("name") else emptySet()
                assertEquals(expectedKeys, payload.keys().asSequence().toSet())
            }
            assertEquals("current", requests.first().header("X-Device-Id"))
            assertEquals("proof", requests.first().header("X-Device-Key"))
        }
    }

    @Test
    fun `account password codes use private purpose verify and change with device proof`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"cooldown_seconds":42,"verified":true,"user":{"id":"user-1","username":"owner",
                    "email":"owner@example.com","is_active":true,"is_admin":false,"has_password":true}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")

        assertEquals(42, api.sendAccountEmailCode(
            token = "access", email = "owner@example.com", currentDeviceId = "device", currentDeviceKey = "proof",
            currentEmail = null, currentVerificationCode = null, purpose = "account_password_change",
        ))
        api.verifyAccountPasswordCode("access", "owner@example.com", "123456", "device", "proof")
        val changed = api.changeAccountPassword("access", null, "new-password", "device", "proof", "123456")
        assertTrue(changed.user.hasPassword)

        assertEquals(listOf("/v1/app/security/email-code", "/v1/app/security/password/verify-code",
            "/v1/app/security/password"), requests.map { it.url.encodedPath })
        requests.forEach { sent ->
            assertEquals("POST", sent.method)
            assertEquals("Bearer access", sent.header("Authorization"))
            assertEquals("device", sent.header("X-Device-Id"))
            assertEquals("proof", sent.header("X-Device-Key"))
        }
        val send = Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8().let(::JSONObject)
        assertEquals(setOf("email", "purpose"), send.keys().asSequence().toSet())
        assertEquals("owner@example.com", send.getString("email"))
        assertEquals("account_password_change", send.getString("purpose"))
        val verify = Buffer().also { requests[1].body!!.writeTo(it) }.readUtf8().let(::JSONObject)
        assertEquals(setOf("email", "verification_code"), verify.keys().asSequence().toSet())
        assertEquals("owner@example.com", verify.getString("email"))
        assertEquals("123456", verify.getString("verification_code"))
        val change = Buffer().also { requests[2].body!!.writeTo(it) }.readUtf8().let(::JSONObject)
        assertEquals(setOf("new_password", "verification_code"), change.keys().asSequence().toSet())
        assertEquals("new-password", change.getString("new_password"))
        assertEquals("123456", change.getString("verification_code"))
    }

    @Test
    fun `anonymous password recovery keeps its public purpose and routes`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")

        api.sendPasswordRecoveryCode("owner@example.com")
        api.verifyPasswordRecoveryCode("owner@example.com", "123456")
        api.resetPassword("owner@example.com", "123456", "new-password")

        assertEquals(listOf("/v1/auth/email-code/send", "/v1/auth/email-code/verify",
            "/v1/auth/password-recovery/reset"), requests.map { it.url.encodedPath })
        requests.forEach { assertEquals(null, it.header("Authorization")) }
        requests.take(2).forEach { sent ->
            val body = Buffer().also { sent.body!!.writeTo(it) }.readUtf8().let(::JSONObject)
            assertEquals("password_recovery", body.getString("purpose"))
        }
    }

    @Test
    fun `current email change codes use authenticated security send and verification routes`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"cooldown_seconds":42,"verified":true}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")

        assertEquals(42, api.sendAccountEmailCode(
            token = "access", email = "current@example.com", currentDeviceId = "device", currentDeviceKey = "proof",
            currentEmail = null, currentVerificationCode = null, purpose = "account_email_change_current",
        ))
        api.verifyCurrentAccountEmailCode("access", "current@example.com", "123456", "device", "proof")
        assertEquals(42, api.sendAccountEmailCode(
            token = "access", email = "new@example.com", currentDeviceId = "device", currentDeviceKey = "proof",
            currentEmail = "current@example.com", currentVerificationCode = "123456",
        ))

        assertEquals(3, requests.size)
        requests.forEach { sent ->
            assertEquals("POST", sent.method)
            assertEquals("Bearer access", sent.header("Authorization"))
            assertEquals("device", sent.header("X-Device-Id"))
            assertEquals("proof", sent.header("X-Device-Key"))
        }
        val send = requests[0]
        val sendBody = Buffer().also { send.body!!.writeTo(it) }.readUtf8().let(::JSONObject)
        assertEquals("/v1/app/security/email-code", send.url.encodedPath)
        assertEquals(setOf("email", "purpose"), sendBody.keys().asSequence().toSet())
        assertEquals("current@example.com", sendBody.getString("email"))
        assertEquals("account_email_change_current", sendBody.getString("purpose"))

        val verify = requests[1]
        val verifyBody = Buffer().also { verify.body!!.writeTo(it) }.readUtf8().let(::JSONObject)
        assertEquals("/v1/app/security/email/verify-current", verify.url.encodedPath)
        assertEquals(setOf("email", "verification_code"), verifyBody.keys().asSequence().toSet())
        assertEquals("current@example.com", verifyBody.getString("email"))
        assertEquals("123456", verifyBody.getString("verification_code"))

        val newEmail = requests[2]
        val newBody = Buffer().also { newEmail.body!!.writeTo(it) }.readUtf8().let(::JSONObject)
        assertEquals("/v1/app/security/email-code", newEmail.url.encodedPath)
        assertEquals(setOf("email", "current_email", "current_verification_code"), newBody.keys().asSequence().toSet())
        assertEquals("new@example.com", newBody.getString("email"))
        assertEquals("current@example.com", newBody.getString("current_email"))
        assertEquals("123456", newBody.getString("current_verification_code"))
    }

    @Test
    fun `username availability uses the pending account token and keeps anonymous registration anonymous`() = runBlocking {
        for (token in listOf(null, "pending-access")) {
            var request: Request? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                request = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"available":true}""".toResponseBody("application/json".toMediaType())).build()
            }.build()

            assertTrue(BackendApiClient(client, "https://api.example").checkRegistrationUsernameAvailable("Chosen.Name", token))
            val sent = checkNotNull(request)
            assertEquals("GET", sent.method)
            assertEquals("/v1/auth/registration/username/check", sent.url.encodedPath)
            assertEquals("Chosen.Name", sent.url.queryParameter("username"))
            assertEquals(token?.let { "Bearer $it" }, sent.header("Authorization"))
        }
    }

    @Test
    fun `credentials setup atomically sends the pair with account device proof and parses plain user`() = runBlocking {
        var request: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"user-1","username":"chosen_name","email":"tg_12345678901234@a.noki",
                    "is_active":true,"is_admin":false,"has_password":true,"credentials_setup_required":false}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()

        val user = BackendApiClient(client, "https://api.example").setupAccountCredentials(
            "access", "Chosen.Name", "secret123", "device", "proof",
        )
        val sent = checkNotNull(request)
        val body = Buffer().also { sent.body!!.writeTo(it) }.readUtf8().let(::JSONObject)

        assertEquals("POST", sent.method)
        assertEquals("/v1/app/security/credentials", sent.url.encodedPath)
        assertEquals("Bearer access", sent.header("Authorization"))
        assertEquals("device", sent.header("X-Device-Id"))
        assertEquals("proof", sent.header("X-Device-Key"))
        assertEquals(setOf("username", "password"), body.keys().asSequence().toSet())
        assertEquals("Chosen.Name", body.getString("username"))
        assertEquals("secret123", body.getString("password"))
        assertEquals("chosen_name", user.username)
        assertTrue(user.hasPassword)
        assertFalse(user.credentialsSetupRequired)
    }

    @Test
    fun `VPN access sends the release version with authenticated device proof`() = runBlocking {
        var request: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"can_connect\":true}".toResponseBody("application/json".toMediaType())).build()
        }.build()

        val access = BackendApiClient(client, "https://api.example").vpnAccess("access", "device", "proof")
        val sent = checkNotNull(request)

        assertTrue(access.canConnect)
        assertEquals("/v1/vpn/access", sent.url.encodedPath)
        assertEquals("Bearer access", sent.header("Authorization"))
        assertEquals("device", sent.header("X-Device-Id"))
        assertEquals("proof", sent.header("X-Device-Key"))
        assertEquals(BuildConfig.VERSION_CODE.toString(), sent.header("X-Noki-Version-Code"))
    }

    @Test
    fun `backend control plane and streaming retain default protocols`() {
        val controlPlane = defaultBackendControlPlaneClient()
        val streaming = backendStreamingClient(controlPlane)

        assertEquals(OkHttpClient().protocols, controlPlane.protocols)
        assertEquals(controlPlane.protocols, streaming.protocols)
    }

    @Test
    fun `control plane and streaming both have finite total deadlines`() {
        val controlPlane = defaultBackendControlPlaneClient()
        val streaming = backendStreamingClient(controlPlane)

        assertEquals(30_000, controlPlane.callTimeoutMillis)
        assertEquals(30 * 60 * 1_000, streaming.callTimeoutMillis)
        assertEquals(controlPlane.connectTimeoutMillis, streaming.connectTimeoutMillis)
        assertEquals(controlPlane.readTimeoutMillis, streaming.readTimeoutMillis)
        assertEquals(controlPlane.writeTimeoutMillis, streaming.writeTimeoutMillis)
    }

    @Test
    fun `object array and stream expose identical backend error`() {
        val transport = BackendTransport(errorClient(), "https://api.example.com/v1")
        val request = Request.Builder().url("https://api.example.com/v1/test").build()

        val errors = listOf(
            assertBackendError { runBlocking { transport.jsonObject(request) } },
            assertBackendError { runBlocking { transport.jsonArray(request) } },
            assertBackendError { runBlocking { transport.stream(request) {} } },
        )

        errors.forEach {
            assertEquals(422, it.statusCode)
            assertEquals(3_000L, it.retryAfterMillis)
            assertEquals("email: invalid", it.message)
        }
    }

    @Test
    fun `request outside configured origin or path is rejected before network`() {
        val transport = BackendTransport(OkHttpClient(), "https://api.example.com/v1")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transport.jsonObject(Request.Builder().url("https://evil.example/v1/test").build()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transport.jsonObject(Request.Builder().url("https://api.example.com/other").build()) }
        }
    }

    @Test
    fun `cancelling coroutine cancels active okhttp call`() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val releaseInterceptor = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requestStarted.countDown()
                while (!releaseInterceptor.get()) {
                    if (chain.call().isCanceled()) {
                        callCancelled.countDown()
                        throw IOException("cancelled")
                    }
                    Thread.sleep(5L)
                }
                throw IOException("test interceptor released")
            })
            .build()
        val transport = BackendTransport(client, "https://api.example.com/v1")
        val request = Request.Builder().url("https://api.example.com/v1/slow").build()
        val requestJob = launch(Dispatchers.IO) {
            runCatching { transport.jsonObject(request) }
        }

        assertTrue("The real OkHttp call did not start", requestStarted.await(1L, TimeUnit.SECONDS))
        requestJob.cancel()
        val cancellationReachedCall = try {
            callCancelled.await(1L, TimeUnit.SECONDS)
        } finally {
            releaseInterceptor.set(true)
            requestJob.cancelAndJoin()
        }

        assertTrue("Coroutine cancellation must cancel the active OkHttp call", cancellationReachedCall)
    }

    @Test
    fun `declared oversized json response is rejected`() {
        val body = ByteArray(MAX_JSON_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
            .toResponseBody("application/json".toMediaType())
        val transport = BackendTransport(responseClient(body), "https://api.example.com/v1")
        val request = Request.Builder().url("https://api.example.com/v1/oversized").build()

        val failure = assertThrows(BackendException::class.java) {
            runBlocking { transport.jsonObject(request) }
        }

        assertEquals("Backend response is too large", failure.message)
    }

    @Test
    fun `streamed oversized json response is rejected when length is unknown`() {
        val bytes = ByteArray(MAX_JSON_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
        val body = object : ResponseBody() {
            private val content = Buffer().write(bytes)

            override fun contentType() = "application/json".toMediaType()

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = content
        }
        val transport = BackendTransport(responseClient(body), "https://api.example.com/v1")
        val request = Request.Builder().url("https://api.example.com/v1/oversized").build()

        val failure = assertThrows(BackendException::class.java) {
            runBlocking { transport.jsonObject(request) }
        }

        assertEquals("Backend response is too large", failure.message)
    }

    private fun assertBackendError(block: () -> Unit): BackendException =
        assertThrows(BackendException::class.java, block)

    private fun errorClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(422)
                .message("validation")
                .header("Retry-After", "3")
                .body(
                    """{"detail":[{"loc":["body","email"],"msg":"invalid"}]}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        })
        .build()

    private fun responseClient(body: ResponseBody): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        .build()

    private companion object {
        const val MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
