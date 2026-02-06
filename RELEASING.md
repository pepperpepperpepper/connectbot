# Releasing ConnectBot (afteroid / F-Droid)

This checkout is the **legacy v1.9.13-era (“blue UI”)** ConnectBot codebase that we publish to afteroid.

## Publishing rules

- Only publish the **`google`** flavor.
- Do not publish anything with `versionName` ending in `-oss`.

## Release command (google-only)

```bash
cd ~/connectbot-release
AFTEROID_SYNC=1 scripts/publish_afteroid_google.sh <versionName> <versionCode>
```

Notes:
- `~/connectbot-release` is a symlink to this repo, used as the canonical release path.
- Omit `AFTEROID_SYNC=1` to publish locally without syncing to S3/CloudFront.

## Install gotcha (device)

If ConnectBot was previously installed from **Play Store** or **official F-Droid**, Android will refuse to upgrade due to a **different signing key**.

Fix: uninstall ConnectBot, then install from the afteroid repo (`https://fdroid.uh-oh.wtf/repo`).

