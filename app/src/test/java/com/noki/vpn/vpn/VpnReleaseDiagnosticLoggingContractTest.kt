package com.noki.vpn.vpn

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnReleaseDiagnosticLoggingContractTest {
    private val source = File(
        "src/main/java/com/noki/vpn/vpn/AppVpnService.kt",
    ).readText()

    @Test
    fun `readiness diagnostics use the user logging policy in release builds`() {
        val readiness = source
            .substringAfter("private suspend fun measureRuntimeReadiness")
            .substringBefore("private fun readinessDiagnosticDetails")
        val recorder = source
            .substringAfter("private fun recordDiagnostic")
            .substringBefore("private fun ", missingDelimiterValue = source)

        assertTrue(readiness.contains("SettingsRepository(this)"))
        assertFalse(readiness.contains("if (BuildConfig.DIAGNOSTIC_LOGGING)"))
        assertFalse(recorder.contains("if (!BuildConfig.DIAGNOSTIC_LOGGING) return"))
    }

    @Test
    fun `underlay diagnostics contain counts rather than resolver identities`() {
        val underlaySource = File(
            "src/main/java/com/noki/vpn/vpn/AndroidUnderlyingNetworkSource.kt",
        ).readText()

        assertTrue(underlaySource.contains("dns_count="))
        assertFalse(underlaySource.contains("hostAddress"))
        assertFalse(underlaySource.contains("privateDnsServerName.orEmpty"))
    }

    @Test
    fun `readiness uses one native operation with the complete target budget`() {
        val readiness = source.substringAfter("private suspend fun measureRuntimeReadiness")
            .substringBefore("private fun readinessDiagnosticDetails")
        assertTrue(readiness.contains("prepareReadinessCandidates(connectedTargets.map { it.url }, totalTimeout, watchdog)"))
        assertTrue(readiness.indexOf("prepareReadinessCandidates(") < readiness.indexOf("awaitNativeProbe("))
        assertTrue(readiness.contains("measure = probe.measure, cancel = probe.cancel"))
        assertFalse(readiness.contains("remainingMillis /"))
        val loader = javaClass.classLoader
        val server = Class.forName("io.nekohasekai.libbox.CommandServer", false, loader)
        for (method in listOf("prepareReadinessCandidates", "prepareWatchdogCandidates")) {
            server.getMethod(method, String::class.java,
                Class.forName("io.nekohasekai.libbox.StringIterator", false, loader), Long::class.javaPrimitiveType)
        }
        val measurement = Class.forName("io.nekohasekai.libbox.Measurement", false, loader)
        measurement.getMethod("run")
        measurement.getMethod("cancel")
    }
}
