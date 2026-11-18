// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES)
}

/** The empty tag lets Android choose from the system languages. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    SIMPLIFIED_CHINESE("zh"),
    ENGLISH("en")
}

/**
 * Appearance and language. AppCompat applies both to every activity: the theme through the night
 * mode, the language through the application locales, which it also stores.
 */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("shushu_settings", Context.MODE_PRIVATE)
    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Called once at process start. */
    fun applyTheme() = AppCompatDelegate.setDefaultNightMode(_themeMode.value.nightMode)

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    val language: AppLanguage
        get() {
            val locale = AppCompatDelegate.getApplicationLocales()[0]
            return AppLanguage.entries.firstOrNull { it.tag.isNotEmpty() && it.tag == locale?.language } ?: AppLanguage.SYSTEM
        }

    /** Must run on the main thread; open activities are recreated in the new language. */
    fun setLanguage(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    private val _setupDone = MutableStateFlow(prefs.getBoolean(KEY_SETUP_DONE, false))
    /** The first-launch setup was finished; until then it is shown in place of the tabs. */
    val setupDone: StateFlow<Boolean> = _setupDone.asStateFlow()

    fun finishSetup() {
        prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
        _setupDone.value = true
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_SETUP_DONE = "setup_done"
    }
}
