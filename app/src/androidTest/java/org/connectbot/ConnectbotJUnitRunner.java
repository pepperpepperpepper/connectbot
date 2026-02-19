/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2019 Kenny Root, Jeffrey Sharkey
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

import com.linkedin.android.testbutler.TestButler;

import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import androidx.test.runner.AndroidJUnitRunner;

public class ConnectbotJUnitRunner extends AndroidJUnitRunner {
		private static final String TAG = "CB.JUnitRunner";

		private static boolean shouldUseTestButler() {
				// TestButler is implemented via UiAutomation and has been observed to crash the
				// instrumentation process on newer Android versions (e.g., Android 16) with:
				// "Cannot call disconnect() while connecting UiAutomation".
				//
				// Gradle's `testOptions.animationsDisabled = true` already disables animations for tests,
				// so TestButler is primarily a best-effort convenience on older devices.
				return Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM;
		}

		@Override
		public void onStart() {
			if (shouldUseTestButler()) {
				try {
					TestButler.setup(getTargetContext());
				} catch (Throwable t) {
					Log.w(TAG, "TestButler.setup failed; continuing without TestButler", t);
				}
			}
			super.onStart();
		}

		@Override
		public void finish(int resultCode, Bundle results) {
			if (shouldUseTestButler()) {
				try {
					TestButler.teardown(getTargetContext());
				} catch (Throwable t) {
					Log.w(TAG, "TestButler.teardown failed; continuing without TestButler", t);
				}
			}
			super.finish(resultCode, results);
		}
}
