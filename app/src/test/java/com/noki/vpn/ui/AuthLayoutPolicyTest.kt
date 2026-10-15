package com.noki.vpn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.noki.vpn.vpn.VpnRuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthLayoutPolicyTest {
    @Test
    fun `keyboard shift is zero without IME`() {
        assertEquals(0.dp, authKeyboardShift(800.dp, 0.dp, 700.dp))
    }

    @Test
    fun `keyboard shift clears the full form even when its top scrolls offscreen`() {
        assertEquals(205.dp, authKeyboardShift(800.dp, 300.dp, 700.dp))
        assertEquals(0.dp, authKeyboardShift(800.dp, 300.dp, 400.dp))
    }

    @Test
    fun `button surface keeps arbitrary color opaque`() {
        assertEquals(Color.Red, authButtonSurfaceColor(Color.Red))
    }

    @Test
    fun `welcome inline hides temporary vpn progress statuses`() {
        assertEquals(
            null,
            welcomeInlineMessageForDisplay(
                inlineMessage = "status",
                vpnRuntimeMode = VpnRuntimeMode.AUTH_TEMP,
            ),
        )
    }

    @Test
    fun `Telegram error cancel disconnects active temporary vpn`() {
        var dismissed = false
        var disconnected = false

        handleTelegramErrorCancel(
            temporaryVpnActive = true,
            onDismiss = { dismissed = true },
            onDisconnectTemporaryVpn = { disconnected = true },
        )

        assertTrue(dismissed)
        assertTrue(disconnected)
    }

    @Test
    fun `Telegram error cancel does not toggle inactive temporary vpn`() {
        var disconnected = false

        handleTelegramErrorCancel(
            temporaryVpnActive = false,
            onDismiss = {},
            onDisconnectTemporaryVpn = { disconnected = true },
        )

        assertFalse(disconnected)
    }

    @Test
    fun `Telegram retry error remains after transient vpn failure clears`() {
        assertEquals(
            "Temporary connection limit exhausted",
            retainTelegramRetryError(
                retainedMessage = "Temporary connection limit exhausted",
                incomingMessage = null,
            ),
        )
    }

    @Test
    fun `temporary vpn modal retains its latest error until it closes`() {
        val incoming = retainTelegramRetryError(
            retainedMessage = null,
            incomingMessage = "Temporary connection limit exhausted",
        )

        assertEquals(
            "Temporary connection limit exhausted",
            retainTelegramRetryError(
                retainedMessage = incoming,
                incomingMessage = null,
            ),
        )
    }

    @Test
    fun `temporary vpn modal buttons are opaque only in simple mode`() {
        assertEquals(
            NokiBgSoft,
            temporaryVpnModalButtonSurfaceColor(liveGlassEnabled = false),
        )
        assertEquals(
            NokiBgSoft.copy(alpha = AuthBgLighterButtonSurfaceAlpha),
            temporaryVpnModalButtonSurfaceColor(liveGlassEnabled = true),
        )
    }
}
