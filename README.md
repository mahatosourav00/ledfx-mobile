# LedFx Mobile

**Music-reactive LED light shows, made on your Android phone.**

LedFx Mobile listens to music, either through the microphone or from apps playing on the phone
(Spotify, YouTube, …). It turns the sound into real-time effects and streams them over Wi‑Fi to
[WLED](https://kno.wled.ge/) LED controllers at 60 frames per second. It's a native Android app
inspired by [LedFx](https://github.com/LedFx/LedFx). It brings LedFx's effects, settings and
presets to the phone, so you don't need a PC running in the background.

> Unofficial project. It isn't affiliated with or endorsed by the LedFx team.

## Features

- **41 effects.** All 39 of LedFx's strip (1D) effects are ported from its source, plus Ripples and Sparkle:
  | Category | Effects |
  |---|---|
  | Classic | Energy, Spectrum, Wavelength, Scroll, Scroll+, Power, Magnitude, Pitch Spectrum, Blade Power+, Rain, Strobe, Scan, Scan and Flare, Scan Multi, Spotlight |
  | Beat synced | Bar, Multicolor Bar, BPM Strobe |
  | Atmospheric | Fire, Block Reflections, Crawler, Energy 2, Glitch, Lava lamp, Marching, Melt, Melt and Sparkle, Water |
  | Simple | Filter, Hierarchy, VU Meter |
  | Bands | Bands, Bands Matrix, Blocks, Equalizer |
  | No audio | Gradient, Fade, Rainbow, Single Color, Random Flash |
  | Extras | Ripples, Sparkle |
- **LedFx settings for every effect.** Each effect has the same options, defaults and ranges as
  LedFx, plus blur, mirror, reverse and gradient roll. Each effect remembers its own settings.
- **98 LedFx presets** built in. You can also save your own.
- **Colours:** LedFx's 13 gradients (Dancefloor, Plasma, Borealis, Winamp, …), 8 extra palettes,
  and a colour picker.
- **Audio engine:** 32-band spectrum, bass/mid/high levels, kick and onset detection, a tempo
  tracker for beat-synced effects, and pitch detection.
- **WLED:** found automatically on your Wi‑Fi (mDNS), or added by IP address. Pixels are sent over
  DDP, and long strips are split across packets.
- **The WLED web page opens inside the app.** Streaming to that device pauses while you're on the
  page, so you can change WLED settings.
- **Brightness:** a master slider for all lights, and a slider on each device.
- **Virtual strips** preview any effect on the phone screen without LED hardware.
- **Background mode:** runs as a foreground service with the screen off, with a Stop button in
  the notification.

## Install

1. Download the latest `LedFxMobile-*.apk` from **[Releases](../../releases/latest)**.
2. Open it on your phone and allow installing from this source when Android asks.
3. Requires **Android 10 or newer**.

## Quick start

1. Open the app and allow the **microphone** and **notification** permissions.
2. On the **Lights** tab, the *Phone preview* strip already reacts to sound.
3. Tap **Add device** to find WLED controllers on the same Wi‑Fi, or enter an IP address.
4. Tap a device to pick an **effect**, try its **presets**, choose **colours**, and adjust its
   **settings**.
5. On the **Audio** tab, choose **Microphone** or **Phone audio**. Phone audio reacts to music
   playing on the phone; Android asks once for permission.

> Some apps block audio capture, for example certain DRM-protected streaming apps. Use the
> microphone for those.

## Building

You need Android Studio, or JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # engine and effect tests
```

Release builds are signed when a `keystore.properties` file exists in the project root
(it's ignored by git):
```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## How it works

```
Mic / phone audio ─► AudioCapture ─► AudioAnalyzer (FFT, bands, beats, tempo, pitch)
                                            │  60 fps
                                            ▼
                        LightEngine ─► effect per device ─► blur / mirror / flip / brightness
                                            │                          │
                                     on-screen previews        DDP over UDP ─► WLED
```

| Path | Contents |
|---|---|
| `app/src/main/java/.../audio` | Audio capture, FFT and the analyser |
| `.../effects` | Effect catalogue, implementations, settings framework, palettes, presets |
| `.../engine` | Render loop, devices, presets and persistence |
| `.../net` | DDP sender, WLED discovery and JSON API |
| `.../service` | Foreground service and the phone-audio permission screen |
| `.../ui` | Jetpack Compose screens |
| `tools/convert_ledfx_presets.py` | Regenerates `LedFxPresets.kt` from LedFx's `presets.py` |

To add an effect, write a class in one of the `effects/Effects*.kt` files and register an
`EffectInfo` (name, category, settings) in `Effects.all`. The settings screen is built from that
entry automatically.

## Roadmap

- LedFx 2D matrix effects (Flame, Plasma, Digital Rain, GIFs, …) with an LED panel layout
- More output protocols (E1.31/sACN, Art-Net)
- Scenes across several devices

## Credits and licence

Effects, setting names and built-in presets are adapted from
[LedFx](https://github.com/LedFx/LedFx) (GPL-3.0) by the LedFx developers. This project is
therefore licensed under the **GNU General Public License v3.0**; see [LICENSE](LICENSE).
