package dev.android.dynamicisland.island.feedback;

import android.os.HandlerThread;
import android.os.Looper;

/**
 * One background looper for the audio readers (media visualizer, call audio, recording input). Their reads
 * never block, so they share it instead of each keeping its own idle thread inside System UI.
 */
final class AudioThread {
    private static Looper looper;

    private AudioThread() { }

    static synchronized Looper looper() {
        if (looper == null) {
            HandlerThread thread = new HandlerThread("DynamicIslandAudio");
            thread.start();
            looper = thread.getLooper();
        }
        return looper;
    }
}
