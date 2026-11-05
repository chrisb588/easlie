package com.chrisb588.easlie.ui.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Global appearance is stored separately from board documents. */
enum class Appearance {
    LIGHT, DARK, SYSTEM;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }
}

class AppearanceStore(private val preferences: SharedPreferences) {
    var appearance by mutableStateOf(
        Appearance.entries.firstOrNull { it.name == preferences.getString(KEY, null) }
            ?: Appearance.SYSTEM
    )
        private set

    fun select(value: Appearance) {
        preferences.edit { putString(KEY, value.name) }
        appearance = value
    }

    companion object {
        const val PREFERENCES = "appearance"
        private const val KEY = "choice"
    }
}
