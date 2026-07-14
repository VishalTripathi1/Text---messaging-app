package com.messaging.presentation.theme

import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryColor,
    onPrimary = TertiaryColor,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = TertiaryColor,
    secondary = SecondaryColor,
    onSecondary = BackgroundDark,
    tertiary = TertiaryColor,
    onTertiary = TextPrimary,
    error = ErrorColor,
    onError = BackgroundLight,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryColor,
    onPrimary = BackgroundLight,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = BackgroundLight,
    secondary = SecondaryColor,
    onSecondary = BackgroundLight,
    tertiary = TertiaryColor,
    onTertiary = TextPrimary,
    error = ErrorColor,
    onError = BackgroundLight,
    background = BackgroundLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary
)

@Composable
fun MessagingTheme(
    darkTheme: Boolean = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
