package dev.android.dynamicisland.island.view.card;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.TickDrawable;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;
import java.util.List;

/**
 * Quick Share card, AirDrop style: what is shared at the left (a loose stack of up to three photos, or a tile drawn for
 * the kind of content: contact, Wi-Fi, link, text, document), who shares it and what it is at the right, then the
 * progress and the buttons. A finished share gets a tick that strokes in over the content.
 */
public final class QuickShareBody extends CardBody {
    private static final float PREVIEW_DP = 64f;
    private static final float[] ANGLES = {8f, -9f, 0f};
    private static final float[] SHIFT_DP = {5f, -5f, 0f};

    private final float pad, preview, textLeft;
    private final StaticLayout title, subtitle, label;
    private final float blockH, barTop, buttonsTop;
    private final boolean bar;
    /** When the tick of a session started: the card is rebuilt on each update, the tick strokes in only once. */
    private static String tickKey;
    private static long tickAt;
    private final long born;
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    /** Worked out once per card, not per frame: the contact's initials and the fitted extension badge. */
    private final String initials, badgeText;
    private final float badgeSize;
    private int shownPercent = -1;
    private String percentText;

    public QuickShareBody(IslandView owner, IslandItem item, float width) {
        super(owner, item, width);
        pad = owner.dp(18);
        preview = owner.dp(PREVIEW_DP);
        textLeft = pad + preview + owner.dp(16);
        int textWidth = Math.max(1, Math.round(width - textLeft - pad));
        title = IslandView.layout(item.title == null ? "" : item.title, owner.titlePaint, textWidth, 1);
        subtitle = item.subtitle == null ? null : IslandView.layout(item.subtitle, owner.bodyPaint, textWidth, 2);
        CharSequence what = item.shareLabel != null ? item.shareLabel : item.detail;
        label = what == null || what.length() == 0 ? null : IslandView.layout(what, owner.smallPaint, textWidth, 2);
        float block = title.getHeight() + (subtitle == null ? 0 : owner.dp(2) + subtitle.getHeight())
                + (label == null ? 0 : owner.dp(4) + label.getHeight());
        blockH = Math.max(preview + owner.dp(6), block);
        float y = pad + blockH;
        bar = item.trailing == IslandItem.Trailing.PROGRESS_RING && !item.shareDone && item.actions.size() < 2;
        barTop = y + owner.dp(14);
        if (bar) y = barTop + owner.dp(6) + owner.dp(22);
        buttonsTop = y + owner.dp(14);
        float buttonsH = layoutTextButtons(buttonsTop, pad);
        h = buttonsH > 0 ? buttonsTop + buttonsH + pad : y + pad;
        long now = SystemClock.uptimeMillis();
        if (item.shareDone && !item.key.equals(tickKey)) {
            tickKey = item.key;
            tickAt = now;
        }
        born = item.shareDone ? tickAt : now;
        glyphText.setTypeface(owner.titlePaint.getTypeface());
        glyphText.setTextAlign(Paint.Align.CENTER);
        initials = initialsOf(item.shareLabel);
        String badge = item.shareBadge;
        badgeSize = preview * (badge != null && badge.length() > 3 ? .13f : .16f);
        if (badge != null) {
            glyphText.setTextSize(badgeSize);
            badgeText = TextUtils.ellipsize(badge, glyphText, preview * .46f * .92f, TextUtils.TruncateAt.END).toString();
        } else {
            badgeText = null;
        }
    }

    private static String initialsOf(CharSequence name) {
        if (name == null) return null;
        StringBuilder out = new StringBuilder(2);
        for (String part : name.toString().trim().split("\\s+")) {
            if (!part.isEmpty() && Character.isLetter(part.codePointAt(0)) && out.length() < 2) {
                out.appendCodePoint(Character.toUpperCase(part.codePointAt(0)));
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    @Override
    public boolean animating(long now) {
        return now - born < 900 || (bar && item.progress < 0f);
    }

    @Override
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        float a0 = alpha * rowAlpha(elapsed, 0);
        float px = x + pad, py = y + pad + (blockH - preview) / 2f;
        drawContent(c, px, py, a0, elapsed);
        if (item.shareDone) {
            float t = (SystemClock.uptimeMillis() - born) / 1f;
            float grow = IslandView.clamp01((t - 120f) / 260f);
            grow = 1f - (1f - grow) * (1f - grow);
            float overshoot = grow < 1f ? grow * 1.12f : 1f;
            float stroke = IslandView.clamp01((t - 320f) / 320f);
            float size = owner.dp(26);
            float cx = px + preview - size * .25f, cy = py + preview - size * .25f;
            paint.setColor(Colors.withAlpha(Colors.BLACK, a0));
            c.drawCircle(cx, cy, size / 2f * overshoot + owner.dp(2.5f), paint);
            TickDrawable.draw(c, cx, cy, size, overshoot, stroke, a0);
        }

        float a1 = alpha * rowAlpha(elapsed, 1);
        float block = title.getHeight() + (subtitle == null ? 0 : owner.dp(2) + subtitle.getHeight())
                + (label == null ? 0 : owner.dp(4) + label.getHeight());
        float top = y + pad + (blockH - block) / 2f;
        owner.titlePaint.setColor(Colors.withAlpha(Colors.WHITE, a1));
        IslandView.drawLayout(c, title, x + textLeft, top);
        top += title.getHeight();
        if (subtitle != null) {
            owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a1));
            IslandView.drawLayout(c, subtitle, x + textLeft, top + owner.dp(2));
            top += owner.dp(2) + subtitle.getHeight();
        }
        if (label != null) {
            owner.smallPaint.setColor(Colors.withAlpha(0x73FFFFFF, a1));
            IslandView.drawLayout(c, label, x + textLeft, top + owner.dp(4));
        }

        if (bar) drawBar(c, x, y, alpha * rowAlpha(elapsed, 2));
        drawButtons(c, x, y, alpha * rowAlpha(elapsed, bar ? 3 : 2));
    }

    private void drawBar(Canvas c, float x, float y, float a) {
        float left = x + pad, right = x + w - pad, top = y + barTop, hgt = owner.dp(6);
        rect.set(left, top, right, top + hgt);
        paint.setColor(Colors.withAlpha(Colors.TRACK, a));
        c.drawRoundRect(rect, hgt / 2f, hgt / 2f, paint);
        paint.setColor(Colors.withAlpha(item.accent, a));
        String info;
        if (item.progress >= 0f) {
            float p = Math.max(.02f, IslandView.clamp01(item.progress));
            rect.set(left, top, left + (right - left) * p, top + hgt);
            c.drawRoundRect(rect, hgt / 2f, hgt / 2f, paint);
            int percent = Math.round(item.progress * 100);
            if (percent != shownPercent) {
                shownPercent = percent;
                percentText = percent + "%";
            }
            info = percentText;
        } else {
            float phase = (SystemClock.uptimeMillis() % 1300) / 1300f;
            float seg = (right - left) * .3f;
            float start = left - seg + (right - left + seg) * phase;
            int save = c.save();
            c.clipRect(left, top, right, top + hgt);
            rect.set(start, top, start + seg, top + hgt);
            c.drawRoundRect(rect, hgt / 2f, hgt / 2f, paint);
            c.restoreToCount(save);
            info = null;
        }
        if (info != null) {
            owner.smallPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a));
            owner.smallPaint.getFontMetrics(fm);
            owner.drawTime(c, item.key + "#qs", info, owner.smallPaint, left, top + hgt + owner.dp(6) - fm.ascent, false);
        }
    }

    // ---- the content at the left ---------------------------------------------------------------------------------

    private void drawContent(Canvas c, float left, float top, float a, long elapsed) {
        List<android.graphics.Bitmap> pics = item.sharePreviews;
        if (pics != null && !pics.isEmpty()) {
            drawStack(c, pics, left, top, a, elapsed);
            return;
        }
        float r = owner.dp(16);
        rect.set(left, top, left + preview, top + preview);
        String kind = item.shareKind == null ? "file" : item.shareKind;
        paint.setColor(Colors.withAlpha(tileColor(kind), a));
        if (kind.equals("contact")) {
            c.drawOval(rect, paint);
        } else {
            c.drawRoundRect(rect, r, r, paint);
        }
        float cx = rect.centerX(), cy = rect.centerY();
        paint.setColor(Colors.withAlpha(Colors.WHITE, a));
        switch (kind) {
            case "contact": drawInitials(c, cx, cy, a); break;
            case "wifi": drawWifi(c, cx, cy + preview * .12f, preview * .34f, a); break;
            case "link": drawGlobe(c, cx, cy, preview * .26f, a); break;
            case "text": drawCentered(c, "“", cx, cy + preview * .16f, preview * .72f, a); break;
            case "audio": drawCentered(c, "♫", cx, cy, preview * .42f, a); break;
            case "video": drawPlay(c, cx, cy, preview * .2f, a); break;
            case "image": drawMountains(c, a); break;
            default: drawDocument(c, cx, cy, a);
        }
    }

    private static int tileColor(String kind) {
        switch (kind) {
            case "contact": return 0xFF8E8E93;
            case "wifi": return 0xFF0A84FF;
            case "link": return 0xFF5E5CE6;
            case "text": return 0xFFFF9F0A;
            case "audio": return 0xFFFF375F;
            case "video": case "image": return 0xFF30B0C7;
            case "app": return 0xFF34C759;
            default: return 0xFF3A3A3C;
        }
    }

    /** A loose pile: the newest picture straight on top, the others tilted out from under it. */
    private void drawStack(Canvas c, List<Bitmap> pics, float left, float top, float a, long elapsed) {
        int n = Math.min(3, pics.size());
        float size = n == 1 ? preview : preview * .86f;
        float cx = left + preview / 2f, cy = top + preview / 2f;
        float spread = IslandView.clamp01(elapsed / 320f);
        spread = 1f - (1f - spread) * (1f - spread);
        for (int i = 0; i < n; i++) {
            int slot = 3 - n + i; // the last one drawn is the straight one
            Bitmap bmp = pics.get(n - 1 - i);
            int save = c.save();
            c.translate(cx + owner.dp(SHIFT_DP[slot]) * spread, cy);
            c.rotate(ANGLES[slot] * spread);
            float half = size / 2f, r = owner.dp(n == 1 ? 14 : 10), border = n == 1 ? 0 : owner.dp(1.5f);
            if (border > 0) {
                rect.set(-half - border, -half - border, half + border, half + border);
                paint.setColor(Colors.withAlpha(0x40000000, a));
                rect.offset(0, owner.dp(1.5f));
                c.drawRoundRect(rect, r + border, r + border, paint);
                rect.offset(0, -owner.dp(1.5f));
                paint.setColor(Colors.withAlpha(Colors.WHITE, a));
                c.drawRoundRect(rect, r + border, r + border, paint);
            }
            rect.set(-half, -half, half, half);
            clip.reset();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            c.clipPath(clip);
            float scale = Math.max(size / bmp.getWidth(), size / bmp.getHeight());
            float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
            rect.set(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f);
            image.setAlpha(Math.round(255 * a));
            c.drawBitmap(bmp, null, rect, image);
            c.restoreToCount(save);
        }
        if ("video".equals(item.shareKind)) {
            paint.setColor(Colors.withAlpha(0x99000000, a));
            c.drawCircle(cx, cy, preview * .17f, paint);
            paint.setColor(Colors.withAlpha(Colors.WHITE, a));
            drawPlay(c, cx, cy, preview * .1f, a);
        }
    }

    private void drawInitials(Canvas c, float cx, float cy, float a) {
        if (initials != null) {
            drawCentered(c, initials, cx, cy, preview * .36f, a);
            return;
        }
        // person silhouette
        c.drawCircle(cx, cy - preview * .1f, preview * .14f, paint);
        rect.set(cx - preview * .26f, cy + preview * .1f, cx + preview * .26f, cy + preview * .5f);
        c.drawArc(rect, 180, 180, true, paint);
    }

    private void drawCentered(Canvas c, String text, float cx, float cy, float size, float a) {
        glyphText.setTextSize(size);
        glyphText.setColor(Colors.withAlpha(Colors.WHITE, a));
        glyphText.getFontMetrics(fm);
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, glyphText);
    }

    private void drawWifi(Canvas c, float cx, float base, float r, float a) {
        Paint s = strokePaint(r * .2f, a);
        for (int i = 1; i <= 2; i++) {
            float rr = r * (.42f + .3f * i);
            rect.set(cx - rr, base - rr, cx + rr, base + rr);
            c.drawArc(rect, 225, 90, false, s);
        }
        c.drawCircle(cx, base - r * .06f, r * .17f, paint);
    }

    private void drawGlobe(Canvas c, float cx, float cy, float r, float a) {
        Paint s = strokePaint(owner.dp(2.2f), a);
        c.drawCircle(cx, cy, r, s);
        rect.set(cx - r * .45f, cy - r, cx + r * .45f, cy + r);
        c.drawOval(rect, s);
        c.drawLine(cx - r, cy, cx + r, cy, s);
    }

    private void drawPlay(Canvas c, float cx, float cy, float r, float a) {
        clip.reset();
        clip.moveTo(cx - r * .7f, cy - r);
        clip.lineTo(cx + r, cy);
        clip.lineTo(cx - r * .7f, cy + r);
        clip.close();
        c.drawPath(clip, paint);
    }

    private void drawMountains(Canvas c, float a) {
        float l = rect.left, t = rect.top, s = preview;
        c.drawCircle(l + s * .66f, t + s * .34f, s * .08f, paint);
        clip.reset();
        clip.moveTo(l + s * .2f, t + s * .74f);
        clip.lineTo(l + s * .42f, t + s * .44f);
        clip.lineTo(l + s * .58f, t + s * .62f);
        clip.lineTo(l + s * .66f, t + s * .54f);
        clip.lineTo(l + s * .8f, t + s * .74f);
        clip.close();
        c.drawPath(clip, paint);
    }

    /** A sheet with a folded corner and the file's extension. */
    private void drawDocument(Canvas c, float cx, float cy, float a) {
        float dw = preview * .46f, dh = preview * .58f, fold = dw * .32f;
        float l = cx - dw / 2f, t = cy - dh / 2f;
        clip.reset();
        clip.moveTo(l, t + owner.dp(3));
        clip.lineTo(l + dw - fold, t);
        clip.lineTo(l + dw, t + fold);
        clip.lineTo(l + dw, t + dh);
        clip.lineTo(l, t + dh);
        clip.close();
        c.drawPath(clip, paint);
        if (badgeText != null) {
            glyphText.setTextSize(badgeSize);
            glyphText.setColor(Colors.withAlpha(0xFF3A3A3C, a));
            glyphText.getFontMetrics(fm);
            c.drawText(badgeText, cx, cy + dh * .18f - (fm.ascent + fm.descent) / 2f, glyphText);
        }
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Paint strokePaint(float width, float a) {
        Paint s = stroke;
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeWidth(width);
        s.setColor(Colors.withAlpha(Colors.WHITE, a));
        return s;
    }
}
