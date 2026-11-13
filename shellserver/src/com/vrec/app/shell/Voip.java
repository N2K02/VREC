package com.vrec.app.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Messenger calls - WhatsApp, Viber, Telegram, Signal, any app that calls over
 * the internet - recorded by this process, to a file, both sides, the way phone
 * calls are. Only for the apps the user switched on (the app sends the list),
 * and nothing at all while the list is empty.
 *
 * THE OTHER PERSON: an audio policy copies every player using
 * USAGE_VOICE_COMMUNICATION into a stream this process reads, while the call
 * still plays in the user's ear (ROUTE_FLAG_LOOP_BACK_RENDER). Capturing that
 * usage needs CAPTURE_VOICE_COMMUNICATION_OUTPUT, which the shell holds, and
 * voiceCommunicationCaptureAllowed on the rule - set BEFORE build(); scrcpy's
 * playback capture sets it after, where it does nothing. The policy is
 * registered when the list stops being empty, long before any call: Android
 * routes a stream when the app creates it, so a policy registered mid-call
 * copies nothing. An app that marks its audio ALLOW_CAPTURE_BY_NONE is not
 * copied whatever this holds - Android honouring the app, and nothing here
 * tries to get round it.
 *
 * THE USER: the microphone, opened NOT privacy-sensitive, so it never takes
 * the input from the call app - a recorder that leaves the other person unable
 * to hear you is worse than none. As a guard on top, if Android ever reports
 * the call app's own capture silenced while this records, the microphone here
 * is dropped at once and the rest of the call has the other side only.
 *
 * Measured on an Honor BKQ-N49, MagicOS 11.0.0.120 / Android 17, 2026-10-20,
 * with Viber (first with a separate probe, then this), Telegram and WhatsApp:
 * both sides recorded clearly, both directions, and the other person heard
 * the user throughout every call.
 *
 * WHEN IS IT A CALL: the audio mode at MODE_IN_COMMUNICATION, which every VoIP
 * app sets and TelephonyCallback never reports - AND an app on the list both
 * playing voice-call audio and capturing at once. Mode alone also comes from
 * a voice message played at the ear; capturing is what a call has and a
 * message does not. Its end is the mode leaving, confirmed after a moment,
 * because apps flip it while switching the route.
 */
final class Voip {

    interface Listener {
        void onVoipCallStart(String app);

        void onVoipCallEnd();
    }

    private Voip() {
    }

    private static final int SAMPLE_RATE = AudioConfig.SAMPLE_RATE;
    /** How often a call is looked for, and its end, while one may be up. */
    private static final long POLL_MS = 500L;
    /** The mode has to stay out of a call this long for the call to be over. */
    private static final long END_CONFIRM_MS = 1500L;

    /** The packages whose calls are recorded. Empty: nothing is watched. */
    private static volatile Set<String> apps = Collections.emptySet();
    private static Policy policy;
    private static Watch watch;
    /** What configure was last given, so a recording's end can disarm. */
    private static Handler wiredHandler;
    private static AudioManager wiredAudio;
    private static Listener wiredListener;
    /** The capture of the call being recorded, for the guard. */
    private static volatile Capture current;

    static Set<String> apps() {
        return apps;
    }

    static boolean armed() {
        return policy != null;
    }

    /**
     * Independent evidence that one configured messenger is BOTH playing
     * voice-communication audio and capturing. A Telecom state alone is
     * insufficient. Ambiguous simultaneous apps must never be guessed.
     */
    static String uniquelyCallingApp(AudioManager audio) {
        if (audio == null || policy == null) return null;
        String only = null;
        for (int uid : callingUids(audio, false)) {
            String[] packages = FakeContext.get().getPackageManager().getPackagesForUid(uid);
            if (packages == null) continue;
            for (String pkg : packages) {
                if (!apps.contains(pkg)) continue;
                if (only != null && !only.equals(pkg)) return null;
                only = pkg;
            }
        }
        return only;
    }

    /** "com.whatsapp,com.viber.voip" - the environment's form and the command's. */
    static Set<String> parse(String list) {
        Set<String> out = new LinkedHashSet<>();
        if (list != null) {
            for (String part : list.split(",")) {
                String app = part.trim();
                // Package names only: letters, digits, dots and underscores.
                if (app.matches("[A-Za-z0-9_.]{1,200}")) {
                    out.add(app);
                }
            }
        }
        return out;
    }

    /**
     * Take the list the app sent and act on it: arm the policy and start
     * watching when it gains its first app, disarm when it loses its last.
     * Never mid-recording: the copy feeding it would go with the policy.
     */
    static synchronized void configure(Set<String> newApps, Handler handler, AudioManager audio, Listener listener) {
        wiredHandler = handler;
        wiredAudio = audio;
        wiredListener = listener;
        apps = Collections.unmodifiableSet(new LinkedHashSet<>(newApps));
        Ln.i("voip: apps = " + (apps.isEmpty() ? "none" : String.join(",", apps)));
        if (!apps.isEmpty()) {
            if (policy == null) {
                try {
                    policy = Policy.register();
                } catch (Throwable t) {
                    Ln.e("voip: could not register the audio policy; messenger calls will not be recorded", t);
                }
            }
            if (watch == null && policy != null) {
                Watch started = new Watch(handler, audio, listener);
                watch = started;
                handler.post(started::start);
            }
        } else if (current == null) {
            if (watch != null) {
                Watch gone = watch;
                handler.removeCallbacks(gone);
                watch = null;
                // A messenger call it was following - one not being recorded,
                // or there would be a capture - still has to end, or the daemon
                // would go on believing it is in one and refuse every phone
                // call after it.
                handler.post(gone::stop);
            }
            if (policy != null) {
                policy.unregister();
                policy = null;
            }
        }
    }

    /** A recording just ended: if the list emptied while it ran, disarm now. */
    private static void recordingEnded() {
        Handler handler = wiredHandler;
        if (handler != null && apps.isEmpty()) {
            handler.post(() -> configure(apps, wiredHandler, wiredAudio, wiredListener));
        }
    }

    /** A capture for the call that just started, or null if not armed. */
    static AudioCapture newCapture() {
        Policy p = policy;
        return p == null ? null : new Capture(p);
    }

    // --- the audio policy --------------------------------------------------------

    static final class Policy {
        private final Object policy;
        private final Object mix;
        private final Class<?> policyClass;
        private final Class<?> mixClass;

        private Policy(Object policy, Object mix, Class<?> policyClass, Class<?> mixClass) {
            this.policy = policy;
            this.mix = mix;
            this.policyClass = policyClass;
            this.mixClass = mixClass;
        }

        @SuppressLint("PrivateApi")
        static Policy register() throws Exception {
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
            // Android 13 and later only; before it, the role follows from the
            // first rule - a usage rule makes a mix of players, which is what
            // this is. On Android 12 asking for the method threw before Android
            // was even asked.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ruleBuilderClass.getMethod("setTargetMixRole", int.class)
                        .invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null));
            }
            AudioAttributes voice = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build();
            ruleBuilderClass.getMethod("addMixRule", int.class, Object.class)
                    .invoke(ruleBuilder, ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null), voice);
            ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(ruleBuilder, true);
            Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
            mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, AudioConfig.createAudioFormat());
            mixBuilderClass.getMethod("setRouteFlags", int.class)
                    .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null));
            Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(FakeContext.get());
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
            Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

            Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
            register.setAccessible(true);
            int result = (int) register.invoke(null, policy);
            if (result != 0) {
                // Android's reason is in its own log (AudioService). Where the
                // shell lacks CAPTURE_VOICE_COMMUNICATION_OUTPUT - stock Android
                // before 14 - this is always the answer; the app checks for that
                // permission first (VoipApps.supported) and does not ask.
                throw new IllegalStateException("registerAudioPolicyStatic returned " + result
                        + " - Android refused to let the shell copy call audio");
            }
            Ln.i("voip: audio policy registered, copying voice-call playback");
            return new Policy(policy, mix, policyClass, mixClass);
        }

        AudioRecord sink() throws Exception {
            return (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
        }

        void unregister() {
            try {
                Method m = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass);
                m.setAccessible(true);
                m.invoke(null, policy);
                Ln.i("voip: audio policy unregistered");
            } catch (Throwable t) {
                Ln.w("voip: could not unregister the audio policy: " + t);
            }
        }
    }

    // --- spotting a call -------------------------------------------------------------

    /**
     * Spots a messenger call, and its end. Android says when the audio mode
     * changes, and only while it is "communication" - or a call is still being
     * followed - is anything polled. Asking for the mode twice a second, as
     * this did first, was a log line every time in system_server
     * ("AS.AudioService: getMode mode = 0") and another in this process on
     * MagicOS: on an Honor (2026-10-20) the daemon's own lines were gone from
     * the 256 KiB log buffer within a minute, before anyone could read why a
     * call went wrong. Polling stays the fallback where the callback cannot be
     * had. Everything here runs on [handler]'s thread.
     */
    static final class Watch implements Runnable {
        private final Handler handler;
        private final AudioManager audio;
        private final Listener listener;
        /** The mode as Android last said; read once at the start. */
        private int mode;
        /** Null when Android would not take it: then the mode is polled. */
        private AudioManager.OnModeChangedListener modeChanges;
        /** A tick is posted, so a mode change does not start a second loop. */
        private boolean ticking;
        private String active;
        /** The uid of [active]'s app, to ask whether it is still in its call. */
        private int activeUid = -1;
        /** The mode left "communication" while the app still talked; said once. */
        private boolean heldOver;
        private long leftAt;
        /** The app last reported as calling but not switched on, so it is
         *  said once per call, not twice a second. */
        private String noted;

        Watch(Handler handler, AudioManager audio, Listener listener) {
            this.handler = handler;
            this.audio = audio;
            this.listener = listener;
        }

        void start() {
            try {
                AudioManager.OnModeChangedListener changes = changed -> {
                    mode = changed;
                    wake();
                };
                audio.addOnModeChangedListener(handler::post, changes);
                modeChanges = changes;
                Ln.i("voip: watching for a call (told of audio mode changes)");
            } catch (Throwable t) {
                Ln.w("voip: no audio mode callback; polling the mode instead: " + t);
            }
            // After registering, not before: a change in between would
            // otherwise never be heard, and nothing would poll for it.
            mode = audio.getMode();
            wake();
        }

        /** Stopped watching: the call is over as far as this goes. */
        void stop() {
            if (modeChanges != null) {
                try {
                    audio.removeOnModeChangedListener(modeChanges);
                } catch (Throwable t) {
                    Ln.w("voip: could not drop the audio mode callback: " + t);
                }
                modeChanges = null;
            }
            endNow();
        }

        private void wake() {
            if (!ticking && watch == this) {
                ticking = true;
                handler.post(this);
            }
        }

        /** Stopped watching mid-call: the call is over as far as this goes. */
        void endNow() {
            if (active != null) {
                Ln.i("voip: no longer watching; ending the call in " + active);
                active = null;
                activeUid = -1;
                heldOver = false;
                listener.onVoipCallEnd();
            }
        }

        @Override
        public void run() {
            ticking = false;
            try {
                tick();
            } catch (Throwable t) {
                Ln.w("voip: watch tick failed: " + t);
            }
            boolean callPossible = active != null || mode == AudioManager.MODE_IN_COMMUNICATION;
            if (watch == this && (modeChanges == null || callPossible)) {
                ticking = true;
                handler.postDelayed(this, POLL_MS);
            }
        }

        private void tick() {
            if (modeChanges == null) {
                mode = audio.getMode();
            }
            boolean inCommunication = mode == AudioManager.MODE_IN_COMMUNICATION;
            if (active == null) {
                if (!inCommunication) {
                    noted = null;
                    return;
                }
                String app = callingApp();
                if (app != null) {
                    active = app;
                    leftAt = 0;
                    Ln.i("voip: call in " + app);
                    listener.onVoipCallStart(app);
                }
                return;
            }
            guard(active);
            Capture recording = current;
            if (recording != null) recording.checkMic();
            if (inCommunication) {
                leftAt = 0;
                heldOver = false;
                return;
            }
            boolean stillCalling = activeUid >= 0 && callingUids(audio, false).contains(activeUid);
            if (Checks.messengerCallGoesOn(mode, stillCalling)) {
                // A phone call ringing over it took the mode; the messenger
                // call itself goes on, and so does its recording.
                leftAt = 0;
                if (!heldOver) {
                    heldOver = true;
                    Ln.i("voip: the audio mode left the call (" + mode + ") but " + active
                            + " still plays and captures call audio - recording on");
                }
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (leftAt == 0) {
                leftAt = now;
            } else if (now - leftAt >= END_CONFIRM_MS) {
                Ln.i("voip: call in " + active + " ended (audio mode " + mode + ")");
                active = null;
                activeUid = -1;
                heldOver = false;
                leftAt = 0;
                listener.onVoipCallEnd();
            }
        }

        /** An app on the list that is playing voice-call audio and capturing:
         *  a call, not a voice message. */
        private String callingApp() {
            String candidate = null;
            int candidateUid = -1;
            for (int uid : callingUids(audio, false)) {
                String[] packages = FakeContext.get().getPackageManager().getPackagesForUid(uid);
                if (packages == null) continue;
                for (String pkg : packages) {
                    if (!apps.contains(pkg)) continue;
                    if (candidate != null && (!candidate.equals(pkg) || candidateUid != uid)) {
                        // Never attribute a simultaneous call to an arbitrary app.
                        Ln.w("voip: multiple eligible calling apps; waiting for an unambiguous call");
                        return null;
                    }
                    candidate = pkg;
                    candidateUid = uid;
                }
                if (candidate == null) {
                    String who = String.join(",", packages);
                    if (!who.equals(noted)) {
                        noted = who;
                        Ln.i("voip: a call in " + who + ", which is not switched on");
                    }
                }
            }
            if (candidate != null) activeUid = candidateUid;
            return candidate;
        }

        /** The call app's own capture silenced while we record: give the
         *  microphone back at once. */
        private void guard(String app) {
            Capture capture = current;
            if (capture == null || !capture.hasMic()) {
                return;
            }
            for (AudioRecordingConfiguration r : audio.getActiveRecordingConfigurations()) {
                Integer uid = uidOf(r);
                if (uid == null || uid == android.os.Process.myUid() || !r.isClientSilenced()) {
                    continue;
                }
                String[] packages = FakeContext.get().getPackageManager().getPackagesForUid(uid);
                if (packages != null) {
                    for (String pkg : packages) {
                        if (pkg.equals(app)) {
                            Ln.w("voip: " + app + "'s microphone was silenced while recording - dropping ours");
                            capture.dropMic();
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * The apps in a call right now - any app, switched on here or not: each
     * playing voice-call audio and capturing at once, which a call does and a
     * voice message does not. This process too only when [withThisProcess].
     */
    static Set<Integer> callingUids(AudioManager audio, boolean withThisProcess) {
        Set<Integer> playing = new HashSet<>();
        for (AudioPlaybackConfiguration p : audio.getActivePlaybackConfigurations()) {
            if (p.getAudioAttributes().getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                Integer uid = uidOf(p);
                if (uid != null) {
                    playing.add(uid);
                }
            }
        }
        Set<Integer> calling = new HashSet<>();
        for (AudioRecordingConfiguration r : audio.getActiveRecordingConfigurations()) {
            Integer uid = uidOf(r);
            if (uid != null && (withThisProcess || uid != android.os.Process.myUid()) && playing.contains(uid)) {
                calling.add(uid);
            }
        }
        return calling;
    }

    /** getClientUid is a system API: by reflection, or null. */
    private static Integer uidOf(Object config) {
        try {
            return (Integer) config.getClass().getMethod("getClientUid").invoke(config);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- the capture -----------------------------------------------------------------

    /**
     * Both sides as one stereo stream, the shape every other recording has:
     * the other person on the left, the user on the right.
     *
     * The monotonic clock paces both sides. A stopped or stalled microphone
     * must never stall the playback channel. Both queues are bounded and old
     * samples are dropped on route changes so delayed audio cannot drift apart.
     */
    static final class Capture implements AudioCapture {
        private static final int FRAMES = 960;              // 20 ms
        private static final int MAX_AHEAD = SAMPLE_RATE / 5; // 200 ms
        /** Longer than one 40 ms microphone read plus scheduling jitter. */
        private static final long MIC_GRACE_US = 60_000L;
        private static final PcmRing.Clock CLOCK = new PcmRing.Clock() {
            @Override public long nowUs() { return SystemClock.elapsedRealtimeNanos() / 1000; }
            @Override public void sleepUs(long us) throws InterruptedException {
                Thread.sleep(us / 1000, (int) (us % 1000) * 1000);
            }
        };

        private final Policy policy;
        private AudioRecord far;
        private volatile AudioRecord near;
        private Pump farPump;
        private volatile Pump nearPump;
        private final VoipMicHealth micHealth = new VoipMicHealth();
        private int micDeviceType = -1;
        private boolean micBlocked;
        private volatile boolean running;
        private long framesOut;
        private long startUs;
        /** One read's worth of each side, reused: fifty reads a second. */
        private final short[] mine = new short[FRAMES];
        private final short[] theirs = new short[FRAMES * 2];

        Capture(Policy policy) {
            this.policy = policy;
        }

        boolean hasMic() {
            return near != null;
        }

        @Override
        public void checkCompatibility() throws AudioCaptureException {
            if (policy == null) {
                throw new AudioCaptureException();
            }
        }

        @Override
        public synchronized void start() throws AudioCaptureException {
            try {
                far = policy.sink();
                far.startRecording();
                farPump = new Pump("far", far, 2);
                farPump.start();
            } catch (Throwable t) {
                // A partially opened capture is not a valid recording session.
                // Release it before reporting failure so the next call can retry.
                if (farPump != null) {
                    farPump.close();
                } else if (far != null) {
                    try { far.stop(); } catch (Throwable ignored) { }
                    try { far.release(); } catch (Throwable ignored) { }
                }
                far = null;
                Ln.e("voip: could not read the call's audio", t);
                throw new AudioCaptureException();
            }
            running = true;
            openNextMic();
            startUs = SystemClock.elapsedRealtimeNanos() / 1000;
            current = this;
            Ln.i("voip: recording both sides (" + (near != null ? "mic on" : "mic off") + ")");
        }

        @SuppressLint({"WrongConstant", "MissingPermission"})
        private static AudioRecord mic(int source) {
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            return new AudioRecord.Builder()
                    .setContext(FakeContext.get())
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setPrivacySensitive(false)
                    .setBufferSizeInBytes(8 * AudioRecord.getMinBufferSize(
                            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
                    .build();
        }

        private synchronized void openNextMic() {
            int[] sources = { MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION };
            while (running && !micBlocked) {
                int index = micHealth.next(SystemClock.elapsedRealtime());
                if (index < 0) return;
                AudioRecord mic = null;
                try {
                    mic = mic(sources[index]);
                    mic.startRecording();
                    if (mic.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                        throw new IllegalStateException("microphone did not start");
                    Pump pump = new Pump("near", mic, 1);
                    pump.health = micHealth;
                    pump.start();
                    micDeviceType = -1;
                    nearPump = pump;
                    near = mic;
                    Ln.i("voip: " + micHealth.summary());
                    return;
                } catch (Throwable t) {
                    if (mic != null) {
                        try { mic.stop(); } catch (Throwable ignored) { }
                        try { mic.release(); } catch (Throwable ignored) { }
                    }
                    micHealth.failed();
                    Ln.w("voip: microphone open failed: " + micHealth.summary() + " " + t);
                }
            }
        }

        /** Runs on the watcher thread, never the encoder or reader thread. */
        synchronized void checkMic() {
            if (!running || micBlocked || near == null || nearPump == null) return;
            Boolean silenced = null;
            try {
                AudioRecordingConfiguration config = near.getActiveRecordingConfiguration();
                if (config != null) silenced = config.isClientSilenced();
                android.media.AudioDeviceInfo device = near.getRoutedDevice();
                micDeviceType = device == null ? -1 : device.getType();
            } catch (RuntimeException ignored) { /* state unknown, samples remain evidence */ }
            if (micHealth.inspect(SystemClock.elapsedRealtime(), silenced, nearPump.running)) {
                Ln.w("voip: recovering microphone: " + micHealth.summary());
                if (closeMic()) openNextMic();
                else { micBlocked = true; micHealth.readerStuck(); }
            }
        }

        synchronized String micDiagnostic() {
            return micHealth.summary() + ";deviceType=" + micDeviceType;
        }

        private boolean closeMic() {
            near = null;
            Pump pump = nearPump;
            nearPump = null;
            return pump == null || pump.close();
        }

        /** Protecting the call is terminal: recovery must never re-acquire its mic. */
        synchronized void dropMic() {
            micBlocked = true;
            micHealth.protectCall();
            closeMic();
        }

        @Override
        public int read(ByteBuffer out, MediaCodec.BufferInfo info) {
            if (!running) {
                return -1;
            }
            long due = startUs + (framesOut + FRAMES) * 1_000_000L / SAMPLE_RATE;
            long nowUs = SystemClock.elapsedRealtimeNanos() / 1000;
            Pump np = nearPump;
            try {
                if (near != null && np != null) {
                    // A healthy microphone is the clock: wait for a full block,
                    // but never past due + MIC_GRACE, so a stalled or reopening
                    // microphone cannot stall the other person's channel.
                    np.queue.dropBeyond(MAX_AHEAD);
                    long timeoutMs = Math.max(0L, (due + MIC_GRACE_US - nowUs) / 1000);
                    // Closed readers return immediately. Pace an incomplete
                    // block before advancing timestamps or consuming playback.
                    np.queue.takePaced(mine, FRAMES, timeoutMs, due, CLOCK);
                } else {
                    java.util.Arrays.fill(mine, (short) 0);
                    PcmRing.paceUntil(due, CLOCK);
                }
                if (!running) return -1;
                farPump.queue.dropBeyond(MAX_AHEAD * 2);
                farPump.queue.take(theirs, FRAMES * 2, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }

            // PCM in the platform's byte order, as AudioRecord writes it; a
            // ByteBuffer starts out big-endian, which would be noise.
            out.order(ByteOrder.nativeOrder());
            for (int i = 0; i < FRAMES; i++) {
                out.putShort((short) ((theirs[2 * i] + theirs[2 * i + 1]) / 2));
                out.putShort(mine[i]);
            }
            int bytes = FRAMES * AudioConfig.CHANNELS * AudioConfig.BYTES_PER_SAMPLE;
            long pts = startUs + framesOut * 1_000_000L / SAMPLE_RATE;
            framesOut += FRAMES;
            info.set(0, bytes, pts, 0);
            return bytes;
        }

        @Override
        public synchronized void stop() {
            running = false;
            if (current == this) {
                current = null;
            }
            closeMic();
            if (farPump != null) {
                farPump.close();
            }
            recordingEnded();
        }
    }

    /**
     * One AudioRecord drained on its own thread into a bounded queue of
     * samples, so neither side's reads ever wait on the other's.
     */
    static final class Pump implements Runnable {
        private final String name;
        private final AudioRecord record;
        private final Thread thread;
        /** Two seconds of samples; the encoder takes from here. */
        final PcmRing queue;
        volatile boolean running = true;
        VoipMicHealth health;

        Pump(String name, AudioRecord record, int channels) {
            this.name = name;
            this.record = record;
            this.queue = new PcmRing(SAMPLE_RATE * channels * 2);
            this.thread = new Thread(this, "jemrec-voip-" + name);
        }

        void start() {
            thread.start();
        }

        void halt() {
            running = false;
            queue.close();
        }

        /**
         * In this order: stop reading, stop the record (which wakes a read
         * blocked in it), wait for the thread to leave read(), and only then
         * release. Releasing an AudioRecord another thread is still reading
         * frees it under that read.
         */
        boolean close() {
            halt();
            try {
                record.stop();
            } catch (Throwable ignored) {
                // already stopped
            }
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                Ln.w("voip: " + name + " reader did not stop; leaving the record unreleased");
                return false;
            }
            record.release();
            return true;
        }

        @Override
        public void run() {
            short[] buf = new short[SAMPLE_RATE / 50 * 2];
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n < 0) {
                    Ln.w("voip: " + name + " read error " + n);
                    break;
                }
                if (n > 0) {
                    VoipMicHealth h = health;
                    if (h != null) h.samples(buf, n, SystemClock.elapsedRealtime());
                    queue.put(buf, n);
                }
            }
            // However it ended, say so: a read waiting on this side must not
            // wait for samples that will never come.
            halt();
        }
    }
}
