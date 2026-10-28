# VRec — Android call recorder

VRec records supported SIM and VoIP calls locally using a separate, authorized
Android shell recording helper (UID 2000). In experimental [PR #40](https://github.com/N2K02/VREC/pull/40),
all **new** recordings are ADB-backed: no Standard/ADB mode selector or
automatic microphone fallback. Stable `main` remains unchanged until merge.

## First-time ADB setup

1. Open Android Developer options. Enable **USB debugging** and, on Android 11+
   devices with Wi-Fi, **Wireless debugging**.
2. In VRec Home, tap **Set up ADB recording** and open Android Wireless debugging.
   Select **Pair device with pairing code**.
3. Enter the one-time six-digit Android pairing code in VRec or its pairing
   notification. Android controls pairing approval; VRec cannot self-authorize.
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
- Background ADB recovery, Settings diagnostics, logs, updates and Quit.

## APKs

- [Stable releases](https://github.com/N2K02/VREC/releases/latest)
- [All releases](https://github.com/N2K02/VREC/releases)
- [Experimental PR #40](https://github.com/N2K02/VREC/pull/40)

**Validation:** CI does not prove hardware capture quality. Verify a consented
call and playback on the target device before relying on automatic recording.
