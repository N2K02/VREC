# VRec — Android call recorder

VRec records supported SIM and VoIP calls locally using a separate, authorized
Android shell recording helper (UID 2000). All new recordings are ADB-backed:
there is no Standard/ADB mode selector or automatic microphone fallback.

## First-time ADB setup

1. Open Android Developer options. Enable **USB debugging** and, on Android 11+
   devices with Wi-Fi, **Wireless debugging**.
2. In VRec Home, tap **Set up ADB recording** and open Android Wireless debugging.
   Select **Pair device with pairing code**.
3. With the user-enabled **VRec Accessibility** service, automatic pairing-code
   detection is on by default: keep Android's pairing dialog open and VRec
   reads the displayed code. Without Accessibility, enter the six-digit code
   manually in VRec or its pairing notification. Android controls pairing
   approval; VRec cannot self-authorize.
4. VRec connects to this phone's TLS ADB listener, verifies `id -u == 2000`,
   attempts supported permissions, and starts the independent recording helper.
5. **Automatic TCP/5555 is on by default.** After UID 2000 verification, VRec
   switches adbd to TCP/5555, waits for it to restart, reconnects to
   `127.0.0.1:5555` and re-verifies UID 2000 and the recording helper.
   Previously authorized connections are restored automatically when available.

On devices without Wireless debugging, a trusted external USB ADB host must
run `adb devices` then `adb tcpip 5555` after the phone approves the host.
USB debugging alone cannot authorize an ordinary Android app shell.

**Network notice:** Android's TCP/5555 listener may be accessible to other
devices on the network. Disable automatic TCP mode in Settings if you do not
want that exposure; the app attempts to close an active listener when authorized.
Reboot or OEM policies may reset debugging, requiring further user action.

## Recording reliability

Home distinguishes helper readiness, current ADB authorization, recording armed,
actual capture and attention-required conditions. A healthy detached helper
can continue recording without a live ADB client. Android shell UID 2000 alone
is not proof of two-sided audio capture or a playable recording.

New captures use only the shell helper. If capture fails, VRec does not
silently create microphone audio; it shows a warning and, where allowed, a
notification. Imported recordings retain the shell original until the audio and
**atomically saved JSON metadata** are complete.

## Android permissions

VRec attempts and verifies eligible permission grants only after UID 2000 is
verified. Android can refuse restricted grants. Accessibility
(**Vrec call recording helper**), overlay and notification-listener access
remain user-controlled.

## Features

- SIM and supported VoIP capture with caller/number/SIM metadata.
- Audio channel/silence diagnostics, pause/resume/stop controls.
- Saved recordings with playback, search, details, notes, favorites and sharing.
- On-device recording and import failure warnings, metadata integrity checks.
- **Settings → ADB connection:** toggles for automatic pairing-code detection,
  Connection watchdog (on by default), Start on boot (on by default),
  Restore after restart (opt-in), and TCP/5555 mode.
- The watchdog checks connection health in-process and has a best-effort
  15-minute Android WorkManager recovery backstop. Turning it off stops
  scheduled checks without deliberately ending an active recording.
- Start on boot honors Android unlock and existing authorization; switching it
  off pauses boot/background restoration until you open VRec again.
  Restore after restart is a separate option for re-enabling Wireless debugging
  when Android permits it.
- **Home → ADB connection ports:** shows the live UID-2000-verified transport
  (TLS or TCP/5555) and active local port, a discovered TLS port, the last
  verified TLS port cached for this boot, and a temporary pairing port when
  present. A listening socket or cached port is not proof of an authorized
  ADB session.
- Same-boot TLS port reuse and automatic rediscovery when a cached port
  changes, subject to Android authorization.
- Settings diagnostics, logs, updates, and a Quit action that stops VRec-owned
  background recovery until the app is opened again.

## Reference and inspiration

- [**Shizuku-Next**](https://github.com/rushiranpise/Shizuku-Next) — reference
  for wireless ADB pairing ergonomics, automatic pairing-code detection,
  reconnect/recovery strategies, watchdogs, and boot-start controls.

VRec implements its **own** authorized ADB client and recording helper.
Shizuku-Next is credited as an engineering reference; VRec does **not**
require the Shizuku-Next app or server, and does not bundle its runtime.

## APKs

- [Stable releases](https://github.com/N2K02/VREC/releases/latest)
- [All releases](https://github.com/N2K02/VREC/releases)
- [ADB reliability implementation (merged PR #44)](https://github.com/N2K02/VREC/pull/44)

**Validation:** CI does not prove hardware capture quality. Verify a consented
call and playback on the target device before relying on automatic recording.
