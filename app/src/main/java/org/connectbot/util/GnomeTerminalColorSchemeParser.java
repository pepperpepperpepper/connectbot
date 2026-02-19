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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses common GNOME Terminal color scheme exports into a 16-color ANSI palette.
 *
 * <p>Supported inputs (best-effort):</p>
 * <ul>
 *   <li>{@code dconf dump} / {@code gsettings get} style entries:
 *     <ul>
 *       <li>{@code palette=['rgb(...)', ...]}</li>
 *       <li>{@code foreground-color='rgb(...)'}</li>
 *       <li>{@code background-color='rgb(...)'}</li>
 *     </ul>
 *   </li>
 *   <li>Common "Gogh" theme scripts:
 *     <ul>
 *       <li>{@code PALETTE="#RRGGBB:#RRGGBB:... (16 entries)"}</li>
 *       <li>{@code FOREGROUND_COLOR="#RRGGBB"}, {@code BACKGROUND_COLOR="#RRGGBB"}</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class GnomeTerminalColorSchemeParser {
	private GnomeTerminalColorSchemeParser() {
	}

	public static final class ParsedScheme {
		public final int[] ansiColors;
		public final Integer foregroundColor;
		public final Integer backgroundColor;

		private ParsedScheme(int[] ansiColors, Integer foregroundColor, Integer backgroundColor) {
			this.ansiColors = ansiColors;
			this.foregroundColor = foregroundColor;
			this.backgroundColor = backgroundColor;
		}
	}

	private static final Pattern ASSIGNMENT =
			Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z0-9_-]+)\\s*=\\s*(.*?)\\s*$");

	private static final Pattern QUOTED_TOKEN = Pattern.compile("['\"]([^'\"]+)['\"]");

	private static final Pattern RGB =
			Pattern.compile("(?i)rgba?\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*(?:,\\s*([0-9.]+)\\s*)?\\)");

	private static final Pattern COLON_HEX_PALETTE =
			Pattern.compile("(?i)#[0-9a-f]{6}(?::#[0-9a-f]{6}){15}");

	public static boolean looksLikeGnomeTerminalScheme(String text) {
		if (text == null) return false;
		return text.toLowerCase(Locale.US).contains("palette") || COLON_HEX_PALETTE.matcher(text).find();
	}

	public static ParsedScheme parse(String text) {
		if (text == null) throw new IllegalArgumentException("text is null");

		Map<String, String> assignments = parseAssignments(text);

		String paletteValue = assignments.get("palette");
		int[] palette =
				paletteValue != null ? parsePaletteValue(paletteValue) : parsePaletteFallback(text);
		if (palette == null) {
			throw new IllegalArgumentException("No GNOME Terminal palette found");
		}

		Integer fg = null;
		Integer bg = null;
		String fgRaw = firstNonNull(assignments.get("foreground_color"), assignments.get("fg_color"));
		String bgRaw = firstNonNull(assignments.get("background_color"), assignments.get("bg_color"));
		if (fgRaw != null) fg = parseColorString(fgRaw);
		if (bgRaw != null) bg = parseColorString(bgRaw);

		return new ParsedScheme(palette, fg, bg);
	}

	private static Map<String, String> parseAssignments(String text) {
		Map<String, String> result = new LinkedHashMap<>();
		String[] lines = text.split("\n");
		for (String rawLine : lines) {
			if (rawLine == null) continue;
			String line = rawLine.trim();
			if (line.isEmpty()) continue;
			if (line.startsWith("#") || line.startsWith(";")) continue;

			Matcher m = ASSIGNMENT.matcher(rawLine);
			if (!m.matches()) continue;
			String key = normalizeKey(m.group(1));
			String value = m.group(2) != null ? m.group(2).trim() : "";
			result.put(key, value);
		}
		return result;
	}

	private static String normalizeKey(String key) {
		if (key == null) return "";
		return key.trim().toLowerCase(Locale.US).replace('-', '_');
	}

	private static String firstNonNull(String a, String b) {
		return a != null ? a : b;
	}

	private static int[] parsePaletteFallback(String text) {
		Matcher m = COLON_HEX_PALETTE.matcher(text);
		if (m.find()) {
			return parseColonSeparatedPalette(m.group());
		}
		return null;
	}

	private static int[] parsePaletteValue(String value) {
		String unwrapped = stripOuterQuotes(value.trim()).trim();
		if (unwrapped.endsWith(";")) unwrapped = unwrapped.substring(0, unwrapped.length() - 1).trim();

		if (unwrapped.startsWith("[")) {
			return parseBracketListPalette(unwrapped);
		}
		if (unwrapped.contains(":")) {
			return parseColonSeparatedPalette(unwrapped);
		}
		throw new IllegalArgumentException("Unsupported palette format");
	}

	private static int[] parseBracketListPalette(String listText) {
		String inner = listText.trim();
		if (inner.startsWith("[")) inner = inner.substring(1);
		if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
		inner = inner.trim();
		if (inner.isEmpty()) throw new IllegalArgumentException("Empty palette list");

		List<String> tokens = new ArrayList<>();
		Matcher quoted = QUOTED_TOKEN.matcher(inner);
		while (quoted.find()) {
			tokens.add(quoted.group(1));
		}
		if (tokens.isEmpty()) {
			String[] parts = inner.split(",");
			for (String part : parts) {
				String t = part.trim();
				if (!t.isEmpty()) tokens.add(t);
			}
		}

		if (tokens.size() != 16) {
			throw new IllegalArgumentException("Expected 16 palette colors, got " + tokens.size());
		}

		int[] palette = new int[16];
		for (int i = 0; i < 16; i++) {
			palette[i] = parseColorString(tokens.get(i));
		}
		return palette;
	}

	private static int[] parseColonSeparatedPalette(String value) {
		String unwrapped = stripOuterQuotes(value.trim()).trim();
		String[] parts = unwrapped.split(":");
		List<String> tokens = new ArrayList<>();
		for (String p : parts) {
			String t = p.trim();
			if (!t.isEmpty()) tokens.add(t);
		}
		if (tokens.size() != 16) {
			throw new IllegalArgumentException("Expected 16 palette colors, got " + tokens.size());
		}
		int[] palette = new int[16];
		for (int i = 0; i < 16; i++) {
			palette[i] = parseColorString(tokens.get(i));
		}
		return palette;
	}

	private static String stripOuterQuotes(String value) {
		if (value == null) return "";
		String trimmed = value.trim();
		if (trimmed.length() < 2) return trimmed;
		char first = trimmed.charAt(0);
		char last = trimmed.charAt(trimmed.length() - 1);
		if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

	private static int parseColorString(String value) {
		String cleaned = stripOuterQuotes(value == null ? "" : value.trim()).trim();
		if (cleaned.isEmpty()) throw new IllegalArgumentException("Empty color");

		Matcher rgb = RGB.matcher(cleaned);
		if (rgb.matches()) {
			double r = Double.parseDouble(rgb.group(1));
			double g = Double.parseDouble(rgb.group(2));
			double b = Double.parseDouble(rgb.group(3));
			double a = rgb.group(4) != null ? Double.parseDouble(rgb.group(4)) : 1.0;

			int rr = toByte(normalizeComponent(r));
			int gg = toByte(normalizeComponent(g));
			int bb = toByte(normalizeComponent(b));
			int aa = toByte(normalizeAlpha(a));
			return (aa << 24) | (rr << 16) | (gg << 8) | bb;
		}

		return parseHexColor(cleaned);
	}

	private static double normalizeComponent(double value) {
		if (Double.isNaN(value)) return 0.0;
		if (value <= 0.0) return 0.0;
		if (value <= 1.0) return value;
		if (value <= 255.0) return value / 255.0;
		return 1.0;
	}

	private static double normalizeAlpha(double value) {
		if (Double.isNaN(value)) return 1.0;
		if (value <= 0.0) return 0.0;
		if (value <= 1.0) return value;
		if (value <= 255.0) return value / 255.0;
		return 1.0;
	}

	private static int toByte(double v) {
		int b = (int) Math.round(v * 255.0);
		if (b < 0) return 0;
		if (b > 255) return 255;
		return b;
	}

	private static int parseHexColor(String value) {
		String trimmed = value.trim();
		String hex = trimmed;
		if (hex.startsWith("#")) hex = hex.substring(1);
		if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
		hex = hex.toLowerCase(Locale.US);

		if (hex.length() == 3) {
			hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
		} else if (hex.length() == 4) {
			hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2) + hex.charAt(3) + hex.charAt(3);
		}

		if (hex.length() == 6) {
			int r = Integer.parseInt(hex.substring(0, 2), 16);
			int g = Integer.parseInt(hex.substring(2, 4), 16);
			int b = Integer.parseInt(hex.substring(4, 6), 16);
			return (0xFF << 24) | (r << 16) | (g << 8) | b;
		}
		if (hex.length() == 8) {
			boolean looksLikeAarrggbb = hex.startsWith("ff") || hex.startsWith("00");
			if (looksLikeAarrggbb) {
				int a = Integer.parseInt(hex.substring(0, 2), 16);
				int r = Integer.parseInt(hex.substring(2, 4), 16);
				int g = Integer.parseInt(hex.substring(4, 6), 16);
				int b = Integer.parseInt(hex.substring(6, 8), 16);
				return (a << 24) | (r << 16) | (g << 8) | b;
			} else {
				int r = Integer.parseInt(hex.substring(0, 2), 16);
				int g = Integer.parseInt(hex.substring(2, 4), 16);
				int b = Integer.parseInt(hex.substring(4, 6), 16);
				int a = Integer.parseInt(hex.substring(6, 8), 16);
				return (a << 24) | (r << 16) | (g << 8) | b;
			}
		}
		throw new IllegalArgumentException("Unsupported color format: " + value);
	}
}

