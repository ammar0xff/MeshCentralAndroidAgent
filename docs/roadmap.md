# MeshCentral Android Agent - Master Roadmap

Scope: agent (Kotlin) + `mdmpanel` server plugin (JS) + ops/CI.
Effort units: S <=0.5d, M 1-2d, L 3-5d (agent changes build only via CI, +1 CI cycle each).
Standing gates: CI green; `test-protocol.js` + `shots.js` + design gate >=85 for any panel change; adb E2E whenever a device is available.

## P0 - Integrity and the always-on promise

Protects existing guarantees; nothing new is visible to users.

| # | Item | Why | Effort |
|---|------|-----|--------|
| 0.1 | Watchdog re-arm gap: reset `running` in `MDMForegroundService.onDestroy`, make `MDMWatchdogReceiver` re-arm itself on every tick, make `ensureRunning()` self-healing | Shipped (1153934): `running` cleared on service destroy, watchdog receiver re-arms on every tick, `ensureRunning()` self-heals; the resurrection chain survives system-initiated destroys | M |
| 0.2 | Fix `HiddenLaunchReceiver`: accept `mdmagent://setup` in addition to secret code `3664` | Shipped (d5335ad): receiver accepts the registered `mdmagent://setup` link; hidden icon has a working re-entry path again | S |
| 0.3 | `showNotification` lateinit crash on API 23-25 and headless FCM paths; console `alert` `splitCmd[2]` out-of-bounds | Shipped (c61f68e): notification path made null-safe for headless FCM/API 23-25, `alert` no longer indexes past `splitCmd` | S |
| 0.4 | Ship pending fixes: `MeshAgent.kt` verbatim string-seq echo + `strings.xml` em-dash | Shipped (0b1f983): mdm seqs echoed verbatim (string seqs collapsed to 0 and broke correlation), em-dash removed from autostart string | S |
| 0.5 | Unify signing secret names (`ANDROID_SIGNING_KEY_B64` vs `ANDROID_KEYSTORE_BASE64`) | Shipped (da367a6): release workflow reads the repo-stable `ANDROID_KEYSTORE` secrets path; tag releases no longer fail on an unset secret name | S |
| 0.6 | Credential rotation (SSH password, MeshCentral admin, node credentials) | Shipped (2026-09-30): MeshCentral admin password rotated via `meshcentral --resetaccount` + container restart, verified new-credential → 200 and old-credential → 401 on `/mdmpanel`; SSH password deliberately left unchanged per user decision (2026-09-30); node credentials not rotated (agent identity is per-install) | S |
| 0.7 | adb E2E always-on gate: install final APK, re-grant permissions, screen-locked stability, live panel commands, 10-minute idle | The only remaining proof of the core promise; unlocks Tier 1 verification. Skipped on request (2026-09-30); credential rotation (0.6) shipped same day | M |

## P1 - Differentiation (agent features core MeshCentral cannot do)

| # | Item | Notes | Effort |
|---|------|-------|--------|
| 1.1 | Remote control via accessibility: map desktop-view input commands (currently no-ops) to `tap`/`swipe`/`inputText` on device coordinates; add scroll, long-press, drag; key-event filtering | Shipped (v0.4.0): MeshTunnel handles desktop cmd1/cmd2/cmd85 input, scaling image pixels to device coordinates with a tap-vs-swipe threshold; key and unicode events route through MDMAccessibilityService (nav, edit, F-keys, insert at caret) | L |
| 1.2 | Panel exposure of existing console commands: `toast vibrate flash dial alert openurl openbrowser sysinfo netinfo storageinfo serverlog uistate` | Shipped (v0.3.0): agent shares its console dispatcher through a new mdm `console` bridge; the panel gained a typed Actions group plus four console-backed reads | M |
| 1.3 | Wire `ACTION_REMOTE_COMMAND` (lock / wipe / notification-override) to an authenticated path; decide the fate of dead `removeLockdown`, `isDeviceOwner`, `PAIR_TOKEN` | Shipped (v0.4.0): authenticated mdm `remote` command with lock/wipe/notify subcommands replaces the unreachable broadcast handler; `removeLockdown` and `PAIR_TOKEN` dead code deleted; `isDeviceOwner` kept and wired as the wipe guard; wipe clears `DISALLOW_FACTORY_RESET` first | M |
| 1.4 | Screenshot command: one MediaProjection frame as base64 to the panel image renderer | Shipped (v0.4.0): mdm `screenshot` latches one full-resolution frame as base64 JPEG (2s timeout, works without an active desktop tunnel); the panel renders the image payload instead of JSON | M |
| 1.5 | Console spec sweep: `help` omits `kvmstart`/`kvmstop`; align advertised capabilities 12 vs 13; drop WebRTC remnants and `coredump` no-op advertising | Shipped: help lists `kvmstart`/`kvmstop`; hello capabilities aligned to 13 (the Desktop bit was missing while coreinfo already said 13); `coredump`/`getcoredump` no-ops and WebRTC remnants dropped | S |

## P2 - Panel and fleet expansion

| # | Item | Notes | Effort |
|---|------|-------|--------|
| 2.1 | File manager tab (reuse `ls`/`rm`/`upload`/`download` protocol; roots Sdcard, Images, Audio, Videos) | Shipped (v0.6.0): agent `MDMFiles` object answers mdm `ls`/`rm`/`download`/`upload` over the virtual roots (Sdcard via safe path resolution, MediaStore collections for Images/Audio/Videos, 2 MB per-message cap, device-side event log msgid 45/106/105); panel Files rail entry swaps the argument form for a rooted browser (path bar, dir-first table, download/delete/upload) driven by hidden transport commands; the fan-out view hides the entry since a browser targets one device; harness-tested (protocol section 10 plus 18 screenshot checks including download/delete/upload round-trips) and live-verified on device 2026-09-30 after the v0.6.0 APK update (roots listing, 32-entry Sdcard listing, download round-trip; `rm`/`upload` covered by harness only) | M |
| 2.2 | Live notification feed: push listener snapshots instead of 5-second polling | Requires notification-access permission plus tunnel or a new ws event | L — Shipped (v0.9.0). Listener record() pushes posted/removed deltas over the main ws (unsolicited `mdmnotify`, same offline queue as timeline events); plugin buffers a 300-event ring per device served at `/mdmpanel/api/notiffeed`; panel rail entry "Notification feed" (Timeline group) swaps the form for hairline rows. Notifications read command remains pull-based. Harness verified (21 shots, gate 100/100); live-verified 2026-09-30 (v0.9.0 APK): notificationListener granted, posted/removed deltas streaming into /api/notiffeed within a minute of install (fresh node id Ca8qQuyf…, timeline + report unaffected). P0–P2 now fully shipped. |
| 2.3 | Device picker outside the console context plus mesh-level fan-out (one command to all nodes) | Shipped (v0.5.0): `GET /mdmpanel/api/devices` lists server-known nodes (name from the db record, reachability from `wsagents` + `GetConnectivityState`, `mdm` capability from the node's `agent.core` identity) behind a header picker; `POST /mdmpanel/api/fanout` sends one command to every online Android device with per-target derived seqs, skipped nodes reported by reason (`offline` / `not an Android agent`); fleet status (`nodeid=*`) aggregates Android devices; live-verified 2026-09-30 (1 target, 5 desktop nodes skipped, battery result correlated) | M |
| 2.4 | Scheduled reports (battery/storage/location snapshots) and offline alerts surfaced as `log`/`msgid` events | Shipped (v0.7.0): the agent pushes an unsolicited `heartbeat` mdmResult over the main websocket every 30 s (battery level plus charging, storage available/total, location snapshot; replaces the old tunnel-only `mdm_heartbeat` ping that never reached the plugin) and emits an hourly report node event (msgid 60) through `logServerEventEx`; the plugin records the newest heartbeat as `lastReport`, exposes it as `report` on `GET /mdmpanel/api/status`, and a 15 s server-side watcher fires exactly one node event per Android device transition (msgid 61 offline, 62 back online, dispatched with the core's agent `log` event shape); the panel renders a muted report strip under the header (battery, storage, location, age; hidden in fleet view); harness-tested (protocol section 11 plus 19 screenshot checks); live-verified on device 2026-09-30 after the v0.8.0 APK install (battery/storage/location/age report strip fed by 30 s heartbeats; reinstall assigned a fresh node id, old id no longer listed) | M |
| 2.5 | Keyguard / accessibility event streaming into a panel timeline | Shipped (v0.8.0): MDMAccessibilityService streams `TYPE_WINDOW_STATE_CHANGED` app switches (pkg-deduped, SystemUI filtered) and MDMForegroundService broadcasts screen-on / screen-off / unlock through a 50-event queue into unsolicited `mdmevent` mdmResults over the main websocket; the plugin buffers events per device in a 200-entry ring (server receive time stamped, seq correlation untouched) and serves them via `GET /mdmpanel/api/timeline` (newest last); the panel gains a Timeline rail entry (hidden in fan-out, like Files) that swaps the argument form for a hairline timestamp-gutter list rendered from device time; harness-tested (protocol section 12 plus 20 screenshot checks) and gate 100/100; live-verified on device 2026-09-30 after the v0.8.0 APK install (app-switch events streaming into the ring, 22 events within minutes; screen/lock events follow device use) | M |

## P3 - Platform and enterprise (needs device owner or zero-touch)

| # | Item | Gate |
|---|------|------|
| 3.1 | Android Enterprise QR / zero-touch device-owner enrollment | Enrollment channel |
| 3.2 | Silent app install/uninstall and allow/block lists (`REQUEST_INSTALL_PACKAGES` already held) | Device owner |
| 3.3 | Policy engine via `DevicePolicyManager` (policies XML already declares camera, screen capture, keyguard) | Device owner |
| 3.4 | Kiosk / lock-task mode; real `power` (reboot, shutdown) | Device owner or OEM |

## Explicitly out of reach

- Terminal and serial tunnels: Android has no on-device shell without root.
- WebRTC media path: abandoned in-tree; the JPEG tile pipeline is the design.
- Bypassing MediaProjection / autostart consent: enforced by the OS and OEMs.
- OEM autostart re-entry beyond the HONOR/Huawei `mdm://ack` flow.

## Cross-cutting rules

- Differentiation line: core MeshCentral already does desktop view, files, and a
  text console. The panel owns structured MDM data, UI automation, and
  fleet/policy. Do not duplicate core surfaces.
- Dead-code policy: every P1.3 / P1.5 item ends in either wiring or deliberate
  deletion, recorded in `docs/`.
- Verification per item: P0 -> protocol tests + adb; P1.1 and P1.4 -> adb E2E
  (device gate 0.7 skipped 2026-09-30 by user decision; verified via compile +
  protocol tests + harness screenshots instead); P2 -> harness + screenshots +
  design gate; P3 -> lab device only.
- Sequencing: P0 before any feature (the always-on chain is load-bearing);
  P1 before P2 (agent capabilities gate panel work); P3 optional pending an
  enrollment channel.
