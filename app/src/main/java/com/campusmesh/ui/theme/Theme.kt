package com.campusmesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalAppTheme = staticCompositionLocalOf { AppTheme.DEFAULT }

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

private val Pixel8BitColors = lightColorScheme(
    primary = PixelYellow,
    onPrimary = MonoBlack,
    primaryContainer = PixelMagenta,
    onPrimaryContainer = MonoWhite,
    secondary = PixelCyan,
    onSecondary = MonoBlack,
    secondaryContainer = PixelSurfaceVariant,
    onSecondaryContainer = MonoBlack,
    background = Color.White,            // PURE WHITE BACKGROUND FOR ARCADE THEME!
    onBackground = MonoBlack,            // Crisp Black 8-Bit Text!
    surface = PixelSurface,               // Light Cartoon Indigo/White Surface!
    onSurface = MonoBlack,
    surfaceVariant = PixelSurfaceVariant, // Light Card Surface!
    onSurfaceVariant = MonoBlack,
    outline = PixelBorder,
    outlineVariant = PixelMagenta,
    error = PixelOrange,
    onError = MonoWhite,
)

@Composable
fun CampusMeshTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (appTheme) {
        AppTheme.PIXEL_8BIT -> Pixel8BitColors
        AppTheme.DEFAULT -> if (darkTheme) DarkColors else LightColors
    }

    val typography = when (appTheme) {
        AppTheme.PIXEL_8BIT -> PixelTypography
        AppTheme.DEFAULT -> CampusMeshTypography
    }

    CompositionLocalProvider(LocalAppTheme provides appTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
