package com.noki.vpn.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.BackendRuWireGuardConfig
import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.VlessProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConfigFactoryTest {
    private val geosite = GeositeAssetTest.asset(
        "CATEGORY-RU" to listOf(GeositeAssetTest.domain(2, "russian.example")),
        "SAMPLE" to listOf(GeositeAssetTest.domain(3, "sample.example")),
    )

    @Test
    fun emittedConfigurationUsesNativeRouteAndInboundContracts() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(), geosite))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", inbound.getString("type"))
        assertEquals(1500, inbound.getInt("mtu"))
        assertEquals("gvisor", inbound.getString("stack"))
        assertEquals("10.10.0.2/32", inbound.getJSONArray("address").getString(0))
        assertFalse(inbound.getBoolean("auto_route"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertTrue(config.getJSONObject("route").getBoolean("auto_detect_interface"))
        assertFalse(config.has("routing"))
        assertFalse(config.has("stats"))
        assertFalse(config.has("policy"))
        assertFalse(inbound.has("include_package"))
        assertFalse(inbound.has("exclude_package"))
    }

    @Test
    fun fourProfilesKeepTheirNativeTransportAndTlsIdentity() {
        val profiles = listOf(
            profile().copy(transport = "xhttp", transportMode = "stream-up", port = "443", flow = "", path = "/stream", requestHost = "front.example"),
            profile(),
            profile().copy(security = "tls", port = "9443", publicKey = "", shortId = ""),
            profile().copy(proxyType = "hysteria2", transport = "hysteria2", security = "tls", port = "2443", flow = "", publicKey = "", shortId = "", alpn = "h3"),
        )
        for ((index, candidate) in profiles.withIndex()) {
            val config = JSONObject(VpnConfigFactory.build(candidate, AdvancedSettings(), geosite))
            val outbound = config.getJSONArray("outbounds").getJSONObject(0)
            assertEquals(candidate.host, outbound.getString("server"))
            assertEquals(candidate.port.toInt(), outbound.getInt("server_port"))
            assertEquals(if (index == 3) "hysteria2" else "vless", outbound.getString("type"))
            val tls = outbound.getJSONObject("tls")
            assertTrue(tls.getBoolean("enabled"))
            assertEquals(candidate.serverName, tls.getString("server_name"))
            if (index < 2) {
                assertEquals(candidate.publicKey, tls.getJSONObject("reality").getString("public_key"))
                assertEquals(candidate.shortId, tls.getJSONObject("reality").getString("short_id"))
            } else assertFalse(tls.has("reality"))
            if (index == 0) {
                val transport = outbound.getJSONObject("transport")
                assertEquals("xhttp", transport.getString("type"))
                assertEquals("stream-up", transport.getString("mode"))
                assertEquals("100-1000", transport.getString("x_padding_bytes"))
                assertEquals("/stream", transport.getString("path"))
                assertEquals("front.example", transport.getString("host"))
            } else assertFalse(outbound.has("transport"))
            if (index == 3) {
                assertEquals(candidate.uuid, outbound.getString("password"))
                assertEquals("h3", tls.getJSONArray("alpn").getString(0))
                assertFalse(tls.has("utls"))
            } else assertEquals(candidate.uuid, outbound.getString("uuid"))
        }
    }

    @Test
    fun backendHysteriaWireValueKeepsTheHy2Profile() {
        val candidate = profile().copy(proxyType = "hysteria", transport = "hysteria", security = "tls",
            port = "2443", flow = "", publicKey = "", shortId = "", alpn = "h3")
        val outbound = JSONObject(VpnConfigFactory.build(candidate, AdvancedSettings(), geosite))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", outbound.getString("type"))
        assertEquals(2443, outbound.getInt("server_port"))
        assertEquals(candidate.uuid, outbound.getString("password"))
        assertFalse(outbound.has("uuid"))
    }

    @Test
    fun mandatoryRussianDirectPrecedesConflictingVpnAndIgnoresOldPresetToggle() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(
            alwaysOnDomains = listOf("domain:russian.example", DomainRulePolicy.RUSSIAN_RESOURCES_RULE),
            bypassDomains = listOf("domain:local.example"),
        ), geosite))
        val rules = rules(config)
        val russian = rules.indexOfFirst { it.optJSONArray("domain_suffix")?.toString() == "[\"russian.example\"]" && it.optString("outbound") == "direct" }
        val vpn = rules.indexOfFirst { it.optString("outbound") == "proxy" }
        assertTrue(russian >= 0 && vpn > russian)
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertTrue(rules.any { it.optJSONArray("domain_suffix")?.toString() == "[\"local.example\"]" && it.optString("outbound") == "direct" })
        assertFalse(config.toString().contains("geosite:"))
    }

    @Test
    fun dnsAndBlocksRemainBeforeDirectAndDnsDialerUsesTheTunnel() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(), geosite))
        val rules = rules(config)
        assertEquals("sniff", rules[0].getString("action"))
        assertEquals("hijack-dns", rules[1].getString("action"))
        assertEquals("[53]", rules[1].getJSONArray("port").toString())
        assertEquals("reject", rules[2].getString("action"))
        assertEquals("[853]", rules[2].getJSONArray("port").toString())
        assertEquals("and", rules[3].getString("mode"))
        assertEquals("reject", rules[3].getString("action"))
        val constraints = rules[3].getJSONArray("rules")
        assertEquals("[443]", constraints.getJSONObject(1).getJSONArray("port").toString())
        assertEquals("[\"tcp\"]", constraints.getJSONObject(2).getJSONArray("network").toString())
        val dns = config.getJSONObject("dns")
        val server = dns.getJSONArray("servers").getJSONObject(0)
        assertEquals("tcp", server.getString("type"))
        assertEquals("198.18.0.1", server.getString("server"))
        assertEquals("proxy", server.getString("detour"))
        assertEquals("ipv4_only", dns.getString("strategy"))
        assertEquals("bootstrap", dns.getJSONArray("rules").getJSONObject(0).getString("server"))
        assertEquals("[\"russian.example\"]", dns.getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").toString())
        assertEquals("bootstrap", config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("domain_resolver").getString("server"))
        assertTrue(rules.any { it.optInt("ip_version") == 6 && it.optString("action") == "reject" })
        assertTrue(rules.any { it.optJSONArray("ip_cidr")?.toString()?.contains("100.64.0.0/10") == true })
        assertFalse(config.toString().contains("ip_is_private"))
    }

    @Test
    fun wireGuardHasOnlyTheReadyRuPeerAndKeepsDirectForMultiHop() {
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1"), 1280, listOf("0.0.0.0/0"))
        for (multiHop in listOf(false, true)) {
            val config = JSONObject(VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 },
                AdvancedSettings(ruRelayEnabled = true, multiHop = MultiHopSettings(enabled = multiHop)), geosite))
            val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
            assertEquals("wireguard", endpoint.getString("type"))
            assertFalse(endpoint.getBoolean("system"))
            assertEquals("10.240.1.2/32", endpoint.getJSONArray("address").getString(0))
            assertEquals(1280, endpoint.getInt("mtu"))
            val peers = endpoint.getJSONArray("peers")
            assertEquals(1, peers.length())
            val peer = peers.getJSONObject(0)
            assertEquals("203.0.113.10", peer.getString("address"))
            assertEquals(51820, peer.getInt("port"))
            assertEquals(wg.serverPublicKey, peer.getString("public_key"))
            assertEquals(5, peer.getInt("persistent_keepalive_interval"))
            assertEquals(1280, config.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
            assertEquals("10.240.1.2/32", config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").getString(0))
            assertEquals("gvisor", config.getJSONArray("inbounds").getJSONObject(0).getString("stack"))
            assertEquals("1.1.1.1", config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("server"))
            assertEquals("direct", rules(config).first { it.has("domain_suffix") && it.getJSONArray("domain_suffix").toString().contains("russian.example") }.getString("outbound"))
            assertFalse(config.toString().contains("vless"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 }, AdvancedSettings(), geosite)
        }
    }

    @Test
    fun knownEmptyAttributeMatchCannotCreateAnUnconditionalVpnRule() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(
            alwaysOnDomains = listOf("geosite:sample@missing"),
        ), geosite))
        assertFalse(rules(config).any { it.optString("outbound") == "proxy" })
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigFactory.build(profile(), AdvancedSettings(alwaysOnDomains = listOf("geosite:unknown")), geosite)
        }
    }

    @Test
    fun wireGuardDnsListKeepsTheExplicitPrimaryWithoutInventingFailover() {
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1", "8.8.8.8"), 1280, listOf("0.0.0.0/0"))
        val config = JSONObject(VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 }, AdvancedSettings(ruRelayEnabled = true), geosite))
        val dns = config.getJSONObject("dns").getJSONArray("servers")
        assertEquals(2, dns.length())
        assertEquals("1.1.1.1", dns.getJSONObject(0).getString("server"))
        assertEquals("local", dns.getJSONObject(1).getString("type"))
        assertFalse(config.toString().contains("8.8.8.8"))
    }

    @Test
    fun mixedSelectorsBecomeOrBranchesInsteadOfAndOrSilentDrops() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(alwaysOnDomains = listOf(
            "domain:secure.example", "full:exact.example", "regexp:^private[.]", "geosite:sample",
        )), geosite))
        val vpn = rules(config).single { it.optString("outbound") == "proxy" }
        assertEquals("or", vpn.getString("mode"))
        val branches = vpn.getJSONArray("rules")
        assertTrue((0 until branches.length()).any { branches.getJSONObject(it).has("domain_regex") })
        assertTrue(branches.toString().contains("exact.example"))
        assertTrue(branches.toString().contains("sample.example"))
        assertTrue(branches.toString().contains("secure.example"))
    }

    @Test
    fun retiredAndUnsupportedProfilesFailBeforeNativePublication() {
        val invalid = listOf(
            profile().copy(transport = "httpupgrade"), profile().copy(transport = "unknown"),
            profile().copy(transport = "xhttp", transportMode = "packet-up"),
            profile().copy(enableMux = true), profile().copy(randomUserAgent = true),
            profile().copy(allowInsecure = true), profile().copy(spiderX = "/custom"),
        )
        invalid.forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { VpnConfigFactory.buildProbe(candidate) } }
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigFactory.build(profile(), AdvancedSettings(multiHop = MultiHopSettings(enabled = true)), geosite)
        }
    }

    @Test
    fun probeHasOnlyTheNativeProxyAndNoTunOrGeositeDependency() {
        val probe = JSONObject(VpnConfigFactory.buildProbe(profile()))
        assertFalse(probe.has("inbounds"))
        assertFalse(probe.has("endpoints"))
        assertEquals(1, probe.getJSONArray("outbounds").length())
        assertEquals("vless", probe.getJSONArray("outbounds").getJSONObject(0).getString("type"))
        assertTrue(probe.getJSONObject("route").getBoolean("auto_detect_interface"))
        assertFalse(probe.toString().contains("geosite"))
    }

    private fun rules(config: JSONObject): List<JSONObject> = config.getJSONObject("route").getJSONArray("rules")
        .let { array -> (0 until array.length()).map(array::getJSONObject) }

    private fun profile() = VlessProfile(host = "lv.example", port = "8443",
        uuid = "75e9f9e8-a9f8-4d42-9de1-f91f1a210991", flow = "xtls-rprx-vision", security = "reality",
        serverName = "www.example.com", publicKey = "pOCSkrZRwni5dyxWn1-puxPZBrRqtoyd-dwrRAn4ogk", shortId = "abcd")
}
