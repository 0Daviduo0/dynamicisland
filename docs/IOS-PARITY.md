# iOS parity work (islandcn study, status bar reaction, expanding around the camera)

Working notes for the effort to make the island behave like the iOS Dynamic Island. Started from a study of
[islandcn](https://github.com/opencoredev/islandcn) (a web recreation that models Live Activities as slots).
Everything below is in the working tree on `dev`; nothing has been committed yet. Last update: 2026-11-14.

## Status

| Area | State |
|------|-------|
| `LiveSlots` model + builder | Done, tested; not yet used by a renderer |
| Status bar icons react to the island | Done, verified on device |
| Spring on pill resize | Done, verified on device |
| Expand around the camera hole (option) | Done; layout rework installed, **not yet verified on device** |
| Collapse frame rate after a long open | Fix installed, **not yet verified with a new recording** |
| Call: contact photo, waveform while expanded | Done, **not tested with a real call** |
| Concentric card corners (option) | Done, not yet verified on device |
| Pill hidden while a card is open around the hole (iMotion) | Done, **not yet verified on device** |

## 1. Live Activity slots (from islandcn)

islandcn describes an activity as slots, like ActivityKit: `compactLeading`, `compactTrailing`, `minimal`,
`expandedLeading`, `expandedTrailing`, `expandedCenter`, `expandedBottom`. Our island builds each card
type by hand from `IslandItem`; the slot model is the bridge for a future generic renderer.

- `island/model/LiveSlots.java`: the slots plus `Reach` (NONE, INSET, ACTION). Slot kinds: `Icon`, `Image`,
  `Text`, `Lines`, `Chrono`, `Ring`, `Bar` (plain or segmented, with thumb), `Actions`.
- `island/model/LiveSlotsBuilder.java`: `LiveSlots build(IslandItem)`. Teams become left/right images; the
  trailing slot is text, chronometer, icon or a progress ring; the ring stays compact and becomes a bar when
  expanded (never both); the bottom holds the bar and the actions.
- Test: `LiveSlotsBuilderTest` (10 cases).
- Next: a renderer that draws any activity from its `LiveSlots`, replacing the per-type bodies where possible.

## 2. Status bar reacts to the island

Measured frame by frame on an iPhone 15 recording (https://www.youtube.com/watch?v=kxELrD0hN_k): when the
island grows, the icons shrink at once, a blur joins only after a threshold, and they fade last while sliding
away. They come back about 100 ms after the island starts shrinking, over about 300 ms.

- `island/window/IconExit.java` (replaces `IconAlphaFade`): one progress `p` drives
  - scale: 1 → 0.7;
  - blur: from p = 0.3, `((p - 0.3) / 0.7)^1.5`, up to 6dp;
  - alpha: from p = 0.45, `1 - t²`;
  - slide: 10dp outward.

  Out 220 ms, in 300 ms after 100 ms. The pivot is the outer edge, so a view never grows back into the
  island. Views that are only pushed keep System UI's own alpha (`alphaOwned`).
- `island/window/IconNudge.java`: Choreographer spring (response 0.3, damping 0.55) for the sideways push. The
  icon lags a fast edge, overshoots, settles. Its speed becomes a squeeze (shrink 12%, blur from 35% squeeze,
  2.5dp max).
- `island/window/StatusBarIconFader.java`:
  - Leaf views (clock etc.) are pushed up to 12dp, then leave.
  - The `system_icons` cluster moves as one block: translation, scale with the pivot at its right edge, blur.
  - Ancestors are unclipped while it moves (the battery used to be cut off).
  - Resting bounds are measured without our own transforms, so the battery no longer hesitates between
    leaving and staying.
- `graphics/Motion.java`: `PILL_RESIZE` = response 0.38, damping 0.58 (bouncier pill expansion; the
  icons react to the bounce).
- Test: `IconExitTest`.

Possible next: drop priority for status icons (network → Wi‑Fi → battery) when space runs out.

## 3. Expand around the camera hole (Appearance → expansion position)

Setting `expand_around_hole` (`ModuleSettings.expandAroundHole()`), strings `expand_position`, `expand_below`,
`expand_around` in all locales. Off = old behaviour (the card opens below the hole).

The card grows from the pill's own top (`Metrics.stackTop()`). It is as wide as the screen minus the same gap
the pill has from the top (`aroundHoleGap()`), so the top and side gaps are equal (confirmed on device).
`Footprint` counts the cards that overlap the pill row as covered.

### Layout beside the hole

The idea comes from iOS: leading and trailing content sits next to the camera, and the rest starts right
under it, with no empty black stripe.

- `Metrics.fitAroundHole(body)` passes the real hole geometry, in body coordinates, to the first card:
  - the row centre (the hole centre);
  - the lane edges, at the hole radius + 14dp on each side;
  - the hole bottom, at the hole radius + 4dp below the centre;
  - the pill height.

  Called from `Director.layoutCards` and `IMode.targetExpanded`.
- `CardBody.fitHole(...)` asks the body to `layoutBesideHole()`, which returns the new height (or -1 to fall
  back to a band). Bodies move their buttons with `shiftButtons(dy)` and draw their rows themselves. Fields:
  `holeRow`, `holeMid`, `holeLeft`, `holeRight`, `holeBottom`, `holePillH`, `shadeBottom`. Helpers
  `leftLane(inset)` and `rightLane(inset)`.
- Fallback (bodies that do not implement it: battery, progress, recording, Buds, torch, Quick Share, Now, RemoteViews):
  - a black band above the content, minus the body's own top padding (`bandOverlap()`), so the gap under
    the band is not doubled (`insetTop(band - bandOverlap())`, `shadeBottom = band`);
  - `StackPainter.drawHoleRow` draws the compact content (`CompactContent`) in it;
  - rows that centre vertically (recording, timer fallback, event fallback) centre on `h - topInset`;
  - seek bar and slider touches subtract `topInset` (`TouchController`), so they hit the drawn track.
- `StackPainter.drawHoleBand` paints black down to `shadeBottom`, then a fade. The fade is 16dp after a
  beside-the-hole row and 34dp after a band. The band scales with the card while it opens.

Per body:

| Body | Beside the hole | Under the hole |
|------|-----------------|----------------|
| `MediaBody` | output device / app name left, app glyph right | title row moved up (card ~10dp shorter); cover drawn unchanged, starting under the black (`drawBackdrop` at `y = shadeBottom`, clipped) |
| `TimerBody` (calls) | running time in green left, voice bars right (ringing: app name left) | photo 46dp, name + app / "incoming" line, buttons; no duplicated timer |
| `TimerBody` (timers) | label left | icon, big time, buttons (stacked layouts keep the band) |
| `EventBody` (ringer, silent, DND, devices) | symbol left, name (+ gauge) right | becomes a wide pill as tall as the compact one, like the iOS silent-mode alert; a subtitle adds one line |
| `LiveBody` | app glyph + name left, live value or time right | title / text / progress / buttons start under the hole (sports cards keep the band) |
| `ClockBody` (Samsung timer / stopwatch) | the row itself flanks the hole: icon + big time left (time stops short of the hole), buttons right | second line, bar, laps; no band and no duplicated compact row (card 207px instead of 320px on the S25 Ultra); the black melts over the whole card (`shadeFade`) |

Verified on device 2026-11-14 (S25 Ultra, screenshots + screenrecord): media and Samsung timer cards.
`MediaBody` around the hole: 6dp more under the controls (bottom margin 14dp, was 8dp) and the title line uses
the full width (the app glyph is beside the hole, not in the title row). `CardBody.shadeFade` lets a body set
the fade length; the gradient in `StackPainter.drawHoleBand` is built once and stretched (no allocation per frame).

`SliderBody` (plain sliders only; Buds and torch keep the band): label left of the hole, track under it; the
power button moves to the track's row and the track shortens to make room. Renders: `around_hole_slider.png`,
`around_hole_battery.png`, `around_hole_recording.png`.

Render check: `AroundHoleLayoutTest`. Run it with
`./gradlew :app:testDebugUnitTest --tests '*AroundHoleLayoutTest' -Prender -Drender=true`; the pictures go to
`app/build/renders/around_hole_*.png`.

### iMotion: no pill on top of an open card

With the card opened around the hole, the card *is* the island. `IMode.layout` empties the compact row while
the card is open, so the other activities' pills fade out and come back on collapse (as on iOS).

### Cover history

User feedback: the cover must not start under the hole or the black. The first fix shrank the drawing area
and was wrong, because the art is centre-cropped. The second shifted the crop window (`MediaFx.coverShift`,
`ART_SHIFT`), which is still used in band mode. The current version draws the cover unchanged, just lower.

## 4. Collapse after a long open looked sharp

Measured on the user's recording (`collapse2.mp4`, ffmpeg showinfo pts, dark-area per frame):
- after a short open the collapse ran at ~8 ms per frame (120 Hz);
- after a 6–7 s open it ran at ~16.7 ms (60 Hz) for the whole animation.

The cause: while open and idle the island only asks for the 30 fps ambient rate, and the LTPO panel drops to
60 Hz. The high-rate vote came after the first frame, without an explicit rate, and as "only if seamless",
which One UI refuses. Short opens still had 120 Hz from the touch that opened them.

Fix in `IslandView.scheduleFrame` / `requestRate`:
- vote `REQUESTED_FRAME_RATE_CATEGORY_HIGH` as soon as an interactive animation is scheduled;
- vote on the surface for `displayMaxRate()` with `CHANGE_FRAME_RATE_ALWAYS` when high (`ONLY_IF_SEAMLESS`
  otherwise);
- replace a queued paced ambient frame with an immediate one (`pacedFrameQueued`).

To verify, record again with `adb shell screenrecord` and check that the long-open collapse runs at ~8 ms.

## 5. Calls

- Contact photo, in this order:
  - the address book (`ContactPhotos`, System UI holds READ_CONTACTS): by the CallStyle person URI (`tel:` or
    contact lookup), else by the shown number or exact name; looked up off the main thread, cached, and the
    card refreshes when it arrives;
  - the CallStyle `EXTRA_CALL_PERSON` icon (`CallCardBuilder.callerPhoto`);
  - the now bar icon (it can be the dialer logo, which is why the person icon now comes first);
  - the notification large icon (passed through `IconContrast.readable`);
  - else `graphics/Monogram` (initials on a grey disc, as iOS shows contacts without a photo).
- Ringing around the hole: the app name sits left of the hole (the caller's name was shown there twice, in
  green); an unknown caller reads "Incoming call" with the app under it. Render: `around_hole_ringing.png`.
- The voice bars froze while expanded: `IslandView.callWaveTarget()` only looked at compact content. It now
  also checks open cards, so audio capture stays on.

## 6. Concentric card corners

Setting `concentric_corners`, under Appearance → card corners, before the custom radius (which is hidden
while this is on). The radius is the screen corner radius (`Display.getRoundedCorner`, else the
`rounded_corner_radius*` dimens, else 44dp) minus the gap between the card and the screen edge, at least
10dp (`IslandView.concentricCornerRadius`, used by `CardBody.cornerRadius`). `CardShapeSettingsTest` was
updated for the new switch.

## Open questions / next steps

- Ask the user which activities should not expand on tap (on iOS a timer tap opens the app; a long press expands).
- Check on device: the media layout, a call (photo, bars, buttons), silent / DND as a wide pill, the hidden
  pill, concentric corners.
- Bring the remaining band-mode bodies (Buds, slider, Quick Share, RemoteViews) to the beside-the-hole layout.
- Opening a media card: for one or two frames the cover is visible beside the hole before the black band is tall enough.
- A renderer driven by `LiveSlots`.
- Record a real iPhone expansion (music, call) to measure its spring and timing.
- Possible duplicate trailing text in `LiveBody` under the hole row (to check).
- `LottieGoldenTest` (`battery_charging` hash) already fails without these changes.

## References

- Apple HIG, Live Activities: https://developer.apple.com/design/human-interface-guidelines/live-activities
  (regions, even margins, concentric corners, nothing touching the camera, no empty space).
- ActivityKit, `DynamicIslandExpandedRegion`: https://developer.apple.com/documentation/activitykit
- WWDC23 "Design dynamic Live Activities".
- islandcn: https://github.com/opencoredev/islandcn
- iPhone status bar reaction video: https://www.youtube.com/watch?v=kxELrD0hN_k

## Activities that only bounce, charging, taken-over pop-ups (2026-11-14)

- `IslandItem.opensCard()`: system events (`event:*` with BATTERY / STATUS layout) without buttons, details or
  slider do not open; a tap bounces the pill (`IMode.bounce`, `sp.pulse` outside iMotion) with a haptic tick.
- Charging: compact uses a One UI pill battery (`OneUiBattery`), the card shows state + battery left of the hole,
  big green level right of it, thick bar under it. While the island shows the battery, the status bar's battery
  (on One UI 9 an unnamed `ComposeView`, last child of `system_icons`) leaves with the usual icon exit and the
  system icons block slides right into its place (`StatusBarIconFader.setHideBattery`, `batteryGapShift`).
- Recording, battery, progress, Buds lay out beside the hole too (no band, no compact row repeated above).
- Taken-over pop-ups (`AlertBridge`, `AlertWindowBridge`, `AlertSource`): Samsung Clock's "timer ended" window
  (`KEYGUARD_DIALOG`, `ClockAlertHooks`) and EasySetup's earbuds `connectScreen` are kept invisible and untouchable;
  the island opens a card at once with the pop-up's own buttons and presses them on tap. System UI acks; without an
  ack (island hidden) the stock pop-up comes back after 900 ms. On the lock screen Clock's window stays stock.
  Needs Clock in the LSPosed scope (added). Verified on device: timer ends → card opens, Stop works, mini pop-up over
  an app stays hidden. Earbuds connect prompt: not verified yet.
- Timers: no bar under the card; a ring round the hourglass (card and compact) empties as time runs, all red once
  it ran out; the compact shows "-0:12" in red after the end.

## Design philosophy, from Apple's own guidance (2026-11-16)

Sources: WWDC23 "Design dynamic Live Activities" (session 10194) and the HIG Live Activities sizes (compact
62.33x36.67 pt per side, expanded 408 x 84–160 pt on a 430 pt screen).

- **No forehead.** "Avoid having a 'forehead' at the top that calls attention to the sensor region. Hug the
  sensor as tightly as possible and wrap content around it." Content starts right under the camera; the band
  beside it stays empty unless a tall element (artwork, an arrow) naturally reaches up there.
- **No labels in the top corners.** The app name is not shown: identity comes from the icon, the shape and the
  colour. A value is never repeated in two places (corner + title + subtitle).
- **Compact = the essence, as narrow as possible.** "No wasted space… consider truncating or shrinking units,
  or showing a less precise version of your data." A distance, a time, a score: never a sentence.
- **Keep the relative placement** between compact and expanded: what is leading in the pill is leading in the card.
- **Hierarchy over quantity.** One hero element (big glyph, artwork, number), one bold line, one secondary line.
  Large, heavy, easy-to-read text; the rest is secondary grey.
- **Concentric margins**, even all around; separate blocks with an inset shape, not by drawing to the edge.
- **Height is either pill-like or tall**, never in between.
- Buttons are small and round where possible (iOS timer: two circles), not full-width rows.

### Navigation (first card redone this way)

- `model/NavFacts`: pulls distance (`shortCriticalText`, or a title like "300 m"), instruction, the road (chip
  text found in the instruction, drawn bold) and the arrival time ("Arrivo: 13:01" → minutes left).
- Compact: arrow + distance, else minutes left; never the road sentence (it made the pill screen-wide).
- `view/card/NavBody`: 50dp arrow leading, distance bold / instruction with bold road / "Arrival 13:01 · 12 min",
  thin route line with a dot, a round × that ends the route. Nothing beside the camera. Verified on device with
  Google Maps (One UI 9); the × ends navigation.

### All cards brought to it (2026-11-16)

| Card | Before (beside the camera) | Now |
|------|----------------------------|-----|
| `LiveBody` (generic live activities) | app glyph + app name left, value / time right, value repeated in the subtitle | nothing beside the camera; 40dp symbol leading, title, grey secondary line, the live value at the end of the title row only when the text does not contain it and it is short (≤ 12 chars) |
| `TimerBody` call | running time left, voice bars right | photo, name, then the green time with the voice bars on the line under it |
| `TimerBody` ringing / timer | app name or label left | photo/symbol, name + "Incoming call" / big time + label |
| `MediaBody` | output device name left, app glyph right | only symbols: output icon left, app glyph right |
| `SliderBody` | name left | nothing beside the camera; the bar's own symbol says what it is |
| `NavBody` | — | see above |

Kept: event / charging wide pill (symbol left, state right: that is the iOS silent-mode / charging alert), the Samsung
Clock row (icon + big time hug the camera), recording and Buds (no text beside the camera).
