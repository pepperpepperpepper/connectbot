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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import androidx.core.view.MotionEventCompat;
import android.text.ClipboardManager;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.view.ActionMode;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;
import java.util.Arrays;
import de.mud.terminal.VDUBuffer;
import de.mud.terminal.vt320;

/**
 * Custom TextView {@link TextView} which is intended to (invisibly) be on top of the TerminalView
 * (@link TerminalView) in order to allow the user to select and copy the text of the bitmap below.
 *
 * @author rhansby
 */
public class TerminalTextViewOverlay extends androidx.appcompat.widget.AppCompatTextView {
	private static final int DEBUG_CONTEXT_RADIUS = 16;
	private static final int[] EMPTY_ROW_OFFSETS = new int[0];
	private static final int HANDLE_NONE = 0;
	private static final int HANDLE_START = 1;
	private static final int HANDLE_END = 2;
	private static final long HANDLE_HAPTIC_MIN_INTERVAL_MS = 35L;

	private static final class BufferTextSnapshot {
		final String text;
		final int[] rowStarts;
		final int[] rowLengths;

		BufferTextSnapshot(String text, int[] rowStarts, int[] rowLengths) {
			this.text = text;
			this.rowStarts = rowStarts;
			this.rowLengths = rowLengths;
		}
	}

	private static final class TapDebugState {
		long seq;
		float x;
		float y;
		int gridRow;
		int gridCol;
		int layoutLine;
		int layoutCol;
		int liveAbsRow;
		int drawnAbsRow;
		String overlayWord;
		String liveWord;
		String drawnWord;
	}

	public TerminalView terminalView; // ryan: this name sucks
	private String currentSelection = "";
	private ActionMode selectionActionMode;
	private ClipboardManager clipboard;
	private boolean isTouchDown = false;
	private long touchDownUptimeMillis = 0L;
	private float touchDownX = 0f;
	private float touchDownY = 0f;
	private boolean customLongPressSelectionStarted = false;

	private int oldBufferHeight = 0;
	private int oldScrollY = -1;
	private int oldScreenBase = -1;
	private int lastSyncedDrawnWindowBase = -1;
	private int lastSyncedDrawnScreenBase = -1;
	private int lastSyncedDrawnViewportHash = 0;
	private long lastSyncedDrawnFrameSerial = -1L;
	private boolean syncFromDrawQueued = false;
	private volatile int textRowBase = 0;
	private volatile int[] rowStarts = EMPTY_ROW_OFFSETS;
	private volatile int[] rowLengths = EMPTY_ROW_OFFSETS;
	private final Paint selectionFillPaint = new Paint();
	private final Paint selectionHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint selectionHandleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF tempHandleRect = new RectF();
	private int selectionStartAbsRow = -1;
	private int selectionStartCol = -1;
	private int selectionEndAbsRow = -1;
	private int selectionEndCol = -1;
	private int draggingHandle = HANDLE_NONE;
	private int lastHandleHapticAbsRow = Integer.MIN_VALUE;
	private int lastHandleHapticCol = Integer.MIN_VALUE;
	private long lastHandleHapticUptime = 0L;
	private final Runnable beginCustomSelectionRunnable = new Runnable() {
		@Override
		public void run() {
			if (!isTouchDown || selectionActionMode != null) {
				return;
			}
			customLongPressSelectionStarted = beginCustomSelectionAt(touchDownX, touchDownY);
		}
	};

	private final Runnable syncFromLastDrawnFrameRunnable = new Runnable() {
		@Override
		public void run() {
			syncFromDrawQueued = false;
			if (selectionActionMode != null || isTouchDown) {
				return;
			}
			if (shouldRefreshForCurrentDrawnViewport()) {
				refreshTextFromBuffer();
			}
		}
	};

	// When the user begins a long-press selection while the terminal is auto-scrolling (windowBase
	// following screenBase), new output can move the content under their finger before the long
	// press is recognized. That causes selection/copy to be "miscalibrated" relative to what is
	// currently visible.
	//
	// To match common terminal UX, freeze the viewport (windowBase) while a selection is starting
	// or active *if* the user started at the bottom.
	private boolean freezeWindowBase = false;
	private boolean restoreBottomOnUnfreeze = false;
	private long debugTapSequence = 0L;
	private TapDebugState lastTapDebugState = null;

	private void maybeBeginFreezeWindowBase() {
		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			final int currentWindowBase = vb.getWindowBase();
			final boolean atBottomNow = currentWindowBase == vb.screenBase;
			final int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
			final int drawnScreenBase = terminalView.bridge.getLastDrawnScreenBase();
			final boolean hasDrawnViewport = drawnWindowBase >= 0 && drawnScreenBase >= 0;

			if (atBottomNow && hasDrawnViewport) {
				int visibleWindowBase = currentWindowBase;
				// If the buffer advanced since the last frame was rendered (common under heavy output),
				// selection should match what the user sees (the last-drawn bitmap), not the newest
				// buffer state.
				visibleWindowBase = drawnWindowBase;
				if (visibleWindowBase != currentWindowBase) {
					terminalView.bridge.buffer.setWindowBase(visibleWindowBase);
				}
			}

			// Only freeze auto-scroll when the user started selection at the bottom. If they're
			// intentionally scrolled back, preserve their scrollback viewport (don't snap windowBase
			// based on stale last-drawn values).
			freezeWindowBase = atBottomNow;
			restoreBottomOnUnfreeze = atBottomNow;
			terminalView.bridge.buffer.setFreezeWindowBase(atBottomNow);
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
		restoreBottomOnUnfreeze = false;
	}

	public TerminalTextViewOverlay(Context context, TerminalView terminalView) {
		super(context);

		this.terminalView = terminalView;
		clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);

		setTextColor(Color.TRANSPARENT);
		setTypeface(Typeface.MONOSPACE);
		// Keep font padding enabled so TextView uses top/bottom font metrics when positioning
		// baselines. The terminal bitmap grid uses Paint.getFontMetrics().top for baseline
		// placement, so disabling font padding (ascent/descent) causes the selection highlight
		// to visually drift between lines even when hit-testing is correct.
		setIncludeFontPadding(true);
		setPadding(0, 0, 0, 0);
		setHorizontallyScrolling(true);
		setFocusable(true);
		setFocusableInTouchMode(true);
		setClickable(true);
		setLongClickable(true);
		setTextIsSelectable(false);
		setCursorVisible(false);
		setCustomSelectionActionModeCallback(new TextSelectionActionModeCallback());

		selectionFillPaint.setStyle(Paint.Style.FILL);
		selectionHandlePaint.setStyle(Paint.Style.FILL);
		selectionHandleStrokePaint.setStyle(Paint.Style.STROKE);
		selectionHandleStrokePaint.setStrokeWidth(3f);
	}

	private BufferTextSnapshot buildBufferTextSnapshot(int startRowInclusive, int endRowExclusive,
			boolean preferLastDrawnVisibleRows) {
		StringBuilder buffer = new StringBuilder();
		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			int numRows = vb.getBufferSize();
			int numCols = vb.getColumns();
			int visibleRows = vb.getRows();
			int safeStart = Math.max(0, startRowInclusive);
			int safeEnd = Math.min(numRows, endRowExclusive);
			final int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
			int rowCount = Math.max(0, safeEnd - safeStart);
			int[] builtRowStarts = new int[rowCount];
			int[] builtRowLengths = new int[rowCount];

			for (int r = safeStart; r < safeEnd; r++) {
				int rowIndex = r - safeStart;
				int rowStart = buffer.length();
				builtRowStarts[rowIndex] = rowStart;
				char[] sourceRow = vb.charArray[r];
				if (preferLastDrawnVisibleRows
						&& drawnWindowBase >= 0
						&& r >= drawnWindowBase
						&& r < (drawnWindowBase + visibleRows)) {
					char[] drawnRow = terminalView.bridge.getLastDrawnCharRow(r);
					if (drawnRow != null) {
						sourceRow = drawnRow;
					}
				}

				if (sourceRow != null) {
					for (int c = 0; c < numCols; c++) {
						buffer.append(sourceRow[c]);
					}
				}

				while (buffer.length() > rowStart
						&& Character.isWhitespace(buffer.charAt(buffer.length() - 1))) {
					buffer.setLength(buffer.length() - 1);
				}

				builtRowLengths[rowIndex] = buffer.length() - rowStart;
				buffer.append('\n');
			}
			return new BufferTextSnapshot(buffer.toString(), builtRowStarts, builtRowLengths);
		}
	}

	private void replaceBufferTextSnapshot(BufferTextSnapshot snapshot) {
		rowStarts = snapshot.rowStarts;
		rowLengths = snapshot.rowLengths;
		setText(snapshot.text);
	}

	private void appendBufferTextSnapshot(BufferTextSnapshot snapshot) {
		int[] currentRowStarts = rowStarts;
		int[] currentRowLengths = rowLengths;
		CharSequence currentText = getText();
		int baseOffset = currentText != null ? currentText.length() : 0;

		int[] mergedRowStarts = Arrays.copyOf(currentRowStarts, currentRowStarts.length + snapshot.rowStarts.length);
		int[] mergedRowLengths = Arrays.copyOf(currentRowLengths, currentRowLengths.length + snapshot.rowLengths.length);
		for (int i = 0; i < snapshot.rowStarts.length; i++) {
			mergedRowStarts[currentRowStarts.length + i] = baseOffset + snapshot.rowStarts[i];
			mergedRowLengths[currentRowLengths.length + i] = snapshot.rowLengths[i];
		}
		rowStarts = mergedRowStarts;
		rowLengths = mergedRowLengths;
		append(snapshot.text);
	}

	private void recordSyncedViewportState(int windowBase, int screenBase) {
		lastSyncedDrawnWindowBase = windowBase;
		lastSyncedDrawnScreenBase = screenBase;
		lastSyncedDrawnViewportHash = terminalView.bridge.getLastDrawnViewportHash();
		lastSyncedDrawnFrameSerial = terminalView.bridge.getLastDrawnFrameSerial();
	}

	private static String sanitizeForLog(CharSequence text) {
		if (text == null) {
			return "";
		}
		String sanitized = text.toString()
				.replace('\n', ' ')
				.replace('\r', ' ')
				.replace('\u0000', ' ');
		if (sanitized.length() > 80) {
			return sanitized.substring(0, 80);
		}
		return sanitized;
	}

	private static String wordFromTextOffset(CharSequence text, int offset) {
		if (text == null || offset < 0 || offset >= text.length()) {
			return "";
		}

		int start = offset;
		int end = offset;
		while (start > 0) {
			char ch = text.charAt(start - 1);
			if (Character.isWhitespace(ch)) {
				break;
			}
			start--;
		}
		while (end < text.length()) {
			char ch = text.charAt(end);
			if (Character.isWhitespace(ch)) {
				break;
			}
			end++;
		}
		return sanitizeForLog(text.subSequence(start, end));
	}

	private static String lineFromTextLine(CharSequence text, Layout layout, int line) {
		if (text == null || layout == null || line < 0 || line >= layout.getLineCount()) {
			return "";
		}
		int start = layout.getLineStart(line);
		int end = layout.getLineEnd(line);
		if (start < 0 || end < start || start >= text.length()) {
			return "";
		}
		end = Math.min(end, text.length());
		return sanitizeForLog(text.subSequence(start, end));
	}

	private static String wordFromRow(char[] row, int col) {
		if (row == null || col < 0 || col >= row.length) {
			return "";
		}

		int start = col;
		int end = col;
		while (start > 0) {
			char ch = row[start - 1];
			if (ch == 0 || Character.isWhitespace(ch)) {
				break;
			}
			start--;
		}
		while (end < row.length) {
			char ch = row[end];
			if (ch == 0 || Character.isWhitespace(ch)) {
				break;
			}
			end++;
		}
		return sanitizeForLog(new String(row, start, end - start));
	}

	private static String contextFromRow(char[] row, int col) {
		if (row == null || col < 0 || col >= row.length) {
			return "";
		}
		int start = Math.max(0, col - DEBUG_CONTEXT_RADIUS);
		int end = Math.min(row.length, col + DEBUG_CONTEXT_RADIUS + 1);
		return sanitizeForLog(new String(row, start, end - start));
	}

	private static char sanitizeCopyChar(char ch) {
		if (!Character.isDefined(ch) || (Character.isISOControl(ch) && ch != '\t')) {
			return ' ';
		}
		return ch;
	}

	private static boolean isWordChar(char ch) {
		ch = sanitizeCopyChar(ch);
		return ch != ' ' && !Character.isWhitespace(ch);
	}

	private static String buildSelectedWord(char[] row, int left, int right) {
		if (row == null || left < 0 || right < left || right >= row.length) {
			return "";
		}
		StringBuilder selected = new StringBuilder(right - left + 1);
		for (int i = left; i <= right; i++) {
			selected.append(sanitizeCopyChar(row[i]));
		}
		int end = selected.length();
		while (end > 0 && Character.isWhitespace(selected.charAt(end - 1))) {
			end--;
		}
		return selected.substring(0, end);
	}

	private void updateSelectionPaintColors() {
		int baseColor = terminalView.bridge.color[terminalView.bridge.defaultFg];
		selectionFillPaint.setColor(Color.argb(96, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
		selectionHandlePaint.setColor(baseColor);
		selectionHandleStrokePaint.setColor(Color.argb(224, 0, 0, 0));
	}

	private float getHandleRadiusPx() {
		return Math.max(terminalView.bridge.charWidth * 0.55f, terminalView.bridge.charHeight * 0.32f);
	}

	private float getHandleStemHeightPx() {
		return Math.max(terminalView.bridge.charHeight * 0.55f, getHandleRadiusPx() * 1.1f);
	}

	private float getHandleHitRadiusPx() {
		return Math.max(getHandleRadiusPx() * 2.9f, terminalView.bridge.charWidth * 1.4f);
	}

	private void performSelectionHaptic() {
		terminalView.bridge.tryKeyVibrate();
	}

	private void resetHandleHapticState() {
		lastHandleHapticAbsRow = Integer.MIN_VALUE;
		lastHandleHapticCol = Integer.MIN_VALUE;
		lastHandleHapticUptime = 0L;
	}

	private void maybePerformHandleMoveHaptic(int absoluteRow, int col) {
		long now = SystemClock.uptimeMillis();
		if (absoluteRow == lastHandleHapticAbsRow && col == lastHandleHapticCol) {
			return;
		}
		if (lastHandleHapticUptime != 0L && (now - lastHandleHapticUptime) < HANDLE_HAPTIC_MIN_INTERVAL_MS) {
			lastHandleHapticAbsRow = absoluteRow;
			lastHandleHapticCol = col;
			return;
		}
		performSelectionHaptic();
		lastHandleHapticAbsRow = absoluteRow;
		lastHandleHapticCol = col;
		lastHandleHapticUptime = now;
	}

	private boolean hasCustomSelection() {
		return terminalView.bridge.isSelectingForCopy()
				&& selectionStartAbsRow >= 0
				&& selectionEndAbsRow >= 0
				&& selectionStartCol >= 0
				&& selectionEndCol >= 0;
	}

	public boolean hasActiveSelection() {
		return hasCustomSelection();
	}

	private static int compareSelectionPositions(int rowA, int colA, int rowB, int colB) {
		if (rowA != rowB) {
			return rowA < rowB ? -1 : 1;
		}
		if (colA == colB) {
			return 0;
		}
		return colA < colB ? -1 : 1;
	}

	private int getVisibleRows() {
		synchronized (terminalView.bridge.buffer) {
			return terminalView.bridge.getVDUBuffer().getRows();
		}
	}

	private int getColumns() {
		synchronized (terminalView.bridge.buffer) {
			return terminalView.bridge.getVDUBuffer().getColumns();
		}
	}

	private int getWindowBase() {
		synchronized (terminalView.bridge.buffer) {
			return terminalView.bridge.getVDUBuffer().getWindowBase();
		}
	}

	private int clampScreenRow(int screenRow) {
		int rows = getVisibleRows();
		if (rows <= 0) {
			return 0;
		}
		return Math.max(0, Math.min(screenRow, rows - 1));
	}

	private int clampColumn(int col) {
		int cols = getColumns();
		if (cols <= 0) {
			return 0;
		}
		return Math.max(0, Math.min(col, cols - 1));
	}

	private int getScreenRowForY(float y) {
		int charHeight = Math.max(1, terminalView.bridge.charHeight);
		return clampScreenRow((int) Math.floor(y / charHeight));
	}

	private int getColumnForX(float x) {
		int charWidth = Math.max(1, terminalView.bridge.charWidth);
		return clampColumn((int) Math.floor(x / charWidth));
	}

	private int getAbsoluteRowForScreenRow(int screenRow) {
		return getWindowBase() + clampScreenRow(screenRow);
	}

	private int getAbsoluteRowForY(float y) {
		return getAbsoluteRowForScreenRow(getScreenRowForY(y));
	}

	private int getTrimmedRowLength(char[] row) {
		if (row == null) {
			return 0;
		}
		int end = row.length;
		while (end > 0 && Character.isWhitespace(sanitizeCopyChar(row[end - 1]))) {
			end--;
		}
		return end;
	}

	private char[] getAbsoluteRowSnapshot(int absoluteRow) {
		if (absoluteRow < 0) {
			return null;
		}

		int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
		int drawnRows = getVisibleRows();
		if (drawnWindowBase >= 0
				&& absoluteRow >= drawnWindowBase
				&& absoluteRow < (drawnWindowBase + drawnRows)) {
			char[] drawnRow = terminalView.bridge.getLastDrawnCharRow(absoluteRow);
			if (drawnRow != null) {
				return drawnRow;
			}
		}

		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			if (absoluteRow >= vb.getBufferSize()) {
				return null;
			}
			char[] row = vb.charArray[absoluteRow];
			return row == null ? null : row.clone();
		}
	}

	private void setSelectionRangeOrdered(int startAbsRow, int startCol, int endAbsRow, int endCol) {
		if (compareSelectionPositions(startAbsRow, startCol, endAbsRow, endCol) <= 0) {
			selectionStartAbsRow = startAbsRow;
			selectionStartCol = startCol;
			selectionEndAbsRow = endAbsRow;
			selectionEndCol = endCol;
		} else {
			selectionStartAbsRow = endAbsRow;
			selectionStartCol = endCol;
			selectionEndAbsRow = startAbsRow;
			selectionEndCol = startCol;
		}
		currentSelection = buildSelectedText();
		invalidate();
	}

	private int getHandleAbsRow(int handle) {
		return handle == HANDLE_START ? selectionStartAbsRow : selectionEndAbsRow;
	}

	private int getHandleCol(int handle) {
		return handle == HANDLE_START ? selectionStartCol : selectionEndCol;
	}

	private String buildSelectedText() {
		if (!hasCustomSelection()) {
			return "";
		}

		StringBuilder selected = new StringBuilder();
		int cols = Math.max(1, getColumns());
		for (int absoluteRow = selectionStartAbsRow; absoluteRow <= selectionEndAbsRow; absoluteRow++) {
			char[] row = getAbsoluteRowSnapshot(absoluteRow);
			if (row == null) {
				row = new char[cols];
				Arrays.fill(row, ' ');
			}

			int startCol = absoluteRow == selectionStartAbsRow ? selectionStartCol : 0;
			int endCol = absoluteRow == selectionEndAbsRow ? selectionEndCol : cols - 1;
			startCol = Math.max(0, Math.min(startCol, row.length - 1));
			endCol = Math.max(startCol, Math.min(endCol, row.length - 1));

			int lineStart = selected.length();
			for (int col = startCol; col <= endCol; col++) {
				selected.append(sanitizeCopyChar(row[col]));
			}
			while (selected.length() > lineStart
					&& Character.isWhitespace(selected.charAt(selected.length() - 1))) {
				selected.setLength(selected.length() - 1);
			}
			if (absoluteRow < selectionEndAbsRow) {
				selected.append('\n');
			}
		}
		return selected.toString();
	}

	private int[] findWordBounds(char[] row, int col) {
		int wordCol = findWordColumn(row, col);
		if (row == null || wordCol < 0) {
			return null;
		}

		int left = wordCol;
		while (left > 0 && isWordChar(row[left - 1])) {
			left--;
		}

		int right = wordCol;
		while ((right + 1) < row.length && isWordChar(row[right + 1])) {
			right++;
		}

		return new int[] { left, right };
	}

	private static final class HandleGeometry {
		final float stemX;
		final float stemTopY;
		final float stemBottomY;
		final float centerX;
		final float centerY;

		HandleGeometry(float stemX, float stemTopY, float stemBottomY, float centerX, float centerY) {
			this.stemX = stemX;
			this.stemTopY = stemTopY;
			this.stemBottomY = stemBottomY;
			this.centerX = centerX;
			this.centerY = centerY;
		}
	}

	private HandleGeometry getHandleGeometry(int handle) {
		if (!hasCustomSelection()) {
			return null;
		}

		int absoluteRow = handle == HANDLE_START ? selectionStartAbsRow : selectionEndAbsRow;
		int columnBoundary = handle == HANDLE_START ? selectionStartCol : (selectionEndCol + 1);
		int screenRow = absoluteRow - getWindowBase();
		if (screenRow < 0 || screenRow >= getVisibleRows()) {
			return null;
		}

		float charWidth = Math.max(1, terminalView.bridge.charWidth);
		float charHeight = Math.max(1, terminalView.bridge.charHeight);
		float stemX = columnBoundary * charWidth;
		float stemTopY = (screenRow + 1) * charHeight;
		float stemBottomY = stemTopY + getHandleStemHeightPx();
		float knobOffset = getHandleRadiusPx() * 0.82f;
		float centerX = stemX + (handle == HANDLE_START ? -knobOffset : knobOffset);
		float centerY = stemBottomY + (getHandleRadiusPx() * 0.78f);
		return new HandleGeometry(stemX, stemTopY, stemBottomY, centerX, centerY);
	}

	private int findTouchedHandle(float x, float y) {
		HandleGeometry start = getHandleGeometry(HANDLE_START);
		HandleGeometry end = getHandleGeometry(HANDLE_END);
		float hitRadius = getHandleHitRadiusPx();
		float hitRadiusSquared = hitRadius * hitRadius;
		float startDistance = Float.MAX_VALUE;
		float endDistance = Float.MAX_VALUE;
		if (start != null) {
			float dx = x - start.centerX;
			float dy = y - start.centerY;
			startDistance = (dx * dx) + (dy * dy);
		}
		if (end != null) {
			float dx = x - end.centerX;
			float dy = y - end.centerY;
			endDistance = (dx * dx) + (dy * dy);
		}
		if (startDistance <= hitRadiusSquared || endDistance <= hitRadiusSquared) {
			return startDistance <= endDistance ? HANDLE_START : HANDLE_END;
		}
		return HANDLE_NONE;
	}

	public float getStartHandleCenterXForTest() {
		HandleGeometry geometry = getHandleGeometry(HANDLE_START);
		return geometry != null ? geometry.centerX : -1f;
	}

	public float getStartHandleCenterYForTest() {
		HandleGeometry geometry = getHandleGeometry(HANDLE_START);
		return geometry != null ? geometry.centerY : -1f;
	}

	public float getEndHandleCenterXForTest() {
		HandleGeometry geometry = getHandleGeometry(HANDLE_END);
		return geometry != null ? geometry.centerX : -1f;
	}

	public float getEndHandleCenterYForTest() {
		HandleGeometry geometry = getHandleGeometry(HANDLE_END);
		return geometry != null ? geometry.centerY : -1f;
	}

	private char[] getVisibleRowSnapshot(int screenRow) {
		if (screenRow < 0) {
			return null;
		}

		int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
		if (drawnWindowBase >= 0) {
			char[] drawnRow = terminalView.bridge.getLastDrawnCharRow(drawnWindowBase + screenRow);
			if (drawnRow != null) {
				return drawnRow;
			}
		}

		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			int absoluteRow = vb.getWindowBase() + screenRow;
			if (absoluteRow < 0 || absoluteRow >= vb.getBufferSize()) {
				return null;
			}
			char[] row = vb.charArray[absoluteRow];
			return row == null ? null : row.clone();
		}
	}

	private int findWordColumn(char[] row, int col) {
		if (row == null || row.length == 0) {
			return -1;
		}

		int safeCol = Math.max(0, Math.min(col, row.length - 1));
		if (isWordChar(row[safeCol])) {
			return safeCol;
		}

		for (int distance = 1; distance < row.length; distance++) {
			int right = safeCol + distance;
			if (right < row.length && isWordChar(row[right])) {
				return right;
			}

			int left = safeCol - distance;
			if (left >= 0 && isWordChar(row[left])) {
				return left;
			}

			if (left < 0 && right >= row.length) {
				break;
			}
		}

		return -1;
	}

	private boolean updateCustomSelectionAt(float x, float y) {
		if (!hasCustomSelection()) {
			return false;
		}
		return updateHandleSelectionAt(draggingHandle == HANDLE_NONE ? HANDLE_END : draggingHandle, x, y);
	}

	private boolean updateHandleSelectionAt(int handle, float x, float y) {
		if (!hasCustomSelection()) {
			return false;
		}

		int previousAbsRow = getHandleAbsRow(handle);
		int previousCol = getHandleCol(handle);
		int absoluteRow = getAbsoluteRowForY(y);
		int col = getColumnForX(x);
		if (handle == HANDLE_START) {
			selectionStartAbsRow = absoluteRow;
			selectionStartCol = col;
		} else if (handle == HANDLE_END) {
			selectionEndAbsRow = absoluteRow;
			selectionEndCol = col;
		} else {
			return false;
		}

		if (compareSelectionPositions(selectionStartAbsRow, selectionStartCol, selectionEndAbsRow, selectionEndCol) > 0) {
			int tmpRow = selectionStartAbsRow;
			int tmpCol = selectionStartCol;
			selectionStartAbsRow = selectionEndAbsRow;
			selectionStartCol = selectionEndCol;
			selectionEndAbsRow = tmpRow;
			selectionEndCol = tmpCol;
			draggingHandle = handle == HANDLE_START ? HANDLE_END : HANDLE_START;
			handle = draggingHandle;
		}

		currentSelection = buildSelectedText();
		int currentAbsRow = getHandleAbsRow(handle);
		int currentCol = getHandleCol(handle);
		if (currentAbsRow != previousAbsRow || currentCol != previousCol) {
			maybePerformHandleMoveHaptic(currentAbsRow, currentCol);
		}
		invalidate();
		return true;
	}

	private boolean beginCustomSelectionAt(float x, float y) {
		refreshTextFromBuffer();
		int screenRow = getScreenRowForY(y);
		int absoluteRow = getAbsoluteRowForScreenRow(screenRow);
		int col = getColumnForX(x);
		char[] row = getAbsoluteRowSnapshot(absoluteRow);
		int[] wordBounds = findWordBounds(row, col);
		if (wordBounds == null) {
			return false;
		}

		terminalView.bridge.setSelectingForCopy(true);
		terminalView.bridge.getSelectionArea().reset();
		terminalView.viewPager.setPagingEnabled(false);
		setSelectionRangeOrdered(absoluteRow, wordBounds[0], absoluteRow, wordBounds[1]);
		resetHandleHapticState();
		performSelectionHaptic();

		if (selectionActionMode == null) {
			selectionActionMode = startActionMode(new TextSelectionActionModeCallback());
		}
		return selectionActionMode != null || currentSelection.length() > 0;
	}

	private void clearCustomSelection() {
		terminalView.bridge.setSelectingForCopy(false);
		terminalView.bridge.getSelectionArea().reset();
		terminalView.viewPager.setPagingEnabled(true);
		selectionStartAbsRow = -1;
		selectionStartCol = -1;
		selectionEndAbsRow = -1;
		selectionEndCol = -1;
		draggingHandle = HANDLE_NONE;
		resetHandleHapticState();
		invalidate();
		currentSelection = "";
		customLongPressSelectionStarted = false;
	}

	private void logSelectionSnapshot(String label, float x, float y) {
		if (!SelectionDebugLogger.isEnabled()) {
			return;
		}

		final int charWidth = Math.max(1, terminalView.bridge.charWidth);
		final int charHeight = Math.max(1, terminalView.bridge.charHeight);
		final int gridCol = (int) Math.floor(x / charWidth);
		final int gridRow = (int) Math.floor(y / charHeight);

		final int windowBase;
		final int screenBase;
		final int rows;
		final int cols;
		final int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
		final int drawnScreenBase = terminalView.bridge.getLastDrawnScreenBase();
		final int drawnHash = terminalView.bridge.getLastDrawnViewportHash();
		final long drawnSerial = terminalView.bridge.getLastDrawnFrameSerial();
		final char[] liveRow;
		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			windowBase = vb.getWindowBase();
			screenBase = vb.screenBase;
			rows = vb.getRows();
			cols = vb.getColumns();
			int absRow = windowBase + gridRow;
			if (absRow >= 0 && absRow < vb.getBufferSize()) {
				char[] row = vb.charArray[absRow];
				liveRow = row == null ? null : row.clone();
			} else {
				liveRow = null;
			}
		}

		final int liveAbsRow = windowBase + gridRow;
		final int drawnAbsRow = drawnWindowBase >= 0 ? drawnWindowBase + gridRow : -1;
		final char[] drawnRow = drawnAbsRow >= 0 ? terminalView.bridge.getLastDrawnCharRow(drawnAbsRow) : null;

		Layout layout = getLayout();
		CharSequence overlayText = getText();
		int offset = -1;
		int layoutLine = -1;
		int layoutCol = -1;
		char overlayChar = 0;
		String overlayWord = "";
		if (layout != null && overlayText != null && overlayText.length() > 0) {
			offset = getOffsetForPosition(x, y);
			if (offset >= 0 && offset < overlayText.length()) {
				layoutLine = layout.getLineForOffset(offset);
				layoutCol = offset - layout.getLineStart(layoutLine);
				overlayChar = overlayText.charAt(offset);
				overlayWord = wordFromTextOffset(overlayText, offset);
			}
		}

		char liveChar = (liveRow != null && gridCol >= 0 && gridCol < liveRow.length) ? liveRow[gridCol] : 0;
		char drawnChar = (drawnRow != null && gridCol >= 0 && gridCol < drawnRow.length) ? drawnRow[gridCol] : 0;
		String liveWord = wordFromRow(liveRow, gridCol);
		String drawnWord = wordFromRow(drawnRow, gridCol);

		if ("touch_down_after_refresh".equals(label)) {
			TapDebugState state = new TapDebugState();
			state.seq = ++debugTapSequence;
			state.x = x;
			state.y = y;
			state.gridRow = gridRow;
			state.gridCol = gridCol;
			state.layoutLine = layoutLine;
			state.layoutCol = layoutCol;
			state.liveAbsRow = liveAbsRow;
			state.drawnAbsRow = drawnAbsRow;
			state.overlayWord = overlayWord;
			state.liveWord = liveWord;
			state.drawnWord = drawnWord;
			lastTapDebugState = state;
		}

		TapDebugState tapState = lastTapDebugState;

		String message = label
				+ " x=" + x
				+ " y=" + y
				+ " gridRow=" + gridRow
				+ " gridCol=" + gridCol
				+ " rows=" + rows
				+ " cols=" + cols
				+ " overlayScrollY=" + getScrollY()
				+ " overlayLineHeight=" + getLineHeight()
				+ " overlayTranslationY=" + getTranslationY()
				+ " layoutLineCount=" + (layout != null ? layout.getLineCount() : -1)
				+ " layoutLine=" + layoutLine
				+ " layoutCol=" + layoutCol
				+ " offset=" + offset
				+ " overlayChar=" + (int) overlayChar
				+ " overlayWord=\"" + overlayWord + "\""
				+ " liveAbsRow=" + liveAbsRow
				+ " liveChar=" + (int) liveChar
				+ " liveWord=\"" + liveWord + "\""
				+ " liveContext=\"" + contextFromRow(liveRow, gridCol) + "\""
				+ " drawnAbsRow=" + drawnAbsRow
				+ " drawnChar=" + (int) drawnChar
				+ " drawnWord=\"" + drawnWord + "\""
				+ " drawnContext=\"" + contextFromRow(drawnRow, gridCol) + "\""
				+ " windowBase=" + windowBase
				+ " screenBase=" + screenBase
				+ " drawnWindowBase=" + drawnWindowBase
				+ " drawnScreenBase=" + drawnScreenBase
				+ " drawnHash=" + drawnHash
				+ " drawnSerial=" + drawnSerial
				+ " selStart=" + getSelectionStart()
				+ " selEnd=" + getSelectionEnd()
				+ " tapSeq=" + (tapState != null ? tapState.seq : -1)
				+ " logFile=" + SelectionDebugLogger.getLogFilePath(getContext());
		SelectionDebugLogger.log(getContext(), message);
	}

	private void ensureLayoutReadyForSelection() {
		if (getWidth() <= 0 || getHeight() <= 0) {
			return;
		}

		final int widthSpec = MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY);
		final int heightSpec = MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.EXACTLY);
		measure(widthSpec, heightSpec);
		layout(getLeft(), getTop(), getLeft() + getMeasuredWidth(), getTop() + getMeasuredHeight());
	}

	public void refreshTextFromBuffer() {
		final int windowBase;
		final int screenBase;
		final int numRows;
		final int visibleRows;
		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			numRows = vb.getBufferSize();
			visibleRows = vb.getRows();
			oldBufferHeight = numRows;
			screenBase = vb.screenBase;
			oldScreenBase = screenBase;
			windowBase = vb.getWindowBase();
		}

		oldScrollY = 0;
		textRowBase = windowBase;
		replaceBufferTextSnapshot(buildBufferTextSnapshot(windowBase, windowBase + visibleRows, true));

		// Try to apply the scroll immediately so that hit-testing is correct even before the next
		// draw pass (e.g., during fast IME hide/show interactions). We also keep oldScrollY so
		// onPreDraw can re-apply once layout is ready.
		super.scrollTo(0, oldScrollY);
		ensureLayoutReadyForSelection();
		recordSyncedViewportState(windowBase, screenBase);
		if (SelectionDebugLogger.isEnabled()) {
			SelectionDebugLogger.log(getContext(),
					"refresh_text windowBase=" + windowBase
							+ " screenBase=" + screenBase
							+ " rows=" + numRows
							+ " visibleRows=" + visibleRows
							+ " textRowBase=" + textRowBase
							+ " textLength=" + (getText() != null ? getText().length() : -1)
							+ " scrollY=" + getScrollY());
		}
	}

	/**
	 * If there is a new line in the buffer, add an empty line
	 * in this TextView, so that selection seems to move up with the
	 * rest of the buffer.
	 */
	public void onBufferChanged() {
		// While the user is holding their finger down waiting for a long-press selection to begin,
		// avoid mutating the overlay (append/scroll). This can cancel long-press selection under
		// continuous output.
		if (isTouchDown && selectionActionMode == null) {
			return;
		}

		final int numRows;
		final int screenBase;
		synchronized (terminalView.bridge.buffer) {
			VDUBuffer vb = terminalView.bridge.getVDUBuffer();
			numRows = vb.getBufferSize();
			screenBase = vb.screenBase;
		}

		oldBufferHeight = numRows;
		oldScreenBase = screenBase;
		if (SelectionDebugLogger.isEnabled()) {
			SelectionDebugLogger.log(getContext(),
					"buffer_changed_refresh numRows=" + numRows
							+ " screenBase=" + screenBase);
		}
		refreshTextFromBuffer();
	}

	private boolean shouldRefreshForCurrentDrawnViewport() {
		final int drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
		final int drawnScreenBase = terminalView.bridge.getLastDrawnScreenBase();
		final long drawnFrameSerial = terminalView.bridge.getLastDrawnFrameSerial();
		if (drawnWindowBase < 0 || drawnFrameSerial < 0L) {
			return false;
		}
		if (drawnFrameSerial == lastSyncedDrawnFrameSerial) {
			return false;
		}
		if (drawnWindowBase != lastSyncedDrawnWindowBase || drawnScreenBase != lastSyncedDrawnScreenBase) {
			return false;
		}
		return terminalView.bridge.getLastDrawnViewportHash() != lastSyncedDrawnViewportHash;
	}

	public void onTerminalFrameDrawn() {
		if (selectionActionMode != null || isTouchDown) {
			return;
		}
		CharSequence text = getText();
		boolean overlayTextMissing = text == null || text.length() == 0;
		if ((!overlayTextMissing && !shouldRefreshForCurrentDrawnViewport()) || syncFromDrawQueued) {
			return;
		}
		if (SelectionDebugLogger.isEnabled()) {
			SelectionDebugLogger.log(getContext(),
					"frame_sync_queued overlayTextMissing=" + overlayTextMissing
							+ " drawnWindowBase=" + terminalView.bridge.getLastDrawnWindowBase()
							+ " drawnScreenBase=" + terminalView.bridge.getLastDrawnScreenBase()
							+ " drawnHash=" + terminalView.bridge.getLastDrawnViewportHash()
							+ " drawnSerial=" + terminalView.bridge.getLastDrawnFrameSerial());
		}
		syncFromDrawQueued = true;
		post(syncFromLastDrawnFrameRunnable);
	}

	@Override
	public boolean onPreDraw() {
		boolean superResult = super.onPreDraw();

		if (oldScrollY >= 0) {
			super.scrollTo(0, oldScrollY);
			oldScrollY = -1;
		} else if (getScrollY() != 0) {
			super.scrollTo(0, 0);
		}

		return superResult;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		if (!hasCustomSelection()) {
			return;
		}

		updateSelectionPaintColors();

		int windowBase = getWindowBase();
		int charWidth = Math.max(1, terminalView.bridge.charWidth);
		int charHeight = Math.max(1, terminalView.bridge.charHeight);
		int visibleRows = getVisibleRows();
		int cols = Math.max(1, getColumns());

		for (int absoluteRow = selectionStartAbsRow; absoluteRow <= selectionEndAbsRow; absoluteRow++) {
			int screenRow = absoluteRow - windowBase;
			if (screenRow < 0 || screenRow >= visibleRows) {
				continue;
			}

			char[] row = getAbsoluteRowSnapshot(absoluteRow);
			int rowLength = Math.max(0, getTrimmedRowLength(row) - 1);
			int left = absoluteRow == selectionStartAbsRow ? selectionStartCol : 0;
			int right;
			if (absoluteRow == selectionEndAbsRow) {
				right = selectionEndCol;
			} else if (rowLength >= left) {
				right = rowLength;
			} else {
				right = cols - 1;
			}

			left = Math.max(0, Math.min(left, cols - 1));
			right = Math.max(left, Math.min(right, cols - 1));

			float top = screenRow * charHeight;
			float bottom = (screenRow + 1) * charHeight;
			float drawLeft = left * charWidth;
			float drawRight = (right + 1) * charWidth;
			canvas.drawRect(drawLeft, top, drawRight, bottom, selectionFillPaint);
		}

		drawHandle(canvas, HANDLE_START);
		drawHandle(canvas, HANDLE_END);
	}

	private void drawHandle(Canvas canvas, int handle) {
		HandleGeometry geometry = getHandleGeometry(handle);
		if (geometry == null) {
			return;
		}

		float radius = getHandleRadiusPx();
		float stemWidth = Math.max(terminalView.bridge.charWidth * 0.16f, 5f);
		float cornerRadius = stemWidth / 2f;
		float bridgeRadius = Math.max(stemWidth * 0.55f, 3f);

		tempHandleRect.set(
				geometry.stemX - (stemWidth / 2f),
				geometry.stemTopY,
				geometry.stemX + (stemWidth / 2f),
				geometry.stemBottomY);
		canvas.drawRoundRect(tempHandleRect, cornerRadius, cornerRadius, selectionHandlePaint);
		canvas.drawRoundRect(tempHandleRect, cornerRadius, cornerRadius, selectionHandleStrokePaint);

		canvas.drawCircle(geometry.stemX, geometry.stemBottomY, bridgeRadius, selectionHandlePaint);
		canvas.drawCircle(geometry.stemX, geometry.stemBottomY, bridgeRadius, selectionHandleStrokePaint);

		canvas.drawCircle(geometry.centerX, geometry.centerY, radius, selectionHandlePaint);
		canvas.drawCircle(geometry.centerX, geometry.centerY, radius, selectionHandleStrokePaint);
	}

	private int getOffsetForGridPosition(float x, float y) {
		CharSequence text = getText();
		if (text == null || text.length() == 0) {
			return -1;
		}

		int[] currentRowStarts = rowStarts;
		int[] currentRowLengths = rowLengths;
		if (currentRowStarts.length == 0 || currentRowLengths.length == 0) {
			return -1;
		}

		final int charWidth = Math.max(1, terminalView.bridge.charWidth);
		final int charHeight = Math.max(1, terminalView.bridge.charHeight);
		final int gridCol = Math.max(0, (int) Math.floor(x / charWidth));
		final int gridRow = Math.max(0, (int) Math.floor(y / charHeight));

		final int visibleRows;
		synchronized (terminalView.bridge.buffer) {
			visibleRows = terminalView.bridge.getVDUBuffer().getRows();
		}

		int clampedGridRow = gridRow;
		if (visibleRows > 0 && clampedGridRow >= visibleRows) {
			clampedGridRow = visibleRows - 1;
		}

		if (clampedGridRow < 0 || clampedGridRow >= currentRowStarts.length) {
			return -1;
		}

		int rowStart = currentRowStarts[clampedGridRow];
		int rowLength = currentRowLengths[clampedGridRow];
		if (rowStart < 0 || rowStart > text.length()) {
			return -1;
		}
		if (rowLength <= 0) {
			return Math.min(rowStart, Math.max(0, text.length() - 1));
		}

		int clampedCol = Math.min(gridCol, rowLength - 1);
		int offset = rowStart + clampedCol;
		if (offset < 0) {
			return 0;
		}
		return Math.min(offset, text.length() - 1);
	}

	@Override
	public int getOffsetForPosition(float x, float y) {
		int offset = getOffsetForGridPosition(x, y);
		if (offset >= 0) {
			return offset;
		}
		return super.getOffsetForPosition(x, y);
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
		if (hasCustomSelection()) {
			currentSelection = buildSelectedText();
		}
		if (SelectionDebugLogger.isEnabled()) {
			SelectionDebugLogger.log(getContext(),
					"copy_selection text=\"" + sanitizeForLog(currentSelection) + "\""
							+ " selStart=" + getSelectionStart()
							+ " selEnd=" + getSelectionEnd());
		}
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
		if (!terminalView.bridge.isSelectingForCopy()) {
			updateCurrentSelection(selStart, selEnd);
		}
		if (SelectionDebugLogger.isEnabled()) {
			Layout layout = getLayout();
			CharSequence text = getText();
			int start = Math.min(selStart, selEnd);
			int end = Math.max(selStart, selEnd);
			int startLine = (layout != null && start >= 0 && start < text.length()) ? layout.getLineForOffset(start) : -1;
			int endOffset = Math.max(start, end - 1);
			int endLine = (layout != null && endOffset >= 0 && endOffset < text.length()) ? layout.getLineForOffset(endOffset) : -1;
			int startCol = (layout != null && startLine >= 0) ? start - layout.getLineStart(startLine) : -1;
			int endCol = (layout != null && endLine >= 0) ? endOffset - layout.getLineStart(endLine) : -1;
			final int windowBase;
			final int drawnWindowBase;
			synchronized (terminalView.bridge.buffer) {
				windowBase = terminalView.bridge.buffer.getWindowBase();
				drawnWindowBase = terminalView.bridge.getLastDrawnWindowBase();
			}
			final int selectedAbsRow = startLine >= 0 ? textRowBase + startLine : -1;
			TapDebugState tapState = lastTapDebugState;
			SelectionDebugLogger.log(getContext(),
					"selection_changed selStart=" + selStart
							+ " selEnd=" + selEnd
							+ " text=\"" + sanitizeForLog(currentSelection) + "\""
							+ " startLine=" + startLine
							+ " endLine=" + endLine
							+ " startCol=" + startCol
							+ " endCol=" + endCol
							+ " selectedWord=\"" + wordFromTextOffset(text, start) + "\""
							+ " selectedLineText=\"" + lineFromTextLine(text, layout, startLine) + "\""
							+ " selectedScreenRow=" + startLine
							+ " selectedAbsRow=" + selectedAbsRow
							+ " selectedDrawnScreenRow=" + (startLine >= 0 && drawnWindowBase >= 0 ? selectedAbsRow - drawnWindowBase : -1)
							+ " tapSeq=" + (tapState != null ? tapState.seq : -1)
							+ " tapGridRow=" + (tapState != null ? tapState.gridRow : -1)
							+ " tapGridCol=" + (tapState != null ? tapState.gridCol : -1)
							+ " tapLayoutLine=" + (tapState != null ? tapState.layoutLine : -1)
							+ " tapLiveAbsRow=" + (tapState != null ? tapState.liveAbsRow : -1)
							+ " tapDrawnAbsRow=" + (tapState != null ? tapState.drawnAbsRow : -1)
							+ " tapOverlayWord=\"" + (tapState != null ? tapState.overlayWord : "") + "\""
							+ " tapLiveWord=\"" + (tapState != null ? tapState.liveWord : "") + "\""
							+ " tapDrawnWord=\"" + (tapState != null ? tapState.drawnWord : "") + "\""
							+ " deltaFromTapLayoutLine=" + (tapState != null && startLine >= 0 && tapState.layoutLine >= 0 ? (startLine - tapState.layoutLine) : Integer.MIN_VALUE)
							+ " deltaFromTapLiveAbsRow=" + (tapState != null && startLine >= 0 && tapState.liveAbsRow >= 0 ? (startLine - tapState.liveAbsRow) : Integer.MIN_VALUE)
							+ " deltaFromTapDrawnAbsRow=" + (tapState != null && startLine >= 0 && tapState.drawnAbsRow >= 0 ? (startLine - tapState.drawnAbsRow) : Integer.MIN_VALUE));
		}
		super.onSelectionChanged(selStart, selEnd);
	}

	@Override
	public void scrollTo(int x, int y) {
		// The overlay mirrors only the visible viewport, so it should never maintain its own
		// vertical scroll position.
		super.scrollTo(0, 0);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		boolean terminalViewHandled = false;
		boolean consumeTouch = true;

		if (event.getAction() == MotionEvent.ACTION_DOWN) {
			isTouchDown = true;
			touchDownUptimeMillis = SystemClock.uptimeMillis();
			touchDownX = event.getX();
			touchDownY = event.getY();
			customLongPressSelectionStarted = false;
			removeCallbacks(beginCustomSelectionRunnable);
			requestFocus();
			if (selectionActionMode != null) {
				int touchedHandle = findTouchedHandle(event.getX(), event.getY());
				if (touchedHandle != HANDLE_NONE) {
					draggingHandle = touchedHandle;
					customLongPressSelectionStarted = true;
					resetHandleHapticState();
					maybePerformHandleMoveHaptic(getHandleAbsRow(touchedHandle), getHandleCol(touchedHandle));
					return true;
				}
				closeSelectionActionMode();
			}
			draggingHandle = HANDLE_NONE;
			maybeBeginFreezeWindowBase();
			// Selection may be beginning. Sync the TextView with the buffer.
			refreshTextFromBuffer();
			logSelectionSnapshot("touch_down_after_refresh", event.getX(), event.getY());
			postDelayed(beginCustomSelectionRunnable, ViewConfiguration.getLongPressTimeout());
			terminalViewHandled = terminalView.onTouchEvent(event);
		} else if (event.getAction() == MotionEvent.ACTION_MOVE) {
			if (draggingHandle != HANDLE_NONE) {
				updateHandleSelectionAt(draggingHandle, event.getX(), event.getY());
				return true;
			}
			float dx = event.getX() - touchDownX;
			float dy = event.getY() - touchDownY;
			int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
			if (!customLongPressSelectionStarted
					&& ((dx * dx) + (dy * dy)) > (touchSlop * touchSlop)) {
				removeCallbacks(beginCustomSelectionRunnable);
			}
			if (selectionActionMode != null || customLongPressSelectionStarted) {
				return true;
			}
			terminalViewHandled = terminalView.onTouchEvent(event);
		} else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
			removeCallbacks(beginCustomSelectionRunnable);
			logSelectionSnapshot(event.getAction() == MotionEvent.ACTION_UP ? "touch_up" : "touch_cancel",
					event.getX(), event.getY());
			isTouchDown = false;
			final long heldMillis = (touchDownUptimeMillis > 0L)
					? (SystemClock.uptimeMillis() - touchDownUptimeMillis)
					: 0L;
			touchDownUptimeMillis = 0L;
			if (draggingHandle != HANDLE_NONE) {
				draggingHandle = HANDLE_NONE;
				return true;
			}
			// If selection never started, release any freeze and return to bottom so auto-scroll
			// resumes normally.
			if (selectionActionMode == null) {
				// Under continuous output / heavy UI load, the framework can be slow to create the
				// selection ActionMode. If the user held long enough to be a long-press attempt, delay
				// unfreezing briefly so selection has a chance to start before we snap back to bottom.
				if (heldMillis >= ViewConfiguration.getLongPressTimeout()) {
					postDelayed(new Runnable() {
						@Override
						public void run() {
							if (selectionActionMode == null) {
								unfreezeWindowBaseIfNeeded();
							}
						}
					}, 200L);
				} else {
					unfreezeWindowBaseIfNeeded();
				}
			} else {
				return true;
			}
			super.scrollTo(0, 0);
			terminalViewHandled = terminalView.onTouchEvent(event);
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH &&
				MotionEventCompat.getSource(event) == InputDevice.SOURCE_MOUSE) {
			consumeTouch = false;
			if (onMouseEvent(event, terminalView.bridge)) {
				return true;
			}
			terminalView.viewPager.setPagingEnabled(true);
		} else {
			terminalViewHandled = terminalView.onTouchEvent(event);
		}

		boolean handled = consumeTouch || terminalViewHandled || customLongPressSelectionStarted || super.onTouchEvent(event);
		if (SelectionDebugLogger.isEnabled() && event.getAction() == MotionEvent.ACTION_DOWN) {
			SelectionDebugLogger.log(getContext(),
					"touch_down_result handled=" + handled
							+ " terminalViewHandled=" + terminalViewHandled
							+ " actionModeActive=" + (selectionActionMode != null));
		}
		return handled;
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
		// This overlay commonly becomes the focused view. Treat it as a text editor so IMEs use
		// our InputConnection and can support behaviors like long-press key repeat (e.g. delete).
		// Cursor visibility is explicitly disabled in the constructor.
		return true;
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
			clearCustomSelection();
			unfreezeWindowBaseIfNeeded();
		}
	}
}
