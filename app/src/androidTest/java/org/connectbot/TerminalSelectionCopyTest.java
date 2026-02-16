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
import android.graphics.Bitmap;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

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
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.greaterThan;

@RunWith(AndroidJUnit4.class)
public class TerminalSelectionCopyTest {
	private static final long KEYBOARD_DISMISSAL_DELAY_MILLIS = 1000L;
	private static final long TERMINAL_UI_SETTLE_DELAY_MILLIS = 500L;
	private static final String STRESS_SCROLLBACK_LINES = "1000";
	private static final long STREAMING_OUTPUT_DELAY_MILLIS = 25L;

	@Rule
	public final ActivityTestRule<HostListActivity> activityRule = new ActivityTestRule<>(
			HostListActivity.class, false, false);

	@Before
	public void setUp() {
		Context testContext = ApplicationProvider.getApplicationContext();
		grantPostNotificationsPermissionIfNeeded(testContext);
		enableImeOnHardKeyboardIfPossible();
		wakeAndUnlockDeviceIfPossible();
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

	@Test
	public void selectionCopyWorksWhileOutputIsStreamingAndUserIsScrolledUp() throws InterruptedException {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
					// Keep enough scrollback so the "older" numbered lines stay present while output streams.
					settings.edit()
							.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
							.putString(PreferenceConstants.SCROLLBACK, "2000")
							.commit();

			startNewLocalConnectionWithoutIntents("Local");
			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			final String token5 = "NUM00005";
			final String token45 = "NUM00045";

			// Seed deterministic content: a monotonically increasing list of numbers. We'll scroll to
			// older rows and attempt to copy them while new output continues to arrive.
			StringBuilder seed = new StringBuilder();
				// Keep this large enough that NUM00045/NUM00005 are always in scrollback (even on very
				// tall screens / small fonts) so we exercise "scrolled up while output streams".
					for (int i = 0; i < 700; i++) {
						seed.append("NUM").append(String.format(Locale.US, "%05d", i)).append("\r\n");
					}
				insertTerminalOutput(terminalView, "\r\n" + seed.toString());
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			BufferPosition token45Pos = waitForTokenPosition(terminalView, token45, 5000L);
			final int rows = getTerminalRows(terminalView);
			final int initialBase = Math.max(0, token45Pos.row - (rows / 2));
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					synchronized (terminalView.bridge.buffer) {
						terminalView.bridge.buffer.setWindowBase(initialBase);
					}
				}
			});
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			// Start continuous output in a background thread (closer to how Relay behaves in real
			// sessions). We keep adding lines while repeatedly selecting/copying old numbers.
			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);

			// Phase 1: keyboard visible.
			ensureSoftKeyboardVisibility(consoleActivity, true);
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				final AtomicBoolean stopVisible = new AtomicBoolean(false);
				Thread streamerVisible = startBackgroundNumberStreamer(terminalView, stopVisible, 250);
				for (int i = 0; i < 3; i++) {
						BufferPosition token5Pos = waitForTokenPosition(terminalView, token5, 5000L);
						BufferPosition token45PosNow = waitForTokenPosition(terminalView, token45, 5000L);

					clipboard.setText("");
					assertThat(selectTokenAndCopy(terminalView, clipboard, token45PosNow, ViewportAnchor.BOTTOM), equalTo(token45));
					clipboard.setText("");
					assertThat(selectTokenAndCopy(terminalView, clipboard, token5Pos, ViewportAnchor.TOP), equalTo(token5));

				SystemClock.sleep(200L);
			}
			stopVisible.set(true);
			streamerVisible.join(10_000L);

			// Phase 2: keyboard hidden.
			ensureSoftKeyboardVisibility(consoleActivity, false);
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				final AtomicBoolean stopHidden = new AtomicBoolean(false);
				Thread streamerHidden = startBackgroundNumberStreamer(terminalView, stopHidden, 250);
				for (int i = 0; i < 3; i++) {
						BufferPosition token5Pos = waitForTokenPosition(terminalView, token5, 5000L);
						BufferPosition token45PosNow = waitForTokenPosition(terminalView, token45, 5000L);

					clipboard.setText("");
					assertThat(selectTokenAndCopy(terminalView, clipboard, token45PosNow, ViewportAnchor.BOTTOM), equalTo(token45));
					clipboard.setText("");
					assertThat(selectTokenAndCopy(terminalView, clipboard, token5Pos, ViewportAnchor.TOP), equalTo(token5));

				SystemClock.sleep(200L);
			}
			stopHidden.set(true);
			streamerHidden.join(10_000L);
		} finally {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	@Test
	public void selectionDoesNotDriftWhileStartingLongPressAtBottomDuringStreamingOutput() throws InterruptedException {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
			// Large enough that windowBase changes are meaningful.
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
					.putString(PreferenceConstants.SCROLLBACK, "2000")
					.commit();

			startNewLocalConnectionWithoutIntents("Local");
			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);

			// Validate both IME states (visible/hidden) since this bug frequently reproduces with
			// keyboard toggles in real usage.
			boolean[] imeStates = new boolean[] { true, false };
			for (boolean wantKeyboardVisible : imeStates) {
				ensureSoftKeyboardVisibility(consoleActivity, wantKeyboardVisible);
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				final int rows = getTerminalRows(terminalView);

				// Fill enough to ensure we're actually auto-scrolling at the bottom.
				StringBuilder seed = new StringBuilder();
				for (int i = 0; i < rows * 3; i++) {
					seed.append("FILL").append(i).append("\r\n");
				}
				insertTerminalOutput(terminalView, "\r\n" + seed.toString());
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				final String token = wantKeyboardVisible ? "DRIFT_TOKEN_IME_ON" : "DRIFT_TOKEN_IME_OFF";
				insertTerminalOutput(terminalView, "\r\n" + token + "\r\nAFTER1\r\nAFTER2\r\nAFTER3\r\n");
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				final BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);

				final int[] baseBefore = new int[1];
				getInstrumentation().runOnMainSync(new Runnable() {
					@Override
					public void run() {
						synchronized (terminalView.bridge.buffer) {
							de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
							terminalView.bridge.buffer.setWindowBase(buffer.screenBase);
							baseBefore[0] = terminalView.bridge.buffer.getWindowBase();
						}
					}
				});
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				final int cols = getTerminalCols(terminalView);
				final int targetCol = Math.min(Math.max(0, tokenPos.col + 2), Math.max(0, cols - 1));
				final int screenRow = tokenPos.row - baseBefore[0];
				if (screenRow < 0 || screenRow >= rows) {
					throw new AssertionError("Token not in viewport at bottom: screenRow=" + screenRow + " rows=" + rows);
				}

				float x = targetCol * terminalView.bridge.charWidth + terminalView.bridge.charWidth / 2f;
				float y = screenRow * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;

				clipboard.setText("");

				final AtomicBoolean stop = new AtomicBoolean(false);
				Thread streamer = null;
				try {
					streamer = longPressTerminalAtWhileStreamingOutput(terminalView, x, y, stop, 600);

					int baseAfterLongPress = getWindowBase(terminalView);
					assertThat(
							"windowBase must not drift while starting selection at bottom under streaming output",
							baseAfterLongPress,
							equalTo(baseBefore[0]));

					getInstrumentation().runOnMainSync(new Runnable() {
						@Override
						public void run() {
							terminalView.copyCurrentSelectionToClipboard();
						}
					});

					String clip = clipboard.hasText() ? clipboard.getText().toString() : "";
					assertThat(clip, equalTo(token));
				} finally {
					stop.set(true);
					if (streamer != null) {
						streamer.join(10_000L);
					}
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
	public void selectionCopyRemainsCalibratedWhileStartingLongPressAtBottomWhenScrollbackIsSaturated() throws InterruptedException {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
			// Intentionally small so we saturate quickly and begin dropping lines while the user is
			// holding a finger down to start selection.
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
					.putString(PreferenceConstants.SCROLLBACK, "200")
					.commit();

			startNewLocalConnectionWithoutIntents("Local");
			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			// Use the hidden-keyboard layout (more rows => faster saturation for a fixed scrollback).
			ensureSoftKeyboardVisibility(consoleActivity, false);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);

			// Pre-saturate the scrollback so we're already in the "dropping lines" regime.
			StringBuilder seed = new StringBuilder();
			for (int i = 0; i < 800; i++) {
				seed.append("SAT_SEED").append(i).append("\r\n");
			}
			insertTerminalOutput(terminalView, "\r\n" + seed.toString());
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final String token = "SATURATE_TOKEN";
			insertTerminalOutput(terminalView, "\r\n" + token + "\r\nAFTER1\r\nAFTER2\r\n");
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);

			final int[] baseBefore = new int[1];
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					synchronized (terminalView.bridge.buffer) {
						de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
						terminalView.bridge.buffer.setWindowBase(buffer.screenBase);
						baseBefore[0] = terminalView.bridge.buffer.getWindowBase();
					}
				}
			});
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final int rows = getTerminalRows(terminalView);
			final int cols = getTerminalCols(terminalView);
			final int targetCol = Math.min(Math.max(0, tokenPos.col + 2), Math.max(0, cols - 1));
			final int screenRow = tokenPos.row - baseBefore[0];
			if (screenRow < 0 || screenRow >= rows) {
				throw new AssertionError("Token not in viewport at bottom: screenRow=" + screenRow + " rows=" + rows);
			}

			float x = targetCol * terminalView.bridge.charWidth + terminalView.bridge.charWidth / 2f;
			float y = screenRow * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;

			clipboard.setText("");

			final AtomicBoolean stop = new AtomicBoolean(false);
			Thread flooder = null;
			try {
				flooder = longPressTerminalAtWhileFloodingOutput(terminalView, x, y, stop, 2000);

				getInstrumentation().runOnMainSync(new Runnable() {
					@Override
					public void run() {
						terminalView.copyCurrentSelectionToClipboard();
					}
				});

				String clip = clipboard.hasText() ? clipboard.getText().toString() : "";
				assertThat(clip, equalTo(token));
			} finally {
				stop.set(true);
				if (flooder != null) {
					flooder.join(10_000L);
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
	public void selectionCopyRemainsCalibratedWhileStartingLongPressInScrollbackDuringDroppingOutput() throws InterruptedException {
		Context testContext = ApplicationProvider.getApplicationContext();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(testContext);
		boolean wasAlwaysVisible = settings.getBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false);
		String wasScrollback = settings.getString(PreferenceConstants.SCROLLBACK, "140");

		try {
			// Keep this small to force the buffer into the "drop lines from top" regime quickly.
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, false)
					.putString(PreferenceConstants.SCROLLBACK, "200")
					.commit();

			startNewLocalConnectionWithoutIntents("Local");
			ConsoleActivity consoleActivity = waitForConsoleActivity(10000L);
			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);

			ensureSoftKeyboardVisibility(consoleActivity, false);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final int rows = getTerminalRows(terminalView);

			// Saturate scrollback.
			StringBuilder seed = new StringBuilder();
			for (int i = 0; i < 800; i++) {
				seed.append("DROP_SEED").append(i).append("\r\n");
			}
			insertTerminalOutput(terminalView, "\r\n" + seed.toString());
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final String token = "SCROLLBACK_DRIFT_TOKEN";
			insertTerminalOutput(terminalView, "\r\n" + token + "\r\n");

			// Push enough output after the token so it ends up near the *top* of the active screen.
			StringBuilder after = new StringBuilder();
			for (int i = 0; i < Math.max(1, rows - 5); i++) {
				after.append("AFTER_TOKEN").append(i).append("\r\n");
			}
			insertTerminalOutput(terminalView, after.toString());
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);

			// Scroll back a little while output continues to arrive. In this regime, VDUBuffer adjusts
			// windowBase as lines are dropped to keep the user's viewport anchored to the same text.
			final int[] baseBefore = new int[1];
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					synchronized (terminalView.bridge.buffer) {
						de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
						int target = Math.max(0, buffer.screenBase - 10);
						terminalView.bridge.buffer.setWindowBase(target);
						baseBefore[0] = terminalView.bridge.buffer.getWindowBase();
					}
				}
			});
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final int cols = getTerminalCols(terminalView);
			final int targetCol = Math.min(Math.max(0, tokenPos.col + 2), Math.max(0, cols - 1));
			final int screenRow = tokenPos.row - baseBefore[0];
			if (screenRow < 0 || screenRow >= rows) {
				throw new AssertionError("Token not in viewport while scrolled back: screenRow=" + screenRow + " rows=" + rows);
			}

			float x = targetCol * terminalView.bridge.charWidth + terminalView.bridge.charWidth / 2f;
			float y = screenRow * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);
			clipboard.setText("");

			final AtomicBoolean stop = new AtomicBoolean(false);
			Thread flooder = null;
			try {
				flooder = longPressTerminalAtWhileFloodingOutput(terminalView, x, y, stop, 2000);

				getInstrumentation().runOnMainSync(new Runnable() {
					@Override
					public void run() {
						terminalView.copyCurrentSelectionToClipboard();
					}
				});

				String clip = clipboard.hasText() ? clipboard.getText().toString() : "";
				assertThat(clip, equalTo(token));
			} finally {
				stop.set(true);
				if (flooder != null) {
					flooder.join(10_000L);
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
	public void selectionCopyRemainsCalibratedWhileSelectionIsActiveDuringStreamingOutput() throws InterruptedException {
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

			ensureSoftKeyboardVisibility(consoleActivity, false);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);

			final int rows = getTerminalRows(terminalView);

			// Fill enough to ensure we're really at the bottom and auto-scrolling.
			StringBuilder seed = new StringBuilder();
			for (int i = 0; i < rows * 4; i++) {
				seed.append("FILL").append(i).append("\r\n");
			}
			insertTerminalOutput(terminalView, "\r\n" + seed.toString());
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final String token = "ACTIVE_SELECTION_TOKEN";
			insertTerminalOutput(terminalView, "\r\n" + token + "\r\nAFTER1\r\nAFTER2\r\nAFTER3\r\n");
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);

			final int[] baseBefore = new int[1];
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					synchronized (terminalView.bridge.buffer) {
						de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
						terminalView.bridge.buffer.setWindowBase(buffer.screenBase);
						baseBefore[0] = terminalView.bridge.buffer.getWindowBase();
					}
				}
			});
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final int cols = getTerminalCols(terminalView);
			final int targetCol = Math.min(Math.max(0, tokenPos.col + 2), Math.max(0, cols - 1));
			final int screenRow = tokenPos.row - baseBefore[0];
			if (screenRow < 0 || screenRow >= rows) {
				throw new AssertionError("Token not in viewport at bottom: screenRow=" + screenRow + " rows=" + rows);
			}

			float x = targetCol * terminalView.bridge.charWidth + terminalView.bridge.charWidth / 2f;
			float y = screenRow * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;

			clipboard.setText("");

			final AtomicBoolean stop = new AtomicBoolean(false);
			Thread streamer = null;
			try {
				streamer = longPressTerminalAtWhileStreamingOutput(terminalView, x, y, stop, 2000);

				// Keep selection active while output continues to stream in.
				SystemClock.sleep(1200L);

				getInstrumentation().runOnMainSync(new Runnable() {
					@Override
					public void run() {
						terminalView.copyCurrentSelectionToClipboard();
					}
				});

				String clip = clipboard.hasText() ? clipboard.getText().toString() : "";
				assertThat(clip, equalTo(token));
			} finally {
				stop.set(true);
				if (streamer != null) {
					streamer.join(10_000L);
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
	public void selectionCopyMatchesVisibleViewportWhenBufferAdvancesWithoutNewFrame() throws InterruptedException {
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

			ensureSoftKeyboardVisibility(consoleActivity, true);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final String token = "VISIBLE_BEFORE_STREAM";
			final int rows = getTerminalRows(terminalView);
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < rows + 10; i++) {
				out.append("PRE").append(i).append("\r\n");
			}
			out.append(token).append("\r\nAFTER\r\n");
			insertTerminalOutput(terminalView, "\r\n" + out.toString());
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);
			int drawnBase = waitForLastDrawnWindowBase(terminalView, 5000L);
			int drawnBaseAfter = getLastDrawnWindowBase(terminalView);
			assertThat("Expected lastDrawnWindowBase to be stable after settling", drawnBaseAfter, equalTo(drawnBase));

			final int cols = getTerminalCols(terminalView);
			final int targetCol = Math.min(Math.max(0, tokenPos.col + 2), Math.max(0, cols - 1));
			final int screenRowAtDraw = tokenPos.row - drawnBase;
			if (screenRowAtDraw < 0 || screenRowAtDraw >= rows) {
				throw new AssertionError("Token not in viewport at drawn base: screenRow=" + screenRowAtDraw + " rows=" + rows);
			}

			float x = targetCol * terminalView.bridge.charWidth + terminalView.bridge.charWidth / 2f;
			float y = screenRowAtDraw * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);
			clipboard.setText("");

			final AtomicBoolean stop = new AtomicBoolean(false);
			final Thread[] flooder = new Thread[1];
			final long[] downTime = new long[1];
			final View[] rootView = new View[1];
			final float[] xRoot = new float[1];
			final float[] yRoot = new float[1];

			try {
				// Simulate the real-world condition where output advances on a background thread while
				// frames are not being rendered (UI thread busy). The screen still shows the last-drawn
				// bitmap (drawnBase), but the buffer has already advanced.
				getInstrumentation().runOnMainSync(new Runnable() {
					@Override
					public void run() {
						Activity activity = (Activity) terminalView.getContext();
						View root = activity.getWindow().getDecorView();

						int[] rootLoc = new int[2];
						int[] termLoc = new int[2];
						root.getLocationOnScreen(rootLoc);
						terminalView.getLocationOnScreen(termLoc);

						float xScreen = termLoc[0] + x;
						float yScreen = termLoc[1] + y;

						rootView[0] = root;
						xRoot[0] = xScreen - rootLoc[0];
						yRoot[0] = yScreen - rootLoc[1];

						flooder[0] = startBackgroundNumberFlooder(terminalView, stop, 2000);

						final long start = SystemClock.uptimeMillis();
						int base = drawnBase;
						while (SystemClock.uptimeMillis() - start < 2000L) {
							synchronized (terminalView.bridge.buffer) {
								base = terminalView.bridge.buffer.getWindowBase();
							}
							if (base != drawnBase) {
								break;
							}
							SystemClock.sleep(10L);
						}

						assertThat("windowBase should advance as output arrives", base, not(equalTo(drawnBase)));
						assertThat("lastDrawnWindowBase should remain at the visible frame", terminalView.bridge.getLastDrawnWindowBase(), equalTo(drawnBase));

						downTime[0] = SystemClock.uptimeMillis();
						MotionEvent down = MotionEvent.obtain(downTime[0], downTime[0], MotionEvent.ACTION_DOWN, xRoot[0], yRoot[0], 0);
						down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
						root.dispatchTouchEvent(down);
						down.recycle();
					}
				});

				// Stop output so selection begins after "streaming" has ended, matching the user-reported scenario.
				stop.set(true);
				if (flooder[0] != null) {
					flooder[0].join(10_000L);
				}

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
			} finally {
				stop.set(true);
				if (flooder[0] != null) {
					flooder[0].join(10_000L);
				}
			}

			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					terminalView.copyCurrentSelectionToClipboard();
				}
			});

			String clip = clipboard.hasText() ? clipboard.getText().toString() : "";
			assertThat(clip, equalTo(token));
		} finally {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	@Test
	public void consoleKeepsConnectedHostsAfterDisplayResizeAndKeyboardToggle() throws Exception {
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

			ensureSoftKeyboardVisibility(consoleActivity, true);
			assertConsoleHasConnectedHosts(consoleActivity, 5000L);

			// Simulate a foldable "unfold" / window-size-class change by changing the physical display
			// size. Some devices restart or re-layout aggressively under IME hide/show after such a
			// resize; we should never show "No hosts currently connected" while sessions exist.
			execShellCommand("wm size 1200x2000");
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(1500L));

			ConsoleActivity afterResize = waitForConsoleActivity(10000L);
			assertConsoleHasConnectedHosts(afterResize, 5000L);

			// Reported repro: after resize/unfold, hiding the keyboard can cause the console to show
			// the empty-hosts message even though sessions are still connected.
			ensureSoftKeyboardVisibility(afterResize, false);
			assertConsoleHasConnectedHosts(afterResize, 5000L);

			ensureSoftKeyboardVisibility(afterResize, true);
			assertConsoleHasConnectedHosts(afterResize, 5000L);
		} finally {
			try {
				execShellCommand("wm size reset");
			} catch (Throwable ignored) {
			}
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	@Test
	public void consoleStillRendersAfterDisplayResizeAndKeyboardToggle() throws Exception {
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

			ensureSoftKeyboardVisibility(consoleActivity, true);

			TerminalView terminalView = consoleActivity.findViewById(R.id.terminal_view);
			final String token = "RENDERTOKEN";
			insertTerminalOutput(terminalView, "\r\n" + token + "\r\n");
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);
			scrollViewportToRow(terminalView, tokenPos.row);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));
			assertTokenIsRenderedInBitmap(terminalView, tokenPos, token);

			// Simulate foldable resize/unfold.
			execShellCommand("wm size 1200x2000");
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(1500L));

			ConsoleActivity afterResize = waitForConsoleActivity(10000L);
			TerminalView afterResizeTerminalView = afterResize.adapter.getCurrentTerminalView();
			if (afterResizeTerminalView == null) {
				afterResizeTerminalView = afterResize.findViewById(R.id.terminal_view);
			}

			BufferPosition tokenPosAfterResize = waitForTokenPosition(afterResizeTerminalView, token, 5000L);
			scrollViewportToRow(afterResizeTerminalView, tokenPosAfterResize.row);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));
			assertTokenIsRenderedInBitmap(afterResizeTerminalView, tokenPosAfterResize, token);

			// Reported repro: after resize/unfold, hiding the keyboard can blank the console.
			ensureSoftKeyboardVisibility(afterResize, false);
			// Give the UI extra time to settle after a physical display resize + IME transition,
			// which can be slow/flaky on some devices.
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(2000L));

			TerminalView afterHideTerminalView = afterResize.adapter.getCurrentTerminalView();
			if (afterHideTerminalView == null) {
				afterHideTerminalView = afterResizeTerminalView;
			}

			BufferPosition tokenPosAfterHide = waitForTokenPosition(afterHideTerminalView, token, 5000L);
			scrollViewportToRow(afterHideTerminalView, tokenPosAfterHide.row);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));
			assertTokenIsRenderedInBitmap(afterHideTerminalView, tokenPosAfterHide, token);
		} finally {
			try {
				execShellCommand("wm size reset");
			} catch (Throwable ignored) {
			}
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	@Test
	public void selectionCopyRemainsCalibratedIfTextViewAttemptsToScrollDuringLongPress() {
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

			ensureSoftKeyboardVisibility(consoleActivity, true);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final String token = "INTERNALSCROLLTOKEN";
			final int rows = getTerminalRows(terminalView);
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < rows + 40; i++) {
				out.append("FILL").append(i).append("\r\n");
			}
			out.append(token).append("\r\n");
			for (int i = 0; i < rows + 10; i++) {
				out.append("AFTER").append(i).append("\r\n");
			}
			insertTerminalOutput(terminalView, "\r\n" + out.toString());
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			BufferPosition tokenPos = waitForTokenPosition(terminalView, token, 5000L);

			// Put the token near the middle of the viewport so an accidental overlay scroll will
			// definitely shift the touched row to different content.
			final int targetBase = Math.max(0, tokenPos.row - (rows / 2));
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					synchronized (terminalView.bridge.buffer) {
						terminalView.bridge.buffer.setWindowBase(targetBase);
					}
					TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
					overlay.refreshTextFromBuffer();
				}
			});
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			final int cols = getTerminalCols(terminalView);
			final int targetCol = Math.min(Math.max(0, tokenPos.col + 2), Math.max(0, cols - 1));
			final int baseNow = getWindowBase(terminalView);
			final int screenRow = tokenPos.row - baseNow;
			if (screenRow < 0 || screenRow >= rows) {
				throw new AssertionError("Token not in viewport: screenRow=" + screenRow + " rows=" + rows);
			}

			float x = targetCol * terminalView.bridge.charWidth + terminalView.bridge.charWidth / 2f;
			float y = screenRow * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;

			ClipboardManager clipboard = (ClipboardManager) testContext.getSystemService(Context.CLIPBOARD_SERVICE);
			clipboard.setText("");

			// Simulate TextView-internal scrolling during long-press recognition. The overlay must not
			// scroll independently from the terminal bitmap, otherwise hit-testing selects the wrong
			// row/word.
			longPressTerminalAtWithOverlayScrollAttempt(terminalView, x, y, terminalView.bridge.charHeight * 3);

			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					terminalView.copyCurrentSelectionToClipboard();
				}
			});

			String clip = clipboard.hasText() ? clipboard.getText().toString() : "";
			assertThat(clip, equalTo(token));
		} finally {
			settings.edit()
					.putBoolean(PreferenceConstants.KEY_ALWAYS_VISIBLE, wasAlwaysVisible)
					.putString(PreferenceConstants.SCROLLBACK, wasScrollback)
					.commit();
		}
	}

	private enum ViewportAnchor { TOP, MIDDLE, BOTTOM }

	private static Thread startBackgroundNumberStreamer(final TerminalView terminalView, final AtomicBoolean stop, final int count) {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				for (int i = 0; i < count && !stop.get(); i++) {
					String token = "STREAM" + String.format(Locale.US, "%05d", i);
					String line = token + "\r\n";
					appendTerminalOutputFromBackgroundThread(terminalView, line);
					SystemClock.sleep(STREAMING_OUTPUT_DELAY_MILLIS);
				}
			}
		});
		t.setName("TestTerminalStreamer");
		t.setDaemon(true);
		t.start();
		return t;
	}

	private static Thread startBackgroundNumberFlooder(final TerminalView terminalView, final AtomicBoolean stop, final int totalLines) {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				final int chunkSize = 50;
				int sent = 0;
				int seq = 0;
				while (sent < totalLines && !stop.get()) {
					int linesThisChunk = Math.min(chunkSize, totalLines - sent);
					StringBuilder out = new StringBuilder(linesThisChunk * 16);
					for (int i = 0; i < linesThisChunk && !stop.get(); i++) {
						out.append("FLOOD").append(String.format(Locale.US, "%05d", seq++)).append("\r\n");
					}
					appendTerminalOutputFromBackgroundThread(terminalView, out.toString());
					sent += linesThisChunk;
					SystemClock.sleep(1L);
				}
			}
		});
		t.setName("TestTerminalFlooder");
		t.setDaemon(true);
		t.start();
		return t;
	}

	private static void appendTerminalOutputFromBackgroundThread(final TerminalView terminalView, final String output) {
		// Intentionally *not* marshaled to the main thread so we exercise the same thread model as
		// Relay (network thread mutating the vt320 buffer, UI thread reading for selection).
		synchronized (terminalView.bridge.buffer) {
			((de.mud.terminal.vt320) terminalView.bridge.buffer).putString(output);
		}
		char[] raw = output.toCharArray();
		terminalView.bridge.propagateConsoleText(raw, raw.length);
		terminalView.bridge.redraw();
	}

	private static Thread longPressTerminalAtWhileFloodingOutput(final TerminalView terminalView, final float xPx, final float yPx,
			final AtomicBoolean stop, final int totalLines) {
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

		final Thread flooder = startBackgroundNumberFlooder(terminalView, stop, totalLines);

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

		return flooder;
	}

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
			final int[] windowBaseAfterSet = new int[1];
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					synchronized (terminalView.bridge.buffer) {
						terminalView.bridge.buffer.setWindowBase(base);
						windowBaseAfterSet[0] = terminalView.bridge.buffer.getWindowBase();
					}
				}
				});
				onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));

				// Selection is based on what is visible on screen (last-drawn bitmap), not necessarily
				// the newest buffer state under heavy output.
				int windowBase = getWindowBase(terminalView);
				final int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
				final int visibleWindowBase = drawnWindowBase >= 0 ? drawnWindowBase : windowBase;
				final int[] screenBaseAndSizes = new int[3];
				getInstrumentation().runOnMainSync(new Runnable() {
					@Override
					public void run() {
					synchronized (terminalView.bridge.buffer) {
						de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
						screenBaseAndSizes[0] = buffer.screenBase;
						screenBaseAndSizes[1] = buffer.getBufferSize();
						screenBaseAndSizes[2] = buffer.getMaxBufferSize();
					}
				}
			});
			final int screenBase = screenBaseAndSizes[0];
			final int bufferSize = screenBaseAndSizes[1];
				final int maxBufferSize = screenBaseAndSizes[2];
				final int cols = getTerminalCols(terminalView);
				final int targetCol = Math.min(Math.max(0, tokenPos.col + 2), Math.max(0, cols - 1));
				final int screenRow = tokenPos.row - visibleWindowBase;

				float x = targetCol * terminalView.bridge.charWidth + terminalView.bridge.charWidth / 2f;
				float y = screenRow * terminalView.bridge.charHeight + terminalView.bridge.charHeight / 2f;

			final char expectedChar = getBufferCharAt(terminalView, tokenPos.row, targetCol);
			final char actualCharAtHit = getOverlayCharAtPosition(terminalView, x, y);
			final boolean isHitInsideViewport = screenRow >= 0 && screenRow < rows;

			longPressTerminalAt(terminalView, x, y);
			onView(withId(R.id.console_flip)).perform(loopMainThreadFor(TERMINAL_UI_SETTLE_DELAY_MILLIS));
			assertOverlayScrollAlignedWithWindowBase(terminalView);

			final int[] selStartEnd = new int[2];
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
					selStartEnd[0] = overlay.getSelectionStart();
					selStartEnd[1] = overlay.getSelectionEnd();
				}
			});

			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					terminalView.copyCurrentSelectionToClipboard();
			}
			});

			String clip = clipboard.hasText() ? clipboard.getText().toString() : "";
			if (clip.isEmpty()) {
				throw new AssertionError(
						"Clipboard empty after long-press selection.\n"
								+ "  requestedWindowBase=" + base + " windowBaseAfterSet=" + windowBaseAfterSet[0] + " actualWindowBase=" + windowBase + "\n"
								+ "  screenBase=" + screenBase + " bufferSize=" + bufferSize + " maxBufferSize=" + maxBufferSize + "\n"
								+ "  tokenRow=" + tokenPos.row + " tokenCol=" + tokenPos.col + " targetCol=" + targetCol + "\n"
								+ "  screenRow=" + screenRow + " rows=" + rows + " inViewport=" + isHitInsideViewport + "\n"
								+ "  expectedCharAtBuffer='" + expectedChar + "' actualCharAtHit='" + actualCharAtHit + "'\n"
								+ "  selectionStart=" + selStartEnd[0] + " selectionEnd=" + selStartEnd[1]);
			}
			clipboard.setText("");
			return clip;
		}

	private static void assertOverlayAlignedWithTerminalGrid(final TerminalView terminalView) {
		waitForOverlayFontAndMetrics(terminalView, 5000L);

		final int[] overlayLineHeight = new int[1];
		final int[] bridgeCharHeight = new int[1];
		final int[] overlayBaseline = new int[1];
		final int[] expectedBaseline = new int[1];

		final long start = SystemClock.uptimeMillis();
		while (SystemClock.uptimeMillis() - start < 5000L) {
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
					overlay.refreshTextFromBuffer();
					overlayLineHeight[0] = overlay.getLineHeight();
					bridgeCharHeight[0] = terminalView.bridge.charHeight;

					android.text.Layout layout = overlay.getLayout();
					if (layout != null && layout.getLineCount() > 0) {
						int windowBase = terminalView.bridge.buffer.getWindowBase();
						int lineIndex = Math.min(Math.max(0, windowBase), layout.getLineCount() - 1);
						overlayBaseline[0] = overlay.getExtendedPaddingTop()
								+ layout.getLineBaseline(lineIndex)
								- overlay.getScrollY();
					} else {
						overlayBaseline[0] = Integer.MIN_VALUE;
					}
					expectedBaseline[0] = -terminalView.bridge.getCharTop();
				}
			});

			if (overlayBaseline[0] != Integer.MIN_VALUE) {
				break;
			}

			SystemClock.sleep(50L);
		}

		if (overlayBaseline[0] == Integer.MIN_VALUE) {
			throw new AssertionError("Timed out waiting for overlay layout");
		}

		assertThat("Overlay line height must match terminal char height", overlayLineHeight[0], equalTo(bridgeCharHeight[0]));
		assertThat(
				"Overlay baseline must match terminal baseline",
				Math.abs(overlayBaseline[0] - expectedBaseline[0]),
				lessThanOrEqualTo(1));
	}

	private static void assertConsoleHasConnectedHosts(final ConsoleActivity consoleActivity, long timeoutMillis) {
		final long start = SystemClock.uptimeMillis();
		Throwable lastError = null;

		while (SystemClock.uptimeMillis() - start < timeoutMillis) {
			try {
				final int[] count = new int[1];
				final int[] emptyVisibility = new int[1];

				getInstrumentation().runOnMainSync(new Runnable() {
					@Override
					public void run() {
						count[0] = consoleActivity.adapter != null ? consoleActivity.adapter.getCount() : 0;
						View empty = consoleActivity.findViewById(android.R.id.empty);
						emptyVisibility[0] = (empty != null) ? empty.getVisibility() : View.GONE;
					}
				});

				assertThat("Expected at least one connected host", count[0], greaterThan(0));
				assertThat("Empty view must not be visible when hosts are connected", emptyVisibility[0], not(equalTo(View.VISIBLE)));

				// Also ensure the current terminal view is present in the view hierarchy.
				waitForTerminalView(consoleActivity, 2000L);
				return;
			} catch (Throwable t) {
				lastError = t;
				SystemClock.sleep(100L);
			}
		}

		AssertionError error = new AssertionError("Timed out waiting for connected hosts to be visible in ConsoleActivity");
		if (lastError != null) {
			error.initCause(lastError);
		}
		throw error;
	}

	private static TerminalView waitForTerminalView(final ConsoleActivity consoleActivity, long timeoutMillis) {
		final long start = SystemClock.uptimeMillis();
		while (SystemClock.uptimeMillis() - start < timeoutMillis) {
			final TerminalView[] view = new TerminalView[1];
			getInstrumentation().runOnMainSync(new Runnable() {
				@Override
				public void run() {
					View candidate = consoleActivity.findViewById(R.id.terminal_view);
					if (candidate instanceof TerminalView) {
						view[0] = (TerminalView) candidate;
					}
				}
			});
			if (view[0] != null) {
				return view[0];
			}
			SystemClock.sleep(50L);
		}
		throw new AssertionError("Timed out waiting for TerminalView to be present");
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
				synchronized (terminalView.bridge.buffer) {
					terminalView.bridge.buffer.setWindowBase(base);
				}
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

	private static int getLastDrawnWindowBase(final TerminalView terminalView) {
		final int[] base = new int[1];
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				base[0] = terminalView.bridge.getLastDrawnWindowBase();
			}
		});
		return base[0];
	}

	private static int waitForLastDrawnWindowBase(final TerminalView terminalView, long timeoutMillis) {
		final long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timeoutMillis) {
			int base = getLastDrawnWindowBase(terminalView);
			if (base >= 0) {
				return base;
			}
			SystemClock.sleep(50L);
		}
		throw new AssertionError("Timed out waiting for lastDrawnWindowBase to be set");
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

	private static void longPressTerminalAtWithOverlayScrollAttempt(final TerminalView terminalView, final float xPx, final float yPx,
			final int scrollDyPx) {
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

				TerminalTextViewOverlay overlay = (TerminalTextViewOverlay) terminalView.getChildAt(0);
				overlay.scrollTo(0, overlay.getScrollY() + scrollDyPx);
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

	private static Thread longPressTerminalAtWhileStreamingOutput(final TerminalView terminalView, final float xPx, final float yPx,
			final AtomicBoolean stop, final int streamCount) {
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

		// Stream output while the long-press timeout elapses to reproduce the "content moves under
		// your finger" drift that causes miscalibrated selection.
		final Thread streamer = startBackgroundNumberStreamer(terminalView, stop, streamCount);

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

		return streamer;
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
		// Use a unique nickname for each test run so ConsoleActivity doesn't reuse an existing
		// TerminalBridge from a previous test (TerminalManager is a long-lived service).
		final String uniqueName = name + "-" + SystemClock.uptimeMillis();

		onView(withId(R.id.add_host_button)).perform(click());
		onView(withId(R.id.protocol_text)).perform(click());
		onView(withText("local")).perform(click());
		onView(withId(R.id.quickconnect_field)).perform(typeText(uniqueName));
		onView(withId(R.id.save)).perform(click());

		onView(withId(R.id.list)).perform(actionOnHolderItem(withHostNickname(uniqueName), click()));
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
				synchronized (terminalView.bridge.buffer) {
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

	private static void scrollViewportToRow(final TerminalView terminalView, final int row) {
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				synchronized (terminalView.bridge.buffer) {
					de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
					int rows = Math.max(1, buffer.height);
					int targetBase = row - (rows / 2);
					buffer.setWindowBase(targetBase);
				}
				terminalView.bridge.redraw();
			}
		});
	}

	private static void assertTokenIsRenderedInBitmap(final TerminalView terminalView, final BufferPosition tokenPos, final String token) {
		getInstrumentation().runOnMainSync(new Runnable() {
			@Override
			public void run() {
				final int viewWidth = terminalView.getWidth();
				final int viewHeight = terminalView.getHeight();
				if (viewWidth <= 0 || viewHeight <= 0) {
					throw new AssertionError("TerminalView has no size (console not visible); view=" + viewWidth + "x" + viewHeight);
				}

				// Render what the user actually sees, not just the bridge backing bitmap.
				Bitmap bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888);
				android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
				terminalView.draw(canvas);
				final int charWidth = terminalView.bridge.charWidth;
				final int charHeight = terminalView.bridge.charHeight;

				final int windowBase;
				final int screenBase;
				final int rows;
				final int cols;
				final char tokenChar;
				final long tokenAttr;
				synchronized (terminalView.bridge.buffer) {
					de.mud.terminal.VDUBuffer buffer = terminalView.bridge.getVDUBuffer();
					windowBase = buffer.getWindowBase();
					screenBase = buffer.screenBase;
					rows = buffer.height;
					cols = buffer.width;
					tokenChar = buffer.charArray[tokenPos.row][tokenPos.col];
					tokenAttr = buffer.charAttributes[tokenPos.row][tokenPos.col];
				}

				final int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
				final int baseForBitmap = (drawnWindowBase >= 0) ? drawnWindowBase : windowBase;

				final int visibleRow = tokenPos.row - baseForBitmap;
				if (visibleRow < 0 || visibleRow >= rows) {
					throw new AssertionError("Token row not visible in viewport; tokenRow=" + tokenPos.row
							+ " windowBase=" + windowBase
							+ " drawnWindowBase=" + drawnWindowBase
							+ " screenBase=" + screenBase
							+ " rows=" + rows
							+ " cols=" + cols
							+ " view=" + viewWidth + "x" + viewHeight
							+ " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
				}

				final int left = Math.max(0, tokenPos.col * charWidth);
				final int top = Math.max(0, visibleRow * charHeight);
				final int right = Math.min(bitmap.getWidth(), left + (token.length() * charWidth));
				final int bottom = Math.min(bitmap.getHeight(), top + charHeight);

				if (right <= left || bottom <= top) {
					throw new AssertionError("Token bitmap region out of bounds; left=" + left + " top=" + top
							+ " right=" + right + " bottom=" + bottom
							+ " char=" + charWidth + "x" + charHeight
							+ " tokenCol=" + tokenPos.col
							+ " visibleRow=" + visibleRow
							+ " windowBase=" + windowBase
							+ " drawnWindowBase=" + drawnWindowBase
							+ " view=" + viewWidth + "x" + viewHeight
							+ " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
				}

				boolean foundNonBlack = false;
				final int step = 1;
				for (int y = top; y < bottom && !foundNonBlack; y += step) {
					for (int x = left; x < right; x += step) {
						if (bitmap.getPixel(x, y) != 0xff000000) {
							foundNonBlack = true;
							break;
						}
					}
				}

				if (!foundNonBlack) {
					boolean anyNonBlack = false;
					final int coarseStep = 20;
					for (int y = 0; y < bitmap.getHeight() && !anyNonBlack; y += coarseStep) {
						for (int x = 0; x < bitmap.getWidth(); x += coarseStep) {
							if (bitmap.getPixel(x, y) != 0xff000000) {
								anyNonBlack = true;
								break;
							}
						}
					}

					throw new AssertionError("Expected token region to contain non-black pixels (text visible); "
							+ "anyNonBlack=" + anyNonBlack
							+ " tokenRow=" + tokenPos.row
							+ " tokenCol=" + tokenPos.col
							+ " visibleRow=" + visibleRow
							+ " pagerScrollX=" + terminalView.viewPager.getScrollX()
							+ " pagerWidth=" + terminalView.viewPager.getWidth()
							+ " pagerCurrentItem=" + terminalView.viewPager.getCurrentItem()
							+ " pagerChildCount=" + terminalView.viewPager.getChildCount()
							+ " defaultFg=" + terminalView.bridge.defaultFg
							+ " defaultBg=" + terminalView.bridge.defaultBg
							+ " fgColor=0x" + Integer.toHexString(terminalView.bridge.color[terminalView.bridge.defaultFg])
							+ " bgColor=0x" + Integer.toHexString(terminalView.bridge.color[terminalView.bridge.defaultBg])
							+ " tokenChar='" + tokenChar + "'"
							+ " tokenAttr=0x" + Long.toHexString(tokenAttr)
							+ " windowBase=" + windowBase
							+ " drawnWindowBase=" + drawnWindowBase
							+ " screenBase=" + screenBase
							+ " rows=" + rows
							+ " cols=" + cols
							+ " view=" + viewWidth + "x" + viewHeight
							+ " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
				}
			}
		});
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

	private static void wakeAndUnlockDeviceIfPossible() {
		// Some devices/profiles can start in a locked state even after sys.boot_completed=1, which
		// causes Espresso to report "No activities in stage RESUMED" when attempting to interact
		// with the app UI.
		try {
			execShellCommand("input keyevent 224"); // KEYCODE_WAKEUP
		} catch (Throwable ignored) {
		}
		try {
			execShellCommand("wm dismiss-keyguard");
		} catch (Throwable ignored) {
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
