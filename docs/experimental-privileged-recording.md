# VRec enhanced SIM/VoIP recording — experimental PR #40

This document reflects the ADB-only changes in the experimental branch.
Stable `main` stays untouched until review.

## USB-only connection

VRec includes an embedded ADB client and a sandboxed local diagnostic runner;
neither is a substitute for a physically authorized USB ADB host. Enable
USB debugging, connect to a trusted external USB host, approve the prompt,
then run `adb tcpip 5555` there. VRec will connect to localhost TCP/5555,
verify Android shell UID 2000, attempt eligible permission grants, and start
the independent bundled app_process recorder. It does not expose Wireless
Debugging pairing, mDNS discovery, or transport selection.

On unrooted, USB-only Huawei firmware **without** another host or an existing
authorized TCP endpoint, first-time elevated ADB access cannot be established
by a local app shell, PRoot, Termux or a Windows container. TCP can stop
listening after reboot. It can also expose ADB externally even when VRec
uses loopback; treat the debugging endpoint as sensitive.

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

- **Wireless debugging is kept by default.** After a verified wireless
  connection (shell UID 2000), VRec stays on the encrypted Wireless debugging
  TLS session. It moves ADB to TCP/5555 only when the user turns on
  *Settings → Experimental → Keep ADB without Wi-Fi (TCP/5555)* and confirms
  the network-exposure warning. Shell UID 2000 is re-verified after every
  transport change, and setup is reported ready only when the recording helper
  answers. *Disable TCP/5555* closes the listener; if VRec has no session, the
  UI explains how to close it from Developer options.
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
  for connect → UID 2000 → optional TCP switch → helper.

## Validation boundaries

Compilation, unit tests and APK builds validate code structure, not whether
Huawei's audio HAL exposes call channels or an unauthorized phone can start
ADB by itself. Real-device tests should include boot reset, key authorization,
previously verified reconnect, helper health, signal/channel diagnostics,
SIM versus VoIP identity, and Android-denied permission behavior.

Device validation still required for: restore after a real reboot (and OEMs
such as Huawei/EMUI that may refuse the Wireless debugging setting), revoked
authorization, slow adbd restarts during the optional TCP switch, SIM/VoIP
capture and two-sided audio. CI passing proves build and unit-test
compatibility only.
