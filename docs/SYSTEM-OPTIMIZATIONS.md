# System UI optimizations

Besides drawing the island, the module removes work that Samsung's System UI repeats for nothing. This page
lists every such change, why it is safe, how it was found and what it measured. All of it was profiled on a
Galaxy S25 Ultra (SM-S938B), One UI 9 / Android 17 (SDK 37).

Rules every optimization here follows:

- **Same result.** A hook only skips work whose result is provably identical to what is already there (a
  repeated value, an id that cannot change, a log line nobody reads). Nothing visible is approximated.
- **Optional.** Every hook is looked up by name and signature first. If One UI renames or changes the method,
  the hook does not attach and System UI keeps its stock behaviour. Failures inside a hook are contained by
  `SafeHook` and never reach System UI.
- **Measured.** Each change comes from a profile (simpleperf on System UI's main thread, perfetto, `dumpsys
  gfxinfo`) and was measured again after it went in.

## How the work was found

| Tool | Used for |
| --- | --- |
| `simpleperf record -t <System UI pid> -g` | Where System UI's main thread spends its CPU time, with Java call chains. Symbolised on the phone with `simpleperf report-sample --show-callchain`. |
| perfetto (`frametimeline`, `sched`, `atrace`) | Which frames missed their deadline and what the main thread was doing during them. |
| `dumpsys gfxinfo com.android.systemui` | Janky-frame percentage of a scripted, repeatable sequence (`input swipe`, power key). |
| `logcat` grouped by tag and pid | Log volume per tag: every line costs a JNI call, string building and a write to `logd`. |
| Decompiled System UI / AOD plugin | Reading what the hot methods do before deciding a hook is safe. |

Measurements are always taken with the phone idle except for the scripted input, since touching the phone
during a run skews the numbers.

## Notification shade and quick settings (`ShadePerf`)

| What System UI does | What the module does | Why it is safe |
| --- | --- | --- |
| Gets `trimMemory(TRIM_MEMORY_UI_HIDDEN)` every time the shade closes and frees the GPU caches, which the next open must rebuild (first frames of the next pull-down drop). | Swallows only that trim level (`WindowManagerGlobal.trimMemory`). | Real memory pressure arrives with other trim levels, which still pass. |
| Captures a stack trace for a debug string on every quick-settings animation frame (`QSAnimView.nowWithDebugCallers`) and logs the panel state on every frame (`QuickPanelLoggerHelper$PanelStateLogger.log`). | Returns an empty string / does nothing. | Debug output only. |
| Clears the QS blur view's blur info again (`semSetBlurInfo(null)`) when it is already cleared, forcing a relayout. | Skips the repeated clear; any non-null blur info always passes and re-arms it. | Same state before and after. |
| Decodes the quick-settings tile animation frames (`.webp`) again each time. | Keeps the decoded constant state of small frames (≤ 512 px, at most 32). | Same resource, same density key. |
| Writes thousands of info/debug lines per minute from a few chatty tags. | Drops lines of priority ≤ INFO from a fixed list of tags (`ShadePerf.NOISY_TAGS` / `NOISY_TAG_PREFIXES`). Warnings and errors always pass. | Diagnostic output only; no logic reads it. |

## Lock screen and AOD

Profile: 8 automatic screen off → AOD → on cycles, simpleperf on System UI's main thread plus `gfxinfo`.

### Log noise (`ShadePerf.NOISY_TAGS`)

The screen on/off animation logged per frame from the clock, face widgets, wallpaper colour area, clock blur
and AOD plugin (top tags: `SemWallpaperColorsArea` 5 271 lines, `DigitalHorizontalClockView_*` 4 636,
`[AOD]AppWidgetHost` 2 456 in one run). These tags were added to the list.

Cost of logging on the main thread during the transitions: **6.9 % → 2.6 %**.

### Resource ids by name (`ShadePerf.rememberResourceIds`)

`SecKeyguardClockPositionAlgorithm.setup` runs on every layout pass of the animation and looks its dimensions
up by name with `Resources.getIdentifier(name, type, package)`, a slow string search.

The module remembers the answers per `ResourcesImpl` instance (at most 512 names each, weakly held). An id
cannot change for a given `ResourcesImpl`; an overlay, theme or configuration change creates a new one, so the
cache starts empty again. A name that was not found (id 0) is never cached.

`getIdentifier` on the main thread: **4.5 % → 0 %**.

### Repeated widget colour (`LockWidgetColors`)

During the animation the AOD plugin's `KeyguardClockProvider` posts the clock date colour on every frame. Each
post runs `WidgetColorManager.createClockColorObserver$lambda$0`, which recolours every widget view,
recomputes the wallpaper colour of the widget area, re-reads the clock settings from JSON and the blur type,
only to find that nothing changed.

The module skips a post when the colour equals the one the manager already applied (`clockColor`) and the
previous post was less than 1 s ago (one animation). The first post of each screen on/off always runs in full,
and any real colour change always passes. The AOD classes live in the plugin's own class loader, so the hook
is attached when System UI creates it (`PluginInstance$PluginFactory.createClassLoader`).

About half of the posts are skipped (1 752 of 3 500 in one run); the other half carry a colour that really
changes during the animation. Cost of the colour observer: **−40 %**.

### Result

| Build | Janky frames (screen off ↔ on) | Main-thread CPU (relative) |
| --- | --- | --- |
| Stock | 8.87 % | 9.33 |
| + log noise and resource ids | 7.94 % | 8.13 |
| + repeated widget colour | 7.30 % / 6.99 % (two runs) | 7.42 |

## Lock screen editor (`LockEditLaunch`)

Long-pressing the lock screen opens Samsung's editor (`com.samsung.android.app.dressroom`). Before starting
it, `KeyguardEditModeControllerImpl.startEditActivity` captures and decodes the wallpaper preview
(`getWallpaperBitmap`) on System UI's main thread: a **113 ms** block during which the screen froze.

The module moves only that capture to a background thread (`di-lock-edit-preview`) and keeps Samsung's order:
capture → start the editor on the main thread → save the bitmap. Details:

- It acts only on the editor launch from a secured lock screen (`startEditActivity` with its secure flag, on
  the main thread, and the capture called from it). Other callers of the capture, such as the preview shown
  during the edit animation, run untouched.
- The editor start (`ActivityStarterImpl.postStartActivityDismissingKeyguard`) is deferred until the capture
  ends, and posted right after it (not after the save), so the PIN prompt appears as quickly as before.
- If the capture fails or takes longer than 1.5 s, the editor starts anyway, exactly once.
- If any of the four methods is missing or has a different signature, nothing is hooked.

The block on the main thread is gone (verified in perfetto: the decode runs on the background thread).

## Island itself (`ForegroundApp`)

A 10-minute profile of normal use showed that the island was a large part of System UI's own main-thread time.
Every island refresh asked system_server for the running tasks (`ActivityManager.getRunningTasks`), a binder
call plus the unparcelling of each task's `ActivityInfo` / `ApplicationInfo`, about 4–5 % of the main thread,
not counting the time spent waiting for the reply. With music playing the island refreshes often.

The module already hooks System UI's task-stack callbacks (`TaskStackChangeListeners$Impl`,
`TaskStackListenerImpl`: task moved, stack changed, focus changed, PiP) to refresh the island. Now:

- Each callback also bumps a task-change counter.
- A task snapshot is reused for at most **1 s**, and only while no task changed since it was taken.
- When a snapshot is reused, one recheck is scheduled for when it expires, so even a change the callbacks
  missed shows within 1 s.
- If the callbacks could not be hooked, or a query failed, every refresh queries as before.

Opening the player's app hides its pill immediately, as before.

## Checked and left alone

| Candidate | Why it was not changed |
| --- | --- |
| Island drawing (pill, music card, Lottie icon) | Already tuned (frame pacing, reused paints and shapes). The cost is spread over many small pieces; the only notable one, path merging in the playing icon (`LottiePlayer.merge`, ~1 %), runs on continuous time, so frames never repeat exactly. |
| Buds link check (`BudsSource.linkUp`) | The reflected method and the plugin drawables are already cached. The remaining cost is the Bluetooth service call, needed to drop the card as soon as the earbuds disconnect. |
| Raising Samsung's idle CPU cap (SSRM `cpufreq_max_limit`) for the lock screen | No measurable gain on lock screen frames. (For the shade it does help; that is done outside the module, see below.) |
| Launcher (One UI Home) | Logs heavily and re-reads a configuration value by reflection on every touch event (`GestureInputHandler.isClassicDex`), but the module is scoped to System UI only. |
| Other apps' log volume | Outside the module's scope. In 10 minutes the phone wrote ~500 000 log lines; e.g. My Files' background job (`SystemJobService`) alone wrote 75 000 (`FileUriConv`). |

## Outside the module

Some causes of lag cannot be fixed from inside System UI. On the test phone they were addressed with separate
KernelSU modules, which are not part of this project:

- **Idle CPU cap.** Samsung's SSRM keeps `/sys/devices/system/cpu/cpufreq_limit/cpufreq_max_limit` at
  `1401600` (about 1.55 GHz) while the phone is idle and lifts it only on app launches, not when the shade is
  pulled down. Raising it only while the shade is being pulled (and putting the exact original value back
  afterwards) took janky shade frames from 15 % to 8.5 %. Samsung's own `SemDvfsManager` cannot do this from
  System UI: the HyPer service only allows it the `KEYGUARD_UNLOCK` hint.
- **After boot.** The same cap is raised for a short window after boot so the first lock screen and status bar
  do not lag.

## Tests

Each change has unit tests under `app/src/test/java/dev/android/dynamicisland/`:

| Test | Covers |
| --- | --- |
| `xposed/hooks/ShadePerfTest` | Resource ids kept per `ResourcesImpl` and only when found; lock screen noise dropped while keyguard core logs stay. |
| `xposed/hooks/LockWidgetColorsTest` | First post runs, repeats within the window are skipped, colour changes and late posts pass, per-manager state. |
| `xposed/hooks/LockEditLaunchTest` | Capture → start → save order on the main thread, start exactly once, failed and stuck capture, other callers and threads untouched. |
| `island/ForegroundAppTest` | Snapshot reuse and its recheck, task change forces a query, no reuse without the callbacks, failed queries not reused. |

Run them with `./gradlew testReleaseUnitTest`.

## Adding a new optimization

1. Profile the case with the phone otherwise idle, and find the work on the main thread.
2. Read the decompiled code and prove the skipped work gives the same result.
3. Hook by name with `Refl` / `SafeHook`, check the signature, and fall back to stock behaviour when it
   differs. Register it with `runGroup(...)` in `SystemUiHooks.install`.
4. Add unit tests, then measure again with the same script and record the numbers here.
