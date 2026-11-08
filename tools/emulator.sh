#!/usr/bin/env bash
# Start the "etracker" emulator (creates it on first run), install the debug APK
# and launch the app.   Usage: tools/emulator.sh [--headless]
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
AVD=etracker
IMG="system-images;android-35;google_apis;x86_64"

if ! avdmanager list avd -c | grep -qx "$AVD"; then
  echo "no" | avdmanager create avd -n "$AVD" -k "$IMG" -d pixel_6 >/dev/null
  echo "created AVD $AVD"
fi

ARGS=(-avd "$AVD" -gpu swiftshader_indirect -no-snapshot -no-boot-anim)
[ "${1:-}" = "--headless" ] && ARGS+=(-no-window -no-audio)
nohup emulator "${ARGS[@]}" >/tmp/etracker-emulator.log 2>&1 &

echo "waiting for the emulator to boot…"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done

./gradlew -q installDebug
adb shell am start -n app.etracker.tracker/.MainActivity >/dev/null
echo "app installed and launched"
echo "(emulator reaches your dev server at http://10.0.2.2:8080)"
