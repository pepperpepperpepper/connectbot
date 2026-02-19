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

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt
import org.xml.sax.InputSource

/**
 * Parses iTerm2 `.itermcolors` files (plist XML) into a terminal palette.
 *
 * iTerm2 schemes are XML plists where ANSI colors are stored under keys like:
 * `Ansi 0 Color` .. `Ansi 15 Color`.
 *
 * Additional keys like `Foreground Color` / `Background Color` may exist and are
 * returned (as ARGB ints) for optional default-FG/BG heuristics.
 */
object Iterm2ColorSchemeParser {

    data class ParsedScheme(
        val ansiColors: IntArray,
        val foregroundColor: Int?,
        val backgroundColor: Int?,
    )

    fun parse(xmlPlist: String): ParsedScheme {
        val trimmed = xmlPlist.trimStart()
        require(trimmed.startsWith("<") && trimmed.contains("<plist")) {
            "Not an iTerm2 plist"
        }

        val documentBuilder = secureDocumentBuilder()
        val document = documentBuilder.parse(InputSource(StringReader(xmlPlist)))
        val root = document.documentElement
        require(root != null && root.tagName == "plist") { "Invalid plist root" }

        val outerDict = firstChildElement(root, "dict") ?: throw IllegalArgumentException("Missing plist dict")

        val palette = IntArray(16) { ColorSchemePresets.default.colors[it] }
        val foundAnsi = BooleanArray(16)

        var foreground: Int? = null
        var background: Int? = null

        forEachPlistKeyValue(outerDict) { key, valueElement ->
            when {
                key.startsWith("Ansi ") && key.endsWith(" Color") -> {
                    val index = ANSI_KEY_REGEX.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
                    if (index != null && index in 0..15) {
                        val color = parseColorDict(valueElement)
                        palette[index] = color
                        foundAnsi[index] = true
                    }
                }
                key == "Foreground Color" -> foreground = parseColorDict(valueElement)
                key == "Background Color" -> background = parseColorDict(valueElement)
            }
        }

        if (foundAnsi.none { it }) {
            throw IllegalArgumentException("No ANSI colors found in plist")
        }
        if (foundAnsi.any { !it }) {
            val missing = mutableListOf<Int>()
            for (i in foundAnsi.indices) {
                if (!foundAnsi[i]) missing.add(i)
            }
            throw IllegalArgumentException("Missing ANSI colors: $missing")
        }

        return ParsedScheme(
            ansiColors = palette,
            foregroundColor = foreground,
            backgroundColor = background,
        )
    }

    private val ANSI_KEY_REGEX = Regex("""^Ansi (\d+) Color$""")

    private fun secureDocumentBuilder() =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setExpandEntityReferences(false)

            // The common iTerm2 plist includes a DOCTYPE referencing Apple's DTD.
            // We must avoid fetching external resources.
            trySetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }

    private fun DocumentBuilderFactory.trySetFeature(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (_: Exception) {
            // Best-effort: parser implementations vary across Android/JVM.
        }
    }

    private fun firstChildElement(parent: Element, tagName: String): Element? {
        val nodes = parent.childNodes ?: return null
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == tagName) return node
        }
        return null
    }

    private fun nextElementSibling(nodes: org.w3c.dom.NodeList, startIndex: Int): Element? {
        for (i in startIndex until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) return node
        }
        return null
    }

    private inline fun forEachPlistKeyValue(dict: Element, block: (key: String, value: Element) -> Unit) {
        require(dict.tagName == "dict") { "Expected dict" }
        val children = dict.childNodes
        var i = 0
        while (i < children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == "key") {
                val key = node.textContent.trim()
                val valueElement = nextElementSibling(children, i + 1)
                    ?: throw IllegalArgumentException("Missing value for key: $key")
                block(key, valueElement)
            }
            i++
        }
    }

    private fun parseColorDict(colorValueElement: Element): Int {
        require(colorValueElement.tagName == "dict") { "Color value is not a dict" }

        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var alpha = 1.0

        forEachPlistKeyValue(colorValueElement) { key, value ->
            val numericValue = value.textContent.trim().toDoubleOrNull() ?: return@forEachPlistKeyValue
            when (key) {
                "Red Component" -> red = numericValue
                "Green Component" -> green = numericValue
                "Blue Component" -> blue = numericValue
                "Alpha Component" -> alpha = numericValue
            }
        }

        return argbInt(
            r = normalizeComponent(red),
            g = normalizeComponent(green),
            b = normalizeComponent(blue),
            a = normalizeComponent(alpha),
        )
    }

    private fun normalizeComponent(value: Double): Double =
        when {
            value.isNaN() -> 0.0
            value <= 0.0 -> 0.0
            value <= 1.0 -> value
            value <= 255.0 -> value / 255.0
            else -> 1.0
        }

    private fun argbInt(r: Double, g: Double, b: Double, a: Double): Int {
        val rr = (r * 255.0).roundToInt().coerceIn(0, 255)
        val gg = (g * 255.0).roundToInt().coerceIn(0, 255)
        val bb = (b * 255.0).roundToInt().coerceIn(0, 255)
        val aa = (a * 255.0).roundToInt().coerceIn(0, 255)
        return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}
