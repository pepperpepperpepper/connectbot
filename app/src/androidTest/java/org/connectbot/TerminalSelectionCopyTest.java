package org.connectbot;

import org.connectbot.util.HostDatabase;
import org.connectbot.util.PreferenceConstants;
import org.connectbot.util.TerminalTextViewOverlay;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.util.Collection;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnHolderItem;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static androidx.test.runner.lifecycle.Stage.RESUMED;
import static org.connectbot.ConnectbotMatchers.withHostNickname;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

@RunWith(AndroidJUnit4.class)
public class TerminalSelectionCopyTest {
	private static final long KEYBOARD_DISMISSAL_DELAY_MILLIS = 1000L;
	private static final long TERMINAL_UI_SETTLE_DELAY_MILLIS = 500L;
	private static final String STRESS_SCROLLBACK_LINES = "1000";

	@Rule
	public final ActivityTestRule<HostListActivity> activityRule = new ActivityTestRule<>(
			HostListActivity.class, false, false);

	@Before
	public void setUp() {
		Context testContext = ApplicationProvider.getApplicationContext();
		grantPostNotificationsPermissionIfNeeded(testContext);
		enableImeOnHardKeyboardIfPossible();
		HostDatabase.resetInMemoryInstance(testContext);

		activityRule.launchActivity(new Intent());
	}

	@Test
	public void selectionCopyWorksWithSoftKeyboardVisible() {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
					.putString(PreferenceConstants.SCROLLBACK, STRESS_SCROLLBACK_LINES)
					.commit();

			startNewLocalConnectionWithoutIntents("Local");
			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			ensureSoftKeyboardVisibility(consoleActivity, true);

			final String token = "SELECTIONCOPYTOKEN_VISIBLE";
			insertTerminalOutput(terminalView, "\r\n" + token + "\r\n");
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);
			clipboard.setText("");

			String clip = selectTokenAndCopy(terminalView, clipboard, tokenPos, ViewportAnchor.MIDDLE);
			assertThat(clip, equalTo(token));
		} finally {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	@Test
	public void selectionCopyWorksAfterHidingSoftKeyboard() {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
					.putString(PreferenceConstants.SCROLLBACK, STRESS_SCROLLBACK_LINES)
					.commit();

			startNewLocalConnectionWithoutIntents("Local");

			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			ensureSoftKeyboardVisibility(consoleActivity, true);
			final int rowsWithKeyboardVisible = getTerminalRows(terminalView);
			ensureSoftKeyboardVisibility(consoleActivity, false);
			waitForTerminalRowsChange(terminalView, rowsWithKeyboardVisible, 10000L);

			final String tokenTop = "SELECTIONCOPYTOKEN_TOP";
			final String tokenMid = "SELECTIONCOPYTOKEN_MID";
			final String tokenBottom = "SELECTIONCOPYTOKEN_BOTTOM";
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < 120; i++) {
				out.append("LINE").append(i);
				if (i == 20) out.append(" ").append(tokenTop);
				if (i == 60) out.append(" ").append(tokenMid);
				if (i == 100) out.append(" ").append(tokenBottom);
				out.append("\r\n");
			}
			insertTerminalOutput(terminalView, out.toString());
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			BufferPosition topPos = waitForTokenPosition(terminalView, tokenTop, 5000L);
			BufferPosition midPos = waitForTokenPosition(terminalView, tokenMid, 5000L);
			BufferPosition bottomPos = waitForTokenPosition(terminalView, tokenBottom, 5000L);

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);
			clipboard.setText("");

			assertThat(selectTokenAndCopy(terminalView, clipboard, bottomPos, ViewportAnchor.BOTTOM), equalTo(tokenBottom));
			assertThat(selectTokenAndCopy(terminalView, clipboard, midPos, ViewportAnchor.MIDDLE), equalTo(tokenMid));
			assertThat(selectTokenAndCopy(terminalView, clipboard, topPos, ViewportAnchor.TOP), equalTo(tokenTop));
		} finally {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	@Test
	public void selectionCopyWorksAfterRepeatedKeyboardTogglesAndOutput() {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
					.putString(PreferenceConstants.SCROLLBACK, STRESS_SCROLLBACK_LINES)
					.commit();

			startNewLocalConnectionWithoutIntents("Local");
			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			// "Works at first, then breaks" often suggests a state drift. Stress repeated
			// IME hide/show + buffer growth and validate copy at multiple viewport anchors.
			for (int i = 0; i < 4; i++) {
				boolean wantKeyboardVisible = (i % 2 == 0);
				ensureSoftKeyboardVisibility(consoleActivity, wantKeyboardVisible);

				assertOverlayAlignedWithTerminalGrid(terminalView);

				// Keep tokens short so they reliably fit within narrow terminal widths without wrapping.
				final String tokenTop = "RT" + i + "_TOP";
				final String tokenMid = "RT" + i + "_MID";
				final String tokenBottom = "RT" + i + "_BOT";

				// Avoid relying on large scrollback when the keyboard is visible (fewer rows => smaller
				// buffer). Keep the injected output bounded relative to the current screen height.
				final int lineCount = getTerminalRows(terminalView) + 20;
				final int topLine = Math.max(1, lineCount / 4);
				final int midLine = Math.max(2, lineCount / 2);
				final int bottomLine = Math.max(3, lineCount - 3);
				StringBuilder out = new StringBuilder();
				for (int line = 0; line < lineCount; line++) {
					if (line == topLine) {
						out.append(tokenTop).append(" ITER").append(i).append("_LINE").append(line);
					} else if (line == midLine) {
						out.append(tokenMid).append(" ITER").append(i).append("_LINE").append(line);
					} else if (line == bottomLine) {
						out.append(tokenBottom).append(" ITER").append(i).append("_LINE").append(line);
					} else {
						out.append("ITER").append(i).append("_LINE").append(line);
					}
					out.append("\r\n");
				}
				insertTerminalOutput(terminalView, out.toString());
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				BufferPosition topPos = waitForTokenPosition(terminalView, tokenTop, 5000L);
				BufferPosition midPos = waitForTokenPosition(terminalView, tokenMid, 5000L);
				BufferPosition bottomPos = waitForTokenPosition(terminalView, tokenBottom, 5000L);

				ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);
				clipboard.setText("");

				assertThat(selectTokenAndCopy(terminalView, clipboard, bottomPos, ViewportAnchor.BOTTOM), equalTo(tokenBottom));
				assertThat(selectTokenAndCopy(terminalView, clipboard, midPos, ViewportAnchor.MIDDLE), equalTo(tokenMid));
				assertThat(selectTokenAndCopy(terminalView, clipboard, topPos, ViewportAnchor.TOP), equalTo(tokenTop));
			}
		} finally {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	@Test
	public void selectionCopyRemainsStableAfterManyKeyboardTogglesWithLargeScrollback() {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
					.putString(PreferenceConstants.SCROLLBACK, "2000")
					.commit();

			startNewLocalConnectionWithoutIntents("Local");
			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);

			for (int i = 0; i < 12; i++) {
				boolean wantKeyboardVisible = (i % 2 == 0);
				ensureSoftKeyboardVisibility(consoleActivity, wantKeyboardVisible);

				// Keep the per-iteration token short so it never wraps even on narrow terminals.
				final String token = "BURNIN_T" + i;

				final int rows = getTerminalRows(terminalView);
				final int lineCount = rows + 120;
				final int tokenLine = Math.max(1, lineCount - 3);

				StringBuilder out = new StringBuilder();
				for (int line = 0; line < lineCount; line++) {
					if (line == tokenLine) {
						out.append(token);
					} else {
						out.append("BURNIN_ITER").append(i).append("_LINE").append(line);
					}
					out.append("\r\n");
				}
				insertTerminalOutput(terminalView, "\r\n" + out.toString());
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);

				clipboard.setText("");
				assertThat(selectTokenAndCopy(terminalView, clipboard, tokenPos, ViewportAnchor.BOTTOM), equalTo(token));

				// Validate low-level hit-testing after state changes so "works at first, then breaks"
				// is caught even if the clipboard contents happen to look correct.
				if (i == 0 || i == 5 || i == 11) {
					assertHitTestingMatchesTerminalGrid(terminalView);
				}
			}
		} finally {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	@Test
	public void hitTestingMatchesTerminalGridWithKeyboardVisibleAndHidden() {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
					.putString(PreferenceConstants.SCROLLBACK, STRESS_SCROLLBACK_LINES)
					.commit();

			startNewLocalConnectionWithoutIntents("Local");
			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			ensureSoftKeyboardVisibility(consoleActivity, true);
			assertHitTestingMatchesTerminalGrid(terminalView);

			ensureSoftKeyboardVisibility(consoleActivity, false);
			assertHitTestingMatchesTerminalGrid(terminalView);
		} finally {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	private enum ViewportAnchor { TOP, MIDDLE, BOTTOM }

	private static String selectTokenAndCopy(TerminalView terminalView, ClipboardManager clipboard, BufferPosition tokenPos, ViewportAnchor anchor) {
		final int rows = getTerminalRows(terminalView);
		int targetBase;
		switch (anchor) {
		case TOP:
			targetBase = tokenPos.row;
			break;
		case MIDDLE:
			targetBase = tokenPos.row - (rows / 2);
			break;
		case BOTTOM:
			targetBase = tokenPos.row - (rows - 2);
			break;
		default:
			targetBase = tokenPos.row;
		}

		final int base = Math.max(0, targetBase);
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				terminalView.bridge.buffer.setWindowBase(base);
			}
		});
		onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

		int windowBase = getWindowBase(terminalView);
		float x = (tokenPos.col + 2) * terminalView.bridge.charWidth + terminalView.bridge.charWidth / 2f;
		float y = (tokenPos.row - windowBase) * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;
		longPressTerminalAt(terminalView, x, y);
		onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));
		assertOverlayScrollAlignedWithWindowBase(terminalView);

		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				terminalView.copyCurrentSelectionToClipboard();
			}
		});

		String clip = clipboard.hasText() ? clipboard.getText().toString() : "";
		clipboard.setText("");
		return clip;
	}

	private static void assertOverlayAlignedWithTerminalGrid(final TerminalView terminalView) {
		final int[] overlayLineHeight = new int[1];
		final int[] bridgeCharHeight = new int[1];

		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
				overlayLineHeight[0] = overlay.getLineHeight();
				bridgeCharHeight[0] = terminalView.bridge.charHeight;
			}
		});

		assertThat("Overlay line height must match terminal char height", overlayLineHeight[0], equalTo(bridgeCharHeight[0]));
	}

	private static void assertOverlayScrollAlignedWithWindowBase(final TerminalView terminalView) {
		final int[] overlayScrollY = new int[1];
		final int[] expectedScrollY = new int[1];

		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
				overlayScrollY[0] = overlay.getScrollY();
				expectedScrollY[0] = terminalView.bridge.buffer.getWindowBase() * overlay.getLineHeight();
			}
		});

		assertThat("Overlay scroll position must align to windowBase", overlayScrollY[0], equalTo(expectedScrollY[0]));
	}

	private static void assertHitTestingMatchesTerminalGrid(final TerminalView terminalView) {
		waitForOverlayFontAndMetrics(terminalView, 5000L);

		final int rows = getTerminalRows(terminalView);
		final int cols = getTerminalCols(terminalView);

		final String token = "GRIDSTART";
		final int warmupLines = 600;
		final int lineCount = rows + 50;

		StringBuilder out = new StringBuilder();
		for (int line = 0; line < warmupLines; line++) {
			out.append("W").append(line).append("\r\n");
		}
		for (int line = 0; line < lineCount; line++) {
			char[] chars = new char[cols];
			if (cols > 0) {
				// Row-varying content in column 0 so vertical drift is detectable.
				chars[0] = (char) ('A' + (line % 26));
			}
			if (line == 0) {
				// Start token at col=1 so col=0 remains the row-varying sentinel.
				for (int i = 0; i < token.length() && (i + 1) < cols; i++) {
					chars[i + 1] = token.charAt(i);
				}
			}
			for (int c = 1; c < cols; c++) {
				if (chars[c] == 0) {
					chars[c] = (char) ('0' + (c % 10));
				}
			}
			out.append(chars).append("\r\n");
		}

		// Ensure the grid starts at column 0 (shell prompts can leave the cursor mid-line).
		insertTerminalOutput(terminalView, "\r\n" + out.toString());
		onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

		BufferPosition gridStartPos = waitForTokenPosition(terminalView, token, 5000L);
		final int base = gridStartPos.row;

		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				terminalView.bridge.buffer.setWindowBase(base);
				TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
				overlay.refreshTextFromBuffer();
			}
		});
		onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

		final int windowBase = getWindowBase(terminalView);

		// Sample a few points across the viewport (top/mid/bottom) and ensure TextView hit-testing
		// lines up with the terminal grid.
		final int maxSampleRow = Math.min(rows - 1, lineCount - 1);
		final int[] sampleRows = new int[] { 0, 1, rows / 2, rows - 2 };
		final int[] sampleCols = new int[] { 0, 1, cols / 4, cols / 2, cols - 1 };

		for (int screenRow : sampleRows) {
			if (screenRow < 0 || screenRow > maxSampleRow) {
				continue;
			}
			for (int col : sampleCols) {
				if (col < 0 || col >= cols) {
					continue;
				}
				char expected = getBufferCharAt(terminalView, windowBase + screenRow, col);
				if (expected == 0 || Character.isWhitespace(expected)) {
					continue;
				}
				float x = col * terminalView.bridge.charWidth + Math.max(0.5f, terminalView.bridge.charWidth * 0.25f);
				float y = screenRow * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;
				char actual = getOverlayCharAtPosition(terminalView, x, y);
				assertThat("Hit-test mismatch at row=" + screenRow + " col=" + col, actual, equalTo(expected));
			}
		}
	}

	private static void waitForOverlayFontAndMetrics(final TerminalView terminalView, long timeoutMillis) {
		final long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timeoutMillis) {
			final int[] overlayLineHeight = new int[1];
			final float[] overlayTextSize = new float[1];
			final float[] overlayCharWidth = new float[1];
			final float[] overlayScaleX = new float[1];
			final int[] bridgeCharHeight = new int[1];
			final float[] bridgeTextSize = new float[1];
			final int[] bridgeCharWidth = new int[1];

			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
					overlayLineHeight[0] = overlay.getLineHeight();
					overlayTextSize[0] = overlay.getTextSize();
					overlayCharWidth[0] = overlay.getPaint().measureText("X");
					overlayScaleX[0] = overlay.getTextScaleX();

					bridgeCharHeight[0] = terminalView.bridge.charHeight;
					bridgeCharWidth[0] = terminalView.bridge.charWidth;
					bridgeTextSize[0] = terminalView.bridge.getTextSizePx();
				}
			});

			boolean lineHeightOk = overlayLineHeight[0] == bridgeCharHeight[0];
			boolean textSizeOk = Math.abs(overlayTextSize[0] - bridgeTextSize[0]) < 0.01f;
			boolean charWidthOk = Math.abs(overlayCharWidth[0] - bridgeCharWidth[0]) < 0.5f;
			if (lineHeightOk && textSizeOk && charWidthOk) {
				return;
			}

			SystemClock.sleep(100L);
		}

		throw new AssertionError("Timed out waiting for overlay metrics to match terminal grid");
	}

	private static int getTerminalCols(final TerminalView terminalView) {
		final int[] cols = new int[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				cols[0] = terminalView.bridge.getVDUBuffer().getColumns();
			}
		});
		return cols[0];
	}

	private static char getOverlayCharAtPosition(final TerminalView terminalView, final float xPx, final float yPx) {
		final int[] codePoint = new int[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
				int offset = overlay.getOffsetForPosition(xPx, yPx);
				CharSequence text = overlay.getText();
				if (text == null || offset < 0 || offset >= text.length()) {
					codePoint[0] = 0;
					return;
				}
				codePoint[0] = text.charAt(offset);
			}
		});
		return (char) codePoint[0];
	}

	private static char getBufferCharAt(final TerminalView terminalView, final int row, final int col) {
		final int[] codePoint = new int[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
				if (row < 0 || row >= buffer.getBufferSize() || col < 0 || col >= buffer.getColumns()) {
					codePoint[0] = 0;
					return;
				}
				if (buffer.charArray[row] == null) {
					codePoint[0] = 0;
					return;
				}
				codePoint[0] = buffer.charArray[row][col];
			}
		});
		return (char) codePoint[0];
	}

	private static int getWindowBase(final TerminalView terminalView) {
		final int[] base = new int[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				base[0] = terminalView.bridge.buffer.getWindowBase();
			}
		});
		return base[0];
	}

	private static int getTerminalRows(final TerminalView terminalView) {
		final int[] rows = new int[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				rows[0] = terminalView.bridge.getVDUBuffer().getRows();
			}
		});
		return rows[0];
	}

	private static void waitForTerminalRowsChange(final TerminalView terminalView, final int oldRows, long timeoutMillis) {
		final long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timeoutMillis) {
			int rows = getTerminalRows(terminalView);
			if (rows != oldRows) {
				return;
			}
			SystemClock.sleep(100L);
		}
		throw new AssertionError("Timed out waiting for terminal rows to change from " + oldRows);
	}

	private static void longPressTerminalAt(final TerminalView terminalView, final float xPx, final float yPx) {
		final long[] downTime = new long[1];
		final View[] rootView = new View[1];
		final float[] xRoot = new float[1];
		final float[] yRoot = new float[1];

		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				Activity activity = (Activity) terminalView.getContext();
				View root = activity.getWindow().getDecorView();

				int[] rootLoc = new int[2];
				int[] termLoc = new int[2];
				root.getLocationOnScreen(rootLoc);
				terminalView.getLocationOnScreen(termLoc);

				float xScreen = termLoc[0] + xPx;
				float yScreen = termLoc[1] + yPx;

				rootView[0] = root;
				xRoot[0] = xScreen - rootLoc[0];
				yRoot[0] = yScreen - rootLoc[1];

				downTime[0] = SystemClock.uptimeMillis();
				MotionEvent down = MotionEvent.obtain(downTime[0], downTime[0], MotionEvent.ACTION_DOWN, xRoot[0], yRoot[0], 0);
				down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
				root.dispatchTouchEvent(down);
				down.recycle();
			}
		});

		onView(withId(R.id.console_flip)).perform(loopMainThreadFor(android.view.ViewConfiguration.getLongPressTimeout() + 200L));

		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				long upTime = SystemClock.uptimeMillis();
				MotionEvent up = MotionEvent.obtain(downTime[0], upTime, MotionEvent.ACTION_UP, xRoot[0], yRoot[0], 0);
				up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
				rootView[0].dispatchTouchEvent(up);
				up.recycle();
			}
		});
	}

	private static boolean isSoftKeyboardVisible(final Activity activity) {
		final boolean[] visible = new boolean[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				View contentView = activity.findViewById(android.R.id.content);
				Rect r = new Rect();
				contentView.getWindowVisibleDisplayFrame(r);
				int screenHeight = contentView.getRootView().getHeight();
				int keypadHeight = screenHeight - r.bottom;
				visible[0] = keypadHeight > screenHeight * 0.15;
			}
		});
		return visible[0];
	}

	private static ViewAction forceClick() {
		return new ViewAction() {
			@Override
			public org.hamcrest.Matcher<View> getConstraints() {
				return isEnabled();
			}

			@Override
			public String getDescription() {
				return "Performs click via View.performClick() without visibility constraints.";
			}

			@Override
			public void perform(UiController uiController, View view) {
				view.performClick();
				uiController.loopMainThreadUntilIdle();
			}
		};
	}

	private static void toggleSoftKeyboardViaButton() {
		// The keyboard toggle button lives inside the emulated-keys tray, which is normally hidden.
		onView(withId(R.id.console_flip)).perform(forceClick());
		onView(withId(R.id.console_flip)).perform(loopMainThreadFor(250L));
		onView(withId(R.id.button_keyboard)).perform(forceClick());
	}

	private static void ensureSoftKeyboardVisibility(final ConsoleActivity consoleActivity, boolean wantVisible) {
		final long timeoutMillis = 10000L;
		final long start = System.currentTimeMillis();

		while (System.currentTimeMillis() - start < timeoutMillis) {
			boolean isVisible = isSoftKeyboardVisible(consoleActivity);
			if (isVisible == wantVisible) {
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));
				return;
			}

			toggleSoftKeyboardViaButton();
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(KEYBOARD_DISMISSAL_DELAY_MILLIS));
		}

		throw new AssertionError("Timed out waiting for soft keyboard visibility=" + wantVisible);
	}

	private void startNewLocalConnectionWithoutIntents(String name) {
		onView(withId(R.id.add_host_button)).perform(click());
		onView(withId(R.id.protocol_text)).perform(click());
		onView(withText("local")).perform(click());
		onView(withId(R.id.quickconnect_field)).perform(typeText(name));
		onView(withId(R.id.save)).perform(click());

		onView(withId(R.id.list)).perform(actionOnHolderItem(withHostNickname(name), click()));
	}

	private static ConsoleActivity waitForConsoleActivity(long timeoutMillis) {
		final long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timeoutMillis) {
			Activity resumed = getResumedActivity();
			if (resumed instanceof ConsoleActivity) {
				return (ConsoleActivity) resumed;
			}
			SystemClock.sleep(100L);
		}
		throw new AssertionError("Timed out waiting for ConsoleActivity to reach RESUMED");
	}

	private static Activity getResumedActivity() {
		final Activity[] currentActivity = new Activity[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				Collection<Activity> resumedActivities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(RESUMED);
				if (resumedActivities.iterator().hasNext()) {
					currentActivity[0] = resumedActivities.iterator().next();
				}
			}
		});
		return currentActivity[0];
	}

	private static void insertTerminalOutput(final TerminalView terminalView, final String output) {
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				synchronized (terminalView.bridge.buffer) {
					((de.mud.terminal.vt320) terminalView.bridge.buffer).putString(output);
				}
				terminalView.bridge.propagateConsoleText(output.toCharArray(), output.length());
				terminalView.bridge.redraw();
			}
		});
	}

	private static BufferPosition waitForTokenPosition(final TerminalView terminalView, final String token, long timeoutMillis) {
		final long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timeoutMillis) {
			BufferPosition found = findTokenPosition(terminalView, token);
			if (found != null) {
				return found;
			}
			SystemClock.sleep(100L);
		}
		throw new AssertionError("Timed out waiting for token '" + token + "' to appear in the terminal buffer");
	}

	private static BufferPosition findTokenPosition(final TerminalView terminalView, final String token) {
		final BufferPosition[] found = new BufferPosition[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
				int cols = buffer.getColumns();

				for (int r = buffer.getBufferSize() - 1; r >= 0; r--) {
					if (buffer.charArray[r] == null) {
						continue;
					}
					StringBuilder line = new StringBuilder(cols);
					for (int c = 0; c < cols; c++) {
						line.append(buffer.charArray[r][c]);
					}
					String lineStr = line.toString();
					int idx = lineStr.indexOf(token);
					if (idx >= 0) {
						found[0] = new BufferPosition(r, idx);
						return;
					}
				}
			}
		});
		return found[0];
	}

	private static class BufferPosition {
		final int row;
		final int col;

		BufferPosition(int row, int col) {
			this.row = row;
			this.col = col;
		}
	}

	private static void grantPostNotificationsPermissionIfNeeded(Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			return;
		}
		getInstrumentation().getUiAutomation().grantRuntimePermission(
				context.getPackageName(),
				Manifest.permission.POST_NOTIFICATIONS);
	}

	private static void enableImeOnHardKeyboardIfPossible() {
		// Genymotion (and some CI environments) may present a hardware keyboard, which prevents the
		// soft keyboard from showing. Force the on-screen keyboard to appear even with a hardware
		// keyboard present so we can exercise IME resize behavior.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
			return;
		}
		try {
			execShellCommand("settings put secure show_ime_with_hard_keyboard 1");
		} catch (Throwable ignored) {
			// Best-effort; if this fails, tests that rely on IME visibility will fail and surface it.
		}
	}

	private static void execShellCommand(String command) throws IOException {
		ParcelFileDescriptor pfd = getInstrumentation().getUiAutomation().executeShellCommand(command);
		if (pfd != null) {
			pfd.close();
		}
	}

	public static ViewAction loopMainThreadFor(final long millis) {
		return new ViewAction() {
			@Override
			public org.hamcrest.Matcher<View> getConstraints() {
				return isEnabled();
			}

			@Override
			public String getDescription() {
				return "Loops the main thread for at least " + millis + "ms.";
			}

			@Override
			public void perform(final UiController uiController, final View view) {
				uiController.loopMainThreadForAtLeast(millis);
			}
		};
	}
}
