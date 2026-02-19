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

public class Iterm2ColorSchemeParserTest {
	@Test
	public void parse_validPlist_returnsPaletteAndDefaults() {
		double[][] colors = new double[][] {
				{0, 0, 0},
				{1, 0, 0},
				{0, 1, 0},
				{0, 0, 1},
				{1, 1, 0},
				{1, 0, 1},
				{0, 1, 1},
				{1, 1, 1},
				{0.5, 0.5, 0.5},
				{0.25, 0.25, 0.25},
				{0.75, 0, 0},
				{0, 0.75, 0},
				{0, 0, 0.75},
				{0.75, 0.75, 0},
				{0.75, 0, 0.75},
				{0, 0.75, 0.75},
		};

		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ");
		sb.append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
		sb.append("<plist version=\"1.0\"><dict>\n");
		for (int i = 0; i < 16; i++) {
			sb.append("<key>Ansi ").append(i).append(" Color</key>");
			sb.append(colorDict(colors[i][0], colors[i][1], colors[i][2]));
			sb.append("\n");
		}
		sb.append("<key>Foreground Color</key>").append(colorDict(1, 1, 1)).append("\n");
		sb.append("<key>Background Color</key>").append(colorDict(0, 0, 0)).append("\n");
		sb.append("</dict></plist>");

		Iterm2ColorSchemeParser.ParsedScheme parsed = Iterm2ColorSchemeParser.parse(sb.toString());
		assertNotNull(parsed);
		assertEquals(16, parsed.ansiColors.length);
		assertEquals(0xFFFF0000, parsed.ansiColors[1]);
		assertEquals(0xFF00FF00, parsed.ansiColors[2]);
		assertEquals(0xFF0000FF, parsed.ansiColors[3]);
		assertNotNull(parsed.foregroundColor);
		assertNotNull(parsed.backgroundColor);
		assertEquals(0xFFFFFFFF, (int) parsed.foregroundColor);
		assertEquals(0xFF000000, (int) parsed.backgroundColor);
	}

	private static String colorDict(double r, double g, double b) {
		return "<dict>"
				+ "<key>Red Component</key><real>" + r + "</real>"
				+ "<key>Green Component</key><real>" + g + "</real>"
				+ "<key>Blue Component</key><real>" + b + "</real>"
				+ "<key>Alpha Component</key><real>1.0</real>"
				+ "</dict>";
	}
}

