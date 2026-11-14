package com.vrec.app.shell;

/** Call-audio ownership and headset-microphone decisions; pure Java. */
public final class CallSignalsTest {
    private static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    static void phoneCallAudio() {
        check(CallSignals.phoneCallAudioUp(CallSignals.MODE_IN_CALL, false), "in-call keeps a phone call up");
        check(CallSignals.phoneCallAudioUp(CallSignals.MODE_IN_CALL, true),
                "in-call is telecom's even if a messenger also plays");
        check(CallSignals.phoneCallAudioUp(CallSignals.MODE_IN_COMMUNICATION, false),
                "communication mode with no messenger still counts (Wi-Fi calling, some dialers)");
        check(!CallSignals.phoneCallAudioUp(CallSignals.MODE_IN_COMMUNICATION, true),
                "a messenger call owning the mode means the phone call is over");
        check(!CallSignals.phoneCallAudioUp(0, false), "normal mode ends it");
        check(!CallSignals.phoneCallAudioUp(1, false), "ringtone mode ends it");
    }

    static void headsetFollowing() {
        int bt = CallSignals.TYPE_BLUETOOTH_SCO, wired = CallSignals.TYPE_WIRED_HEADSET,
                builtin = CallSignals.TYPE_BUILTIN_MIC;
        check(CallSignals.micToFollow(bt, builtin, 0) == bt, "Bluetooth takes the call: follow it");
        check(CallSignals.micToFollow(bt, bt, 0) == -1, "already on it: nothing to do");
        check(CallSignals.micToFollow(bt, builtin, bt) == -1, "already asked for it: do not ask again");
        check(CallSignals.micToFollow(wired, bt, bt) == wired, "moved from Bluetooth to wired: follow");
        check(CallSignals.micToFollow(builtin, bt, bt) == 0, "headset gone: back to the system's choice");
        check(CallSignals.micToFollow(builtin, builtin, 0) == -1, "phone mic throughout: leave it");
        check(CallSignals.micToFollow(-1, builtin, bt) == -1, "call input unknown: change nothing");
        check(CallSignals.micToFollow(CallSignals.TYPE_BLE_HEADSET, builtin, 0) == CallSignals.TYPE_BLE_HEADSET,
                "LE audio headset followed too");
    }

    public static void main(String[] args) {
        phoneCallAudio();
        headsetFollowing();
        System.out.println("CallSignalsTest: OK");
    }
}
