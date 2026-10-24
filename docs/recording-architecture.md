# VRec recording architecture (consolidated experimental)

## Two recording methods

1. **Standard** — Android foreground microphone service managed by `RecordingService` and `RecordingEngine`. Does not require ADB. The Home **Recording** master switch and automatic-recording setting control whether new recordings may start.
2. **Built-in ADB** — VRec's own Java shell process (`shellserver/src/com/vrec/app/shell/Main.java`) started through authenticated ADB, either Android Wireless debugging or TCP/5555. `AdbPrivilegedApiHelper` connects to and checks the health of `ExperimentalCaptureDaemon` via an authenticated loopback socket. It needs ADB to *bootstrap a missing process*, but may continue after that ADB connection is lost while Android keeps the shell process alive.

No Shizuku app, provider or user service is required. The old SHIZUKU setting migrates to Standard.

## Source-of-truth boundaries

- **Detection:** `CallMonitor` combines Telecom, SIM and VoIP notification signals, enriches metadata, and decides when recording is permitted. Notification metadata is not evidence of two-way audio capture.
- **Standard recording:** `RecordingService` starts a foreground service. `RecordingEngine` attempts one Android audio source at a time in auto mode (VOICE_CALL → VOICE_RECOGNITION → VOICE_COMMUNICATION; optional speaker mic), retaining the first source with a measurable signal. *Sequential* probing avoids competing simultaneous microphones and disruption of a known-good source; amplitude is **not** proof both callers are audible.
- **ADB enhanced recording:** `ExperimentalCaptureDaemon` bootstraps the bundled Java recorder once; the shell process owns its call watcher and audio pipeline independently of the APK's microphone recorder. Local loopback commands are authenticated, and completed audio is imported without relying on a persistent ADB connection.
- **Lifetime and health:** `AdbPrivilegedApiHelper` reconciles an existing daemon after app process restart and when the Experimental screen appears. It must not silently enable USB/Wireless debugging, start ADB over the network, or re-arm capture against the user's Home preference.
- **Mutual exclusion:** While the enhanced shell daemon is active, SIM calls are owned by it and VRec avoids a duplicate foreground microphone recording. VoIP uses the Standard fallback unless explicitly armed and supported by the shell side. The Home master switch stops future capture in both engines.
- **Storage/metadata:** `RecordingStore` stores audio in app-specific storage with a JSON sidecar. Only record metadata that is actually observed or derived; unknown number/carrier/SIM/party voice should remain unknown, and approximate call times must be labeled estimated.

## Privileges and recovery

- Android's `shell` UID is **not** root, and successful ADB pairing does not imply permission to capture every call-audio pipeline.
- A daemon that Android kills, a reboot, an APK reinstall that rotates the local token, or an incompatible OS update may require a new authorized ADB/TCP bootstrap.
- TCP/5555 should not be treated as safer than modern encrypted Wireless debugging: it can expose debugging to the local network, and it may not remain available after a restart.
- We do **not** restart recording after Home Off, nor infer two-way sound from nonzero amplitude.

## Regression checklist

- Standard source fallback: Voice call source fails, Voice recognition produces audible audio; no extra duplicate files.
- Standard silent fallback: test Communication mic only after Voice recognition remains silent during the probe.
- Built-in ADB: start daemon using authenticated ADB; disconnect ADB/Wi-Fi; reopen VRec; verify local authenticated probe and file import.
- Switch Home Off with an active call: both engines must stop/disable capturing; verify status, then re-enable only with explicit user action.
- Restart app without ADB: helper reports running if daemon survived, otherwise ADB required. Never claim auto-persistence across reboot.
- Old metadata remains readable; unknown details remain unknown rather than guessed.
- Recordings screen: only one active inline audio player and rounded action/sort sheets.
