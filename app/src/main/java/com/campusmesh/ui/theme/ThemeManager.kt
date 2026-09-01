package com.campusmesh.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AppTheme(val label: String, val description: String) {
    DEFAULT("Default Dark", "Sleek monochromatic cyberpunk dark theme"),
    PIXEL_8BIT("8-Bit Retro Arcade", "Vibrant cartoon pixel style with arcade colors"),
}

@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentTheme = MutableStateFlow(loadSavedTheme())
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    private fun loadSavedTheme(): AppTheme {
        val savedName = prefs.getString(KEY_THEME, AppTheme.DEFAULT.name)
        return try {
            AppTheme.valueOf(savedName ?: AppTheme.DEFAULT.name)
        } catch (_: Exception) {
            AppTheme.DEFAULT
        }
    }

    fun setTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _currentTheme.value = theme
    }

    companion object {
        private const val PREFS_NAME = "campusmesh_theme"
        private const val KEY_THEME = "selected_app_theme"
    }
}
