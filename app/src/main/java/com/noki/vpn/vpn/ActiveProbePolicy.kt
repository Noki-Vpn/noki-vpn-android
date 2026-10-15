package com.noki.vpn.vpn

object ActiveProbePolicy {
    const val SLOW_DELAY_MS = 4_000L

    data class TargetResult(
        val delayMs: Long?,
        val issue: VpnCoreIssue? = null,
    )

    data class Decision(
        val treatAsHealthy: Boolean,
        val recordEndpointSuccess: Boolean,
        val recordEndpointFailure: Boolean,
        val recordEndpointSlow: Boolean,
        val scheduleRetry: Boolean,
        val restartVpn: Boolean,
        val nextFailureCount: Int,
    )

    fun evaluate(
        targetResults: List<TargetResult>,
        previousFailures: Int,
        slowDelayMs: Long = SLOW_DELAY_MS,
    ): Decision {
        val healthyProbe = targetResults.any { target ->
            val delay = target.delayMs
            delay != null && delay < slowDelayMs
        }
        if (healthyProbe) {
            return Decision(
                treatAsHealthy = true,
                recordEndpointSuccess = true,
                recordEndpointFailure = false,
                recordEndpointSlow = false,
                scheduleRetry = false,
                restartVpn = false,
                nextFailureCount = 0,
            )
        }

        val nextFailures = (previousFailures + 1).coerceAtLeast(1)
        val shouldRestart = nextFailures >= REQUIRED_FAILURES_BEFORE_RESTART
        val hasAnyProbeResponse = targetResults.any { it.delayMs != null }
        return Decision(
            treatAsHealthy = false,
            recordEndpointSuccess = false,
            recordEndpointFailure = shouldRestart,
            recordEndpointSlow = hasAnyProbeResponse,
            scheduleRetry = !shouldRestart,
            restartVpn = shouldRestart,
            nextFailureCount = nextFailures,
        )
    }

    fun representativeDelay(targetResults: List<TargetResult>): Long? {
        val delays = targetResults.mapNotNull { it.delayMs }.sorted()
        if (delays.isEmpty()) return null
        return delays[delays.size / 2]
    }

    private const val REQUIRED_FAILURES_BEFORE_RESTART = 2
}
