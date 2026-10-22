package com.noki.vpn.vpn

internal interface VpnCoreRuntime {
    fun start(config: String, tunFd: Int): Boolean

    fun rebindNetwork(): Boolean

    fun stop()

    fun cancelMeasureDelay()

    fun measureDelay(targetUrl: String, timeoutMillis: Long): VpnProbeResult

    fun nokiNvCarrier(): String
}

internal data class VpnProbeResult(
    val delayMs: Long?,
    val issue: VpnCoreIssue? = null,
)
