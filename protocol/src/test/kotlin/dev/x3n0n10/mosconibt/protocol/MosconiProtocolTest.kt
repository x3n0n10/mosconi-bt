package dev.x3n0n10.mosconibt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import dev.x3n0n10.mosconibt.protocol.MosconiProtocol.toByteArray

class MosconiProtocolTest {

    @Test
    fun `logVolumeTable has expected shape`() {
        assertEquals(36, MosconiProtocol.LOG_VOLUME_TABLE.size)
        assertEquals(210, MosconiProtocol.LOG_VOLUME_TABLE.first())
        assertEquals(0, MosconiProtocol.LOG_VOLUME_TABLE.last())
        // Monotonically non-increasing (an audio taper curve).
        for (i in 1 until MosconiProtocol.LOG_VOLUME_TABLE.size) {
            assert(MosconiProtocol.LOG_VOLUME_TABLE[i] <= MosconiProtocol.LOG_VOLUME_TABLE[i - 1])
        }
    }

    @Test
    fun `nearestVolumeStep inverts the table`() {
        for (step in 0..MosconiProtocol.VOLUME_STEPS) {
            assertEquals(step, MosconiProtocol.nearestVolumeStep(MosconiProtocol.LOG_VOLUME_TABLE[step]))
        }
        // A value between two table entries snaps to the closer one.
        assertEquals(0, MosconiProtocol.nearestVolumeStep(205)) // close to 210
        assertEquals(35, MosconiProtocol.nearestVolumeStep(-50)) // clamps toward 0
    }

    @Test
    fun `fresh state defaults match decompiled literals`() {
        val state = MosconiProtocol.State()
        assertEquals(
            listOf(8, 0x43, 0, 15, 64, 64, 0, 0x00),
            state.setSub(15).toList(), // resend without changing sub (already default 15)
        )
    }

    @Test
    fun `setVolume patches only byte 2 of the correct buffer`() {
        val state = MosconiProtocol.State()
        val outFrame = state.setVolume(MosconiProtocol.VolumeTarget.OUTPUT, 0)
        assertEquals(listOf(8, 0x43, 210, 15, 64, 64, 0, 0x00), outFrame.toList())

        val inFrame = state.setVolume(MosconiProtocol.VolumeTarget.INPUT, 35)
        assertEquals(listOf(8, 0x43, 0, 15, 64, 64, 0, 0x80), inFrame.toList())

        // Output buffer must be unaffected by the input-volume write.
        assertEquals(listOf(8, 0x43, 210, 15, 64, 64, 0, 0x00), state.setSub(15).toList())
    }

    @Test
    fun `sub balance fader and preset all patch the output buffer regardless of volume target`() {
        val state = MosconiProtocol.State()
        state.setVolume(MosconiProtocol.VolumeTarget.INPUT, 10) // should not affect output buffer

        assertEquals(listOf(8, 0x43, 0, 5, 64, 64, 0, 0x00), state.setSub(5).toList())
        assertEquals(listOf(8, 0x43, 0, 5, 80, 64, 0, 0x00), state.setBalance(32).toList())
        assertEquals(listOf(8, 0x43, 0, 5, 80, 48, 0, 0x00), state.setFader(0).toList())
        assertEquals(listOf(8, 0x43, 0, 5, 80, 48, 3, 0x00), state.selectPreset(3).toList())
    }

    @Test
    fun `tone controls share one buffer independent of volume buffers`() {
        val state = MosconiProtocol.State()
        assertEquals(listOf(8, 0x44, 0, 8, 8, 0, 0, 0), state.setTreble(0).toList())
        assertEquals(listOf(8, 0x44, 0, 15, 8, 0, 0, 0), state.setMid(15).toList())
        assertEquals(listOf(8, 0x44, 0, 15, 4, 0, 0, 0), state.setBass(4).toList())
    }

    @Test
    fun `out-of-range inputs are clamped, not thrown`() {
        val state = MosconiProtocol.State()
        assertEquals(0, state.setVolume(MosconiProtocol.VolumeTarget.OUTPUT, 999)[2])
        assertEquals(0, state.setSub(-5)[3])
        assertEquals(48, state.setBalance(-1)[4])
        assertEquals(48 + MosconiProtocol.BALANCE_FADER_STEPS, state.setFader(999)[5])
    }

    @Test
    fun `toByteArray maps 0-255 ints straight to bytes`() {
        val bytes = intArrayOf(8, 0x43, 210, 15, 64, 64, 0, 0x80).toByteArray()
        assertEquals(8, bytes.size)
        assertEquals(0x80.toByte(), bytes[7])
        assertEquals(210.toByte(), bytes[2]) // wraps to a negative Byte, which is correct/expected
    }

    @Test
    fun `crc8 matches the standard CRC-8-MAXIMDOW check value`() {
        // Reference check value for the well-known poly-0x31 reflected CRC-8 (MAXIM/DOW-CRC)
        // over ASCII "123456789" is 0xA1. This table is transcribed verbatim from the vendor's
        // Windows GUI, not re-derived - matching the public reference confirms it's that
        // standard algorithm and that the transcription is correct.
        val ascii123456789 = "123456789".map { it.code }.toIntArray()
        assertEquals(0xA1, MosconiProtocol.Crc8.calculate(ascii123456789))
    }

    @Test
    fun `sum8 wraps at 256 and matches a hand-computed value`() {
        assertEquals(45, MosconiProtocol.Sum8.calculate(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
        assertEquals(0, MosconiProtocol.Sum8.calculate(intArrayOf(0xFF, 1))) // 256 wraps to 0
        assertEquals(0xFE, MosconiProtocol.Sum8.calculate(intArrayOf(0xFF, 0xFF))) // 510 -> 0xFE
    }

    @Test
    fun `buildStatusRequest has the expected header and a self-consistent checksum`() {
        val frame = MosconiProtocol.buildStatusRequest()
        assertEquals(6, frame.size)
        assertEquals(listOf(0x07, 0x69, 0, 0x10, 0x0D), frame.toList().dropLast(1))
        assertEquals(MosconiProtocol.Crc8.calculate(frame.copyOfRange(0, 5)), frame.last())
    }

    @Test
    fun `parseStatusResponse decodes the volume-controls page`() {
        val statusByte = 0x11 // preset 2 (bits0-1 = 1) with bit4 set, matching a "data present" ack
        val info = IntArray(20)
        // `info` is 0-indexed here but represents the wire's 1-indexed INFORMATION[1..20],
        // so INFORMATION[N] lives at info[N - 1].
        info[5] = 0x00 // INFORMATION[6]: page-select bit clear -> balance/fader/sub page
        info[7] = MosconiProtocol.LOG_VOLUME_TABLE[20] // INFORMATION[8]: volume raw byte for slider step 20
        info[8] = 70 // INFORMATION[9]: balance raw (48+22)
        info[9] = 50 // INFORMATION[10]: fader raw (48+2)
        info[10] = 9 // INFORMATION[11]: sub level

        val frame = intArrayOf(0x07, 0xE9, 0, statusByte, *info, 0x0D)
        val withChecksum = frame + MosconiProtocol.Sum8.calculate(frame)

        val parsed = assertNotNull(MosconiProtocol.parseStatusResponse(withChecksum))
        assertEquals(1, parsed.preset) // 0-indexed: (0x11 and 3) = 1 -> preset P2
        assertEquals(20, parsed.volumeStep)
        val page = assertNotNull(parsed.page as? MosconiProtocol.InformationPage.VolumeControls)
        assertEquals(22, page.balance)
        assertEquals(2, page.fader)
        assertEquals(9, page.sub)
    }

    @Test
    fun `parseStatusResponse decodes the tone page`() {
        val info = IntArray(20)
        info[5] = 0x80 // INFORMATION[6]: page-select bit set -> bass/mid/treble page
        info[7] = MosconiProtocol.LOG_VOLUME_TABLE[0] // INFORMATION[8]: volume
        info[8] = 3 // INFORMATION[9]: bass
        info[9] = 12 // INFORMATION[10]: mid
        info[10] = 15 // INFORMATION[11]: treble

        val frame = intArrayOf(0x07, 0xE9, 0, 0x00, *info, 0x0D)
        val withChecksum = frame + MosconiProtocol.Sum8.calculate(frame)

        val parsed = assertNotNull(MosconiProtocol.parseStatusResponse(withChecksum))
        val page = assertNotNull(parsed.page as? MosconiProtocol.InformationPage.Tone)
        assertEquals(3, page.bass)
        assertEquals(12, page.mid)
        assertEquals(15, page.treble)
    }

    @Test
    fun `parseStatusResponse decodes a real captured frame from a PICO V2 6-8`() {
        // Reconstructed from an actual live capture (see PROTOCOL.md) - this is what
        // exposed both the missing terminator byte and the CRC-8-vs-Sum8 mixup, so it's
        // kept as a real-hardware regression anchor rather than only synthetic frames.
        val frame = intArrayOf(
            0x07, 0xE9, 0x02, 0xFA, 0x00, 0x02, 0x02, 0x02, 0xFF, 0x80, 0x86, 0x00, 0x09, 0x07,
            0x07, 0x00, 0x00, 0xB9, 0xBF, 0xBD, 0x73, 0x1E, 0x00, 0x00, 0x0D, 0xE1,
        )
        val parsed = assertNotNull(MosconiProtocol.parseStatusResponse(frame))
        assertEquals(0xFA, parsed.statusByte)
        assertEquals(2, parsed.preset)
        assertEquals(35, parsed.volumeStep) // raw 0 -> quietest end of LOG_VOLUME_TABLE
        val page = assertNotNull(parsed.page as? MosconiProtocol.InformationPage.Tone)
        assertEquals(9, page.bass)
        assertEquals(7, page.mid)
        assertEquals(7, page.treble)
    }

    @Test
    fun `parseStatusResponse rejects wrong length, wrong header, missing terminator, and bad checksum`() {
        val info = IntArray(20)
        val goodFrame = intArrayOf(0x07, 0xE9, 0, 0, *info, 0x0D)
        val withGoodChecksum = goodFrame + MosconiProtocol.Sum8.calculate(goodFrame)

        assertNull(MosconiProtocol.parseStatusResponse(withGoodChecksum.copyOf(10))) // wrong length
        assertNull(
            MosconiProtocol.parseStatusResponse(withGoodChecksum.copyOf().also { it[1] = 0x00 }),
        ) // wrong header
        assertNull(
            MosconiProtocol.parseStatusResponse(withGoodChecksum.copyOf().also { it[it.size - 2] = 0x00 }),
        ) // missing terminator
        assertNull(
            MosconiProtocol.parseStatusResponse(
                withGoodChecksum.copyOf().also { it[it.size - 1] = it[it.size - 1] xor 0xFF },
            ),
        ) // corrupted checksum
    }

    @Test
    fun `buildUserDataRequest has the expected header and a self-consistent checksum`() {
        val frame = MosconiProtocol.buildUserDataRequest(address = 0x0102, count = 63)
        assertEquals(10, frame.size)
        assertEquals(listOf(0x07, 0x45, 0, 0x10, 0xA2, 0x01, 0x02, 63, 0x0D), frame.toList().dropLast(1))
        assertEquals(MosconiProtocol.Crc8.calculate(frame.copyOfRange(0, 9)), frame.last())
    }

    @Test
    fun `parseUserDataResponse round-trips a synthetic response`() {
        val address = MosconiProtocol.PRESET_NAME_ADDRESS
        val count = MosconiProtocol.PRESET_NAME_COUNT
        val payload = IntArray(count + 1) { it }
        val frame = intArrayOf(
            0x07, 0xC5, 0, 0x10, 0xA2,
            (address ushr 8) and 0xFF, address and 0xFF, count,
            *payload,
            0x0D,
        )
        val withChecksum = frame + MosconiProtocol.Sum8.calculate(frame)

        val parsed = assertNotNull(MosconiProtocol.parseUserDataResponse(withChecksum, address, count))
        assertEquals(payload.toList(), parsed.toList())
    }

    @Test
    fun `parseUserDataResponse rejects wrong length, wrong echoed address, missing terminator, and bad checksum`() {
        val address = MosconiProtocol.PRESET_NAME_ADDRESS
        val count = MosconiProtocol.PRESET_NAME_COUNT
        val payload = IntArray(count + 1)
        val goodFrame = intArrayOf(
            0x07, 0xC5, 0, 0, 0xA2,
            (address ushr 8) and 0xFF, address and 0xFF, count,
            *payload,
            0x0D,
        )
        val withGoodChecksum = goodFrame + MosconiProtocol.Sum8.calculate(goodFrame)

        assertNull(MosconiProtocol.parseUserDataResponse(withGoodChecksum.copyOf(10), address, count)) // wrong length
        assertNull(
            MosconiProtocol.parseUserDataResponse(
                withGoodChecksum.copyOf().also { it[5] = 0x00 },
                address,
                count,
            ),
        ) // wrong echoed address
        assertNull(
            MosconiProtocol.parseUserDataResponse(
                withGoodChecksum.copyOf().also { it[it.size - 2] = 0x00 },
                address,
                count,
            ),
        ) // missing terminator
        assertNull(
            MosconiProtocol.parseUserDataResponse(
                withGoodChecksum.copyOf().also { it[it.size - 1] = it[it.size - 1] xor 0xFF },
                address,
                count,
            ),
        ) // corrupted checksum
    }

    @Test
    fun `parsePresetNames decodes null-terminated ASCII and the unset sentinel`() {
        val payload = IntArray(MosconiProtocol.PRESET_NAME_LENGTH * MosconiProtocol.PRESET_COUNT) { 0xFF }
        "Sport".forEachIndexed { i, c -> payload[i] = c.code } // preset 1: "Sport", null-padded rest
        payload[5] = 0
        // preset 2 (index 16..31) left as all-0xFF -> unset
        "Loud".forEachIndexed { i, c -> payload[32 + i] = c.code } // preset 3: "Loud"
        payload[36] = 0
        // preset 4 (index 48..63) left as all-0xFF -> unset

        val names = MosconiProtocol.parsePresetNames(payload)
        assertEquals(listOf("Sport", null, "Loud", null), names)
    }
}
