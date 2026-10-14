package com.romangarms.autoxlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Immutable
data class EGColors(
    val bg: Color,
    val ink: Color,
    val red: Color,
    val darkRed: Color,
    val gray: Color,
    val grayDark: Color,
    val grayLight: Color,
    val hairline: Color,
    val pinnedBg: Color,
    val meBg: Color,
    val card: Color,
    val tabBg: Color,
    val dark: Boolean,
) {
    // Deliberately non-adaptive: sits on red, which stays red in both modes.
    val onRed = Color(0xFFF8F5F4)
    val divider: Color get() = ink.copy(alpha = 0.4f)
}

private val LightColors = EGColors(
    bg = Color(0xFFF3F2F2),
    ink = Color(0xFF201E1D),
    red = Color(0xFFEC3013),
    darkRed = Color(0xFFAE1800),
    gray = Color(0xFF7D7979),
    grayDark = Color(0xFF605D5D),
    grayLight = Color(0xFFBAB6B6),
    hairline = Color(0xFFD7D3D3),
    pinnedBg = Color(0xFFFFF2EF),
    meBg = Color(0xFFEAE7E7),
    card = Color(0xFFF8F4F4),
    tabBg = Color(0xFFEAE9E9),
    dark = false,
)

private val DarkColors = EGColors(
    bg = Color(0xFF171514),
    ink = Color(0xFFEDEBEA),
    red = Color(0xFFFF4A2C),
    darkRed = Color(0xFFE0523C),
    gray = Color(0xFF969191),
    grayDark = Color(0xFFB0ACAB),
    grayLight = Color(0xFF555151),
    hairline = Color(0xFF383534),
    pinnedBg = Color(0xFF2B1D19),
    meBg = Color(0xFF282625),
    card = Color(0xFF201E1D),
    tabBg = Color(0xFF232121),
    dark = true,
)

val LocalEGColors = staticCompositionLocalOf { LightColors }

object EG {
    val colors: EGColors
        @Composable get() = LocalEGColors.current

    // Sizes throughout are tuned for the default text size; this is how far
    // the system font scale has moved them.
    val textScale: Float
        @Composable get() = LocalDensity.current.fontScale

    // Tables on a phone run out of room for every column once text grows.
    val largeText: Boolean
        @Composable get() = textScale > LARGE_TEXT_SCALE

    const val MIN_TEXT_SCALE = 1.0f
    const val MAX_TEXT_SCALE = 1.6f
    const val LARGE_TEXT_SCALE = 1.15f
}

fun egStyle(size: Float, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) = TextStyle(
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = if (tracking == 0f) 0.sp else (tracking / size).em,
)

// For table columns, which have to widen with the text they hold.
@Composable
fun Modifier.egWidth(width: Float): Modifier = this.width(egDp(width))

@Composable
fun egDp(value: Float): Dp = (value * EG.textScale).dp

@Composable
fun AutoXTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    val density = LocalDensity.current
    // The tables stop fitting a phone beyond this, and the base sizes are
    // already too small to shrink.
    val clamped = remember(density) {
        Density(density.density, density.fontScale.coerceIn(EG.MIN_TEXT_SCALE, EG.MAX_TEXT_SCALE))
    }
    val scheme = if (colors.dark) {
        darkColorScheme(
            primary = colors.red,
            onPrimary = colors.onRed,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.card,
            onSurface = colors.ink,
            surfaceContainer = colors.card,
            surfaceContainerHigh = colors.card,
            surfaceContainerLow = colors.bg,
            onSurfaceVariant = colors.grayDark,
            outline = colors.divider,
            outlineVariant = colors.hairline,
            secondaryContainer = colors.pinnedBg,
            error = colors.red,
        )
    } else {
        lightColorScheme(
            primary = colors.red,
            onPrimary = colors.onRed,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.card,
            onSurface = colors.ink,
            surfaceContainer = colors.card,
            surfaceContainerHigh = colors.card,
            surfaceContainerLow = colors.bg,
            onSurfaceVariant = colors.grayDark,
            outline = colors.divider,
            outlineVariant = colors.hairline,
            secondaryContainer = colors.pinnedBg,
            error = colors.red,
        )
    }
    CompositionLocalProvider(LocalEGColors provides colors, LocalDensity provides clamped) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun EGText(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = EG.colors.ink,
    tracking: Float = 0f,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = egStyle(size, weight, tracking),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun EGTag(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = EG.colors.red,
    foreground: Color = EG.colors.onRed,
    size: Float = 11f,
) {
    EGText(
        text = text,
        size = size,
        weight = FontWeight.Black,
        tracking = 1.1f,
        color = foreground,
        maxLines = 1,
        modifier = modifier.background(background).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun EGOutlineTag(text: String, modifier: Modifier = Modifier) {
    EGText(
        text = text,
        size = 10f,
        weight = FontWeight.Black,
        tracking = 0.8f,
        maxLines = 1,
        modifier = modifier
            .border(1.5.dp, EG.colors.divider, RectangleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun EGColumnLabel(text: String, modifier: Modifier = Modifier, size: Float = 9.5f) {
    EGText(text = text, size = size, weight = FontWeight.Black, tracking = 0.9f, color = EG.colors.gray, maxLines = 1, modifier = modifier)
}

enum class EGButtonKind { PRIMARY, SECONDARY }

@Composable
fun EGButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: EGButtonKind = EGButtonKind.SECONDARY,
    enabled: Boolean = true,
) {
    val colors = EG.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = if (kind == EGButtonKind.PRIMARY) {
        modifier.background(colors.red)
    } else {
        modifier.border(2.dp, colors.divider, RectangleShape)
    }
    Box(
        modifier = base
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (pressed || !enabled) 0.55f else 1f)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        EGText(
            text = text,
            size = 12f,
            weight = FontWeight.Black,
            tracking = 0.8f,
            color = if (kind == EGButtonKind.PRIMARY) colors.onRed else colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
fun EGChipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = EG.colors.ink,
    content: @Composable RowScope.(TextStyle, Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .background(EG.colors.card)
            .border(1.5.dp, EG.colors.divider, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .alpha(if (pressed) 0.55f else 1f)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content(egStyle(10.5f, FontWeight.Black, 0.9f), tint)
    }
}

@Composable
fun EGChipButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = EG.colors.ink) {
    EGChipButton(onClick = onClick, modifier = modifier, tint = tint) { style, color ->
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EGCheckbox(checked: Boolean, modifier: Modifier = Modifier, size: Float = 20f) {
    val colors = EG.colors
    Box(
        modifier = modifier
            .size(size.dp)
            .background(if (checked) colors.red else colors.card)
            .border(2.dp, if (checked) colors.red else colors.divider, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onRed, modifier = Modifier.size((size * 0.7f).dp))
        }
    }
}
