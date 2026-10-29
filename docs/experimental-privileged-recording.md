# VRec enhanced SIM/VoIP recording — experimental PR #40

This document reflects the ADB-only changes in the experimental branch.
Stable `main` stays untouched until review.

## First-time wireless pairing and USB fallback

On Android 11+, the user enables USB debugging and Wireless debugging,
chooses Android's pairing-code dialog and enters the six-digit code in VRec.
VRec discovers only this phone's mDNS TLS endpoints, verifies UID 2000,
attempts supported grants, and starts the independent bundled app_process
recorder. The initial Android pairing approval cannot be bypassed.

After shell UID 2000 is verified, **automatic TCP/5555 is enabled by default**.
VRec switches adbd, waits for the listener, reconnects to localhost:5555
and re-verifies shell UID and helper readiness. The listener may also be
reachable from other devices on the network; Settings lets the user opt out
and disable the listener when connected.

On older Android versions without Wireless debugging, a trusted external
USB ADB host must approve the debugging prompt and run
`adb devices` followed by `adb tcpip 5555`. Neither VRec nor an ordinary
Termux app can self-authorize USB ADB. Reboot can reset debugging on any device.

## Home and navigation

Home shows a **Recording dashboard**, a single **ADB control center** with
a four-state badge (green verified UID 2000, yellow progress, red failure,
grey disconnected), USB setup instructions and the Recording master switch.
The old Standard/ADB recording-method radio selector and separate in-app
permission wizard are removed from navigation. Settings exposes recording,
VoIP, advanced SIM audio sources, floating controls, diagnostics, logs,
updates and Quit alongside Settings in the persistent navigation.

## What Shell 2000 can and cannot do

An authenticated `id -u` output of 2000 means the ADB shell was authorized.
It does **not** mean root access, a running recorder, protected VOICE_CALL
audio availability, or both parties' audio being recorded. The daemon's
authenticated health and source diagnostics are separate checks.

After authorization, VRec tries to grant eligible phone state, contacts, call
log and notification runtime permissions via `pm grant`, then checks actual
permission state. It also checks/grants eligible Telecom call detection AppOp.
Android might refuse restricted permissions. User-controlled Accessibility,
notification-listener and overlay roles still need direct Android settings
approval. No universal one-tap bypass is available.

## Recording and import

The independent helper owns call monitoring and audio capture when available.
It may continue after the ADB transport disconnects but isn't a permanent
reboot service. ADB-only means **no automatic microphone fallback** if it is
missing or if a selected SIM/VoIP capture route isn't supported.

The helper can attempt Voice Call, uplink, downlink and combined sources where
permitted by the OEM. VoIP capture depends on Android version and app support.
A source with measurable volume is not proof that both call participants
were captured. Completed audio is imported via the authenticated local
channel; metadata identifies only observed details.

## ADB security choices and recovery

- **Automatic TCP/5555 after verification.** Wireless ADB pairing is only
  needed for initial authorization or repair after keys change. VRec defaults
  to moving verified UID 2000 to TCP/5555 and reconnecting. This can expose
  ADB to the network. The user can turn the mode off under *Settings →
  Keep ADB without Wi-Fi (TCP/5555)*. Disabling the preference does not itself
  guarantee an already listening endpoint has closed; VRec attempts to shut
  it down when a verified session is available and reports the result.
- **Restore after restart is opt-in** (*Restore after restart*, default off).
  Only then does VRec request `WRITE_SECURE_SETTINGS` through the verified
  shell (revoked again when switched off). After a reboot, which Android uses
  to turn Wireless debugging off, VRec may switch it back on, on Wi-Fi only.
  It never does so when Wireless debugging was turned off during the same
  boot (the user's choice), when the boot count is unknown, after a deliberate
  Disconnect, or while an authorization failure awaits Retry. The setting is
  read back; success needs re-verified UID 2000 and the helper answering.
  A paired key is not assumed to still be authorized.
- **Typed failures.** Connection failures are `AUTH_TIMEOUT`, `AUTH_REJECTED`,
  `CONNECTION_LOST`, `NETWORK_UNAVAILABLE`, `HELPER_UNAVAILABLE` or
  `PERMISSION_DENIED`, classified by stage and exception type, never by
  message text. Only `CONNECTION_LOST` is retried automatically (two quick
  retries, then exponential backoff from 1 to 30 minutes). Authorization
  timeouts, rejections and wrong UIDs require the user's Retry, so Android's
  prompt is never re-requested in the background.
- **Background recovery** runs in the app process (periodically and on Wi-Fi),
  not on Home. Quit stops it, the Wi-Fi listener, wireless discovery, in-flight
  connects and VRec's ADB session; opening VRec resumes it. Recording OFF and
  Disconnect both stop automatic reconnection.
- Wireless discovery is bounded (60 s per automatic search, backoff up to
  10 minutes, paused after 5 empty searches) and uses a single-flight lock
  for connect → UID 2000 → automatic TCP switch (unless disabled) → helper.

## Validation boundaries

Compilation, unit tests and APK builds validate code structure, not whether
Huawei's audio HAL exposes call channels or an unauthorized phone can start
ADB by itself. Real-device tests should include boot reset, key authorization,
previously verified reconnect, helper health, signal/channel diagnostics,
SIM versus VoIP identity, and Android-denied permission behavior.

Device validation still required for: restore after a real reboot (and OEMs
such as Huawei/EMUI that may refuse the Wireless debugging setting), revoked
authorization, slow adbd restarts during the automatic TCP switch, SIM/VoIP
capture and two-sided audio. CI passing proves build and unit-test
compatibility only.
