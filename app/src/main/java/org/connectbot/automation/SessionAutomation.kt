/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025 Kenny Root
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

package org.connectbot.automation

import android.content.Intent
import org.connectbot.terminal.VTermKey
import java.util.Locale

data class AutomationKeyStroke(
    val key: Int,
    val modifiers: Int = 0
)

data class SessionAutomationRequest(
    val text: String? = null,
    val keyStrokes: List<AutomationKeyStroke> = emptyList(),
    val delayMs: Long = 0L
) {
    fun isEmpty(): Boolean = text.isNullOrEmpty() && keyStrokes.isEmpty()
}

object SessionAutomationIntents {
    const val ACTION_AUTOMATE_SESSION: String = "org.connectbot.action.AUTOMATE_SESSION"

    const val EXTRA_TEXT: String = "org.connectbot.extra.AUTOMATION_TEXT"
    const val EXTRA_APPEND_NEWLINE: String = "org.connectbot.extra.AUTOMATION_APPEND_NEWLINE"
    const val EXTRA_KEYS: String = "org.connectbot.extra.AUTOMATION_KEYS"
    const val EXTRA_DELAY_MS: String = "org.connectbot.extra.AUTOMATION_DELAY_MS"

    fun parse(intent: Intent, enabled: Boolean): SessionAutomationRequest? {
        if (!enabled || intent.action != ACTION_AUTOMATE_SESSION) {
            return null
        }

        val rawText = intent.getStringExtra(EXTRA_TEXT)
        val appendNewline = intent.getBooleanExtra(EXTRA_APPEND_NEWLINE, false)
        val parsedText = when {
            rawText != null && appendNewline -> decodeEscapes(rawText) + "\n"
            rawText != null -> decodeEscapes(rawText)
            appendNewline -> "\n"
            else -> null
        }?.takeUnless { it.isEmpty() }

        val keyStrokes = parseKeyStrokes(intent.getStringExtra(EXTRA_KEYS))
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 0L).coerceAtLeast(0L)

        return SessionAutomationRequest(
            text = parsedText,
            keyStrokes = keyStrokes,
            delayMs = delayMs
        ).takeUnless { it.isEmpty() }
    }

    internal fun decodeEscapes(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0

        while (index < raw.length) {
            val ch = raw[index]
            if (ch != '\\' || index == raw.lastIndex) {
                out.append(ch)
                index++
                continue
            }

            val next = raw[index + 1]
            when (next) {
                '\\' -> {
                    out.append('\\')
                    index += 2
                }
                'n' -> {
                    out.append('\n')
                    index += 2
                }
                'r' -> {
                    out.append('\r')
                    index += 2
                }
                't' -> {
                    out.append('\t')
                    index += 2
                }
                'b' -> {
                    out.append('\b')
                    index += 2
                }
                'f' -> {
                    out.append('\u000c')
                    index += 2
                }
                'u' -> {
                    val hex = raw.substringOrNull(index + 2, index + 6)
                    if (hex != null && hex.length == 4) {
                        val value = hex.toIntOrNull(16)
                        if (value != null) {
                            out.append(value.toChar())
                            index += 6
                            continue
                        }
                    }
                    out.append('\\')
                    index++
                }
                'x' -> {
                    val hex = raw.substringOrNull(index + 2, index + 4)
                    if (hex != null && hex.length == 2) {
                        val value = hex.toIntOrNull(16)
                        if (value != null) {
                            out.append(value.toChar())
                            index += 4
                            continue
                        }
                    }
                    out.append('\\')
                    index++
                }
                else -> {
                    out.append(next)
                    index += 2
                }
            }
        }

        return out.toString()
    }

    internal fun parseKeyStrokes(raw: String?): List<AutomationKeyStroke> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return raw
            .split(',', '\n')
            .mapNotNull { parseSingleKeyStroke(it.trim()) }
    }

    private fun parseSingleKeyStroke(raw: String): AutomationKeyStroke? {
        if (raw.isBlank()) {
            return null
        }

        val parts = raw.split('+').map { normalizeKeyToken(it) }.filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return null
        }

        var modifiers = 0
        for (part in parts.dropLast(1)) {
            modifiers = modifiers or when (part) {
                "SHIFT" -> 1
                "ALT" -> 2
                "CTRL", "CONTROL" -> 4
                else -> return null
            }
        }

        val key = when (parts.last()) {
            "ENTER", "RETURN" -> VTermKey.ENTER
            "TAB" -> VTermKey.TAB
            "BACKSPACE" -> VTermKey.BACKSPACE
            "ESC", "ESCAPE" -> VTermKey.ESCAPE
            "UP" -> VTermKey.UP
            "DOWN" -> VTermKey.DOWN
            "LEFT" -> VTermKey.LEFT
            "RIGHT" -> VTermKey.RIGHT
            "INS", "INSERT" -> VTermKey.INS
            "DEL", "DELETE" -> VTermKey.DEL
            "HOME" -> VTermKey.HOME
            "END" -> VTermKey.END
            "PGUP", "PAGEUP" -> VTermKey.PAGEUP
            "PGDN", "PAGEDOWN" -> VTermKey.PAGEDOWN
            "F1" -> VTermKey.FUNCTION_1
            "F2" -> VTermKey.FUNCTION_2
            "F3" -> VTermKey.FUNCTION_3
            "F4" -> VTermKey.FUNCTION_4
            "F5" -> VTermKey.FUNCTION_5
            "F6" -> VTermKey.FUNCTION_6
            "F7" -> VTermKey.FUNCTION_7
            "F8" -> VTermKey.FUNCTION_8
            "F9" -> VTermKey.FUNCTION_9
            "F10" -> VTermKey.FUNCTION_10
            "F11" -> VTermKey.FUNCTION_11
            "F12" -> VTermKey.FUNCTION_12
            else -> return null
        }

        return AutomationKeyStroke(key = key, modifiers = modifiers)
    }

    private fun normalizeKeyToken(raw: String): String =
        raw
            .trim()
            .replace('-', '_')
            .replace(' ', '_')
            .uppercase(Locale.US)

    private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? {
        if (startIndex < 0 || endIndex > length || startIndex >= endIndex) {
            return null
        }
        return substring(startIndex, endIndex)
    }
}
