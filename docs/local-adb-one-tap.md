# VRec in-app ADB quick setup

This is part of experimental PR #40; main is unchanged until review.

## One-tap behavior

When the user selects Enhanced ADB and taps **Set up automatically** on
Home, VRec's internal LocalAdbMiddleware first tries to reattach to its own
authenticated shell recording helper. If it has stopped, the middleware uses
the already-bundled libadb client to connect to a detectable, user-authorized
localhost adbd endpoint (TCP/5555 or previously paired Wireless debugging),
verifies Android shell UID **2000**, then launches the bundled independent
Java recording daemon. The Home Recording master switch invokes the same
readiness check when Enhanced ADB is selected.

VRec includes a small Termux-style **local diagnostic runner** using the
phone's own Android command executables. Its commands run under the ordinary
VRec app UID; it is not root, not shell UID 2000, and cannot start adbd,
authorize USB, change protected system properties, or capture protected audio.
No Windows emulator, Termux installation, root, or Shizuku is required for
these local diagnostics. Privileged shell commands use the existing embedded
authenticated ADB transport instead of the local diagnostic runner.

## First-time authorization

* If Wireless debugging is available: enable it in Developer options,
  pair once with Android's six-digit pairing dialog under
  Settings → Experimental, then return to Home and use one tap.
* If TCP/5555 was previously enabled from a trusted ADB host: tap the one-tap
  action; VRec will try localhost and require shell UID 2000.
* If a Huawei phone has **USB debugging only** and no TCP listener: no
  computerless authorization workaround is provided. A local shell,
  container, adb client binary, or running adb server cannot transform
  ordinary app access into shell privilege. A separate authorized ADB host
  is needed once to issue `adb tcpip 5555`, or supported pairing must be
  made available by the firmware. This is not a one-tap solvable case.

USB and Wireless setup cards are deliberately separate and show device
capabilities without claiming that a debug toggle equals authorization.
An open TCP socket by itself does not mean the helper is ready. Real-device
capture reliability and audio HAL support still require testing.

## Consent and safety

The app does not enable Android Developer options, switch debugging
transports, pair on behalf of the user, or approve ADB prompts. Automatic
recovery only touches an available locally authorized endpoint; Android's
prompts and original one-time pairing remain the user's responsibility.
The detached helper remains independent of Termux/Shizuku.
