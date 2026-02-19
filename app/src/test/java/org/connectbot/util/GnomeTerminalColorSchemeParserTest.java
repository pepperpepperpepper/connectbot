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

package org.connectbot.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class GnomeTerminalColorSchemeParserTest {
	@Test
	public void parse_bracketListPalette_returnsPaletteAndDefaults() {
		String text = ""
				+ "palette=['#000000','#ff0000','#00ff00','#0000ff','#ffff00','#ff00ff','#00ffff','#ffffff',"
				+ "'#111111','#222222','#333333','#444444','#555555','#666666','#777777','#888888']\n"
				+ "foreground-color='#ffffff'\n"
				+ "background-color='#000000'\n";

		GnomeTerminalColorSchemeParser.ParsedScheme parsed = GnomeTerminalColorSchemeParser.parse(text);
		assertNotNull(parsed);
		assertEquals(16, parsed.ansiColors.length);
		assertEquals(0xFF000000, parsed.ansiColors[0]);
		assertEquals(0xFFFF0000, parsed.ansiColors[1]);
		assertEquals(0xFF00FF00, parsed.ansiColors[2]);
		assertEquals(0xFF0000FF, parsed.ansiColors[3]);
		assertNotNull(parsed.foregroundColor);
		assertNotNull(parsed.backgroundColor);
		assertEquals(0xFFFFFFFF, (int) parsed.foregroundColor);
		assertEquals(0xFF000000, (int) parsed.backgroundColor);
	}
}

