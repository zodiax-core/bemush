package com.campusmesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = MonoBlack,
    onPrimary = MonoWhite,
    primaryContainer = MonoGrayLight,
    onPrimaryContainer = MonoBlack,
    secondary = MonoGrayDark,
    onSecondary = MonoWhite,
    secondaryContainer = MonoLightSurfaceVariant,
    onSecondaryContainer = MonoBlack,
    background = MonoLightBackground,
    onBackground = MonoTextPrimaryLight,
    surface = MonoLightSurface,
    onSurface = MonoTextPrimaryLight,
    surfaceVariant = MonoLightSurfaceVariant,
    onSurfaceVariant = MonoTextSecondaryLight,
    outline = MonoGrayMedium,
    outlineVariant = MonoGrayLight,
    error = CampusDanger,
    onError = MonoWhite,
)

private val DarkColors = darkColorScheme(
    primary = MonoWhite,
    onPrimary = MonoBlack,
    primaryContainer = MonoDarkSurfaceVariant,
    onPrimaryContainer = MonoWhite,
    secondary = MonoGrayLight,
    onSecondary = MonoBlack,
    secondaryContainer = MonoDarkSurface,
    onSecondaryContainer = MonoWhite,
    background = MonoDarkBackground,
    onBackground = MonoTextPrimaryDark,
    surface = MonoDarkSurface,
    onSurface = MonoTextPrimaryDark,
    surfaceVariant = MonoDarkSurfaceVariant,
    onSurfaceVariant = MonoTextSecondaryDark,
    outline = MonoGrayMedium,
    outlineVariant = MonoGrayDark,
    error = Color(0xFFFFB4AB),
    onError = MonoBlack,
)

@Composable
fun CampusMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CampusMeshTypography,
        content = content,
    )
}
