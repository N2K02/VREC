# VREC — Call recorder (no root, no Shizuku, no debugging)

VREC is a personal Android call recorder that works **without root, without Shizuku, and without
USB or wireless debugging**. It records SIM calls (and optionally VoIP calls like WhatsApp) using
only permissions you grant on the phone itself.

## Download

- [Latest release / APK download](https://github.com/N2K02/VREC/releases/latest)
- [All releases](https://github.com/N2K02/VREC/releases)

Install the APK, open VREC, and follow the two setup steps. Each push to `main` builds a new APK
through GitHub Actions and publishes it here; the app can also update itself from these releases.

## How it records

Since Android 10, an ordinary app is handed **silence** while a call is active. The only no-privilege
exception the system allows is an **enabled accessibility service** — the same mechanism Cube ACR and
ACR Phone rely on (and the reason they ship a separate helper app). VREC includes a minimal
accessibility service whose *only* job is to be switched on so the system treats VREC's microphone
capture as privileged during a call. It does not read your screen, tap buttons, or control calls.

Whether the **other party** is captured still varies a lot by phone, Android version, headset and
Wi-Fi calling — no app can guarantee it without root. So VREC doesn't trust a single microphone
source. In **Automatic** mode it tries a fallback chain and keeps the first that produces sound:

1. **Voice call line** — full two-way on some OEMs; blocked/silent on many phones.
2. **Voice recognition** (with in-communication mode) — often the best two-way result.
3. **Communication mic** — your side plus some of the other side.
4. **Microphone + loudspeaker** — last resort: the mic records both sides from the room.

Each source is probed for ~2.5s; if it only produces silence, VREC moves to the next. If a recording
still comes out silent, VREC tells you so in the list and the activity log, so you know this device
blocks call capture on that source. You can also pick a single source by hand in Settings.

## Recording management

The Recordings screen includes caller/search text matching, incoming/outgoing/VoIP/favorites/silent filters, sorting by date, duration, size or caller, multi-select deletion, favorites, notes, manual caller labels, playback speed, bookmarks, audio sharing and JSON detail export. Open a recording to see its metadata and edit caller number, country/calling code, SIM label and carrier when Android did not provide them.

Recordings remain M4A audio files with a JSON sidecar. Older recordings without newer metadata remain visible and playable. Caller and SIM fields are best-effort: Android may not expose the phone number, contact match, or the SIM subscription used for a particular call. VREC shows unknown/unavailable rather than guessing. Country is only suggested for recognised explicit international prefixes; it can be edited manually. SIM label and carrier can be added manually when the call event doesn't identify them.

Bookmarks are saved as timestamps in the sidecar; playback speed is a local playback setting. Recording audio and metadata remain on-device unless you explicitly share/export them. Bulk deletion asks for confirmation.

## Setup

1. **Microphone & phone permissions** — to record and to notice when a call starts (contacts is
   optional, only to show who you were talking to).
2. **Accessibility service** — switch on "VREC call recording" under Android's Accessibility settings.
   - If the toggle is greyed out with *"Restricted setting"* (Android 13+), open
     **Settings ▸ Apps ▸ VREC ▸ ⋮ ▸ Allow restricted settings**, then try again.
   - On **Android 16** you can't enable accessibility *during* a call — set it up beforehand.
3. **VoIP (optional)** — grant Notification access and turn on "Record VoIP calls" to also record
   WhatsApp, Telegram, Signal and similar. These usually need the loudspeaker option to catch both sides.

## Settings

- **Record calls automatically** — start as soon as a call connects.
- **Microphone source** — Automatic (recommended), or a specific source.
- **Put call on loudspeaker** — last-resort way to capture both sides.
- **Force in-communication mode** — helps some phones route the far side into the recording.
- **Record VoIP calls** — WhatsApp etc., via Notification access.
- **Discard silent recordings** — drop recordings this phone blocked.

Recordings are saved privately in VREC's own storage and never leave the phone unless you share them.

## Known limits

- Capturing the other party is **device-dependent** — some phones only ever record your side.
- Bluetooth / wired headset calls usually capture only your side.
- Android 17's Advanced Protection mode can disable non-accessibility-tool services; VREC can't
  record while that mode is on.
- Recording calls may require the other person's consent where you live. You are responsible for
  using VREC lawfully.

## Build

Standard Android/Gradle project (Kotlin, Jetpack Compose, Material 3). CI builds a signed debug APK
and publishes it as a release. No source other than the phone itself is needed to record.

## Links

- [Source repository](https://github.com/N2K02/VREC)
- [Report an issue](https://github.com/N2K02/VREC/issues)
