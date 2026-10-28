# VRec local ADB setup and automatic recovery

## First-time setup (Android 11+)

Enable **USB debugging** and **Wireless debugging** in Android Developer options.
Connect to Wi-Fi, then in VRec Home tap **Set up ADB recording**.
Open Android's **Pair device with pairing code** screen and enter its six-digit
code in the VRec pairing notification or on-screen field. Only Android can
authorize the first pairing.

VRec discovers the TLS endpoints on **this phone**, verifies shell UID 2000,
configures eligible permissions, and starts the detached recorder.

## Automatic TCP/5555

Once UID 2000 is verified, VRec defaults to switching adbd to TCP/5555, waits
for its restart, reconnects to **127.0.0.1:5555**, and checks UID 2000 and helper
readiness again. Later repairs use the listener when it remains available.
TCP/5555 may also be accessible from other devices on the network. Disable
automatic TCP mode in Settings if undesired; this is an opt-out setting.

Reboot or device debugging policies may disable the TCP listener. VRec cannot
guarantee unattended recovery when Android requires fresh authorization.

## USB-host fallback

For Android versions without Wireless debugging, enable USB debugging, approve
a trusted external USB host, then run:

```sh
adb devices
adb tcpip 5555
```

An ordinary phone app (including VRec or Termux) cannot authorize itself as an
ADB shell. After the host enables TCP/5555, return to VRec and select setup.

## Home and recordings

The badge identifies the current ADB shell, not audio quality. Helper readiness
and active capture are separate. Missing helper or failed imports show a user
warning instead of silently creating a microphone fallback. Imported recordings
store their JSON metadata atomically and retain the shell original on failures.

Android-protected Accessibility and notification-listener permissions still
require user approval. Test an actual consented call on the target phone.
