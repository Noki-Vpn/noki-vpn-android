package com.noki.vpn.data

import org.json.JSONObject

internal fun contractFixture(name: String): JSONObject {
    val resource = requireNotNull(
        BackendEndpointCandidate::class.java.getResourceAsStream("/contracts/$name"),
    ) { "Missing $name test resource" }
    return resource.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
}

internal fun tcpCandidate(
    code: String,
    priority: Int = 10,
    weight: Int = 100,
): BackendEndpointCandidate {
    return BackendEndpointCandidate(
        code = code,
        nodeId = "node",
        label = code,
        locationCode = "lv1",
        locationName = "Latvia",
        entryHost = "$code.example.com",
        entryPort = 443,
        serverName = "www.lu.lv",
        proxyType = "vless",
        transport = "tcp",
        transportMode = null,
        security = "reality",
        fingerprint = "chrome",
        requestHost = null,
        path = "/noki",
        alpn = null,
        allowInsecure = false,
        enableMux = false,
        randomUserAgent = false,
        publicKey = "public",
        shortId = "short",
        flow = "xtls-rprx-vision",
        priority = priority,
        weight = weight,
        canaryOnly = false,
        tags = emptyList(),
    )
}

internal fun hysteriaCandidate(
    code: String,
    priority: Int = 90,
): BackendEndpointCandidate {
    return tcpCandidate(code, priority).copy(
        entryPort = 2443,
        serverName = "$code.example.com",
        proxyType = "hysteria",
        transport = "hysteria",
        security = "tls",
        publicKey = null,
        shortId = null,
        flow = null,
    )
}
