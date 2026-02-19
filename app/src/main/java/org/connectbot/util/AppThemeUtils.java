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

import org.connectbot.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.preference.PreferenceManager;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Helper for applying user-selected app UI colors (e.g., the ActionBar/header).
 */
public final class AppThemeUtils {
	private AppThemeUtils() {
	}

	public static void apply(AppCompatActivity activity) {
		apply(activity, null);
	}

	/**
	 * Apply the given raw preference value immediately (useful for live preview in Settings).
	 * If {@code rawOverride} is {@code null}, uses the stored preference value.
	 */
	public static void apply(AppCompatActivity activity, String rawOverride) {
		if (activity == null) return;

		String raw = rawOverride;
		if (raw == null) {
			SharedPreferences prefs =
					PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());
			raw = prefs.getString(PreferenceConstants.APP_THEME_COLOR, "default");
		}

		int primary = resolvePrimaryColor(activity, raw);
		int darkPrimary = resolveDarkPrimaryColor(activity, raw, primary);

		ActionBar actionBar = activity.getSupportActionBar();
		if (actionBar != null) {
			actionBar.setBackgroundDrawable(new ColorDrawable(primary));
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			activity.getWindow().setStatusBarColor(darkPrimary);
		}
	}

	public static int resolvePrimaryColor(Context context, String raw) {
		int defaultPrimary = ContextCompat.getColor(context, R.color.primary);
		return resolvePrimaryColor(context, raw, defaultPrimary);
	}

	private static int resolvePrimaryColor(Context context, String raw, int fallback) {
		if (context == null) return fallback;
		if (raw == null) return fallback;
		String trimmed = raw.trim();
		if (trimmed.isEmpty() || "default".equals(trimmed)) return fallback;
		return parseColorValue(trimmed, fallback);
	}

	private static int resolveDarkPrimaryColor(Context context, String raw, int primaryColor) {
		if (context == null) return darken(primaryColor, 0.85f);
		if (raw == null) return darken(primaryColor, 0.85f);
		String trimmed = raw.trim();
		if (trimmed.isEmpty() || "default".equals(trimmed)) {
			return ContextCompat.getColor(context, R.color.dark_primary);
		}
		return darken(primaryColor, 0.85f);
	}

	private static int parseColorValue(String raw, int fallback) {
		if (raw == null) return fallback;
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) return fallback;

		try {
			if (trimmed.startsWith("#")) return Color.parseColor(trimmed);
			if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
				return (int) Long.parseLong(trimmed.substring(2), 16);
			}
			// Accept bare hex, like RRGGBB or AARRGGBB.
			return Color.parseColor("#" + trimmed);
		} catch (Exception e) {
			return fallback;
		}
	}

	private static int darken(int argb, float factor) {
		if (factor < 0f) factor = 0f;
		if (factor > 1f) factor = 1f;
		int a = Color.alpha(argb);
		int r = clamp(Math.round(Color.red(argb) * factor));
		int g = clamp(Math.round(Color.green(argb) * factor));
		int b = clamp(Math.round(Color.blue(argb) * factor));
		return Color.argb(a, r, g, b);
	}

	private static int clamp(int v) {
		if (v < 0) return 0;
		if (v > 255) return 255;
		return v;
	}
}
