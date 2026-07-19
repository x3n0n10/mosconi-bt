# MOSCONI DSP Bluetooth protocol

Reverse-engineered from two independent sources: `Mos_DSP_control_App` (the factory
Android app; write-only) and the official Windows tuning GUI (`MOSCONI GLADEN GUI
V3_55_x64.exe`; adds read/status support). The write side was purely decompiled,
never live-tested. The read side started the same way, but has since been corrected
against a live capture from a real PICO V2 6|8 — see
[Confidence](#confidence--open-questions).

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
  - **Read/status frames** (`0x07`-prefixed, from the Windows GUI): *requests* are
    checksummed with CRC-8; the longer structured *responses* they get back are
    checksummed differently — a plain byte-sum, confirmed against real hardware. See
    [CRC-8](#crc-8) and [Sum8](#sum8).

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

### Response (26 bytes on the wire)

```
[0x07, 0xE9, ???, STATUS, INFO[1], INFO[2], ..., INFO[20], 0x0D, SUM8]
```

Confirmed against a real PICO V2 6|8: the response is **one byte longer** than the
request-side framing alone would suggest — a `0x0D` terminator precedes the trailing
checksum byte, the same convention [write frames](#write-protocol-frame-format) and
[requests](#read-status-query-protocol) already use. The trailing checksum is a plain
[byte-sum](#sum8), *not* CRC-8, despite every request in this protocol using CRC-8.

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
`INFORMATION_LOAD` poll for the regular status-sync loop — no need for the bulk read
at that scope.

This project *does* use the bulk read for one thing: the four **custom preset names**
(`buildUserDataRequest`/`parseUserDataResponse`/`parsePresetNames` in
`MosconiProtocol.kt`), read once per connection and shown read-only next to the P1–P4
preset chips — this app never writes them; renaming stays a Windows-GUI-only feature.

### Preset names (`USERDATA_LOAD`, read-only)

```
Request  (10 bytes): [0x07, 0x45, 0x00, STATUS|0x10, 0xA2, ADRH, ADRL, COUNT, 0x0D, CRC8]
Response (COUNT+11 bytes): [0x07, 0xC5, LEN_HINT, STATUS, 0xA2, ADRH, ADRL, COUNT, payload(COUNT+1 bytes), 0x0D, SUM8]
```

- `ADRH`/`ADRL` split a 16-bit address; `COUNT` is zero-based (`COUNT+1` bytes come
  back). Preset names live at address `256 + 16*16 = 512` (a shared 24-entry, 16-
  byte-per-entry label table that also holds input/output/mixer-channel labels this
  app doesn't use; entries 16–19 are the preset names, P1–P4 in order, contiguous) — a
  single request with `COUNT=63` fetches all four at once.
- Each 16-byte slot is raw ASCII, null-padded; a first byte of `0xFF` is the device's
  own "no custom name set" sentinel (the Windows GUI checks the same byte before
  falling back to a generic "Preset N").
- `LEN_HINT` (response byte 2) mirrors `COUNT` — sent early, before the rest of the
  frame, so the receiving side can compute the total frame length up front. This
  resolves what used to be an open question here about that byte's purpose.

**Confirmed against real hardware.** The response is `COUNT + 11` bytes: an 8-byte
header, `COUNT + 1` payload bytes, a `0x0D` terminator, and a trailing [Sum8](#sum8)
checksum — the same `[header, ..., terminator, checksum]` shape the status response
uses. This settles what used to be a genuine ambiguity between two disagreeing pieces
of the Windows GUI's own source: `EEPROM_DATEN_RECEIVE`'s payload indexing implied
`COUNT + 10`, while its receive-length table (`$UARTWERT[3] + 11`) said `COUNT + 11`.
The `+11` form was right all along. A real device's response, decoded with this
formula, produced clean, correctly null-terminated ASCII preset names ("Carplace",
"Zelf", "Anderen") plus the `0xFF` unset sentinel on the fourth slot — independently
confirming both the length and the rest of the framing (address/count echo, payload
offset) in one shot.

### PIN protection (not implemented — local Windows GUI lock, not a DSP control lock)

The Windows GUI has a 4-digit PIN feature (`$PIN_ACT`/`$PIN_SEND`/`EEPROM_DATEN_RECEIVE`'s
PIN-validity check). Worth documenting since it's easy to assume it locks the tuning
controls this app exposes — it doesn't:

- It gates exactly four **whole-device** operations in the Windows GUI: loading a saved
  `.SDx` setup file to the DSP, doing a full "copy current setup to DSP" write, doing a
  full "read everything from DSP" sync, and finishing the Setup Wizard's write step.
  Entering the wrong (or no) PIN for those just pops "Enter a valid PIN" and refuses.
- It does **not** gate the individual controls this app has (volume, sub, balance/fader,
  tone, presets) — those work identically whether a PIN is set or not.
- The PIN itself lives on the DSP, not just in the Windows app: `PIN_SEND` writes the 4
  digits plus a checksum byte to the *same* bulk USERDATA EEPROM region as the preset
  names, at address `2176` (`ADRH=8, ADRL=128`). `0xFF,0xFF,0xFF,0xFF` is the "no PIN
  set" sentinel — the same convention as the preset-name-unset sentinel above.

Since this app (like the factory Android app before it) never performs any of those
four whole-device operations — it only ever sends small incremental control frames —
the PIN is simply outside its scope. Nothing to implement here; noted for anyone later
wondering why a PIN set in the Windows GUI has no visible effect in this app.

### CRC-8

Every **request** this protocol sends (`INFORMATION_LOAD`, `USERDATA_LOAD`) is
checksummed with the standard **CRC-8/MAXIM (DOW-CRC)** algorithm — poly `0x31`
reflected, init `0x00`. The 256-entry table was transcribed verbatim from the Windows
GUI's `CRC_CALC_COM` rather than re-derived from the polynomial, to guarantee a
bit-for-bit match with the firmware; it was then confirmed against the standard's
public check value (CRC-8/MAXIM of ASCII `"123456789"` = `0xA1`) in
`MosconiProtocolTest.kt`, which independently confirms both the transcription and that
this is indeed that well-known algorithm rather than a custom variant.

**Responses do not use CRC-8** — see [Sum8](#sum8) below.

### Sum8

Every **response** this protocol receives (`INFORMATION_LOAD`'s status response,
`USERDATA_LOAD`'s payload response) is checksummed with a much simpler algorithm: a
plain sum of every preceding byte in the frame, masked to `0x00`–`0xFF`. This was
*not* visible from decompiling either app — the Windows GUI never actually validates
incoming checksums, it only computes them for outgoing requests — so it only came to
light from a live capture against a real PICO V2 6|8: the original assumption that
responses reused CRC-8 like requests do caused every read to fail to validate, which
combined with the [1-byte frame-length miss](#response-26-bytes-on-the-wire) above to
produce a slowly-accumulating stream desync (each under-read status poll left one
stray byte in the input stream, which prepended itself onto the next read, growing by
one byte every second). Reconstructing whole frames by concatenating several
consecutive (contaminated) reads end-to-end and re-slicing at 26 bytes recovered clean
frames; CRC-8 didn't match any of them, but a plain byte-sum matched every one tried.

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

Originally reconstructed purely by reading two independently-compiled, decompiled
apps and cross-checking them against each other, with no live Bluetooth capture
against a real DSP. The read/status side has since been corrected against a live
capture from a real PICO V2 6|8 (see [Sum8](#sum8) and the real-frame regression test
in `MosconiProtocolTest.kt`), which resolved the two issues that used to be listed
here as open (response frame length, and the ambiguous `USERDATA_LOAD` response
length formula — both are now confirmed, see above).

**Confirmed against real hardware:** the Volume slider's direction. `LOG_VOLUME_TABLE`
is indexed so the rightmost/max slider position (step 35) maps to raw `0`; testing
against a real PICO V2 6|8 set to full volume on both the input and output channels
confirmed the slider reads correctly at its rightmost position in both modes, so no
reversal is needed.

What's left as a genuine assumption still to verify against real hardware:

1. **Whether the status-response "page toggle" bit really alternates autonomously on
   the device** between successive `INFORMATION_LOAD` polls, as opposed to depending
   on the echoed status byte in the request — which only starts reflecting real
   device state once a poll has successfully parsed at least once, so this couldn't be
   properly exercised until the checksum/length fix above. If it turns out to need
   explicit selection instead, the poll loop needs a page-selector byte added to
   `buildStatusRequest`.

See the doc comments in
`protocol/src/main/kotlin/dev/x3n0n10/mosconibt/protocol/MosconiProtocol.kt` for the
code-level detail behind each.
