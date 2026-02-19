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

package org.connectbot.data

import kotlin.math.roundToInt

/**
 * Parses common GNOME Terminal color-scheme exports into a terminal palette.
 *
 * Supported inputs (best-effort):
 * - `dconf dump` / `gsettings get` style entries:
 *   - `palette=['rgb(...)', ...]`
 *   - `foreground-color='rgb(...)'`
 *   - `background-color='rgb(...)'`
 * - Common "Gogh" theme scripts:
 *   - `PALETTE="#RRGGBB:#RRGGBB:... (16 entries)"`
 *   - `FOREGROUND_COLOR="#RRGGBB"`, `BACKGROUND_COLOR="#RRGGBB"`
 *
 * Note: GNOME Terminal stores foreground/background as arbitrary colors, but ConnectBot stores defaults
 * as indices into the 16-color palette; callers may map FG/BG to closest ANSI entries.
 */
object GnomeTerminalColorSchemeParser {

    data class ParsedScheme(
        val ansiColors: IntArray,
        val foregroundColor: Int?,
        val backgroundColor: Int?,
    )

    fun looksLikeGnomeTerminalScheme(text: String): Boolean =
        text.contains("palette", ignoreCase = true) || COLON_HEX_PALETTE_REGEX.containsMatchIn(text)

    fun parse(text: String): ParsedScheme {
        val assignments = parseAssignments(text)

        val paletteValue = assignments[KEY_PALETTE]
        val palette =
            when {
                paletteValue != null -> parsePaletteValue(paletteValue)
                else -> parsePaletteFallback(text)
            } ?: throw IllegalArgumentException("No GNOME Terminal palette found")

        val foreground =
            assignments[KEY_FOREGROUND_COLOR]?.let { parseColorString(it) }
                ?: assignments[KEY_FG_COLOR]?.let { parseColorString(it) }
        val background =
            assignments[KEY_BACKGROUND_COLOR]?.let { parseColorString(it) }
                ?: assignments[KEY_BG_COLOR]?.let { parseColorString(it) }

        return ParsedScheme(
            ansiColors = palette,
            foregroundColor = foreground,
            backgroundColor = background,
        )
    }

    private const val KEY_PALETTE = "palette"
    private const val KEY_FOREGROUND_COLOR = "foreground_color"
    private const val KEY_BACKGROUND_COLOR = "background_color"
    private const val KEY_FG_COLOR = "fg_color"
    private const val KEY_BG_COLOR = "bg_color"

    private val ASSIGNMENT_REGEX =
        Regex("""^\s*(?:export\s+)?([A-Za-z0-9_-]+)\s*=\s*(.*?)\s*$""")

    private fun normalizeKey(key: String): String =
        key.trim().lowercase().replace('-', '_')

    private fun parseAssignments(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#") || line.startsWith(";")) continue

            val match = ASSIGNMENT_REGEX.matchEntire(rawLine) ?: continue
            val key = normalizeKey(match.groupValues[1])
            val value = match.groupValues[2].trim()
            result[key] = value
        }
        return result
    }

    private fun parsePaletteFallback(text: String): IntArray? {
        BRACKET_LIST_REGEX.findAll(text).forEach { match ->
            val candidate = match.value
            try {
                return parseBracketListPalette(candidate)
            } catch (_: Exception) {
                // Ignore and keep searching
            }
        }

        val colonPalette = COLON_HEX_PALETTE_REGEX.find(text)?.value
        if (colonPalette != null) {
            try {
                return parseColonSeparatedPalette(colonPalette)
            } catch (_: Exception) {
                // Ignore
            }
        }

        return null
    }

    private val BRACKET_LIST_REGEX = Regex("""\[[^\]]+\]""", RegexOption.DOT_MATCHES_ALL)
    private val COLON_HEX_PALETTE_REGEX = Regex(
        """(?i)#[0-9a-f]{6}(?::#[0-9a-f]{6}){15}"""
    )

    private fun parsePaletteValue(value: String): IntArray {
        val unwrapped = stripOuterQuotes(value.trim()).trim().removeSuffix(";").trim()
        return when {
            unwrapped.startsWith("[") -> parseBracketListPalette(unwrapped)
            unwrapped.startsWith("(") -> parseParenListPalette(unwrapped)
            unwrapped.contains(":") -> parseColonSeparatedPalette(unwrapped)
            else -> throw IllegalArgumentException("Unsupported palette format")
        }
    }

    private fun parseBracketListPalette(listText: String): IntArray {
        val inner = listText.trim().removeSurrounding("[", "]").trim()
        if (inner.isBlank()) throw IllegalArgumentException("Empty palette list")

        val quotedTokens = QUOTED_TOKEN_REGEX.findAll(inner).map { it.groupValues[1] }.toList()
        val rawTokens =
            if (quotedTokens.isNotEmpty()) {
                quotedTokens
            } else {
                inner.split(',').map { it.trim() }.filter { it.isNotBlank() }
            }

        val colors = rawTokens.map { parseColorString(it) }
        if (colors.size != 16) throw IllegalArgumentException("Expected 16 palette colors, got ${colors.size}")
        return colors.toIntArray()
    }

    private fun parseParenListPalette(listText: String): IntArray {
        val inner = listText.trim().removeSurrounding("(", ")").trim()
        if (inner.isBlank()) throw IllegalArgumentException("Empty palette list")
        val tokens = QUOTED_TOKEN_REGEX.findAll(inner).map { it.groupValues[1] }.toList()
        val rawTokens =
            if (tokens.isNotEmpty()) tokens else inner.split(Regex("""\s+""")).map { it.trim() }.filter { it.isNotBlank() }
        val colors = rawTokens.map { parseColorString(it) }
        if (colors.size != 16) throw IllegalArgumentException("Expected 16 palette colors, got ${colors.size}")
        return colors.toIntArray()
    }

    private fun parseColonSeparatedPalette(value: String): IntArray {
        val unwrapped = stripOuterQuotes(value.trim()).trim()
        val parts = unwrapped.split(':').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size != 16) throw IllegalArgumentException("Expected 16 palette colors, got ${parts.size}")
        return parts.map { parseColorString(it) }.toIntArray()
    }

    private val QUOTED_TOKEN_REGEX = Regex("""['"]([^'"]+)['"]""")

    private fun stripOuterQuotes(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 2) return trimmed
        val first = trimmed.first()
        val last = trimmed.last()
        return if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun parseColorString(value: String): Int {
        val cleaned = stripOuterQuotes(value.trim()).trim()
        if (cleaned.isBlank()) throw IllegalArgumentException("Empty color")

        val rgbMatch = RGB_REGEX.matchEntire(cleaned)
        if (rgbMatch != null) {
            val r = rgbMatch.groupValues[1].toDouble()
            val g = rgbMatch.groupValues[2].toDouble()
            val b = rgbMatch.groupValues[3].toDouble()
            val a = rgbMatch.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }?.toDouble() ?: 1.0

            return argbInt(
                r = normalizeComponent(r),
                g = normalizeComponent(g),
                b = normalizeComponent(b),
                a = normalizeAlpha(a),
            )
        }

        return parseHexColor(cleaned)
    }

    private val RGB_REGEX =
        Regex("""(?i)rgba?\(\s*([0-9.]+)\s*,\s*([0-9.]+)\s*,\s*([0-9.]+)\s*(?:,\s*([0-9.]+)\s*)?\)""")

    private fun normalizeComponent(value: Double): Double =
        when {
            value.isNaN() -> 0.0
            value <= 0.0 -> 0.0
            value <= 1.0 -> value
            value <= 255.0 -> value / 255.0
            else -> 1.0
        }

    private fun normalizeAlpha(value: Double): Double =
        when {
            value.isNaN() -> 1.0
            value <= 0.0 -> 0.0
            value <= 1.0 -> value
            value <= 255.0 -> value / 255.0
            else -> 1.0
        }

    private fun parseHexColor(value: String): Int {
        val trimmed = value.trim()
        val hex =
            trimmed.removePrefix("#")
                .removePrefix("0x")
                .removePrefix("0X")
                .lowercase()

        val expanded =
            when (hex.length) {
                3 -> "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                4 -> "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}${hex[3]}${hex[3]}"
                else -> hex
            }

        return when (expanded.length) {
            6 -> {
                val r = expanded.substring(0, 2).toInt(16)
                val g = expanded.substring(2, 4).toInt(16)
                val b = expanded.substring(4, 6).toInt(16)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            8 -> {
                val looksLikeAarrggbb = expanded.startsWith("ff") || expanded.startsWith("00")
                if (looksLikeAarrggbb) {
                    val a = expanded.substring(0, 2).toInt(16)
                    val r = expanded.substring(2, 4).toInt(16)
                    val g = expanded.substring(4, 6).toInt(16)
                    val b = expanded.substring(6, 8).toInt(16)
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    val r = expanded.substring(0, 2).toInt(16)
                    val g = expanded.substring(2, 4).toInt(16)
                    val b = expanded.substring(4, 6).toInt(16)
                    val a = expanded.substring(6, 8).toInt(16)
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            else -> throw IllegalArgumentException("Unsupported color format: $value")
        }
    }

    private fun argbInt(r: Double, g: Double, b: Double, a: Double): Int {
        val rr = (r * 255.0).roundToInt().coerceIn(0, 255)
        val gg = (g * 255.0).roundToInt().coerceIn(0, 255)
        val bb = (b * 255.0).roundToInt().coerceIn(0, 255)
        val aa = (a * 255.0).roundToInt().coerceIn(0, 255)
        return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}

