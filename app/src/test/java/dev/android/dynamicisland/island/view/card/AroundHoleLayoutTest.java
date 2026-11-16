package dev.android.dynamicisland.island.view.card;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import dev.android.dynamicisland.island.graphics.Glyphs;
import dev.android.dynamicisland.island.graphics.Monogram;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandHost;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.testutil.RenderCapture;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Cards opened around the camera hole put their first row beside it and start their content right under it. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w384dp-h832dp-xxhdpi")
public class AroundHoleLayoutTest {
    private final Application app = RuntimeEnvironment.getApplication();
    private final IslandView view = new IslandView(app, IslandHost.NONE);
    private final float d = app.getResources().getDisplayMetrics().density;

    private static GradientDrawable dot(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize(48, 48);
        return g;
    }

    private void fit(CardBody body) {
        float pill = 30 * d, r = 5 * d, mid = pill / 2, cx = body.w / 2;
        body.fitHole(mid, cx - r - 14 * d, cx + r + 14 * d, mid + r + 4 * d, pill, pill + 6 * d);
    }

    private void render(CardBody body, String name) throws Exception {
        int w = Math.round(body.w), h = Math.round(body.h);
        Bitmap bmp = Bitmap.createBitmap(w + 40, h + 40, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF6A7B8C);
        c.translate(20, 20);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF111111);
        RectF frame = new RectF(0, 0, w, h);
        c.drawRoundRect(frame, Math.min(h / 2f, 40 * d), Math.min(h / 2f, 40 * d), p);
        body.drawBackground(c, frame, 1f);
        p.setColor(Color.BLACK);
        c.drawRect(0, 0, w, body.shadeBottom, p);
        body.draw(c, 0, body.topInset, 1f, 5000);
        p.setColor(0xFF303030);
        c.drawCircle(w / 2f, body.holeMid, 5 * d, p);
        RenderCapture.write(bmp, new File("build/renders/around_hole_" + name + ".png"));
    }

    @Test public void mediaPutsTheSourceBesideTheHoleAndTheTitleUnderIt() throws Exception {
        IslandItem it = new IslandItem("m");
        it.isMedia = true; it.kind = IslandItem.Kind.MEDIA; it.playing = true;
        it.title = "Blinding Lights"; it.subtitle = "The Weeknd"; it.appName = "Spotify";
        it.mediaOutput = "Galaxy Buds3 Pro"; it.mediaDuration = 200_000;
        MediaBody body = new MediaBody(view, it, 360 * d);
        float before = body.h;
        fit(body);
        assertTrue(body.holeRow);
        assertTrue("the empty band is gone", body.h <= before);
        render(body, "media");
    }

    @Test public void callShowsTimeAndBarsBesideTheHolePhotoNameAndButtonsUnder() throws Exception {
        IslandItem it = new IslandItem("call:x");
        it.kind = IslandItem.Kind.CALL; it.title = "Mario Rossi"; it.appName = "Telefono";
        it.trailing = IslandItem.Trailing.CHRONOMETER; it.chronoBase = System.currentTimeMillis() - 83_000;
        it.cardImage = Monogram.of("Mario Rossi"); it.cardImageRound = true; it.icon = dot(0xFF34C759);
        IslandItem.Action speaker = new IslandItem.Action("Altoparlante", null, null, () -> { }, false);
        speaker.glyph = Glyphs.SPEAKER;
        it.actions.add(speaker);
        it.actions.add(new IslandItem.Action("Termina", null, null, () -> { }, true));
        TimerBody body = new TimerBody(view, it, 360 * d);
        fit(body);
        assertTrue(body.holeRow);
        for (Btn b : body.buttons) assertTrue("buttons sit below the hole", b.r.top >= body.holeBottom);
        render(body, "call");
    }

    @Test public void ringingCallShowsCallerAndAnswerDecline() throws Exception {
        IslandItem it = new IslandItem("call:r");
        it.kind = IslandItem.Kind.CALL; it.title = "Mario Rossi"; it.appName = "Telefono"; it.callRinging = true;
        it.subtitle = "Chiamata in arrivo"; it.trailing = IslandItem.Trailing.TEXT; it.trailingText = "Mario Rossi";
        it.cardImage = Monogram.of("Mario Rossi"); it.cardImageRound = true; it.icon = it.cardImage;
        IslandItem.Action answer = new IslandItem.Action("Rispondi", null, null, () -> { }, false);
        answer.glyph = Glyphs.ANSWER; answer.affirmative = true;
        it.actions.add(answer);
        it.actions.add(new IslandItem.Action("Rifiuta", null, null, () -> { }, true));
        TimerBody body = new TimerBody(view, it, 360 * d);
        fit(body);
        assertTrue(body.holeRow);
        render(body, "ringing");
    }

    @Test public void systemEventIsAWidePill() throws Exception {
        IslandItem it = new IslandItem("e");
        it.kind = IslandItem.Kind.EVENT; it.eventLayout = IslandItem.EventLayout.STATUS;
        it.title = "Silenzioso"; it.icon = dot(0xFFFF3B30); it.accent = 0xFFFF9F0A;
        EventBody body = new EventBody(view, it, 360 * d);
        fit(body);
        assertTrue(body.holeRow);
        assertEquals(30 * d, body.h, 0.5f);
        render(body, "event");
    }

    @Test public void liveCardHeaderMovesBesideTheHole() throws Exception {
        IslandItem it = new IslandItem("l");
        it.kind = IslandItem.Kind.LIVE; it.appName = "Uber Eats"; it.title = "Il tuo ordine è in arrivo";
        it.subtitle = "Arrivo previsto 14:52"; it.progress = 0.6f; it.icon = dot(0xFF06C167);
        it.whenMillis = System.currentTimeMillis();
        LiveBody body = new LiveBody(view, it, 360 * d);
        fit(body);
        assertTrue(body.holeRow);
        render(body, "live");
    }

    /** Google Maps' real ProgressStyle notification (One UI 9, 2026-11-16) as the island turns it into an item. */
    static IslandItem mapsItem(String title, String chip, String critical) {
        IslandItem it = new IslandItem("oa:maps");
        it.kind = IslandItem.Kind.NAVIGATION; it.packageName = "com.google.android.apps.maps"; it.appName = "Maps";
        it.progressStyle = true; it.title = title; it.icon = dot(0xFFFFFFFF); it.iconIsImage = false;
        it.progress = 0.31f;
        it.progressSegmentStarts = new float[]{0f, 0.001f}; it.progressSegmentColors = new int[]{0xFFC9C9C8, 0xFF0F51FF};
        it.actionType = 1;
        it.actions.add(new IslandItem.Action("Esci dalla navigazione", null, null, () -> { }, false));
        android.os.Bundle extras = new android.os.Bundle();
        extras.putCharSequence("android.title", title);
        extras.putCharSequence("android.subText", "Arrivo: 13:01");
        extras.putCharSequence("android.shortCriticalText", critical);
        dev.android.dynamicisland.island.source.NavTestAccess.bind(it, extras, chip);
        return it;
    }

    @Test public void navigationReadsLikeATurnCard() throws Exception {
        IslandItem start = mapsItem("Esci dal parcheggio in direzione Via dell' Artigianato", "verso Via dell' Artigianato", "");
        assertEquals(null, start.trailingText == null ? null : start.trailingText.toString().startsWith("verso") ? "chip" : null);
        NavBody a = new NavBody(view, start, 360 * d);
        fit(a);
        render(a, "nav_start");
        IslandItem turn = mapsItem("Svolta a destra in Via Roma", "Via Roma", "300 m");
        assertEquals("300 m", turn.trailingText.toString());
        NavBody b = new NavBody(view, turn, 360 * d);
        fit(b);
        render(b, "nav_turn");
    }

    @Test public void sliderLabelBesideTheHoleTrackUnderIt() throws Exception {
        IslandItem it = new IslandItem("s");
        it.kind = IslandItem.Kind.EVENT; it.title = "Volume multimediale"; it.sliderValue = 0.45f;
        it.icon = dot(0xFFFFFFFF);
        it.actions.add(new IslandItem.Action("Spegni", null, null, () -> { }, false));
        SliderBody body = new SliderBody(view, it, 360 * d);
        fit(body);
        assertTrue(body.holeRow);
        for (Btn b : body.buttons) assertTrue("button below hole", b.r.top >= body.holeBottom);
        render(body, "slider");
    }

    @Test public void bandCardsDoNotDoubleTheTopGap() throws Exception {
        IslandItem bat = new IslandItem("b");
        bat.kind = IslandItem.Kind.EVENT; bat.title = "In carica"; bat.subtitle = "Pieno tra 42 min";
        bat.batteryPercent = 68; bat.batteryCharging = true; bat.accent = 0xFF3DB653;
        BatteryBody battery = new BatteryBody(view, bat, 360 * d);
        float plain = battery.h;
        fit(battery);
        assertTrue("gauge beside the hole, no band", battery.holeRow);
        assertTrue("grows less than a band", battery.h - plain < battery.holeBottom);
        render(battery, "battery");

        IslandItem pr = new IslandItem("p");
        pr.kind = IslandItem.Kind.EVENT; pr.title = "Download in corso"; pr.subtitle = "foto.zip · 12 MB di 40 MB";
        pr.progress = 0.3f; pr.icon = dot(0xFF0A84FF);
        ProgressBody progress = new ProgressBody(view, pr, 360 * d);
        fit(progress);
        assertTrue(progress.holeRow);
        render(progress, "progress");

        IslandItem bu = new IslandItem("bu");
        bu.kind = IslandItem.Kind.EVENT; bu.eventLayout = IslandItem.EventLayout.BUDS;
        bu.budsName = "Galaxy Buds3 Pro"; bu.title = "Connesse"; bu.batteryPercent = 80;
        bu.budsImage = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888);
        bu.budsImage.eraseColor(0xFFDDDDDD);
        BudsBody buds = new BudsBody(view, bu, 360 * d);
        fit(buds);
        assertTrue(buds.holeRow);
        render(buds, "buds");

        IslandItem rec = new IslandItem("r");
        rec.kind = IslandItem.Kind.EVENT; rec.trailingText = "1:24";
        IslandItem.Action pause = new IslandItem.Action("Pausa", null, null, () -> { }, false);
        pause.recordingControl = 1;
        IslandItem.Action stop = new IslandItem.Action("Stop", null, null, () -> { }, true);
        stop.recordingControl = 3;
        rec.actions.add(pause); rec.actions.add(stop);
        RecordingBody recording = new RecordingBody(view, rec, 360 * d);
        float recPlain = recording.h;
        fit(recording);
        assertTrue("recording row flanks the hole, no band", recording.holeRow);
        // like the call card: symbol and waveform flank the hole, time and buttons go under it
        assertTrue("taller by the hole row only", recording.h > recPlain && recording.h < recPlain + recording.holeBottom);
        for (Btn b : recording.buttons) assertTrue("buttons under the hole", b.r.top >= recording.holeBottom);
        render(recording, "recording");
    }
}
