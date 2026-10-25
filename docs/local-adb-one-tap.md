# VRec USB-only ADB setup and auto-recovery

## On the phone, before first use

Enable **USB debugging** in Developer options. A phone-local app shell is not
Android's privileged ADB shell and cannot authorize itself.

## One-time external USB host

Use a trusted external USB ADB host, connect the Huawei through USB, approve
Android's authorization prompt, and run:

```sh
adb devices
adb tcpip 5555
```

On the phone, open VRec Home → **Connect / restore ADB**. The built-in client
uses only **127.0.0.1:5555**, never discovery or wireless pairing, and
requires `id -u` to confirm `2000`. If Android asks to trust VRec's
separate ADB identity, approve that OS prompt.

Without the external USB ADB host, stock USB-only Huawei phones cannot
bootstrap this endpoint using Termux, VRec's local command middleware, or
a container. After authorization, automatic reconnection can work as long
as `adbd` stays available at TCP/5555; reboot or OEM settings may disable it.

## Home indicator

Grey: disconnected / connect. Yellow: connecting. Red: failed or missing
bootstrap. Green: shell UID 2000 verified. The helper's readiness and audio
quality are separate checks. Connection success does not establish recording
health or privileged two-way audio capture.

## Automatic permission attempts

Only after UID 2000 verification, VRec tries permitted `pm grant` and Telecom
AppOp commands. Every grant is checked after execution; Android-denied access
is reported on Home and in ADB diagnostics. No silent claim of full access.
Android Accessibility, notification listeners and overlay permissions remain
subject to user-controlled system settings. The Accessibility service is
named **Vrec call recording helper**.

## Recording rules

Only the shell helper captures new calls in this experimental workflow.
A missing shell helper blocks capture instead of starting the app's standard
microphone recorder. The Home Recording master switch still governs arming.
TCP/5555 can expose debugging access beyond localhost; disable it when done.
