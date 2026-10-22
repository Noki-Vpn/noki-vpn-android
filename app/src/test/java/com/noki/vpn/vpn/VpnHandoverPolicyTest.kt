package com.noki.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnHandoverPolicyTest {
    @Test
    fun changedUnderlyingWithTunnelRebindsTheExistingRuntime() {
        assertEquals(
            VpnHandoverPolicy.Plan(
                action = VpnHandoverPolicy.Action.RebindNetwork,
                strategy = VpnPreparationStrategy.CachedFirst,
            ),
            VpnHandoverPolicy.plan(
                hasTunnel = true,
                activeSignature = "wifi-100",
                nextSignature = "cell-101",
            ),
        )
    }

    @Test
    fun unchangedUnderlyingDoesNothing() {
        assertEquals(
            VpnHandoverPolicy.Action.NoAction,
            VpnHandoverPolicy.plan(
                hasTunnel = true,
                activeSignature = "wifi-100",
                nextSignature = "wifi-100",
            ).action,
        )
    }

    @Test
    fun missingTunnelUsesFreshStartWithTransientCacheFallback() {
        val plan = VpnHandoverPolicy.plan(
            hasTunnel = false,
            activeSignature = null,
            nextSignature = "wifi-100",
        )

        assertEquals(VpnHandoverPolicy.Action.FreshStart, plan.action)
        assertEquals(VpnPreparationStrategy.CachedFirst, plan.strategy)
    }
}
