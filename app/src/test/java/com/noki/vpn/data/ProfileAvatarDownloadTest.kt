package com.noki.vpn.data

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class ProfileAvatarDownloadTest {
    @Test
    fun avatarCacheWaitIsBoundedButExternalCancellationStillPropagates() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val coordinator = com.noki.vpn.ProfileAvatarCoordinator(context, backendApi(responseBody(byteArrayOf())))
        val mutex = coordinator.javaClass.getDeclaredField("cacheMutex").apply { isAccessible = true }
            .get(coordinator) as kotlinx.coroutines.sync.Mutex
        mutex.lock()
        try {
            val fallback = "file:///cached-avatar.jpg"
            assertEquals(fallback, kotlinx.coroutines.withTimeout(8_000) {
                coordinator.cachedBackendAvatarUri("token", AVATAR_URL, fallback)
            })
            assertEquals(null, coordinator.cachedBackendAvatarUri("token", null, fallback))
            val failure = runCatching { kotlinx.coroutines.withTimeout(50) {
                coordinator.cachedBackendAvatarUri("token", AVATAR_URL, fallback)
            } }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        } finally { mutex.unlock() }
    }

    @Test
    fun avatarUnauthorizedResponseReachesTheAuthOwner() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("expired")
                .body(okhttp3.ResponseBody.create("application/json".toMediaType(), "{\"detail\":\"expired\"}")).build()
        }.build()
        val api = BackendApiClient(client, BASE_URL)
        val coordinator = com.noki.vpn.ProfileAvatarCoordinator(context, api)
        val failure = runCatching { coordinator.cachedBackendAvatarUri("expired", AVATAR_URL, null) }.exceptionOrNull()
        assertEquals(401, (failure as? BackendException)?.statusCode)
    }
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun uploadRejectsPayloadAboveBackendOneMebibyteContract() {
        val failure = runCatching {
            runBlocking {
                backendApi(responseBody(byteArrayOf())).uploadAvatar(
                    token = "token",
                    fileName = "avatar.jpg",
                    mimeType = "image/jpeg",
                    bytes = ByteArray(MAX_AVATAR_UPLOAD_BYTES.toInt() + 1),
                )
            }
        }.exceptionOrNull()

        assertAvatarTooLarge(failure)
    }

    @Test
    fun streamFailurePreservesExistingAvatarAndRemovesPartFile() {
        val destination = seededDestination()
        val failure = IOException("stream failed")

        val attempt = download(
            destination = destination,
            body = responseBody(
                bytes = "new-avatar".toByteArray(),
                declaredLength = -1L,
                failAfterBytes = 4,
                failure = failure,
            ),
        )

        assertFailurePreservesCachedAvatar(destination, attempt)
        assertEquals(IOException::class.java, attempt.failure?.javaClass)
        assertEquals(failure.message, attempt.failure?.message)
    }

    @Test
    fun declaredOversizePreservesExistingAvatarAndRemovesPartFile() {
        val destination = seededDestination()

        val attempt = download(
            destination = destination,
            body = responseBody(
                bytes = "too-large".toByteArray(),
                declaredLength = MAX_AVATAR_BYTES + 1L,
            ),
        )

        assertFailurePreservesCachedAvatar(destination, attempt)
        assertAvatarTooLarge(attempt.failure)
    }

    @Test
    fun streamedOversizePreservesExistingAvatarAndRemovesPartFile() {
        val destination = seededDestination()

        val attempt = download(
            destination = destination,
            body = responseBody(
                bytes = ByteArray(MAX_AVATAR_BYTES.toInt() + 1) { 1 },
                declaredLength = -1L,
            ),
        )

        assertFailurePreservesCachedAvatar(destination, attempt)
        assertAvatarTooLarge(attempt.failure)
    }

    @Test
    fun zeroByteResponsePreservesExistingAvatarAndRemovesPartFile() {
        val destination = seededDestination()

        val attempt = download(
            destination = destination,
            body = responseBody(bytes = byteArrayOf(), declaredLength = 0L),
        )

        assertFailurePreservesCachedAvatar(destination, attempt)
        assertNotNull("zero-byte avatar must be rejected", attempt.failure)
    }

    @Test
    fun cancellationPreservesExistingAvatarAndRemovesPartFile() {
        val destination = seededDestination()
        val cancellation = CancellationException("cancelled")
        val partialWriteCompleted = CountDownLatch(1)
        val continueReading = CountDownLatch(1)
        val api = backendApi(
            responseBody(
                bytes = "new-avatar".toByteArray(),
                declaredLength = -1L,
                pause = ReadPause(
                    afterBytes = 4L,
                    reached = partialWriteCompleted,
                    release = continueReading,
                ),
            ),
        )

        val attempt = runBlocking {
            val deferred = async(Dispatchers.Default) {
                api.downloadProfileAvatar(
                    token = "token",
                    avatarUrl = AVATAR_URL,
                    destination = destination,
                )
            }
            try {
                assertTrue(
                    "download must reach a partial temp write",
                    partialWriteCompleted.await(10, TimeUnit.SECONDS),
                )
                deferred.cancel(cancellation)
            } finally {
                continueReading.countDown()
            }
            deferred.join()
            DownloadAttempt(
                result = null,
                failure = runCatching { deferred.await() }.exceptionOrNull(),
            )
        }

        assertFailurePreservesCachedAvatar(destination, attempt)
        assertEquals(
            "cancellation must not be reclassified",
            CancellationException::class.java,
            attempt.failure?.javaClass,
        )
        assertEquals(cancellation.message, attempt.failure?.message)
    }

    @Test
    fun successfulDownloadReplacesExistingAvatarAndRemovesPartFile() {
        val destination = seededDestination()
        val replacement = "new-avatar".toByteArray()

        val attempt = download(
            destination = destination,
            body = responseBody(bytes = replacement),
        )

        assertEquals(null, attempt.failure)
        assertSame(destination, attempt.result)
        assertArrayEquals(replacement, destination.readBytes())
        assertFalse(partFile(destination).exists())
    }

    private fun seededDestination(): File = temporaryFolder.newFile("avatar.jpg").apply {
        writeBytes(OLD_AVATAR)
    }

    private fun download(
        destination: File,
        body: ResponseBody,
    ): DownloadAttempt {
        val api = backendApi(body)
        var result: Any? = null
        var failure: Throwable? = null
        try {
            result = runBlocking {
                api.downloadProfileAvatar(
                    token = "token",
                    avatarUrl = AVATAR_URL,
                    destination = destination,
                )
            }
        } catch (error: Throwable) {
            failure = error
        }
        return DownloadAttempt(result = result, failure = failure)
    }

    private fun backendApi(body: ResponseBody): BackendApiClient =
        BackendApiClient(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build()
                }
                .build(),
            baseUrl = BASE_URL,
        )

    private fun assertFailurePreservesCachedAvatar(
        destination: File,
        attempt: DownloadAttempt,
    ) {
        assertFalse("temporary file must be removed", partFile(destination).exists())
        assertTrue("stable avatar must remain present", destination.exists())
        assertArrayEquals(OLD_AVATAR, destination.readBytes())
        assertNotNull("download must fail", attempt.failure)
    }

    private fun assertAvatarTooLarge(failure: Throwable?) {
        assertTrue(failure is IllegalArgumentException)
        assertEquals("avatar_file_too_large", failure?.message)
    }

    private fun partFile(destination: File): File =
        File(destination.parentFile, "${destination.name}.part")

    private fun responseBody(
        bytes: ByteArray,
        declaredLength: Long = bytes.size.toLong(),
        failAfterBytes: Int? = null,
        failure: Throwable? = null,
        pause: ReadPause? = null,
    ): ResponseBody {
        val source = object : Source {
            private val content = Buffer().write(bytes)
            private var bytesRead = 0L
            private var paused = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (failAfterBytes != null && bytesRead >= failAfterBytes) {
                    throw requireNotNull(failure)
                }
                if (!paused && pause != null && bytesRead >= pause.afterBytes) {
                    paused = true
                    pause.reached.countDown()
                    check(pause.release.await(10, TimeUnit.SECONDS)) {
                        "timed out waiting to continue avatar download"
                    }
                }
                if (content.exhausted()) return -1L
                val remainingBeforeFailure = failAfterBytes
                    ?.let { it.toLong() - bytesRead }
                    ?: byteCount
                val remainingBeforePause = pause
                    ?.takeUnless { paused }
                    ?.let { it.afterBytes - bytesRead }
                    ?: byteCount
                val read = content.read(
                    sink,
                    minOf(byteCount, remainingBeforeFailure, remainingBeforePause),
                )
                if (read > 0L) bytesRead += read
                return read
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()
        return object : ResponseBody() {
            override fun contentType(): MediaType = "image/jpeg".toMediaType()

            override fun contentLength(): Long = declaredLength

            override fun source(): BufferedSource = source
        }
    }

    private data class DownloadAttempt(
        val result: Any?,
        val failure: Throwable?,
    )

    private data class ReadPause(
        val afterBytes: Long,
        val reached: CountDownLatch,
        val release: CountDownLatch,
    )

    private companion object {
        const val BASE_URL = "https://example.test"
        const val AVATAR_URL = "$BASE_URL/v1/app/profile/avatar/avatar.jpg"
        const val MAX_AVATAR_BYTES = 5L * 1024L * 1024L
        const val MAX_AVATAR_UPLOAD_BYTES = 1L * 1024L * 1024L
        val OLD_AVATAR = "old-avatar".toByteArray()
    }
}
