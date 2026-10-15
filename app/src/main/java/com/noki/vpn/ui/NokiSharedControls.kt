package com.noki.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalWrappedSource
import com.styropyr0.prismal.sources.rememberPrismalMergedSource
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.prismalLens
import com.styropyr0.prismal.specular.PrismalSpecular
import com.styropyr0.prismal.depth.PrismalDepthInset
import com.styropyr0.prismal.depth.PrismalDepthShadow

@Composable
internal fun NokiLiquidToggle(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    modifier: Modifier = Modifier,
    sizeFactor: Float = 1f,
) {
    SecurityLiquidToggle(
        selected = selected,
        onSelectedChange = onSelectedChange,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        sizeFactor = sizeFactor,
        modifier = modifier,
    )
}

@Composable
internal fun NokiLiquidThumb(
    modifier: Modifier,
    animation: DampedDragAnimation,
    backdrop: PrismalGlassLayer?,
    trackBackdrop: PrismalGlassLayer,
    liveGlassEnabled: Boolean,
    inactiveColor: Color,
    activeColor: Color,
    surfaceProgress: Float = animation.value,
    sizeFactor: Float = 1f,
) {
    val shape = RoundedCornerShape(percent = 50)
    if (liveGlassEnabled && backdrop != null) {
        Box(
            modifier = modifier.drawPrismalGlass(
                backdrop = rememberPrismalMergedSource(
                    backdrop,
                    rememberPrismalWrappedSource(trackBackdrop) { drawPrismalGlass ->
                        val progress = animation.pressProgress
                        val scaleX = lerpFloat(2f / 3f, 0.92f, progress)
                        val scaleY = lerpFloat(0f, 0.75f, progress)
                        scale(scaleX, scaleY) {
                            drawPrismalGlass()
                        }
                    },
                ),
                shape = { shape },
                effects = {
                    val progress = animation.pressProgress
                    prismalLens(
                        (5f * sizeFactor).dp.toPx() * progress,
                        (10f * sizeFactor).dp.toPx() * progress,
                        chromaticAberration = 1f,
                    )
                },
                specular = {
                    PrismalSpecular.Ambient.copy(
                        width = PrismalSpecular.Ambient.width / 1.5f,
                        blurRadius = PrismalSpecular.Ambient.blurRadius / 1.5f,
                        alpha = animation.pressProgress,
                    )
                },
                depthShadow = {
                    PrismalDepthShadow(
                        radius = (4f * sizeFactor).dp,
                        color = Color.Black.copy(alpha = 0.05f),
                    )
                },
                depthInset = {
                    PrismalDepthInset(
                        radius = (4f * sizeFactor).dp * animation.pressProgress,
                        alpha = animation.pressProgress,
                    )
                },
                layerBlock = {
                    scaleX = animation.scaleX
                    scaleY = animation.scaleY
                    val velocity = animation.velocity / 50f
                    scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                    scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                },
                onDrawSurface = {
                    val animationMix = maxOf(animation.pressProgress, surfaceProgress)
                    drawRect(
                        lerpColor(inactiveColor, activeColor, animationMix)
                            .copy(alpha = 1f - animation.pressProgress),
                    )
                },
            ),
        )
    } else {
        Box(
            modifier = modifier
                .graphicsLayer {
                    scaleX = animation.scaleX
                    scaleY = animation.scaleY
                }
                .background(lerpColor(inactiveColor, activeColor, surfaceProgress), shape),
        )
    }
}
