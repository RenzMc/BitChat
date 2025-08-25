package com.bitchat.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Modern color scheme with sophisticated blues and purples
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6366F1),        // Modern indigo/purple
    onPrimary = Color.White,
    secondary = Color(0xFF8B5CF6),      // Purple accent
    onSecondary = Color.White,
    background = Color(0xFF0F172A),     // Deep slate background
    onBackground = Color(0xFFE2E8F0),   // Light slate text
    surface = Color(0xFF1E293B),        // Darker slate surface
    onSurface = Color(0xFFE2E8F0),      // Light slate text
    error = Color(0xFFEF4444),          // Modern red for errors
    onError = Color.White,
    tertiary = Color(0xFF06B6D4),       // Cyan accent for highlights
    onTertiary = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),        // Rich indigo
    onPrimary = Color.White,
    secondary = Color(0xFF7C3AED),      // Rich purple
    onSecondary = Color.White,
    background = Color(0xFFFCFDFE),     // Crisp white background
    onBackground = Color(0xFF1E293B),   // Dark slate text
    surface = Color(0xFFF8FAFC),        // Light slate surface
    onSurface = Color(0xFF334155),      // Medium slate text
    error = Color(0xFFDC2626),          // Modern red for errors
    onError = Color.White,
    tertiary = Color(0xFF0891B2),       // Cyan accent for highlights
    onTertiary = Color.White
)

@Composable
fun BitchatTheme(
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
