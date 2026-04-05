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

package org.connectbot.automation

import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAutomationTest {
    @Test
    fun decodeEscapes_handlesControlSequences() {
        assertEquals(
            "tmux\n\u0002\t\\",
            SessionAutomationIntents.decodeEscapes("tmux\\n\\u0002\\t\\\\")
        )
    }

    @Test
    fun parseKeyStrokes_decodesModifiersAndNamedKeys() {
        assertEquals(
            listOf(
                AutomationKeyStroke(key = VTermKey.LEFT, modifiers = 4),
                AutomationKeyStroke(key = VTermKey.ENTER, modifiers = 0),
                AutomationKeyStroke(key = VTermKey.FUNCTION_2, modifiers = 0)
            ),
            SessionAutomationIntents.parseKeyStrokes("ctrl+left, enter, F2")
        )
    }
}
