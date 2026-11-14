package com.vrec.app.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Whole-lifecycle regression: the real MultiSimCalls feeding the real
 * CallStates, with the real hand-over rules, against a model recorder that
 * allows one recording at a time (as Main does). Pure Java, no device.
 */
public final class CallLifecycleTest {
    private static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    private static final int IDLE = MultiSimCalls.IDLE, RING = MultiSimCalls.RINGING, OFF = MultiSimCalls.OFFHOOK;

    /** The daemon, reduced to what decides which recording runs. */
    private static final class Phone implements CallStates.Effects {
        final MultiSimCalls sims = new MultiSimCalls();
        final CallStates states = new CallStates(this);
        /** Each recording started: "sim:in", "sim:out" or "voip:<app>". */
        final List<String> recordings = new ArrayList<>();
        int finalized;
        String recording;
        /** The messenger call the VoIP watch sees up. */
        String messengerUp;
        int audio = CallStates.AUDIO_DOWN;
        boolean endCheckPending;

        void sim(int source, int state) {
            int c = sims.update(source, state);
            if (c == MultiSimCalls.NO_CHANGE) return;
            if (c == RING) states.ringing();
            else if (c == OFF) states.offHook();
            else states.idle();
        }

        /** The end check timer fires. */
        void tick() {
            if (endCheckPending) {
                endCheckPending = false;
                states.endCheck();
            }
        }

        void messengerStarts(String app) {
            messengerUp = app;
            start("voip:" + app);
        }

        void messengerEnds() {
            messengerUp = null;
            if (recording == null || !recording.startsWith("voip:")) return;
            stop();
            if (CallSignals.phoneAfterMessengerEnd(true, states.inCall())) {
                start(states.rang() ? "sim:in" : "sim:out");
            }
        }

        private void start(String what) {
            if (recording != null) return; // one recording at a time
            recording = what;
            recordings.add(what);
        }

        private void stop() {
            if (recording != null) {
                recording = null;
                finalized++;
            }
        }

        @Override public void callStarted(boolean incoming) { start(incoming ? "sim:in" : "sim:out"); }
        @Override public void callEnded() {
            if (recording != null && recording.startsWith("voip:")) return; // not ours
            stop();
            String app = CallSignals.messengerAfterPhoneEnd(null, messengerUp);
            if (app != null) start("voip:" + app);
        }
        @Override public void scheduleEndCheck(long delayMs) { endCheckPending = true; }
        @Override public void cancelEndCheck() { endCheckPending = false; }
        @Override public int phoneCallAudio() { return audio; }
        @Override public void callGoesOn() { }
        @Override public void log(String message) { }
    }

    private static Phone dualSim() {
        Phone p = new Phone();
        p.sim(MultiSimCalls.DEFAULT_SOURCE, IDLE);
        p.sim(1, IDLE);
        p.sim(2, IDLE);
        p.sims.setDefaultTrusted(false);
        return p;
    }

    static void incomingOnSim2RecordedOnce() {
        Phone p = dualSim();
        p.sim(2, RING);
        p.sim(2, OFF);
        p.audio = CallStates.AUDIO_UP;
        p.sim(2, IDLE);           // premature idle (Google Phone)
        p.tick(); p.tick();
        check(p.recording != null, "premature idle must not cut the SIM2 call");
        p.audio = CallStates.AUDIO_DOWN;
        p.tick();
        check(p.recordings.size() == 1 && p.recordings.get(0).equals("sim:in") && p.finalized == 1,
                "one incoming recording, finalized once: " + p.recordings);
    }

    static void outgoingOnSim1WithLateDefaultDuplicate() {
        Phone p = dualSim();
        p.sim(1, OFF);
        p.audio = CallStates.AUDIO_DOWN;
        p.sim(1, IDLE);
        p.sim(MultiSimCalls.DEFAULT_SOURCE, OFF); // stale duplicate arrives late
        p.tick();
        check(p.recordings.size() == 1 && p.recordings.get(0).equals("sim:out") && p.finalized == 1,
                "late duplicate must neither keep nor restart the call: " + p.recordings);
    }

    static void phoneCallThenWhatsAppIsHandedOver() {
        Phone p = dualSim();
        p.sim(1, OFF);
        p.sim(1, IDLE);
        p.messengerStarts("com.whatsapp");   // turned away: a phone call is still up
        check(p.recordings.size() == 1, "no second recording while the phone call is up");
        p.audio = CallStates.AUDIO_MESSENGER;
        p.tick();
        check("sim:out".equals(p.recording), "one messenger check is not enough to end the phone call");
        p.tick();
        check("voip:com.whatsapp".equals(p.recording) && p.finalized == 1,
                "after consecutive checks, the WhatsApp call is recorded: " + p.recordings);
    }

    static void whatsAppThenPhoneCallIsHandedOver() {
        Phone p = dualSim();
        p.messengerStarts("com.whatsapp");
        p.sim(2, RING);
        p.sim(2, OFF);                      // answered; messenger call ends
        check(p.recordings.size() == 1, "phone call not recorded while the messenger call is");
        p.messengerEnds();
        check("sim:in".equals(p.recording), "the phone call takes over: " + p.recordings);
    }

    static void unreadableModeDuringHandOverKeepsPhoneRecording() {
        Phone p = dualSim();
        p.sim(1, OFF);
        p.sim(1, IDLE);
        p.messengerUp = "org.telegram.messenger";
        p.audio = CallStates.AUDIO_MESSENGER;
        p.tick();
        p.audio = CallStates.AUDIO_UNKNOWN;
        p.tick();
        p.audio = CallStates.AUDIO_MESSENGER;
        p.tick();
        check("sim:out".equals(p.recording), "an unknown read breaks the messenger streak");
    }

    static void callWaitingAcrossSimsIsOneRecording() {
        Phone p = dualSim();
        p.sim(1, OFF);
        p.sim(2, RING);
        p.sim(2, OFF);
        p.sim(1, IDLE);
        p.tick();
        check(p.recordings.size() == 1 && p.recording != null, "still one recording");
        p.sim(2, IDLE);
        p.audio = CallStates.AUDIO_DOWN;
        p.tick();
        check(p.recording == null && p.finalized == 1, "ends once the last call ends");
    }

    static void concurrentSimCallbacksStayConsistent() throws InterruptedException {
        MultiSimCalls m = new MultiSimCalls();
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int sub = t + 1;
            new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < 2000; i++) {
                        m.update(sub, i % 3 == 0 ? RING : OFF);
                        m.update(sub, IDLE);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        done.await();
        check(m.combined() == IDLE && m.sources() == threads, "every SIM ended idle, combined must be idle");
    }

    public static void main(String[] args) throws Exception {
        incomingOnSim2RecordedOnce();
        outgoingOnSim1WithLateDefaultDuplicate();
        phoneCallThenWhatsAppIsHandedOver();
        whatsAppThenPhoneCallIsHandedOver();
        unreadableModeDuringHandOverKeepsPhoneRecording();
        callWaitingAcrossSimsIsOneRecording();
        concurrentSimCallbacksStayConsistent();
        System.out.println("CallLifecycleTest: OK");
    }
}
