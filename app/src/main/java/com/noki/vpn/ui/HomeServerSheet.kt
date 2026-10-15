package com.noki.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
@Composable
internal fun HomeServerDropdownOverlay(
    modifier: Modifier,
    visible: Boolean,
    scale: Float,
    language: AppLanguage,
    locations: List<ServerLocation>,
    onCollapse: () -> Unit,
    onLocationSelected: (String, ServerSelectionMode) -> Unit,
    userProfile: UserProfile,
    foreignNodes: List<RuForeignNode> = emptyList(),
    ruRelayEnabled: Boolean = false,
) {
    val menuLocations = remember(locations, foreignNodes, ruRelayEnabled, language) {
        serverMenuLocations(locations, foreignNodes, ruRelayEnabled, language)
    }
    var expandedCountry by remember { mutableStateOf<String?>(null) }
    NokiServerSheetOverlay(
        modifier = modifier,
        visible = visible,
        scale = scale,
        onCollapse = onCollapse,
    ) { contentModifier ->
        HomeServerCountryList(
            modifier = contentModifier
                .fillMaxWidth()
                .padding(horizontal = designDp(NokiUiKitPolicy.homeServerItemHorizontalPaddingDp, scale))
                .padding(top = designDp(HOME_SERVER_DROPDOWN_FIRST_ITEM_TOP_DP, scale)),
            scale = scale, language = language, entries = menuLocations,
            selectionMode = userProfile.serverSelectionMode,
            selectedCountryCode = userProfile.selectedCountryCode,
            selectedNodeId = userProfile.selectedNodeId,
            expandedCountry = expandedCountry,
            onExpandedCountryChange = { expandedCountry = it },
            onNodeSelected = { onLocationSelected(it, ServerSelectionMode.SERVER) },
            onCountrySelected = { onLocationSelected(it, ServerSelectionMode.COUNTRY) },
            onAutomaticSelected = { onLocationSelected("", ServerSelectionMode.AUTO) },
        )
    }
}

@Composable
internal fun HomeServerCountryList(
    modifier: Modifier,
    scale: Float,
    language: AppLanguage,
    entries: List<HomeServerMenuEntry>,
    selectionMode: ServerSelectionMode?,
    selectedCountryCode: String?,
    selectedNodeId: String?,
    expandedCountry: String?,
    onExpandedCountryChange: (String?) -> Unit,
    onNodeSelected: (String) -> Unit,
    onCountrySelected: ((String) -> Unit)? = null,
    onAutomaticSelected: (() -> Unit)? = null,
) {
    val gap = designDp(NokiUiKitPolicy.homeServerItemGapDp, scale)
    val radius = designDp(24f, scale)
    LazyColumn(modifier = modifier) {
        if (onAutomaticSelected != null) item(key = "auto") {
            Box(
                Modifier.fillMaxWidth().padding(bottom = gap).homeServerItemSurface(
                    shape = RoundedCornerShape(radius),
                    selected = selectionMode == ServerSelectionMode.AUTO,
                ),
            ) {
                HomeServerMenuItem(
                    location = null,
                    language = language,
                    scale = scale,
                    title = tr(language, "Автовыбор", "Automatic"),
                    selected = selectionMode == ServerSelectionMode.AUTO,
                    onClick = onAutomaticSelected,
                )
            }
        }
        item(key = "countries-header") {
            Text(
                text = tr(language, "Страны", "Countries"),
                color = HomeTextSecondary,
                fontFamily = ManropeFontFamily,
                fontSize = designSp(12f, scale),
                modifier = Modifier.padding(horizontal = designDp(4f, scale)).padding(bottom = gap),
            )
        }
        entries.forEachIndexed { entryIndex, entry ->
            val groupGap = if (entryIndex == entries.lastIndex) 0.dp else gap
            val countrySelected = selectionMode == ServerSelectionMode.COUNTRY &&
                (entry.key.equals(selectedCountryCode, true) ||
                    entry.countryCode.equals(selectedCountryCode, true))
            val totalNodes = entry.servers.size + entry.foreignNodes.size
            val hasServers = totalNodes > 0
            val expanded = expandedCountry == entry.key && hasServers
            val toggleCountry = { onExpandedCountryChange(if (expanded) null else entry.key) }
            item(key = "country:${entry.key}", contentType = "country") {
                val shape = if (expanded) RoundedCornerShape(topStart = radius, topEnd = radius)
                    else RoundedCornerShape(radius)
                Box(Modifier.fillMaxWidth().padding(bottom = if (expanded) 0.dp else groupGap)
                    .homeServerItemSurface(shape, selected = countrySelected)) {
                    HomeServerMenuItem(
                        location = entry.location,
                        title = entry.country,
                        countryCode = entry.countryCode,
                        language = language,
                        scale = scale,
                        latencyMs = entry.latencyMs,
                        selected = countrySelected,
                        expanded = expanded,
                        onExpand = if (hasServers) toggleCountry else null,
                        onClick = if (onCountrySelected != null) {
                            { onCountrySelected(entry.key) }
                        } else toggleCountry,
                    )
                }
            }
            if (expanded) {
                item(key = "divider:${entry.key}", contentType = "divider") {
                    Box(Modifier.fillMaxWidth().background(homeServerItemSurfaceColor(countrySelected))) {
                        HorizontalDivider(Modifier.padding(horizontal = designDp(18f, scale)), color = HomeStroke)
                    }
                }
                fun childModifier(index: Int): Modifier {
                    val last = index == totalNodes - 1
                    val bottomRadius = if (last) radius else 0.dp
                    return Modifier.fillMaxWidth().padding(bottom = if (last) groupGap else 0.dp)
                        .homeServerItemSurface(
                            RoundedCornerShape(bottomStart = bottomRadius, bottomEnd = bottomRadius),
                            selected = countrySelected,
                        )
                        .padding(horizontal = designDp(8f, scale))
                        .padding(top = if (index == 0) designDp(8f, scale) else 0.dp,
                            bottom = if (last) designDp(8f, scale) else 0.dp)
                }
                itemsIndexed(entry.servers, key = { _, server -> "server:${entry.key}:${server.id}" },
                    contentType = { _, _ -> "server" }) { index, server ->
                    val serverSelected = selectionMode == ServerSelectionMode.SERVER && selectedNodeId == server.id
                    Box(childModifier(index)) {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(designDp(16f, scale)))
                            .then(if (serverSelected) Modifier.background(homeServerItemSurfaceColor(true)) else Modifier)) {
                            HomeServerMenuItem(
                                location = entry.location, title = server.name, countryCode = server.countryCode,
                                language = language, scale = scale, latencyMs = server.latencyMs,
                                online = server.isOnline, selected = serverSelected, serverRow = true,
                                onClick = { onNodeSelected(server.id) },
                            )
                        }
                    }
                }
                itemsIndexed(entry.foreignNodes, key = { _, node -> "foreign:${entry.key}:${node.id}" },
                    contentType = { _, _ -> "server" }) { index, node ->
                    val serverSelected = selectionMode == ServerSelectionMode.SERVER && selectedNodeId == node.id
                    Box(childModifier(entry.servers.size + index)) {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(designDp(16f, scale)))
                            .then(if (serverSelected) Modifier.background(homeServerItemSurfaceColor(true)) else Modifier)) {
                            HomeServerMenuItem(
                                location = null, title = node.name, countryCode = node.countryCode,
                                language = language, scale = scale, latencyMs = node.latencyMs,
                                selected = serverSelected, serverRow = true,
                                onClick = { onNodeSelected(node.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NokiServerSheetOverlay(
    modifier: Modifier,
    visible: Boolean,
    scale: Float,
    onCollapse: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val sheetDismissThresholdPx = with(density) { 96.dp.toPx() }
    val dismissDirection = -1f
    val firstServerItemTopPx = with(density) {
        designDp(HOME_SERVER_DROPDOWN_FIRST_ITEM_TOP_DP, scale).toPx()
    }
    val sheetDragOffset = remember { Animatable(0f) }
    val sheetDragScope = rememberCoroutineScope()
    var sheetHeightPx by remember(visible) { mutableFloatStateOf(0f) }
    val passthroughBlockerInteractionSource = remember { MutableInteractionSource() }
    LaunchedEffect(visible, sheetDragOffset) {
        if (visible) {
            sheetDragOffset.snapTo(0f)
        }
    }

    fun animateSheetBack() {
        sheetDragScope.launch {
            sheetDragOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            )
        }
    }

    fun sheetExitDistancePx(sheetHeightPx: Float): Float {
        return sheetHeightPx.coerceAtLeast(sheetDismissThresholdPx * 2f)
    }

    fun handleFadeEndDistancePx(sheetHeightPx: Float): Float {
        val exitDistancePx = sheetExitDistancePx(sheetHeightPx)
        val firstItemBoundaryPx = firstServerItemTopPx.coerceIn(0f, exitDistancePx)
        return (exitDistancePx - firstItemBoundaryPx)
            .coerceAtLeast(exitDistancePx * HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION)
    }

    fun handleAlphaForSheetOffset(offsetPx: Float): Float {
        val fadeEndDistancePx = handleFadeEndDistancePx(sheetHeightPx)
        val fadeStartDistancePx = fadeEndDistancePx * HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION
        val closeDistancePx = (offsetPx * dismissDirection).coerceAtLeast(0f)
        if (closeDistancePx <= fadeStartDistancePx) {
            return HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA
        }
        val fadeRangePx = (fadeEndDistancePx - fadeStartDistancePx).coerceAtLeast(1f)
        val fadeProgress = ((closeDistancePx - fadeStartDistancePx) / fadeRangePx)
            .coerceIn(0f, 1f)
        return HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA * (1f - fadeProgress)
    }

    fun handleFadeEndMillis(sheetHeightPx: Float): Int {
        val exitDistancePx = sheetExitDistancePx(sheetHeightPx)
        val fadeEndDistancePx = handleFadeEndDistancePx(sheetHeightPx)
        val fadeEndFraction = (fadeEndDistancePx / exitDistancePx).coerceIn(
            HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION,
            1f,
        )
        return (HOME_SERVER_DROPDOWN_EXIT_DURATION_MS * fadeEndFraction)
            .roundToInt()
            .coerceAtLeast((HOME_SERVER_DROPDOWN_EXIT_DURATION_MS *
                HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION).roundToInt() + 1)
    }

    fun settleSheetDrag() {
        sheetDragScope.launch {
            if (sheetDragOffset.value * dismissDirection >= sheetDismissThresholdPx) {
                onCollapse()
            } else {
                animateSheetBack()
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(
                durationMillis = HOME_SERVER_DROPDOWN_EXIT_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        ),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(
                durationMillis = HOME_SERVER_DROPDOWN_EXIT_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        ),
    ) {
        val transitionHandleAlpha by transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.PostExit) {
                    keyframes {
                        durationMillis = HOME_SERVER_DROPDOWN_EXIT_DURATION_MS
                        val fadeStartMillis = (HOME_SERVER_DROPDOWN_EXIT_DURATION_MS *
                            HOME_SERVER_DROPDOWN_HANDLE_FADE_START_FRACTION).roundToInt()
                        val fadeEndMillis = handleFadeEndMillis(sheetHeightPx)
                            .coerceAtMost(HOME_SERVER_DROPDOWN_EXIT_DURATION_MS)
                        HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA at 0
                        HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA at fadeStartMillis
                        0f at fadeEndMillis
                        0f at HOME_SERVER_DROPDOWN_EXIT_DURATION_MS
                    }
                } else {
                    tween(durationMillis = 180, easing = FastOutSlowInEasing)
                }
            },
            label = "homeServerDropdownHandleAlpha",
        ) { state ->
            if (state == EnterExitState.Visible) {
                HOME_SERVER_DROPDOWN_HANDLE_VISIBLE_ALPHA
            } else {
                0f
            }
        }
        val sheetRadius = designDp(NokiUiKitPolicy.homeServerDropdownSheetBottomRadiusDp, scale)
        val dropdownSheetShape = RoundedCornerShape(bottomStart = sheetRadius, bottomEnd = sheetRadius)
        val sheetModifier = Modifier
            .fillMaxSize()
            .padding(
                bottom = designDp(NokiUiKitPolicy.homeServerDropdownSheetBottomSpaceDp, scale),
            )
        val sheetVisualModifier = sheetModifier
            .onSizeChanged { sheetHeightPx = it.height.toFloat() }
            .offset { IntOffset(0, sheetDragOffset.value.roundToInt()) }
        val contentClipInset = designDp(
            NokiUiKitPolicy.homeServerDropdownHandleBottomPaddingDp +
                NokiUiKitPolicy.homeServerDropdownHandleHeightDp -
                NokiUiKitPolicy.homeServerDropdownSheetBottomSpaceDp,
            scale,
        ) + NokiUiKitPolicy.homeServerDropdownContentHandleGapDp.dp
        val sheetContentClipModifier = sheetVisualModifier.padding(bottom = contentClipInset)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = passthroughBlockerInteractionSource,
                    indication = null,
                    onClick = {},
                ),
        ) {
            Box(
                modifier = sheetVisualModifier.clip(dropdownSheetShape).background(SettingsBgSoft),
            )
            Box(
                modifier = sheetContentClipModifier.clip(dropdownSheetShape),
            ) {
                val contentModifier = Modifier
                    .offset { IntOffset(0, -sheetDragOffset.value.roundToInt()) }
                    .fillMaxSize()
                content(contentModifier)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(designDp(NokiUiKitPolicy.homeServerDropdownHandleTouchHeightDp, scale))
                    .offset { IntOffset(0, sheetDragOffset.value.roundToInt()) }
                    .pointerInput(visible, sheetDismissThresholdPx, sheetHeightPx) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                sheetDragScope.launch {
                                    sheetDragOffset.stop()
                                }
                            },
                            onDragEnd = {
                                settleSheetDrag()
                            },
                            onDragCancel = {
                                animateSheetBack()
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val targetHeightPx = sheetHeightPx
                                .coerceAtLeast(sheetDismissThresholdPx * 2f)
                            val proposedOffset = sheetDragOffset.value + dragAmount
                            val nextOffset = proposedOffset.coerceIn(-targetHeightPx, 0f)
                            sheetDragScope.launch {
                                sheetDragOffset.snapTo(nextOffset)
                            }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = designDp(
                                NokiUiKitPolicy.homeServerDropdownHandleBottomPaddingDp,
                                scale,
                            ),
                        )
                        .width(designDp(NokiUiKitPolicy.homeServerDropdownHandleWidthDp, scale))
                        .height(designDp(NokiUiKitPolicy.homeServerDropdownHandleHeightDp, scale))
                        .graphicsLayer {
                            alpha = transitionHandleAlpha.coerceAtMost(
                                handleAlphaForSheetOffset(sheetDragOffset.value),
                            )
                        }
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White),
                )
            }
        }
    }
}
