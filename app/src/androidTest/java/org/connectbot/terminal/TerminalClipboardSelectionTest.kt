/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025 Kenny Root
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

package org.connectbot.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.HiltComponentActivity
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.SelectionMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TerminalClipboardSelectionTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    private lateinit var clipboard: ClipboardManager

    @Before
    fun setUp() {
        hiltRule.inject()
        clipboard =
            composeTestRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    @Test
    fun copySelection_whenScrolledUp_includesVisibleScreenLines() {
        assumeTrue(
            "Set -Pandroid.testInstrumentationRunnerArguments.connectbot_enable_terminal_clipboard_test=true to enable this test",
            isTestEnabled()
        )

        val selectionControllerRef = AtomicReference<SelectionController?>()

        val terminalEmulator =
            TerminalEmulatorFactory.create(
                initialRows = 5,
                initialCols = 20,
                defaultForeground = Color.White,
                defaultBackground = Color.Black,
                onKeyboardInput = {},
                onBell = {},
                onResize = {},
                onClipboardCopy = {}
            )

        composeTestRule.setContent {
            ConnectBotTheme {
                Terminal(
                    terminalEmulator = terminalEmulator,
                    modifier =
                        Modifier
                            .size(320.dp)
                            .testTag("terminal"),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = 16.sp,
                    keyboardEnabled = false,
                    showSoftKeyboard = false,
                    focusRequester = FocusRequester(),
                    forcedSize = Pair(5, 20),
                    modifierManager =
                        object : ModifierManager {
                            override fun isCtrlActive(): Boolean = false

                            override fun isAltActive(): Boolean = false

                            override fun isShiftActive(): Boolean = false

                            override fun clearTransients() = Unit
                        },
                    onTerminalTap = {},
                    onImeVisibilityChanged = {},
                    onSelectionControllerAvailable = { selectionControllerRef.set(it) }
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { selectionControllerRef.get() != null }
        val selectionController = selectionControllerRef.get()!!

        val screenState = selectionController.reflectFieldByTypeName("org.connectbot.terminal.TerminalScreenState")
        val selectionManager = selectionController.reflectFieldByTypeName("org.connectbot.terminal.SelectionManager")

        composeTestRule.runOnIdle {
            val content =
                listOf(
                    "LINE0",
                    "LINE1",
                    "LINE2",
                    "LINE3",
                    "LINE4",
                    "LINE5"
                ).joinToString(separator = "\r\n")
            terminalEmulator.writeInput(content.encodeToByteArray())
        }

        // Wait for scrollback to exist (rows=5, lines=6 => scrollback should be >= 1).
        waitUntil(timeoutMillis = 5_000) {
            var hasScrollback = false
            composeTestRule.runOnIdle {
                hasScrollback = screenState.getScrollbackSize() >= 1
            }
            hasScrollback
        }

        // Baseline: at bottom, copying should work.
        composeTestRule.runOnIdle {
            assertThat(screenState.invokeIntMethod("getScrollbackPosition")).isEqualTo(0)
            selectionManager.invokeVoidMethod(
                "startSelection",
                Integer.TYPE,
                2,
                Integer.TYPE,
                0,
                SelectionMode::class.java,
                SelectionMode.LINE
            )
            selectionManager.invokeVoidMethod("endSelection")
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "OLD_CLIPBOARD_VALUE"))
        var copiedAtBottom: String? = null
        composeTestRule.runOnIdle {
            copiedAtBottom = selectionController.copySelection()
        }
        assertThat(copiedAtBottom).isEqualTo("LINE3")
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo("LINE3")

        // Repro: after scrolling up by one line, visible rows include both scrollback and screen
        // lines. Copying a line that comes from the "screen" portion should still work.
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "OLD_CLIPBOARD_VALUE_2"))
        composeTestRule.runOnIdle {
            screenState.invokeVoidMethod("scrollBy", Integer.TYPE, 1)
            assertThat(screenState.invokeIntMethod("getScrollbackPosition")).isEqualTo(1)

            // Select the 4th visible row (0-based). After scrolling up by 1, this is "LINE3".
            selectionManager.invokeVoidMethod(
                "startSelection",
                Integer.TYPE,
                3,
                Integer.TYPE,
                0,
                SelectionMode::class.java,
                SelectionMode.LINE
            )
            selectionManager.invokeVoidMethod("endSelection")
        }

        var copiedText: String? = null
        composeTestRule.runOnIdle {
            copiedText = selectionController.copySelection()
        }

        val clipboardText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertThat(copiedText).isEqualTo("LINE3")
        assertThat(clipboardText).isEqualTo("LINE3")
    }

    private fun isTestEnabled(): Boolean {
        val raw = InstrumentationRegistry.getArguments().getString("connectbot_enable_terminal_clipboard_test")
        return raw == "true" || raw == "1"
    }

    private inline fun waitUntil(timeoutMillis: Long, crossinline predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        assertThat(predicate()).isTrue()
    }
}

private fun SelectionController.reflectFieldByTypeName(typeName: String): Any {
    val field =
        javaClass.declaredFields.firstOrNull { it.type.name == typeName }
            ?: throw AssertionError("Missing field of type $typeName on ${javaClass.name}")
    field.isAccessible = true
    return field.get(this) ?: throw AssertionError("Field of type $typeName was null on ${javaClass.name}")
}

private fun Any.getScrollbackSize(): Int {
    val snapshot = javaClass.getMethod("getSnapshot").invoke(this)
        ?: throw AssertionError("getSnapshot() returned null on ${javaClass.name}")
    val scrollback = snapshot.javaClass.getMethod("getScrollback").invoke(snapshot) as? List<*>
        ?: throw AssertionError("getScrollback() returned non-List on ${snapshot.javaClass.name}")
    return scrollback.size
}

private fun Any.invokeIntMethod(methodName: String): Int {
    val value = javaClass.getMethod(methodName).invoke(this)
        ?: throw AssertionError("$methodName() returned null on ${javaClass.name}")
    return (value as Number).toInt()
}

private fun Any.invokeVoidMethod(methodName: String, vararg paramsAndArgs: Any) {
    check(paramsAndArgs.size % 2 == 0) { "Expected alternating (Class, arg) pairs" }
    val paramTypes = Array(paramsAndArgs.size / 2) { idx -> paramsAndArgs[idx * 2] as Class<*> }
    val args = Array(paramsAndArgs.size / 2) { idx -> paramsAndArgs[idx * 2 + 1] }
    javaClass.getMethod(methodName, *paramTypes).invoke(this, *args)
}
