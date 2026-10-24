# VRec experimental enhanced recording — technical design (proposal)

This is a staged engineering plan, **not implemented recording support**. All development
belongs in PR #23 (`experimental/incall-unified-detection`) so there is only one
experimental review branch. Stable `main` stays unchanged until device validation.

## Goals and non-goals

* Preserve VRec's default unprivileged capture path as the initial, always-available choice.
* Add opt-in **Built-in ADB recording** (JemRec-style) and **Shizuku recording**.
* Reuse existing VRec call detection (Telephony, optional InCallService, VoIP notification),
  caller-metadata enrichment, MRadio-inspired Material 3 UI, updater and recording library.
* Keep call detection, privilege transport and audio capture distinct. InCallService
  does **not** grant protected call audio.
* Discover support on-device; do not assume all Android 11+ devices expose Wireless debugging
  or that privileged audio sources work across OEMs. Old Huawei Android 12 variants
  must retain Standard recording when ADB is absent.
* Do not download code or execute arbitrary scripts from the network, install the Shizuku
  manager silently, or hide mandatory OS prompts. No root required.
* Never record simultaneously through Standard and Enhanced backends.

## Settings screen architecture

`Settings → Experimental`:

1. **Recording method** (exactly one selected)
   - **Standard (recommended)** — VRec's existing recorder, no ADB or Shizuku.
   - **Built-in ADB (JemRec-style)** — phone pairs with itself via a bundled ADB client;
     a separately authenticated shell-side capture daemon is used when supported.
   - **Shizuku** — uses an already installed and authorized Shizuku service;
     provides shell-privileged capture through a separately tested backend.
   - When unsupported, show the reason instead of a dead switch. Default remains Standard.
2. **Connection and audio readiness**, only shown for a privileged method:
   - Platform capability: supported / unavailable / not checked.
   - ADB or Shizuku: not paired / paired / connected and shell UID verified.
   - Capture daemon: stopped / running / needs restart.
   - Audio: **not tested** / test passed / silent or unsupported.
   - Buttons: `Set up`, `Check connection`, `Test audio`, `Troubleshoot`.
   - A connected ADB socket is *not* proof of two-sided call capture. Test audio may
     confirm frames exist; a real consented call and listening test are needed
     to verify both participants.
3. **Call detection** remains independently controlled:
   - Standard call-state fallback works without elevated AppOp.
   - Optional InCallService activation, single elevated `manage_ongoing_calls`
     AppOp action and status remain here, *not* in the permissions Setup page.
4. **Advanced connection options**, collapsed by default:
   - No-notification pairing instructions / optional floating setup overlay.
   - Reconnect/recover capture daemon.
   - `Record away from Wi-Fi` experimental switch (opt-in, device capability gated).
   - `Keep ADB TCP mode` separately consented, security-warning gated; off by default.
   - Stop enhanced capture and close/debugging transport when possible.

Do not conflate the updater's Experimental channel with the recording method.

## Pairing without notifications

* On Android 11+, launch Developer options via
  `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`, with OEM fallback guidance.
* Detect active `_adb-tls-pairing._tcp` (only while Android's pairing dialog is
  open) and `_adb-tls-connect._tcp` via `NsdManager`; verify the endpoint
  by an authenticated loopback/TLS connection. Use fixed-port TCP as a distinct
  fallback. Never treat a global ADB setting or SDK version as definitive proof.
* Let users enter pairing code and port through a VRec-owned UI **without a
  notification** using Android split-screen (Settings dialog + VRec).
  Optional explicit user-granted floating code-entry overlay may be tried, with
  clear notice that Android/OEM restrictions can block it.
* Codes expire and remain in memory only; generate and store private ADB identity
  with Android Keystore-backed protection. Verify real `id -u == 2000`;
  unprivileged terminals can raise `INTERACT_ACROSS_USERS` errors.
* Don't scrape pairing codes from Accessibility, dismiss Android's pairing
  prompt, or offer a false 'paired' status because a TCP port happened to open.

## Transport/capture separation

```text
Unified CallMonitor (SIM / Telecom / VoIP / Manual)
                 |
          SessionCoordinator
                 |
    CaptureBackend (only one active)
       |             |               |
    Standard     Built-in ADB      Shizuku
    VRec mic     shell daemon      authorized service
       \             |               /
        RecordingStore + metadata sidecar + diagnostics
```

* A per-call session lock prevents duplicate recordings, races when switching
  backend, and misleading 'recording' indicators if shell capture failed.
* Adapter contract: `capabilityProbe()`, `prepare()`, `start(session)`,
  `stop()`, `health()`, `release()`.
* Preserve the legacy M4A/sidecar format for existing files. If an enhanced
  backend emits Opus/Ogg, add explicit format handling for playback/share/indexing,
  not a fake M4A filename.
* Use only an authenticated local IPC channel to the shell daemon; store tokens in
  app-private state, rotate as needed, make binding loopback-only; clean up orphaned
  daemons and temp resources.
* Device-dependent shell capture can still fail even with UID 2000.
  Explicit unsupported/silent status and user-selectable fallback to Standard
  are mandatory; never silently claim stereo/two-sided success.
* Avoid changing capture backends in the middle of a live recording.

## Offline and TCP truthfulness

* **No internet** is not **no Wi-Fi**. Initial Android wireless debugging pairing
  generally requires Wi-Fi; a local loopback connection itself does not use
  remote servers or mobile data.
* JemRec demonstrates a preferred architecture: use ADB briefly to bootstrap
  a local shell daemon, then close ADB while the daemon continues handling calls.
  Network loss need not terminate an already running daemon, but OS/OEM death,
  adbd restart, app update or reboot may require restart.
* Some devices support `adb tcpip 5555` after an authorized ADB session. The
  connection can work without Wi-Fi on loopback while adbd remains alive, but
  this is not guaranteed; switching mode restarts adbd and may kill processes.
* ADB/TCP can expose debugging to a network interface, not merely `127.0.0.1`.
  DO NOT auto-enable it in the default pathway. Offer separately consented
  time-limited advanced use with explicit risk, on-device verification,
  disable/revert instructions, and a safety explanation. Prefer a daemon over
  persistent externally reachable ADB.
* Never promise 'survives reboot offline'. Expose `Needs Wi-Fi to restart`
  or `Recorder stopped` state when accurate.

## Platform permissions and compatibility

* Android >= 11 (API 30) is a **candidate** for Wireless debugging; capability
  check must probe system Settings and mDNS plus actual ADB connection.
* Handle Android 17 (SDK 37) local-network permission for apps targeting 37+
  (and any Android 16 opt-in network restrictions). Respect runtime denial.
* Device has no Wireless debugging? Show "Built-in ADB not available on this
  device" and retain Standard; an already running authorized Shizuku service
  may be probed independently. Avoid OEM bans by model name only.
* Do not assume bypassing ADB UI checks in the Zoefiro Shizuku fork can
  activate a disabled adbd or defeat device-management policies.
* Shizuku integration should use official permission/binder APIs with a
  separately installed Shizuku service. Do not embed/clone the full Shizuku
  manager in VRec or claim VRec can grant itself shell powers.

## Provenance and licensing

* Reference: `jemcik/JemRec` (Apache-2.0), `Zoefiro/Shizuku-Stable-Debugg-Fix`
  (Apache-2.0), upstream Shizuku. Reuse only compatible modules after reviewing
  transitive licenses and copying required NOTICE/copyright markers.
* Reference architecture only, no code copied: `kitsumed/ShizuCallRecorder`,
  `madkongo/CallVault`, `DP-Hridayan/aShellYou` (GPL-3.0 projects).
* Local reusable source for embedded ADB is JemRec's `libadb-android` route,
  licensed under its offered Apache-2.0 alternative, plus SPAKE2/TLS and
  maintained notice files. Adapt, do not copy app-specific UI code or branding.

## Rollout and acceptance tests

1. **Capability audit and transport**: device detects lack of Wireless debugging;
   no permission prompts on Standard; mDNS+manual port pairing with split-screen;
   secure identity; `id -u == 2000`; no-notification pairing.
2. **ADB audio proof of concept**: start/stop authenticated shell daemon, run
   short voice-source test, test with consented SIM call and wired/Bluetooth
   headsets on real devices; detect silence and unsupported sources.
3. **Session integration**: replace only the recording backend, keep unified
   CallMonitor detection and existing metadata, add format import and health.
4. **Shizuku alternate route**: detect installed+running+authorized service,
   test with the same capture backend contract, handle binder death.
5. **Offline resilience**: experimentally test Wi-Fi loss, mobile-only,
   airplane mode, adbd restart, reboot and recovery; only then expose
   optional TCP mode with the explicit security warning.
6. **QA/CI**: Android 12 Huawei without Wireless debugging (must use Standard);
   Android 12/14/17 with debugging; OEM ADB restrictions; permission denial;
   no double capture, no data loss, reboot recovery, mode switching while
   idle/active, audio playback/share and safe updater installation.

### No go / rollout gates

Don't enable privileged capture as the default, automatically activate TCP
debugging, claim two-sided capture before a real call test, or merge this
proposal into stable until there is a working, reviewable implementation
with explicit per-device failure states.
