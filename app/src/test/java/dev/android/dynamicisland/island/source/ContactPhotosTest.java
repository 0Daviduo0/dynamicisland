package dev.android.dynamicisland.island.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContactPhotosTest {
    @Test public void numbersAreTellFromNames() {
        assertTrue(ContactPhotos.looksLikeNumber("+39 333 123 4567"));
        assertTrue(ContactPhotos.looksLikeNumber("(02) 555-0100"));
        assertFalse(ContactPhotos.looksLikeNumber("Mario Rossi"));
        assertFalse(ContactPhotos.looksLikeNumber("12"));
        assertFalse(ContactPhotos.looksLikeNumber(null));
    }

    @Test public void cacheKeyPrefersThePersonUri() {
        assertEquals("u:tel:123", ContactPhotos.keyOf("tel:123", "Mario"));
        assertEquals("s:Mario", ContactPhotos.keyOf(null, " Mario "));
        assertNull(ContactPhotos.keyOf("", "  "));
    }
}
