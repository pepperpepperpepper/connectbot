package org.connectbot.util;

import android.content.Context;
import android.util.Log;

import org.connectbot.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SelectionDebugLogger {
	private static final String TAG = "CB.SelectionDebug";
	private static final String FILE_NAME = "selection-debug.log";
	private static final long MAX_FILE_BYTES = 512 * 1024;
	private static final Object LOCK = new Object();
	private static boolean announcedPath = false;

	private SelectionDebugLogger() {
	}

	public static boolean isEnabled() {
		return BuildConfig.DEBUG;
	}

	public static void log(Context context, String message) {
		if (!isEnabled()) {
			return;
		}

		String line = System.currentTimeMillis() + " " + message;
		Log.d(TAG, line);

		if (context == null) {
			return;
		}

		synchronized (LOCK) {
			File file = new File(context.getFilesDir(), FILE_NAME);
			try {
				if (!announcedPath) {
					announcedPath = true;
					Log.d(TAG, "selection debug log file=" + file.getAbsolutePath());
				}

				if (file.exists() && file.length() > MAX_FILE_BYTES) {
					FileOutputStream truncate = new FileOutputStream(file, false);
					truncate.close();
				}

				FileOutputStream output = new FileOutputStream(file, true);
				try {
					output.write(line.getBytes(StandardCharsets.UTF_8));
					output.write('\n');
				} finally {
					output.close();
				}
			} catch (IOException e) {
				Log.e(TAG, "Failed writing selection debug log", e);
			}
		}
	}

	public static String getLogFilePath(Context context) {
		if (context == null) {
			return "";
		}
		return new File(context.getFilesDir(), FILE_NAME).getAbsolutePath();
	}
}
