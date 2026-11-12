<h1 align="center">Dynamic Island for One UI (fork)</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Android-15%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 15+">
  <img src="https://img.shields.io/badge/LSPosed-Xposed%20API%2093-6f42c1?style=flat-square" alt="LSPosed">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License"></a>
</p>

A personal fork of [sampple-korea/dynamicisland](https://github.com/sampple-korea/dynamicisland), an LSPosed module that replaces Samsung's Now Bar capsule with a pill that opens into cards. Work happens on the `dev` branch; `main` follows upstream.

## What's different

- **Samsung's music player on the island:** the media card follows the stock One UI player. It has:
  - the cover art across the whole card;
  - a centred "Title • Artist" line;
  - the output device and the player app's icon;
  - translucent round controls.

  It plays System UI's own Lottie animations: play/pause morphs, and prev/next arrows that slide out and back in. The seek bar is a live waveform up to a ringed thumb.
- **New cards:**
  - alarm, calendar, Galaxy Watch, car and workout, as Now Bar style cards with their own glyphs;
  - Galaxy Buds: battery for each side and the case, plus noise mode;
  - Quick Share: sending and receiving;
  - voice bars on the pill during an answered call.
- **Removed:** the Gemini integration and the experimental "Dynamic inset" style.
- **Optimizations:**
  - frame pacing that lets the panel slow down while only ambient motion runs;
  - less per-frame garbage: reused font metrics and shapes, and resource IDs resolved once instead of every frame;
  - steadier rolling numbers in the clocks;
  - a still waveform in low-performance mode.
- **Other changes:** iMode paging refinements and a partial Italian translation.

## Requirements

- A Samsung device on One UI 7 / 8 / 9 (Android 15 / 16 / 17) with the stock System UI. See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).
- LSPosed.
- Root only for the optional **Restart System UI** button.

## Install

1. Build the APK (below) and install it: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. In LSPosed, enable **Dynamic Island** and set its scope to **System UI** (`com.android.systemui`).
3. Restart System UI (the app's **Restart System UI** button, or reboot).

## Build from source

Requires JDK 17+ and the Android SDK (compile SDK 36).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests and headless render tests
```

See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for the source layout, hook notes, rules format and test setup.

## If it does not work

Open **Advanced & troubleshooting → Diagnostics** in the app after restarting System UI. It lists:
- the System UI version and the start-up stages;
- hooks that could not attach;
- errors and the previous crash.

## Privacy

The module runs inside System UI and reads the notifications System UI already holds. It has no network permission and sends nothing anywhere. The optional microphone waveform uses short in-memory buffers to compute levels. It is off by default, and audio is never stored or transmitted.

## Disclaimer

This is an independent, unofficial project. It is not affiliated with or endorsed by Samsung. It hooks private System UI classes that may change with any update. Use it at your own risk.

## License and credits

[MIT](LICENSE) © 2026 sampple-korea, the original author. Changes in this fork are under the same license.

- [sampple-korea/dynamicisland](https://github.com/sampple-korea/dynamicisland): the original project.
- [LiveBridge](https://github.com/appsfolder/livebridge): the rule file format the module reads.
- [Xposed API](https://api.xposed.info/) (Apache-2.0) and [LSPosed](https://github.com/LSPosed/LSPosed).
- DynamicIsland by @cryyear, which the original project began as a modification of; and [@UnkilBonki](https://t.me/UnkilBonki), who contributed to the original project.
