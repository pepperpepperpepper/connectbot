#!/usr/bin/env bash
set -euo pipefail

# Builds + publishes the legacy (blue UI) Google flavor to the local afteroid/F-Droid repo.
#
# Usage:
#   scripts/publish_afteroid_google.sh <versionName> <versionCode>
#
# Env:
#   FDROID_DIR (default: ~/fdroid)
#   FDROID_APPID (default: org.connectbot)
#   AFTEROID_SYNC=1 to also sync to S3 + invalidate CloudFront
#
# Notes:
#   - This script is meant to be run from the legacy v1.9.13-era codebase checkout.
#   - It refuses to publish the oss flavor (versionName suffix "-oss").

VERSION_NAME="${1:?Usage: scripts/publish_afteroid_google.sh <versionName> <versionCode>}"
VERSION_CODE="${2:?Usage: scripts/publish_afteroid_google.sh <versionName> <versionCode>}"

FDROID_DIR="${FDROID_DIR:-${HOME}/fdroid}"
APPID="${FDROID_APPID:-org.connectbot}"

if [[ ! -f "app/src/main/java/org/connectbot/ConsoleActivity.java" ]]; then
  echo "Error: this does not look like the legacy (blue UI) ConnectBot checkout." >&2
  echo "Expected to find: app/src/main/java/org/connectbot/ConsoleActivity.java" >&2
  echo "Use: ~/connectbot-release (symlink to the legacy checkout) for publishing." >&2
  exit 2
fi

if [[ ! -d "${FDROID_DIR}" ]]; then
  echo "Error: FDROID_DIR does not exist: ${FDROID_DIR}" >&2
  exit 2
fi

if ! command -v aapt >/dev/null 2>&1; then
  echo "Error: aapt is required to verify the built APK (not found on PATH)." >&2
  exit 2
fi

if ! command -v fdroid >/dev/null 2>&1; then
  echo "Error: fdroid is required for publishing (not found on PATH)." >&2
  exit 2
fi

echo "Building googleRelease (${VERSION_NAME}, ${VERSION_CODE})…"
./gradlew --no-daemon :app:assembleGoogleRelease \
  -PforceVersionName="${VERSION_NAME}" \
  -PforceVersionCode="${VERSION_CODE}"

APK_PATH="app/build/outputs/apk/google/release/app-google-release-unsigned.apk"
if [[ ! -f "${APK_PATH}" ]]; then
  echo "Error: expected APK not found at ${APK_PATH}" >&2
  exit 1
fi

BADGING="$(aapt dump badging "${APK_PATH}")"
BADGING="${BADGING%%$'\n'*}"
echo "${BADGING}"

if [[ "${BADGING}" != package:\ name=\'${APPID}\'* ]]; then
  echo "Error: APK packageName mismatch; expected ${APPID}" >&2
  exit 1
fi
if [[ "${BADGING}" != *"versionCode='${VERSION_CODE}'"* ]]; then
  echo "Error: APK versionCode mismatch; expected ${VERSION_CODE}" >&2
  exit 1
fi
if [[ "${BADGING}" != *"versionName='${VERSION_NAME}'"* ]]; then
  echo "Error: APK versionName mismatch; expected ${VERSION_NAME}" >&2
  exit 1
fi
if [[ "${BADGING}" == *"-oss'"* ]]; then
  echo "Error: refusing to publish an -oss build." >&2
  exit 1
fi

DEST_UNSIGNED="${FDROID_DIR}/unsigned/${APPID}_${VERSION_CODE}.apk"
echo "Staging APK → ${DEST_UNSIGNED}"
cp "${APK_PATH}" "${DEST_UNSIGNED}"

METADATA_PATH="${FDROID_DIR}/metadata/${APPID}.yml"
if [[ -f "${METADATA_PATH}" ]]; then
  echo "Updating metadata → ${METADATA_PATH}"
  python3 - <<PY "${METADATA_PATH}" "${VERSION_NAME}" "${VERSION_CODE}"
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
version_name = sys.argv[2]
version_code = sys.argv[3]

text = path.read_text(encoding="utf-8")
text2 = re.sub(r"^CurrentVersion:.*$", f"CurrentVersion: {version_name}", text, flags=re.M)
text2 = re.sub(r"^CurrentVersionCode:.*$", f"CurrentVersionCode: {version_code}", text2, flags=re.M)

if text2 == text:
    raise SystemExit("Error: did not find CurrentVersion/CurrentVersionCode to update in metadata.")

path.write_text(text2, encoding="utf-8")
PY
else
  echo "Warning: metadata missing at ${METADATA_PATH}; skipping CurrentVersion updates." >&2
fi

echo "Signing + indexing via fdroid…"
(cd "${FDROID_DIR}" && fdroid publish "${APPID}:${VERSION_CODE}" && fdroid update)

if [[ "${AFTEROID_SYNC:-}" == "1" ]]; then
  if ! command -v aws >/dev/null 2>&1; then
    echo "Error: AFTEROID_SYNC=1 but aws is not found on PATH." >&2
    exit 2
  fi

  : "${FDROID_AWS_BUCKET:?Missing FDROID_AWS_BUCKET for AFTEROID_SYNC=1}"
  : "${FDROID_AWS_CF_DISTRIBUTION_ID:?Missing FDROID_AWS_CF_DISTRIBUTION_ID for AFTEROID_SYNC=1}"

  echo "Syncing repo/archive to S3…"
  aws s3 sync "${FDROID_DIR}/repo" "s3://${FDROID_AWS_BUCKET}/repo" --only-show-errors
  aws s3 sync "${FDROID_DIR}/archive" "s3://${FDROID_AWS_BUCKET}/archive" --only-show-errors

  echo "Invalidating CloudFront cache…"
  aws cloudfront create-invalidation \
    --distribution-id "${FDROID_AWS_CF_DISTRIBUTION_ID}" \
    --paths "/repo/*" "/archive/*"
fi

echo "Publish complete ✅"

