# VRec experimental call recording — unified PR #32

This document describes the actual experimental implementation and its limitations.
**PR #32 is the only open experimental PR, targeting `main`.** PRs #19, #23, #26, and #31 are closed as superseded. Stable is unchanged until review and testing.

## Recording methods

**Standard (default):** The Android app's own foreground microphone recorder
tries `VOICE_CALL`, `VOICE_RECOGNITION`, then `VOICE_COMMUNICATION`; the optional
mic/loudspeaker fallback depends on user settings. Auto mode selects the first
source that starts and produces measurable sound. A non-silent recording does
**not** prove that both participants were captured.

**Built-in ADB (opt-in):** VRec pairs with the device's own Android Wireless
debugging using a bundled ADB client, or connects to an already enabled TCP/5555
endpoint. After verifying shell UID 2000 it can install and start the bundled
Apache-2.0 scrcpy/JemRec-derived Java `app_process` recording daemon. The
daemon captures into a shell-side file, uses a local nonce/HMAC-authenticated
loopback channel, and imports completed Opus/Ogg audio into VRec's library.

**Shizuku is not installed or required.** Its UI and dependency/provider/binder
integration were removed. A previously saved Shizuku choice migrates to Standard.

### Persistence and `AdbPrivilegedApiHelper`

- A successfully started shell Java process may continue running after the ADB
  session or Wi-Fi disconnects; it is **not** a permanent system service.
- `AdbPrivilegedApiHelper.kt` provides authenticated `reconnect()`,
  privileged `startWithAdb()`, and `checkHealth()` states. VRec uses it on
  app-process startup and in the Experimental recording screen.
  VRec now also attempts recovery at process startup and periodically while
  the app is in the foreground. Only an endpoint previously verified as
  Android shell UID 2000 may bootstrap a missing helper; reconnect retries
  are rate limited. A missing Android ADB authorization still requires the
  system prompt and user approval. Android may stop the app process in the
  background, so this is not an always-on watchdog.
- Neither the APK nor its helper can start a new shell-UID process without
  already-authorized ADB access. If Android kills the daemon or the phone
  reboots, a fresh privileged start may be necessary.
- Shell UID is not root. Android/OEM audio HAL restrictions still apply.
  InCallService and the Telecom AppOp help detect calls; they do **not**
  automatically unlock protected two-way audio.

## Setup and permission separation

- **Setup** contains only the everyday recording permissions (microphone,
  phone state and Accessibility) and optional Contacts, notifications and floating
  controls. A second WhatsApp enable switch and redundant Setup complete banner
  are no longer shown.
- The main Settings page has the only **Recording method** radio selector:
  Regular recording (default) or ADB privileged helper.
- **Settings → VoIP** owns the VoIP enable switch and Notification access
  permission, keeping internet-call options out of everyday setup.
- **Settings → Experimental** owns ADB pairing, Wireless/TCP connection, helper
  status and optional advanced Telecom AppOp permission. Merely choosing the
  ADB option does not establish elevated access or enable Home recording.

## Built-in ADB setup

1. On the main Settings page, choose **ADB privileged helper** under **Recording method**. Then tap **Configure ADB access**, or open Settings → Experimental.
2. For first-time phone-local authorization, use Android Developer options →
   Wireless debugging → Pair device with pairing code, preferably in split screen.
   Enter its temporary pairing code/port in VRec and pair.
3. Choose **Wireless** or **TCP/5555** in the single-choice connection
   selector, then tap **Connect**. Wireless uses discovery or an explicitly
   entered paired port; it does not silently fall back to TCP/5555. TCP requires
   an already enabled and authorized listener (e.g. `adb tcpip 5555` over USB).
4. After shell UID 2000 is verified, VRec **automatically starts or reconnects**
   the enhanced Java helper. The button then changes to **Disconnect ADB**:
   this closes VRec's ADB session **without stopping the shell helper or an
   active recording**. Home's Recording switch independently controls capture.
   Completed shell calls are automatically imported into VRec's recording library
   by the authenticated call-ended import service, so the extra Import calls
   button is unnecessary. Complete a consented test call and listen to both
   participants before relying on this mode.

Switching an existing authorized connection into TCP/5555 restarts `adbd` and
naturally drops the previous ADB session. VRec sends `tcpip:5555` only after
explicit confirmation, retries the localhost connection, and requires shell
UID 2000 before declaring TCP authorized. It remembers TCP as the requested
transport even if Android restarts but verification fails. TCP socket presence
alone is **never** proof of ADB authorization or a running recorder helper.

#### Phones without Wireless debugging (one-time USB bootstrap)

On devices that do not offer Wireless debugging, Android does not offer an
ordinary app permission popup that grants a third-party app shell-level ADB.
Use a trusted computer/USB ADB host and:

1. Enable USB debugging from Developer options and connect the phone to the host.
2. Approve the system's RSA debugging authorization prompt for that USB host.
3. Run `adb tcpip 5555` on the host. This is a one-time setup attempt, not
   something the VRec app can execute before it has its own authorized shell link.
4. In VRec, select TCP/5555 and tap **Connect TCP/5555**. Android may separately
   ask for confirmation of **VRec's own ADB key**, which differs from the host key;
   unlock the device and approve explicitly if shown. Retry after a timeout.
5. Confirm the screen says **Connected via TCP/5555 · shell UID 2000** before
   relying on this access for helper startup.

The existing VRec Accessibility service may assist with recognizing UI window
changes when enabled by the user, but it is **not** an ADB authorization method,
cannot summon an RSA prompt without a real ADB request, and must not auto-approve
security prompts. VRec instead instructs users to watch for Android's prompt
while the ADB connection is pending.

After a manually verified ADB connection, VRec remembers the verified transport
and can automatically reconnect/restart a missing helper while running. It
never enables Wireless/USB debugging or TCP itself. The user may tap Disconnect
to disable automatic ADB retries until their next explicit Connect. USB
debugging alone is not an ADB transport: TCP recovery also requires an
already-listening TCP/5555 endpoint with VRec's ADB key still authorized.

ADB over TCP may listen on LAN interfaces and exposes debugging access to other
authorized clients. Android may disable legacy TCP mode on reboot, debugging
changes, or OEM policy. **Never enable it without explicit approval**. Turn it
off after setup when it is no longer needed; disconnecting VRec's ADB session
alone does not disable the TCP listener.

## Runtime fallback when the ADB helper is unavailable

- Selecting **ADB privileged helper** does not switch off the Home master
  recording toggle and does not silently turn Android debugging on.
- When recording is enabled on Home and a recording actually starts while
  ADB mode is selected but the enhanced helper is unavailable, VRec uses its
  normal microphone recorder **for that recording**. It retains the selected
  ADB method for future calls.
- Once the standard fallback starts, VRec posts a dismissible **ADB helper
  unavailable · Standard recording** notification with **Enable debugging**
  (opens Android Developer options) and **ADB setup** (opens the Experimental
  settings page) actions. Android 13+ requires notification permission for
  this reminder. Home also displays the fallback state while it is recording.
- No reminder is posted merely because the Home master switch is turned on,
  or while the enhanced helper remains healthy despite Wi-Fi/ADB transport
  disconnecting.
- If ADB is restored during a standard fallback on an ongoing **cellular**
  call, VRec now checks that both the app's call monitor and the shell daemon's
  own live call watcher recognize that call. Only then does VRec **save the
  standard segment, enable enhanced capture and explicitly start a new shell
  segment for the remaining call**. The two segments are kept separately.
  Neither an existing helper nor ADB authorization alone proves handoff safe.
- If the shell daemon cannot verify the active call, standard recording
  continues without interruption. If the enhanced segment fails to start after
  standard finalization, VRec disables shell capture and attempts a fresh
  standard segment while the call is still live; any small transition gap is
  unavoidable. Paused, VoIP, unknown-direction and ended calls are never
  forcibly switched mid-call.
- Home shows the enhanced segment after a confirmed handoff, hides the
  duplicate Record now button, and clears the indicator on call end.
- This runtime fallback is not a guarantee of audible remote-side audio on
  every phone. Android may prevent microphone recording from starting in the
  background or capturing the far end of the call.

## Call detection, recordings and metadata

- Unified `CallMonitor` handles SIM phone state, optional non-UI Telecom
  InCallService, WhatsApp/other VoIP notifications, and manual recording.
  One active standard recording is allowed; device-dependent sources may fail.
- The Home recording master switch is opt-in and stops/prevents capture when
  disabled. Advanced call-detection permission status is shown in one card
  (Granted / Not granted).
- Standard capture is stored as M4A; imported enhanced recordings use Ogg,
  both with backwards-compatible JSON sidecars. Current metadata can be
  incomplete or estimated for some sources and old recordings; audio duration
  and call duration are different quantities.
- The recording library has a single in-card player with seek, pause/resume
  and stop, rounded recording action/detail sheets, share, sorting, bookmarks,
  notes and metadata editing.

## Verification status and acceptance criteria

On the user's Pixel, a **26-minute Standard Voice recognition recording**
contained both participants, with the remote person faint. The same logs
showed `VOICE_CALL` start exceptions and a silent Voice recognition probe
on another short call. This is **not** proof that enhanced shell capture works
for every SIM or third-party VoIP app.

Acceptance testing should check: no duplicate recordings; correct caller and
direction metadata; silent-source fallback; the Home Off guard; in-card player,
sort/action sheets and existing recordings; privileged ADB pairing, launch,
loopback reconnect, Wi-Fi loss and TCP security; import and two-sided listening;
and overlay/master toggle integration. Passing GitHub CI establishes build
compatibility, not a device-specific protected audio entitlement.

Future improvements requested but **not yet implemented** include a stronger
source-quality selector, validated per-recording audio-source diagnostics,
metadata-consistency fixes, and OS-scheduled background recovery when the app
process has been stopped.


## 2026-10-25 — one-batch ADB/PCM reliability review (ShizuCallRecorder 2.x ideas)

VREC retains its existing **built-in ADB, UID 2000, authenticated loopback,
Apache-2.0 scrcpy** implementation; it does **not** install Shizuku or copy
GPL-3.0 ShizuCallRecorder source code. This batch independently implements
the applicable safety properties from upstream's October native-audio rework:

- **Stereo PCM diagnostics:** every enhanced capture is passively sampled
  before Opus encoding. The shell logs bounded per-channel RMS, silence share,
  clipping share, and frame count, without storing or logging audio samples.
  Left/right energy is **not proof of both callers**, nor a guarantee of audio
  routing on every Android/OEM version. The established AudioRecord source,
  Opus 48 kHz stereo output, and caller privacy are unchanged.
- **No infinite zero-read spin:** the audio encoder retries transient empty
  AudioRecord reads with 10 ms backoff, up to 20 consecutive returns, before
  treating the source as stalled. Negative reads immediately report failure.
  Input/output worker crashes and unexpected I/O errors now propagate a
  fatal flag to the recording owner instead of leaving an apparently running
  but silent session. Normal stop does not generate false failure.
- **Recoverable recording shutdown:** a separate recovery thread (never the
  encoder's own callback thread) stops/finalizes a failed recording, then
  notifies the app to import existing audio packets. Compatibility check
  occurs before the output file is created; failed setup cleans it up.
- **Incomplete packet protection:** Opus import distinguishes a clean frame
  boundary from truncated PTS/header/payload, preserves the original shell
  recording on incomplete data, removes the temporary Ogg, and reports a
  failure rather than publishing a misleading successful file. A lost tail at
  an exact frame boundary is not detectable by the existing scrcpy framing;
  full physical-device validation remains essential.
- **Privileged helper health:** authenticated PONG alone cannot return the
  helper to RUNNING. The current Home-mode / Telecom-answer handshake must
  succeed first, including after a stale ERROR state; failure stays ERROR and
  never silently activates legacy, incompatible helpers.
- **Regression coverage:** pure Java shell tests cover PCM energy/silence/clips,
  empty/negative buffer handling, audio zero-read retry bounds, and the
  existing outgoing answer gate. JVM tests cover truncated encoded packets,
  impossible lengths, clean EOF and helper handshake state.

**Deliberately not integrated:** a second native capture stack, dual
AudioPolicy/AudioRecord sources, automatic AGC/ducking, codec replacement or
hidden-system-permission changes. Those depend on OEM audio routing and
carry substantial regression and GPL compatibility risk. The benefits here
come from reliability and diagnostics, not from bypassing Android capture
restrictions. Test with consented SIM/VoLTE, Wi-Fi calling and Bluetooth calls
on Android 13–17 before promoting out of experimental.

## Attribution

Embedded ADB and audio capture follow the Apache-2.0 compatible JemRec/scrcpy
architecture. `shellserver/LICENSE-scrcpy` and `shellserver/PATCHES.md`
record the required licensing and adaptation details. ShizuCallRecorder and
other GPL-only projects served as design references rather than copied code.

**Do not merge this experimental PR into stable until current CI and
real-device tests have passed.**

## Experimental overlay and answered-call regression safeguards

- Compact floating controls now default to **on**, but Android's separate
  **Display over other apps** permission is still required; an explicit
  user-disabled value is preserved. The far-right three-dot grip and status
  indicator move the controls and remember a position safely clamped to
  the display after rotation.
- Outgoing cellular calls with **enabled, authorized non-UI Telecom detection**
  wait for the actual `Call.STATE_ACTIVE` event. OFFHOOK alone is *dialing*,
  not an answer. A verified ACTIVE signal reaches the shell recorder via an
  authenticated loopback command. Incoming calls start on their off-hook state.
- When an older shell binary survives the app update, a verified ADB session
  can upgrade it after its current call ends. If it refuses to stop because a
  call is in progress, do not interrupt that call; the app must fall back to
  Standard until the helper is updated.
- When advanced Telecom observation is unavailable, Android exposes only the
  coarser OFFHOOK signal; the legacy immediate fallback remains, and its precise
  connection timing cannot be guaranteed. If an older shell helper is still
  running, its mode handshake may need refreshing after upgrading the APK.
- CI includes JVM tests for gate transitions, no double starts, Home-off/legacy
  conditions, draggable overlay bounds, and the default enabled setting.
  Real-device tests are still needed for OEM call states and overlay permission.
