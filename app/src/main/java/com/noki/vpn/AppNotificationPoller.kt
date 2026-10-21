package com.noki.vpn

import android.os.Build
import android.content.Context
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.AppNotificationHistoryStore
import com.noki.vpn.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AppNotificationPoller {
    private val pollMutex = Mutex()

    suspend fun pollOnce(
        context: Context,
        repository: SettingsRepository,
        backendApi: BackendApiClient,
        token: String,
    ) {
        if (token.isBlank()) return
        if (repository.isFcmPushRegistered()) return
        pollMutex.withLock {
            if (repository.isFcmPushRegistered()) return
            val session = repository.load()
            if (!AppNotificationHistoryStore.canPersistForSession(
                    isAuthenticated = session.isAuthenticated,
                    accessToken = session.backendAccessToken,
                    userId = session.userProfile.backendUserId,
                    deviceId = session.backendDeviceId,
                )
            ) {
                return
            }
            if (session.backendAccessToken != token) return
            val seenIds = repository.loadSeenAppNotificationIds()
            val fresh = backendApi.appNotifications(token)
                .asReversed()
                .filter { it.id !in seenIds }
            for (notification in fresh) {
                val accepted = repository.acceptAppNotification(notification, session) {
                    if (AppBroadcastNotifier.show(context, notification,
                            userId = session.userProfile.backendUserId, deviceId = session.backendDeviceId)) {
                        repository.markAppNotificationsSeen(listOf(notification.id))
                    }
                }
                if (!accepted) return
                if (notification.action == MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE) {
                    val updateAvailable = try {
                        backendApi.androidUpdateAvailable(
                            token = token,
                            versionCode = repository.currentAppVersionCode(),
                            abis = Build.SUPPORTED_ABIS.toList(),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (updateAvailable != null) {
                        if (updateAvailable) {
                            repository.markAndroidUpdateAvailable()
                        } else {
                            repository.clearAndroidUpdateAvailable()
                        }
                    }
                }
            }
        }
    }
}
