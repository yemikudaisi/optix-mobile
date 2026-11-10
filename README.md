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
* **Situation tab** — a read-only common operating picture for the device's
  operation: the plan (phases switchable), other units' live positions drawn as
  military symbols, positioned watch-log entries, your own position ("YOU"), an
  MGRS grid and centre-of-map coordinates. Refreshes every ~10 s while open and
  keeps the last picture for offline use. What a device may see is set per device
  on the Devices page (*Own operation* / *None*; the threat/IPB layer is a
  separate, off-by-default tick).
* **Revocation** — if the server rejects the token (operator revoked the
  device) reporting stops and the app returns to onboarding.
* **Remote wipe** — an administrator can order a wipe on the Devices page. Every
  server reply carries a `wipe` flag; when it is true the app stops reporting,
  erases all of its own data (token, enrolment, position queue, the Situation
  page's task/target outbox and WebView storage, caches, downloaded APKs), returns
  to onboarding with "This device was reset by an administrator" and then sends
  `ack_wipe` (retried at the next app start if the network is down; the server
  revokes the token on receipt). The reporting service performs it too, so a phone
  in a pocket is wiped at its next position post (or, when no fixes are flowing, at
  the ~10-minute heartbeat). It does **not** wipe the phone or other apps, and it
  happens at the device's next contact, not instantly. The only thing kept is a
  small encrypted record of the token, solely to deliver the acknowledgement.
* **In-app updates** — the app asks the server for a newer APK on start, when the
  Situation tab opens, every ~3 h while open and ~10 min while the tracking
  service runs (offline checks are skipped). A dialog shows version, size and
  notes ("Update now" / "Later"; a *mandatory* update, below the server's minimum
  version code, has no "Later"). The APK downloads to the app cache with progress
  and its SHA-256 is checked against the server's before it is handed to the system
  installer; the package name, newer version code and signing key are also checked
  up front for a clear message. If "install unknown apps" is not allowed for the app,
  it explains and opens that settings screen, then continues when you return.
  Failures offer "Try again"; the position queue and reports are never touched.
* **Secure storage** — the device token lives in `EncryptedSharedPreferences`;
  the server stores only a hash. Release builds are HTTPS-only.

### How the Situation tab works

The map is the *same* page the web app uses (Leaflet + the shared military-symbol
renderer), built from the server repo and bundled in `app/src/main/assets/situation/`,
so symbols and graphics look identical on the phone. It runs in a `WebView`; native
code owns auth and networking and pushes data in through a tiny JS API
(`Situation.setData / setSelf / setStatus / upsertDevice / removeDevice / addWatch`).

After changing the web map (`frontend/src/situation/` in the server repo), refresh
the bundle:

```
tools/sync-situation.sh [path/to/etracker-server-repo]   # builds it and copies it into the app
```

The bundled assets are generated (git-ignored): run that script once on a fresh
checkout before building. Tiles come from OpenStreetMap, so the map needs a
connection (the WebView caches what it has seen).

Phones **poll** rather than use sockets: Frappe's socket server only accepts a
browser session or an API key, not a device token, and broadcasts site-wide. The
JS API already takes single updates, so a push channel can be added without
touching the map.

## Build

JDK 17 + Android SDK 35 (or Android Studio Ladybug or newer). First run
`tools/sync-situation.sh` (see above) to bundle the Situation map. The Gradle wrapper
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
  data/     Session (encrypted token), Api (HTTP), FixQueue (SQLite),
            SituationCache (last picture), TrackerState,
            DeviceReset (remote wipe / clean sign-out), Updater (in-app updates)
  service/  TrackingService (foreground GPS + delivery)
  ui/       TrackerApp (tabs, onboarding, status), SituationScreen (WebView), Theme
app/src/main/assets/situation/   bundled map page (generated)
tools/    simulate_device.py, sync-situation.sh, emulator.sh, env.sh
```

## Status

First version. It **compiles and builds a debug APK**. The server side of the
protocol has been exercised end to end with `simulate_device.py` (enrol, single
and batched posts, burned code rejected, positions stored and shown). **Verified on an Android 15 emulator** against a live server: enrolment, reporting
(foreground service, ±5 m fixes reaching the server and the live map), offline
queueing and flush on reconnect (2 queued -> 0, server count rose), and
revocation (app returns to onboarding, service stops). **Not yet tried on a
physical phone**, and QR scanning is untested (it needs a camera).

Situation tab verified on the emulator: map and plan render, own position shown,
offline banner with saved data. **Not yet tried on a physical phone.**

## Updates: signing and publishing a release

**An update only installs if the new APK is signed with the same key as the one
installed** (and has a higher `versionCode`). Android refuses it otherwise
("App not installed"); the app detects this before installing and says so.

* **Debug builds** are signed with the Android debug keystore, `~/.android/debug.keystore`,
  which Android creates **per machine**. A debug APK built on another computer (or after
  that file is deleted) has a different key and cannot update a phone that has yours.
  Fine for development; for real devices use one stable key.
* **Release builds** currently have **no `signingConfig`** in `app/build.gradle.kts`
  (the output is unsigned). Before distributing, create a keystore once, keep it (and its
  passwords) safe and backed up, add a `signingConfigs { create("release") { … } }` block
  reading it from `local.properties`/environment, and use it for every build you publish.
  Losing the key means every phone must uninstall (losing local data) and enrol again.

To publish a release:

1. Bump `versionCode` (must increase every release) and `versionName` in
   `app/build.gradle.kts`.
2. Build: `./gradlew assembleRelease` (or `assembleDebug` for a test fleet using the same
   debug key). The server needs the APK, e.g. `app/build/outputs/apk/<type>/app-<type>.apk`.
3. In the web app: **Devices → Phone app releases** (System Manager / Operations
   Director): upload the APK with its version code/name, notes and the *Mandatory* /
   *Minimum supported* flags (this makes a Draft; the server stores the APK privately and
   computes the SHA-256), then **Publish**. To retract a build, **Withdraw** it (a
   published release is never edited; publish a new, higher revision instead).
4. Phones see it at their next check; only enrolled, non-revoked devices can download it.

Not done yet: push updates (sockets), auto-start after reboot, background-location permission flow
(not needed while the service is started from the foreground), battery
optimisation prompt, symbol rendering on the phone (shows the unit name),
a release signing config, CI. The in-app update and remote-wipe
flows compile and the server side is tested; see the notes in the project history for
what has been exercised on an emulator.
