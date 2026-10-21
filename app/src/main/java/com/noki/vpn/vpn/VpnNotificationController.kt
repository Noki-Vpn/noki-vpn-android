package com.noki.vpn.vpn

internal object VpnNotificationContract {
    const val CHANNEL_ID = "noki_vpn_channel"
    const val ONGOING = true
    const val AUTO_CANCEL = false
    const val DISCONNECT_LABEL = "Отключить"
    const val RESTART_LABEL = "Рестарт"
    val ACTION_LABELS = listOf(DISCONNECT_LABEL, RESTART_LABEL)

    fun content(title: String, text: String, statusEnabled: Boolean): Pair<String, String> =
        if (statusEnabled) title to text else "Noki VPN" to "Служба VPN"
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
