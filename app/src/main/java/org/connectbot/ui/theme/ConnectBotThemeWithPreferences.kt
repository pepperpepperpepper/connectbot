/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.ui.theme

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import org.connectbot.util.PreferenceConstants

@Composable
fun ConnectBotThemeWithPreferences(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    val dynamicColor by rememberBooleanPreference(
        prefs = prefs,
        key = PreferenceConstants.DYNAMIC_COLOR,
        defaultValue = true
    )
    val accentColorHex by rememberStringPreference(
        prefs = prefs,
        key = PreferenceConstants.APP_ACCENT_COLOR,
        defaultValue = ""
    )

    val accentColor = remember(accentColorHex) { accentColorHex.toComposeColorOrNull() }

    ConnectBotTheme(
        dynamicColor = dynamicColor,
        appAccentColor = accentColor,
        content = content
    )
}

private fun String.toComposeColorOrNull(): Color? {
    val trimmed = trim()
    if (trimmed.isBlank()) return null

    return try {
        Color(android.graphics.Color.parseColor(trimmed))
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Composable
private fun rememberBooleanPreference(
    prefs: SharedPreferences,
    key: String,
    defaultValue: Boolean
): State<Boolean> {
    val state = remember(prefs, key) {
        mutableStateOf(prefs.getBoolean(key, defaultValue))
    }

    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                state.value = prefs.getBoolean(key, defaultValue)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return state
}

@Composable
private fun rememberStringPreference(
    prefs: SharedPreferences,
    key: String,
    defaultValue: String
): State<String> {
    val state = remember(prefs, key) {
        mutableStateOf(prefs.getString(key, defaultValue) ?: defaultValue)
    }

    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                state.value = prefs.getString(key, defaultValue) ?: defaultValue
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return state
}
