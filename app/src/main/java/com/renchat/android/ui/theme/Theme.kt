package com.renchat.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.renchat.android.ui.SettingsManager

// BitChat 1.2.0 exact dark theme colors
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF39FF14),        // Bright green (terminal-like)
    onPrimary = Color.Black,
    secondary = Color(0xFF2ECB10),      // Darker green
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFF39FF14),   // Green on black
    surface = Color(0xFF111111),        // Very dark gray
    onSurface = Color(0xFF39FF14),      // Green text
    error = Color(0xFFFF5555),          // Red for errors
    onError = Color.Black
)

// BitChat 1.2.0 exact light theme colors
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF008000),        // Dark green
    onPrimary = Color.White,
    secondary = Color(0xFF006600),      // Even darker green
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF008000),   // Dark green on white
    surface = Color(0xFFF8F8F8),        // Very light gray
    onSurface = Color(0xFF008000),      // Dark green text
    error = Color(0xFFCC0000),          // Dark red for errors
    onError = Color.White
)

@Composable
fun RenChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    settingsManager: SettingsManager? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // Observe theme mode changes to make the composable reactive
    val currentThemeMode by settingsManager?.themeMode ?: remember { mutableStateOf(SettingsManager.ThemeMode.SYSTEM) }
    
    // Determine if dynamic color is available and enabled
    val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            currentThemeMode == SettingsManager.ThemeMode.DYNAMIC
    
    // Determine actual dark theme based on current settings
    val actualDarkTheme = when (currentThemeMode) {
        SettingsManager.ThemeMode.SYSTEM -> darkTheme
        SettingsManager.ThemeMode.LIGHT -> false
        SettingsManager.ThemeMode.DARK -> true
        SettingsManager.ThemeMode.DYNAMIC -> darkTheme
    }
    
    val colorScheme = when {
        dynamicColor && actualDarkTheme -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                DarkColorScheme
            }
        }
        dynamicColor && !actualDarkTheme -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(context)
            } else {
                LightColorScheme
            }
        }
        actualDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
