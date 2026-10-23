package com.noki.vpn

import android.app.Application
import android.content.Intent
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.AppVpnService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [NokiQuickSettingsTileServiceTest.TileServiceShadow::class, NokiQuickSettingsTileServiceTest.TileShadow::class])
class NokiQuickSettingsTileServiceTest {
    // Robolectric's stock TileService shadow does not extend ShadowService.
    @Implements(TileService::class)
    class TileServiceShadow : ShadowService() {
        private val tile = Shadow.newInstanceOf(Tile::class.java)
        @Implementation fun getQsTile(): Tile = tile
    }
    @Implements(Tile::class)
    class TileShadow {
        @Implementation fun updateTile() = Unit
    }
    private val application: Application = RuntimeEnvironment.getApplication()
    private val previous = AppVpnService.liveRuntimeState()

    private fun tileService() = NokiQuickSettingsTileService().also {
        ReflectionHelpers.callInstanceMethod<Unit>(it, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        it.onCreate()
    }

    private fun publish(state: VpnConnectionState) {
        ReflectionHelpers.setStaticField(AppVpnService::class.java, "liveRuntimeState", previous.copy(state = state))
        application.sendBroadcast(Intent(AppVpnService.ACTION_STATE_CHANGED).setPackage(application.packageName))
        ShadowLooper.idleMainLooper()
    }

    @After fun restoreRuntime() {
        ReflectionHelpers.setStaticField(AppVpnService::class.java, "liveRuntimeState", previous)
    }

    @Test fun `open shade follows completed stop and failures without rebinding the tile`() {
        publish(VpnConnectionState.DISCONNECTED)
        val service = tileService()
        service.onStartListening()
        try {
            assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
            publish(VpnConnectionState.CONNECTED)
            assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
            publish(VpnConnectionState.DISCONNECTED)
            assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
            assertEquals("Не подключено", service.qsTile.subtitle.toString())
            publish(VpnConnectionState.CONNECTING)
            assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
            publish(VpnConnectionState.FAILED)
            assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
            assertEquals("Ошибка", service.qsTile.subtitle.toString())
        } finally { service.onDestroy() }
    }

    @Test fun `listening is idempotent and closed shade stops observing until reopened`() {
        publish(VpnConnectionState.DISCONNECTED)
        val service = tileService()
        service.onStartListening(); service.onStartListening()
        service.onStopListening(); service.onStopListening()
        publish(VpnConnectionState.CONNECTED)
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
        service.onStartListening()
        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        service.onDestroy()
        publish(VpnConnectionState.DISCONNECTED)
        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
    }
}
