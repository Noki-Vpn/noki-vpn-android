package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalMergedSource
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.prismalBlur
import com.styropyr0.prismal.effects.prismalLens
import com.styropyr0.prismal.effects.vibrancy
import com.styropyr0.prismal.specular.PrismalSpecular
import com.noki.vpn.R
import kotlin.math.roundToInt

private val NavStroke = Color(0xFF29404E)

@Composable
internal fun HomeBottomNavigation(
    modifier: Modifier,
    scale: Float,
    backdrop: PrismalBackdrop?,
    liveGlassEnabled: Boolean = true,
    selectedTabIndex: Int,
    hasUnreadAppNotifications: Boolean = false,
    isAndroidUpdateAvailable: Boolean = false,
    onTabSelected: (Int) -> Unit,
) {
    val containerColor = Color(0xFF0D1B2A).copy(alpha = 0.5f)
    val activeSurfaceColor = Color(0xFF132635).copy(alpha = 0.05f)
    val capsuleShape = RoundedCornerShape(percent = 50)
    val capsulePadding = navDp(5f, scale)
    val activeCapsuleWidth = navDp(128f, scale)
    val activeCapsuleHeight = navDp(50f, scale)
    val clampedSelectedTabIndex = selectedTabIndex.coerceIn(0, 2)
    val attentionIndicatorTabs = PrimaryNavigationPolicy.attentionIndicatorTabs(
        hasUnreadAppNotifications = hasUnreadAppNotifications,
        isAndroidUpdateAvailable = isAndroidUpdateAvailable,
    )
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    val interactionSource = remember { MutableInteractionSource() }
    val navigationSurfaceBackdrop = if (liveGlassEnabled) rememberPrismalGlassLayer() else null
    val iconsBackdrop = if (liveGlassEnabled) rememberPrismalGlassLayer() else null
    val animationScope = rememberCoroutineScope()
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val tabsCount = 3
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()
        val activeCapsuleWidthPx = with(density) { activeCapsuleWidth.toPx() }
        val activeCapsuleHeightPx = with(density) { activeCapsuleHeight.toPx() }
        val capsulePaddingPx = with(density) { capsulePadding.toPx() }
        val buttonTravelPx = containerWidthPx - (capsulePaddingPx * 2f) - activeCapsuleWidthPx
        val buttonStepPx = buttonTravelPx / (tabsCount - 1)
        val iconCenterXs = List(tabsCount) { index ->
            capsulePaddingPx + activeCapsuleWidthPx / 2f + buttonStepPx * index
        }
        val clickTargetWidthPx = buttonStepPx

        val dampedDragAnimation = remember(animationScope, buttonStepPx, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = clampedSelectedTabIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex =
                        targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    animateToValue(targetIndex.toFloat())
                    currentOnTabSelected(targetIndex)
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / buttonStepPx)
                            .coerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                },
            )
        }

        LaunchedEffect(clampedSelectedTabIndex, dampedDragAnimation) {
            if (dampedDragAnimation.targetValue.roundToInt() != clampedSelectedTabIndex) {
                dampedDragAnimation.animateToValue(clampedSelectedTabIndex.toFloat())
            }
        }

        fun animateTabSelection(targetIndex: Int) {
            val clampedIndex = targetIndex.coerceIn(0, tabsCount - 1)
            dampedDragAnimation.animateToValue(clampedIndex.toFloat())
        }

        fun selectTab(targetIndex: Int) {
            val clampedIndex = targetIndex.coerceIn(0, tabsCount - 1)
            animateTabSelection(clampedIndex)
            currentOnTabSelected(clampedIndex)
        }

        val activeCapsuleOffsetPx = dampedDragAnimation.value * buttonStepPx
        val activeButtonBackdrop =
            if (
                liveGlassEnabled &&
                navigationSurfaceBackdrop != null &&
                iconsBackdrop != null
            ) {
                rememberPrismalMergedSource(navigationSurfaceBackdrop, iconsBackdrop)
            } else {
                null
            }

        @Composable
        fun IconsContent() {
            HomeBottomNavigationIconVisual(
                centerXPx = iconCenterXs[0],
                targetWidthPx = clickTargetWidthPx,
                containerHeightPx = containerHeightPx,
                resId = R.drawable.home_nav_active_icon_vector,
                iconWidth = navDp(18f, scale),
                iconHeight = navDp(30f, scale),
            )
            HomeBottomNavigationIconVisual(
                centerXPx = iconCenterXs[1],
                targetWidthPx = clickTargetWidthPx,
                containerHeightPx = containerHeightPx,
                rawSvgResId = R.raw.account_nav_profile,
                iconWidth = navDp(27f, scale),
                iconHeight = navDp(30f, scale),
            )
            HomeBottomNavigationIconVisual(
                centerXPx = iconCenterXs[2],
                targetWidthPx = clickTargetWidthPx,
                containerHeightPx = containerHeightPx,
                resId = R.drawable.home_nav_settings_icon_vector,
                iconWidth = navDp(23f, scale),
                iconHeight = navDp(25f, scale),
            )
        }

        @Composable
        fun IconBackdropSourceLayer() {
            val sourceBackdrop = iconsBackdrop ?: return
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(capsuleShape)
                    .alpha(0f)
                    .prismalGlassLayer(sourceBackdrop),
            ) {
                IconsContent()
            }
        }

        @Composable
        fun IconLayer() {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(capsuleShape),
            ) {
                HomeBottomNavigationItem(
                    scale = scale,
                    contentDescription = "Home",
                    interactionSource = interactionSource,
                    onClick = {
                        selectTab(0)
                    },
                    centerXPx = iconCenterXs[0],
                    targetWidthPx = clickTargetWidthPx,
                    containerHeightPx = containerHeightPx,
                    resId = R.drawable.home_nav_active_icon_vector,
                    iconWidth = navDp(18f, scale),
                    iconHeight = navDp(30f, scale),
                )
                HomeBottomNavigationItem(
                    scale = scale,
                    contentDescription = "Account",
                    interactionSource = interactionSource,
                    onClick = {
                        selectTab(1)
                    },
                    centerXPx = iconCenterXs[1],
                    targetWidthPx = clickTargetWidthPx,
                    containerHeightPx = containerHeightPx,
                    rawSvgResId = R.raw.account_nav_profile,
                    iconWidth = navDp(27f, scale),
                    iconHeight = navDp(30f, scale),
                )
                HomeBottomNavigationItem(
                    scale = scale,
                    contentDescription = "Settings",
                    interactionSource = interactionSource,
                    onClick = {
                        selectTab(2)
                    },
                    centerXPx = iconCenterXs[2],
                    targetWidthPx = clickTargetWidthPx,
                    containerHeightPx = containerHeightPx,
                    resId = R.drawable.home_nav_settings_icon_vector,
                    iconWidth = navDp(23f, scale),
                    iconHeight = navDp(25f, scale),
                )
            }
        }

        @Composable
        fun AttentionIndicatorLayer() {
            if (1 in attentionIndicatorTabs) {
                HomeBottomNavigationAttentionDot(
                    centerXPx = iconCenterXs[1],
                    targetWidthPx = clickTargetWidthPx,
                    containerHeightPx = containerHeightPx,
                    iconWidth = navDp(27f, scale),
                    iconHeight = navDp(30f, scale),
                    scale = scale,
                )
            }
            if (2 in attentionIndicatorTabs) {
                HomeBottomNavigationAttentionDot(
                    centerXPx = iconCenterXs[2],
                    targetWidthPx = clickTargetWidthPx,
                    containerHeightPx = containerHeightPx,
                    iconWidth = navDp(23f, scale),
                    iconHeight = navDp(25f, scale),
                    scale = scale,
                )
            }
        }

        @Composable
        fun ButtonLayer() {
            val velocity = dampedDragAnimation.velocity / 10f
            val movingScaleX = 1f + (dampedDragAnimation.scaleX - 1f) * 0.42f
            val velocityScaleX =
                1f / (1f - (velocity * 0.28f).coerceIn(-0.08f, 0.08f))
            val desiredScaleX = (movingScaleX * velocityScaleX)
                .coerceIn(1f, 1.28f)
            val scaledWidthPx = activeCapsuleWidthPx * desiredScaleX
            val scaledLeftPx = capsulePaddingPx +
                activeCapsuleOffsetPx +
                (activeCapsuleWidthPx - scaledWidthPx) / 2f
            val scaledRightPx = scaledLeftPx + scaledWidthPx
            val activeLayerTranslationX = when {
                scaledLeftPx < 0f -> -scaledLeftPx
                scaledRightPx > containerWidthPx -> containerWidthPx - scaledRightPx
                else -> 0f
            }
            val maxScaleY = containerHeightPx / activeCapsuleHeightPx
            val desiredScaleY = (
                1f + (dampedDragAnimation.scaleY - 1f) * 0.72f
                )
                .coerceAtMost(maxScaleY)
                .let { it * (1f - (velocity * 0.18f).coerceIn(-0.12f, 0.12f)) }
                .coerceAtMost(maxScaleY)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = capsulePadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = activeCapsuleOffsetPx.roundToInt(),
                                y = 0,
                            )
                        }
                        .width(activeCapsuleWidth)
                        .height(activeCapsuleHeight),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (liveGlassEnabled) {
                                    Modifier
                                } else {
                                    Modifier.graphicsLayer {
                                        translationX = activeLayerTranslationX
                                        scaleX = desiredScaleX
                                        scaleY = desiredScaleY
                                    }
                                },
                            )
                            .then(
                                if (liveGlassEnabled && activeButtonBackdrop != null) {
                                    Modifier.drawPrismalGlass(
                                        backdrop = activeButtonBackdrop,
                                        shape = { capsuleShape },
                                        effects = {
                                            val progress = dampedDragAnimation.pressProgress
                                            prismalLens(
                                                refractionHeight = 10f.dp.toPx() * progress,
                                                refractionAmount = 14f.dp.toPx() * progress,
                                                chromaticAberration = 1f,
                                            )
                                        },
                                        specular = {
                                            PrismalSpecular.Default.copy(alpha = dampedDragAnimation.pressProgress)
                                        },
                                        depthShadow = null,
                                        depthInset = null,
                                        layerBlock = {
                                            translationX = activeLayerTranslationX
                                            scaleX = desiredScaleX
                                            scaleY = desiredScaleY
                                        },
                                        onDrawSurface = {
                                            drawRect(activeSurfaceColor)
                                        },
                                    )
                                } else {
                                    Modifier.background(
                                        glassSurfaceColor(activeSurfaceColor.copy(alpha = 0.24f), liveGlassEnabled),
                                        capsuleShape,
                                    )
                                },
                            )
                            .then(
                                if (liveGlassEnabled) {
                                    Modifier.border(BorderStroke(1.dp, NavStroke), capsuleShape)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }

        @Composable
        fun ButtonDragHandleLayer() {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = capsulePadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = activeCapsuleOffsetPx.roundToInt(),
                                y = 0,
                            )
                        }
                        .then(dampedDragAnimation.modifier)
                        .width(activeCapsuleWidth)
                        .height(activeCapsuleHeight),
                )
            }
        }

        if (!liveGlassEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(capsuleShape)
                    .background(
                        glassSurfaceColor(containerColor.copy(alpha = 0.42f), liveGlassEnabled),
                        capsuleShape,
                    ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(capsuleShape)
                .then(
                    if (liveGlassEnabled && backdrop != null) {
                        Modifier.drawPrismalGlass(
                            backdrop = backdrop,
                            shape = { capsuleShape },
                            effects = {
                                vibrancy()
                                prismalBlur(8f.dp.toPx())
                                prismalLens(24f.dp.toPx(), 24f.dp.toPx())
                            },
                            nestedGlassSource = navigationSurfaceBackdrop,
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(
                            glassSurfaceColor(containerColor.copy(alpha = 0.28f), liveGlassEnabled),
                            capsuleShape,
                        )
                    },
                )
                .then(
                    if (liveGlassEnabled) {
                        Modifier.border(BorderStroke(1.dp, NavStroke), capsuleShape)
                    } else {
                        Modifier
                    },
                ),
        )

        if (!liveGlassEnabled) {
            ButtonLayer()
            IconLayer()
        } else {
            IconLayer()
            if (iconsBackdrop != null) {
                IconBackdropSourceLayer()
            }
            ButtonLayer()
        }
        AttentionIndicatorLayer()
        ButtonDragHandleLayer()
    }
}

@Composable
private fun HomeBottomNavigationAttentionDot(
    centerXPx: Float,
    targetWidthPx: Float,
    containerHeightPx: Float,
    iconWidth: Dp,
    iconHeight: Dp,
    scale: Float,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .width(with(density) { targetWidthPx.toDp() })
            .height(with(density) { containerHeightPx.toDp() })
            .offset {
                IntOffset(
                    x = (centerXPx - targetWidthPx / 2f).roundToInt(),
                    y = 0,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(iconWidth)
                .height(iconHeight),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = navDp(-3.5f, scale))
                    .size(navDp(7f, scale))
                    .background(SettingsError, CircleShape),
            )
        }
    }
}

@Composable
private fun HomeBottomNavigationIconVisual(
    centerXPx: Float,
    targetWidthPx: Float,
    containerHeightPx: Float,
    resId: Int? = null,
    rawSvgResId: Int? = null,
    iconWidth: Dp,
    iconHeight: Dp,
) {
    Box(
        modifier = Modifier
            .width(with(LocalDensity.current) { targetWidthPx.toDp() })
            .height(with(LocalDensity.current) { containerHeightPx.toDp() })
            .offset {
                IntOffset(
                    x = (centerXPx - targetWidthPx / 2f).roundToInt(),
                    y = 0,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val iconModifier = Modifier
            .width(iconWidth)
            .height(iconHeight)
        if (rawSvgResId != null) {
            FigmaSvgAsset(
                resId = rawSvgResId,
                viewportWidth = 27,
                viewportHeight = 30,
                modifier = iconModifier,
            )
        } else if (resId != null) {
            NavVector(resId = resId, modifier = iconModifier)
        }
    }
}

@Composable
private fun HomeBottomNavigationItem(
    scale: Float,
    contentDescription: String,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    centerXPx: Float,
    targetWidthPx: Float,
    containerHeightPx: Float,
    resId: Int? = null,
    rawSvgResId: Int? = null,
    iconWidth: Dp,
    iconHeight: Dp,
) {
    Box(
        modifier = Modifier
            .width(with(LocalDensity.current) { targetWidthPx.toDp() })
            .height(with(LocalDensity.current) { containerHeightPx.toDp() })
            .offset {
                IntOffset(
                    x = (centerXPx - targetWidthPx / 2f).roundToInt(),
                    y = 0,
                )
            }
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        HomeBottomNavigationIconVisual(
            centerXPx = targetWidthPx / 2f,
            targetWidthPx = targetWidthPx,
            containerHeightPx = containerHeightPx,
            resId = resId,
            rawSvgResId = rawSvgResId,
            iconWidth = iconWidth,
            iconHeight = iconHeight,
        )
    }
}

@Composable
private fun NavVector(
    resId: Int,
    modifier: Modifier,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = modifier,
    )
}

private fun navDp(value: Float, scale: Float): Dp = (value * scale).dp
