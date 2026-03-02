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

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.HiltComponentActivity
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TerminalImeKeyRepeatTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun imeDeleteSurroundingText_synthesizesRepeatableBackspace() {
        hiltRule.inject()

        val output = ByteArrayOutputStream()
        val terminalEmulator =
            TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color.Black,
                onKeyboardInput = { data ->
                    synchronized(output) {
                        output.write(data)
                    }
                },
                onBell = {},
                onResize = {},
                onClipboardCopy = {}
            )

        val focusRequester = FocusRequester()
        composeTestRule.setContent {
            ConnectBotTheme {
                Terminal(
                    terminalEmulator = terminalEmulator,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("terminal"),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = 16.sp,
                    keyboardEnabled = true,
                    showSoftKeyboard = false,
                    focusRequester = focusRequester,
                    forcedSize = Pair(24, 80),
                    modifierManager = null,
                    onTerminalTap = {},
                    onImeVisibilityChanged = {}
                )
            }
        }

        composeTestRule.runOnIdle {
            focusRequester.requestFocus()
        }
        composeTestRule.waitForIdle()

        lateinit var inputConnection: InputConnection
        composeTestRule.runOnIdle {
            val decorView = composeTestRule.activity.window.decorView
            val imeView =
                findImeInputView(decorView)
                    ?: error("ImeInputView not found; IME input cannot be tested")
            imeView.requestFocus()

            val outAttrs = EditorInfo()
            inputConnection =
                imeView.onCreateInputConnection(outAttrs)
                    ?: error("ImeInputView returned null InputConnection")

            assertThat(outAttrs.inputType and EditorInfo.TYPE_MASK_CLASS).isEqualTo(EditorInfo.TYPE_CLASS_TEXT)
        }
        composeTestRule.waitForIdle()

        // Capture the baseline "single backspace" output.
        composeTestRule.runOnIdle {
            inputConnection.deleteSurroundingText(1, 0)
        }
        val single = drainKeyboardOutput(output)
        assertThat(single).isNotEmpty

        // Simulate an IME long-press delete that requests multiple backspaces at once.
        composeTestRule.runOnIdle {
            inputConnection.deleteSurroundingText(5, 0)
        }
        val repeated = drainKeyboardOutput(output)

        val expected = ByteArray(single.size * 5)
        for (i in 0 until 5) {
            System.arraycopy(single, 0, expected, i * single.size, single.size)
        }
        assertThat(repeated.toList()).isEqualTo(expected.toList())
    }

    private fun findImeInputView(root: View): View? {
        if (root.javaClass.name == "org.connectbot.terminal.ImeInputView") return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findImeInputView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun drainKeyboardOutput(output: ByteArrayOutputStream): ByteArray {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return synchronized(output) {
            val bytes = output.toByteArray()
            output.reset()
            bytes
        }
    }
}

