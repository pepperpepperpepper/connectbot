/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007 Kenny Root, Jeffrey Sharkey
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.connectbot.bean.HostBean;
import org.connectbot.mock.NullTransport;
import org.connectbot.util.HostDatabase;
import org.connectbot.util.PubkeyDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;

import android.preference.PreferenceManager;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class TerminalKeyListenerXtermKeysTest {
	public static class TestTerminalManager extends TerminalManager {
		@Override
		public void onCreate() {
			prefs = PreferenceManager.getDefaultSharedPreferences(this);
			res = getResources();
			hostdb = HostDatabase.get(this);
			colordb = HostDatabase.get(this);
			pubkeydb = PubkeyDatabase.get(this);
		}
	}

	private static class RecordingTransport extends NullTransport {
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();

		public RecordingTransport(HostBean host, TerminalBridge bridge, TerminalManager manager) {
			super(host, bridge, manager);
		}

		public String drainUtf8() throws IOException {
			String s = output.toString("UTF-8");
			output.reset();
			return s;
		}

		@Override
		public void connect() {
		}

		@Override
		public int read(byte[] buffer, int offset, int length) {
			return -1;
		}

		@Override
		public void write(byte[] buffer) throws IOException {
			output.write(buffer);
		}

		@Override
		public void write(int c) {
			output.write(c);
		}
	}

	private static HostBean createHost() {
		HostBean host = new HostBean();
		host.setEncoding("UTF-8");
		return host;
	}

	@Before
	public void resetGlobalHardwareCtrlState() {
		TerminalKeyListener.resetGlobalHardwareCtrlDown();
	}

	@Test
	public void ctrlLeftArrowSendsXtermModifiedSequence() throws IOException {
		TerminalManager manager = Robolectric.buildService(TestTerminalManager.class).create().get();
		HostBean host = createHost();
		TerminalBridge bridge = new TerminalBridge(manager, host);

		RecordingTransport transport = new RecordingTransport(host, bridge, manager);
		bridge.transport = transport;

		TerminalKeyListener keyListener = bridge.getKeyHandler();
		View v = new View(ApplicationProvider.getApplicationContext());

		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_DPAD_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_CTRL_ON));

		assertEquals("\u001b[1;5D", transport.drainUtf8());
	}

	@Test
	public void ctrlLeftArrowSendsXtermModifiedSequenceWhenCtrlMetaStateIsMissing() throws IOException {
		TerminalManager manager = Robolectric.buildService(TestTerminalManager.class).create().get();
		HostBean host = createHost();
		TerminalBridge bridge = new TerminalBridge(manager, host);

		RecordingTransport transport = new RecordingTransport(host, bridge, manager);
		bridge.transport = transport;

		TerminalKeyListener keyListener = bridge.getKeyHandler();
		View v = new View(ApplicationProvider.getApplicationContext());

		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_CTRL_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0));
		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_DPAD_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, 0));

		assertEquals("\u001b[1;5D", transport.drainUtf8());
	}

	@Test
	public void ctrlLeftArrowUsesGlobalCtrlStateWhenCtrlKeyEventIsNotDeliveredToListener() throws IOException {
		TerminalManager manager = Robolectric.buildService(TestTerminalManager.class).create().get();
		HostBean host = createHost();
		TerminalBridge bridge = new TerminalBridge(manager, host);

		RecordingTransport transport = new RecordingTransport(host, bridge, manager);
		bridge.transport = transport;

		TerminalKeyListener keyListener = bridge.getKeyHandler();
		View v = new View(ApplicationProvider.getApplicationContext());

		TerminalKeyListener.updateGlobalHardwareCtrlDownFromKeyEvent(
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0));
		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_DPAD_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, 0));

		assertEquals("\u001b[1;5D", transport.drainUtf8());
	}

	@Test
	public void softCtrlTapThenLeftArrowSendsXtermModifiedSequenceWhenCtrlMetaStateIsMissing() throws IOException {
		TerminalManager manager = Robolectric.buildService(TestTerminalManager.class).create().get();
		HostBean host = createHost();
		TerminalBridge bridge = new TerminalBridge(manager, host);

		RecordingTransport transport = new RecordingTransport(host, bridge, manager);
		bridge.transport = transport;

		TerminalKeyListener keyListener = bridge.getKeyHandler();
		View v = new View(ApplicationProvider.getApplicationContext());

		final int softFlags = KeyEvent.FLAG_SOFT_KEYBOARD;
		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_CTRL_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, 0, 0, softFlags, 0));
		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_CTRL_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, 0, 0, softFlags, 0));
		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_DPAD_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, 0));

		assertEquals("\u001b[1;5D", transport.drainUtf8());
	}

	@Test
	public void ctrlTapUsesGlobalOneShotWhenCtrlKeyEventIsNotDeliveredToListener() throws IOException {
		TerminalManager manager = Robolectric.buildService(TestTerminalManager.class).create().get();
		HostBean host = createHost();
		TerminalBridge bridge = new TerminalBridge(manager, host);

		RecordingTransport transport = new RecordingTransport(host, bridge, manager);
		bridge.transport = transport;

		TerminalKeyListener keyListener = bridge.getKeyHandler();
		View v = new View(ApplicationProvider.getApplicationContext());

		final int deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD;
		TerminalKeyListener.updateGlobalHardwareCtrlDownFromKeyEvent(
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, deviceId, 0, 0, 0));
		TerminalKeyListener.updateGlobalHardwareCtrlDownFromKeyEvent(
				new KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, deviceId, 0, 0, 0));

		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_DPAD_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, 0));

		assertEquals("\u001b[1;5D", transport.drainUtf8());
	}

	@Test
	public void altLeftArrowSendsXtermModifiedSequence() throws IOException {
		TerminalManager manager = Robolectric.buildService(TestTerminalManager.class).create().get();
		HostBean host = createHost();
		TerminalBridge bridge = new TerminalBridge(manager, host);

		RecordingTransport transport = new RecordingTransport(host, bridge, manager);
		bridge.transport = transport;

		TerminalKeyListener keyListener = bridge.getKeyHandler();
		View v = new View(ApplicationProvider.getApplicationContext());

		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_DPAD_LEFT,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_ALT_ON));

		assertEquals("\u001b[1;3D", transport.drainUtf8());
	}

	@Test
	public void shiftTabSendsBackTab() throws IOException {
		TerminalManager manager = Robolectric.buildService(TestTerminalManager.class).create().get();
		HostBean host = createHost();
		TerminalBridge bridge = new TerminalBridge(manager, host);

		RecordingTransport transport = new RecordingTransport(host, bridge, manager);
		bridge.transport = transport;

		TerminalKeyListener keyListener = bridge.getKeyHandler();
		View v = new View(ApplicationProvider.getApplicationContext());

		keyListener.onKey(
				v,
				KeyEvent.KEYCODE_TAB,
				new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON));

		assertEquals("\u001b[Z", transport.drainUtf8());
	}
}
