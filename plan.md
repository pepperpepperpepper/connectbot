# Plan / Notes

## Target build (important)

- For our purposes, the **correct ConnectBot** is the **Play Store–style UI baseline** (v1.9.13-era). Newer Compose-rewrite builds are not the target for testing/publishing.
- **Publishing rule:** only publish the **google** flavor. Any “oss” flavor testing/publishing is considered **invalid** for our purposes.

## Text selection/copy bugs

- ⚠️ User report (baseline): `1.9.13.3` (`10914004`) — selection works when the soft keyboard is visible, but becomes misaligned/wrong after hiding the keyboard via ConnectBot’s keyboard toggle.
- ⚠️ User report (regression): `1.9.13.4` (`10914005`) — “regular” selection is now broken as well (keyboard visible), indicating our automated tests are not representative.

### Current status (Jan 21, 2026)

- Root cause for the “keyboard hidden” misalignment appears to be **metric drift** between the terminal’s `bridge.charHeight` and the overlay `TextView` line height as more rows become visible (keyboard hidden), which compounds towards the bottom of the screen.
- Fix applied in the v1.9.13 codebase: `TerminalView#onFontSizeChanged` now calls `TextViewCompat.setLineHeight(terminalTextViewOverlay, bridge.charHeight)` so the overlay line grid matches the terminal grid.
- After that change, `org.connectbot.TerminalSelectionCopyTest` passes on Genymotion SaaS recipe `d212b329-aacd-4fe5-aa76-3480f12a6200`.

### Automated coverage (GMSaaS)

These cases are now covered by scripted instrumentation tests in the v1.9.13 build:
- Primary release gate: `org.connectbot.TerminalSelectionCopyTest`
  - selection+copy with keyboard visible
  - selection+copy after hiding keyboard (via ConnectBot toggle) at multiple viewport anchors (top/mid/bottom)

Note: `StartupTest` is broad/flaky across some Genymotion profiles; don’t gate releases on it.

### Why the current tests are suspect

- Current GMSaaS tests inject touch events by dispatching directly to `TerminalTextViewOverlay`, which can bypass real input dispatch/interception (e.g., parent `ViewPager` / window insets / event coordinate transforms).
- That can allow tests to “pass” even when real user selection is broken on-device.

### Test suite changes (Jan 21, 2026)

- Added a focused regression test class: `org.connectbot.TerminalSelectionCopyTest` (v1.9.13 build) that only validates selection+copy with:
  - keyboard visible
  - keyboard hidden (via ConnectBot keyboard toggle)
- Long-press is now dispatched via the activity decor view (instead of directly to the overlay) so ViewPager/interception is exercised.
- Full `StartupTest` is too broad/flaky across some Genymotion device profiles (unrelated swipe/scroll tests can fail), so the release gate should run the focused selection test class.

### Genymotion ADB tunnel gotcha

- `gmadbtunneld` uses a fixed local socket name (MD5 of `"gmadbtunneld"`). If a **stale** socket exists in `/tmp` (often owned by a different user), `gmsaas instances adbconnect` can fail.
- Workaround: run `gmsaas` with `TMPDIR=~/.Genymobile/gmsaas/tmp_${USER}_gmadbtunneld` (per-user temp dir) so the socket is created somewhere we control.

### Release gate (must-do)

- Before publishing to F-Droid (“afteroid”), run the selection tests for **both**:
  - keyboard visible
  - keyboard hidden (via ConnectBot keyboard toggle)
- Tests must not inject touches directly to the overlay (avoid `dispatchTouchEvent` on `TerminalTextViewOverlay`).

### Current publish

- Published **google** flavor `1.9.13.8` (`10914009`) to afteroid/F-Droid repo (Jan 27, 2026).
- Known-bad historical build: `1.9.13.4` (`10914005`) regressed “regular” selection (do not republish).

### If the bug still reproduces on-device

Capture:
- Android version + device model
- Whether using touch vs mouse/trackpad
- Whether font size was changed in ConnectBot
- A short description of the exact selection gesture (long-press word? drag handles? drag to scroll?)

## Follow-up investigation (Jan 22, 2026)

- New user report: selection can start “OK”, but becomes unreliable after more use (keyboard shown/hidden).
- We added additional scripted coverage to detect **coordinate → character** mismatches (not just clipboard contents).

### New suspected root causes

- Even if line height is fixed, **horizontal hit-testing** can still drift if the overlay `TextView` differs from the terminal grid in any of:
  - per-character advance width (`bridge.charWidth` vs TextView glyph advances / layout width)
  - wrapping behavior (TextView wrapping terminal rows into multiple visual lines)
  - padding/font padding affecting the effective text area

### Fixes applied in v1.9.13 build

- Overlay `TextView` hardened to behave like a grid:
  - `setIncludeFontPadding(false)`, `setPadding(0,0,0,0)`
  - `setHorizontallyScrolling(true)` (prevent wrapping)
- `TerminalView#onFontSizeChanged` now also calibrates the overlay horizontally:
  - sets `fakeBoldText` to match terminal paint
  - iteratively adjusts `setTextScaleX()` using an average width sample so the overlay’s effective cell width matches `bridge.charWidth`

### Test suite changes (Jan 22, 2026)

- `org.connectbot.TerminalSelectionCopyTest` now includes:
  - repeated keyboard hide/show + output loop (`selectionCopyWorksAfterRepeatedKeyboardTogglesAndOutput`)
  - hit-testing validation (`hitTestingMatchesTerminalGridWithKeyboardVisibleAndHidden`)
    - injects a known grid to the buffer
    - samples multiple viewport rows/cols and asserts the overlay hit-test maps to the same character as the terminal buffer

### Release gate update

- Before publishing to afteroid, require:
  - `TerminalSelectionCopyTest` passes on at least one Android 13+ recipe
  - and ideally re-run on Android 13/14/15 recipes (Genymotion Phone profiles) to reduce device-specific flakiness

## Follow-up (Jan 22, 2026, continued)

- Strengthened hit-testing coverage: the injected grid now includes **row-varying sentinel characters** (col 0) so vertical drift is detectable by `getOffsetForPosition()` checks (previously rows were too similar).
- Added a **large-scrollback burn-in** test: `selectionCopyRemainsStableAfterManyKeyboardTogglesWithLargeScrollback`.
  - Uses `scrollback=2000`, toggles keyboard repeatedly, and periodically re-validates hit-testing.
- Re-ran `org.connectbot.TerminalSelectionCopyTest` on Genymotion SaaS:
  - Android 15 recipe `d212b329-aacd-4fe5-aa76-3480f12a6200` ✅
  - Android 13 recipe `4476e634-eb1e-46bd-acd5-5074c7b62e45` ✅
- GMSaaS quota gotcha: random background jobs can spawn instances (e.g. `odp_*`) and cause `TOO_MANY_RUNNING_VDS`; stop them via `gmsaas instances list` + `gmsaas instances stop <uuid>` before starting the ConnectBot test run.

## Afteroid publish (Jan 27, 2026)

- Published **google** flavor `1.9.13.8` (`10914009`) to afteroid/F-Droid repo, updated metadata, and invalidated CloudFront (`I6SKMF341PCYAMFNOOR9S77XWY`).

## Follow-up resolution (Jan 26, 2026)

- Previously: with a combination of **keyboard hide/show**, **scrolling**, and **continuous output**, selection could eventually become “lost”/misaligned again.
- Current: user reports this no longer reproduces in normal use; treat as resolved (keep the streaming regression test in the release gate).

## Continuous output regression test + fix (Jan 22, 2026, later)

- Added a scripted Genymotion regression test that reproduces the “continuous scrolling + scrollback selection” failure mode:
  - `org.connectbot.TerminalSelectionCopyTest#selectionCopyWorksWhileOutputIsStreamingAndUserIsScrolledUp`
  - Seeds the terminal with deterministic `NUM00000..NUM00999`, starts a background streamer (`STREAMxxxxx`), and repeatedly long-press selects + copies `NUM00045` and `NUM00005` while:
    - the soft keyboard is visible
    - the soft keyboard is hidden (via ConnectBot toggle)
  - This is now part of the release gate (google flavor).

### Root cause (confirmed via debug instrumentation)

- The selection overlay (`TerminalTextViewOverlay`, a `TextView`) was feeding **TextView-internal scrolling** back into the terminal buffer’s scrollback position (`VDUBuffer.windowBase`) via `TerminalTextViewOverlay.scrollTo(...)`.
- Under continuous output, the framework can invoke `TextView.scrollTo(...)` internally (layout/selection/bring-into-view), which unintentionally:
  - moved `windowBase` toward `screenBase` (bottom),
  - pushed the viewport away from the intended token line mid-gesture,
  - and resulted in either an empty selection/copy or selecting the wrong character range.

### Fixes applied (v1.9.13 build)

- Make the **terminal buffer** the source of truth for scrollback:
  - `TerminalTextViewOverlay.scrollTo(...)` no longer updates `bridge.buffer.windowBase` (prevents TextView-internal scroll from mutating scrollback).
  - `TerminalTextViewOverlay.onPreDraw()` applies pending scroll using `super.scrollTo(...)` (no feedback loop into `windowBase`).
- Harden scrollback behavior in termlib:
  - `VDUBuffer.insertLine(...)` now avoids auto-advancing `windowBase` while the user is in scrollback (`windowBase != screenBase`), and adjusts appropriately when dropping lines at the top.

### Current status

- `org.connectbot.TerminalSelectionCopyTest` passes on GMSaaS Android 15 recipe `d212b329-aacd-4fe5-aa76-3480f12a6200` ✅ (including the new streaming test).
- Confirmed by user on real-device/manual testing (Jan 26, 2026): the “gets lost after longer use” report no longer reproduces; automated repro + gate remain in place.

## Terminal bell / “task done” → Android notification (research + plan)

- Goal: when a tool finishes (e.g., Claude Code / Codex CLI), surface an Android notification containing the **final message/status** while the session/app is backgrounded.

### Current status (Jan 26, 2026)

- Implemented end-to-end “terminal-triggered notifications”:
  - termlib:
    - Supports OSC notifications: **OSC 9** (iTerm2-style `OSC 9 ; <message> ST`) and **OSC 777** (`OSC 777 ; notify ; <title> ; <body> ST`).
    - Surfaces notifications via `TerminalEmulatorFactory.create(onNotify = ...)` with `TerminalNotification(title, body)`.
    - BEL fallback uses `TerminalEmulator.getRecentText(...)` to capture a best-effort output snippet.
  - ConnectBot:
    - `TerminalBridge` wires `onBell` and `onNotify` to `TerminalManager.sendActivityNotification(host, message)`.
    - Notifications are gated by: app backgrounded (`!isUiBound`) + Settings toggle `bellNotification`.
    - Notification tap deep-links to the host URI (opens the correct session).
    - Per-host rate limit: 3s.
    - Build note: ConnectBot needs a termlib build that includes `TerminalEmulator.getRecentText`, `onNotify`, and link-tap APIs (publish termlib to `mavenLocal` and build ConnectBot with `-PconnectbotTermlibVersion=0.0.18-SNAPSHOT`).
  - Automated coverage (termlib):
    - `org.connectbot.terminal.TerminalNotificationTest` validates OSC 9 + OSC 777 parsing/dispatch and BEL → `getRecentText()` snippet behavior.
    - Verified on Genymotion SaaS (Android 15 recipe `d212b329-aacd-4fe5-aa76-3480f12a6200`): `cd /home/arch/termlib && ./gradlew --no-daemon --no-configuration-cache :lib:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.connectbot.terminal.TerminalNotificationTest` ✅

### What’s actually happening on Windows

- Windows Terminal’s “bell” is triggered by **BEL** (`\u0007`) and configured via `bellStyle` (audible / window flash / taskbar) — **BEL has no payload**, so it can’t carry “last message” text.
- Windows Terminal shell integration uses **OSC `9;9;<cwd>`** for current working directory (CWD) — **not** notifications.
- Codex CLI’s “floating notification with last message” is typically **Codex itself** (desktop notification), via:
  - built-in `tui.notifications`, and/or
  - a `notify` hook on `agent-turn-complete` which includes `last-assistant-message` in the event payload.

### ConnectBot replication plan

- We need a **message-carrying signal over the SSH byte stream** that we can map to an Android notification.
- Supported/target sequences:
  - **OSC 777**: `ESC ] 777 ; notify ; <title> ; <body> ST` (hterm/libapps style)
  - **OSC 9**: `ESC ] 9 ; <message> ST` (iTerm2-style desktop notification payload)
  - Fallback: **BEL** with a best-effort “recent output snippet” as the body.
- Recommended setup for Codex/Claude on a remote host: use their “notify hook” / completion hook to emit an OSC notify sequence to the terminal (e.g., write `ESC ] 777 ; notify ; Codex ; <last-assistant-message> ST` to `/dev/tty`). ConnectBot consumes the OSC sequence (no visual output) and posts an Android notification when the session is backgrounded.

### tmux fan-in problem (multiple panes / sessions)

- In tmux, Codex/Claude may be running in **different panes** than the one ConnectBot is currently viewing.
- If ConnectBot is only attached to (and therefore only receiving output from) a **single PTY**, then:
  - Notifications sent to other panes’ PTYs will **not** reach ConnectBot.
  - Multiple sources can still “fan in” to the viewed pane by writing OSC notify sequences to the viewed pane’s PTY, but that requires an explicit routing step.
- tmux can’t truly “run a command inside an existing interactive shell” without injecting input:
  - Many “run on attach” patterns are implemented via `send-keys` (it looks like tmux is “typing”), which is fragile if the pane isn’t at a prompt / is in copy-mode / is running a full-screen program.
  - Prefer `run-shell` hooks + writing OSC notify sequences directly to the attached client’s TTY (`#{client_tty}`) when possible (out-of-band; doesn’t disturb pane input).

### Options to make notifications reach the device reliably

1) Remote-side aggregation (no Android changes)
- Run a small “notify router” on the remote host:
  - All Codex/Claude instances publish messages to a local IPC (UNIX socket / file / tmux hook).
  - Router forwards as `OSC 777 notify` to the **specific tmux pane TTY** that ConnectBot is watching (tracked via a tmux global option like `@connectbot_notify_pane`).
- Pros: simple, no changes to ConnectBot; works well when ConnectBot is attached to a known tmux client/pane.
- Cons: still ultimately depends on “what PTY is ConnectBot reading”; if ConnectBot is disconnected or looking at a different host, messages won’t arrive.

2) Device-side “bus” listener (ConnectBot change or companion app)
- If we want notifications from *any* remote session to reach the phone regardless of which terminal stream is visible, we need a **separate transport**:
  - ConnectBot (or a small companion Android app) opens an outbound connection (e.g., WebSocket) to a server and subscribes to a channel.
  - Remote Codex/Claude instances publish completion messages to that server.
  - The device receives and posts Android notifications locally.
- Pros: robust; does not depend on tmux pane routing; works across many hosts/sessions.
- Cons: needs infrastructure + auth; device must run a long-lived background connection; more security surface area.

### Clicking the notification to “take me to the right tmux pane”

- Minimum: clicking opens ConnectBot to the correct host/session (deep link to the host URI).
- Better: include routing metadata in the message payload (e.g., `hostId`, `tmux_session`, `tmux_window`, `tmux_pane`).
- To automatically jump to the right tmux target, ConnectBot would need a safe way to send a command/keystrokes to the session after opening:
  - Example: send `tmux select-pane -t <pane> \; select-window -t <window>` (or `tmux switch-client -t <session>`).
  - This likely requires an explicit “allow remote control from notifications” setting + authentication, since arbitrary keystroke injection is high risk.

## Click-through links (low priority)

- Implemented tap-to-open links:
  - Plain `http(s)://...` URLs are tappable (and OSC8 hyperlinks are tappable) and open an “Open / Copy / Cancel” dialog.
  - Setting: `tapToOpenLinks` (default now **on**) and it applies immediately when changed in Settings.
  - Setting gates **all** link taps (plain URLs and OSC8 hyperlinks); when off, taps behave like normal terminal taps.
- Must not break long-press selection gestures; keep selection regression tests in the release gate.
- Added stable automated regression test: `org.connectbot.terminal.TerminalLinkTapTest` (uses termlib `TerminalTapController` to simulate a cell tap deterministically; avoids flaky touch injection on GMSaaS).
  - Verified on Genymotion SaaS: `CONNECTBOT_ANDROID_TEST_CLASS=org.connectbot.terminal.TerminalLinkTapTest CONNECTBOT_TERMLIB_VERSION=0.0.18-SNAPSHOT scripts/run_gmsaas_android_test.sh` ✅
  - Extended coverage to include OSC8 hyperlinks (enabled + disabled) and verified locally on Genymotion: `./gradlew :app:connectedGoogleDebugAndroidTest -PconnectbotTermlibVersion=0.0.18-SNAPSHOT -Pandroid.testInstrumentationRunnerArguments.class=org.connectbot.terminal.TerminalLinkTapTest` ✅

## Shell navigation (higher priority)

- **Requirement:** the *remote shell* must respond correctly to standard **readline emacs-mode** and **vi-mode** keybindings using a real keyboard (hardware keyboard or an IME that sends key events).
- **Do not** “solve” this by adding custom buttons to ConnectBot’s extra key row. Leave the extra key row as-is.
- Fix is in the terminal input pipeline (termlib):
  - Use Android `KeyEvent.getUnicodeChar()` (layout-aware) instead of hardcoded `Key → Char` mappings.
  - Treat **Ctrl** as control (e.g., `Ctrl+A` → `0x01`) and **Alt** as Meta/ESC-prefix when Alt didn’t produce a different character (preserve AltGr/international characters).
  - Preserve modifier-encoded special keys (e.g., `Ctrl+Left` → `ESC[1;5D`) so readline can bind them.
- Regression coverage: extend/keep `KeyboardHandlerTest` in termlib to assert:
  - `Ctrl+A` outputs `0x01`
  - `Alt+B` outputs `ESC b`
  - `Ctrl+Left` outputs `ESC[1;5D`
  - “All xterm-style” modified navigation keys (at minimum):
    - `Ctrl+Right` → `ESC[1;5C`
    - `Alt+Left/Right` → `ESC[1;3D` / `ESC[1;3C`
    - `Shift+Tab` → `ESC[Z`
    - `Home/End` → `ESC[H` / `ESC[F`
    - `PageUp/PageDown` → `ESC[5~` / `ESC[6~`
    - `Insert/Delete` → `ESC[2~` / `ESC[3~`
    - `Ctrl+Space` → `NUL (0x00)` (readline/emacs-style mark)

### Current status (Jan 26, 2026)

- Implemented in termlib `KeyboardHandler` (no extra key row changes).
- Extended termlib tests to cover both:
  - “held modifier key” paths (`CtrlLeft` down → `A`)
  - meta-state-only paths (`A` with `META_CTRL_ON`, etc.)
- Fixed `Ctrl+[` (and other Ctrl+punctuation) to emit canonical ASCII control bytes (e.g. ESC) instead of CSI-u sequences (observed: `ESC[91;5u`), so readline/vi-mode works as expected.
- Extended coverage for additional xterm-style navigation bindings (Ctrl/Alt+arrows, Shift+Tab, Home/End, PageUp/Down, Insert/Delete, Ctrl+Space→NUL).
- Stabilized termlib `ShellIntegrationTest` by awaiting the `snapshot` StateFlow instead of calling `processPendingUpdates()` directly (prevents flakiness from async native callbacks).
- Verified on local Genymotion device: `cd /home/arch/termlib && ./gradlew :lib:connectedAndroidTest` ✅
- Build note: publishing termlib to `mavenLocal` may require disabling configuration cache: `cd /home/arch/termlib && ./gradlew --no-configuration-cache :lib:publishToMavenLocal`
- Added ConnectBot integration regression test: `org.connectbot.terminal.TerminalReadlineKeybindingsTest` (validates Ctrl+A/Ctrl+E/Ctrl+Left/Ctrl+[ and Alt+B/Alt+F via the `Terminal` composable).
- Verified on local Genymotion device: `./gradlew :app:connectedGoogleDebugAndroidTest -PconnectbotTermlibVersion=0.0.18-SNAPSHOT -Pandroid.testInstrumentationRunnerArguments.class=org.connectbot.terminal.TerminalReadlineKeybindingsTest` ✅
- Verified on Genymotion SaaS: `CONNECTBOT_ANDROID_TEST_CLASS=org.connectbot.terminal.TerminalReadlineKeybindingsTest CONNECTBOT_TERMLIB_VERSION=0.0.18-SNAPSHOT scripts/run_gmsaas_android_test.sh` ✅

### Troubleshooting (Ctrl+Left doesn’t skip a word)

- ConnectBot should emit `ESC[1;5D` / `ESC[1;5C` for Ctrl+Left/Ctrl+Right (xterm-style).
- If `cat -v` shows `^[[D` (plain Left Arrow) even when holding Ctrl, it can be an Android keyboard meta-state quirk: some devices set only `META_CTRL_LEFT_ON`/`META_CTRL_RIGHT_ON` (not `META_CTRL_ON`). termlib now treats the full left/right ctrl meta masks as “Ctrl held” so Ctrl+arrows emit the correct xterm sequences.
- If the remote shell doesn’t move by word, it’s usually a **readline keybinding** issue on the remote host (or inside tmux). Quick checks:
  - In the remote shell, run `cat -v`, press Ctrl+Left, and confirm you see `^[[1;5D`.
  - Add to remote `~/.inputrc` (bash/readline):
    - `"\e[1;5D": backward-word`
    - `"\e[1;5C": forward-word`
    - (optional compat) `"\e[5D": backward-word` / `"\e[5C": forward-word`
  - If using tmux, enable xterm-style modified keys: `set -g xterm-keys on`

### Toggling emacs/vi editing-mode (remote shell)

- This is controlled by the **remote** readline client (bash, etc.), not ConnectBot.
- Bash interactive toggle commands:
  - `set -o emacs`
  - `set -o vi`
- Readline init file (`~/.inputrc`) defaults:
  - `set editing-mode emacs` (or `vi`)
  - Optional keybinds (pick combos you don’t already use): bind `emacs-editing-mode` / `vi-editing-mode`.

## Genymotion release gate helper (Jan 26, 2026)

- Added `scripts/run_gmsaas_release_gate.sh` to run the small “publish gate” suite on a single GMSaaS instance:
  - `org.connectbot.terminal.TerminalClipboardSelectionTest`
  - `org.connectbot.terminal.TerminalLinkTapTest`
  - `org.connectbot.terminal.TerminalReadlineKeybindingsTest`
- Verified on Genymotion SaaS: `CONNECTBOT_TERMLIB_VERSION=0.0.18-SNAPSHOT scripts/run_gmsaas_release_gate.sh` ✅
- Re-ran on Genymotion SaaS (recipe `d212b329-aacd-4fe5-aa76-3480f12a6200`) on Jan 26, 2026: ✅ (instance `45791ba8-c100-46ee-b731-2d42690d15a0`)
- Re-ran after extending xterm-style keyboard coverage on **Jan 27, 2026**: ✅ (instance `e1ca85e9-7b30-46d0-8650-6f7bb500526a`)
