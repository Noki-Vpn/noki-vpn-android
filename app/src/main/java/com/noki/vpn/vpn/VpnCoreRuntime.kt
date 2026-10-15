package com.noki.vpn.vpn

internal interface VpnCoreRuntime {
    fun start(config: String, tunFd: Int): Boolean

    fun rebindWireGuard(): Boolean

    fun stop()

    fun cancelMeasureDelay()

    fun measureDelay(targetUrl: String, timeoutMillis: Long): VpnProbeResult
}

internal data class VpnProbeResult(
    val delayMs: Long?,
    val issue: VpnCoreIssue? = null,
)
