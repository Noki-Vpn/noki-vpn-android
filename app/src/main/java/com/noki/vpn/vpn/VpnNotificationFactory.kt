package com.noki.vpn.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.noki.vpn.MainActivity
import com.noki.vpn.R
import com.noki.vpn.data.DeviceTrafficMonitor
import java.util.Locale

internal class VpnNotificationFactory(
    private val context: Context,
    private val notificationId: Int,
) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createActive(serverLabel: String): Notification {
        val traffic = DeviceTrafficMonitor.snapshot.value
        val details = "$serverLabel   ↓ ${formatSpeed(traffic.downloadMbps)} / " +
            "↑ ${formatSpeed(traffic.uploadMbps)} Мбит/с"
        return create(title = "Соединен", text = details, showActions = true)
    }

    fun updateActive(serverLabel: String) {
        manager.notify(notificationId, createActive(serverLabel))
    }

    fun create(title: String, text: String, showActions: Boolean): Notification {
        VpnNotificationContract.ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PENDING_INTENT_FLAGS,
        )
        val builder = NotificationCompat.Builder(context, VpnNotificationContract.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_noki_notification)
            .setContentIntent(contentIntent)
            .setOngoing(VpnNotificationContract.ONGOING)
            .setAutoCancel(VpnNotificationContract.AUTO_CANCEL)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)

        if (showActions) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                VpnNotificationContract.DISCONNECT_LABEL,
                PendingIntent.getService(
                    context,
                    1,
                    AppVpnService.stopIntent(context),
                    PENDING_INTENT_FLAGS,
                ),
            )
            builder.addAction(
                android.R.drawable.ic_popup_sync,
                VpnNotificationContract.RESTART_LABEL,
                PendingIntent.getForegroundService(
                    context,
                    2,
                    AppVpnService.restartIntent(context),
                    PENDING_INTENT_FLAGS,
                ),
            )
        }

        return builder.build().apply {
            flags = flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR or
                Notification.FLAG_FOREGROUND_SERVICE
        }
    }

    private fun formatSpeed(value: Double?): String {
        val safeValue = value?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
        val format = if (safeValue < 10.0) "%.2f" else "%.1f"
        return String.format(Locale.US, format, safeValue).trimEnd('0').trimEnd('.')
    }

    companion object {
        private const val PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
