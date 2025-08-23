package com.renchat.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Modern WhatsApp-inspired dark theme colors
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00BFA5),        // Teal accent (WhatsApp-like)
    onPrimary = Color.White,
    secondary = Color(0xFF1976D2),      // Blue accent
    onSecondary = Color.White,
    tertiary = Color(0xFF7C4DFF),       // Purple accent
    onTertiary = Color.White,
    background = Color(0xFF0F1419),     // Deep dark background
    onBackground = Color(0xFFE1E5E9),   // Light text on dark
    surface = Color(0xFF1C2128),        // Dark surface (cards, etc)
    onSurface = Color(0xFFE1E5E9),      // Light text on surface
    surfaceVariant = Color(0xFF2B3137), // Darker surface variant
    onSurfaceVariant = Color(0xFFB3B9C0), // Muted text
    outline = Color(0xFF3D4348),        // Border colors
    outlineVariant = Color(0xFF2B3137),
    error = Color(0xFFFF5252),          // Error red
    onError = Color.White,
    errorContainer = Color(0xFF3D1A1A),
    onErrorContainer = Color(0xFFFFB3B3)
)

// Modern WhatsApp-inspired light theme colors
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00BFA5),        // Teal accent (WhatsApp-like)
    onPrimary = Color.White,
    secondary = Color(0xFF1976D2),      // Blue accent
    onSecondary = Color.White,
    tertiary = Color(0xFF7C4DFF),       // Purple accent
    onTertiary = Color.White,
    background = Color(0xFFFFFFFF),     // Pure white background
    onBackground = Color(0xFF1C1C1E),   // Dark text on light
    surface = Color(0xFFF5F5F5),        // Light gray surface
    onSurface = Color(0xFF1C1C1E),      // Dark text on surface
    surfaceVariant = Color(0xFFEEEEEE), // Lighter surface variant
    onSurfaceVariant = Color(0xFF666666), // Muted text
    outline = Color(0xFFE0E0E0),        // Light border colors
    outlineVariant = Color(0xFFF0F0F0),
    error = Color(0xFFD32F2F),          // Error red
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E5),
    onErrorContainer = Color(0xFF8C1D1D)
)

@Composable
fun RenChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
