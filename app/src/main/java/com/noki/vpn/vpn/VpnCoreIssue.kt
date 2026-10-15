package com.noki.vpn.vpn

import java.util.Locale

enum class VpnCoreIssue(
    val logMessage: String,
) {
    DNS_TIMEOUT("dns_timeout"),
    PROXY_TCP_TIMEOUT("proxy_tcp_timeout");

    companion object {
        fun fromThrowable(error: Throwable): VpnCoreIssue? {
            var current: Throwable? = error
            while (current != null) {
                fromDiagnosticText("${current::class.java.name}: ${current.message.orEmpty()}")?.let { return it }
                current = current.cause
            }
            return null
        }

        fun fromDiagnosticText(text: String?): VpnCoreIssue? {
            val value = text?.lowercase(Locale.ROOT).orEmpty()
            if (value.isBlank() || !value.hasTimeoutMarker()) return null
            if (value.isDnsTimeout()) return DNS_TIMEOUT
            if (value.isProxyTcpTimeout()) return PROXY_TCP_TIMEOUT
            return null
        }

        private fun String.hasTimeoutMarker(): Boolean =
            contains("context deadline exceeded") ||
                contains("i/o timeout") ||
                contains("operation timed out") ||
                contains("timeout")

        private fun String.isDnsTimeout(): Boolean =
            contains("dns_timeout") ||
                contains("app/dns") ||
                contains("dns-query") ||
                contains("from dns")

        private fun String.isProxyTcpTimeout(): Boolean =
            contains("dial tcp") ||
                contains("connect tcp") ||
                contains("proxy") ||
                contains("outbound") ||
                contains("tcp")
    }
}
