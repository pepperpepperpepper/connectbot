# Releasing ConnectBot (afteroid / F-Droid)

This repo has **two different UI/code lines** that can both be built with a `google` flavor:

1) **Legacy / “blue UI” (v1.9.13-era)** — this is what we publish.
2) **Newer / “black UI” (Compose-style)** — do **not** publish (even if building the `google` flavor).

The “google vs oss” choice is a **product flavor** (Google Play Services vs Conscrypt provider); it is **not** what makes the UI blue vs black.

## Canonical release checkout

Always publish from:

- `~/connectbot-release` (symlink to the legacy v1.9.13-era checkout)

Do **not** publish from:

- `~/connectbot` (this checkout; newer UI line)

## Publishing rule (must-follow)

- Only publish the **`google`** flavor.
- Refuse/avoid publishing anything with `versionName` ending in `-oss`.

## Release command (google-only)

From the legacy checkout:

```bash
cd ~/connectbot-release
AFTEROID_SYNC=1 scripts/publish_afteroid_google.sh <versionName> <versionCode>
```

This will:
- build `:app:assembleGoogleRelease`
- verify the APK package/version (and that it is not `-oss`)
- stage into `~/fdroid/unsigned/`
- update `~/fdroid/metadata/org.connectbot.yml`
- run `fdroid publish` + `fdroid update`
- sync to S3 + invalidate CloudFront (`AFTEROID_SYNC=1`)

## Quick sanity check (before publishing)

```bash
cd ~/connectbot-release
aapt dump badging app/build/outputs/apk/google/release/app-google-release-unsigned.apk | head -n 1
```

You should see:
- `package: name='org.connectbot' …`
- `versionName='<the value you passed>'` and **no** `-oss` suffix.

## Install gotcha (device)

If you previously installed ConnectBot from **Play Store** or **official F-Droid**, Android will block upgrading due to a **different signing key**.

Fix: uninstall ConnectBot, then install from the afteroid repo (`https://fdroid.uh-oh.wtf/repo`).

