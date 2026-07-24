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
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = SecondaryColor,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = TertiaryColor,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    error = ErrorColor,
    onError = androidx.compose.ui.graphics.Color.White,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = SurfaceVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryColor,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = SurfaceVariantLight,
    onPrimaryContainer = PrimaryVariant,
    secondary = SecondaryColor,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = TertiaryColor,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    error = ErrorColor,
    onError = androidx.compose.ui.graphics.Color.White,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = SurfaceVariantLight
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
