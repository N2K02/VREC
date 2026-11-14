package com.vrec.app.shell;

/**
 * The phone's call state, as telephony reports it, made into the start and
 * end of each call and which way it went. Plain Java, nothing of Android, so
 * it can be tested (shellserver/tests: CallStatesTest); Main.CallWatch feeds
 * it the events and does what it says through {@link Effects}.
 *
 * AN IDLE IS A CLAIM, NOT A FACT.
 *
 * Ending a recording is destructive - the app closes the socket, the encoder
 * dies of ECONNRESET mid-frame, and whatever was left of the call is gone - so
 * it must not be done on one unconfirmed signal.
 *
 * Measured on this phone: a twenty-second outgoing call recorded six seconds.
 * The daemon's own log shows off-hook, the client connecting on VOICE_CALL,
 * and then "idle" arriving while the call was still up, followed immediately
 * by the encoder's ECONNRESET. The call was not over; the state said it was.
 *
 * So an idle before any off-hook is ignored outright - that one is just the
 * current state being handed over at registration, which used to fire a
 * CALL_ENDED at every daemon start. And a real idle is confirmed against the
 * AUDIO MODE rather than the call state - see {@link #endCheck}.
 */
final class CallStates {

    /** What the call states do to the world. */
    interface Effects {
        /** A call went off-hook: record it (or ask), as [incoming] or not. */
        void callStarted(boolean incoming);

        /** The call is over. */
        void callEnded();

        /** Run {@link #endCheck} once, [delayMs] from now. */
        void scheduleEndCheck(long delayMs);

        /** The scheduled end check is not to run. */
        void cancelEndCheck();

        /** What the audio says about the phone call after its IDLE: one of
         *  {@link #AUDIO_UP}, {@link #AUDIO_DOWN}, {@link #AUDIO_UNKNOWN},
         *  {@link #AUDIO_MESSENGER}. See {@link #phoneCallAudio}. */
        int phoneCallAudio();

        /** An idle came, and the call is still up after it: whatever else
         *  took that idle for the end can think again. */
        void callGoesOn();

        void log(String message);
    }

    /** How long after an idle the end is checked, and checked again: long
     *  enough to outlast a momentary idle, short enough not to leave a
     *  finished call recording into silence. */
    static final long END_CHECK_MS = 2000L;

    /** The phone call still holds the audio: keep recording. */
    static final int AUDIO_UP = 1;
    /** The audio left the call: it is over. */
    static final int AUDIO_DOWN = 0;
    /** The audio mode could not be read. A failed read is not an end. */
    static final int AUDIO_UNKNOWN = -1;
    /** A messenger app, not the phone call, now holds the communication
     *  mode, and telecom says no phone call is up. Probably over - but it
     *  ends a recording, so it must say so on consecutive checks. */
    static final int AUDIO_MESSENGER = 2;

    /** Unreadable checks in a row before the call is taken as over: the
     *  recording must not run on forever on a mode that cannot be read, nor
     *  stop on the first failed read. 3 x 2 s. */
    static final int MAX_UNKNOWN_CHECKS = 3;
    /** Messenger-held checks in a row before the phone call is taken as over. */
    static final int MESSENGER_CHECKS = 2;

    /** AudioManager.MODE_IN_CALL / MODE_IN_COMMUNICATION. */
    static final int MODE_IN_CALL = 2;
    static final int MODE_IN_COMMUNICATION = 3;

    /**
     * What the audio says about a phone call whose IDLE arrived.
     *
     * [mode] is the audio mode, or null if it could not be read.
     * [messengerInCall]: a third-party app (not the phone process or dialer)
     * is playing and capturing call audio. [telecomInCall]: telecom's own
     * answer, null if unknown.
     *
     * IN_CALL is telecom's and means the phone call goes on. IN_COMMUNICATION
     * used to always count too - some dialers and Wi-Fi calling use it - but a
     * messenger sets it for its own call, and a WhatsApp call started right
     * after a phone call kept the PHONE recording running through it. So it
     * hands over only when a messenger is in a call AND telecom positively
     * says no phone call is up; any doubt keeps the phone recording.
     */
    static int phoneCallAudio(Integer mode, boolean messengerInCall, Boolean telecomInCall) {
        if (mode == null) return AUDIO_UNKNOWN;
        if (mode == MODE_IN_CALL) return AUDIO_UP;
        if (mode == MODE_IN_COMMUNICATION) {
            return messengerInCall && Boolean.FALSE.equals(telecomInCall) ? AUDIO_MESSENGER : AUDIO_UP;
        }
        return AUDIO_DOWN;
    }

    private final Effects effects;
    /** The call starting now rang first: it is incoming. */
    private boolean sawRinging;
    private boolean inCall;
    private boolean endCheckPending;
    private int unknownChecks;
    private int messengerChecks;

    CallStates(Effects effects) {
        this.effects = effects;
    }

    void ringing() {
        sawRinging = true;
        effects.log("callwatch: ringing");
    }

    void offHook() {
        cancelEndCheck();
        inCall = true;
        effects.log("callwatch: off-hook (" + (sawRinging ? "incoming" : "outgoing") + ")");
        effects.callStarted(sawRinging);
    }

    void idle() {
        if (!inCall) {
            if (sawRinging) {
                // It rang and was never answered - missed, or declined.
                // Forget the ring, or the next OUTGOING call is labelled
                // incoming: the flag only ever cleared when a call ended.
                sawRinging = false;
                effects.log("callwatch: ringing ended without a call");
            } else {
                effects.log("callwatch: idle before any call, ignoring");
            }
            return;
        }
        // The ring was this call's, and this is its end: forget it now, not
        // when the end is confirmed. The next call can ring inside those two
        // seconds - and the confirmation then cleared ITS ring, and an
        // incoming call was recorded as outgoing (emulator, 2026-10-21: the
        // saver then took the call before for it).
        sawRinging = false;
        cancelEndCheck();
        unknownChecks = 0;
        messengerChecks = 0;
        endCheckPending = true;
        effects.scheduleEndCheck(END_CHECK_MS);
    }

    /**
     * CONFIRM THE END AGAINST THE AUDIO MODE, AND POLL - DO NOT TRUST ONE
     * IDLE.
     *
     * Measured on this phone: with some dialers (Google Phone here) telephony
     * CALL_STATE goes IDLE partway through a live call and STAYS idle - a 37s
     * call reported idle at 11s, a 30s call at 6s - so a single re-read of
     * getCallState() confirms an end that has not happened, and because the
     * state never leaves idle there is no later edge to correct it. The
     * recording was cut ~25s short, twice.
     *
     * Telecom, though, holds the audio mode at MODE_IN_CALL for the real
     * length of the call: its dumpsys setMode history lines up to the second
     * with the true spans. So the audio mode is the signal to trust, and
     * because telephony gives no second idle edge it is POLLED here rather
     * than checked once - re-armed until telecom actually leaves in-call, then
     * ended. A momentary idle with the audio still in-call simply keeps the
     * recording running, which is the whole point.
     */
    void endCheck() {
        if (!endCheckPending) {
            return;
        }
        endCheckPending = false;
        int audio = effects.phoneCallAudio();
        boolean over;
        String why;
        switch (audio) {
            case AUDIO_UP:
                unknownChecks = 0;
                messengerChecks = 0;
                over = false;
                why = null;
                break;
            case AUDIO_UNKNOWN:
                messengerChecks = 0;
                over = ++unknownChecks >= MAX_UNKNOWN_CHECKS;
                why = "audio mode unreadable " + unknownChecks + " times";
                break;
            case AUDIO_MESSENGER:
                unknownChecks = 0;
                over = ++messengerChecks >= MESSENGER_CHECKS;
                why = "a messenger call holds the audio and telecom has no call";
                break;
            default:
                over = true;
                why = "audio mode left in-call";
                break;
        }
        if (!over) {
            // Still up, or not yet sure. Telephony won't tell us again; keep
            // watching the audio mode, which will.
            endCheckPending = true;
            effects.scheduleEndCheck(END_CHECK_MS);
            if (audio == AUDIO_UP) {
                effects.callGoesOn();
            }
            return;
        }
        inCall = false;
        unknownChecks = 0;
        messengerChecks = 0;
        effects.log("callwatch: end confirmed (" + why + ")");
        effects.callEnded();
    }

    /** A phone call is up: off-hook, its end not yet confirmed. */
    boolean inCall() {
        return inCall;
    }

    /** The call that is up rang first: it is incoming. */
    boolean rang() {
        return sawRinging;
    }

    private void cancelEndCheck() {
        if (endCheckPending) {
            endCheckPending = false;
            effects.cancelEndCheck();
        }
    }
}
