package dev.android.dynamicisland.island.source;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.ContactsContract;
import dev.android.dynamicisland.xposed.XLog;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The caller's picture from the address book (System UI holds READ_CONTACTS). Looked up off the main thread by
 * the CallStyle person's URI, else by the number or the name the call shows; the result (or its absence) is
 * cached, and {@code onLoaded} runs once a picture arrives so the card can be rebuilt with it.
 */
final class ContactPhotos {
    private static final int MAX_PX = 256;
    private static final Object NONE = new Object();

    private final Context ctx;
    private final Runnable onLoaded;
    private final Map<String, Object> cache = new HashMap<>();
    private ExecutorService worker;

    ContactPhotos(Context ctx, Runnable onLoaded) {
        this.ctx = ctx;
        this.onLoaded = onLoaded;
    }

    /** The cached picture for this caller, or null (a lookup starts in the background on first ask). */
    synchronized Drawable photo(String personUri, CharSequence shown) {
        String key = keyOf(personUri, shown);
        if (key == null) return null;
        Object hit = cache.get(key);
        if (hit instanceof Bitmap) return new BitmapDrawable(ctx.getResources(), (Bitmap) hit);
        if (hit != null) return null; // NONE or a lookup in flight
        cache.put(key, NONE);
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "island-contacts"));
        worker.execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = load(ctx.getContentResolver(), personUri, shown);
            } catch (Throwable t) {
                XLog.e("contact photo lookup failed", t);
            }
            if (bmp == null) return;
            synchronized (ContactPhotos.this) {
                cache.put(key, bmp);
            }
            onLoaded.run();
        });
        return null;
    }

    static String keyOf(String personUri, CharSequence shown) {
        if (personUri != null && !personUri.isEmpty()) return "u:" + personUri;
        if (shown == null || shown.toString().trim().isEmpty()) return null;
        return "s:" + shown.toString().trim();
    }

    /** A shown caller that is a phone number rather than a name. */
    static boolean looksLikeNumber(String s) {
        if (s == null) return false;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch)) digits++;
            else if ("+-() .#*".indexOf(ch) < 0 && !Character.isSpaceChar(ch)) return false;
        }
        return digits >= 3;
    }

    private static Bitmap load(ContentResolver cr, String personUri, CharSequence shown) {
        Uri contact = null;
        if (personUri != null && !personUri.isEmpty()) {
            Uri u = Uri.parse(personUri);
            if ("tel".equals(u.getScheme())) contact = byNumber(cr, u.getSchemeSpecificPart());
            else if ("content".equals(u.getScheme())) contact = ContactsContract.Contacts.lookupContact(cr, u);
        }
        if (contact == null && shown != null) {
            String s = shown.toString().trim();
            contact = looksLikeNumber(s) ? byNumber(cr, s) : byName(cr, s);
        }
        if (contact == null) return null;
        try (InputStream in = ContactsContract.Contacts.openContactPhotoInputStream(cr, contact, true)) {
            return in == null ? null : decode(in);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Uri byNumber(ContentResolver cr, String number) {
        Uri q = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        return contactOf(cr, q, ContactsContract.PhoneLookup._ID, null, null);
    }

    private static Uri byName(ContentResolver cr, String name) {
        return contactOf(cr, ContactsContract.Contacts.CONTENT_URI, ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + "=? AND " + ContactsContract.Contacts.PHOTO_ID + " IS NOT NULL",
                new String[] {name});
    }

    private static Uri contactOf(ContentResolver cr, Uri q, String idColumn, String where, String[] args) {
        try (Cursor c = cr.query(q, new String[] {idColumn}, where, args, null)) {
            if (c == null || !c.moveToFirst()) return null;
            return ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.getLong(0));
        }
    }

    private static Bitmap decode(InputStream in) throws java.io.IOException {
        byte[] bytes = in.readAllBytes();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= MAX_PX) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }
}
