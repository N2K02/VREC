# VRec recording architecture — experimental PR #41

## Capture path

Home Recording → CallMonitor → ExperimentalCaptureDaemon →
independent Java shell recorder → authenticated loopback import →
RecordingStore → Recordings UI.

New captures use the authorized shell helper only; no microphone fallback.
Capture STARTED/PAUSED/ENDED events, authenticated snapshots, and the helper
control protocol drive recording state. Recording mode follows Off (0),
Automatic (1), and on-demand (2) semantics. Manual shell starts are verified
against the selected call source and refused on mismatch.

## ADB initialization and recovery

- On Android 11+, the user enables USB and Wireless debugging and enters the
  one-time Android pairing-code dialog. With the user-enabled Accessibility
  service, VRec reads the code automatically; otherwise it supports manual entry.
- The embedded ADB transport connects to this phone's TLS pairing/connection
  ports, verifies `id -u == 2000`, then configures eligible permissions.
- Automatic TCP/5555 is enabled by default. After verification, VRec switches
  transports, waits for adbd, reconnects to localhost:5555 and re-verifies
  the shell UID and helper. TCP may be network accessible; Settings has an opt-out.
- A trusted external USB host with `adb tcpip 5555` remains an alternative
  for Android versions without Wireless debugging.
- AdbLifecycle owns periodic background recovery, while shared transport
  serialization coordinates foreground setup, wireless pairing and TCP repair.
  A separate recovery gate may wait for wireless discovery without blocking
  the pairing callback.
- A detached healthy helper does not require a permanently attached ADB client;
  reboot or OEM policies can still stop the helper and TCP listener.
- Previously UID-2000-verified dynamic TLS ports are cached **within the same boot only**.
  An optimistic reconnect keeps mDNS discovery running and falls back to the newest
  observed local port on connection failure; stale service-lost callbacks cannot
  erase newer ports. Authentication and UID 2000 are rechecked, never inferred
  from a cached port or a listening socket.
- The app's existing ADB lifecycle mutex coordinates manual starts, background
  recovery, and wireless pairing. A 15-minute WorkManager watchdog provides a
  best-effort process-death backstop; boot and package-update receivers request
  safe recovery after user storage is available. Android may defer background
  jobs, so this is not an always-running service.
- Deliberate Quit persists a stop latch, cancels the watchdog, discovery and
  background recovery; only reopening VRec re-enables them.
- Pairing-code auto-read is ON by default when the user enables VRec's existing
  Accessibility service (no additional Accessibility service is installed).
  It reads only the Android Settings pairing dialog and retries short-lived
  window reads while Android renders the code after the opening event. The
  user can disable this in VRec; no code is persisted or logged, and manual
  code entry and notification RemoteInput remain available.
- A classic TCP/5555 port is **not guaranteed to persist across reboot**:
  unprivileged shell UID 2000 cannot generally write persist.adb.tcp.port
  on stock Android. This app never requires root or quietly modifies persistent
  system properties. Any TCP listener may be network-accessible.

## Recording data integrity

The shell produces an authenticated Opus stream. VRec validates packets and
writes the playable OGG file via a temporary file. It writes complete JSON
sidecar metadata using Android AtomicFile before deleting the shell original.
Write failures keep the shell original for retry. Metadata edits and bookmarks
use atomic writes, and deletions/saves report failures to the UI.
Confirmed-silent recordings are discarded only when the user enables that
option and both channels are quiet or audio is missing. Unknown results are
retained. Live calls reconcile authenticated capture status every 20 seconds;
background checks retry completed imports even after missed END events.

Storage and capture errors appear as Home warnings and Android notifications
where allowed, with no call identity in the notification. The app never treats
UID 2000 alone as proof of two-way audio.

## Validation

Regression tests cover pairing decision policy, transport synchronization,
navigation stability, helper readiness, quit, imports and metadata contracts.
Physical-device testing remains necessary for capture quality, reboot,
wireless pairing and network listener behavior.
