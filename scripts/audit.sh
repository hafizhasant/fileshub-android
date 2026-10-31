#!/usr/bin/env bash
# Network code is allowed only in Net.kt and only for user-added FTP/SMB servers.
set -euo pipefail
cd "$(dirname "$0")/.."
if grep -rlE 'java\.net|HttpURLConnection|okhttp|retrofit|https?://[a-z]' app/src/main/java | grep -v '/Net\.kt$'; then echo "AUDIT FAILED: network code outside Net.kt"; exit 1; fi
if grep -nE 'https?://[a-z]' app/src/main/java/org/cairn/files/Net.kt; then echo "AUDIT FAILED: hard-coded URL in Net.kt"; exit 1; fi
if grep -nE 'implementation\(' app/build.gradle.kts | grep -Ei 'firebase|analytics|crashlytics|okhttp|retrofit|admob|ads|sentry'; then echo "AUDIT FAILED: tracking or HTTP dependency"; exit 1; fi
echo "AUDIT PASSED: network code only in Net.kt, no hard-coded hosts, no trackers"
