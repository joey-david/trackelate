package com.joey.trackelate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3F4E3A),
    onPrimary = Color(0xFFF7F0E4),
    primaryContainer = Color(0xFFDDE5D1),
    onPrimaryContainer = Color(0xFF1B2418),
    secondary = Color(0xFF8B5E47),
    onSecondary = Color(0xFFF8F2EC),
    secondaryContainer = Color(0xFFF0D9CA),
    onSecondaryContainer = Color(0xFF321A11),
    tertiary = Color(0xFFAD8A46),
    onTertiary = Color(0xFF1E1706),
    tertiaryContainer = Color(0xFFF1E0B8),
    onTertiaryContainer = Color(0xFF2D2208),
    background = Color(0xFFFFFCF8),
    onBackground = Color(0xFF191611),
    surface = Color(0xFFFFFCF8),
    onSurface = Color(0xFF191611),
    surfaceVariant = Color(0xFFF2E8DA),
    onSurfaceVariant = Color(0xFF5F5348),
    outline = Color(0xFFCFC2B3),
    outlineVariant = Color(0xFFE6DACC),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFEDE6DB),
    onPrimary = Color(0xFF050505),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFEDE6DB),
    secondary = Color(0xFFD9C8B4),
    onSecondary = Color(0xFF050505),
    secondaryContainer = Color(0xFF151515),
    onSecondaryContainer = Color(0xFFD9C8B4),
    tertiary = Color(0xFFC5D0BE),
    onTertiary = Color(0xFF050505),
    tertiaryContainer = Color(0xFF151515),
    onTertiaryContainer = Color(0xFFC5D0BE),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2EFEA),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2EFEA),
    surfaceVariant = Color(0xFF101010),
    onSurfaceVariant = Color(0xFFB6B0A7),
    outline = Color(0xFF525252),
    outlineVariant = Color(0xFF1D1D1D),
)

@Composable
fun TrackelateTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
