# VRec embedded local command runner and USB-bootstrapped ADB

VRec has a built-in Android app-shell diagnostic runner and a separate
embedded ADB client. Neither requires a Termux installation, Windows
emulator, container or Shizuku. The local diagnostic runner has VRec's
normal app UID and cannot grant shell UID 2000, root, or protected
two-way call recording.

## Home setup

In **Enhanced ADB** mode, the Home page shows **ADB quick setup** and
**USB debugging**. Tap **Set up automatically** or enable the Home
recording master switch to reattach a running, authorized helper or
attempt localhost TCP/5555. A successful ADB handshake must return
Android shell UID 2000 before the independent recording helper starts.

## First-time USB authorization

1. Enable USB debugging in Developer options.
2. Connect a **trusted external USB ADB host** and authorize it.
3. On that host, run `adb tcpip 5555` once.
4. Return to VRec and connect to localhost TCP/5555. Approve any
   Android authorization prompt for VRec's separate ADB identity.
5. Test the helper and both audio channels before relying on recording.

On an unrooted Huawei with only USB debugging, no separate host and no
pre-existing TCP listener, an ordinary local Android process cannot
perform step 3. VRec reports this limitation rather than promising
that one tap will unlock shell permissions.

No wireless pairing, discovery, network selection, or Wi-Fi-specific
permissions are used for the ADB connection. TCP/5555 may still expose
Android debugging beyond localhost; disable it when not needed.
