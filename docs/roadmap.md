# MeshCentral Android Agent - Master Roadmap

Scope: agent (Kotlin) + `mdmpanel` server plugin (JS) + ops/CI.
Effort units: S <=0.5d, M 1-2d, L 3-5d (agent changes build only via CI, +1 CI cycle each).
Standing gates: CI green; `test-protocol.js` + `shots.js` + design gate >=85 for any panel change; adb E2E whenever a device is available.

## P0 - Integrity and the always-on promise

Protects existing guarantees; nothing new is visible to users.

| # | Item | Why | Effort |
|---|------|-----|--------|
| 0.1 | Watchdog re-arm gap: reset `running` in `MDMForegroundService.onDestroy`, make `MDMWatchdogReceiver` re-arm itself on every tick, make `ensureRunning()` self-healing | A system-initiated destroy leaves `running=true`, so the next watchdog tick no-ops and no new alarm is armed; the resurrection chain dies until reboot | M |
| 0.2 | Fix `HiddenLaunchReceiver`: accept `mdmagent://setup` in addition to secret code `3664` | Manifest registers only `mdmagent://setup`; no input matched the receiver, so a hidden app icon left no re-entry path | S |
| 0.3 | `showNotification` lateinit crash on API 23-25 and headless FCM paths; console `alert` `splitCmd[2]` out-of-bounds | Crash / failed-command paths on supported devices | S |
| 0.4 | Ship pending fixes: `MeshAgent.kt` verbatim string-seq echo + `strings.xml` em-dash | Protocol correctness (seq correlation for string sequence ids) | S |
| 0.5 | Unify signing secret names (`ANDROID_SIGNING_KEY_B64` vs `ANDROID_KEYSTORE_BASE64`) | Tag-release workflow reads a secret name CI never sets; tag releases fail | S |
| 0.6 | Credential rotation (SSH password, MeshCentral admin, node credentials) | Standing security item, after E2E | S |
| 0.7 | adb E2E always-on gate: install final APK, re-grant permissions, screen-locked stability, live panel commands, 10-minute idle | The only remaining proof of the core promise; unlocks Tier 1 verification. Skipped on request (2026-09-30); rotate credentials (0.6) still pending a device session | M |

## P1 - Differentiation (agent features core MeshCentral cannot do)

| # | Item | Notes | Effort |
|---|------|-------|--------|
| 1.1 | Remote control via accessibility: map desktop-view input commands (currently no-ops) to `tap`/`swipe`/`inputText` on device coordinates; add scroll, long-press, drag; key-event filtering | Turns the view-only KVM into true remote control; needs coordinate scaling and consent UX; largest single value item | L |
| 1.2 | Panel exposure of existing console commands: `toast vibrate flash dial alert openurl openbrowser sysinfo netinfo storageinfo serverlog uistate` | Shipped (v0.3.0): agent shares its console dispatcher through a new mdm `console` bridge; the panel gained a typed Actions group plus four console-backed reads | M |
| 1.3 | Wire `ACTION_REMOTE_COMMAND` (lock / wipe / notification-override) to an authenticated path; decide the fate of dead `removeLockdown`, `isDeviceOwner`, `PAIR_TOKEN` | Implemented but unreachable; needs an auth design (FCM auth or signed command) | M |
| 1.4 | Screenshot command: one MediaProjection frame as base64 to the panel image renderer | Reuses the capture pipeline; consent prompt required | M |
| 1.5 | Console spec sweep: `help` omits `kvmstart`/`kvmstop`; align advertised capabilities 12 vs 13; drop WebRTC remnants and `coredump` no-op advertising | Spec hygiene visible to the server UI | S |

## P2 - Panel and fleet expansion

| # | Item | Notes | Effort |
|---|------|-------|--------|
| 2.1 | File manager tab (reuse `ls`/`rm`/`upload`/`download` protocol; roots Sdcard, Images, Audio, Videos) | Do not rebuild core file views beyond device-native roots | M |
| 2.2 | Live notification feed: push listener snapshots instead of 5-second polling | Requires notification-access permission plus tunnel or a new ws event | L |
| 2.3 | Device picker outside the console context plus mesh-level fan-out (one command to all nodes) | Server-side batching via `GetConnectivityState` / `wsagents` | M |
| 2.4 | Scheduled reports (battery/storage/location snapshots) and offline alerts surfaced as `log`/`msgid` events | Builds on `mdm_heartbeat` | M |
| 2.5 | Keyguard / accessibility event streaming into a panel timeline | Replaces polling; enables automation rules later | M |

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
  mandatory; P2 -> harness + screenshots + design gate; P3 -> lab device only.
- Sequencing: P0 before any feature (the always-on chain is load-bearing);
  P1 before P2 (agent capabilities gate panel work); P3 optional pending an
  enrollment channel.
