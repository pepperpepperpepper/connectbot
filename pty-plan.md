# PTY Web Relay Plan (tmux/zellij) — avoid `send-keys` fragility

## Goal

Build a web UI that behaves like a real terminal connected to a remote tmux/zellij session (interactive apps, cursor movement, full-screen TUI programs, copy/paste, etc.).

## Why `tmux send-keys` is fragile

`send-keys` is **keystroke injection into a pane**, not “run a command inside the shell”.

- **Wrong context**: if the pane is in `vim`, `less`, a REPL, `ssh`, a password prompt, or tmux copy-mode, the injected text becomes unintended input.
- **Not at a prompt**: even in a shell, it might be mid-command (open quote), running a foreground job, or output is racing with your injected keys.
- **Special keys are hard**: you must map Enter/Backspace/arrows/Ctrl+… into the exact escape sequences the app expects.
- **Terminal features bypassed**: bracketed paste, IME composition, mouse reporting, and some modifier semantics won’t match a real client.
- **Concurrency**: multiple sources can interleave input into the same pane unless you serialize it.
- **Safety/security**: accidental destructive commands, leaking secrets, or injecting into the wrong pane/program.

If you want “a web page that acts like a terminal”, `send-keys` is the wrong abstraction.

## Recommended approach: a real PTY per web client

Make the web UI a terminal emulator (xterm.js or similar) connected to a **server-side PTY**.

### High-level architecture

- Browser:
  - terminal renderer (e.g., xterm.js)
  - WebSocket to the server (binary)
  - sends keystrokes as bytes; receives output bytes; sends resize events
- Server:
  - allocates a PTY per web session
  - launches `tmux attach -t <target>` (or `zellij attach <target>`) *inside the PTY*
  - forwards bytes between PTY ↔ WebSocket

### State management (PTY approach)

You manage **very little** application state:

- Mapping: `{web_session_id → pty_fd → tmux/zellij target}`
- Lifecycle:
  - Reconnect: keep PTY alive for some idle timeout; on reconnect, reattach to the same PTY if it exists.
  - Or: treat PTY as ephemeral and rely on tmux/zellij persistence; reconnect just starts a new PTY and re-attaches.
- Resizing: propagate browser rows/cols to the PTY (`TIOCSWINSZ`) so TUIs render correctly.
- Auth: user identity → allowed targets (which tmux sessions/panes they may attach).

Everything else (“where the cursor is”, “what’s running”, “copy-mode”, “full-screen apps”) is naturally handled by the terminal + multiplexer.

### Benefits

- Works with full-screen TUIs and complex terminal modes.
- No guessing about prompt readiness.
- Correct handling of escape sequences, bracketed paste, etc.
- Output is naturally streamed; no need for `capture-pane` polling.

## If you insist on `send-keys`: treat it as a state machine (hard mode)

To make `send-keys` tolerable, you need to *invent* terminal/session state:

- Target selection:
  - stable pane identity (`%pane_id`) and a mapping layer (`user → allowed pane IDs`)
- Input gating (“safe to inject?”):
  - check copy-mode, check if pane is active, check what’s running (best-effort)
  - serialize per-pane (queue + lock) so inputs don’t interleave
- Prompt-ready detection (shell integration):
  - make the shell emit a sentinel when it’s ready (e.g., `PROMPT_COMMAND` writes a marker)
  - only allow “send a command” when you’ve seen the sentinel recently
- Output sync:
  - incremental: `pipe-pane` to a stream/log
  - periodic resync: `capture-pane` snapshots
  - and you still need a terminal emulator to interpret escapes for display
- Special keys:
  - map to escape sequences (arrows/Home/End/Ctrl+Left/etc.) consistently per `$TERM`

Even then, it will never be as robust as a PTY-driven terminal.

## How this relates to “task done” notifications

- Notifications can be treated separately from interactive control:
  - For tmux: prefer hooks + `run-shell` that write OSC notify to the **attached client TTY** (`#{client_tty}`), not to a pane via `send-keys`.
  - For a web terminal: the server can emit OSC notify sequences to the browser UI, and/or publish to a separate notification bus.

## Next concrete step

Decide which mode you want:

1) **Web terminal (PTY + WebSocket)**: most robust; minimal “state” code.
2) **Command relay (send-keys)**: only viable if you restrict scope (command-at-prompt only, no full-screen apps) and accept fragility.

