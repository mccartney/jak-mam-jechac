#!/usr/bin/env bash
# Build the debug APK, install it on the USB-connected phone (alongside the release app) and launch it.
# With several devices attached, pick one: ANDROID_SERIAL=<serial> ./deploy-debug.sh
set -euo pipefail
cd "$(dirname "$0")"

./gradlew -q :app:installDebug
adb shell am start -n pl.waw.oledzki.jmj.debug/pl.waw.oledzki.jmj.MainActivity
