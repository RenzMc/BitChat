package com.renchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State

/**
 * Settings manager for RenChat theme preferences
 * Based on BitChat PR #121 but adapted for RenChat
 */
class SettingsManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("renchat_settings", Context.MODE_PRIVATE)
    
    private val _themeMode = mutableStateOf(getThemeMode())
    val themeMode: State<ThemeMode> = _themeMode
    
    enum class ThemeMode(val displayName: String) {
        SYSTEM("System"),
        LIGHT("Light"),
        DARK("Dark"),
        DYNAMIC("Dynamic") // Material You support
    }
    
    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val DEFAULT_THEME_MODE = "SYSTEM"
    }
    
    fun getThemeMode(): ThemeMode {
        val savedMode = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE)
        return try {
            ThemeMode.valueOf(savedMode ?: DEFAULT_THEME_MODE)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }
    
    fun setThemeMode(themeMode: ThemeMode) {
        prefs.edit()
            .putString(KEY_THEME_MODE, themeMode.name)
            .apply()
        _themeMode.value = themeMode
    }
    
    fun isDarkMode(isSystemInDarkTheme: Boolean): Boolean {
        return when (getThemeMode()) {
            ThemeMode.SYSTEM, ThemeMode.DYNAMIC -> isSystemInDarkTheme
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    }
    
    fun isDynamicColorEnabled(): Boolean {
        return getThemeMode() == ThemeMode.DYNAMIC
    }
}