package com.noki.vpn.vpn

import android.content.Context
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.DomainRulePolicy
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** The verified protobuf asset remains valid across network failures and app upgrades. */
internal object GeositeAsset {
    private const val NAME = "geosite.dat"
    const val MAX_BYTES = 32 * 1024 * 1024
    private var validatedDigest: String? = null

    fun file(context: Context): File = File(context.noBackupFilesDir, NAME)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun currentDigest(context: Context): String? = runCatching {
        sha256(readSnapshot(context))
    }.getOrNull()

    @Synchronized
    fun readSnapshot(context: Context): ByteArray = AtomicFile(file(context)).openRead().use { input ->
        require(input.channel.size() in 1024L..MAX_BYTES.toLong()) { "geosite_size_invalid" }
        input.readBytes().also { require(isValid(it)) { "geosite_invalid" } }
    }

    @Synchronized
    fun ensureBundledFallback(context: Context) {
        if (currentDigest(context) != null) return
        val bytes = context.assets.open(NAME).use { it.readBytes() }
        install(context, bytes)
    }

    @Synchronized
    fun install(context: Context, bytes: ByteArray) {
        require(isValid(bytes)) { "Geosite invalid" }
        val atomic = AtomicFile(file(context))
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun isValid(bytes: ByteArray): Boolean = runCatching {
        if (sha256(bytes) != validatedDigest) resolveDomains(bytes, emptySet())
        true
    }.getOrDefault(false)

    /** Decode Xray GeoSiteList bytes into native inline fields, retaining attribute conjunction. */
    @Synchronized
    fun resolveDomains(bytes: ByteArray, selectors: Set<String>): Map<String, JSONObject> {
        require(bytes.size in 1024..MAX_BYTES) { "geosite_size_invalid" }
        val digest = sha256(bytes)
        val validated = digest == validatedDigest
        val requested = selectors.associateWith { selector ->
            require(selector.startsWith("geosite:") && DomainRulePolicy.normalize(selector) == selector) { "geosite_selector_invalid" }
            selector.substringAfter(':').split('@').also { parts -> require(parts.all(String::isNotBlank)) { "geosite_selector_invalid" } }
        }
        val selected = requested.mapValues { linkedMapOf<String, LinkedHashSet<String>>() }
        val seen = HashSet<String>()
        var russianDomains = 0
        for ((field, wire, range) in fields(bytes, 0, bytes.size)) {
            require(field == 1 && wire == 2) { "geosite_list_invalid" }
            var siteCode: String? = null
            for ((nestedField, nestedWire, codeRange) in fields(bytes, range.first, range.last + 1)) {
                if (nestedField == 1) {
                    require(nestedWire == 2 && siteCode == null) { "geosite_code_invalid" }
                    siteCode = text(bytes, codeRange).lowercase(Locale.ROOT)
                }
            }
            val code = requireNotNull(siteCode) { "geosite_code_invalid" }
            require(code.isNotBlank() && seen.add(code)) { "geosite_code_invalid" }
            val wanted = requested.filterValues { it.first() == code }
            // Unchanged bytes have already passed full validation, including unselected domains.
            if (validated && wanted.isEmpty()) continue
            for ((nestedField, nestedWire, domainRange) in fields(bytes, range.first, range.last + 1)) {
                if (nestedField != 2) continue
                require(nestedWire == 2) { "geosite_domain_invalid" }
                var type = 0
                var value: String? = null
                val attributes = HashSet<String>()
                for ((domainField, domainWire, valueRange) in fields(bytes, domainRange.first, domainRange.last + 1)) {
                    when (domainField) {
                        1 -> {
                            require(domainWire == 0) { "geosite_domain_type_invalid" }
                            val enum = varint(bytes, valueRange.first, valueRange.last + 1).first
                            require(enum in 0L..3L) { "geosite_domain_type_unsupported" }
                            type = enum.toInt()
                        }
                        2 -> {
                            require(domainWire == 2) { "geosite_domain_invalid" }
                            value = text(bytes, valueRange).also { require(it.isNotBlank()) { "geosite_domain_invalid" } }
                        }
                        3 -> {
                            require(domainWire == 2) { "geosite_attribute_invalid" }
                            var key: String? = null
                            for ((attributeField, attributeWire, attributeRange) in fields(bytes, valueRange.first, valueRange.last + 1)) {
                                when (attributeField) {
                                    1 -> {
                                        require(attributeWire == 2) { "geosite_attribute_invalid" }
                                        key = text(bytes, attributeRange).also { require(it.isNotBlank()) { "geosite_attribute_invalid" } }
                                    }
                                    2, 3 -> require(attributeWire == 0) { "geosite_attribute_invalid" }
                                }
                            }
                            attributes += requireNotNull(key) { "geosite_attribute_invalid" }
                        }
                    }
                }
                val domain = requireNotNull(value) { "geosite_domain_invalid" }
                if (code == "category-ru") russianDomains++
                val nativeField = when (type) { 0 -> "domain_keyword"; 1 -> "domain_regex"; 2 -> "domain_suffix"; else -> "domain" }
                for ((selector, parts) in wanted) {
                    // Xray matches attribute keys, including keys whose bool/int value is false/zero.
                    if (parts.drop(1).all(attributes::contains)) selected.getValue(selector)
                        .getOrPut(nativeField) { linkedSetOf() }.add(domain)
                }
            }
        }
        require(validated || russianDomains > 0) { "geosite_category_ru_empty" }
        require(requested.values.all { it.first() in seen }) { "geosite_tag_unknown" }
        validatedDigest = digest
        return selected.mapValues { (_, fields) -> JSONObject().apply { fields.forEach { (key, values) -> put(key, JSONArray(values)) } } }
    }

    private fun text(bytes: ByteArray, range: IntRange): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, range.first, range.last + 1 - range.first)).toString()

    private fun fields(bytes: ByteArray, start: Int, end: Int): Sequence<Triple<Int, Int, IntRange>> = sequence {
        var pos = start
        while (pos < end) {
            val (tag, afterTag) = varint(bytes, pos, end)
            pos = afterTag
            require(tag in 1L..0xffffffffL && tag ushr 3 != 0L) { "geosite_field_invalid" }
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            var valueStart = pos
            when (wire) {
                0 -> pos = varint(bytes, pos, end).second
                1 -> pos += 8
                2 -> {
                    val (length, afterLength) = varint(bytes, pos, end)
                    require(length >= 0 && length <= end - afterLength) { "geosite_length_invalid" }
                    valueStart = afterLength
                    pos = afterLength + length.toInt()
                }
                5 -> pos += 4
                else -> error("Invalid geosite wire type")
            }
            require(pos <= end)
            yield(Triple(field, wire, valueStart until pos))
        }
    }

    private fun varint(bytes: ByteArray, start: Int, end: Int): Pair<Long, Int> {
        var value = 0L
        var pos = start
        for (shift in 0..63 step 7) {
            require(pos < end)
            val byte = bytes[pos++].toInt() and 0xff
            require(shift < 63 || byte <= 1) { "geosite_varint_invalid" }
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte < 128) return value to pos
        }
        error("Invalid geosite varint")
    }
}

class GeositeUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val api = BackendApiClient()
        val metadata = api.geositeMetadata()
        if (GeositeAsset.currentDigest(applicationContext) != metadata.first) {
            val bytes = api.downloadGeosite(metadata.second)
            require(GeositeAsset.sha256(bytes) == metadata.first) { "Geosite checksum mismatch" }
            GeositeAsset.install(applicationContext, bytes)
        }
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GeositeUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "geosite_daily_update", ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
