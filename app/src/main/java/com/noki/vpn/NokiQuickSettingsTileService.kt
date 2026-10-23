package com.noki.vpn

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.AppVpnService

class NokiQuickSettingsTileService : TileService() {
    private var observingRuntime = false
    private val runtimeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppVpnService.ACTION_STATE_CHANGED) {
                updateTile(AppVpnService.liveRuntimeState().state)
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!observingRuntime) {
            ContextCompat.registerReceiver(this, runtimeReceiver,
                IntentFilter(AppVpnService.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            observingRuntime = true
        }
        updateTile(AppVpnService.liveRuntimeState().state)
    }

    override fun onStopListening() {
        stopObservingRuntime()
        super.onStopListening()
    }

    override fun onDestroy() {
        stopObservingRuntime()
        super.onDestroy()
    }

    private fun stopObservingRuntime() {
        if (!observingRuntime) return
        unregisterReceiver(runtimeReceiver)
        observingRuntime = false
    }

    override fun onClick() {
        super.onClick()
        when (AppVpnService.liveRuntimeState().state) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING -> {
                updateTile(AppVpnService.liveRuntimeState().state, "Отключение…")
                startService(AppVpnService.stopIntent(this))
            }
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.FAILED -> connectFromTile()
        }
    }

    private fun connectFromTile() {
        if (VpnService.prepare(this) != null) {
            updateTile(VpnConnectionState.FAILED, "Нужно разрешение")
            return
        }
        updateTile(VpnConnectionState.CONNECTING)
        val intent = AppVpnService.startIntent(this, refreshSession = true)
        startForegroundService(intent)
    }

    private fun updateTile(
        state: VpnConnectionState,
        subtitleOverride: String? = null,
    ) {
        val tile = qsTile ?: return
        tile.label = "Noki VPN"
        tile.state = when (state) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING -> Tile.STATE_ACTIVE
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.FAILED -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitleOverride ?: when (state) {
                VpnConnectionState.CONNECTED -> "Подключено"
                VpnConnectionState.CONNECTING -> "Подключение"
                VpnConnectionState.FAILED -> "Ошибка"
                VpnConnectionState.DISCONNECTED -> "Не подключено"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestTileRefresh(context: Context) {
            val component = ComponentName(context, NokiQuickSettingsTileService::class.java)
            runCatching {
                TileService.requestListeningState(context, component)
            }
        }
    }
}
