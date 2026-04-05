## Forked Termlib

This branch is pinned to the forked termlib build:

- repo: `git@github.com:pepperpepperpepper/termlib.git`
- branch: `connectbot-0.0.18-fork.1`
- version: `0.0.18-fork.1-SNAPSHOT`

ConnectBot expects that forked version to be published to `mavenLocal`.

### Publish termlib

```bash
cd ../termlib
git fetch fork
git checkout connectbot-0.0.18-fork.1
./gradlew --no-daemon --no-configuration-cache :lib:publishMavenPublicationToMavenLocal
```

### Build ConnectBot

No extra override is needed after that because this branch already pins:

- `org.connectbot:termlib:0.0.18-fork.1-SNAPSHOT`

Example:

```bash
cd ../connectbot
./gradlew :app:connectedGoogleDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.connectbot.MainActivityTest#automationIntent_localUri_runsCommandOnNewSession,org.connectbot.MainActivityTest#automationIntent_reusesExistingSession
```

### Why this exists

This avoids depending on the generic upstream `0.0.18-SNAPSHOT` artifact whose Java and native pieces may not match. The forked version is the tested termlib build used by this ConnectBot branch.
