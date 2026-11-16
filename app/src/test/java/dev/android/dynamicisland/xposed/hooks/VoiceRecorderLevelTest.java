package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Voice Recorder's peak amplitude becomes the island's 0..1 level. */
public class VoiceRecorderLevelTest {
    @Test public void silenceIsZeroFullScaleIsOne() {
        assertEquals(0f, VoiceRecorderHooks.level(0), 0f);
        assertEquals(0f, VoiceRecorderHooks.level(50), 0f);
        assertEquals(1f, VoiceRecorderHooks.level(32767), 0.01f);
    }

    @Test public void louderIsHigher() {
        assertTrue(VoiceRecorderHooks.level(2000) < VoiceRecorderHooks.level(8000));
        assertTrue(VoiceRecorderHooks.level(2000) > 0f);
    }
}
