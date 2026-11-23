package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val BackTalkLightColorScheme = lightColorScheme(
    primary = ElectricIndigo,
    onPrimary = PureWhite,
    primaryContainer = IndigoTint,
    onPrimaryContainer = StudioInk,
    secondary = EmeraldTeal,
    onSecondary = PureWhite,
    secondaryContainer = EmeraldTint,
    onSecondaryContainer = StudioInk,
    tertiary = AmberGold,
    onTertiary = StudioInk,
    tertiaryContainer = AmberTint,
    onTertiaryContainer = StudioInk,
    background = StudioCanvasBg,
    onBackground = StudioInk,
    surface = StudioWhite,
    onSurface = StudioInk,
    surfaceVariant = StudioSoftSurface,
    onSurfaceVariant = StudioMuted,
    outline = StudioBorder,
    error = RecordCrimson,
    onError = PureWhite
)

private val BackTalkDarkColorScheme = darkColorScheme(
    primary = ElectricIndigo,
    onPrimary = PureWhite,
    primaryContainer = DarkStudioCard,
    onPrimaryContainer = PureWhite,
    secondary = EmeraldTeal,
    onSecondary = DarkStudioBg,
    secondaryContainer = DarkStudioCard,
    onSecondaryContainer = PureWhite,
    tertiary = AmberGold,
    onTertiary = DarkStudioBg,
    tertiaryContainer = DarkStudioCard,
    onTertiaryContainer = PureWhite,
    background = DarkStudioBg,
    onBackground = PureWhite,
    surface = DarkStudioSurface,
    onSurface = PureWhite,
    surfaceVariant = DarkStudioCard,
    onSurfaceVariant = DarkStudioMuted,
    outline = DarkStudioBorder,
    error = RecordCrimson,
    onError = PureWhite
)

val BackTalkShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun BackTalkTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) BackTalkDarkColorScheme else BackTalkLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BackTalkTypography,
        shapes = BackTalkShapes,
        content = content
    )
}
