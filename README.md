# eTracker Device (Android)

Companion app for the eTracker server. A phone **enrols** with a one-time code
from the web app, then **reports its GPS position**, which the server draws on
the Situation Map with the unit's symbol (alongside the plan, IPB, watch log and
the other live devices).

Native Kotlin + Jetpack Compose. Server contract: `docs/DEVICE_API.md` in the
eTracker server repo (summarised below).

## What it does

* **Onboarding** — scan the QR from the web app's *Devices* page (Google code
  scanner, no camera permission) or type server + code. The code is single use
  and expires after 24 h.
* **Reporting** — a foreground service (persistent notification) requests GPS at
  the interval the server suggests (default 30 s).
* **Offline-safe** — every fix is written to a local SQLite queue first and only
  deleted once the server accepts it. Dead zones, crashes and reboots lose
  nothing; the queue flushes in batches of 200 with exponential backoff.
* **Revocation** — if the server rejects the token (operator revoked the
  device) reporting stops and the app returns to onboarding.
* **Secure storage** — the device token lives in `EncryptedSharedPreferences`;
  the server stores only a hash. Release builds are HTTPS-only.

## Build

JDK 17 + Android SDK 35 (or Android Studio Ladybug or newer). The Gradle wrapper
is included; point it at your SDK with `local.properties` (`sdk.dir=/path/to/sdk`,
git-ignored) or `ANDROID_HOME`.

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # to a connected device / running emulator
```

`source tools/env.sh` puts a toolchain installed under `~/tools` on your PATH.

The QR scanner and location need Google Play services (a normal phone, or an
emulator image with "Google APIs" / "Google Play").

**Talking to a dev server.** Debug builds allow plain HTTP. From the Android
emulator the host machine is `http://10.0.2.2:8080`; on a real phone use the
machine's LAN address. Release builds require HTTPS.

## Try it in an emulator first

`tools/emulator.sh` creates an emulator (Pixel 6, Android 15, Google APIs),
installs the debug APK and launches it. Add `--headless` for no window.

In the emulator: *Extended controls (…) → Location* sets a position or plays a
GPX route; the app reports it like a real GPS. Enrol with server
`http://10.0.2.2:8080` and a code from the web app's *Devices* page (type it —
the emulator's camera won't read a QR on your monitor unless you point the
virtual scene at it).

## Try it without a phone

`tools/simulate_device.py` makes the same requests as the app (JSON POST,
ISO-8601 `Z` timestamps, batched `post_positions`):

```
./tools/simulate_device.py --site http://localhost:8080 --code ABCD-1234 --steps 10 --interval 2
./tools/simulate_device.py --site http://localhost:8080 --code ABCD-1234 --offline-burst 20   # queue flush
```

## Protocol (summary)

All calls are `POST /api/method/<method>` with a JSON body; no Frappe login.

| Call | Body | Result |
|---|---|---|
| `etracker.devices.enrol_device` | `{code}` | `{token, device, label, unit, sidc, interval_s, site}` |
| `etracker.devices.post_positions` | `{token, positions:[{lat, lon, ts, accuracy?, speed?, heading?}]}` (≤ 200) | `{ok, accepted, interval_s}` |

`401/403` = token unknown or revoked (stop, re-enrol). `429`/`5xx`/network =
keep the queue and retry.

## Layout

```
app/src/main/java/app/etracker/tracker/
  MainActivity.kt
  data/     Session (encrypted token), Api (HTTP), FixQueue (SQLite), TrackerState
  service/  TrackingService (foreground GPS + delivery)
  ui/       TrackerApp (onboarding + status), Theme
tools/simulate_device.py
```

## Status

First version. It **compiles and builds a debug APK**. The server side of the
protocol has been exercised end to end with `simulate_device.py` (enrol, single
and batched posts, burned code rejected, positions stored and shown). **Verified on an Android 15 emulator** against a live server: enrolment, reporting
(foreground service, ±5 m fixes reaching the server and the live map), offline
queueing and flush on reconnect (2 queued -> 0, server count rose), and
revocation (app returns to onboarding, service stops). **Not yet tried on a
physical phone**, and QR scanning is untested (it needs a camera).

Not done yet: auto-start after reboot, background-location permission flow
(not needed while the service is started from the foreground), battery
optimisation prompt, symbol rendering on the phone (shows the unit name),
release signing config, CI.
