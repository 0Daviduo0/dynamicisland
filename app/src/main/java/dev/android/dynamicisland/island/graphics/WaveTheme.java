package dev.android.dynamicisland.island.graphics;

/**
 * The One UI 9 player's wave themes ({@code com.android.systemui.media.colorfulwave.config.WaveTheme}):
 * the seed colour's hue picks one of 18 fixed themes, each five layers of colour, opacity and blend mode.
 * Pure Java so tests and previews see the same mapping.
 */
public final class WaveTheme {
    /** Blend modes the stock layers use; mapped to {@code android.graphics.BlendMode} by the renderer. */
    public static final int NORMAL = 0, ADD = 1, OVERLAY = 2, SCREEN = 3;
    public static final int LAYERS = 5;
    /** Track after the thumb, the same in every theme. */
    public static final int REMAIN_TRACK = 0x66FCFCFC;

    /** Per layer (stock MAIN slot tuning). */
    public static final float[] SCALES = {1.0f, 0.85f, 0.7f, 0.55f, 0.4f};
    public static final float[] FREQUENCIES = {0.7f, 0.9f, 1.3f, 1.7f, 2.0f};
    public static final float[] PHASES = {0.0f, 1.26f, 2.51f, 3.77f, 5.03f};
    /** Wave cycles across the whole track (before the layer frequency), phase speed and per-layer speed. */
    public static final int CYCLES = 3;
    public static final float PHASE_PER_SECOND = -9.0f;
    public static final float SPEED = 0.5f;

    public final String name;
    /** ARGB, alpha already holding the layer's opacity. */
    public final int[] colors;
    public final int[] modes;

    private WaveTheme(String name, int[] colors, int[] opacities, int[] modes) {
        this.name = name;
        this.colors = new int[LAYERS];
        for (int i = 0; i < LAYERS; i++) {
            int a = Math.max(0, Math.min(255, opacities[i] * 255 / 100));
            this.colors[i] = (colors[i] & 0x00FFFFFF) | (a << 24);
        }
        this.modes = modes;
    }

    /** Thumb centre and glow: the first layer's colour, opaque. */
    public int thumb() {
        return colors[0] | 0xFF000000;
    }

    private static final int[] OP_RED = {90, 90, 100, 90, 100};
    private static final int[] OP_WARM = OP_RED;
    private static final int[] OP_90 = {90, 90, 90, 90, 90};
    private static final int[] OP_COOL = {90, 100, 90, 90, 90};
    private static final int[] M_WARM = {ADD, OVERLAY, NORMAL, SCREEN, SCREEN};
    private static final int[] M_COOL = {SCREEN, OVERLAY, SCREEN, SCREEN, SCREEN};

    private static WaveTheme warm(String n, int a, int c, int d, int e) {
        return new WaveTheme(n, new int[]{a, 0xFFFFFF, c, d, e}, OP_WARM, M_WARM);
    }

    private static WaveTheme cool(String n, int[] op, int a, int c, int d, int e) {
        return new WaveTheme(n, new int[]{a, 0xFFFFFF, c, d, e}, op, M_COOL);
    }

    public static final WaveTheme RED = warm("RED", 0xEA4147, 0xFF9499, 0xFA301E, 0xBF2C2C);
    public static final WaveTheme REDORANGE = warm("REDORANGE", 0xE52E00, 0xFFA494, 0xFA4A1E, 0xBF492C);
    public static final WaveTheme ORANGE = warm("ORANGE", 0xE55800, 0xFFB494, 0xFA7A1E, 0xBF692C);
    public static final WaveTheme ORANGEYELLOW = warm("ORANGEYELLOW", 0xE58600, 0xFFC694, 0xFA9E1E, 0xBF822C);
    public static final WaveTheme YELLOW = cool("YELLOW", OP_90, 0xFDE52F, 0xF3FF94, 0xFAE01E, 0xBFB32C);
    public static final WaveTheme YELLOWGREEN = cool("YELLOWGREEN", OP_90, 0xBFFD2F, 0xBDFF94, 0xEBFA1E, 0xA9BF2C);
    public static final WaveTheme GREEN = cool("GREEN", OP_90, 0x77FD2F, 0xA6FF94, 0x97FA1E, 0x6CBF2C);
    public static final WaveTheme GREENCYAN = cool("GREENCYAN", OP_90, 0x2FFD85, 0x94FF9B, 0x4EE029, 0x2CBF31);
    public static final WaveTheme CYAN = cool("CYAN", OP_90, 0x2FFDEC, 0x94FFDA, 0x1EFAAD, 0x2CBFAE);
    public static final WaveTheme CYANBLUE = cool("CYANBLUE", OP_90, 0x2FD0FD, 0x94F8FF, 0x1ED9FA, 0x2C8EBF);
    public static final WaveTheme BLUE = cool("BLUE", OP_COOL, 0x2FA7FD, 0x94E2FF, 0x1FB7EE, 0x2C7DBF);
    public static final WaveTheme BLUENAVY = cool("BLUENAVY", OP_COOL, 0x2F70FD, 0x94C2FF, 0x1F70EB, 0x2C5FBF);
    public static final WaveTheme NAVY = cool("NAVY", OP_COOL, 0x2F4AFD, 0x94ADFF, 0x244BE9, 0x2C45BF);
    public static final WaveTheme NAVYVIOLET = cool("NAVYVIOLET", OP_COOL, 0x4E2FFD, 0x9B94FF, 0x5C37DE, 0x422CBF);
    public static final WaveTheme VIOLET = cool("VIOLET", OP_COOL, 0x8E31FF, 0xB494FF, 0x6E24D7, 0x581DAA);
    public static final WaveTheme PURPLE = cool("PURPLE", OP_COOL, 0xDB2FFD, 0xFF94E8, 0xB51DE8, 0x93267C);
    public static final WaveTheme PINK = cool("PINK", OP_COOL, 0xFD2FB1, 0xFF94B6, 0xDD14B1, 0x9E1F52);
    public static final WaveTheme MONO = new WaveTheme("MONO",
            new int[]{0x2D3855, 0xFFFFFF, 0x5A5140, 0xC0C0C0, 0x463E30},
            new int[]{90, 100, 80, 60, 80}, new int[]{ADD, OVERLAY, SCREEN, SCREEN, SCREEN});

    /** {@code WaveSeekbar.setThemeByColor}: grey or dark seeds are MONO, otherwise by hue bucket. */
    public static WaveTheme forSeed(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float v = max / 255f;
        float s = max == 0 ? 0 : (max - min) / (float) max;
        if (s <= 0.1f || v <= 0.1f) return MONO;
        float h;
        float d = max - min;
        if (max == r) h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        int hue = Math.max(0, Math.min(360, (int) h));
        if (hue >= 350 || hue < 6) return RED;
        if (hue < 21) return REDORANGE;
        if (hue < 36) return ORANGE;
        if (hue < 51) return ORANGEYELLOW;
        if (hue < 66) return YELLOW;
        if (hue < 81) return YELLOWGREEN;
        if (hue < 117) return GREEN;
        if (hue < 151) return GREENCYAN;
        if (hue < 176) return CYAN;
        if (hue < 191) return CYANBLUE;
        if (hue < 216) return BLUE;
        if (hue < 241) return BLUENAVY;
        if (hue < 264) return NAVY;
        if (hue < 286) return NAVYVIOLET;
        if (hue < 308) return VIOLET;
        if (hue < 330) return PURPLE;
        return PINK;
    }
}
