#!/usr/bin/env bash
set -euo pipefail

# Builds + publishes the Google flavor to the local afteroid/F-Droid repo.
#
# Usage:
#   scripts/publish_afteroid_google.sh [<versionName> <versionCode>]
#
# If versionName/versionCode are omitted ("auto" mode), the appVersioning
# Gradle plugin derives them from the current git tag and they are read back
# from the built APK. This is the mode used by the release-tag pre-push hook.
#
# Env:
#   FDROID_DIR (default: ~/fdroid)
#   FDROID_APPID (default: org.connectbot)
#   AFTEROID_SYNC=1 to also sync to S3 + invalidate CloudFront after publishing
#   SYNC_ONLY=1     to ONLY sync the existing repo to S3 + CloudFront (no build,
#                   no re-publish). Use this for the manual go-live step after
#                   the pre-push hook has already published locally. No version
#                   args needed. Implies the sync; AFTEROID_SYNC is not required.
#
# Notes:
#   - This fork ships the Compose build's Google flavor from this checkout.
#   - It refuses to publish the oss flavor (versionName suffix "-oss").
#   - Re-running the full build+publish for an already-published versionCode
#     fails (fdroid refuses an APK present in both unsigned/ and repo/). Use
#     SYNC_ONLY=1 to push an already-published version live.

VERSION_NAME="${1:-}"
VERSION_CODE="${2:-}"

FDROID_DIR="${FDROID_DIR:-${HOME}/fdroid}"
APPID="${FDROID_APPID:-org.connectbot}"

# Sync the already-built local repo to S3 and invalidate CloudFront. Used both
# by the optional post-publish sync (AFTEROID_SYNC=1) and the standalone
# SYNC_ONLY=1 go-live step.
do_sync() {
  if ! command -v aws >/dev/null 2>&1; then
    echo "Error: aws is required for syncing (not found on PATH)." >&2
    exit 2
  fi

  : "${FDROID_AWS_BUCKET:?Missing FDROID_AWS_BUCKET for sync}"
  : "${FDROID_AWS_CF_DISTRIBUTION_ID:?Missing FDROID_AWS_CF_DISTRIBUTION_ID for sync}"

  echo "Syncing repo/archive to S3…"
  aws s3 sync "${FDROID_DIR}/repo" "s3://${FDROID_AWS_BUCKET}/repo" --only-show-errors
  aws s3 sync "${FDROID_DIR}/archive" "s3://${FDROID_AWS_BUCKET}/archive" --only-show-errors

  echo "Invalidating CloudFront cache…"
  aws cloudfront create-invalidation \
    --distribution-id "${FDROID_AWS_CF_DISTRIBUTION_ID}" \
    --paths "/repo/*" "/archive/*"
}

if [[ ! -d "${FDROID_DIR}" ]]; then
  echo "Error: FDROID_DIR does not exist: ${FDROID_DIR}" >&2
  exit 2
fi

# SYNC_ONLY: push the existing local repo live without rebuilding or
# re-publishing. Skips all build/publish prerequisites and version handling.
if [[ "${SYNC_ONLY:-}" == "1" ]]; then
  echo "SYNC_ONLY=1 → syncing existing repo to S3 + CloudFront (no build/publish)…"
  do_sync
  echo "Sync complete ✅"
  exit 0
fi

if ! command -v aapt >/dev/null 2>&1; then
  echo "Error: aapt is required to verify the built APK (not found on PATH)." >&2
  exit 2
fi

if ! command -v fdroid >/dev/null 2>&1; then
  echo "Error: fdroid is required for publishing (not found on PATH)." >&2
  exit 2
fi

# Either both version args are given (explicit mode) or neither (auto mode,
# where the appVersioning plugin derives the version from the current git tag).
AUTO_VERSION=0
if [[ -z "${VERSION_NAME}" && -z "${VERSION_CODE}" ]]; then
  AUTO_VERSION=1
elif [[ -z "${VERSION_NAME}" || -z "${VERSION_CODE}" ]]; then
  echo "Error: provide both <versionName> and <versionCode>, or neither (auto mode)." >&2
  exit 2
fi

if [[ "${AUTO_VERSION}" == "1" ]]; then
  echo "Building googleRelease (auto version from git tag)…"
  ./gradlew --no-daemon :app:assembleGoogleRelease
else
  echo "Building googleRelease (${VERSION_NAME}, ${VERSION_CODE})…"
  ./gradlew --no-daemon :app:assembleGoogleRelease \
    -PforceVersionName="${VERSION_NAME}" \
    -PforceVersionCode="${VERSION_CODE}"
fi

APK_PATH="app/build/outputs/apk/google/release/app-google-release-unsigned.apk"
if [[ ! -f "${APK_PATH}" ]]; then
  echo "Error: expected APK not found at ${APK_PATH}" >&2
  exit 1
fi

BADGING="$(aapt dump badging "${APK_PATH}")"
BADGING="${BADGING%%$'\n'*}"
echo "${BADGING}"

# In auto mode, read the plugin-generated version back from the built APK so it
# stays the single source of truth for staging, metadata, and fdroid publish.
if [[ "${AUTO_VERSION}" == "1" ]]; then
  VERSION_CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"${BADGING}")"
  VERSION_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"${BADGING}")"
  if [[ -z "${VERSION_CODE}" || -z "${VERSION_NAME}" ]]; then
    echo "Error: could not read versionCode/versionName from built APK." >&2
    exit 1
  fi
  echo "Resolved version from APK: ${VERSION_NAME} (${VERSION_CODE})"
fi

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
text2, n_name = re.subn(r"^CurrentVersion:.*$", f"CurrentVersion: {version_name}", text, flags=re.M)
text2, n_code = re.subn(r"^CurrentVersionCode:.*$", f"CurrentVersionCode: {version_code}", text2, flags=re.M)

# Error only if the fields are genuinely absent; an unchanged (already
# up-to-date) value is fine and must not abort an idempotent re-run.
if n_name == 0 or n_code == 0:
    raise SystemExit("Error: did not find CurrentVersion/CurrentVersionCode to update in metadata.")

if text2 != text:
    path.write_text(text2, encoding="utf-8")
PY
else
  echo "Warning: metadata missing at ${METADATA_PATH}; skipping CurrentVersion updates." >&2
fi

echo "Signing + indexing via fdroid…"
(cd "${FDROID_DIR}" && fdroid publish "${APPID}:${VERSION_CODE}" && fdroid update)

if [[ "${AFTEROID_SYNC:-}" == "1" ]]; then
  do_sync
fi

echo "Publish complete ✅"

