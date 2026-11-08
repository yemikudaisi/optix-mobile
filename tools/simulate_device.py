#!/usr/bin/env python3
"""Stand-in for the Android app: enrol with a code, then walk a path posting
positions, using exactly the requests the app makes (JSON POST, ISO-8601 'Z'
timestamps, post_positions batches). Handy for testing the server and the live
map without a phone. Standard library only.

  # enrol and walk (code from the web app's Devices page)
  ./simulate_device.py --site http://localhost:8080 --code ABCD-1234

  # reuse a token from an earlier run
  ./simulate_device.py --site http://localhost:8080 --token XXXX
"""
import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone


def call(site, method, body):
    req = urllib.request.Request(
        f"{site.rstrip('/')}/api/method/{method}",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.load(r)["message"]
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            msgs = json.loads(json.loads(raw).get("_server_messages", "[]"))
            msg = "; ".join(json.loads(m).get("message", "") for m in msgs) or raw[:200]
        except Exception:
            msg = raw[:200]
        return e.code, msg


def iso_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--site", required=True)
    ap.add_argument("--code")
    ap.add_argument("--token")
    ap.add_argument("--lat", type=float, default=9.70)
    ap.add_argument("--lon", type=float, default=10.29)
    ap.add_argument("--bearing", type=float, default=90, help="degrees, walk direction")
    ap.add_argument("--speed", type=float, default=8, help="m/s")
    ap.add_argument("--steps", type=int, default=10)
    ap.add_argument("--interval", type=float, default=0, help="seconds between fixes (0 = use the server's)")
    ap.add_argument("--offline-burst", type=int, default=0, help="queue N old fixes and send them as one batch first")
    a = ap.parse_args()

    token, interval = a.token, a.interval
    if not token:
        if not a.code:
            sys.exit("need --code or --token")
        status, m = call(a.site, "etracker.devices.enrol_device", {"code": a.code})
        if status != 200:
            sys.exit(f"enrol failed ({status}): {m}")
        token = m["token"]
        interval = interval or m["interval_s"]
        print(f"enrolled {m['device']} as {m['unit']} — token {token}\n(reuse with --token)")
    interval = interval or 30

    lat, lon = a.lat, a.lon
    def advance(dt):
        d = a.speed * dt
        b = math.radians(a.bearing)
        return (lat + (d * math.cos(b)) / 110574.0,
                lon + (d * math.sin(b)) / (111320.0 * math.cos(math.radians(lat))))

    if a.offline_burst:
        now = time.time()
        queued = []
        for i in range(a.offline_burst, 0, -1):
            lat, lon = advance(interval)
            ts = datetime.fromtimestamp(now - i * interval, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            queued.append({"lat": lat, "lon": lon, "ts": ts, "accuracy": 8, "speed": a.speed, "heading": a.bearing})
        status, m = call(a.site, "etracker.devices.post_positions", {"token": token, "positions": queued})
        print(f"offline burst of {len(queued)} -> {status} {m}")

    for i in range(a.steps):
        lat, lon = advance(interval)
        status, m = call(a.site, "etracker.devices.post_positions", {
            "token": token,
            "positions": [{"lat": lat, "lon": lon, "ts": iso_now(), "accuracy": 8, "speed": a.speed, "heading": a.bearing}],
        })
        print(f"[{i + 1}/{a.steps}] {lat:.5f},{lon:.5f} -> {status} {m}")
        if status in (401, 403):
            sys.exit("token rejected (device revoked?) — re-enrol")
        if i < a.steps - 1:
            time.sleep(interval)


if __name__ == "__main__":
    main()
