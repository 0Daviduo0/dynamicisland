package dev.android.dynamicisland.island.feedback;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.XLog;
import java.util.Objects;

/**
 * Loudness of the ongoing call (0..1) for the call waveform. Only a level is computed; samples are never kept.
 *
 * <p>Phone calls are read from the call's own capture sources (System UI holds CAPTURE_AUDIO_OUTPUT): first both
 * directions, then the far end only. Calls of VoIP apps play through the normal mix, so they are measured with a
 * {@link Visualizer} on the output mix. When none of these delivers anything, {@link #isLive()} stays false and the
 * view falls back to a calm idle motion.
 */
public final class CallAudio {
    private static final int RATE = 16000;
    private static final long READ_MS = 40;
    private static final long LIVE_WINDOW_MS = 600;
    /** A source that gives only digital silence this long is taken as blocked and the next one is tried. */
    private static final long DEAD_SOURCE_MS = 4000;
    private static final int[] RECORD_SOURCES = {MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_DOWNLINK};

    private final Context context;
    private final Handler worker;
    private final Runnable onLevel;
    private volatile float level;
    private volatile long lastLevelAt;
    private volatile int generation;
    private String wanted;

    // worker thread only
    private AudioRecord recorder;
    private Visualizer visualizer;
    private final short[] pcm = new short[640];
    private long startedAt;
    private long lastSoundAt;

    public CallAudio(Context context, Runnable onLevel) {
        this.context = context;
        this.onLevel = onLevel;
        HandlerThread thread = new HandlerThread("IslandCallAudio");
        thread.start();
        worker = new dev.android.dynamicisland.xposed.GuardedHandler(thread.getLooper());
    }

    /**
     * Main thread. {@code target} names the visible call ("telecom" for phone calls, "voip" otherwise) and its key,
     * null when no call waveform is on screen.
     */
    public void setTarget(String target) {
        if (Objects.equals(target, wanted)) return;
        wanted = target;
        int token = ++generation;
        level = 0f;
        worker.post(() -> {
            stop();
            if (token != generation || target == null) return;
            if (target.startsWith("telecom")) startRecord(token, 0);
            else startVisualizer(token);
        });
    }

    /** Current level 0..1. */
    public float level() {
        return level;
    }

    /** True while a source delivered levels recently. */
    public boolean isLive() {
        return wanted != null && SystemClock.uptimeMillis() - lastLevelAt < LIVE_WINDOW_MS;
    }

    // Runs inside SystemUI, which holds CAPTURE_AUDIO_OUTPUT; failures fall through to the next source.
    @android.annotation.SuppressLint("MissingPermission")
    private void startRecord(int token, int index) {
        if (token != generation) return;
        if (index >= RECORD_SOURCES.length) {
            startVisualizer(token);
            return;
        }
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("format " + min);
            AudioRecord r = new AudioRecord.Builder().setContext(context)
                    .setAudioSource(RECORD_SOURCES[index])
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(min * 2, RATE / 5 * 2)).build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                throw new IllegalStateException("not initialised");
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                throw new IllegalStateException("did not start");
            }
            recorder = r;
            startedAt = SystemClock.uptimeMillis();
            lastSoundAt = 0;
            report("call source " + index + " capturing");
            readRecord(token, index);
        } catch (Throwable t) {
            report("call source " + index + " unavailable: " + t.getClass().getSimpleName());
            stop();
            startRecord(token, index + 1);
        }
    }

    private void readRecord(int token, int index) {
        if (token != generation || recorder == null) return;
        try {
            int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
            if (count < 0) throw new IllegalStateException("read " + count);
            long now = SystemClock.uptimeMillis();
            if (count > 0) {
                boolean silent = true;
                for (int i = 0; i < count && silent; i++) silent = pcm[i] == 0;
                if (!silent) lastSoundAt = now;
                publish(RecordingEnvelope.measure(pcm, count));
            }
            java.util.Arrays.fill(pcm, (short) 0);
            if (lastSoundAt == 0 && now - startedAt > DEAD_SOURCE_MS) {
                // the policy hands this source only zeros: try the next one
                report("call source " + index + " silent, trying next");
                stop();
                startRecord(token, index + 1);
                return;
            }
            worker.postDelayed(() -> readRecord(token, index), READ_MS);
        } catch (Throwable t) {
            stop();
            startRecord(token, index + 1);
        }
    }

    private void startVisualizer(int token) {
        if (token != generation) return;
        try {
            Visualizer v = new Visualizer(0);
            v.setEnabled(false);
            v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
            v.setEnabled(true);
            visualizer = v;
            report("call output mix measuring");
            readVisualizer(token, new Visualizer.MeasurementPeakRms());
        } catch (Throwable t) {
            report("no call audio source: " + t.getClass().getSimpleName());
            XLog.once("CallAudio.visualizer", t);
            stop();
        }
    }

    private void readVisualizer(int token, Visualizer.MeasurementPeakRms m) {
        if (token != generation || visualizer == null) return;
        try {
            if (visualizer.getMeasurementPeakRms(m) == Visualizer.SUCCESS) {
                // millibels, -9600 (silence) .. 0 (full scale)
                float db = m.mRms / 100f;
                publish(Math.max(0f, Math.min(1f, (db + 58f) / 46f)));
            }
            worker.postDelayed(() -> readVisualizer(token, m), READ_MS);
        } catch (Throwable t) {
            stop();
        }
    }

    private void publish(float value) {
        level = value;
        lastLevelAt = SystemClock.uptimeMillis();
        onLevel.run();
    }

    private void stop() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) { }
            try { recorder.release(); } catch (Throwable ignored) { }
            recorder = null;
        }
        if (visualizer != null) {
            try { visualizer.setEnabled(false); } catch (Throwable ignored) { }
            try { visualizer.release(); } catch (Throwable ignored) { }
            visualizer = null;
        }
        java.util.Arrays.fill(pcm, (short) 0);
    }

    private void report(String state) {
        Diagnostics.fact("call waveform", state);
    }
}
