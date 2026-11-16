package dev.android.dynamicisland.xposed.hooks;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.SystemClock;
import de.robv.android.xposed.XposedBridge;
import dev.android.dynamicisland.island.feedback.RecorderInput;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;

/**
 * Runs in Samsung Voice Recorder. The app reads its input level ({@link MediaRecorder#getMaxAmplitude}) for its
 * own waveform; the same level goes to System UI for the island's waveform. Only a number 0..1 leaves the app,
 * never audio. Opt-in: only when Voice Recorder is in the module's LSPosed scope.
 */
public final class VoiceRecorderHooks {
    public static final String PACKAGE = "com.sec.android.app.voicenote";
    /** At most this often (ms): the island's waveform does not need more. */
    private static final long MIN_GAP_MS = 45;
    private static long sentAt;
    private static int peak;

    private VoiceRecorderHooks() {
    }

    public static void install() {
        int n = XposedBridge.hookAllMethods(MediaRecorder.class, "getMaxAmplitude", SafeHook.after("recorder level", p -> {
            if (!(p.getResult() instanceof Integer)) return;
            peak = Math.max(peak, (Integer) p.getResult());
            long now = SystemClock.elapsedRealtime();
            if (now - sentAt < MIN_GAP_MS) return;
            sentAt = now;
            float level = level(peak);
            peak = 0;
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            app.sendBroadcast(new Intent(RecorderInput.ACTION_LEVEL).setPackage("com.android.systemui")
                    .putExtra(RecorderInput.EXTRA_LEVEL, level));
        })).size();
        XLog.i("voice recorder hooks: " + n);
    }

    /** Peak amplitude (0..32767) to the island's 0..1, on a decibel scale like the island's own meter. */
    static float level(int amplitude) {
        if (amplitude <= 0) return 0f;
        double db = 20d * Math.log10(amplitude / 32768d);
        return (float) Math.max(0d, Math.min(1d, (db + 50d) / 45d));
    }
}
