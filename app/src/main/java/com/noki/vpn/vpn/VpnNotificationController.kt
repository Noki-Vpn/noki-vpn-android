package com.noki.vpn.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object VpnNotificationContract {
    const val CHANNEL_ID = "noki_vpn_channel"
    const val ONGOING = true
    const val AUTO_CANCEL = false
    const val DISCONNECT_LABEL = "Отключить"
    const val RESTART_LABEL = "Рестарт"
    val ACTION_LABELS = listOf(DISCONNECT_LABEL, RESTART_LABEL)

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Noki VPN", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun isVisible(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
}

internal fun interface CancelableTask {
    fun cancel()
}

internal class VpnNotificationController(
    private val scheduler: DelayedTaskScheduler,
    private val speedUpdateIntervalMillis: Long,
    private val pollIntervalMillis: Long,
    private val updateActiveNotification: () -> Unit,
    private val pollNotifications: () -> CancelableTask?,
) {
    private val speedOwner = Any()
    private val pollOwner = Any()
    @Volatile
    private var running = false
    @Volatile
    private var screenOn = true
    private var activePoll: CancelableTask? = null

    @Synchronized
    fun start() {
        stop()
        running = true
        schedulePoll(delayMillis = 0L)
        if (screenOn) scheduleSpeedUpdate(delayMillis = 0L)
    }

    @Synchronized
    fun stop() {
        running = false
        scheduler.cancel(speedOwner)
        scheduler.cancel(pollOwner)
        activePoll?.cancel()
        activePoll = null
    }

    @Synchronized
    fun setScreenOn(screenOn: Boolean) {
        this.screenOn = screenOn
        if (!running) return
        if (screenOn) {
            scheduleSpeedUpdate(delayMillis = 0L)
        } else {
            scheduler.cancel(speedOwner)
        }
    }

    private fun scheduleSpeedUpdate(delayMillis: Long) {
        scheduler.schedule(speedOwner, delayMillis) {
            if (!running || !screenOn) return@schedule
            updateActiveNotification()
            synchronized(this) {
                if (running && screenOn) scheduleSpeedUpdate(speedUpdateIntervalMillis)
            }
        }
    }

    private fun schedulePoll(delayMillis: Long) {
        scheduler.schedule(pollOwner, delayMillis) {
            if (!running) return@schedule
            val previousPoll = synchronized(this) {
                activePoll.also { activePoll = null }
            }
            previousPoll?.cancel()
            val nextPoll = pollNotifications()
            synchronized(this) {
                if (running) {
                    activePoll = nextPoll
                } else {
                    nextPoll?.cancel()
                }
            }
            synchronized(this) {
                if (running) schedulePoll(pollIntervalMillis)
            }
        }
    }
}
