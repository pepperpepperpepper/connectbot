/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2010 Kenny Root, Jeffrey Sharkey
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
package org.connectbot.service;

import java.io.IOException;

import org.connectbot.bean.SelectionArea;
import org.connectbot.util.PreferenceConstants;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import de.mud.terminal.VDUBuffer;
import de.mud.terminal.vt320;

/**
 * @author kenny
 *
 */
@SuppressWarnings("deprecation") // for ClipboardManager
public class TerminalKeyListener implements OnKeyListener, OnSharedPreferenceChangeListener {
	private static final String TAG = "CB.OnKeyListener";

	private final static int HC_META_SHIFT_MASK = KeyEvent.META_SHIFT_ON
			| KeyEvent.META_SHIFT_LEFT_ON
			| KeyEvent.META_SHIFT_RIGHT_ON;

	// Constants for our private tracking of modifier state
	public final static int OUR_CTRL_ON = 0x01;
	public final static int OUR_CTRL_LOCK = 0x02;
	public final static int OUR_ALT_ON = 0x04;
	public final static int OUR_ALT_LOCK = 0x08;
	public final static int OUR_SHIFT_ON = 0x10;
	public final static int OUR_SHIFT_LOCK = 0x20;
	private final static int OUR_SLASH = 0x40;
	private final static int OUR_TAB = 0x80;

	// All the transient key codes
	private final static int OUR_TRANSIENT = OUR_CTRL_ON | OUR_ALT_ON
			| OUR_SHIFT_ON | OUR_SLASH | OUR_TAB;

	// The bit mask of momentary and lock states for each
	private final static int OUR_CTRL_MASK = OUR_CTRL_ON | OUR_CTRL_LOCK;
	private final static int OUR_ALT_MASK = OUR_ALT_ON | OUR_ALT_LOCK;
	private final static int OUR_SHIFT_MASK = OUR_SHIFT_ON | OUR_SHIFT_LOCK;

	// backport constants from api level 11
	private final static int KEYCODE_ESCAPE = 111;
	private final static int KEYCODE_CTRL_LEFT = 113;
	private final static int KEYCODE_CTRL_RIGHT = 114;
	private final static int KEYCODE_INSERT = 124;
	private final static int KEYCODE_FORWARD_DEL = 112;
	private final static int KEYCODE_MOVE_HOME = 122;
	private final static int KEYCODE_MOVE_END = 123;
	private final static int KEYCODE_PAGE_DOWN = 93;
	private final static int KEYCODE_PAGE_UP = 92;
	private final static int HC_META_CTRL_ON = 0x1000;
	private final static int HC_META_CTRL_LEFT_ON = 0x2000;
	private final static int HC_META_CTRL_RIGHT_ON = 0x4000;
	private final static int HC_META_CTRL_MASK = HC_META_CTRL_ON | HC_META_CTRL_RIGHT_ON
			| HC_META_CTRL_LEFT_ON;
	private final static int HC_META_ALT_MASK = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
			| KeyEvent.META_ALT_RIGHT_ON;

	private final TerminalManager manager;
	private final TerminalBridge bridge;
	private final VDUBuffer buffer;

	private String keymode = null;
	private final boolean deviceHasHardKeyboard;
	private boolean shiftedNumbersAreFKeysOnHardKeyboard;
	private boolean controlNumbersAreFKeysOnSoftKeyboard;
	private boolean volumeKeysChangeFontSize;
	private int stickyMetas;

	private int ourMetaState = 0;

	private int mDeadKey = 0;

	// Hardware modifier tracking: some keyboards do not include ctrl meta-state on
	// non-printable keys (like DPAD arrows), but they do send separate ctrl key events.
	// Track ctrl down/up so Ctrl+Arrow can still emit xterm-modified key sequences.
	private boolean hardwareCtrlDown = false;

	// Some environments deliver KEYCODE_CTRL events to the Activity but not to our focused View
	// (or deliver them inconsistently). Track a global ctrl-down state that can be updated from
	// ConsoleActivity.dispatchKeyEvent() as a best-effort fallback for Ctrl+Arrow and similar keys.
	private static volatile boolean globalHardwareCtrlDown = false;
	private static volatile long globalHardwareCtrlLastUpdateUptimeMillis = -1L;
	private static final long GLOBAL_HARDWARE_CTRL_STUCK_TIMEOUT_MILLIS = 2 * 60_000L;

	// Soft keyboard Ctrl is often delivered as a "one-shot" press (down+up) and some IMEs omit
	// ctrl meta-state on non-printable keys (like DPAD arrows). Track a global one-shot Ctrl
	// state so Ctrl+Arrow can still emit xterm-modified sequences even after Ctrl has been released.
	private static volatile boolean globalCtrlOneShotActive = false;
	private static volatile long globalCtrlOneShotLastUpdateUptimeMillis = -1L;
	private static final long GLOBAL_CTRL_ONE_SHOT_TIMEOUT_MILLIS = 10_000L;

	private static boolean isLikelyVirtualOrSoftKeyboardKeyEvent(KeyEvent event) {
		if ((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) {
			return true;
		}
		if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
			return true;
		}

		final int deviceId = event.getDeviceId();
		if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) {
			return true;
		}

		final InputDevice device = InputDevice.getDevice(deviceId);
		if (device == null) {
			return true;
		}
		return device.isVirtual();
	}

	public static void updateGlobalHardwareCtrlDownFromKeyEvent(KeyEvent event) {
		globalHardwareCtrlDown = (event.getAction() == KeyEvent.ACTION_DOWN);
		final long now = SystemClock.uptimeMillis();
		globalHardwareCtrlLastUpdateUptimeMillis = now;

		if (event.getAction() == KeyEvent.ACTION_DOWN
				&& event.getRepeatCount() == 0
				&& isLikelyVirtualOrSoftKeyboardKeyEvent(event)) {
			globalCtrlOneShotActive = true;
			globalCtrlOneShotLastUpdateUptimeMillis = now;
		}
	}

	public static void resetGlobalHardwareCtrlDown() {
		globalHardwareCtrlDown = false;
		globalHardwareCtrlLastUpdateUptimeMillis = -1L;
		globalCtrlOneShotActive = false;
		globalCtrlOneShotLastUpdateUptimeMillis = -1L;
	}

	private static boolean isGlobalHardwareCtrlDownActive() {
		if (!globalHardwareCtrlDown) {
			return false;
		}
		final long lastUpdate = globalHardwareCtrlLastUpdateUptimeMillis;
		if (lastUpdate < 0L) {
			return false;
		}
		if (SystemClock.uptimeMillis() - lastUpdate > GLOBAL_HARDWARE_CTRL_STUCK_TIMEOUT_MILLIS) {
			globalHardwareCtrlDown = false;
			return false;
		}
		return true;
	}

	private static boolean consumeGlobalCtrlOneShotIfActive() {
		if (!globalCtrlOneShotActive) {
			return false;
		}
		final long lastUpdate = globalCtrlOneShotLastUpdateUptimeMillis;
		if (lastUpdate < 0L) {
			globalCtrlOneShotActive = false;
			return false;
		}
		if (SystemClock.uptimeMillis() - lastUpdate > GLOBAL_CTRL_ONE_SHOT_TIMEOUT_MILLIS) {
			globalCtrlOneShotActive = false;
			return false;
		}
		globalCtrlOneShotActive = false;
		return true;
	}

	private static boolean consumeGlobalCtrlOneShotIfActiveForKeyEvent(int keyCode, KeyEvent event) {
		if (keyCode == KEYCODE_CTRL_LEFT || keyCode == KEYCODE_CTRL_RIGHT) {
			return false;
		}
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return false;
		}
		if (event.getRepeatCount() != 0) {
			return false;
		}
		return consumeGlobalCtrlOneShotIfActive();
	}

	// TODO add support for the new API.
	private ClipboardManager clipboard = null;

	private boolean selectingForCopy = false;
	private final SelectionArea selectionArea;

	private String encoding;

	private final SharedPreferences prefs;

	private static boolean isCtrlPressed(int metaState) {
		return (metaState & HC_META_CTRL_MASK) != 0;
	}

	private static boolean isAltPressed(int metaState) {
		return (metaState & HC_META_ALT_MASK) != 0;
	}

	private static boolean isShiftPressed(int metaState) {
		return (metaState & HC_META_SHIFT_MASK) != 0;
	}

	private static int getXtermModifierParam(boolean shift, boolean alt, boolean ctrl) {
		return 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
	}

	private void writeXtermCsi(String sequence) throws IOException {
		bridge.transport.write(sequence.getBytes(encoding != null ? encoding : "UTF-8"));
	}

	private void writeXtermModifiedCsi(boolean shift, boolean alt, boolean ctrl, char finalChar)
			throws IOException {
		final int modifierParam = getXtermModifierParam(shift, alt, ctrl);
		writeXtermCsi(String.format("\u001b[1;%d%c", modifierParam, finalChar));
	}

	private void writeXtermModifiedCsiTilde(boolean shift, boolean alt, boolean ctrl, int code)
			throws IOException {
		final int modifierParam = getXtermModifierParam(shift, alt, ctrl);
		writeXtermCsi(String.format("\u001b[%d;%d~", code, modifierParam));
	}

	public TerminalKeyListener(TerminalManager manager,
			TerminalBridge bridge,
			VDUBuffer buffer,
			String encoding) {
		this.manager = manager;
		this.bridge = bridge;
		this.buffer = buffer;
		this.encoding = encoding;

		selectionArea = new SelectionArea();

		prefs = PreferenceManager.getDefaultSharedPreferences(manager);
		prefs.registerOnSharedPreferenceChangeListener(this);

		deviceHasHardKeyboard = (manager.res.getConfiguration().keyboard
				== Configuration.KEYBOARD_QWERTY);

		updatePrefs();
	}

	/**
	 * Handle onKey() events coming down from a {@link org.connectbot.TerminalView} above us.
	 * Modify the keys to make more sense to a host then pass it to the transport.
	 */
	@Override
	public boolean onKey(View v, int keyCode, KeyEvent event) {
		try {
			// skip keys if we aren't connected yet or have been disconnected
			if (bridge.isDisconnected() || bridge.transport == null)
				return false;

			final boolean interpretAsHardKeyboard = deviceHasHardKeyboard &&
					!manager.hardKeyboardHidden;
			final boolean rightModifiersAreSlashAndTab = interpretAsHardKeyboard &&
					PreferenceConstants.KEYMODE_RIGHT.equals(keymode);
			final boolean leftModifiersAreSlashAndTab = interpretAsHardKeyboard &&
					PreferenceConstants.KEYMODE_LEFT.equals(keymode);
			final boolean shiftedNumbersAreFKeys = shiftedNumbersAreFKeysOnHardKeyboard &&
					interpretAsHardKeyboard;
			final boolean controlNumbersAreFKeys = controlNumbersAreFKeysOnSoftKeyboard &&
					!interpretAsHardKeyboard;

			// Track hardware ctrl down/up before the ACTION_UP early-return.
			if (keyCode == KEYCODE_CTRL_LEFT || keyCode == KEYCODE_CTRL_RIGHT) {
				hardwareCtrlDown = (event.getAction() == KeyEvent.ACTION_DOWN);
				updateGlobalHardwareCtrlDownFromKeyEvent(event);

				if (isLikelyVirtualOrSoftKeyboardKeyEvent(event)
						&& event.getAction() == KeyEvent.ACTION_DOWN
						&& event.getRepeatCount() == 0) {
					// Some soft keyboards send CTRL as an immediate down+up "one-shot" key without
					// reliably setting ctrl meta-state on non-printable keys (like DPAD arrows).
					// Treat these CTRL key events as a transient modifier (like our on-screen CTRL)
					// so Ctrl+Arrow can still emit xterm-modified sequences.
					metaPress(OUR_CTRL_ON, true);
				}

				return true;
			}

			// Ignore all key-up events except for the special keys
			if (event.getAction() == KeyEvent.ACTION_UP) {
				if (rightModifiersAreSlashAndTab) {
					if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT
							&& (ourMetaState & OUR_SLASH) != 0) {
						ourMetaState &= ~OUR_TRANSIENT;
						bridge.transport.write('/');
						return true;
					} else if (keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
							&& (ourMetaState & OUR_TAB) != 0) {
						ourMetaState &= ~OUR_TRANSIENT;
						bridge.transport.write(0x09);
						return true;
					}
				} else if (leftModifiersAreSlashAndTab) {
					if (keyCode == KeyEvent.KEYCODE_ALT_LEFT
							&& (ourMetaState & OUR_SLASH) != 0) {
						ourMetaState &= ~OUR_TRANSIENT;
						bridge.transport.write('/');
						return true;
					} else if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
							&& (ourMetaState & OUR_TAB) != 0) {
						ourMetaState &= ~OUR_TRANSIENT;
						bridge.transport.write(0x09);
						return true;
					}
				}

				return false;
			}

			if (volumeKeysChangeFontSize) {
				if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
					bridge.increaseFontSize();
					return true;
				} else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
					bridge.decreaseFontSize();
					return true;
				}
			}

			bridge.resetScrollPosition();

			// Handle potentially multi-character IME input.
			if (keyCode == KeyEvent.KEYCODE_UNKNOWN &&
					event.getAction() == KeyEvent.ACTION_MULTIPLE) {
				byte[] input = event.getCharacters().getBytes(encoding);
				bridge.transport.write(input);
				return true;
			}

			/// Handle alt and shift keys if they aren't repeating
			if (event.getRepeatCount() == 0) {
				if (rightModifiersAreSlashAndTab) {
					switch (keyCode) {
					case KeyEvent.KEYCODE_ALT_RIGHT:
						ourMetaState |= OUR_SLASH;
						return true;
					case KeyEvent.KEYCODE_SHIFT_RIGHT:
						ourMetaState |= OUR_TAB;
						return true;
					case KeyEvent.KEYCODE_SHIFT_LEFT:
						metaPress(OUR_SHIFT_ON);
						return true;
					case KeyEvent.KEYCODE_ALT_LEFT:
						metaPress(OUR_ALT_ON);
						return true;
					}
				} else if (leftModifiersAreSlashAndTab) {
					switch (keyCode) {
					case KeyEvent.KEYCODE_ALT_LEFT:
						ourMetaState |= OUR_SLASH;
						return true;
					case KeyEvent.KEYCODE_SHIFT_LEFT:
						ourMetaState |= OUR_TAB;
						return true;
					case KeyEvent.KEYCODE_SHIFT_RIGHT:
						metaPress(OUR_SHIFT_ON);
						return true;
					case KeyEvent.KEYCODE_ALT_RIGHT:
						metaPress(OUR_ALT_ON);
						return true;
					}
				} else {
					switch (keyCode) {
					case KeyEvent.KEYCODE_ALT_LEFT:
					case KeyEvent.KEYCODE_ALT_RIGHT:
						metaPress(OUR_ALT_ON);
						return true;
					case KeyEvent.KEYCODE_SHIFT_LEFT:
					case KeyEvent.KEYCODE_SHIFT_RIGHT:
						metaPress(OUR_SHIFT_ON);
						return true;
					}
				}
				if (keyCode == KEYCODE_CTRL_LEFT || keyCode == KEYCODE_CTRL_RIGHT) {
					metaPress(OUR_CTRL_ON);
					return true;
				}
			}

			if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
				if (selectingForCopy) {
					if (selectionArea.isSelectingOrigin())
						selectionArea.finishSelectingOrigin();
					else {
						if (clipboard != null) {
							// copy selected area to clipboard
							String copiedText = selectionArea.copyFrom(buffer);
							clipboard.setText(copiedText);
							// XXX STOPSHIP
//							manager.notifyUser(manager.getString(
//									R.string.console_copy_done,
//									copiedText.length()));
							selectingForCopy = false;
							selectionArea.reset();
						}
					}
				} else {
					if ((ourMetaState & OUR_CTRL_ON) != 0) {
						sendEscape();
						ourMetaState &= ~OUR_CTRL_ON;
					} else
						metaPress(OUR_CTRL_ON, true);
				}
				bridge.redraw();
				return true;
			}

			int derivedMetaState = event.getMetaState();
			if ((ourMetaState & OUR_SHIFT_MASK) != 0)
				derivedMetaState |= KeyEvent.META_SHIFT_ON;
			if ((ourMetaState & OUR_ALT_MASK) != 0)
				derivedMetaState |= KeyEvent.META_ALT_ON;
			if ((ourMetaState & OUR_CTRL_MASK) != 0)
				derivedMetaState |= HC_META_CTRL_ON;
			if (hardwareCtrlDown || isGlobalHardwareCtrlDownActive())
				derivedMetaState |= HC_META_CTRL_ON;

			final boolean consumedGlobalCtrlOneShot =
					consumeGlobalCtrlOneShotIfActiveForKeyEvent(keyCode, event);
			if (consumedGlobalCtrlOneShot && !isCtrlPressed(derivedMetaState))
				derivedMetaState |= HC_META_CTRL_ON;

			final boolean shiftPressed = isShiftPressed(derivedMetaState);

			if ((ourMetaState & OUR_TRANSIENT) != 0) {
				ourMetaState &= ~OUR_TRANSIENT;
				bridge.redraw();
			}

			// Test for modified numbers becoming function keys
			if (shiftedNumbersAreFKeys && (derivedMetaState & KeyEvent.META_SHIFT_ON) != 0) {
				if (sendFunctionKey(keyCode))
					return true;
			}
			if (controlNumbersAreFKeys && isCtrlPressed(derivedMetaState)) {
				if (sendFunctionKey(keyCode))
					return true;
			}

			// CTRL-SHIFT-C to copy.
			if (keyCode == KeyEvent.KEYCODE_C
					&& isCtrlPressed(derivedMetaState)
					&& shiftPressed) {
				bridge.copyCurrentSelection();
				return true;
			}

			// CTRL-SHIFT-V to paste.
			if (keyCode == KeyEvent.KEYCODE_V
					&& isCtrlPressed(derivedMetaState)
					&& shiftPressed
					&& clipboard.hasText()) {
				bridge.injectString(clipboard.getText().toString());
				return true;
			}

			if ((keyCode == KeyEvent.KEYCODE_EQUALS
					&& isCtrlPressed(derivedMetaState)
					&& shiftPressed)
					|| (keyCode == KeyEvent.KEYCODE_PLUS
					&& isCtrlPressed(derivedMetaState))) {
				bridge.increaseFontSize();
				return true;
			}

			if (keyCode == KeyEvent.KEYCODE_MINUS && isCtrlPressed(derivedMetaState)) {
				bridge.decreaseFontSize();
				return true;
			}

			// Ask the system to use the keymap to give us the unicode character for this key,
			// with our derived modifier state applied.
			int uchar = event.getUnicodeChar(derivedMetaState & ~HC_META_CTRL_MASK);
			int ucharWithoutAlt = event.getUnicodeChar(
					derivedMetaState & ~(HC_META_ALT_MASK | HC_META_CTRL_MASK));
			if (uchar == 0) {
				// Keymap doesn't know the key with alt on it, so just go with the unmodified version
				uchar = ucharWithoutAlt;
			} else if (uchar != ucharWithoutAlt) {
				// The alt key was used to modify the character returned; therefore, drop the alt
				// modifier from the state so we don't end up sending alt+key.
				derivedMetaState &= ~HC_META_ALT_MASK;
			}

			// Remove shift from the modifier state as it has already been used by getUnicodeChar.
			derivedMetaState &= ~HC_META_SHIFT_MASK;

			if ((uchar & KeyCharacterMap.COMBINING_ACCENT) != 0) {
				mDeadKey = uchar & KeyCharacterMap.COMBINING_ACCENT_MASK;
				return true;
			}

			if (mDeadKey != 0) {
				uchar = KeyCharacterMap.getDeadChar(mDeadKey, keyCode);
				mDeadKey = 0;
			}

			// If we have a defined non-control character
			if (uchar >= 0x20) {
				if (isCtrlPressed(derivedMetaState))
					uchar = keyAsControl(uchar);
				if (isAltPressed(derivedMetaState))
					sendEscape();
				if (uchar < 0x80)
					bridge.transport.write(uchar);
				else
					// TODO write encoding routine that doesn't allocate each time
					bridge.transport.write(new String(Character.toChars(uchar))
							.getBytes(encoding));
				return true;
			}

			// look for special chars
			switch (keyCode) {
			case KEYCODE_ESCAPE:
				sendEscape();
				return true;
			case KeyEvent.KEYCODE_TAB:
				if (shiftPressed) {
					writeXtermCsi("\u001b[Z");
				} else {
					bridge.transport.write(0x09);
				}
				return true;
			case KeyEvent.KEYCODE_CAMERA:

				// check to see which shortcut the camera button triggers
				String camera = manager.prefs.getString(
						PreferenceConstants.CAMERA,
						PreferenceConstants.CAMERA_CTRLA_SPACE);
				if (PreferenceConstants.CAMERA_CTRLA_SPACE.equals(camera)) {
					bridge.transport.write(0x01);
					bridge.transport.write(' ');
				} else if (PreferenceConstants.CAMERA_CTRLA.equals(camera)) {
					bridge.transport.write(0x01);
				} else if (PreferenceConstants.CAMERA_ESC.equals(camera)) {
					((vt320) buffer).keyTyped(vt320.KEY_ESCAPE, ' ', 0);
				} else if (PreferenceConstants.CAMERA_ESC_A.equals(camera)) {
					((vt320) buffer).keyTyped(vt320.KEY_ESCAPE, ' ', 0);
					bridge.transport.write('a');
				}

				break;

			case KeyEvent.KEYCODE_DEL:
				((vt320) buffer).keyPressed(vt320.KEY_BACK_SPACE, ' ',
						getStateForBuffer());
				return true;
			case KeyEvent.KEYCODE_ENTER:
				if (isAltPressed(derivedMetaState)) {
					sendEscape();
				}
				((vt320) buffer).keyTyped(vt320.KEY_ENTER, ' ', 0);
				return true;

			case KeyEvent.KEYCODE_DPAD_LEFT:
				if (selectingForCopy) {
					selectionArea.decrementColumn();
					bridge.redraw();
				} else {
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsi(shiftPressed, altPressed, ctrlPressed, 'D');
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_LEFT, ' ', getStateForBuffer());
					}
					bridge.tryKeyVibrate();
				}
				return true;

			case KeyEvent.KEYCODE_DPAD_UP:
				if (selectingForCopy) {
					selectionArea.decrementRow();
					bridge.redraw();
				} else {
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsi(shiftPressed, altPressed, ctrlPressed, 'A');
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_UP, ' ', getStateForBuffer());
					}
					bridge.tryKeyVibrate();
				}
				return true;

			case KeyEvent.KEYCODE_DPAD_DOWN:
				if (selectingForCopy) {
					selectionArea.incrementRow();
					bridge.redraw();
				} else {
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsi(shiftPressed, altPressed, ctrlPressed, 'B');
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_DOWN, ' ', getStateForBuffer());
					}
					bridge.tryKeyVibrate();
				}
				return true;

			case KeyEvent.KEYCODE_DPAD_RIGHT:
				if (selectingForCopy) {
					selectionArea.incrementColumn();
					bridge.redraw();
				} else {
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsi(shiftPressed, altPressed, ctrlPressed, 'C');
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_RIGHT, ' ', getStateForBuffer());
					}
					bridge.tryKeyVibrate();
				}
				return true;

			case KEYCODE_INSERT:
				{
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsiTilde(shiftPressed, altPressed, ctrlPressed, 2);
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_INSERT, ' ', getStateForBuffer());
					}
				}
				return true;
			case KEYCODE_FORWARD_DEL:
				{
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsiTilde(shiftPressed, altPressed, ctrlPressed, 3);
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_DELETE, ' ', getStateForBuffer());
					}
				}
				return true;
			case KEYCODE_MOVE_HOME:
				{
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsi(shiftPressed, altPressed, ctrlPressed, 'H');
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_HOME, ' ', getStateForBuffer());
					}
				}
				return true;
			case KEYCODE_MOVE_END:
				{
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsi(shiftPressed, altPressed, ctrlPressed, 'F');
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_END, ' ', getStateForBuffer());
					}
				}
				return true;
			case KEYCODE_PAGE_UP:
				{
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsiTilde(shiftPressed, altPressed, ctrlPressed, 5);
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_PAGE_UP, ' ', getStateForBuffer());
					}
				}
				return true;
			case KEYCODE_PAGE_DOWN:
				{
					final boolean ctrlPressed = isCtrlPressed(derivedMetaState);
					final boolean altPressed = isAltPressed(derivedMetaState);
					if (ctrlPressed || altPressed || shiftPressed) {
						writeXtermModifiedCsiTilde(shiftPressed, altPressed, ctrlPressed, 6);
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_PAGE_DOWN, ' ', getStateForBuffer());
					}
				}
				return true;
			}

		} catch (IOException e) {
			Log.e(TAG, "Problem while trying to handle an onKey() event", e);
			try {
				bridge.transport.flush();
			} catch (IOException ioe) {
				Log.d(TAG, "Our transport was closed, dispatching disconnect event");
				bridge.dispatchDisconnect(false);
			}
		} catch (NullPointerException npe) {
			Log.d(TAG, "Input before connection established ignored.");
			return true;
		}

		return false;
	}

	public int keyAsControl(int key) {
		// Support CTRL-a through CTRL-z
		if (key >= 0x61 && key <= 0x7A)
			key -= 0x60;
		// Support CTRL-A through CTRL-_
		else if (key >= 0x40 && key <= 0x5F)
			key -= 0x40;
		// CTRL-space sends NULL
		else if (key == 0x20)
			key = 0x00;
		// CTRL-? sends DEL
		else if (key == 0x3F)
			key = 0x7F;
		return key;
	}

	public void sendEscape() {
		((vt320) buffer).keyTyped(vt320.KEY_ESCAPE, ' ', 0);
	}

	public void sendTab() {
		try {
			bridge.transport.write(0x09);
		} catch (IOException e) {
			Log.e(TAG, "Problem while trying to send TAB press.", e);
			try {
				bridge.transport.flush();
			} catch (IOException ioe) {
				Log.d(TAG, "Our transport was closed, dispatching disconnect event");
				bridge.dispatchDisconnect(false);
			}
		}
	}

	public void sendPressedKey(int key) {
		((vt320) buffer).keyPressed(key, ' ', getStateForBuffer());
	}

	/**
	 * @param keyCode
	 * @return successful
	 */
	private boolean sendFunctionKey(int keyCode) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_1:
			((vt320) buffer).keyPressed(vt320.KEY_F1, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_2:
			((vt320) buffer).keyPressed(vt320.KEY_F2, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_3:
			((vt320) buffer).keyPressed(vt320.KEY_F3, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_4:
			((vt320) buffer).keyPressed(vt320.KEY_F4, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_5:
			((vt320) buffer).keyPressed(vt320.KEY_F5, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_6:
			((vt320) buffer).keyPressed(vt320.KEY_F6, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_7:
			((vt320) buffer).keyPressed(vt320.KEY_F7, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_8:
			((vt320) buffer).keyPressed(vt320.KEY_F8, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_9:
			((vt320) buffer).keyPressed(vt320.KEY_F9, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_0:
			((vt320) buffer).keyPressed(vt320.KEY_F10, ' ', 0);
			return true;
		default:
			return false;
		}
	}

	/**
	 * Handle meta key presses where the key can be locked on.
	 * <p>
	 * 1st press: next key to have meta state<br />
	 * 2nd press: meta state is locked on<br />
	 * 3rd press: disable meta state
	 *
	 * @param code
	 */
	public void metaPress(int code, boolean forceSticky) {
		if ((ourMetaState & (code << 1)) != 0) {
			ourMetaState &= ~(code << 1);
		} else if ((ourMetaState & code) != 0) {
			ourMetaState &= ~code;
			ourMetaState |= code << 1;
		} else if (forceSticky || (stickyMetas & code) != 0) {
			ourMetaState |= code;
		} else {
			// skip redraw
			return;
		}
		bridge.redraw();
	}

	public void metaPress(int code) {
		metaPress(code, false);
	}

	public void setTerminalKeyMode(String keymode) {
		this.keymode = keymode;
	}

	private int getStateForBuffer() {
		int bufferState = 0;

		if ((ourMetaState & OUR_CTRL_MASK) != 0)
			bufferState |= vt320.KEY_CONTROL;
		if ((ourMetaState & OUR_SHIFT_MASK) != 0)
			bufferState |= vt320.KEY_SHIFT;
		if ((ourMetaState & OUR_ALT_MASK) != 0)
			bufferState |= vt320.KEY_ALT;

		return bufferState;
	}

	public int getMetaState() {
		return ourMetaState;
	}

	public int getDeadKey() {
		return mDeadKey;
	}

	public void setClipboardManager(ClipboardManager clipboard) {
		this.clipboard = clipboard;
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
			String key) {
		if (PreferenceConstants.KEYMODE.equals(key) ||
				PreferenceConstants.SHIFT_FKEYS.equals(key) ||
				PreferenceConstants.CTRL_FKEYS.equals(key) ||
				PreferenceConstants.VOLUME_FONT.equals(key) ||
				PreferenceConstants.STICKY_MODIFIERS.equals(key)) {
			updatePrefs();
		}
	}

	private void updatePrefs() {
		keymode = prefs.getString(PreferenceConstants.KEYMODE, PreferenceConstants.KEYMODE_NONE);
		shiftedNumbersAreFKeysOnHardKeyboard =
				prefs.getBoolean(PreferenceConstants.SHIFT_FKEYS, false);
		controlNumbersAreFKeysOnSoftKeyboard =
				prefs.getBoolean(PreferenceConstants.CTRL_FKEYS, false);
		volumeKeysChangeFontSize = prefs.getBoolean(PreferenceConstants.VOLUME_FONT, true);
		String stickyModifiers = prefs.getString(PreferenceConstants.STICKY_MODIFIERS,
				PreferenceConstants.NO);
		if (PreferenceConstants.ALT.equals(stickyModifiers)) {
			stickyMetas = OUR_ALT_ON;
		} else if (PreferenceConstants.YES.equals(stickyModifiers)) {
			stickyMetas = OUR_SHIFT_ON | OUR_CTRL_ON | OUR_ALT_ON;
		} else {
			stickyMetas = 0;
		}
	}

	public void setCharset(String encoding) {
		this.encoding = encoding;
	}
}
