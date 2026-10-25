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

## Validation boundaries

Compilation, unit tests and APK builds validate code structure, not whether
Huawei's audio HAL exposes call channels or an unauthorized phone can start
ADB by itself. Real-device tests should include boot reset, key authorization,
previously verified reconnect, helper health, signal/channel diagnostics,
SIM versus VoIP identity, and Android-denied permission behavior.
