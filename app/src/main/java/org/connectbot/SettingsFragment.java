/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2017 Kenny Root, Jeffrey Sharkey
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

package org.connectbot;

import org.connectbot.util.VolumePreference;
import org.connectbot.util.VolumePreferenceFragment;

import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import org.connectbot.R;
import org.connectbot.util.AppThemeUtils;
import org.connectbot.util.PreferenceConstants;

/**
 * Created by kenny on 2/20/17.
 */

public class SettingsFragment extends PreferenceFragmentCompat {
	public SettingsFragment() {
	}

	/**
	 * Called when a preference in the tree requests to display a dialog. Subclasses should
	 * override this method to display custom dialogs or to handle dialogs for custom preference
	 * classes.
	 *
	 * @param preference The Preference object requesting the dialog.
	 */
	@Override
	public void onDisplayPreferenceDialog(Preference preference) {
		if (preference instanceof VolumePreference) {
			DialogFragment fragment = VolumePreferenceFragment.newInstance(preference);
			fragment.setTargetFragment(this, 0);
			fragment.show(getFragmentManager(),
					"android.support.v7.preference.PreferenceFragment.DIALOG");
		} else {
			super.onDisplayPreferenceDialog(preference);
		}
	}

	@Override
	public void onCreatePreferences(Bundle bundle, String rootKey) {
		setPreferencesFromResource(R.xml.preferences, rootKey);
		setupAppThemeColorPreference();
	}

	private void setupAppThemeColorPreference() {
		ListPreference pref = findPreference(PreferenceConstants.APP_THEME_COLOR);
		if (pref == null) return;

		final CharSequence[] plainEntries = pref.getEntries();
		final CharSequence[] values = pref.getEntryValues();
		if (plainEntries == null || values == null) return;

		// Add a small swatch in the chooser dialog using a colored square + label.
		if (plainEntries.length == values.length) {
			CharSequence[] previewEntries = new CharSequence[plainEntries.length];
			for (int i = 0; i < plainEntries.length; i++) {
				String raw = String.valueOf(values[i]);
				int color = AppThemeUtils.resolvePrimaryColor(requireContext(), raw);
				previewEntries[i] = colorizeEntry(plainEntries[i], color);
			}
			pref.setEntries(previewEntries);
		}

		pref.setSummaryProvider(preference -> {
			String value = pref.getValue();
			int idx = pref.findIndexOfValue(value);
			CharSequence label = (idx >= 0 && plainEntries != null && idx < plainEntries.length)
					? plainEntries[idx]
					: value;
			return getString(R.string.pref_app_theme_color_summary, label);
		});

		pref.setOnPreferenceChangeListener((preference, newValue) -> {
			if (getActivity() instanceof AppCompatActivity) {
				AppThemeUtils.apply((AppCompatActivity) getActivity(), String.valueOf(newValue));
			}
			return true;
		});
	}

	private static CharSequence colorizeEntry(CharSequence label, int color) {
		String bullet = "\u25A0";
		SpannableString s = new SpannableString(bullet + " " + String.valueOf(label));
		s.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		s.setSpan(new RelativeSizeSpan(1.2f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		return s;
	}
}
