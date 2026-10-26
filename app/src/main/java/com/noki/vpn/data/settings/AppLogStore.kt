package com.noki.vpn.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

internal object AppLogStore {
    const val EXPORT_MAX_CHARS = 200_000

    fun export(logs: List<AppLogEntry>): String {
        if (logs.isEmpty()) return EXPORT_HEADER + "(no local events yet)\n"
        val blocks = ArrayDeque<String>()
        var remaining = EXPORT_MAX_CHARS - EXPORT_HEADER.length
        for (entry in logs.sortedBy { it.timestamp }.asReversed()) {
            val block = exportBlock(entry)
            if (block.length > remaining) {
                if (blocks.isEmpty()) {
                    var end = remaining - OVERSIZED_EVENT_MARKER.length
                    if (block[end - 1].isHighSurrogate() && block[end].isLowSurrogate()) end--
                    blocks.addFirst(block.take(end) + OVERSIZED_EVENT_MARKER)
                }
                break
            }
            blocks.addFirst(block)
            remaining -= block.length
        }
        return EXPORT_HEADER + blocks.joinToString("")
    }

    private fun exportBlock(entry: AppLogEntry): String = buildString {
        append(entry.timestamp)
        append(" [")
        append(entry.level.uppercase(Locale.ROOT))
        append("] ")
        append(entry.category)
        append(": ")
        appendLine(AppDiagnosticRedactor.redact(entry.message))
        entry.details?.takeIf { it.isNotBlank() }?.let { details ->
            append("  ")
            appendLine(AppDiagnosticRedactor.redact(details).replace("\n", "\n  "))
        }
        val metadata = buildList {
            entry.appVersion?.takeIf { it.isNotBlank() }?.let { add("app=${AppDiagnosticRedactor.redact(it)}") }
            entry.androidVersion?.takeIf { it.isNotBlank() }?.let { add("android=${AppDiagnosticRedactor.redact(it)}") }
            entry.deviceModel?.takeIf { it.isNotBlank() }?.let { add("device=${AppDiagnosticRedactor.redact(it)}") }
            entry.errorType?.takeIf { it.isNotBlank() }?.let { add("error_type=${AppDiagnosticRedactor.redact(it)}") }
            entry.serverCountry?.takeIf { it.isNotBlank() }?.let { add("server_country=${AppDiagnosticRedactor.redact(it)}") }
            entry.apiResponseTimeMs?.let { add("api_response_ms=$it") }
            entry.connectionSuccess?.let { add("connection_success=$it") }
            entry.endpointRating?.takeIf { it.isNotBlank() }?.let { add("endpoint_rating=${AppDiagnosticRedactor.redact(it)}") }
        }
        if (metadata.isNotEmpty()) {
            append("  ")
            appendLine(metadata.joinToString(" | "))
        }
    }

    fun decode(raw: String?): List<AppLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val timestamp = json.optString("timestamp").takeIf { it.isNotBlank() } ?: continue
                    add(AppLogEntry(
                        timestamp = timestamp,
                        level = json.optString("level", "info"),
                        category = json.optString("category", "app"),
                        message = json.optString("message"),
                        details = json.optString("details").takeIf { it.isNotBlank() },
                        appVersion = json.optString("appVersion").takeIf { it.isNotBlank() },
                        androidVersion = json.optString("androidVersion").takeIf { it.isNotBlank() },
                        deviceModel = json.optString("deviceModel").takeIf { it.isNotBlank() },
                        errorType = json.optString("errorType").takeIf { it.isNotBlank() },
                        serverCountry = json.optString("serverCountry").takeIf { it.isNotBlank() },
                        apiResponseTimeMs = json.optLong("apiResponseTimeMs").takeIf {
                            json.has("apiResponseTimeMs") && !json.isNull("apiResponseTimeMs")
                        },
                        connectionSuccess = json.optBoolean("connectionSuccess").takeIf {
                            json.has("connectionSuccess") && !json.isNull("connectionSuccess")
                        },
                        endpointRating = json.optString("endpointRating").takeIf { it.isNotBlank() },
                    ))
                }
            }
        }.getOrDefault(emptyList()).let(::prune)
    }

    fun encode(logs: List<AppLogEntry>): String = JSONArray().apply {
        logs.forEach { entry ->
            put(JSONObject()
                .put("timestamp", entry.timestamp)
                .put("level", entry.level)
                .put("category", entry.category)
                .put("message", entry.message)
                .put("details", entry.details ?: "")
                .put("appVersion", entry.appVersion ?: "")
                .put("androidVersion", entry.androidVersion ?: "")
                .put("deviceModel", entry.deviceModel ?: "")
                .put("errorType", entry.errorType ?: "")
                .put("serverCountry", entry.serverCountry ?: "")
                .put("endpointRating", entry.endpointRating ?: "")
                .apply {
                    entry.apiResponseTimeMs?.let { put("apiResponseTimeMs", it) }
                    entry.connectionSuccess?.let { put("connectionSuccess", it) }
                })
        }
    }.toString()

    fun prune(logs: List<AppLogEntry>, now: Instant = Instant.now()): List<AppLogEntry> {
        val cutoff = now.minus(RETENTION_DAYS, ChronoUnit.DAYS)
        val retained = logs.filter { entry ->
            runCatching { !Instant.parse(entry.timestamp).isBefore(cutoff) }.getOrDefault(false)
        }.sortedBy { it.timestamp }.takeLast(MAX_ENTRIES)
        return trimToSize(retained, MAX_CHARS)
    }

    private fun trimToSize(logs: List<AppLogEntry>, maxChars: Int): List<AppLogEntry> {
        var current = logs
        while (current.isNotEmpty() && current.sumOf { entry ->
                entry.timestamp.length + entry.level.length + entry.category.length +
                    entry.message.length + (entry.details?.length ?: 0) +
                    (entry.endpointRating?.length ?: 0) + 16
            } > maxChars) {
            current = current.drop(1)
        }
        return current
    }

    private const val RETENTION_DAYS = 14L
    private const val MAX_ENTRIES = 500
    private const val MAX_CHARS = 2_000_000
    private const val EXPORT_HEADER = "Noki diagnostic log\n"
    private const val OVERSIZED_EVENT_MARKER = "\n  [event truncated: export limit]\n"
}

internal object VpnIncidentStore {
    fun decode(raw: String?): List<VpnIncidentReport> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
                    add(
                        VpnIncidentReport(
                            id = id,
                            reason = json.optString("reason").take(128),
                            countryCode = json.optString("countryCode").take(16),
                            locationCode = json.optString("locationCode").take(64),
                            recoveryAttempts = json.optInt("recoveryAttempts").coerceAtLeast(0),
                            outcome = json.optString("outcome").take(32),
                            occurredAt = json.optString("occurredAt").take(64),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList()).takeLast(MAX_PENDING)
    }

    fun encode(incidents: List<VpnIncidentReport>): String = JSONArray().apply {
        incidents.takeLast(MAX_PENDING).forEach { incident ->
            put(
                JSONObject()
                    .put("id", incident.id)
                    .put("reason", incident.reason)
                    .put("countryCode", incident.countryCode)
                    .put("locationCode", incident.locationCode)
                    .put("recoveryAttempts", incident.recoveryAttempts)
                    .put("outcome", incident.outcome)
                    .put("occurredAt", incident.occurredAt),
            )
        }
    }.toString()

    private const val MAX_PENDING = 10
}
