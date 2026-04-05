## Session Automation Intents

This branch adds an explicit automation action for ConnectBot sessions:

- Action: `org.connectbot.action.AUTOMATE_SESSION`
- Data URI: the normal host URI, for example `ssh://user@example.com:22/#prod` or `local://#Local`

Automation is only honored when the user enables:

- Settings -> Allow automation links

Without that setting, the intent still opens or reuses the session, but no text or keys are injected.

### Extras

- `org.connectbot.extra.AUTOMATION_TEXT`
  - Optional text to inject into the session.
  - Supports C-style escapes like `\n`, `\r`, `\t`, `\\`, `\u0002`, `\x1b`.
- `org.connectbot.extra.AUTOMATION_APPEND_NEWLINE`
  - Optional boolean.
  - If `true`, appends a trailing newline after the decoded text.
- `org.connectbot.extra.AUTOMATION_KEYS`
  - Optional comma-separated key list.
  - Supported names: `ENTER`, `TAB`, `BACKSPACE`, `ESC`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `HOME`, `END`, `PGUP`, `PGDN`, `INS`, `DEL`, `F1`..`F12`
  - Supported modifiers: `CTRL+`, `ALT+`, `SHIFT+`
  - Example: `CTRL+LEFT,ENTER,F2`
- `org.connectbot.extra.AUTOMATION_DELAY_MS`
  - Optional delay before the automation runs.

### Behavior

- If a matching session is already active, ConnectBot jumps to it and queues the automation immediately.
- If the session is not active, ConnectBot opens it first and runs the automation after the session is connected.
- Matching uses the same URI/nickname rules as normal deep links.

### Examples

Open or reuse a host, then run a shell command:

```bash
adb shell am start \
  -n org.connectbot/org.connectbot.ui.MainActivity \
  -a org.connectbot.action.AUTOMATE_SESSION \
  -d 'ssh://user@example.com:22/#prod' \
  --es org.connectbot.extra.AUTOMATION_TEXT 'tmux select-pane -L' \
  --ez org.connectbot.extra.AUTOMATION_APPEND_NEWLINE true
```

Send a tmux prefix with raw control text, then a follow-up command:

```bash
adb shell am start \
  -n org.connectbot/org.connectbot.ui.MainActivity \
  -a org.connectbot.action.AUTOMATE_SESSION \
  -d 'ssh://user@example.com:22/#prod' \
  --es org.connectbot.extra.AUTOMATION_TEXT '\u0002o'
```

Send navigation keys to an existing session:

```bash
adb shell am start \
  -n org.connectbot/org.connectbot.ui.MainActivity \
  -a org.connectbot.action.AUTOMATE_SESSION \
  -d 'local://#Local' \
  --es org.connectbot.extra.AUTOMATION_KEYS 'UP,UP,ENTER'
```
