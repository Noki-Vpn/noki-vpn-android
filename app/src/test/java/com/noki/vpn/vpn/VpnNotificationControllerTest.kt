package com.noki.vpn.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.ServerSelectionMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnNotificationControllerTest {
    @Test
    fun `system channel hides the whole vpn notice and service never reopens it`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(VpnNotificationContract.CHANNEL_ID,
            "Noki VPN", NotificationManager.IMPORTANCE_NONE))
        VpnNotificationContract.ensureChannel(context)
        assertEquals(false, VpnNotificationContract.isVisible(context))
        VpnNotificationFactory(context, 1001).createActive("Latvia 2")
        assertEquals(NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(VpnNotificationContract.CHANNEL_ID).importance)
        val intent = VpnNotificationContract.settingsIntent(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(VpnNotificationContract.CHANNEL_ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun `vpn channel starts enabled independently of chat notifications`() {
        val context = RuntimeEnvironment.getApplication()
        VpnNotificationContract.ensureChannel(context)
        assertEquals(true, VpnNotificationContract.isVisible(context))
        assertEquals(NotificationManager.IMPORTANCE_LOW, context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(VpnNotificationContract.CHANNEL_ID).importance)
    }

    @Test
    fun `notification keeps the connected server name in every selection mode`() {
        val defaults = DefaultStoredSettingsFactory.create()
        ServerSelectionMode.entries.forEach { mode ->
            val settings = defaults.copy(
                profile = defaults.profile.copy(remark = "Noki Latvia 4"),
                userProfile = defaults.userProfile.copy(
                    serverSelectionMode = mode,
                    actualCountryCode = "LV",
                    selectedServerCode = "lv",
                ),
            )
            assertEquals("Latvia 4", VpnServiceLogContext.serverLabel(settings))
            assertEquals("France1", VpnServiceLogContext.serverLabel(
                settings.copy(profile = settings.profile.copy(remark = "Noki France1")),
            ))
        }
        assertEquals("Латвия", VpnServiceLogContext.serverLabel("lv", "Noki VPN", AppLanguage.RU))
        assertEquals("Germany", VpnServiceLogContext.serverLabel("de", "", AppLanguage.EN))
        assertEquals("Riga Noki 2", VpnServiceLogContext.serverLabel("lv", "Noki Riga Noki 2", AppLanguage.RU))
    }

    @Test
    fun `foreground notification contract keeps low-noise channel and disconnect restart actions`() {
        assertEquals("noki_vpn_channel", VpnNotificationContract.CHANNEL_ID)
        assertEquals(true, VpnNotificationContract.ONGOING)
        assertEquals(false, VpnNotificationContract.AUTO_CANCEL)
        assertEquals(listOf("Отключить", "Рестарт"), VpnNotificationContract.ACTION_LABELS)
    }

    @Test
    fun `screen off stops speed updates but keeps polling and screen on resumes`() {
        val scheduler = NotificationScheduler()
        var speedUpdates = 0
        var polls = 0
        var pollCancellations = 0
        val controller = VpnNotificationController(
            scheduler = scheduler,
            speedUpdateIntervalMillis = 1_000L,
            pollIntervalMillis = 60_000L,
            updateActiveNotification = { speedUpdates += 1 },
            pollNotifications = {
                polls += 1
                CancelableTask { pollCancellations += 1 }
            },
        )

        controller.setScreenOn(true)
        controller.start()
        scheduler.runReady()
        assertEquals(1, speedUpdates)
        assertEquals(1, polls)

        controller.setScreenOn(false)
        scheduler.runAllOnce()
        assertEquals(1, speedUpdates)
        assertEquals(2, polls)

        controller.setScreenOn(true)
        scheduler.runReady()
        assertEquals(2, speedUpdates)

        controller.stop()
        scheduler.runAllOnce()
        assertEquals(2, speedUpdates)
        assertEquals(2, polls)
        assertEquals(2, pollCancellations)
    }
}

private class NotificationScheduler : DelayedTaskScheduler {
    private data class Pending(val delayMillis: Long, val task: () -> Unit)

    private val pending = mutableMapOf<Any, Pending>()

    override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) {
        pending[owner] = Pending(delayMillis, task)
    }

    override fun cancel(owner: Any) {
        pending.remove(owner)
    }

    fun runReady() {
        runMatching { it.delayMillis == 0L }
    }

    fun runAllOnce() {
        runMatching { true }
    }

    private fun runMatching(predicate: (Pending) -> Boolean) {
        val ready = pending.filterValues(predicate).values.toList()
        pending.entries.removeAll { predicate(it.value) }
        ready.forEach { it.task() }
    }
}
