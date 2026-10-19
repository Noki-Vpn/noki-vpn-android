package com.noki.vpn.ui

import com.noki.vpn.AppDialog
import com.noki.vpn.AppDestination
import com.noki.vpn.confirmedServerCode
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.loadInstalledAppsOrEmpty
import com.noki.vpn.navigationStackAfterOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryNavigationPolicyTest {
    @Test
    fun missingRuDiscoveryKeepsBootstrapSelectionButNeverConfirmsOfflineNodes() {
        val node = com.noki.vpn.data.VpnServer("lv-2", "Latvia 2", "LV", "lv2", "probe.example", 443, true)
        val locations = listOf(ServerLocation("LV", "LV", "Латвия", "", "probe.example",
            isOnline = true, servers = listOf(node)))
        assertEquals("LV", confirmedServerCode(AppDialog.ChangeServer("LV", ServerSelectionMode.COUNTRY),
            locations, emptyList(), true))
        assertEquals("lv-2", confirmedServerCode(AppDialog.ChangeServer("lv-2", ServerSelectionMode.SERVER),
            locations, emptyList(), true))
        assertEquals("", confirmedServerCode(AppDialog.ChangeServer("", ServerSelectionMode.AUTO),
            locations, emptyList(), true))
        assertNull(confirmedServerCode(AppDialog.ChangeServer("lv-2", ServerSelectionMode.SERVER),
            locations.map { it.copy(servers = listOf(node.copy(isOnline = false))) }, emptyList(), true))
    }

    @Test
    fun overlaySelectionUsesRuDiscoveryAndRevalidatesWithdrawnNodes() {
        val nodes = listOf(RuForeignNode("nl", "NL overlay", "NL"))
        val dialog = AppDialog.ChangeServer("nl", ServerSelectionMode.SERVER)
        assertEquals("nl", confirmedServerCode(dialog, emptyList(), nodes, true))
        assertNull(confirmedServerCode(dialog, emptyList(), nodes))
        assertNull(confirmedServerCode(dialog, emptyList(), emptyList(), true))
        assertEquals("NL", confirmedServerCode(AppDialog.ChangeServer("NL", ServerSelectionMode.COUNTRY),
            emptyList(), nodes, true))
        assertEquals("", confirmedServerCode(AppDialog.ChangeServer("", ServerSelectionMode.AUTO),
            emptyList(), nodes, true))
    }
    @Test
    fun selectionModeIsPartOfIdentityAndOfflineNodeCannotBeConfirmed() {
        val profile = com.noki.vpn.data.UserProfile(selectedCountryCode = "DE")
        assertEquals(true, com.noki.vpn.isCurrentServerSelection(profile, "de", com.noki.vpn.data.ServerSelectionMode.COUNTRY))
        assertEquals(false, com.noki.vpn.isCurrentServerSelection(profile, "", com.noki.vpn.data.ServerSelectionMode.AUTO))
        val node = com.noki.vpn.data.VpnServer(
            id = "node-a", name = "Frankfurt 1", countryCode = "DE", locationCode = "de-1",
            host = "probe.example", probePort = 443, isOnline = false,
        )
        val locations = listOf(ServerLocation(code = "DE", country = "Germany", city = "", host = "", isOnline = true, servers = listOf(node)))
        assertNull(confirmedServerCode(AppDialog.ChangeServer("node-a", com.noki.vpn.data.ServerSelectionMode.SERVER), locations))
        assertEquals("", confirmedServerCode(AppDialog.ChangeServer("", com.noki.vpn.data.ServerSelectionMode.AUTO), locations))
        assertEquals("node-a", confirmedServerCode(
            AppDialog.ChangeServer("node-a", com.noki.vpn.data.ServerSelectionMode.SERVER),
            locations.map { it.copy(servers = listOf(node.copy(isOnline = true))) },
        ))
    }

    @Test
    fun devicesPullRefreshLoadsBothNativeAndIncyDataWithoutProbingLatency() {
        for (destination in listOf(AppDestination.DEVICES, AppDestination.EXTRA_DEVICES)) {
            val requests = mutableListOf<String>()
            refreshVisibleScreenData(
                destination = destination,
                refreshAllData = { latency -> requests += "bootstrap:$latency" },
                refreshIncyDevices = { requests += "incy" },
            )
            assertEquals(destination.name, listOf("bootstrap:false", "incy"), requests)
        }
    }

    @Test
    fun additionalDevicesRemainADetailOfTheDevicesScreen() {
        val destination = AppDestination.EXTRA_DEVICES
        assertFalse(PrimaryNavigationPolicy.isTopLevelDestination(destination))
        assertTrue(PrimaryNavigationPolicy.showsSharedNavigation(true, destination))
        assertFalse(PrimaryNavigationPolicy.showsSharedNavigation(false, destination))
        assertEquals(2, PrimaryNavigationPolicy.selectedTabIndex(destination))
        assertEquals(
            listOf(AppDestination.DEVICES, destination),
            navigationStackAfterOpen(listOf(AppDestination.DEVICES), destination, false),
        )
    }

    @Test
    fun homePullRefreshStillProbesLatencyWithoutLoadingIncy() {
        val requests = mutableListOf<String>()

        refreshVisibleScreenData(
            destination = AppDestination.HOME,
            refreshAllData = { latency -> requests += "bootstrap:$latency" },
            refreshIncyDevices = { requests += "incy" },
        )

        assertEquals(listOf("bootstrap:true"), requests)
    }

    @Test
    fun otherScreensPullRefreshOnlyCommonBackendData() {
        for (destination in listOf(AppDestination.ACCOUNT, AppDestination.SETTINGS, AppDestination.STATS)) {
            val requests = mutableListOf<String>()
            refreshVisibleScreenData(
                destination = destination,
                refreshAllData = { latency -> requests += "bootstrap:$latency" },
                refreshIncyDevices = { requests += "incy" },
            )
            assertEquals(destination.name, listOf("bootstrap:false"), requests)
        }
    }

    @Test
    fun attentionIndicatorsMapNotificationsToAccountAndUpdatesToSettings() {
        assertEquals(
            setOf(1),
            PrimaryNavigationPolicy.attentionIndicatorTabs(
                hasUnreadAppNotifications = true,
                isAndroidUpdateAvailable = false,
            ),
        )
        assertEquals(
            setOf(2),
            PrimaryNavigationPolicy.attentionIndicatorTabs(
                hasUnreadAppNotifications = false,
                isAndroidUpdateAvailable = true,
            ),
        )
        assertEquals(
            setOf(1, 2),
            PrimaryNavigationPolicy.attentionIndicatorTabs(
                hasUnreadAppNotifications = true,
                isAndroidUpdateAvailable = true,
            ),
        )
    }

    @Test
    fun middleTabTargetsAccount() {
        assertEquals(AppDestination.ACCOUNT, PrimaryNavigationPolicy.destinationForTab(1))
    }

    @Test
    fun accountSelectsMiddleTabWhileStatsRemainsUnderSettings() {
        assertEquals(1, PrimaryNavigationPolicy.selectedTabIndex(AppDestination.ACCOUNT))
        assertEquals(2, PrimaryNavigationPolicy.selectedTabIndex(AppDestination.STATS))
        assertEquals(2, PrimaryNavigationPolicy.selectedTabIndex(AppDestination.SETTINGS))
    }

    @Test
    fun plansSelectsMiddleTab() {
        assertEquals(1, PrimaryNavigationPolicy.selectedTabIndex(AppDestination.PLANS))
    }

    @Test
    fun anonymousSupportDoesNotExposeAuthenticatedBottomNavigation() {
        assertFalse(
            PrimaryNavigationPolicy.showsSharedNavigation(
                isAuthenticated = false,
                destination = AppDestination.SUPPORT,
            ),
        )
        assertTrue(
            PrimaryNavigationPolicy.showsSharedNavigation(
                isAuthenticated = true,
                destination = AppDestination.SUPPORT,
            ),
        )
    }

    @Test
    fun topLevelNavigationReplacesTheWholeDetailStack() {
        assertEquals(
            listOf(AppDestination.ACCOUNT),
            navigationStackAfterOpen(
                currentStack = listOf(
                    AppDestination.HOME,
                    AppDestination.SETTINGS,
                    AppDestination.APP_FILTER,
                ),
                destination = AppDestination.ACCOUNT,
                replaceStack = true,
            ),
        )
    }

    @Test
    fun repeatedTopLevelNavigationCannotGrowTheStack() {
        var stack = listOf(AppDestination.HOME)
        repeat(1_000) { index ->
            stack = navigationStackAfterOpen(
                currentStack = stack,
                destination = if (index % 2 == 0) AppDestination.ACCOUNT else AppDestination.SETTINGS,
                replaceStack = true,
            )
        }

        assertEquals(1, stack.size)
        assertEquals(AppDestination.SETTINGS, stack.single())
    }

    @Test
    fun detailNavigationStillPushesOnce() {
        assertEquals(
            listOf(AppDestination.SETTINGS, AppDestination.APP_FILTER),
            navigationStackAfterOpen(
                currentStack = listOf(AppDestination.SETTINGS),
                destination = AppDestination.APP_FILTER,
                replaceStack = false,
            ),
        )
        assertEquals(
            listOf(AppDestination.SETTINGS),
            navigationStackAfterOpen(
                currentStack = listOf(AppDestination.SETTINGS),
                destination = AppDestination.SETTINGS,
                replaceStack = false,
            ),
        )
    }

    @Test
    fun modalAndCheckoutHideBottomNavigation() {
        assertTrue(
            PrimaryNavigationPolicy.showsBottomNavigation(
                isAuthenticated = true,
                destination = AppDestination.APP_FILTER,
                dialogVisible = false,
                plansCheckoutVisible = false,
            ),
        )
        assertFalse(
            PrimaryNavigationPolicy.showsBottomNavigation(
                isAuthenticated = true,
                destination = AppDestination.APP_FILTER,
                dialogVisible = true,
                plansCheckoutVisible = false,
            ),
        )
        assertFalse(
            PrimaryNavigationPolicy.showsBottomNavigation(
                isAuthenticated = true,
                destination = AppDestination.PLANS,
                dialogVisible = false,
                plansCheckoutVisible = true,
            ),
        )
    }

    @Test
    fun staleServerDialogCannotSelectADeletedLocation() {
        val dialog = AppDialog.ChangeServer("pl")
        val availableLocations = listOf(
            ServerLocation(
                code = "lv",
                country = "Latvia",
                city = "Riga",
                host = "lv.example",
                isOnline = true,
            ),
        )

        assertNull(confirmedServerCode(dialog, availableLocations))
    }

    @Test(expected = CancellationException::class)
    fun installedAppsLoadDoesNotSwallowScopeCancellation() {
        runBlocking {
            loadInstalledAppsOrEmpty {
                throw CancellationException("view model cleared")
            }
        }
    }
}
