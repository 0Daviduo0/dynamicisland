package dev.android.dynamicisland.island.feedback;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.XLog;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Experimental concurrent microphone reader in System UI only. Never changes routing or audio focus. */
public final class RecorderInput {
    private static final int RATE = 16000;
    private final Context context;
    private final AudioManager audio;
    private final PowerManager power;
    private final Handler main = new dev.android.dynamicisland.xposed.GuardedHandler(Looper.getMainLooper());
    private final Handler worker;
    private final Runnable onChanged;
    public final RecordingEnvelope envelope = new RecordingEnvelope();
    private volatile int generation;
    private volatile String wantedKey;
    /** Last level the recording app itself reported (elapsedRealtime), see {@link #ACTION_LEVEL}. */
    private volatile long appLevelAt = -1;
    /**
     * A recording app hooked by the module (Samsung Voice Recorder) sends its own input level: no second
     * microphone, which the audio policy would answer by silencing the real recording.
     */
    public static final String ACTION_LEVEL = "dev.android.dynamicisland.action.RECORDING_LEVEL";
    public static final String EXTRA_LEVEL = "level";
    /** App levels this recent keep the concurrent microphone off. */
    private static final long APP_LEVEL_MS = 3000;
    // Worker thread only, except the callback's immutable snapshot.
    private AudioRecord recorder;
    private AudioManager.AudioRecordingCallback callback;
    private final short[] pcm = new short[800];
    private int reads, ticks;
    /** The next read, reused so the 25 Hz loop allocates nothing. Worker thread only. */
    private String readKey;
    private int readToken;
    private final Runnable readNext = () -> read(readKey, readToken);

    public RecorderInput(Context context, Runnable onChanged) {
        this.context = context;
        this.onChanged = onChanged;
        audio = context.getSystemService(AudioManager.class);
        power = context.getSystemService(PowerManager.class);
        worker = new dev.android.dynamicisland.xposed.GuardedHandler(AudioThread.looper());
        try {
            // only a level 0..1 is read from it: anything sending it can at most move the waveform
            context.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, android.content.Intent intent) {
                    onAppLevel(intent.getFloatExtra(EXTRA_LEVEL, -1f));
                }
            }, new android.content.IntentFilter(ACTION_LEVEL), null, worker, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.once("recording level receiver", t);
        }
    }

    /** Worker thread. */
    private void onAppLevel(float level) {
        String key = wantedKey;
        if (key == null || !(level >= 0f && level <= 1f)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (appLevelAt < 0 || recorder != null) {
            stop();
            report("levels from the recording app");
        }
        appLevelAt = now;
        envelope.add(key, level, now);
        main.post(onChanged);
    }

    /** Main thread. A failed attempt is retried only after the target changes (pause/resume/new session). */
    public void setTarget(String key) {
        if (java.util.Objects.equals(key, wantedKey)) return;
        wantedKey = key;
        int token = ++generation;
        envelope.reset(key);
        worker.post(() -> {
            stop();
            if (token != generation) return;
            if (key == null) { report("inactive"); return; }
            report("waiting for recorder; concurrent MIC experiment");
            worker.postDelayed(() -> start(key, token), 650);
        });
    }

    // This opt-in reader runs only in SystemUI; host permission/AppOps and startup failures remain enforced.
    @android.annotation.SuppressLint("MissingPermission")
    private void start(String key, int token) {
        if (token != generation) return;
        if (appLevelAt >= 0 && android.os.SystemClock.elapsedRealtime() - appLevelAt < APP_LEVEL_MS) {
            report("levels from the recording app");
            return;
        }
        try {
            if (audio == null || audio.isMicrophoneMute() || power == null || !power.isInteractive()) {
                report("microphone muted or screen off");
                return;
            }
            Set<Integer> previouslyAudible = audibleSessions(audio.getActiveRecordingConfigurations());
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("unsupported microphone format: " + min);
            recorder = new AudioRecord.Builder().setContext(context)
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setPrivacySensitive(false)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(min * 2, RATE / 5 * 2)).build();
            if (token != generation) { stop(); return; }
            callback = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    if (token != generation) return;
                    Set<Integer> silenced = silencedSessions(configs);
                    if (otherBecameSilenced(previouslyAudible, silenced)) {
                        worker.post(() -> fail(key, token, "stopped: another recording became silenced", null));
                    }
                }
            };
            audio.registerAudioRecordingCallback(callback, main);
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("microphone did not start");
            }
            reads = 0;
            ticks = 0;
            report("capturing concurrent microphone (VOICE_RECOGNITION)");
            read(key, token);
        } catch (Throwable error) {
            fail(key, token, "unavailable: " + error.getClass().getSimpleName(), error);
        }
    }

    private void read(String key, int token) {
        if (token != generation) return;
        try {
            if (recorder == null) return;
            // Binder checks once a second, not on every 40 ms read: sleep and doze already clear the target,
            // and the recording callback reports silencing as it happens.
            if (ticks++ % 25 == 0) {
                if (!power.isInteractive() || audio.isMicrophoneMute()) {
                    fail(key, token, "stopped: screen off or microphone muted", null);
                    return;
                }
                AudioRecordingConfiguration own = recorder.getActiveRecordingConfiguration();
                if (own != null && own.isClientSilenced()) {
                    fail(key, token, "blocked: concurrent input is silenced by audio policy", null);
                    return;
                }
            }
            int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
            if (count < 0) throw new IllegalStateException("microphone read: " + count);
            if (count > 0) {
                float level = RecordingEnvelope.measure(pcm, count);
                envelope.add(key, level, android.os.SystemClock.elapsedRealtime());
                // Publish only coarse status, never microphone samples or audio contents.
                if (++reads % 25 == 0) report(level > .01f ? "capturing; input signal detected" : "capturing; input currently quiet");
                main.post(onChanged);
            }
            readKey = key;
            readToken = token;
            worker.postDelayed(readNext, 40);
        } catch (Throwable error) {
            fail(key, token, "read failed: " + error.getClass().getSimpleName(), error);
        }
    }

    private void fail(String key, int token, String reason, Throwable error) {
        if (token != generation) return;
        stop();
        envelope.reset(key);
        report(reason);
        main.post(onChanged);
        if (error != null) XLog.once("concurrent microphone", error);
    }

    private void stop() {
        if (callback != null && audio != null) {
            try { audio.unregisterAudioRecordingCallback(callback); } catch (Throwable ignored) { }
            callback = null;
        }
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) { }
            try { recorder.release(); } catch (Throwable ignored) { }
            recorder = null;
        }
        java.util.Arrays.fill(pcm, (short) 0);
    }

    private void report(String state) {
        Diagnostics.fact("recording microphone experiment", state);
        Diagnostics.publish(context);
    }

    private static Set<Integer> audibleSessions(List<AudioRecordingConfiguration> configs) {
        Set<Integer> sessions = new HashSet<>();
        for (AudioRecordingConfiguration c : configs) {
            if (!c.isClientSilenced() && c.getClientAudioSessionId() > 0) sessions.add(c.getClientAudioSessionId());
        }
        return sessions;
    }

    private static Set<Integer> silencedSessions(List<AudioRecordingConfiguration> configs) {
        Set<Integer> sessions = new HashSet<>();
        for (AudioRecordingConfiguration c : configs) {
            if (c.isClientSilenced() && c.getClientAudioSessionId() > 0) sessions.add(c.getClientAudioSessionId());
        }
        return sessions;
    }

    static boolean otherBecameSilenced(Set<Integer> before, Set<Integer> silenced) {
        for (int id : before) if (silenced.contains(id)) return true;
        return false;
    }
}
