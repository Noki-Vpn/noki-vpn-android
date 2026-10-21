package com.noki.vpn

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.noki.vpn.data.BackendAppNotification
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.RemoteMessage
import com.noki.vpn.vpn.AppVpnServiceCommandTest.InMemoryAndroidKeyStore
import java.security.Provider
import java.security.Security
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppBroadcastNotifierTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun provideAndroidKeyStore() {
        android.provider.Settings.Secure.putString(application.contentResolver, android.provider.Settings.Secure.ANDROID_ID, "support-notifications")
        Security.addProvider(object : Provider("NokiSupportNotificationTest", 1.0, "Test-only Android keystore") {
            init { put("KeyStore.AndroidKeyStore", InMemoryAndroidKeyStore::class.java.name) }
        })
    }

    @After
    fun removeAndroidKeyStore() { Security.removeProvider("NokiSupportNotificationTest") }

    @Test
    fun `plain notification does not erase an earlier security action`() {
        val security = post("security", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        post("plain")

        val intent = tap(security)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, action(intent))
        assertTrue(accepts(intent))
    }

    @Test fun `support notifications are suppressed only for the foreground dialog owner and account`() {
        val first = Any()
        val second = Any()
        val message = BackendAppNotification(id = "support-visible", title = "Support", message = "Reply", createdAt = "2026-10-20T00:00:00Z",
            action = MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT)
        try {
            AppBroadcastNotifier.setSupportChatVisibility(first, "user-1", true)
            assertTrue(AppBroadcastNotifier.show(application, message, "user-1", "device-1"))
            assertTrue(manager.activeNotifications.isEmpty())
            AppBroadcastNotifier.setSupportChatVisibility(second, "user-1", true)
            AppBroadcastNotifier.setSupportChatVisibility(first, "user-1", false)
            assertTrue(AppBroadcastNotifier.show(application, message, "user-1", "device-1"))
            assertTrue(manager.activeNotifications.isEmpty())
            AppBroadcastNotifier.setSupportChatVisibility(second, "user-1", false)
            assertTrue(AppBroadcastNotifier.show(application, message, "user-1", "device-1"))
            assertEquals(1, manager.activeNotifications.size)
        } finally {
            AppBroadcastNotifier.setSupportChatVisibility(second, "user-1", false)
            AppBroadcastNotifier.setSupportChatVisibility(first, "user-1", false)
        }
    }

    @Test
    fun `security notification does not add an action to an earlier plain notification`() {
        val plain = post("plain")
        post("security", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)

        val intent = tap(plain)

        assertNull(action(intent))
        assertNull(nonce(intent))
        assertFalse(accepts(intent))
    }

    @Test
    fun `consuming one notification action does not invalidate another notification`() {
        val first = post("first", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val second = post("second", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val firstIntent = tap(first)
        val secondIntent = tap(second)

        assertNotEquals(nonce(firstIntent), nonce(secondIntent))
        assertTrue(accepts(firstIntent))

        assertFalse(accepts(firstIntent))
        assertTrue(accepts(secondIntent))
    }

    @Test
    fun `different notification identifiers with equal hashes remain independent`() {
        val first = post("Aa", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val second = post("BB")

        assertEquals(2, manager.activeNotifications.size)
        assertTrue(accepts(tap(first)))
        assertNull(action(tap(second)))
    }

    @Test
    fun `reposting the same notification replaces its action without affecting another notification`() {
        val original = post("same", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val other = post("other", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val replacement = post("same", "unsupported_action")

        assertEquals(2, manager.activeNotifications.size)
        assertEquals(original.contentIntent, replacement.contentIntent)
        val replacementIntent = tap(replacement)
        assertNull(action(replacementIntent))
        assertFalse(accepts(replacementIntent))
        assertTrue(accepts(tap(other)))
    }

    private fun post(id: String, action: String? = null): Notification {
        assertTrue(AppBroadcastNotifier.show(application, BackendAppNotification(
            id = id,
            title = id,
            message = "Message for $id",
            createdAt = "2026-09-05T00:00:00Z",
            action = action,
        )))
        return manager.activeNotifications.single {
            it.notification.extras.getString(Notification.EXTRA_TITLE) == id
        }.notification
    }

    @Test
    fun `expired and evicted nonces cannot be consumed`() {
        val start = 1_000L
        val expired = AppNotificationActionNonceStore.issue(application, start)
        assertNull(AppNotificationActionNonceStore.validateAndConsume(
            application, MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, expired,
            start + AppNotificationActionNonceStore.TTL_MILLIS,
        ))
        val oldest = AppNotificationActionNonceStore.issue(application, start)
        repeat(AppNotificationActionNonceStore.MAX_NONCES) {
            AppNotificationActionNonceStore.issue(application, start + it + 1)
        }
        assertNull(AppNotificationActionNonceStore.validateAndConsume(
            application, MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, oldest,
            start + AppNotificationActionNonceStore.MAX_NONCES,
        ))
    }

    @Test
    fun `unsupported action cannot consume a valid nonce`() {
        val nonce = AppNotificationActionNonceStore.issue(application)
        assertNull(AppNotificationActionNonceStore.validateAndConsume(application, "unknown", nonce))
        assertEquals(AppNotificationAction.OpenSecurityUpdate,
            AppNotificationActionNonceStore.validateAndConsume(
                application, MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, nonce,
            ))
    }

    @Test
    fun `concurrent consumers accept a nonce only once`() {
        val nonce = AppNotificationActionNonceStore.issue(application)
        val start = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val workers = List(8) {
            Thread {
                start.await()
                if (AppNotificationActionNonceStore.validateAndConsume(
                        application, MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, nonce,
                    ) != null) accepted.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(5_000L) }
        assertTrue(workers.none { it.isAlive })
        assertEquals(1, accepted.get())
    }

    @Test
    fun `support click nonce is bound to user device and action`() {
        val nonce = AppNotificationActionNonceStore.issue(application,
            action = AppNotificationAction.OpenSupportChat, userId = "user-1", deviceId = "device-1")
        assertNull(AppNotificationActionNonceStore.validateAndConsume(application,
            MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT, nonce, userId = "user-2", deviceId = "device-1"))
        assertNull(AppNotificationActionNonceStore.validateAndConsume(application,
            MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT, nonce, userId = "user-1", deviceId = "device-2"))
        assertNull(AppNotificationActionNonceStore.validateAndConsume(application,
            MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, nonce))
        assertEquals(AppNotificationAction.OpenSupportChat, AppNotificationActionNonceStore.validateAndConsume(application,
            MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT, nonce, userId = "user-1", deviceId = "device-1"))
        assertNull(AppNotificationActionNonceStore.validateAndConsume(application,
            MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT, nonce, userId = "user-1", deviceId = "device-1"))
        assertNull(AppNotificationActionNonceStore.issue(application, action = AppNotificationAction.OpenSupportChat))
        val securityNonce = AppNotificationActionNonceStore.issue(application)
        assertNull(AppNotificationActionNonceStore.validateAndConsume(application,
            MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT, securityNonce, userId = "user-1", deviceId = "device-1"))
    }

    @Test
    @Config(sdk = [35])
    fun `support FCM delivery validates audience and click opens the native chat`() {
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val model = supportViewModel()
        val controller = Robolectric.buildService(NokiFirebaseMessagingService::class.java).create()
        try {
            val data = mapOf("action" to MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT,
                "notification_id" to "support:1:17", "title" to "Поддержка Noki", "message" to "Вам ответили в чате поддержки",
                "audience_user_id" to "user-1", "audience_device_id" to "device-1")
            controller.get().onMessageReceived(RemoteMessage.Builder("test").setData(data + ("audience_user_id" to "other-user")).build())
            assertTrue(manager.activeNotifications.isEmpty())
            controller.get().onMessageReceived(RemoteMessage.Builder("test").setData(data).build())
            val notification = manager.activeNotifications.single().notification
            assertEquals("Вам ответили в чате поддержки", notification.extras.getString(Notification.EXTRA_TEXT))
            val intent = tap(notification)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            model.handleAppNotificationAction(action(intent), nonce(intent))
            assertEquals(AppDestination.SUPPORT, model.uiState.currentDestination)
            assertTrue(model.uiState.supportChat.isOpen)
        } finally { controller.destroy() }
    }

    @Test
    fun `support click is ignored after switching account or logging out`() {
        val model = supportViewModel()
        val oldNonce = AppNotificationActionNonceStore.issue(application,
            action = AppNotificationAction.OpenSupportChat, userId = "other-user", deviceId = "device-1")
        model.handleAppNotificationAction(MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT, oldNonce)
        assertEquals(AppDestination.HOME, model.uiState.currentDestination)
        assertFalse(model.uiState.supportChat.isOpen)
        val runtime = org.robolectric.util.ReflectionHelpers.getField<AppUiRuntime>(model, "runtime")
        runtime.uiState = runtime.uiState.copy(isAuthenticated = false)
        val nonce = AppNotificationActionNonceStore.issue(application,
            action = AppNotificationAction.OpenSupportChat, userId = "user-1", deviceId = "device-1")
        model.handleAppNotificationAction(MainActivity.APP_NOTIFICATION_ACTION_OPEN_SUPPORT_CHAT, nonce)
        assertFalse(model.uiState.supportChat.isOpen)
    }

    @Test
    fun `cold notification waits for initial navigation and warm launch is already ready`() = runBlocking {
        val model = supportViewModel()
        val runtime = org.robolectric.util.ReflectionHelpers.getField<AppUiRuntime>(model, "runtime")
        runtime.uiState = runtime.uiState.copy(screenStack = listOf(AppDestination.SPLASH))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { model.awaitReady() }
        yield()
        assertFalse(waiting.isCompleted)
        runtime.uiState = runtime.uiState.copy(screenStack = listOf(AppDestination.HOME))
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        yield()
        withTimeout(2_000) { waiting.await() }
        assertTrue(waiting.isCompleted)
        model.awaitReady()
    }

    private fun supportViewModel(): MainViewModel {
        val model = MainViewModel(application, SavedStateHandle()).also { it.viewModelScope.cancel() }
        val runtime = org.robolectric.util.ReflectionHelpers.getField<AppUiRuntime>(model, "runtime")
        val settings = runtime.repository.updateSettings { previous -> previous.copy(isAuthenticated = true,
            backendAccessToken = "noki-access", backendRefreshToken = "noki-refresh", backendDeviceId = "device-1",
            userProfile = previous.userProfile.copy(backendUserId = "user-1")) }
        runtime.authSessionCoordinator.restore(settings)
        runtime.uiState = AppUiState(isReady = true, isAuthenticated = true,
            installOnboardingStep = com.noki.vpn.data.InstallOnboardingStep.COMPLETE,
            userProfile = settings.userProfile, screenStack = listOf(AppDestination.HOME))
        return model
    }

    private fun tap(notification: Notification): Intent {
        notification.contentIntent.send()
        return requireNotNull(shadowOf(application).nextStartedActivity)
    }

    private fun action(intent: Intent) = intent.getStringExtra(MainActivity.EXTRA_APP_NOTIFICATION_ACTION)

    private fun nonce(intent: Intent) = intent.getStringExtra(MainActivity.EXTRA_APP_NOTIFICATION_ACTION_NONCE)

    private fun accepts(intent: Intent) = AppNotificationActionNonceStore.validateAndConsume(
        context = application,
        action = action(intent),
        nonce = nonce(intent),
    ) != null
}
