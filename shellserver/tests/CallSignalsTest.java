package com.vrec.app.shell;

/** Call-audio ownership and headset-microphone decisions; pure Java. */
public final class CallSignalsTest {
    private static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    private static final int BT = CallSignals.TYPE_BLUETOOTH_SCO, WIRED = CallSignals.TYPE_WIRED_HEADSET,
            BUILTIN = CallSignals.TYPE_BUILTIN_MIC;
    private static final int NONE = CallSignals.MicFollow.NONE, RESET = CallSignals.MicFollow.RESET;

    static void followsBluetoothAndConfirms() {
        CallSignals.MicFollow f = new CallSignals.MicFollow();
        check(f.decide(5, BUILTIN, 5) == NONE, "both on the phone mic: nothing");
        check(f.decide(9, BT, 5) == 9, "call moved to headset id 9: follow that exact device");
        check(f.decide(9, BT, 9) == NONE, "routed: confirmed, nothing more");
        check(f.decide(9, BT, 9) == NONE, "stays quiet while confirmed");
    }

    static void unconfirmedRouteIsGivenUp() {
        CallSignals.MicFollow f = new CallSignals.MicFollow();
        check(f.decide(9, BT, 5) == 9, "asked");
        for (int i = 1; i < CallSignals.MicFollow.CONFIRM_CHECKS; i++) {
            check(f.decide(9, BT, 5) == NONE, "waiting for the route, check " + i);
        }
        check(f.decide(9, BT, 5) == RESET, "never routed: drop the preference");
        check(f.decide(9, BT, 5) == NONE, "and do not ask that device again");
        check(f.decide(12, BT, 5) == 12, "a different headset is still tried");
    }

    static void sameTypeDifferentDeviceIsFollowed() {
        CallSignals.MicFollow f = new CallSignals.MicFollow();
        f.decide(9, BT, 5);
        f.decide(9, BT, 9);
        check(f.decide(14, BT, 9) == 14, "another Bluetooth headset (same type, new id) is followed");
    }

    static void headsetGoneResets() {
        CallSignals.MicFollow f = new CallSignals.MicFollow();
        f.decide(9, BT, 5);
        check(f.decide(5, BUILTIN, 9) == RESET, "headset gone: back to the system's choice");
        check(f.decide(5, BUILTIN, 5) == NONE, "only once");
    }

    static void givenUpClearedWhenCallLeavesHeadset() {
        CallSignals.MicFollow f = new CallSignals.MicFollow();
        f.decide(9, BT, 5);
        f.refused();
        check(f.decide(9, BT, 5) == NONE, "refused device not retried");
        f.decide(5, BUILTIN, 5);
        check(f.decide(9, BT, 5) == 9, "after a disconnect/reconnect it is tried again");
    }

    static void unknownCallInputChangesNothing() {
        CallSignals.MicFollow f = new CallSignals.MicFollow();
        f.decide(9, BT, 5);
        check(f.decide(-1, -1, 5) == NONE && f.followed() == 9, "call input unknown: keep state");
        check(f.decide(0, BT, 5) == NONE, "id 0 is unknown too");
    }

    static void reopenForgetsPreference() {
        CallSignals.MicFollow f = new CallSignals.MicFollow();
        f.decide(9, BT, 5);
        f.reopened();
        check(f.decide(9, BT, 5) == 9, "a reopened microphone asks again");
        check(f.decide(16, WIRED, 5) == 16, "wired headset followed");
        check(f.decide(20, CallSignals.TYPE_BLE_HEADSET, 5) == 20, "LE audio headset followed");
    }

    public static void main(String[] args) {
        followsBluetoothAndConfirms();
        unconfirmedRouteIsGivenUp();
        sameTypeDifferentDeviceIsFollowed();
        headsetGoneResets();
        givenUpClearedWhenCallLeavesHeadset();
        unknownCallInputChangesNothing();
        reopenForgetsPreference();
        System.out.println("CallSignalsTest: OK");
    }
}
