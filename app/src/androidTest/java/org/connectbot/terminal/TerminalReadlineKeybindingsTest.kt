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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
class TerminalReadlineKeybindingsTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun ctrlA_outputsControlA() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_A)
            dispatchKeyUp(KeyEvent.KEYCODE_A)
            dispatchKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x01)
    }

    @Test
    fun altB_outputsEscapeB() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_ALT_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_B)
            dispatchKeyUp(KeyEvent.KEYCODE_B)
            dispatchKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x1b, 'b'.code)
    }

    @Test
    fun ctrlLeftArrow_outputsModifiedCsi() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_DPAD_LEFT)
            dispatchKeyUp(KeyEvent.KEYCODE_DPAD_LEFT)
            dispatchKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        }

        val expected = "\u001B[1;5D".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun ctrlRightArrow_outputsModifiedCsi() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
            dispatchKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
            dispatchKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        }

        val expected = "\u001B[1;5C".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun altLeftArrow_outputsModifiedCsi() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_ALT_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_DPAD_LEFT)
            dispatchKeyUp(KeyEvent.KEYCODE_DPAD_LEFT)
            dispatchKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
        }

        val expected = "\u001B[1;3D".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun altRightArrow_outputsModifiedCsi() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_ALT_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
            dispatchKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
            dispatchKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
        }

        val expected = "\u001B[1;3C".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun shiftTab_outputsBacktab() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_TAB)
            dispatchKeyUp(KeyEvent.KEYCODE_TAB)
            dispatchKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        }

        val expected = "\u001B[Z".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun moveHome_outputsHomeSequence() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_MOVE_HOME)
            dispatchKeyUp(KeyEvent.KEYCODE_MOVE_HOME)
        }

        val expected = "\u001B[H".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun moveEnd_outputsEndSequence() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_MOVE_END)
            dispatchKeyUp(KeyEvent.KEYCODE_MOVE_END)
        }

        val expected = "\u001B[F".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun pageUp_outputsSequence() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_PAGE_UP)
            dispatchKeyUp(KeyEvent.KEYCODE_PAGE_UP)
        }

        val expected = "\u001B[5~".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun pageDown_outputsSequence() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_PAGE_DOWN)
            dispatchKeyUp(KeyEvent.KEYCODE_PAGE_DOWN)
        }

        val expected = "\u001B[6~".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun insert_outputsSequence() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_INSERT)
            dispatchKeyUp(KeyEvent.KEYCODE_INSERT)
        }

        val expected = "\u001B[2~".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun delete_outputsSequence() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_FORWARD_DEL)
            dispatchKeyUp(KeyEvent.KEYCODE_FORWARD_DEL)
        }

        val expected = "\u001B[3~".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun ctrlSpace_outputsNul() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_SPACE)
            dispatchKeyUp(KeyEvent.KEYCODE_SPACE)
            dispatchKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x00)
    }

    @Test
    fun ctrlE_outputsControlE() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_E)
            dispatchKeyUp(KeyEvent.KEYCODE_E)
            dispatchKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x05)
    }

    @Test
    fun ctrlLeftBracket_outputsEscape() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_LEFT_BRACKET)
            dispatchKeyUp(KeyEvent.KEYCODE_LEFT_BRACKET)
            dispatchKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x1b)
    }

    @Test
    fun altF_outputsEscapeF() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_ALT_LEFT)
            dispatchKeyDown(KeyEvent.KEYCODE_F)
            dispatchKeyUp(KeyEvent.KEYCODE_F)
            dispatchKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x1b, 'f'.code)
    }

    @Test
    fun ctrlA_outputsControlA_fromMetaStateOnly() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_A, metaState = KeyEvent.META_CTRL_ON)
            dispatchKeyUp(KeyEvent.KEYCODE_A, metaState = KeyEvent.META_CTRL_ON)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x01)
    }

    @Test
    fun ctrlE_outputsControlE_fromMetaStateOnly() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_E, metaState = KeyEvent.META_CTRL_ON)
            dispatchKeyUp(KeyEvent.KEYCODE_E, metaState = KeyEvent.META_CTRL_ON)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x05)
    }

    @Test
    fun ctrlLeftBracket_outputsEscape_fromMetaStateOnly() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_LEFT_BRACKET, metaState = KeyEvent.META_CTRL_ON)
            dispatchKeyUp(KeyEvent.KEYCODE_LEFT_BRACKET, metaState = KeyEvent.META_CTRL_ON)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x1b)
    }

    @Test
    fun altB_outputsEscapeB_fromMetaStateOnly() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_B, metaState = KeyEvent.META_ALT_ON)
            dispatchKeyUp(KeyEvent.KEYCODE_B, metaState = KeyEvent.META_ALT_ON)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x1b, 'b'.code)
    }

    @Test
    fun altF_outputsEscapeF_fromMetaStateOnly() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_F, metaState = KeyEvent.META_ALT_ON)
            dispatchKeyUp(KeyEvent.KEYCODE_F, metaState = KeyEvent.META_ALT_ON)
        }

        assertThat(drainKeyboardOutput(harness.output).map { it.toInt() and 0xff })
            .containsExactly(0x1b, 'f'.code)
    }

    @Test
    fun ctrlLeftArrow_outputsModifiedCsi_fromMetaStateOnly() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, metaState = KeyEvent.META_CTRL_ON)
            dispatchKeyUp(KeyEvent.KEYCODE_DPAD_LEFT, metaState = KeyEvent.META_CTRL_ON)
        }

        val expected = "\u001B[1;5D".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    @Test
    fun ctrlLeftArrow_outputsModifiedCsi_fromCtrlLeftMetaStateOnly() {
        val harness = setUpTerminal()

        composeTestRule.runOnIdle {
            dispatchKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, metaState = KeyEvent.META_CTRL_LEFT_ON)
            dispatchKeyUp(KeyEvent.KEYCODE_DPAD_LEFT, metaState = KeyEvent.META_CTRL_LEFT_ON)
        }

        val expected = "\u001B[1;5D".toByteArray(Charsets.UTF_8)
        assertThat(drainKeyboardOutput(harness.output).toList()).isEqualTo(expected.toList())
    }

    private data class TerminalHarness(
        val output: ByteArrayOutputStream
    )

    private fun setUpTerminal(): TerminalHarness {
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

        // FocusRequester does not reliably focus the Terminal's internal ImeInputView on all
        // devices/CI profiles. Ensure the actual View that receives key events is focused.
        composeTestRule.runOnIdle {
            val decorView = composeTestRule.activity.window.decorView
            val imeView =
                findImeInputView(decorView)
                    ?: error("ImeInputView not found; keyboard input cannot be tested")
            imeView.requestFocus()
        }
        composeTestRule.waitForIdle()

        return TerminalHarness(output = output)
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

    private fun dispatchKeyDown(keyCode: Int, metaState: Int = 0) {
        composeTestRule.activity.dispatchKeyEvent(
            KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState
            )
        )
    }

    private fun dispatchKeyUp(keyCode: Int, metaState: Int = 0) {
        composeTestRule.activity.dispatchKeyEvent(
            KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                metaState
            )
        )
    }
}
