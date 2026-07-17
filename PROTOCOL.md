# MOSCONI DSP Bluetooth protocol

Reverse-engineered from two independent sources: `Mos_DSP_control_App` (the factory
Android app; write-only) and the official Windows tuning GUI (`MOSCONI GLADEN GUI
V3_55_x64.exe`; adds read/status support). No live Bluetooth capture against real
hardware was performed for either — see [Confidence](#confidence--open-questions).

## How the apps were decompiled

### Android app

`Mos_DSP_control_App` turned out to be built with **MIT App Inventor** rather than
hand-written Java/Kotlin: `AndroidManifest.xml` names
`com.google.appinventor.components.runtime.multidex.MultiDexApplication` as the
`Application` class, and the entire app's logic lives in one generated class,
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

### Windows GUI

The Android app has no read/status support at all — it's a pure write-only remote.
The official Windows GUI does read live values back (needed to reflect the DSP's
physical Sub-level knob, for example), so it was decompiled too.

`MOSCONI GLADEN GUI V3_55_x64.exe` turned out to be a compiled **AutoIt v3** script
(`file` shows a plain PE32+ EXE; `strings` on it turns up `"AutoIt v3 GUI"`,
`GUICtrlCreateSlider`, etc.). Unlike a native C/C++ binary, AutoIt's compiler embeds
the original script source almost verbatim (behind light, documented compression) —
recovering it doesn't require a disassembler.

Steps taken:

1. `pip install autoit-ripper` (a small open-source AutoIt3-script extractor).
2. Its 10MB "max script size" safety limit rejected this particular script (a large,
   full-featured tuning GUI); patched the limit up and re-ran.
3. Out came `script.au3` — a 66,939-line, fully readable AutoIt source file — plus all
   of the GUI's bundled images, a Prolific PL2303 USB-serial driver installer
   (confirming a wired serial link is also supported/expected), and two spreadsheet
   databases (speaker/vehicle presets).
4. Searched the source directly for serial I/O (`_WinAPI_ReadFile`/`WriteFile` via
   `DllCall`) and worked outward from there: `COM_SEND`/`COM_RECEIVE`/`DATA_RECEIVE`
   for the wire framing, then the specific `Case`s in the big control-event `Switch`
   for each slider (`$S_AIVOLUME`, `$S_AISUBLEVEL`, `$S_AI_BALANCE`, `$S_AIFADER`,
   `$S_AI_BASS`/`$S_AI_MITTEN`/`$S_AI_HOEHEN`), plus `INFORMATION_LOAD` /
   `EEPROM_DATEN_RECEIVE` for the read side.

Cross-checking the two apps against each other is what gives this document its
confidence: the Windows GUI's own write path for volume/sub/balance/fader/preset
(`$UARTSEND[1]=8, $UARTSEND[2]=67, ...`) and tone (`$UARTSEND[2]=68`) uses **the exact
same command bytes, byte offsets, and defaults** independently reconstructed from the
Android app — including a Windows-side comment/log line
(`"Command for Android APP"`) that explicitly recognizes and special-cases the
Android app's shorter frame format. Two apps, two different reverse-engineering
methods, same answer.

## Transport

- **Classic Bluetooth RFCOMM (SPP)**, not BLE. The Android app's only Bluetooth
  component is App Inventor's `BluetoothClient`, which is RFCOMM-only; the Windows GUI
  talks to whatever COM port (real USB-serial adapter, or Windows' virtual COM port for
  a paired Bluetooth SPP device — same wire protocol either way, it's all just a serial
  byte stream).
- Standard SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`.
- The DSP must already be paired via Android's system Bluetooth settings (this app only
  lists already-bonded devices; it never runs discovery).
- Two framing styles coexist:
  - **Write frames** (`0x08`-prefixed, used by the Android app and reused here): fixed
    8 bytes, **no checksum**, get a single ack byte back that's never validated.
  - **Read/status frames** (`0x07`-prefixed, from the Windows GUI): checksummed with a
    CRC-8, get a longer structured response back.

## Write protocol (frame format)

Every write command is a fixed **8-byte** frame:

```
byte:  0    1    2      3      4      5      6      7
      len  cmd  ...................payload...................
```

Byte 0 is always `0x08` (the frame length, including itself). Byte 1 selects which of
two command families this is.

### Volume-family frame (`cmd = 0x43`)

```
[0x08, 0x43, VOL, SUB, BALANCE, FADER, PRESET, TARGET]
```

| Byte | Meaning | Range | Written by |
|---|---|---|---|
| 2 | Volume level | looked up from `LOG_VOLUME_TABLE` (see below) | main Volume slider |
| 3 | Sub level | 0–15, raw | Sub slider |
| 4 | Balance (left↔right) | 48 + (0–32) → 48–80 | Balance slider |
| 5 | Fader (front↔rear) | 48 + (0–32) → 48–80 | Fader slider |
| 6 | Preset index | 0–3 | Preset buttons P1–P4 |
| 7 | Target flag | `0x00` = output volume, `0x80` = input/CAN-bus volume | Input/Output switch |

Byte 4/5 are **Balance and Fader** — the standard car-audio left/right and front/rear
mix controls, not a "listening position" as originally guessed from the Android app
alone (its blocks just called them "Geo X/Y"). The Windows GUI's own slider labels
(`"Balance"`, `"Fader"`) and its confirmed min/default/max (49/64/79, vs. this
project's 48/64/80 — a one-off rounding difference at the edges, not a different
scale) settled it.

The app keeps **two independent 8-byte buffers** for this command — one for
"output volume" mode (`TARGET=0x00`) and one for "input volume" mode (`TARGET=0x80`).
Moving the Sub, Balance, Fader, or Preset controls always patches and resends the
*output* buffer, regardless of which volume mode is currently selected; only the
Volume slider itself decides which of the two buffers gets its byte 2 updated and
sent. The input-mode buffer's other bytes are therefore always compile-time defaults
and never meaningfully used — a quirk in the original app, reproduced exactly here for
fidelity (see `MosconiProtocol.State`). ("Input/CAN volume" itself is for reflecting a
factory head unit's steering-wheel volume control over the vehicle's CAN bus — the
Windows GUI has a corresponding read-only `CAN_VOLUME` display fed by passively
listening for these Android-style frames on the shared line, which is out of scope
here.)

Defaults (before any control has been touched in a session): volume=`0`, sub=`15`,
balance=`64`, fader=`64`, preset=`0`.

### Tone frame (`cmd = 0x44`)

```
[0x08, 0x44, TREBLE, MID, BASS, 0x00, 0x00, 0x00]
```

Treble/Mid/Bass are each raw `0–15`. Default `8` (flat/center) for all three.

### Volume lookup table

The Volume slider doesn't send its raw 0–35 position; it looks it up in a 36-entry
non-linear ("audio taper") table:

```
index:  0    1    2    3    4    5    6   7   8   9  10  11  12  13  14  15
value: 210  187  149  133  119  107  95  85  76  68  61  54  48  43  38  34

index: 16  17  18  19  20  21  22  23  24  25  26  27  28  29  30  31
value: 31  27  24  22  19  17  15  13  12  11   9   8   7   6   5   4

index: 32  33  34  35
value:  3   2   1   0
```

i.e. `LOG_VOLUME_TABLE[slider_position]`, monotonically decreasing 210 → 0. The DSP
itself stores/reports this raw 0–210 byte, not the slider position — reading a live
value back requires inverting the table (`nearestVolumeStep` in `MosconiProtocol.kt`).
The Windows GUI's own volume slider covers a slightly wider raw range (0 to -225,
shown as dB) — close enough to be clearly the same underlying scale; the extra range
past 210 is untested here.

## Read (status query) protocol

Recovered entirely from the Windows GUI — the Android app has no equivalent. The
request is a short, CRC-8-checksummed frame; the reply is a longer structured one.

### Request — `INFORMATION_LOAD` (6 bytes on the wire)

```
[0x07, 0x69, 0x00, STATUS|0x10, 0x0D, CRC8]
```

`STATUS` is the status byte from the most recently received response (0 if none yet).
CRC8 is computed over the first 5 bytes — see [CRC-8](#crc-8) below.

### Response (25 bytes on the wire)

```
[0x07, 0xE9, ???, STATUS, INFO[1], INFO[2], ..., INFO[20], CRC8]
```

- Byte 3 (`STATUS`) is present on **every** response frame of any kind, not just this
  one — bits 0–1 are the currently active preset (0-indexed here; the Windows GUI adds
  1 to display "P1"–"P4"). Bit 4 (`0x10`) marks "data present" in other response types;
  its meaning here isn't exercised.
- `INFO[8]` is always the current volume (raw 0–210-ish byte, invert through
  `LOG_VOLUME_TABLE`).
- `INFO[9]`, `INFO[10]`, `INFO[11]` are **one of two pages**, selected by bit 7 of
  `INFO[6]`:
  - bit clear → Balance, Fader, Sub level (raw bytes, same scale as the write side)
  - bit set → Bass, Mid, Treble (raw `0–15`)

  The DSP appears to alternate which page it reports across successive polls (this
  project never sends a page selector, matching what the Windows GUI does) — poll at
  least twice to see both. This app polls once a second while connected and merges
  whichever page shows up into local state, so it converges within ~2 seconds of
  connecting and stays live thereafter (e.g. turning the physical Sub-level knob shows
  up in-app on the next poll or two).

There's also a separate, heavier **bulk EEPROM read** (`USERDATA_LOAD`/`FLOWDATA_LOAD`,
command byte `0x45` sub-selecting memory pages via byte 5 = `0xA2`/`0xA0`) that the
Windows GUI issues once right after connecting to hydrate its *entire* settings UI
(crossovers, per-channel mixer matrix, effects, etc. — far more than this app exposes).
The same `AD_USERDATA` memory addresses it reads happen to include everything
`INFORMATION_LOAD` already covers (byte offsets 2/3/4/5 = fader/balance/sub/volume,
56/57/58 = bass/mid/treble), so this project only implements the lighter
`INFORMATION_LOAD` poll — no need for the bulk read at this app's scope. It's
documented here in case a future feature needs one of the many other settings that
live in that address space.

### CRC-8

Read-frame checksums use the standard **CRC-8/MAXIM (DOW-CRC)** algorithm — poly
`0x31` reflected, init `0x00`. The 256-entry table was transcribed verbatim from the
Windows GUI's `CRC_CALC_COM` rather than re-derived from the polynomial, to guarantee
a bit-for-bit match with the firmware; it was then confirmed against the standard's
public check value (CRC-8/MAXIM of ASCII `"123456789"` = `0xA1`) in
`MosconiProtocolTest.kt`, which independently confirms both the transcription and that
this is indeed that well-known algorithm rather than a custom variant.

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

Reconstructed by reading two independently-compiled, decompiled apps and
cross-checking them against each other — no live Bluetooth capture against a real DSP
was done for either. What's left as genuine assumptions to verify against real
hardware:

1. **Which end of the Volume slider is loud.** Both apps' UI code shows *a* slider
   position maps to *a* table index, but not which physical direction the original
   artwork/labels implied. If it's backwards, reverse `LOG_VOLUME_TABLE`.
2. **Whether the status-response "page toggle" bit really alternates autonomously on
   the device** between successive `INFORMATION_LOAD` polls, as opposed to being
   controlled by a request field neither app happens to set deliberately. If it turns
   out to need explicit selection instead, the poll loop needs a page-selector byte
   added to `buildStatusRequest`.
3. **The bulk `USERDATA_LOAD` response's exact byte-3 framing semantics** (an early
   length hint the Windows GUI uses to know how many more bytes to block-read) weren't
   fully pinned down, since this project doesn't use that path — noted here only in
   case someone extends the app to read from the wider `AD_USERDATA` address space.

All are one-line fixes once confirmed; see the `ASSUMPTION`/doc comments in
`protocol/src/main/kotlin/dev/x3n0n10/mosconibt/protocol/MosconiProtocol.kt`.
