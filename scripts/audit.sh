#!/usr/bin/env bash
# Fails the build if the app asks for network access or contains network code.
set -euo pipefail
cd "$(dirname "$0")/.."
if grep -n 'permission.INTERNET\|ACCESS_NETWORK_STATE' app/src/main/AndroidManifest.xml; then echo "AUDIT FAILED: network permission"; exit 1; fi
if grep -rnE 'java\.net|okhttp|HttpURLConnection|https?://[a-z]' app/src/main/java; then echo "AUDIT FAILED: network code or URL in sources"; exit 1; fi
if grep -nE 'implementation\(' app/build.gradle.kts | grep -Ei 'firebase|analytics|crashlytics|okhttp|retrofit|ads'; then echo "AUDIT FAILED: tracking or network dependency"; exit 1; fi
echo "AUDIT PASSED: no internet permission, no network code, no trackers"
