package dev.x3n0n10.mosconibt.protocol

/**
 * Wire protocol for the MOSCONI PICO (and compatible) DSPs, as spoken by the
 * factory "Mos_DSP_control_App".
 *
 * Reverse engineered by decompiling that app (it's built with MIT App
 * Inventor; the logic lives in a Kawa/Scheme-compiled `Screen1` class) and
 * statically tracing how each on-screen control edits one of three 8-byte
 * buffers before handing them to the classic-Bluetooth `BluetoothClient`
 * component's `SendBytes`. No live capture against real hardware was done,
 * so two details are marked below as ASSUMPTIONS to double check against a
 * real unit the first time you drive it:
 *
 *  - Which end of the volume slider is loud vs. quiet ([LOG_VOLUME_TABLE]
 *    order).
 *  - Which screen edge is which for the "Geo" X/Y pair.
 *
 * Transport: classic Bluetooth RFCOMM/SPP (NOT BLE) using the standard SPP
 * UUID. The app never validates a response; it only drains a single ack
 * byte the DSP sends back after each command and discards it. There is no
 * checksum anywhere in the frame.
 */
object MosconiProtocol {

    /** Standard Serial Port Profile UUID; the DSP's Bluetooth module exposes this. */
    const val SPP_UUID: String = "00001101-0000-1000-8000-00805F9B34FB"

    /** Every command frame sent to the DSP is exactly this many bytes. */
    const val FRAME_SIZE: Int = 8

    /** Command byte for the "volume family" frame (level / sub / geo / preset). */
    private const val CMD_VOLUME_FAMILY: Int = 0x43

    /** Command byte for the tone (treble/mid/bass) frame. */
    private const val CMD_TONE: Int = 0x44

    /** Byte 7 flag: this volume-family frame targets the output (speaker) volume. */
    private const val FLAG_TARGET_OUTPUT: Int = 0x00

    /** Byte 7 flag: this volume-family frame targets the input (CAN-bus) volume. */
    private const val FLAG_TARGET_INPUT: Int = 0x80

    /** Sub level, geo axes, and tone bands all use a 0..15 (or 0..32) raw scale, clamped here. */
    const val SUB_STEPS: Int = 15
    const val GEO_STEPS: Int = 32
    const val TONE_STEPS: Int = 15
    const val PRESET_COUNT: Int = 4

    /** Number of discrete positions the volume slider snaps to (0..35 inclusive). */
    const val VOLUME_STEPS: Int = 35

    /**
     * 36-entry non-linear (audio-taper) volume lookup table, extracted verbatim from the
     * app's compiled literals: position 0 -> 210 ... position 35 -> 0.
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

    enum class VolumeTarget { OUTPUT, INPUT }

    /**
     * Holds the three persistent 8-byte send-buffers the original app keeps for the
     * lifetime of a session (and mirrors to local storage). Every control edits exactly
     * one byte of one buffer, then the *whole* buffer is retransmitted - not just the
     * changed byte - so this class reproduces that "patch one field, resend the record"
     * behaviour faithfully rather than sending sparse diffs.
     */
    class State {
        // [len, cmd, volume, sub, geoX, geoY, preset, targetFlag]
        private val outputVolume = intArrayOf(FRAME_SIZE, CMD_VOLUME_FAMILY, 0, 15, 64, 64, 0, FLAG_TARGET_OUTPUT)

        // Only byte[2] (volume) of this buffer is ever touched after construction -
        // that mirrors the original app exactly: Sub/Geo/Preset always write to the
        // *output* buffer regardless of which volume target is currently selected.
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

        /** @param position 0..[GEO_STEPS], transmitted as 48+position. */
        fun setGeoX(position: Int): IntArray =
            outputVolume.also { it[4] = 48 + position.coerceIn(0, GEO_STEPS) }.copyOf()

        /** @param position 0..[GEO_STEPS], transmitted as 48+position. */
        fun setGeoY(position: Int): IntArray =
            outputVolume.also { it[5] = 48 + position.coerceIn(0, GEO_STEPS) }.copyOf()

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
}
