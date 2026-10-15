package com.noki.vpn.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.styropyr0.prismal.sources.PrismalGlassLayer
@Composable
internal fun HomeVector(
    resId: Int,
    modifier: Modifier,
) {
    androidx.compose.foundation.Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
    )
}

@Composable
internal fun HomeQuickSettingsPill(
    text: String,
    iconResId: Int?,
    modifier: Modifier,
    scale: Float,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val glassScale = 1f
    HomeSettingsGlassSurface(
        modifier = modifier,
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = settingsDp(20f, glassScale)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconResId != null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(iconResId),
                    contentDescription = null,
                    modifier = Modifier
                        .width(designDp(19f, scale))
                        .height(designDp(15f, scale)),
                )
                Spacer(modifier = Modifier.width(designDp(10f, scale)))
            }
            Text(
                text = text,
                color = HomeTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = designSp(14f, scale),
                lineHeight = designSp(16.8f, scale),
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

@Composable
internal fun HomeSettingsGlassSurface(
    modifier: Modifier,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "homeSettingsGlassPress",
    )
    val glassScale = 1f
    val shape = RoundedCornerShape(settingsDp(20f, glassScale))
    Box(
        modifier = modifier
            .nokiGlassSurface(
                shape = shape,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                scale = glassScale,
                shadowAlpha = if (liveGlassEnabled) 0.05f else NokiUiKitPolicy.simpleSurfaceShadowAlpha,
                surfaceColor = SettingsTextMuted.copy(alpha = 0.05f),
                simpleSurfaceColor = SettingsBgSoft,
                blurAndLensEnabled = false,
                backdropShadowsEnabled = false,
                dropShadowRadiusDp = 4f,
                layerBlock = if (onClick != null) {
                    {
                        scaleX = pressScale
                        scaleY = pressScale
                    }
                } else {
                    null
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
