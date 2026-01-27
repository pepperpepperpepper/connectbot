# Submitting a Pull Request (ConnectBot + termlib)

This repo (`connectbot`) depends on a separate repo/library (`termlib`). For the terminal copy/selection bug we fixed, the **actual bugfix is in `termlib`**, and ConnectBot only needs to **consume** that fixed termlib version (plus optional regression tests).

## 0) Decide where your PR should go

- **Bugfix PR:** `termlib` (selection/copy when scrolled).
- **Consumer PR:** `connectbot` (bump termlib dependency + optional regression test harness).

Keep these as separate PRs unless you have a strong reason to couple them.

## 1) Prereqs

- A GitHub account.
- Fork the repo you’re changing:
  - `termlib`: fork the termlib repo you use internally (example: `https://github.com/pepperpepperpepper/termlib`).
  - `connectbot`: fork `https://github.com/pepperpepperpepper/connectbot`.
- Ensure git identity is set once:
  - `git config --global user.name "Your Name"`
  - `git config --global user.email "you@example.com"`

## 2) Standard git workflow (works for both repos)

In the repo you’re changing:

1. Add upstream remote (one-time per clone):
   - `git remote add upstream <UPSTREAM_GIT_URL>`
2. Sync main:
   - `git checkout main`
   - `git fetch upstream`
   - `git rebase upstream/main`
3. Create a feature branch:
   - `git checkout -b fix-terminal-selection-when-scrolled`
4. Make changes and run relevant tests (see below).
5. Commit:
   - `git add -A`
   - `git commit -m "Fix selection copy when scrolled"`
6. Push branch to your fork:
   - `git push -u origin fix-terminal-selection-when-scrolled`
7. Open a PR:
   - Go to your fork on GitHub, click “Compare & pull request”.
   - Target base repo `upstream/main`.

## 3) termlib PR checklist (bugfix repo)

### What to include

- The fix in termlib selection logic:
  - `lib/src/main/java/org/connectbot/terminal/SelectionManager.kt`
- A unit test proving the scrolled selection behavior:
  - `lib/src/test/java/org/connectbot/terminal/SelectionManagerTest.kt`

### What to run locally

- Unit tests:
  - `./gradlew :lib:test`

### PR description (copy/paste template)

**Problem**
- When scrolled up (`scrollbackPosition > 0`), the visible viewport can include both scrollback and current screen lines. Copying a selection that lands in the “screen” portion could return the wrong text (often empty), leaving the clipboard unchanged.

**Fix**
- Map viewport row -> “actual index” across scrollback+screen and read from scrollback *or* `snapshot.lines` as appropriate.

**Tests**
- Added unit test covering selection text extraction when scrolled.

## 4) ConnectBot PR checklist (consumer repo)

### Preferred approach (once termlib is released)

1. Bump the termlib dependency version in:
   - `gradle/libs.versions.toml`
2. Remove any local-only termlib overrides you don’t want long-term.
3. Run:
   - `./gradlew :app:test`
   - Optional device test (see below)

### Optional: regression test harness (Genymotion)

If you’re keeping the instrumentation regression test and Genymotion runner:

- Instrumentation test:
  - `app/src/androidTest/java/org/connectbot/terminal/TerminalClipboardSelectionTest.kt`
  - It is gated by the runner arg `connectbot_enable_terminal_clipboard_test` so it won’t fail CI by default.
- Genymotion SaaS runner script:
  - `scripts/run_gmsaas_android_test.sh`

Run on Genymotion (google flavor):

```bash
scripts/run_gmsaas_android_test.sh
```

Or explicitly:

```bash
./gradlew :app:connectedGoogleDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.connectbot.terminal.TerminalClipboardSelectionTest \
  -Pandroid.testInstrumentationRunnerArguments.connectbot_enable_terminal_clipboard_test=true
```

### Important policy note (internal)

For our internal workflows: **only the `google` flavor is representative** for testing/publishing; the `oss` flavor is not used for our purposes.

## 5) Don’t accidentally include local-only files

Before pushing, verify you’re not committing secrets or machine-local artifacts:

- `git status`
- `git diff --stat`

Examples of things that should not go into PRs:
- `~/.api-keys` and any local key material
- Local F-Droid release notes/instructions (we keep `fdroid-instructions.md` gitignored)

