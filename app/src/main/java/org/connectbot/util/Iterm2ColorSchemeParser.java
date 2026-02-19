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

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Parses iTerm2 {@code .itermcolors} files (plist XML) into a 16-color ANSI palette.
 *
 * <p>iTerm2 schemes are XML plists where ANSI colors are stored under keys like:
 * {@code Ansi 0 Color} .. {@code Ansi 15 Color}.</p>
 *
 * <p>Optional keys like {@code Foreground Color} / {@code Background Color} may exist and are
 * returned (as ARGB ints) for optional default-FG/BG heuristics.</p>
 */
public final class Iterm2ColorSchemeParser {
	private Iterm2ColorSchemeParser() {
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

	private static final Pattern ANSI_KEY = Pattern.compile("^Ansi (\\d+) Color$");

	public static boolean looksLikeIterm2Scheme(String text) {
		if (text == null) return false;
		String trimmed = ltrim(text);
		return trimmed.startsWith("<") && trimmed.toLowerCase().contains("<plist");
	}

	public static ParsedScheme parse(String xmlPlist) {
		if (xmlPlist == null) {
			throw new IllegalArgumentException("xmlPlist is null");
		}
		String trimmed = ltrim(xmlPlist);
		if (!trimmed.startsWith("<") || trimmed.toLowerCase().indexOf("<plist") < 0) {
			throw new IllegalArgumentException("Not an iTerm2 plist");
		}

		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);

			// The common iTerm2 plist includes a DOCTYPE referencing Apple's DTD.
			// Avoid fetching external resources (XXE / network fetches).
			trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
			trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
			trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);

			javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

			org.w3c.dom.Document document = builder.parse(new InputSource(new StringReader(xmlPlist)));
			Element root = document.getDocumentElement();
			if (root == null || !"plist".equals(root.getTagName())) {
				throw new IllegalArgumentException("Invalid plist root");
			}

			Element outerDict = firstChildElement(root, "dict");
			if (outerDict == null) {
				throw new IllegalArgumentException("Missing plist dict");
			}

			int[] palette = new int[16];
			System.arraycopy(Colors.defaults, 0, palette, 0, 16);
			boolean[] foundAnsi = new boolean[16];
			final Integer[] fgBg = new Integer[] {null, null};

			forEachPlistKeyValue(outerDict, new KeyValueVisitor() {
				@Override
				public void visit(String key, Element valueElement) {
					if (key == null) return;
					Matcher m = ANSI_KEY.matcher(key);
					if (m.matches()) {
						int idx;
						try {
							idx = Integer.parseInt(m.group(1));
						} catch (NumberFormatException e) {
							return;
						}
						if (idx >= 0 && idx < 16) {
							int color = parseColorDict(valueElement);
							palette[idx] = color;
							foundAnsi[idx] = true;
						}
					} else if ("Foreground Color".equals(key)) {
						fgBg[0] = parseColorDict(valueElement);
					} else if ("Background Color".equals(key)) {
						fgBg[1] = parseColorDict(valueElement);
					}
				}
			});

			boolean any = false;
			for (boolean b : foundAnsi) {
				if (b) {
					any = true;
					break;
				}
			}
			if (!any) {
				throw new IllegalArgumentException("No ANSI colors found in plist");
			}
			for (int i = 0; i < foundAnsi.length; i++) {
				if (!foundAnsi[i]) {
					throw new IllegalArgumentException("Missing ANSI colors: " + i);
				}
			}

			return new ParsedScheme(palette, fgBg[0], fgBg[1]);
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to parse plist: " + e.getMessage(), e);
		}
	}

	private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
		try {
			factory.setFeature(feature, value);
		} catch (Exception ignored) {
			// Best-effort: parser implementations vary across Android/JVM.
		}
	}

	private static String ltrim(String s) {
		int i = 0;
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		return s.substring(i);
	}

	private static Element firstChildElement(Element parent, String tagName) {
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node instanceof Element) {
				Element e = (Element) node;
				if (tagName.equals(e.getTagName())) {
					return e;
				}
			}
		}
		return null;
	}

	private interface KeyValueVisitor {
		void visit(String key, Element valueElement);
	}

	private static void forEachPlistKeyValue(Element dict, KeyValueVisitor visitor) {
		if (!"dict".equals(dict.getTagName())) {
			throw new IllegalArgumentException("Expected dict");
		}
		NodeList children = dict.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node node = children.item(i);
			if (node instanceof Element && "key".equals(((Element) node).getTagName())) {
				String key = ((Element) node).getTextContent().trim();
				Element value = nextElementSibling(children, i + 1);
				if (value == null) {
					throw new IllegalArgumentException("Missing value for key: " + key);
				}
				visitor.visit(key, value);
			}
		}
	}

	private static Element nextElementSibling(NodeList nodes, int startIndex) {
		for (int i = startIndex; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node instanceof Element) {
				return (Element) node;
			}
		}
		return null;
	}

	private static int parseColorDict(Element colorValueElement) {
		if (!"dict".equals(colorValueElement.getTagName())) {
			throw new IllegalArgumentException("Color value is not a dict");
		}

		final double[] rgba = new double[] {0.0, 0.0, 0.0, 1.0};

		forEachPlistKeyValue(colorValueElement, (key, value) -> {
			String text = value.getTextContent();
			if (text == null) return;
			double numeric;
			try {
				numeric = Double.parseDouble(text.trim());
			} catch (NumberFormatException e) {
				return;
			}
			switch (key) {
				case "Red Component":
					rgba[0] = numeric;
					break;
				case "Green Component":
					rgba[1] = numeric;
					break;
				case "Blue Component":
					rgba[2] = numeric;
					break;
				case "Alpha Component":
					rgba[3] = numeric;
					break;
				default:
					break;
			}
		});

		int r = toByte(normalizeComponent(rgba[0]));
		int g = toByte(normalizeComponent(rgba[1]));
		int b = toByte(normalizeComponent(rgba[2]));
		int a = toByte(normalizeComponent(rgba[3]));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static double normalizeComponent(double value) {
		if (Double.isNaN(value)) return 0.0;
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
}

