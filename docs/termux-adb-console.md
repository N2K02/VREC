# Optional Termux console in VRec

VRec Home contains an optional expandable **Termux command console** which
executes user-submitted commands in the Termux app and displays their output.
Termux must already be installed from F-Droid and opened at least once. The console does not simulate a second USB host: sharing one phone cannot recreate the physical USB host/device connection.

Configure Termux:

1. In Termux: `pkg install android-tools`.
2. In `~/.termux/termux.properties`, set `allow-external-apps=true`;
   restart Termux. A sample command is `mkdir -p ~/.termux; printf
   '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties`.
3. In Android Settings for **VRec**, grant the additional permission
   **Run commands in Termux environment**. This permission authorizes VRec
   to execute shell commands in Termux, so only use a trusted VRec build.
4. Return to VRec Home, open the optional console, enter a command, and
   tap **Run command in Termux**. Android Platform Tools can report device
   and ADB version details. Output stays in VRec and is not persisted in its
   ordinary activity log. Termux >= 0.109 is required for returned results.

**Important security and Android limitation:** Termux uses its own ordinary
app UID. `adb` inside Termux is an ADB client, not a host-side authorization
bypass. It cannot connect to the phone's own USB gadget to obtain UID 2000.
For VRec's USB-only ADB capture, a separate trusted USB ADB host must
first authorize the target device and run `adb tcpip 5555` after `adb devices`.
Termux on ANOTHER Android device connected by USB can be such a host if
proper USB-host tooling is installed. On the target phone, once an authorized
`127.0.0.1:5555` ADB service is running, VRec's embedded ADB client verifies
shell UID 2000 and applies eligible grants in sequence.

The Termux console does NOT run Android `pm grant` automatically and does NOT
replace VRec's existing verified ADB setup flow. Do not log entered commands:
they may contain credentials.
