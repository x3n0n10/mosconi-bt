package dev.x3n0n10.mosconibt.protocol

/**
 * Wire protocol for the MOSCONI PICO (and compatible) DSPs, as spoken by the
 * factory "Mos_DSP_control_App" and the official Windows tuning GUI.
 *
 * The write side was reverse engineered by decompiling the Android app (built with
 * MIT App Inventor; logic lives in a Kawa/Scheme-compiled `Screen1` class) and
 * statically tracing how each on-screen control edits one of three 8-byte buffers
 * before handing them to the classic-Bluetooth `BluetoothClient` component's
 * `SendBytes`. The read side doesn't exist in the Android app at all - it was
 * recovered by decompiling the official Windows GUI (an AutoIt3 script, extracted
 * verbatim from the compiled .exe) and tracing `INFORMATION_LOAD` / its response
 * handling. Cross-checking the two independently-derived write paths against each
 * other (identical command bytes, byte offsets, and defaults) is what gives this
 * confidence - see PROTOCOL.md for the full derivation.
 *
 * The read side was live-tested against a real PICO V2 6|8 and needed two corrections
 * from the original decompiled-only guesses: response frames are 1 byte longer than
 * first assumed (a `0x0D` terminator precedes the checksum, mirroring how request
 * frames are built), and - contrary to every request in this protocol - response
 * checksums are a plain byte-sum mod 256, not [Crc8]. See [Sum8] and PROTOCOL.md.
 *
 * [LOG_VOLUME_TABLE]'s direction (rightmost/max slider position = loudest) is also
 * confirmed against real hardware: full volume on both the input and output channels
 * reads back correctly at the slider's rightmost position.
 *
 * [StatusResponse]'s "page toggle" bit is likewise confirmed to alternate
 * autonomously on the device, not in response to anything sent to it: a real capture
 * with the request's echoed status byte pinned to one constant value the whole time
 * still showed the page varying. It isn't tightly synced to a 1-second poll rate
 * though (occasional repeats, up to 3 consecutive polls on the same page observed),
 * so no page-selector byte is needed - just don't assume strict 1:1 alternation.
 *
 * Transport: classic Bluetooth RFCOMM/SPP (NOT BLE) using the standard SPP UUID.
 * Write frames (the `0x08`-prefixed short format) carry no checksum and get a
 * single ack byte back that's never validated. Read *requests* (the `0x07`-prefixed
 * format used for [buildStatusRequest]) are checksummed with [Crc8]; read *responses*
 * are checksummed with [Sum8] instead.
 */
object MosconiProtocol {

    /** Standard Serial Port Profile UUID; the DSP's Bluetooth module exposes this. */
    const val SPP_UUID: String = "00001101-0000-1000-8000-00805F9B34FB"

    /** Every write command frame sent to the DSP is exactly this many bytes. */
    const val FRAME_SIZE: Int = 8

    /** Command byte for the "volume family" frame (level / sub / balance / fader / preset). */
    private const val CMD_VOLUME_FAMILY: Int = 0x43

    /** Command byte for the tone (treble/mid/bass) frame. */
    private const val CMD_TONE: Int = 0x44

    /** Byte 7 flag: this volume-family frame targets the output (speaker) volume. */
    private const val FLAG_TARGET_OUTPUT: Int = 0x00

    /** Byte 7 flag: this volume-family frame targets the input (CAN-bus) volume. */
    private const val FLAG_TARGET_INPUT: Int = 0x80

    /** Sub level and tone bands use a 0..15 raw scale, clamped here. */
    const val SUB_STEPS: Int = 15
    const val TONE_STEPS: Int = 15
    const val PRESET_COUNT: Int = 4

    /** Balance and fader use a 0..32 raw scale (transmitted as 48+value), clamped here. */
    const val BALANCE_FADER_STEPS: Int = 32

    /** Number of discrete positions the volume slider snaps to (0..35 inclusive). */
    const val VOLUME_STEPS: Int = 35

    /**
     * 36-entry non-linear (audio-taper) volume lookup table, extracted verbatim from the
     * Android app's compiled literals: position 0 -> 210 ... position 35 -> 0. The DSP
     * itself stores/reports the raw table *value* (0..210), not the slider position, so
     * reading a live value back requires [nearestVolumeStep] to invert this table.
     *
     * ASSUMPTION: position 0 is whichever end of the slider App Inventor's canvas
     * treated as X=0. Verify on real hardware; if the mapping is backwards, reverse
     * this list (`LOG_VOLUME_TABLE.reversed()`) rather than changing the indexing math.
     */
    val LOG_VOLUME_TABLE: IntArray = intArrayOf(
        210, 187, 149, 133, 119, 107, 95, 85, 76, 68,
        61, 54, 48, 43, 38, 34, 31, 27, 24, 22,
        19, 17, 15, 13, 12, 11, 9, 8, 7, 6,
        5, 4, 3, 2, 1, 0,
    )

    init {
        check(LOG_VOLUME_TABLE.size == VOLUME_STEPS + 1) {
            "LOG_VOLUME_TABLE must have ${VOLUME_STEPS + 1} entries"
        }
    }

    /** Inverts [LOG_VOLUME_TABLE]: the DSP's raw 0..210 byte -> the nearest slider step. */
    fun nearestVolumeStep(rawValue: Int): Int =
        LOG_VOLUME_TABLE.indices.minBy { i -> kotlin.math.abs(LOG_VOLUME_TABLE[i] - rawValue) }

    enum class VolumeTarget { OUTPUT, INPUT }

    /**
     * Holds the three persistent 8-byte send-buffers the original app keeps for the
     * lifetime of a session (and mirrors to local storage). Every control edits exactly
     * one byte of one buffer, then the *whole* buffer is retransmitted - not just the
     * changed byte - so this class reproduces that "patch one field, resend the record"
     * behaviour faithfully rather than sending sparse diffs.
     */
    class State {
        // [len, cmd, volume, sub, balance, fader, preset, targetFlag]
        private val outputVolume = intArrayOf(FRAME_SIZE, CMD_VOLUME_FAMILY, 0, 15, 64, 64, 0, FLAG_TARGET_OUTPUT)

        // Only byte[2] (volume) of this buffer is ever touched after construction -
        // that mirrors the original app exactly: Sub/Balance/Fader/Preset always write
        // to the *output* buffer regardless of which volume target is currently selected.
        private val inputVolume = intArrayOf(FRAME_SIZE, CMD_VOLUME_FAMILY, 0, 15, 64, 64, 0, FLAG_TARGET_INPUT)

        // [len, cmd, treble, mid, bass, 0, 0, 0]
        private val tone = intArrayOf(FRAME_SIZE, CMD_TONE, 8, 8, 8, 0, 0, 0)

        /** @param step 0..[VOLUME_STEPS] slider position. Returns the frame to send. */
        fun setVolume(target: VolumeTarget, step: Int): IntArray {
            val level = LOG_VOLUME_TABLE[step.coerceIn(0, VOLUME_STEPS)]
            return when (target) {
                VolumeTarget.OUTPUT -> outputVolume.also { it[2] = level }.copyOf()
                VolumeTarget.INPUT -> inputVolume.also { it[2] = level }.copyOf()
            }
        }

        /** @param level 0..[SUB_STEPS]. Always patches the output-volume-family buffer. */
        fun setSub(level: Int): IntArray =
            outputVolume.also { it[3] = level.coerceIn(0, SUB_STEPS) }.copyOf()

        /** @param position 0..[BALANCE_FADER_STEPS] (0=full left), transmitted as 48+position. */
        fun setBalance(position: Int): IntArray =
            outputVolume.also { it[4] = 48 + position.coerceIn(0, BALANCE_FADER_STEPS) }.copyOf()

        /** @param position 0..[BALANCE_FADER_STEPS] (0=full rear), transmitted as 48+position. */
        fun setFader(position: Int): IntArray =
            outputVolume.also { it[5] = 48 + position.coerceIn(0, BALANCE_FADER_STEPS) }.copyOf()

        /** @param index 0..3, selecting preset P1..P4. */
        fun selectPreset(index: Int): IntArray =
            outputVolume.also { it[6] = index.coerceIn(0, PRESET_COUNT - 1) }.copyOf()

        /** @param level 0..[TONE_STEPS]. */
        fun setTreble(level: Int): IntArray = tone.also { it[2] = level.coerceIn(0, TONE_STEPS) }.copyOf()

        /** @param level 0..[TONE_STEPS]. */
        fun setMid(level: Int): IntArray = tone.also { it[3] = level.coerceIn(0, TONE_STEPS) }.copyOf()

        /** @param level 0..[TONE_STEPS]. */
        fun setBass(level: Int): IntArray = tone.also { it[4] = level.coerceIn(0, TONE_STEPS) }.copyOf()
    }

    /** Converts a frame of 0..255 int "bytes" to an actual [ByteArray] ready for the socket. */
    fun IntArray.toByteArray(): ByteArray = ByteArray(size) { i -> this[i].toByte() }

    // ---------------------------------------------------------------------------------
    // Read (status query) protocol - "INFORMATION_LOAD" in the Windows GUI's source.
    // ---------------------------------------------------------------------------------

    private const val CMD_STATUS_FAMILY: Int = 0x07
    private const val CMD_STATUS_REQUEST: Int = 0x69 // 105 - request; ack comes back as 0x69 | 0x80 = 0xE9
    private const val CMD_STATUS_RESPONSE: Int = 0xE9 // 233
    private const val FRAME_TERMINATOR: Int = 0x0D // CR

    /** Total bytes of a status *response* frame: 4 header + 20 data + terminator + checksum. */
    const val STATUS_RESPONSE_SIZE: Int = 26

    /**
     * Builds the 6-byte "give me your current status" request frame (checksummed with
     * [Crc8], unlike the write frames above). Response is [STATUS_RESPONSE_SIZE] bytes;
     * parse it with [parseStatusResponse].
     *
     * @param lastStatusByte the status byte from the most recent response, or 0 if none
     *   yet (the Windows app always ORs the current status byte into this request; its
     *   exact effect on the reply isn't confirmed, so 0 is a safe default before the
     *   first successful read).
     */
    fun buildStatusRequest(lastStatusByte: Int = 0): IntArray {
        val payload = intArrayOf(
            CMD_STATUS_FAMILY,
            CMD_STATUS_REQUEST,
            0,
            lastStatusByte or 0x10,
            FRAME_TERMINATOR,
        )
        val checksum = Crc8.calculate(payload)
        return payload + checksum
    }

    /**
     * One "page" of a status response. The DSP autonomously alternates which page it
     * reports on successive [buildStatusRequest] calls (bit 7 of the byte at offset 6
     * of the 20-value info block selects the page), confirmed against real hardware -
     * but not in strict lockstep with each poll, so don't assume exactly every other
     * response flips; a handful of polls in a row can occasionally land on the same
     * page.
     */
    sealed interface InformationPage {
        data class VolumeControls(val balance: Int, val fader: Int, val sub: Int) : InformationPage
        data class Tone(val bass: Int, val mid: Int, val treble: Int) : InformationPage
    }

    /** A parsed status response: always carries volume/preset/status, plus one [InformationPage]. */
    data class StatusResponse(
        val statusByte: Int,
        val preset: Int,
        val volumeRaw: Int,
        val page: InformationPage?,
    ) {
        /** [volumeRaw] converted to a 0..[VOLUME_STEPS] slider position via [nearestVolumeStep]. */
        val volumeStep: Int get() = nearestVolumeStep(volumeRaw)
    }

    /**
     * Parses a [STATUS_RESPONSE_SIZE]-byte response to [buildStatusRequest]. Returns null if
     * the frame doesn't look like a status response (wrong length/command byte), the
     * terminator is missing, or the checksum doesn't match.
     */
    fun parseStatusResponse(frame: IntArray): StatusResponse? {
        if (frame.size != STATUS_RESPONSE_SIZE) return null
        if (frame[0] != CMD_STATUS_FAMILY || frame[1] != CMD_STATUS_RESPONSE) return null
        if (frame[STATUS_RESPONSE_SIZE - 2] != FRAME_TERMINATOR) return null

        val payload = frame.copyOfRange(0, STATUS_RESPONSE_SIZE - 1)
        val checksum = frame[STATUS_RESPONSE_SIZE - 1]
        if (Sum8.calculate(payload) != checksum) return null

        val statusByte = frame[3]
        val preset = statusByte and 0x03
        // info[1..20] live at frame[4..23] (frame[3 + i] for i in 1..20)
        fun info(i: Int) = frame[3 + i]

        val volumeRaw = info(8)
        val toggle = info(6) and 0x80
        val page = if (toggle == 0) {
            InformationPage.VolumeControls(
                balance = (info(9) - 48).coerceIn(0, BALANCE_FADER_STEPS),
                fader = (info(10) - 48).coerceIn(0, BALANCE_FADER_STEPS),
                sub = info(11).coerceIn(0, SUB_STEPS),
            )
        } else {
            InformationPage.Tone(
                bass = info(9).coerceIn(0, TONE_STEPS),
                mid = info(10).coerceIn(0, TONE_STEPS),
                treble = info(11).coerceIn(0, TONE_STEPS),
            )
        }

        return StatusResponse(statusByte = statusByte, preset = preset, volumeRaw = volumeRaw, page = page)
    }

    // ---------------------------------------------------------------------------------
    // Bulk EEPROM/"USERDATA" read - "USERDATA_LOAD" in the Windows GUI's source. Used
    // here only to read the four custom preset names (read-only; this app never writes
    // them). The wider address space this command can reach covers a lot more than
    // that - see PROTOCOL.md.
    // ---------------------------------------------------------------------------------

    private const val CMD_EXTENDED_REQUEST: Int = 0x45 // 69 - shared by USERDATA_LOAD and FLOWDATA_LOAD
    private const val CMD_USERDATA_RESPONSE: Int = 0xC5 // 197
    private const val SUBCMD_USERDATA: Int = 0xA2 // selects the EEPROM/"USERDATA" memory space, vs 0xA0 "FLOWDATA"

    /**
     * Where the DSP stores its 4 custom preset names: a 24-entry, 16-byte-per-entry
     * label table (shared with several other labels this app doesn't use - input/
     * output/mixer channel names) starting at address 256. Entries 16-19 are the
     * preset names, P1..P4 in order, and are contiguous, so one [buildUserDataRequest]
     * for [PRESET_NAME_COUNT] bytes covers all four.
     */
    const val PRESET_NAME_ADDRESS: Int = 256 + 16 * 16
    const val PRESET_NAME_LENGTH: Int = 16
    const val PRESET_NAME_COUNT: Int = PRESET_NAME_LENGTH * PRESET_COUNT - 1 // "count" is zero-based on the wire

    /**
     * Builds a request for `count + 1` bytes of the DSP's bulk "USERDATA" (EEPROM-
     * backed settings) memory starting at [address], e.g. [PRESET_NAME_ADDRESS] with
     * [PRESET_NAME_COUNT]. Response size/shape is [userDataResponseSize]; parse it with
     * [parseUserDataResponse].
     */
    fun buildUserDataRequest(address: Int, count: Int, lastStatusByte: Int = 0): IntArray {
        val payload = intArrayOf(
            CMD_STATUS_FAMILY,
            CMD_EXTENDED_REQUEST,
            0,
            (lastStatusByte or 0x10) and 0xFF,
            SUBCMD_USERDATA,
            (address ushr 8) and 0xFF,
            address and 0xFF,
            count and 0xFF,
            FRAME_TERMINATOR,
        )
        return payload + Crc8.calculate(payload)
    }

    /**
     * Total wire bytes of the response to a [buildUserDataRequest] for [count]: an
     * 8-byte header, `count + 1` payload bytes, a `0x0D` terminator, and a trailing
     * [Sum8] checksum - confirmed against a real device's status responses, which use
     * the same [header..., terminator, checksum] shape (see [parseStatusResponse]) and
     * happen to match the length this formula already gave (`count + 11`), so it's
     * carried over here with the same confidence rather than re-verified byte-by-byte
     * for this specific request.
     */
    fun userDataResponseSize(count: Int): Int = count + 11

    /**
     * Validates and unwraps a [buildUserDataRequest] response: checks the terminator
     * and [Sum8] checksum, and that the echoed selector/address/count match what was
     * asked for, then returns just the payload (`count + 1` bytes). Returns null on any
     * mismatch - including a wrong guess about [userDataResponseSize] - so a framing
     * error fails quietly rather than risk handing back misaligned bytes.
     */
    fun parseUserDataResponse(frame: IntArray, address: Int, count: Int): IntArray? {
        if (frame.size != userDataResponseSize(count)) return null
        if (frame[frame.size - 2] != FRAME_TERMINATOR) return null
        if (Sum8.calculate(frame.copyOfRange(0, frame.size - 1)) != frame.last()) return null
        if (frame[0] != CMD_STATUS_FAMILY || frame[1] != CMD_USERDATA_RESPONSE) return null
        val addressHigh = (address ushr 8) and 0xFF
        val addressLow = address and 0xFF
        if (frame[4] != SUBCMD_USERDATA || frame[5] != addressHigh || frame[6] != addressLow || frame[7] != (count and 0xFF)) {
            return null
        }
        return frame.copyOfRange(8, 8 + count + 1)
    }

    /**
     * Decodes a [parseUserDataResponse] payload (of at least [PRESET_NAME_LENGTH] *
     * [PRESET_COUNT] bytes, i.e. a [PRESET_NAME_ADDRESS]/[PRESET_NAME_COUNT] read) into
     * the 4 preset names, P1..P4 order. A slot is null when the DSP marks it unset
     * (first byte of its 16-byte block is `0xFF`) - the same "no custom name" sentinel
     * the Windows GUI itself checks - so callers should fall back to a generic label.
     */
    fun parsePresetNames(payload: IntArray): List<String?> {
        require(payload.size >= PRESET_NAME_LENGTH * PRESET_COUNT)
        return (0 until PRESET_COUNT).map { preset ->
            val base = preset * PRESET_NAME_LENGTH
            if (payload[base] == 0xFF) return@map null
            (0 until PRESET_NAME_LENGTH)
                .map { payload[base + it] }
                .takeWhile { it != 0 }
                .map { it.toChar() }
                .joinToString("")
                .trim()
                .ifEmpty { null }
        }
    }

    /**
     * Plain sum of every byte, masked to 0..255. Confirmed against a real device: every
     * response frame in this protocol ([parseStatusResponse], [parseUserDataResponse])
     * is checksummed this way, *not* with [Crc8] - despite every request using CRC-8.
     * Reconstructed from a live capture (concatenating consecutive under-read status
     * responses back into whole frames revealed this immediately; CRC-8 never matched,
     * a byte sum matched every single frame tried).
     */
    object Sum8 {
        fun calculate(payload: IntArray): Int {
            var sum = 0
            for (b in payload) sum += b
            return sum and 0xFF
        }
    }

    /**
     * CRC-8 (poly 0x31, "Dallas/Maxim" table form) as implemented by the Windows GUI's
     * `CRC_CALC_COM`. Table transcribed verbatim - not re-derived from the polynomial -
     * to guarantee it matches the firmware bit-for-bit. Used for every *request* this
     * protocol sends ([buildStatusRequest], [buildUserDataRequest]) - responses use
     * [Sum8] instead, confirmed against real hardware.
     */
    object Crc8 {
        private val TABLE = intArrayOf(
            0, 94, 188, 226, 97, 63, 221, 131, 194, 156, 126, 32, 163, 253, 31, 65,
            157, 195, 33, 127, 252, 162, 64, 30, 95, 1, 227, 189, 62, 96, 130, 220,
            35, 125, 159, 193, 66, 28, 254, 160, 225, 191, 93, 3, 128, 222, 60, 98,
            190, 224, 2, 92, 223, 129, 99, 61, 124, 34, 192, 158, 29, 67, 161, 255,
            70, 24, 250, 164, 39, 121, 155, 197, 132, 218, 56, 102, 229, 187, 89, 7,
            219, 133, 103, 57, 186, 228, 6, 88, 25, 71, 165, 251, 120, 38, 196, 154,
            101, 59, 217, 135, 4, 90, 184, 230, 167, 249, 27, 69, 198, 152, 122, 36,
            248, 166, 68, 26, 153, 199, 37, 123, 58, 100, 134, 216, 91, 5, 231, 185,
            140, 210, 48, 110, 237, 179, 81, 15, 78, 16, 242, 172, 47, 113, 147, 205,
            17, 79, 173, 243, 112, 46, 204, 146, 211, 141, 111, 49, 178, 236, 14, 80,
            175, 241, 19, 77, 206, 144, 114, 44, 109, 51, 209, 143, 12, 82, 176, 238,
            50, 108, 142, 208, 83, 13, 239, 177, 240, 174, 76, 18, 145, 207, 45, 115,
            202, 148, 118, 40, 171, 245, 23, 73, 8, 86, 180, 234, 105, 55, 213, 139,
            87, 9, 235, 181, 54, 104, 138, 212, 149, 203, 41, 119, 244, 170, 72, 22,
            233, 183, 85, 11, 136, 214, 52, 106, 43, 117, 151, 201, 74, 20, 246, 168,
            116, 42, 200, 150, 21, 75, 169, 247, 182, 232, 10, 84, 215, 137, 107, 53,
        )

        /**
         * Computes the checksum over every byte of [payload] (values are masked to 0..255).
         *
         * The source computes `crc = (crc shr 8) xor TABLE[(crc xor byte) and 0xFF]` per byte,
         * but since `crc` is always an 8-bit value here, `crc shr 8` is always 0 - so this
         * reduces to a plain table-driven CRC-8 update.
         */
        fun calculate(payload: IntArray): Int {
            var crc = 0
            for (b in payload) {
                crc = TABLE[(crc xor b) and 0xFF]
            }
            return crc
        }
    }
}
