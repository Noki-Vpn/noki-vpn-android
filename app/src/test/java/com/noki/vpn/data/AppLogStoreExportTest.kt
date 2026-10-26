package com.noki.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AppLogStoreExportTest {
    private val header = "Noki diagnostic log\n"

    private fun entry(index: Int, details: String? = null) = AppLogEntry(
        timestamp = Instant.parse("2026-10-26T00:00:00Z").plusSeconds(index.toLong()).toString(),
        level = "info",
        category = "vpn",
        message = "event_$index",
        details = details,
    )

    @Test
    fun `latest incident survives upload budget with whole chronological events`() {
        val logs = (0 until 100).map { index ->
            entry(index, "begin_$index\n" + "x".repeat(7_000) + "\nend_$index")
        }
        val exported = AppLogStore.export(logs.reversed())

        assertTrue(exported.length <= AppLogStore.EXPORT_MAX_CHARS)
        assertTrue(exported.startsWith(header))
        assertFalse(exported.contains("[INFO] vpn: event_0\n"))
        assertTrue(exported.contains("[INFO] vpn: event_99\n"))
        val selected = logs.filter { exported.contains("[INFO] vpn: ${it.message}\n") }
        assertTrue(selected.size > 1)
        assertEquals(logs.takeLast(selected.size), selected)
        assertEquals(header + selected.joinToString("") { AppLogStore.export(listOf(it)).removePrefix(header) }, exported)
        assertTrue(exported.endsWith("  end_99\n"))
    }

    @Test
    fun `header and exact event boundary count toward limit`() {
        val older = entry(0)
        val newest = entry(1, "x")
        val baseLength = AppLogStore.export(listOf(older, newest)).length
        val exact = newest.copy(details = "x".repeat(1 + AppLogStore.EXPORT_MAX_CHARS - baseLength))

        val exported = AppLogStore.export(listOf(older, exact))
        assertEquals(AppLogStore.EXPORT_MAX_CHARS, exported.length)
        assertTrue(exported.contains("[INFO] vpn: event_0\n"))
        assertEquals(
            AppLogStore.export(listOf(exact.copy(details = exact.details + "x"))),
            AppLogStore.export(listOf(older, exact.copy(details = exact.details + "x"))),
        )
    }

    @Test
    fun `oversized newest event is explicitly bounded without splitting surrogate`() {
        val newest = entry(1, "😀".repeat(AppLogStore.EXPORT_MAX_CHARS))
        val exported = AppLogStore.export(listOf(entry(0), newest))

        assertTrue(exported.length <= AppLogStore.EXPORT_MAX_CHARS)
        assertTrue(exported.contains("[INFO] vpn: event_1\n"))
        assertFalse(exported.contains("[INFO] vpn: event_0\n"))
        assertTrue(exported.endsWith("\n  [event truncated: export limit]\n"))
        val body = exported.substringBefore("\n  [event truncated:")
        assertFalse(body.last().isHighSurrogate())
    }

    @Test
    fun `empty export remains readable`() {
        assertEquals(header + "(no local events yet)\n", AppLogStore.export(emptyList()))
    }

    @Test
    fun `export redacts all event fields before sizing`() {
        val secret = "private-export-value"
        val value = "token=$secret"
        val log = entry(0, "line one\nAuthorization: Bearer $secret").copy(
            message = value,
            appVersion = value,
            androidVersion = value,
            deviceModel = value,
            errorType = value,
            serverCountry = value,
            endpointRating = value,
            apiResponseTimeMs = 7,
            connectionSuccess = false,
        )
        val exported = AppLogStore.export(listOf(log))
        assertFalse(exported.contains(secret))
        assertTrue(exported.contains("token=<redacted>"))
        assertTrue(exported.contains("  line one\n  Authorization: Bearer <redacted>\n"))
        assertTrue(exported.contains("api_response_ms=7"))
        assertTrue(exported.contains("connection_success=false"))

        val baseLength = exported.length
        val exact = log.copy(details = log.details + "\n" + "x".repeat(AppLogStore.EXPORT_MAX_CHARS - baseLength - 3))
        assertEquals(AppLogStore.EXPORT_MAX_CHARS, AppLogStore.export(listOf(exact)).length)
        assertFalse(AppLogStore.export(listOf(exact)).contains("[event truncated:"))
    }
}
