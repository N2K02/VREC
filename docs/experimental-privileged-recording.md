# VRec experimental call recording — unified PR #31

This document describes the actual experimental implementation and its limitations.
**PR #32 is the only open experimental PR, targeting `main`.** PRs #19, #23, #26, and #31 are closed as superseded. Stable is unchanged until review and testing.

## Recording methods

**Standard (default):** The Android app's own foreground microphone recorder
tries `VOICE_CALL`, `VOICE_RECOGNITION`, then `VOICE_COMMUNICATION`; the optional
mic/loudspeaker fallback depends on user settings. Auto mode selects the first
source that starts and produces measurable sound. A non-silent recording does
**not** prove that both participants were captured.

**Built-in ADB (opt-in):** VRec pairs with the device's own Android Wireless
debugging using a bundled ADB client, or connects to an already enabled TCP/5555
endpoint. After verifying shell UID 2000 it can install and start the bundled
Apache-2.0 scrcpy/JemRec-derived Java `app_process` recording daemon. The
daemon captures into a shell-side file, uses a local nonce/HMAC-authenticated
loopback channel, and imports completed Opus/Ogg audio into VRec's library.

**Shizuku is not installed or required.** Its UI and dependency/provider/binder
integration were removed. A previously saved Shizuku choice migrates to Standard.

### Persistence and `AdbPrivilegedApiHelper`

- A successfully started shell Java process may continue running after the ADB
  session or Wi-Fi disconnects; it is **not** a permanent system service.
- `AdbPrivilegedApiHelper.kt` is the initial lifecycle facade: authenticated
  `reconnect()`, privileged `startWithAdb()`, and `checkHealth()` states.
  Wiring this helper into all startup/foreground/background lifecycles and
  automated recovery is *not yet complete*.
- Neither the APK nor its helper can start a new shell-UID process without
  already-authorized ADB access. If Android kills the daemon or the phone
  reboots, a fresh privileged start may be necessary.
- Shell UID is not root. Android/OEM audio HAL restrictions still apply.
  InCallService and the Telecom AppOp help detect calls; they do **not**
  automatically unlock protected two-way audio.

## Built-in ADB setup

1. Open Settings → Experimental → Built-in ADB.
2. For first-time phone-local authorization, use Android Developer options →
   Wireless debugging → Pair device with pairing code, preferably in split screen.
   Enter its temporary pairing code/port in VRec and pair.
3. Use **Connect with Wireless debugging** and verify shell UID 2000.
   Alternatively, when TCP/5555 has already been authorized and enabled
   (e.g. `adb tcpip 5555` from a computer over USB), choose **Connect TCP/5555**.
4. Start the enhanced recorder and verify its daemon readiness separately from
   ADB connection state. Complete a consented test call, import the resulting
   audio, and listen to both participants before relying on this mode.

Switching an existing connection into TCP/5555 restarts `adbd` and naturally
drops the previous ADB session. VRec retries the new localhost connection,
but Android may disable legacy TCP mode on reboot, debugging changes, or OEM
policy. ADB over TCP might listen on LAN interfaces and expose device access
to other authorized ADB clients. **Never enable it automatically.** Turn it
off when not required.

## Call detection, recordings and metadata

- Unified `CallMonitor` handles SIM phone state, optional non-UI Telecom
  InCallService, WhatsApp/other VoIP notifications, and manual recording.
  One active standard recording is allowed; device-dependent sources may fail.
- The Home recording master switch is opt-in and stops/prevents capture when
  disabled. Advanced call-detection permission status is shown in one card
  (Granted / Not granted).
- Standard capture is stored as M4A; imported enhanced recordings use Ogg,
  both with backwards-compatible JSON sidecars. Current metadata can be
  incomplete or estimated for some sources and old recordings; audio duration
  and call duration are different quantities.
- The recording library has a single in-card player with seek, pause/resume
  and stop, rounded recording action/detail sheets, share, sorting, bookmarks,
  notes and metadata editing.

## Verification status and acceptance criteria

On the user's Pixel, a **26-minute Standard Voice recognition recording**
contained both participants, with the remote person faint. The same logs
showed `VOICE_CALL` start exceptions and a silent Voice recognition probe
on another short call. This is **not** proof that enhanced shell capture works
for every SIM or third-party VoIP app.

Acceptance testing should check: no duplicate recordings; correct caller and
direction metadata; silent-source fallback; the Home Off guard; in-card player,
sort/action sheets and existing recordings; privileged ADB pairing, launch,
loopback reconnect, Wi-Fi loss and TCP security; import and two-sided listening;
and overlay/master toggle integration. Passing GitHub CI establishes build
compatibility, not a device-specific protected audio entitlement.

Future improvements requested but **not yet implemented** include a stronger
source-quality selector, validated per-recording audio-source diagnostics,
metadata-consistency fixes, and fully integrated helper lifecycle recovery.

## Attribution

Embedded ADB and audio capture follow the Apache-2.0 compatible JemRec/scrcpy
architecture. `shellserver/LICENSE-scrcpy` and `shellserver/PATCHES.md`
record the required licensing and adaptation details. ShizuCallRecorder and
other GPL-only projects served as design references rather than copied code.

**Do not merge this experimental PR into stable until current CI and
real-device tests have passed.**
