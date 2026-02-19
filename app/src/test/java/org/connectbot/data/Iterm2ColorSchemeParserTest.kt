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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Iterm2ColorSchemeParserTest {

    @Test
    fun parse_ValidItermcolors_ParsesAnsiAndDefaults() {
        val xml = itermcolorsXml()

        val parsed = Iterm2ColorSchemeParser.parse(xml)

        assertEquals(16, parsed.ansiColors.size)
        assertEquals(0xFF000000.toInt(), parsed.ansiColors[0])
        assertEquals(0xFFFF0000.toInt(), parsed.ansiColors[1])
        assertEquals(0xFF00FF00.toInt(), parsed.ansiColors[2])
        assertEquals(0xFF0000FF.toInt(), parsed.ansiColors[3])
        assertEquals(0xFFFF0000.toInt(), parsed.foregroundColor)
        assertEquals(0xFF000000.toInt(), parsed.backgroundColor)
    }

    @Test
    fun parse_MissingAnsiColors_Throws() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
              <dict>
                <key>Ansi 0 Color</key>
                <dict>
                  <key>Red Component</key><integer>0</integer>
                  <key>Green Component</key><integer>0</integer>
                  <key>Blue Component</key><integer>0</integer>
                </dict>
              </dict>
            </plist>
            """.trimIndent()

        try {
            Iterm2ColorSchemeParser.parse(xml)
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Missing ANSI colors") == true)
        }
    }

    private fun itermcolorsXml(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
          <dict>
            <key>Ansi 0 Color</key>
            <dict>
              <key>Red Component</key><integer>0</integer>
              <key>Green Component</key><integer>0</integer>
              <key>Blue Component</key><integer>0</integer>
              <key>Alpha Component</key><real>1</real>
            </dict>
            <key>Ansi 1 Color</key>
            <dict>
              <key>Red Component</key><integer>255</integer>
              <key>Green Component</key><integer>0</integer>
              <key>Blue Component</key><integer>0</integer>
              <key>Alpha Component</key><real>1</real>
            </dict>
            <key>Ansi 2 Color</key>
            <dict>
              <key>Red Component</key><integer>0</integer>
              <key>Green Component</key><integer>255</integer>
              <key>Blue Component</key><integer>0</integer>
              <key>Alpha Component</key><real>1</real>
            </dict>
            <key>Ansi 3 Color</key>
            <dict>
              <key>Red Component</key><integer>0</integer>
              <key>Green Component</key><integer>0</integer>
              <key>Blue Component</key><integer>255</integer>
              <key>Alpha Component</key><real>1</real>
            </dict>
            <key>Ansi 4 Color</key><dict><key>Red Component</key><integer>255</integer><key>Green Component</key><integer>255</integer><key>Blue Component</key><integer>0</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 5 Color</key><dict><key>Red Component</key><integer>255</integer><key>Green Component</key><integer>0</integer><key>Blue Component</key><integer>255</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 6 Color</key><dict><key>Red Component</key><integer>0</integer><key>Green Component</key><integer>255</integer><key>Blue Component</key><integer>255</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 7 Color</key><dict><key>Red Component</key><integer>255</integer><key>Green Component</key><integer>255</integer><key>Blue Component</key><integer>255</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 8 Color</key><dict><key>Red Component</key><integer>128</integer><key>Green Component</key><integer>128</integer><key>Blue Component</key><integer>128</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 9 Color</key><dict><key>Red Component</key><integer>128</integer><key>Green Component</key><integer>0</integer><key>Blue Component</key><integer>0</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 10 Color</key><dict><key>Red Component</key><integer>0</integer><key>Green Component</key><integer>128</integer><key>Blue Component</key><integer>0</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 11 Color</key><dict><key>Red Component</key><integer>0</integer><key>Green Component</key><integer>0</integer><key>Blue Component</key><integer>128</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 12 Color</key><dict><key>Red Component</key><integer>128</integer><key>Green Component</key><integer>128</integer><key>Blue Component</key><integer>0</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 13 Color</key><dict><key>Red Component</key><integer>128</integer><key>Green Component</key><integer>0</integer><key>Blue Component</key><integer>128</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 14 Color</key><dict><key>Red Component</key><integer>0</integer><key>Green Component</key><integer>128</integer><key>Blue Component</key><integer>128</integer><key>Alpha Component</key><real>1</real></dict>
            <key>Ansi 15 Color</key><dict><key>Red Component</key><integer>240</integer><key>Green Component</key><integer>240</integer><key>Blue Component</key><integer>240</integer><key>Alpha Component</key><real>1</real></dict>

            <key>Foreground Color</key>
            <dict>
              <key>Red Component</key><integer>255</integer>
              <key>Green Component</key><integer>0</integer>
              <key>Blue Component</key><integer>0</integer>
              <key>Alpha Component</key><real>1</real>
            </dict>
            <key>Background Color</key>
            <dict>
              <key>Red Component</key><integer>0</integer>
              <key>Green Component</key><integer>0</integer>
              <key>Blue Component</key><integer>0</integer>
              <key>Alpha Component</key><real>1</real>
            </dict>
          </dict>
        </plist>
        """.trimIndent()
}

