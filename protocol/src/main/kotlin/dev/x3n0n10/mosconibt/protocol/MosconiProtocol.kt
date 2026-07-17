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
 * No live capture against real hardware was done for either side. Remaining
 * ASSUMPTIONS to verify against a real unit:
 *  - Which end of the volume slider is loud vs. quiet ([LOG_VOLUME_TABLE] order).
 *  - Whether [InformationResponse]'s "page toggle" bit really alternates
 *    autonomously on the device between successive status polls (this project
 *    never sends a page selector, matching what the Windows app does).
 *
 * Transport: classic Bluetooth RFCOMM/SPP (NOT BLE) using the standard SPP UUID.
 * Write frames (the `0x08`-prefixed short format) carry no checksum and get a
 * single ack byte back that's never validated. Read frames (the `0x07`-prefixed
 * format used for [buildStatusRequest]) are checksummed with [Crc8].
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

    /** Total bytes of a status *response* frame: 4 header + 20 data + 1 checksum. */
    const val STATUS_RESPONSE_SIZE: Int = 25

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
     * One "page" of a status response. The DSP appears to alternate which page it
     * reports on successive [buildStatusRequest] calls (bit 7 of the byte at offset 6
     * of the 20-value info block selects the page) - poll at least twice to see both.
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
     * the frame doesn't look like a status response (wrong length/command byte) or the
     * checksum doesn't match.
     */
    fun parseStatusResponse(frame: IntArray): StatusResponse? {
        if (frame.size != STATUS_RESPONSE_SIZE) return null
        if (frame[0] != CMD_STATUS_FAMILY || frame[1] != CMD_STATUS_RESPONSE) return null

        val payload = frame.copyOfRange(0, STATUS_RESPONSE_SIZE - 1)
        val checksum = frame[STATUS_RESPONSE_SIZE - 1]
        if (Crc8.calculate(payload) != checksum) return null

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

    /**
     * CRC-8 (poly 0x31, "Dallas/Maxim" table form) as implemented by the Windows GUI's
     * `CRC_CALC_COM`. Table transcribed verbatim - not re-derived from the polynomial -
     * to guarantee it matches the firmware bit-for-bit.
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
