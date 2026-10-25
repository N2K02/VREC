# VRec recording architecture — current experimental PR #40

## Capture path

Home Recording → CallMonitor → ExperimentalCaptureDaemon →
independent Java shell recorder → authenticated loopback import →
RecordingStore → Recordings UI.

The app has a separate ordinary-UID local diagnostic command runner and an
embedded authenticated ADB client. The former cannot elevate privileges.
The ADB client only connects to the **fixed 127.0.0.1:5555** endpoint after
a trusted USB ADB host has enabled `adb tcpip 5555`; it verifies `id -u`
returns `2000` before launching the bundled Java app_process helper.
Wireless pairing, discovery, and mode selection are removed.

## Shell helper and lifecycle

The detached helper can survive disconnect of VRec's ADB client while the
Android shell process stays alive. It is not an OS daemon guaranteed across
reboot. Startup and foreground retries may reattach to a live helper, or
restore one only if a previously verified TCP endpoint and stored ADB identity
are still available. The Recording master switch controls whether capture
is armed, including after restarts. Quit stops app-owned work safely.

## No microphone fallback

This experimental workflow is ADB-only. If the shell helper is missing or
cannot capture the selected SIM/VoIP source, do **not** start the app microphone
recorder automatically or mark the recording as successful. Standard recorder
classes remain in the codebase for existing data compatibility, but the
method selector and normal fallback path are disabled in this branch.
Android/OEM audio-HAL restrictions can still make protected call sources
silent even when shell UID 2000 is verified.

## Permissions

Eligible runtime grants are attempted only through a verified shell and
checked afterwards with PackageManager. Telecom AppOp is checked separately.
Restricted Call Log access may be refused. Accessibility, overlay settings,
and notification-listener access require user control via Android Settings;
there is no guarantee of blanket permission approval.

## Metadata, recordings and diagnostics

Import is authenticated using the local helper protocol. Keep source identity,
SIM versus supported VoIP app, file size, timing estimates, silence diagnostics,
and independently verified audio details. Never infer a second party from
nonzero signal level. Confirm supported SIM audio sources and VoIP behavior
with real consented test calls.

## Regression priorities

- Home badge: grey idle, yellow busy, red failed, green **only** after UID 2000.
- USB debugging without TCP/5555 cannot self-authorize.
- A TCP listener without authenticated shell cannot start the helper.
- Automatic reconnection requires previous verification, allowed recovery,
  USB debugging and a real localhost listener.
- ADB missing: no new microphone recording.
- Home Off: independent helper stops/disarms capture safely.
- Android refuses a runtime grant: display attention required.
- Accessibility label remains **Vrec call recording helper**.
- Saved playback, imports, details, Settings/Quit and updates stay operational.
