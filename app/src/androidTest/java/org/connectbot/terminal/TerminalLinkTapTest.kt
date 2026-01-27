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
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.HiltComponentActivity
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TerminalLinkTapTest {
    private val terminalTag = "terminal"

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun tapHttpUrl_invokesCallback_whenEnabled() {
        hiltRule.inject()

        val tappedUrl = AtomicReference<String?>(null)
        val tapCellControllerRef = AtomicReference<TerminalTapController?>()
        val terminalTapCount = AtomicInteger(0)
        val selectionControllerRef = AtomicReference<SelectionController?>()

        val terminalEmulator =
            TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color.Black,
                onKeyboardInput = {},
                onBell = {},
                onResize = {},
                onClipboardCopy = {}
            )

        val focusRequester = FocusRequester()
        val fontSize = 16.sp

        composeTestRule.setContent {
            ConnectBotTheme {
                TerminalWithAccessibility(
                    terminalEmulator = terminalEmulator,
                    modifier =
                        Modifier
                            .size(360.dp)
                            .testTag(terminalTag),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = fontSize,
                    keyboardEnabled = true,
                    showSoftKeyboard = false,
                    focusRequester = focusRequester,
                    modifierManager = null,
                    forceAccessibilityEnabled = false,
                    onSelectionControllerAvailable = { selectionControllerRef.set(it) },
                    detectUrlsOnTap = true,
                    onHyperlinkClick = { tappedUrl.set(it) },
                    onTerminalTap = { terminalTapCount.incrementAndGet() },
                    onImeVisibilityChanged = {},
                    onTapCellForTestingAvailable = { tapCellControllerRef.set(it) },
                )
            }
        }

        composeTestRule.runOnIdle {
            focusRequester.requestFocus()
        }
        composeTestRule.waitForIdle()
        waitUntil(timeoutMillis = 5_000) { selectionControllerRef.get() != null }
        waitUntil(timeoutMillis = 5_000) { tapCellControllerRef.get() != null }

        composeTestRule.runOnIdle {
            terminalEmulator.writeInput("https://example.com\r\n".encodeToByteArray())
        }
        composeTestRule.waitForIdle()

        val url = "https://example.com"
        val selectionController = selectionControllerRef.get()!!
        var urlPosition: Pair<Int, Int>? = null
        waitUntil(timeoutMillis = 5_000) {
            urlPosition = findVisibleTextStart(selectionController, url)
            urlPosition != null
        }

        val (urlRow, urlCol) = urlPosition!!
        val tapCol = urlCol + 8 // inside "https://..."
        val tapCellController = tapCellControllerRef.get()!!
        composeTestRule.runOnIdle {
            tapCellController.tapCell(urlRow, tapCol)
        }

        val selectionMode =
            selectionController
                .reflectFieldByTypeName("org.connectbot.terminal.SelectionManager")
                .javaClass
                .getMethod("getMode")
                .invoke(
                    selectionController.reflectFieldByTypeName("org.connectbot.terminal.SelectionManager")
                )
                ?.toString()

        assertThat(tappedUrl.get())
            .withFailMessage(
                "Expected onHyperlinkClick for %s but got onTerminalTap=%d (selectionMode=%s)",
                url,
                terminalTapCount.get(),
                selectionMode
            )
            .isEqualTo(url)
    }

    @Test
    fun tapOsc8Hyperlink_invokesCallback_whenEnabled() {
        hiltRule.inject()

        val tappedUrl = AtomicReference<String?>(null)
        val tapCellControllerRef = AtomicReference<TerminalTapController?>()
        val terminalTapCount = AtomicInteger(0)
        val selectionControllerRef = AtomicReference<SelectionController?>()

        val terminalEmulator =
            TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color.Black,
                onKeyboardInput = {},
                onBell = {},
                onResize = {},
                onClipboardCopy = {}
            )

        val focusRequester = FocusRequester()
        val fontSize = 16.sp

        composeTestRule.setContent {
            ConnectBotTheme {
                TerminalWithAccessibility(
                    terminalEmulator = terminalEmulator,
                    modifier =
                        Modifier
                            .size(360.dp)
                            .testTag(terminalTag),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = fontSize,
                    keyboardEnabled = true,
                    showSoftKeyboard = false,
                    focusRequester = focusRequester,
                    modifierManager = null,
                    forceAccessibilityEnabled = false,
                    onSelectionControllerAvailable = { selectionControllerRef.set(it) },
                    detectUrlsOnTap = true,
                    onHyperlinkClick = { tappedUrl.set(it) },
                    onTerminalTap = { terminalTapCount.incrementAndGet() },
                    onImeVisibilityChanged = {},
                    onTapCellForTestingAvailable = { tapCellControllerRef.set(it) },
                )
            }
        }

        composeTestRule.runOnIdle {
            focusRequester.requestFocus()
        }
        composeTestRule.waitForIdle()
        waitUntil(timeoutMillis = 5_000) { selectionControllerRef.get() != null }
        waitUntil(timeoutMillis = 5_000) { tapCellControllerRef.get() != null }

        val url = "https://example.com"
        val linkText = "CLICK"
        val osc8Start = "\u001B]8;;$url\u0007"
        val osc8End = "\u001B]8;;\u0007"
        val payload = "$osc8Start$linkText$osc8End\r\n".encodeToByteArray()

        composeTestRule.runOnIdle {
            terminalEmulator.writeInput(payload)
        }
        composeTestRule.waitForIdle()

        val selectionController = selectionControllerRef.get()!!
        var linkTextPosition: Pair<Int, Int>? = null
        waitUntil(timeoutMillis = 5_000) {
            linkTextPosition = findVisibleTextStart(selectionController, linkText)
            linkTextPosition != null
        }

        val (row, col) = linkTextPosition!!
        val tapCellController = tapCellControllerRef.get()!!
        composeTestRule.runOnIdle {
            tapCellController.tapCell(row, col + 2)
        }

        assertThat(tappedUrl.get())
            .withFailMessage(
                "Expected onHyperlinkClick for %s but got onTerminalTap=%d",
                url,
                terminalTapCount.get()
            )
            .isEqualTo(url)
    }

    @Test
    fun tapOsc8Hyperlink_doesNotInvokeCallback_whenDisabled() {
        hiltRule.inject()

        val tappedUrl = AtomicReference<String?>(null)
        val tapCellControllerRef = AtomicReference<TerminalTapController?>()
        val selectionControllerRef = AtomicReference<SelectionController?>()
        val terminalTapCount = AtomicInteger(0)

        val terminalEmulator =
            TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color.Black,
                onKeyboardInput = {},
                onBell = {},
                onResize = {},
                onClipboardCopy = {}
            )

        val focusRequester = FocusRequester()
        val fontSize = 16.sp

        composeTestRule.setContent {
            ConnectBotTheme {
                TerminalWithAccessibility(
                    terminalEmulator = terminalEmulator,
                    modifier =
                        Modifier
                            .size(360.dp)
                            .testTag(terminalTag),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = fontSize,
                    keyboardEnabled = true,
                    showSoftKeyboard = false,
                    focusRequester = focusRequester,
                    modifierManager = null,
                    forceAccessibilityEnabled = false,
                    onSelectionControllerAvailable = { selectionControllerRef.set(it) },
                    detectUrlsOnTap = false,
                    onHyperlinkClick = { tappedUrl.set(it) },
                    onTerminalTap = { terminalTapCount.incrementAndGet() },
                    onImeVisibilityChanged = {},
                    onTapCellForTestingAvailable = { tapCellControllerRef.set(it) },
                )
            }
        }

        composeTestRule.runOnIdle {
            focusRequester.requestFocus()
        }
        composeTestRule.waitForIdle()
        waitUntil(timeoutMillis = 5_000) { selectionControllerRef.get() != null }
        waitUntil(timeoutMillis = 5_000) { tapCellControllerRef.get() != null }

        val url = "https://example.com"
        val linkText = "CLICK"
        val osc8Start = "\u001B]8;;$url\u0007"
        val osc8End = "\u001B]8;;\u0007"
        val payload = "$osc8Start$linkText$osc8End\r\n".encodeToByteArray()

        composeTestRule.runOnIdle {
            terminalEmulator.writeInput(payload)
        }
        composeTestRule.waitForIdle()

        val selectionController = selectionControllerRef.get()!!
        var linkTextPosition: Pair<Int, Int>? = null
        waitUntil(timeoutMillis = 5_000) {
            linkTextPosition = findVisibleTextStart(selectionController, linkText)
            linkTextPosition != null
        }

        val (row, col) = linkTextPosition!!
        val tapCellController = tapCellControllerRef.get()!!
        composeTestRule.runOnIdle {
            tapCellController.tapCell(row, col + 2)
        }

        assertThat(terminalTapCount.get()).isGreaterThan(0)
        assertThat(tappedUrl.get()).isNull()
    }

    @Test
    fun tapHttpUrl_doesNotInvokeCallback_whenDisabled() {
        hiltRule.inject()

        val tappedUrl = AtomicReference<String?>(null)
        val tapCellControllerRef = AtomicReference<TerminalTapController?>()
        val selectionControllerRef = AtomicReference<SelectionController?>()
        val terminalTapCount = AtomicInteger(0)

        val terminalEmulator =
            TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color.Black,
                onKeyboardInput = {},
                onBell = {},
                onResize = {},
                onClipboardCopy = {}
            )

        val focusRequester = FocusRequester()
        val fontSize = 16.sp

        composeTestRule.setContent {
            ConnectBotTheme {
                TerminalWithAccessibility(
                    terminalEmulator = terminalEmulator,
                    modifier =
                        Modifier
                            .size(360.dp)
                            .testTag(terminalTag),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = fontSize,
                    keyboardEnabled = true,
                    showSoftKeyboard = false,
                    focusRequester = focusRequester,
                    modifierManager = null,
                    forceAccessibilityEnabled = false,
                    onSelectionControllerAvailable = { selectionControllerRef.set(it) },
                    detectUrlsOnTap = false,
                    onHyperlinkClick = { tappedUrl.set(it) },
                    onTerminalTap = { terminalTapCount.incrementAndGet() },
                    onImeVisibilityChanged = {},
                    onTapCellForTestingAvailable = { tapCellControllerRef.set(it) },
                )
            }
        }

        composeTestRule.runOnIdle {
            focusRequester.requestFocus()
        }
        composeTestRule.waitForIdle()
        waitUntil(timeoutMillis = 5_000) { selectionControllerRef.get() != null }
        waitUntil(timeoutMillis = 5_000) { tapCellControllerRef.get() != null }

        composeTestRule.runOnIdle {
            terminalEmulator.writeInput("https://example.com\r\n".encodeToByteArray())
        }
        composeTestRule.waitForIdle()

        val url = "https://example.com"
        val selectionController = selectionControllerRef.get()!!
        var urlPosition: Pair<Int, Int>? = null
        waitUntil(timeoutMillis = 5_000) {
            urlPosition = findVisibleTextStart(selectionController, url)
            urlPosition != null
        }

        val (urlRow, urlCol) = urlPosition!!
        val tapCol = urlCol + 8 // inside "https://..."
        val tapCellController = tapCellControllerRef.get()!!
        composeTestRule.runOnIdle {
            tapCellController.tapCell(urlRow, tapCol)
        }

        val selectionMode =
            selectionController
                .reflectFieldByTypeName("org.connectbot.terminal.SelectionManager")
                .javaClass
                .getMethod("getMode")
                .invoke(
                    selectionController.reflectFieldByTypeName("org.connectbot.terminal.SelectionManager")
                )
                ?.toString()

        assertThat(terminalTapCount.get()).isGreaterThan(0)
        assertThat(selectionMode).isEqualTo("NONE")
        assertThat(tappedUrl.get()).isNull()
    }

    private inline fun waitUntil(timeoutMillis: Long, crossinline predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            composeTestRule.waitForIdle()
            Thread.sleep(50)
        }
        assertThat(predicate()).isTrue()
    }

    private fun findVisibleTextStart(selectionController: SelectionController, text: String): Pair<Int, Int>? {
        val screenState = selectionController.reflectFieldByTypeName("org.connectbot.terminal.TerminalScreenState")
        val snapshot =
            screenState.javaClass.getMethod("getSnapshot").invoke(screenState)
                ?: return null

        val rows =
            (snapshot.javaClass.getMethod("getRows").invoke(snapshot) as? Number)?.toInt()
                ?: return null

        val getVisibleLine =
            screenState.javaClass.getMethod("getVisibleLine", Integer.TYPE)

        for (row in 0 until rows) {
            val line = getVisibleLine.invoke(screenState, row) ?: continue
            val lineText = line.javaClass.getMethod("getText").invoke(line) as? String ?: continue
            val col = lineText.indexOf(text)
            if (col >= 0) return Pair(row, col)
        }

        return null
    }

    private fun SelectionController.reflectFieldByTypeName(typeName: String): Any {
        val field =
            javaClass.declaredFields.firstOrNull { it.type.name == typeName }
                ?: throw AssertionError("Missing field of type $typeName on ${javaClass.name}")
        field.isAccessible = true
        return field.get(this) ?: throw AssertionError("Field of type $typeName was null on ${javaClass.name}")
    }

}
