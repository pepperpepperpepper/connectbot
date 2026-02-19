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

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GnomeTerminalColorSchemeParserTest {

    @Test
    fun parse_DconfDump_ParsesPaletteAndDefaults() {
        val text =
            """
            [/]
            foreground-color='rgb(255,0,0)'
            background-color='rgb(0,0,0)'
            palette=['rgb(0,0,0)', 'rgb(255,0,0)', 'rgb(0,255,0)', 'rgb(0,0,255)', 'rgb(255,255,0)', 'rgb(255,0,255)', 'rgb(0,255,255)', 'rgb(255,255,255)', 'rgb(128,128,128)', 'rgb(128,0,0)', 'rgb(0,128,0)', 'rgb(0,0,128)', 'rgb(128,128,0)', 'rgb(128,0,128)', 'rgb(0,128,128)', 'rgb(240,240,240)']
            """.trimIndent()

        val parsed = GnomeTerminalColorSchemeParser.parse(text)

        assertEquals(16, parsed.ansiColors.size)
        assertEquals(0xFF000000.toInt(), parsed.ansiColors[0])
        assertEquals(0xFFFF0000.toInt(), parsed.ansiColors[1])
        assertEquals(0xFF00FF00.toInt(), parsed.ansiColors[2])
        assertEquals(0xFF0000FF.toInt(), parsed.ansiColors[3])
        assertEquals(0xFFFF0000.toInt(), parsed.foregroundColor)
        assertEquals(0xFF000000.toInt(), parsed.backgroundColor)
    }

    @Test
    fun parse_GoghScript_ParsesColonSeparatedPalette() {
        val text =
            """
            #!/usr/bin/env bash
            PALETTE="#000000:#ff0000:#00ff00:#0000ff:#ffff00:#ff00ff:#00ffff:#ffffff:#808080:#800000:#008000:#000080:#808000:#800080:#008080:#f0f0f0"
            FOREGROUND_COLOR="#ff0000"
            BACKGROUND_COLOR="#000000"
            """.trimIndent()

        val parsed = GnomeTerminalColorSchemeParser.parse(text)

        assertEquals(16, parsed.ansiColors.size)
        assertEquals(0xFF000000.toInt(), parsed.ansiColors[0])
        assertEquals(0xFFFF0000.toInt(), parsed.ansiColors[1])
        assertEquals(0xFF00FF00.toInt(), parsed.ansiColors[2])
        assertEquals(0xFF0000FF.toInt(), parsed.ansiColors[3])
        assertEquals(0xFFFF0000.toInt(), parsed.foregroundColor)
        assertEquals(0xFF000000.toInt(), parsed.backgroundColor)
    }
}

