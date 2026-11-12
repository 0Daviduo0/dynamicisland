package dev.android.dynamicisland.island.source;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One incoming Quick Share session as Quick Share's receiving sheet knows it, read from the text of its view state
 * ({@code ReceivingDialogState(fetchStatus=..., requestAccept=RequestAccept(id=..., receiveStatus=..., ...), ...)}).
 * Kotlin keeps those names in the text whatever the obfuscation does to the classes, so nothing here depends on
 * class or field names. Also holds the broadcast protocol between Quick Share's process and System UI.
 */
public final class QuickShareState {
    public static final String PACKAGE = "com.samsung.android.app.sharelive";
    /** Quick Share → System UI: the sheet's state changed ({@link #EXTRA_STATE}) or it closed ({@link #EXTRA_GONE}). */
    public static final String ACTION_STATE = "dev.android.dynamicisland.QUICK_SHARE_STATE";
    /** System UI → Quick Share: {@link #EXTRA_COMMAND} for session {@link #EXTRA_ID}. */
    public static final String ACTION_COMMAND = "dev.android.dynamicisland.QUICK_SHARE_COMMAND";
    /** Both sides hold it (privileged); other apps can neither fake states nor send commands. */
    public static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_GONE = "gone";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_ID = "id";
    /** JPEG bytes of up to three previews, "preview0".."preview2", for session {@link #EXTRA_ID}. */
    public static final String EXTRA_PREVIEW = "preview";
    public static final int MAX_PREVIEWS = 3;
    public static final String CMD_ACK = "ack";
    public static final String CMD_RELEASE = "release";
    public static final String CMD_ACCEPT = "accept";
    public static final String CMD_DECLINE = "decline";
    public static final String CMD_CANCEL = "cancel";
    public static final String CMD_CLOSE = "close";
    /** Presses the finished sheet's own Open / View button, so the received content opens where Quick Share would. */
    public static final String CMD_OPEN = "open";

    /** Channel of Quick Share's outgoing-transfer notifications (sending, sent, failed); each carries {@code shareId}. */
    public static final String SEND_CHANNEL = "com.samsung.android.app.sharelive.FILE_SHARE";
    public static final String EXTRA_SHARE_ID = "shareId";
    /** Quick Share → System UI: progress of outgoing share {@link #EXTRA_SEND_SHARE} read from Quick Share's database. */
    public static final String EXTRA_SEND_SHARE = "sendShare";
    public static final String EXTRA_SEND_PROGRESS = "sendProgress";
    public static final String EXTRA_SEND_DEVICE = "sendDevice";
    public static final String EXTRA_SEND_FILE = "sendFile";
    public static final String EXTRA_SEND_COUNT = "sendCount";

    public enum Phase { REQUEST, TRANSFER, DONE, FAILED, DECLINED }

    public final int id;
    public final String receiveStatus;
    public final String acceptResult;
    public final String sender;
    public final String fileName;
    public final String text;
    public final int fileCount;
    public final int doneCount;
    /** 0..1, or -1 when not known. */
    public final float progress;
    /** Quick Share's own names: shareContentType (IMAGE, LINK, QR_WIFI...) and shareFileType (CONTACT, PDF...). */
    public final String contentType;
    public final String fileType;
    public final long totalBytes;

    private QuickShareState(int id, String receiveStatus, String acceptResult, String sender, String fileName, String text,
            int fileCount, int doneCount, float progress, String contentType, String fileType, long totalBytes) {
        this.contentType = contentType;
        this.fileType = fileType;
        this.totalBytes = totalBytes;
        this.id = id;
        this.receiveStatus = receiveStatus;
        this.acceptResult = acceptResult;
        this.sender = sender;
        this.fileName = fileName;
        this.text = text;
        this.fileCount = fileCount;
        this.doneCount = doneCount;
        this.progress = progress;
    }

    /** The state in {@code s}, or null when it is not a receiving state with a session. */
    public static QuickShareState parse(String s) {
        if (s == null) return null;
        int request = s.indexOf("RequestAccept(");
        if (request < 0) return null;
        String inner = s.substring(request + "RequestAccept(".length());
        int id = intOf(inner, "id", -1);
        if (id < 0) return null;
        String sender = firstText(value(inner, "senderName"), value(inner, "deviceName"));
        String file = firstText(value(inner, "representFileName"), value(inner, "fileName"));
        long total = longOf(inner, "totalFileSize", 0);
        long bytes = longOf(inner, "downloadBytes", 0);
        int percent = intOf(inner, "progress", -1);
        float progress = total > 0 && bytes > 0 ? Math.min(1f, bytes / (float) total)
                : percent >= 0 ? Math.min(1f, percent / 100f) : -1f;
        // the sheet's own choice sits outside RequestAccept; RequestAccept has its acceptResult too
        String user = word(s, "userAcceptResult");
        if (user == null || "NONE".equals(user)) user = word(inner, "acceptResult");
        return new QuickShareState(id, word(inner, "receiveStatus"), user, sender, file, value(inner, "text"),
                intOf(inner, "totalFileCount", 0), intOf(inner, "downloadFileCount", 0), progress,
                word(inner, "shareContentType"), word(inner, "shareFileType"), total);
    }

    /** The preview URIs of the state text ({@code previewUris=[a, b]}), at most {@link #MAX_PREVIEWS}. */
    public static java.util.List<String> previewUris(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) return out;
        Matcher m = PREVIEW_URIS.matcher(s);
        if (!m.find()) return out;
        for (String part : m.group(1).split(", ")) {
            String uri = part.trim();
            if ((uri.startsWith("content://") || uri.startsWith("file://") || uri.startsWith("/")) && out.size() < MAX_PREVIEWS) out.add(uri);
        }
        return out;
    }

    /** What the card shows for this content: image, video, contact, wifi, link, text, audio, app or file. */
    /** The same session with another receive status: a sheet that closed mid-transfer ended it one way or the other. */
    public QuickShareState withStatus(String status) {
        return new QuickShareState(id, status, acceptResult, sender, fileName, text, fileCount, doneCount, progress,
                contentType, fileType, totalBytes);
    }

    public String kind() {
        String c = contentType == null ? "" : contentType;
        String f = fileType == null ? "" : fileType;
        if (c.equals("QR_WIFI") || f.equals("QR_WIFI")) return "wifi";
        if (f.equals("CONTACT")) return "contact";
        if (c.equals("LINK") || f.equals("HTML")) return "link";
        if (c.equals("PLAIN_TEXT") || f.equals("TEXT") || f.equals("NOTE")) return "text";
        if (c.equals("VIDEO") || f.equals("VIDEO")) return "video";
        if (c.equals("IMAGE") || f.equals("IMAGE") || c.equals("MEDIA")) return "image";
        if (c.equals("AUDIO") || f.equals("MUSIC") || f.equals("RECORD")) return "audio";
        if (f.equals("APK")) return "app";
        return "file";
    }

    /** The network name of a shared Wi-Fi ("WIFI:S:name;T:WPA;P:secret;;"); the password is never read. */
    public static String wifiName(String text) {
        if (text == null) return null;
        Matcher m = WIFI_NAME.matcher(text);
        return m.find() ? m.group(1).replace("\\", "") : null;
    }

    public Phase phase() {
        String st = receiveStatus == null ? "" : receiveStatus;
        if ("DECLINE".equals(acceptResult) || "TIME_OUT".equals(acceptResult) || st.equals("NEARBY_SHARE_REJECTED")) return Phase.DECLINED;
        if (st.equals("TRANSFER_FAILED") || st.equals("TRANSFER_CANCELED") || st.equals("NEARBY_MEMORY_FULL")
                || "SENDER_CANCEL".equals(acceptResult) || "RECEIVER_CANCEL".equals(acceptResult)) return Phase.FAILED;
        if (st.equals("TRANSFER_FINISHED") || st.equals("DATA_RECEIVED_ACKNOWLEDGEMENT")) return Phase.DONE;
        if (st.equals("WAIT_FOR_ACCEPT") || st.equals("NONE") || st.isEmpty()) {
            return "ACCEPT".equals(acceptResult) ? Phase.TRANSFER : Phase.REQUEST;
        }
        return Phase.TRANSFER;
    }

    private static String firstText(String a, String b) {
        return a != null && !a.isEmpty() ? a : b != null && !b.isEmpty() ? b : null;
    }

    /** Compiled once per key: a transfer sends several states a second, each read through ~15 patterns. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Pattern PREVIEW_URIS = Pattern.compile("previewUris=\\[([^\\]]*)\\]");
    private static final Pattern WIFI_NAME = Pattern.compile("(?:^|;|:)S:((?:\\\\.|[^;])*)");

    private static Pattern pattern(char kind, String key) {
        return PATTERNS.computeIfAbsent(kind + key, k -> {
            String head = "(?:^|[ ,(])" + Pattern.quote(key) + "=";
            switch (kind) {
                case 'v': return Pattern.compile(head + "(.*?)(?=, [a-zA-Z0-9]+=|\\)$|\\), |\\)\\)|$)");
                case 'w': return Pattern.compile(head + "([A-Za-z_]+)");
                default: return Pattern.compile(head + "(-?\\d+)");
            }
        });
    }

    /** The raw value after {@code key=} up to the next {@code , name=} or the closing bracket; null for "null". */
    static String value(String s, String key) {
        Matcher m = pattern('v', key).matcher(s);
        if (!m.find()) return null;
        String v = m.group(1).trim();
        return v.isEmpty() || v.equals("null") ? null : v;
    }

    private static String word(String s, String key) {
        Matcher m = pattern('w', key).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static int intOf(String s, String key, int fallback) {
        long v = longOf(s, key, fallback);
        return v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? fallback : (int) v;
    }

    private static long longOf(String s, String key, long fallback) {
        Matcher m = pattern('n', key).matcher(s);
        return m.find() ? Long.parseLong(m.group(1)) : fallback;
    }
}
