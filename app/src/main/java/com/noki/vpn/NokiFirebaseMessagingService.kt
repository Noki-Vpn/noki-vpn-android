package com.noki.vpn

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.SettingsRepository

class NokiFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val repository = SettingsRepository(applicationContext)
        FcmTokenRegistrar.registerKnownToken(
            repository = repository,
            backendApi = BackendApiClient(),
            fcmToken = token,
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val repository = SettingsRepository(applicationContext)
        val settings = repository.load()
        if (!FcmNotificationPayload.matchesAudience(
                data = message.data,
                userId = settings.userProfile.backendUserId,
                deviceId = settings.backendDeviceId,
            )
        ) {
            return
        }
        val notification = FcmNotificationPayload.toBackendNotification(
            data = message.data,
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
            messageId = message.messageId,
            sentTimeMillis = message.sentTime,
        ) ?: return
        val accepted = repository.acceptAppNotification(notification, settings) {
            repository.recordAppLog(
                category = "notification",
                message = "fcm_notification_received",
                details = "has_action=${!notification.action.isNullOrBlank()}",
            )
            if (AppBroadcastNotifier.show(applicationContext, notification,
                    userId = settings.userProfile.backendUserId, deviceId = settings.backendDeviceId)) {
                repository.markAppNotificationsSeen(listOf(notification.id))
            }
        }
        if (!accepted) return
        if (notification.action == MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE) {
            repository.markAndroidUpdateAvailable()
        }
    }
}
