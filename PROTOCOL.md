# MOSCONI DSP Bluetooth protocol

Reverse-engineered from `Mos_DSP_control_App` (package
`appinventor.ai_carhifistoremob.Mos_DSP_control_App`) by static analysis of the
decompiled APK. No live Bluetooth capture against real hardware was performed — see
[Confidence](#confidence--open-questions) at the bottom.

## How the app was decompiled

The APK turned out to be built with **MIT App Inventor** rather than hand-written
Java/Kotlin: `AndroidManifest.xml` names `com.google.appinventor.components.runtime.multidex.MultiDexApplication`
as the `Application` class, and the entire app's logic lives in one generated class,
`Screen1`, compiled from App Inventor "blocks" to JVM bytecode via the Kawa Scheme
compiler. That compilation style deduplicates every literal (string/number/symbol) in
the program into a static field named `LitN`, and expresses control flow as generic
`gnu.mapping`/`gnu.expr` interpreter calls — so a straight decompile is close to
unreadable.

Steps taken:

1. `apktool d` to get baksmali'd `Screen1.smali` plus resources/manifest.
2. `enjarify` (dex → jar) + `procyon` (jar → Java) for a friendlier control-flow view of
   the same class.
3. A small Python script (`resolve_lits.py`, not checked in) parsed `Screen1.smali`'s
   `<clinit>` to build a `LitN -> literal value` table (~640 of ~800 literals resolved:
   `SimpleSymbol`/`String` names, `IntNum` integers, booleans), then substituted those
   values back into the Procyon output as inline comments. That turned opaque call
   chains like `runtime.callComponentMethod(Lit77, Lit78, ...)` into
   `runtime.callComponentMethod(/*'TinyDB1'*/, /*'GetValue'*/, ...)`, which is
   straightforward to read.
4. Traced every event handler (`Slider_Vol$Dragged`, `Button_P1$LongClick`,
   `Clock1$Timer`, etc.) by hand from there.

## Transport

- **Classic Bluetooth RFCOMM (SPP)**, not BLE. The app's only Bluetooth component is
  App Inventor's `BluetoothClient`, which is RFCOMM-only.
- Standard SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`.
- The DSP must already be paired via Android's system Bluetooth settings (the app only
  lists already-bonded devices; it never runs discovery).
- No handshake beyond the socket connect. No checksum anywhere in the protocol.
- After every command, the DSP sends back exactly 1 byte. The factory app reads and
  discards it (`ReceiveUnsigned1ByteNumber`, result stored but never inspected) — so it
  functions as a transmit-buffer-clearing step, not an application-level ack.

## Frame format

Every command is a fixed **8-byte** frame:

```
byte:  0    1    2      3      4      5      6      7
      len  cmd  ...................payload...................
```

Byte 0 is always `0x08` (the frame length, including itself). Byte 1 selects which of
two command families this is.

### Volume-family frame (`cmd = 0x43`)

```
[0x08, 0x43, VOL, SUB, GEO_X, GEO_Y, PRESET, TARGET]
```

| Byte | Meaning | Range | Written by |
|---|---|---|---|
| 2 | Volume level | looked up from `LOG_VOLUME_TABLE` (see below) | main Volume slider |
| 3 | Sub level | 0–15, raw | Sub slider |
| 4 | "Geo" X | 48 + (0–32) → 48–80 | Listening-position X slider |
| 5 | "Geo" Y | 48 + (0–32) → 48–80 | Listening-position Y slider |
| 6 | Preset index | 0–3 | Preset buttons P1–P4 |
| 7 | Target flag | `0x00` = output volume, `0x80` = input/CAN-bus volume | Input/Output switch |

The app keeps **two independent 8-byte buffers** for this command — one for
"output volume" mode (`TARGET=0x00`) and one for "input volume" mode (`TARGET=0x80`).
Moving the Sub, Geo, or Preset controls always patches and resends the *output* buffer,
regardless of which volume mode is currently selected; only the Volume slider itself
decides which of the two buffers gets its byte 2 updated and sent. The input-mode
buffer's sub/geo/preset bytes are therefore always its compile-time defaults
(`15, 64, 64, 0`) and never meaningfully used — this looks like a quirk/bug in the
original app rather than intentional behavior, but this project reproduces it exactly
for fidelity (see `MosconiProtocol.State`).

Defaults (before any control has been touched in a session): volume=`0`, sub=`15`,
geoX=`64`, geoY=`64`, preset=`0`.

### Tone frame (`cmd = 0x44`)

```
[0x08, 0x44, TREBLE, MID, BASS, 0x00, 0x00, 0x00]
```

Treble/Mid/Bass are each raw `0–15`. Default `8` (flat/center) for all three.

### Volume lookup table

The Volume slider doesn't send its raw 0–35 position; it looks it up in a 36-entry
non-linear ("audio taper") table, extracted verbatim from the app's compiled integer
literals:

```
index:  0    1    2    3    4    5    6   7   8   9  10  11  12  13  14  15
value: 210  187  149  133  119  107  95  85  76  68  61  54  48  43  38  34

index: 16  17  18  19  20  21  22  23  24  25  26  27  28  29  30  31
value: 31  27  24  22  19  17  15  13  12  11   9   8   7   6   5   4

index: 32  33  34  35
value:  3   2   1   0
```

i.e. `LOG_VOLUME_TABLE[slider_position]`, monotonically decreasing 210 → 0.

## Not part of the wire protocol (local-only app state)

These UI elements only affect the App Inventor `TinyDB` local key-value store (i.e.
`SharedPreferences`) and are **never transmitted**:

- "Enable Preset" toggles per preset slot (gates whether long-pressing a preset button
  does anything in the factory UI — purely a local safety guard against activating an
  unprogrammed preset).
- The "Feedback" switch — controls local vibration-on-drag only.

This project's replacement app keeps local persistence for slider positions
(mirroring `TinyDB`) but drops the preset-enable gate as unnecessary complexity now
that the UI uses direct tap-to-select instead of App Inventor's canvas-drag paradigm.

## Confidence & open questions

Everything above describes **what bytes the factory app sends for a given UI state**,
reconstructed purely by reading decompiled code — there was no Bluetooth capture
against a real DSP to confirm the device's interpretation. Two things are genuinely
ambiguous from static analysis alone and need a quick check against real hardware:

1. **Which end of the Volume slider is loud.** The decompiled canvas math shows *a*
   slider position maps to *a* table index, but not which physical direction the
   original artwork implied. If it's backwards, reverse `LOG_VOLUME_TABLE`.
2. **"Geo" X/Y axis orientation** (which edge is left/right, front/rear). Labeled as
   "listening position" in the new app based on this being a common feature on
   Mosconi-class DSPs, but the exact semantics of "Geo" were not confirmed from the
   app's UI text.

Both are one-line fixes once confirmed; see the `ASSUMPTION` comments in
`protocol/src/main/kotlin/dev/x3n0n10/mosconibt/protocol/MosconiProtocol.kt`.
