package com.vrec.app.shell;

/**
 * Decisions about which call owns the audio, kept free of Android so they can
 * be tested (CallSignalsTest).
 */
final class CallSignals {

    private CallSignals() {
    }

    /** AudioManager.MODE_IN_CALL / MODE_IN_COMMUNICATION. */
    static final int MODE_IN_CALL = 2;
    static final int MODE_IN_COMMUNICATION = 3;

    /**
     * Whether a phone call whose IDLE arrived is still up, by the audio mode.
     *
     * IN_CALL is telecom's and means the phone call goes on. IN_COMMUNICATION
     * used to count too - some dialers and Wi-Fi calling use it - but a
     * messenger app sets it for its own call, and a WhatsApp call started
     * right after a phone call then kept the PHONE recording running through
     * the whole messenger call (and the messenger call unrecorded). So it
     * counts only while no identifiable third-party app is the one in a call.
     */
    static boolean phoneCallAudioUp(int mode, boolean messengerInCall) {
        if (mode == MODE_IN_CALL) return true;
        if (mode == MODE_IN_COMMUNICATION) return !messengerInCall;
        return false;
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
     * Which input the recording microphone should prefer: the one the call app
     * itself captures from, when that is a headset and ours is not on it.
     * Bluetooth or a wired headset taking the call's input mid-call left the
     * recorder on the phone's own microphone, which hears almost nothing of
     * the user once the phone is in a pocket. Returns the device type to
     * follow, 0 to go back to the system's choice, or -1 to leave it alone.
     *
     * [followed] is the type already preferred (0 for none).
     */
    static int micToFollow(int callAppInput, int ourInput, int followed) {
        if (callAppInput <= 0) {
            // The call app's input is not known right now: change nothing.
            return -1;
        }
        if (headsetMic(callAppInput)) {
            return callAppInput == ourInput || callAppInput == followed ? -1 : callAppInput;
        }
        // The headset is gone (or the app is back on the phone): stop
        // preferring a device that no longer carries the call.
        return followed != 0 ? 0 : -1;
    }
}
