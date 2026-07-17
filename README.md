# mosconi-bt

An unofficial, modern replacement for MOSCONI's "Mos_DSP_control_App" — the Android app
used to control MOSCONI PICO-series car-audio DSPs (tested against a PICO V2 6|8) over
Bluetooth. Native Kotlin + Jetpack Compose, Material 3 sliders instead of drag-image
controls, and support for any screen size (including split-screen and unusually
wide/short displays).

## Why this exists

The factory app hasn't been updated in years and its UI (drag-and-drop image sliders)
is unpleasant to use. MOSCONI doesn't publish the Bluetooth protocol, so this project
reverse-engineered it by decompiling the factory APK — see [PROTOCOL.md](PROTOCOL.md)
for the full writeup, including which parts are verified vs. still assumptions.

## Project layout

- **`:protocol`** — pure Kotlin/JVM module with no Android dependency. Contains
  `MosconiProtocol.kt`, the packet-building logic reverse-engineered from the factory
  app, plus unit tests. Buildable and testable with plain Gradle + Maven Central; no
  Android SDK required.
- **`:app`** — the Android application: Compose UI, classic-Bluetooth (RFCOMM/SPP)
  transport, and a small ViewModel wiring the two together.

## Building

```
./gradlew :protocol:test   # pure-JVM protocol logic + tests, no Android SDK needed
./gradlew :app:assembleDebug   # needs the Android SDK + Google's Maven repo
```

Open the project root in Android Studio (Koala or newer) and it will pick up both
modules automatically. `:app` needs `compileSdk 34` / a recent Android SDK installed
via Android Studio's SDK Manager.

> **Note on how this was built:** the sandbox this project was authored in blocks
> `dl.google.com` (Google's Maven repo, which hosts AndroidX/Compose/the Android Gradle
> Plugin) by network policy, so only `:protocol` could actually be compiled and tested
> there. `:app`'s Compose/Bluetooth code was written and carefully reviewed by hand but
> has **not been compiled**. Build it in Android Studio first and expect to fix minor
> issues (an import path, an API signature drift between library versions) before your
> first successful build.

## Setting up the DSP

1. Pair the DSP's Bluetooth module with your phone the normal way, in Android's
   Bluetooth settings (it should show up with a name containing "MOSCONI").
2. Open the app, grant the Bluetooth permission when asked, and tap the paired device
   to connect.

## Known unknowns — verify against real hardware

Static analysis of the decompiled app tells you *what bytes get sent*, not which
physical control position the original UI author intended for "loud" vs "quiet." Two
things are flagged in `MosconiProtocol.kt` as assumptions to check the first time you
use this against a real unit:

- **Volume slider direction** — whether the low end of the slider should be quiet or
  loud (i.e. whether `LOG_VOLUME_TABLE` needs reversing).
- **"Listening position" (Geo) axis orientation** — which edge is left/right and
  front/rear.

Both are one-line fixes (see the comments in `MosconiProtocol.kt`) once you've
confirmed the actual behavior on your DSP.

## Contributing back

If you capture a Bluetooth HCI snoop log (Android Developer Options → "Enable
Bluetooth HCI snoop log") while operating the *factory* app and it reveals commands
this app doesn't cover yet (firmware updates, additional DSP features, etc.), please
open an issue/PR — the current feature set only covers what `Screen1.smali` exposed:
output/input volume, sub level, listening position X/Y, 4 presets, and treble/mid/bass.
