package com.noki.vpn.vpn

import android.app.Application
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.BackendRuWireGuardConfig
import com.noki.vpn.data.DefaultStoredSettingsFactory
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    shadows = [VpnTunnelPolicyTest.TunnelAllocationShadow::class])
class VpnTunnelPolicyTest {
    @Test
    fun vpnAdvertisesReservedDnsInsteadOfPublicProvider() {
        val address = InetAddress.getByName(VpnTunnelPolicy.DNS_SERVER).address

        assertTrue(
            "VPN DNS must stay inside RFC 2544 benchmark space",
            address.size == 4 &&
                address[0].toInt() and 0xff == 198 &&
                address[1].toInt() and 0xfe == 18,
        )
    }

    @Test
    fun wireGuardAndroidBuilderAdvertisesOnlyTheReservedHijackAddress() {
        val service = Robolectric.buildService(VpnService::class.java).create().get()
        val settings = DefaultStoredSettingsFactory.create().copy(
            filterMode = AppFilterMode.ALL_APPS, selectedPackages = emptySet(),
        )
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1", "8.8.8.8"), 1280, listOf("0.0.0.0/0"))
        TunnelAllocationShadow.advertisedDns = emptyList()

        AndroidTunInterfaceFactory(service).establishWireGuard(settings, null, wg)

        assertEquals(listOf("198.18.0.1"), TunnelAllocationShadow.advertisedDns)
    }

    @Implements(VpnService.Builder::class)
    class TunnelAllocationShadow {
        @RealObject
        private lateinit var builder: VpnService.Builder

        // The JVM lacks Android's InetAddress.parseNumericAddress String overload path.
        @Implementation
        fun addAddress(address: String, prefix: Int): VpnService.Builder =
            builder.addAddress(InetAddress.getByName(address), prefix)

        @Implementation
        fun addRoute(address: String, prefix: Int): VpnService.Builder =
            builder.addRoute(InetAddress.getByName(address), prefix)

        @Implementation
        fun addDnsServer(address: String): VpnService.Builder =
            builder.addDnsServer(InetAddress.getByName(address))

        @Implementation
        fun addDisallowedApplication(packageName: String): VpnService.Builder = builder

        // Read the real Builder DNS configuration at the OS tunnel-allocation boundary.
        @Implementation
        fun establish(): ParcelFileDescriptor? {
            val config = ReflectionHelpers.getField<Any>(builder, "mConfig")
            advertisedDns = ReflectionHelpers.getField<List<String>?>(config, "dnsServers").orEmpty().toList()
            return null
        }

        companion object {
            var advertisedDns: List<String> = emptyList()
        }
    }
}
