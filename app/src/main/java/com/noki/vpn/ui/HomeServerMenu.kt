package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.usesRuForeignNodeCatalog
import com.styropyr0.prismal.sources.PrismalGlassLayer
import java.util.Locale

internal data class HomeServerMenuEntry(
    val key: String,
    val location: ServerLocation?,
    val countryCode: String,
    val country: String,
    val foreignNodes: List<RuForeignNode> = emptyList(),
    val latencyMs: Int? = location?.latencyMs,
    val servers: List<VpnServer> = location?.servers.orEmpty(),
)

internal fun serverMenuLocations(
    locations: List<ServerLocation>,
    foreignNodes: List<RuForeignNode> = emptyList(),
    ruRelayEnabled: Boolean = false,
    language: AppLanguage = AppLanguage.EN,
): List<HomeServerMenuEntry> {
    if (usesRuForeignNodeCatalog(ruRelayEnabled, foreignNodes)) return foreignNodes.groupBy { it.countryCode }.toSortedMap().map { (code, nodes) ->
        HomeServerMenuEntry(code, null, code, Locale("", code).getDisplayCountry(Locale(language.tag)),
            nodes.sortedWith(compareBy({ it.name }, { it.id })),
            latencyMs = nodes.mapNotNull { it.latencyMs }.minOrNull())
    }
    return locations.map { location ->
        HomeServerMenuEntry(
            key = location.code,
            location = location,
            countryCode = location.countryCode,
            country = location.country,
        )
    }
}

internal fun serverMenuLocations(
    nodes: List<VpnServer>,
    language: AppLanguage,
    excludedNodeId: String?,
): List<HomeServerMenuEntry> = nodes.asSequence()
    .filter { it.isOnline && it.id != excludedNodeId && it.countryCode.isNotBlank() }
    .distinctBy { it.id }
    .groupBy { it.countryCode.trim().uppercase(Locale.ROOT) }
    .toSortedMap()
    .map { (code, members) ->
        HomeServerMenuEntry(
            key = code, location = null, countryCode = code,
            country = Locale("", code).getDisplayCountry(Locale(language.tag)).ifBlank { code },
            latencyMs = members.mapNotNull { it.latencyMs }.minOrNull(),
            servers = members.sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.id })),
        )
    }

@Composable
internal fun HomeDropdownArrowButton(
    modifier: Modifier,
    scale: Float,
    arrowRotation: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = 1f,
                elevationDp = 14f,
                shadowAlpha = if (liveGlassEnabled) 0.05f else NokiUiKitPolicy.simpleSurfaceShadowAlpha,
                highlightAlpha = 0.30f,
                surfaceColor = SettingsTextMuted.copy(alpha = 0.05f),
                simpleSurfaceColor = SettingsBgSoft,
                blurAndLensEnabled = false,
                backdropShadowsEnabled = false,
                dropShadowRadiusDp = 4f,
            ),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = arrowRotation
                },
        ) {
            val strokeWidth = designDp(2.2f, scale).toPx()
            val arrowHalfWidth = designDp(6.2f, scale).toPx()
            val arrowHalfHeight = designDp(4.0f, scale).toPx()
            val arrowCenterY = center.y + designDp(1f, scale).toPx()
            drawLine(
                color = HomeAccentPrimary,
                start = Offset(center.x - arrowHalfWidth, arrowCenterY - arrowHalfHeight),
                end = Offset(center.x, arrowCenterY + arrowHalfHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = HomeAccentPrimary,
                start = Offset(center.x, arrowCenterY + arrowHalfHeight),
                end = Offset(center.x + arrowHalfWidth, arrowCenterY - arrowHalfHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun HomeServerMenuItem(
    location: ServerLocation?,
    language: AppLanguage,
    scale: Float,
    onClick: () -> Unit,
    title: String? = null,
    selected: Boolean = false,
    latencyMs: Int? = location?.latencyMs,
    online: Boolean = location?.isOnline ?: true,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    countryCode: String? = location?.countryCode,
    contentDescription: String? = null,
    serverRow: Boolean = false,
) {
    val countryText = title ?: location?.country.orEmpty()
    val latencyText = latencyMs?.let { "$it " + tr(language, "мс", "ms") } ?: "—"
    val markerSize = if (serverRow) 32f else NokiUiKitPolicy.homeServerItemFlagSizeDp
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "homeServerMenuItemPress",
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "countryServersArrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = designDp(if (serverRow) 68f else NokiUiKitPolicy.homeServerItemHeightDp, scale))
                .semantics {
                    this.selected = selected
                    if (contentDescription != null) this.contentDescription = contentDescription
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = online,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(start = designDp(18f, scale), end = designDp(if (onExpand == null) 18f else 0f, scale))
                .padding(vertical = designDp(12f, scale)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (countryCode != null && !serverRow) {
                CountryFlagMarker(countryCode = countryCode, scale = scale,
                    sizeDp = markerSize)
            } else if (!serverRow) {
                AutomaticServerMarker(scale, markerSize)
            } else {
                Box(
                    modifier = Modifier
                        .size(designDp(markerSize, scale))
                        .clip(RoundedCornerShape(designDp(14f, scale))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Dns,
                        contentDescription = null,
                        tint = if (!selected) HomeTextSecondary else HomeAccentPrimary,
                        modifier = Modifier.size(designDp(21f, scale)),
                    )
                }
            }
            Spacer(modifier = Modifier.width(designDp(14f, scale)))
            Text(
                text = countryText,
                color = if (online) HomeTextPrimary else HomeTextSecondary,
                fontFamily = ManropeFontFamily,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = designSp(if (serverRow) 17f else NokiUiKitPolicy.homeServerItemNameTextSp, scale),
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(designDp(10f, scale)))
            if (countryCode != null || serverRow) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(designDp(6f, scale))) {
                    Text(
                        text = latencyText,
                        color = if (online && latencyMs != null) HomeAccentPrimary else HomeTextSecondary,
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = designSp(NokiUiKitPolicy.homeServerItemMetricTextSp, scale),
                        letterSpacing = 0.sp,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Right,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    )
                    Box(Modifier.size(designDp(14f, scale))) {
                        OnlineStatusIndicator(online = online, animate = false)
                    }
                }
            }
        }
        if (onExpand != null) {
            val expandLabel = tr(language, "Серверы: ", "Servers: ") + countryText
            val expandedLabel = if (expanded) tr(language, "Развернуто", "Expanded") else tr(language, "Свернуто", "Collapsed")
            Box(
                modifier = Modifier.size(designDp(48f, scale).coerceAtLeast(48.dp))
                    .semantics { stateDescription = expandedLabel }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onExpand,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = expandLabel,
                    tint = HomeTextSecondary,
                    modifier = Modifier.size(designDp(24f, scale)).graphicsLayer { rotationZ = arrowRotation },
                )
            }
        }
    }
}

@Composable
internal fun AutomaticServerMarker(scale: Float, sizeDp: Float) {
    Box(
        Modifier.size(designDp(sizeDp, scale))
            .clip(RoundedCornerShape(designDp(14f, scale)))
            .background(lerp(SettingsBgSoft, HomeAccentPrimary, 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null,
            tint = HomeAccentPrimary, modifier = Modifier.size(designDp(24f, scale)))
    }
}

internal fun Modifier.homeServerItemSurface(
    shape: Shape,
    selected: Boolean = false,
): Modifier = clip(shape).background(homeServerItemSurfaceColor(selected))

internal fun homeServerItemSurfaceColor(selected: Boolean): Color = lerp(
    SettingsBgSoft,
    if (selected) HomeAccentPrimary else HomeTextPrimary,
    if (selected) 0.18f else 0.07f,
)

@Composable
internal fun CountryFlagMarker(
    countryCode: String,
    scale: Float,
    sizeDp: Float = 46f,
) {
    val context = LocalContext.current
    val resourceName = countryMarkerCode(countryCode)?.let { "flag_${it.lowercase(Locale.ROOT)}" }
    val resourceId = remember(context, resourceName) {
        resourceName?.let { context.resources.getIdentifier(it, "raw", context.packageName) } ?: 0
    }
    val markerSize = designDp(sizeDp, scale)
    Box(
        modifier = Modifier
            .size(markerSize)
            .clip(RoundedCornerShape(percent = 50))
            .background(HomeStroke),
        contentAlignment = Alignment.Center,
    ) {
        if (resourceId != 0) {
            FigmaSvgAsset(
                resId = resourceId,
                viewportWidth = 512,
                viewportHeight = 512,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "??",
                color = HomeTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = designSp(15f, scale),
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true)),
            )
        }
    }
}

@Composable
internal fun OnlineStatusIndicator(
    online: Boolean,
    animate: Boolean,
) {
    if (!online) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = HomeTextSecondary.copy(alpha = 0.56f),
                radius = size.minDimension * 0.18f,
                center = center,
            )
        }
        return
    }

    if (!animate) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = HomeAccentPrimary,
                radius = size.minDimension * 0.2f,
                center = center,
            )
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "serverOnlinePulse")
    val pulseProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "serverOnlinePulseProgress",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val baseRadius = size.minDimension * 0.2f
        val maxRadius = size.minDimension * 0.48f

        fun drawPulse(phase: Float) {
            val radius = baseRadius + (maxRadius - baseRadius) * phase
            val alpha = (1f - phase).coerceIn(0f, 1f) * 0.24f
            if (alpha > 0.01f) {
                drawCircle(
                    color = HomeAccentPrimary.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                )
            }
        }

        drawPulse(pulseProgress)
        drawPulse((pulseProgress + 0.5f) % 1f)
        drawCircle(
            color = HomeAccentPrimary.copy(alpha = 0.18f),
            radius = size.minDimension * 0.32f,
            center = center,
        )
        drawCircle(
            color = HomeAccentPrimary,
            radius = baseRadius,
            center = center,
        )
    }
}

internal fun countryMarkerCode(location: ServerLocation): String? = countryMarkerCode(location.countryCode)

internal fun countryMarkerCode(countryCode: String): String? {
    return countryCode
        .trim()
        .uppercase(Locale.ROOT)
        .takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
}

internal fun countryFlagResourceName(location: ServerLocation): String? =
    countryMarkerCode(location)?.let { code -> "flag_${code.lowercase(Locale.ROOT)}" }
