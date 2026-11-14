package com.vrec.app.shell;

/**
 * Decisions about which call owns the audio, kept free of Android so they can
 * be tested (CallSignalsTest).
 */
final class CallSignals {

    private CallSignals() {
    }

    /**
     * The phone call just ended (its end confirmed). [messengerRecording] is
     * the messenger call being recorded, if any; [messengerFollowed] the one
     * the VoIP watch sees up. Returns the messenger call to record now, or
     * null. A messenger call that began during the phone call was turned
     * away then (one recording at a time); it is the call now.
     */
    static String messengerAfterPhoneEnd(String messengerRecording, String messengerFollowed) {
        return messengerRecording != null ? null : messengerFollowed;
    }

    /** The messenger call just ended: record the phone call if one is up. */
    static boolean phoneAfterMessengerEnd(boolean messengerWasRecording, boolean phoneCallUp) {
        return messengerWasRecording && phoneCallUp;
    }

    /** AudioDeviceInfo types of a headset microphone. */
    static final int TYPE_BUILTIN_MIC = 15;
    static final int TYPE_BLUETOOTH_SCO = 7;
    static final int TYPE_WIRED_HEADSET = 3;
    static final int TYPE_USB_DEVICE = 11;
    static final int TYPE_USB_HEADSET = 22;
    static final int TYPE_BLE_HEADSET = 26;

    static boolean headsetMic(int type) {
        return type == TYPE_BLUETOOTH_SCO || type == TYPE_WIRED_HEADSET || type == TYPE_USB_DEVICE
                || type == TYPE_USB_HEADSET || type == TYPE_BLE_HEADSET;
    }

    /**
     * WHICH INPUT THE RECORDING MICROPHONE SHOULD PREFER, BY DEVICE IDENTITY.
     *
     * Bluetooth or a wired headset taking the call's input mid-call left the
     * recorder on the phone's own microphone, which hears almost nothing of
     * the user once the phone is in a pocket. So the recorder asks to follow
     * the exact device (AudioDeviceInfo id) the call app captures from - not
     * a device type, since two headsets can share one - and then CHECKS that
     * its own routed device became that id. A device that does not take the
     * route after {@link #CONFIRM_CHECKS} checks is given up for this
     * connection, rather than asked for again and again.
     *
     * One per recording microphone. Not thread-safe; the capture's lock.
     */
    static final class MicFollow {
        static final int NONE = -1;
        static final int RESET = 0;
        static final int CONFIRM_CHECKS = 3;

        private int followedId;
        private int unconfirmed;
        private int givenUpId;

        /**
         * [callId]/[callType]: the call app's input device, id <= 0 if
         * unknown. [ourRoutedId]: where our microphone is actually routed, <=
         * 0 if unknown. Returns a device id to prefer, {@link #RESET} to drop
         * the preference, or {@link #NONE}.
         */
        int decide(int callId, int callType, int ourRoutedId) {
            if (callId <= 0) {
                return NONE;
            }
            if (headsetMic(callType)) {
                if (callId == givenUpId) {
                    return NONE;
                }
                if (ourRoutedId == callId) {
                    unconfirmed = 0;
                    return NONE;
                }
                if (followedId == callId) {
                    if (++unconfirmed >= CONFIRM_CHECKS) {
                        givenUpId = callId;
                        followedId = 0;
                        unconfirmed = 0;
                        return RESET;
                    }
                    return NONE;
                }
                followedId = callId;
                unconfirmed = 0;
                return callId;
            }
            // The call is back on a non-headset input: forget any device that
            // refused, so a reconnect is tried afresh.
            givenUpId = 0;
            if (followedId != 0) {
                followedId = 0;
                unconfirmed = 0;
                return RESET;
            }
            return NONE;
        }

        /** Android refused the preference outright. */
        void refused() {
            givenUpId = followedId;
            followedId = 0;
            unconfirmed = 0;
        }

        /** A new AudioRecord: it carries no preference. Refusals stand. */
        void reopened() {
            followedId = 0;
            unconfirmed = 0;
        }

        int followed() {
            return followedId;
        }
    }
}
