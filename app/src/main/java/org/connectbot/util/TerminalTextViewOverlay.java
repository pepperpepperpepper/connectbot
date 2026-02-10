/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2015 Kenny Root, Jeffrey Sharkey
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
import org.connectbot.TerminalView;
import org.connectbot.service.TerminalBridge;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import androidx.core.view.MotionEventCompat;
import android.text.ClipboardManager;
import android.text.Selection;
import android.text.Spannable;
import android.view.ActionMode;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;
import de.mud.terminal.VDUBuffer;
import de.mud.terminal.vt320;

/**
 * Custom TextView {@link TextView} which is intended to (invisibly) be on top of the TerminalView
 * (@link TerminalView) in order to allow the user to select and copy the text of the bitmap below.
 *
 * @author rhansby
 */
public class TerminalTextViewOverlay extends androidx.appcompat.widget.AppCompatTextView {
	public TerminalView terminalView; // ryan: this name sucks
	private String currentSelection = "";
	private ActionMode selectionActionMode;
	private ClipboardManager clipboard;
	private boolean isTouchDown = false;

	private int oldBufferHeight = 0;
	private int oldScrollY = -1;

	// When the user begins a long-press selection while the terminal is auto-scrolling (windowBase
	// following screenBase), new output can move the content under their finger before the long
	// press is recognized. That causes selection/copy to be "miscalibrated" relative to what is
	// currently visible.
	//
	// To match common terminal UX, freeze the viewport (windowBase) while a selection is starting
	// or active *if* the user started at the bottom.
	private boolean freezeWindowBase = false;
	private int frozenWindowBase = -1;
	private boolean restoreBottomOnUnfreeze = false;

	private void maybeBeginFreezeWindowBase() {
		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			final int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
			final int drawnScreenBase = terminalView.bridge.getLastDrawnScreenBase();
			final boolean hasDrawnViewport = drawnWindowBase >= 0 && drawnScreenBase >= 0;

			// If the buffer advanced since the last frame was rendered (common under heavy output),
			// selection should match what the user sees (the last-drawn bitmap), not the newest
			// buffer state.
			int visibleWindowBase = hasDrawnViewport ? drawnWindowBase : vb.getWindowBase();
			if (visibleWindowBase != vb.getWindowBase()) {
				terminalView.bridge.buffer.setWindowBase(visibleWindowBase);
				visibleWindowBase = vb.getWindowBase(); // clamp
			}

			boolean atBottom = hasDrawnViewport ? (drawnWindowBase == drawnScreenBase)
					: (visibleWindowBase == vb.screenBase);

			freezeWindowBase = atBottom;
			frozenWindowBase = atBottom ? visibleWindowBase : -1;
			restoreBottomOnUnfreeze = atBottom;
			terminalView.bridge.buffer.setFreezeWindowBase(atBottom);
		}
	}

	private void unfreezeWindowBaseIfNeeded() {
		if (!freezeWindowBase) {
			return;
		}

		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			terminalView.bridge.buffer.setFreezeWindowBase(false);
			if (restoreBottomOnUnfreeze) {
				terminalView.bridge.buffer.setWindowBase(vb.screenBase);
			}
		}
		terminalView.bridge.redraw();

		freezeWindowBase = false;
		frozenWindowBase = -1;
		restoreBottomOnUnfreeze = false;
	}

	private void enforceFrozenWindowBaseIfNeeded() {
		if (!freezeWindowBase) {
			return;
		}
		if (!isTouchDown && selectionActionMode == null) {
			return;
		}

		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			int target = frozenWindowBase;
			if (target < 0) {
				return;
			}
			// Clamp to a valid range in case screenBase advances or scrollback saturates.
			if (target > vb.screenBase) {
				target = vb.screenBase;
			}
			if (target < 0) {
				target = 0;
			}

			int current = vb.getWindowBase();
			if (current != target) {
				terminalView.bridge.buffer.setWindowBase(target);
			}
		}
	}

	public TerminalTextViewOverlay(Context context, TerminalView terminalView) {
		super(context);

		this.terminalView = terminalView;
		clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);

		setTextColor(Color.TRANSPARENT);
		setTypeface(Typeface.MONOSPACE);
		setIncludeFontPadding(false);
		setPadding(0, 0, 0, 0);
		setHorizontallyScrolling(true);
		setTextIsSelectable(true);
		setCustomSelectionActionModeCallback(new TextSelectionActionModeCallback());
	}

	public void refreshTextFromBuffer() {
		StringBuilder buffer = new StringBuilder();
		final int windowBase;
		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			int numRows = vb.getBufferSize();
			int numCols = vb.getColumns();
			oldBufferHeight = numRows;
			windowBase = vb.getWindowBase();

			int previousTotalLength = 0;
			for (int r = 0; r < numRows && vb.charArray[r] != null; r++) {
				for (int c = 0; c < numCols; c++) {
					buffer.append(vb.charArray[r][c]);
				}

				// Truncate all the new whitespace without removing the old data.
				while (buffer.length() > previousTotalLength &&
						Character.isWhitespace(buffer.charAt(buffer.length() - 1))) {
					buffer.setLength(buffer.length() - 1);
				}

				// Make sure each line ends with a carriage return and then remember the buffer
				// at that length.
				buffer.append('\n');
				previousTotalLength = buffer.length();
			}
		}

		oldScrollY = windowBase * getLineHeight();

		setText(buffer);

		// Try to apply the scroll immediately so that hit-testing is correct even before the next
		// draw pass (e.g., during fast IME hide/show interactions). We also keep oldScrollY so
		// onPreDraw can re-apply once layout is ready.
		super.scrollTo(0, oldScrollY);
	}

	/**
	 * If there is a new line in the buffer, add an empty line
	 * in this TextView, so that selection seems to move up with the
	 * rest of the buffer.
	 */
	public void onBufferChanged() {
		enforceFrozenWindowBaseIfNeeded();

		// While the user is holding their finger down waiting for a long-press selection to begin,
		// avoid mutating the overlay (append/scroll). This can cancel long-press selection under
		// continuous output.
		if (isTouchDown && selectionActionMode == null) {
			return;
		}

		final int numRows;
		final int windowBase;
		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			numRows = vb.getBufferSize();
			windowBase = vb.getWindowBase();
		}

		int numNewRows = numRows - oldBufferHeight;

		// Always keep the overlay scroll position aligned to the current windowBase, even when the
		// scrollback is saturated and the buffer height doesn't grow.
		oldScrollY = windowBase * getLineHeight();
		oldBufferHeight = numRows;

		if (numNewRows <= 0) {
			super.scrollTo(0, oldScrollY);
			return;
		}

		StringBuilder newLines = new StringBuilder(numNewRows);
		for (int i = 0; i < numNewRows; i++) {
			newLines.append('\n');
		}

		// Appending to the TextView while selection handles are active can cause selection to be
		// cleared by framework internals. Preserve any active selection explicitly.
		final int selStart = getSelectionStart();
		final int selEnd = getSelectionEnd();
		final boolean hadSelection = selStart >= 0 && selEnd >= 0 && selStart != selEnd;

		append(newLines);
		if (hadSelection) {
			CharSequence text = getText();
			if (text instanceof Spannable) {
				Selection.setSelection((Spannable) text, selStart, selEnd);
			}
		}
		super.scrollTo(0, oldScrollY);
	}

	@Override
	public boolean onPreDraw() {
		boolean superResult = super.onPreDraw();

		if (oldScrollY >= 0) {
			// Apply pending scroll without feeding back into buffer.windowBase. The buffer is the source
			// of truth here; this is only to align the overlay's visible (invisible) scroll position
			// once layout is ready.
			super.scrollTo(0, oldScrollY);
			oldScrollY = -1;
		}

		return superResult;
	}

	private void closeSelectionActionMode() {
		if (selectionActionMode != null) {
			selectionActionMode.finish();
			selectionActionMode = null;
		}
	}

	private void updateCurrentSelection(int selStart, int selEnd) {
		CharSequence text = getText();
		if (text == null || selStart < 0 || selEnd < 0) {
			currentSelection = "";
			return;
		}

		int start = Math.min(selStart, selEnd);
		int end = Math.max(selStart, selEnd);
		if (start >= end || start >= text.length()) {
			currentSelection = "";
			return;
		}

		end = Math.min(end, text.length());
		currentSelection = text.subSequence(start, end).toString();
	}

	public void copyCurrentSelectionToClipboard() {
		updateCurrentSelection(getSelectionStart(), getSelectionEnd());
		if (currentSelection.length() != 0) {
			clipboard.setText(currentSelection);
		}
		closeSelectionActionMode();
	}

	private void pasteClipboard() {
		String clip = "";
		if (clipboard.hasText()) {
			clip = clipboard.getText().toString();
		}
		terminalView.bridge.injectString(clip);
	}

	@Override
	protected void onSelectionChanged(int selStart, int selEnd) {
		updateCurrentSelection(selStart, selEnd);
		super.onSelectionChanged(selStart, selEnd);
	}

	@Override
	public void scrollTo(int x, int y) {
		// Keep X pinned at 0 and keep Y aligned to the terminal buffer's scrollback (windowBase).
		//
		// TextView can call scrollTo() internally (selection/bring-into-view). If the overlay scrolls
		// independently from the terminal bitmap, hit-testing becomes "miscalibrated" and users can
		// end up selecting/copying the wrong text.
		final int lineHeight = Math.max(1, getLineHeight());
		final int windowBase;
		synchronized (terminalView.bridge.buffer) {
			windowBase = terminalView.bridge.buffer.getWindowBase();
		}
		super.scrollTo(0, windowBase * lineHeight);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_DOWN) {
			isTouchDown = true;
			maybeBeginFreezeWindowBase();
			// Selection may be beginning. Sync the TextView with the buffer.
			refreshTextFromBuffer();
		} else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
			isTouchDown = false;
			// If selection never started, release any freeze and return to bottom so auto-scroll
			// resumes normally.
			if (selectionActionMode == null) {
				unfreezeWindowBaseIfNeeded();
			}
			final int windowBase;
			synchronized (terminalView.bridge.buffer) {
				windowBase = terminalView.bridge.buffer.getWindowBase();
			}
			super.scrollTo(0, windowBase * getLineHeight());
		}

		// Mouse input is treated differently:
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH &&
				MotionEventCompat.getSource(event) == InputDevice.SOURCE_MOUSE) {
			if (onMouseEvent(event, terminalView.bridge)) {
				return true;
			}
			terminalView.viewPager.setPagingEnabled(true);
		} else {
			if (terminalView.onTouchEvent(event)) {
				return true;
			}
		}

		return super.onTouchEvent(event);
	}

	@Override
	public boolean onGenericMotionEvent(MotionEvent event) {
		if ((MotionEventCompat.getSource(event) & InputDevice.SOURCE_CLASS_POINTER) != 0) {
			switch (event.getAction()) {
			case MotionEvent.ACTION_SCROLL:
				// Process scroll wheel movement:
				float yDistance = MotionEventCompat.getAxisValue(event, MotionEvent.AXIS_VSCROLL);

				vt320 vtBuffer = (vt320) terminalView.bridge.buffer;
				boolean mouseReport = vtBuffer.isMouseReportEnabled();
				if (mouseReport) {
					int row = (int) Math.floor(event.getY() / terminalView.bridge.charHeight);
					int col = (int) Math.floor(event.getX() / terminalView.bridge.charWidth);

					vtBuffer.mouseWheel(
							yDistance > 0,
							col,
							row,
							(event.getMetaState() & KeyEvent.META_CTRL_ON) != 0,
							(event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0,
							(event.getMetaState() & KeyEvent.META_META_ON) != 0);
					return true;
				}
			}
		}

		return super.onGenericMotionEvent(event);
	}

	/**
	 * @param event
	 * @param bridge
	 * @return True if the event is handled.
	 */
	private boolean onMouseEvent(MotionEvent event, TerminalBridge bridge) {
		int row = (int) Math.floor(event.getY() / bridge.charHeight);
		int col = (int) Math.floor(event.getX() / bridge.charWidth);
		int meta = event.getMetaState();
		boolean shiftOn = (meta & KeyEvent.META_SHIFT_ON) != 0;
		vt320 vtBuffer = (vt320) bridge.buffer;
		boolean mouseReport = vtBuffer.isMouseReportEnabled();

		// MouseReport can be "defeated" using the shift key.
		if (!mouseReport || shiftOn) {
			if (event.getAction() == MotionEvent.ACTION_DOWN) {
				if (event.getButtonState() == MotionEvent.BUTTON_TERTIARY) {
					// Middle click pastes.
					pasteClipboard();
					return true;
				}

				// Begin "selection mode"

				closeSelectionActionMode();
			} else if (event.getAction() == MotionEvent.ACTION_MOVE) {
				// In the middle of selection.

				if (selectionActionMode == null) {
					selectionActionMode = startActionMode(new TextSelectionActionModeCallback());
				}

				int selectionStart = getSelectionStart();
				int selectionEnd = getSelectionEnd();

				if (selectionStart > selectionEnd) {
					int tempStart = selectionStart;
					selectionStart = selectionEnd;
					selectionEnd = tempStart;
				}

				updateCurrentSelection(selectionStart, selectionEnd);
			}
		} else if (event.getAction() == MotionEvent.ACTION_DOWN) {
			terminalView.viewPager.setPagingEnabled(false);
			vtBuffer.mousePressed(
					col, row, mouseEventToJavaModifiers(event));
			return true;
		} else if (event.getAction() == MotionEvent.ACTION_UP) {
			terminalView.viewPager.setPagingEnabled(true);
			vtBuffer.mouseReleased(col, row);
			return true;
		} else if (event.getAction() == MotionEvent.ACTION_MOVE) {
			int buttonState = event.getButtonState();
			int button = (buttonState & MotionEvent.BUTTON_PRIMARY) != 0 ? 0 :
					(buttonState & MotionEvent.BUTTON_SECONDARY) != 0 ? 1 :
							(buttonState & MotionEvent.BUTTON_TERTIARY) != 0 ? 2 : 3;
			vtBuffer.mouseMoved(
					button,
					col,
					row,
					(meta & KeyEvent.META_CTRL_ON) != 0,
					(meta & KeyEvent.META_SHIFT_ON) != 0,
					(meta & KeyEvent.META_META_ON) != 0);
			return true;
		}

		return false;
	}

	/**
	 * Takes an android mouse event and produces a Java InputEvent modifiers int which can be
	 * passed to vt320.
	 * @param mouseEvent The {@link MotionEvent} which should be a mouse click or release.
	 * @return A Java InputEvent modifier int. See
	 * http://docs.oracle.com/javase/7/docs/api/java/awt/event/InputEvent.html
	 */
	private static int mouseEventToJavaModifiers(MotionEvent mouseEvent) {
		if (MotionEventCompat.getSource(mouseEvent) != InputDevice.SOURCE_MOUSE) return 0;

		int mods = 0;

		// See http://docs.oracle.com/javase/7/docs/api/constant-values.html
		int buttonState = mouseEvent.getButtonState();
		if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0)
			mods |= 16;
		if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0)
			mods |= 8;
		if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0)
			mods |= 4;

		// Note: Meta and Ctrl are intentionally swapped here to keep logic in vt320 simple.
		int meta = mouseEvent.getMetaState();
		if ((meta & KeyEvent.META_META_ON) != 0)
			mods |= 2;
		if ((meta & KeyEvent.META_SHIFT_ON) != 0)
			mods |= 1;
		if ((meta & KeyEvent.META_CTRL_ON) != 0)
			mods |= 4;

		return mods;
	}

	@Override
	public boolean onCheckIsTextEditor() {
		// This prevents a cursor being displayed within the text.
		return false;
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
		return terminalView.onCreateInputConnection(outAttrs);
	}

	private class TextSelectionActionModeCallback implements ActionMode.Callback {
		private static final int COPY = 0;
		private static final int PASTE = 1;

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false;
		}

		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			TerminalTextViewOverlay.this.selectionActionMode = mode;

			menu.clear();

			menu.add(0, COPY, 0, R.string.console_menu_copy)
					.setIcon(R.drawable.ic_action_copy)
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_WITH_TEXT | MenuItem.SHOW_AS_ACTION_IF_ROOM);
			menu.add(0, PASTE, 1, R.string.console_menu_paste)
					.setIcon(R.drawable.ic_action_paste)
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_WITH_TEXT | MenuItem.SHOW_AS_ACTION_IF_ROOM);

			return true;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			switch (item.getItemId()) {
			case COPY:
				copyCurrentSelectionToClipboard();
				return true;
			case PASTE:
				pasteClipboard();
				mode.finish();
				return true;
			}

			return false;
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			TerminalTextViewOverlay.this.selectionActionMode = null;
			unfreezeWindowBaseIfNeeded();
		}
	}
}
