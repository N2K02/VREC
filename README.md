# VRec — Android call recorder

VRec records calls locally. In the current **experimental PR #40**, recording
is **ADB-first and ADB-only**: the separate shell recording helper runs after
an authorized Android shell UID 2000 connection. No Standard/ADB method
selector, wireless pairing, or automatic microphone fallback remains in this
experimental workflow. The stable `main` release remains unchanged until merge.

## First-time Huawei USB setup

1. On the phone, enable **Developer options → USB debugging**.
2. Connect to a **trusted external USB ADB host**, authorize its RSA prompt,
   and run `adb devices` followed by `adb tcpip 5555` on the host.
3. Return to VRec, press **Connect / restore ADB**, and approve any system
   authorization request for VRec's own ADB client key.
4. VRec connects to **127.0.0.1:5555**, verifies **UID 2000**, attempts
   eligible Android permission grants, and starts its independent recorder.
5. Enable the Home **Recording** master switch. Test an actual consented call:
   shell UID 2000 does not guarantee both callers' audio is accessible.

**Important:** On a stock, unrooted Huawei with only USB debugging, no second
device and no existing authorized TCP endpoint, VRec, Termux and containers
cannot grant themselves ADB shell access. An external USB ADB host is
necessary for the initial `adb tcpip 5555` command. Android may reset the
TCP listener on reboot or debugging changes, requiring that step again.

The local TCP/5555 service may be accessible on the network despite VRec only
connecting via localhost. Disable it when no longer needed. **Wireless
Debugging pairing and discovery are not part of this app.**

## Home status

- **Green:** an authorized ADB connection verified shell UID 2000.
- **Yellow:** a connection attempt is in progress.
- **Red:** the last setup attempt failed, was refused, or lacks a required host.
- **Grey:** disconnected / not yet attempted.

The badge represents the ADB connection, **not proof that the recording helper
is healthy or two-way audio is available**. VRec tracks helper readiness
separately. After first authorization, it can reconnect automatically while
the previously authorized TCP endpoint is available.

## Permission automation and Android limits

After a verified UID 2000 connection, VRec **attempts and verifies** shell
grants for phone state, contacts, call log, notification permission, and
eligible Telecom call-detection access. Android can refuse restricted grants
depending on firmware and installer policy. VRec displays ungranted access
rather than declaring false success.

Accessibility (**Vrec call recording helper**), notification-listener role
access, and floating overlay access are user-controlled Android settings.
These cannot be silently or universally granted by the app. There is no
separate in-app permissions wizard; relevant OS settings remain accessible
where needed.

## Other features

- Independent shell capture for supported SIM and VoIP calls.
- Select SIM call-audio source where Android permits; shell access isn't root.
- Audio silence/channel diagnostics and safe recording metadata.
- Saved recordings with search, filtering, playback, details, and sharing.
- Floating controls and a Quit action next to Settings.
- All recordings are stored locally unless explicitly shared.

## APKs and updates

- [Stable releases and APKs](https://github.com/N2K02/VREC/releases/latest)
- [All releases](https://github.com/N2K02/VREC/releases)
- [Experimental PR #40](https://github.com/N2K02/VREC/pull/40)

The experimental PR builds are not proof of compatibility with a particular
Huawei firmware. Test capture with consent and inspect audio diagnostics.
