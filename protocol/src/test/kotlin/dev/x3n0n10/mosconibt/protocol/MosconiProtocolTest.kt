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

        val frame = intArrayOf(0x07, 0xE9, 0, statusByte, *info)
        val withChecksum = frame + MosconiProtocol.Crc8.calculate(frame)

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

        val frame = intArrayOf(0x07, 0xE9, 0, 0x00, *info)
        val withChecksum = frame + MosconiProtocol.Crc8.calculate(frame)

        val parsed = assertNotNull(MosconiProtocol.parseStatusResponse(withChecksum))
        val page = assertNotNull(parsed.page as? MosconiProtocol.InformationPage.Tone)
        assertEquals(3, page.bass)
        assertEquals(12, page.mid)
        assertEquals(15, page.treble)
    }

    @Test
    fun `parseStatusResponse rejects wrong length, wrong header, and bad checksum`() {
        val info = IntArray(20)
        val goodFrame = intArrayOf(0x07, 0xE9, 0, 0, *info)
        val withGoodChecksum = goodFrame + MosconiProtocol.Crc8.calculate(goodFrame)

        assertNull(MosconiProtocol.parseStatusResponse(withGoodChecksum.copyOf(10))) // wrong length
        assertNull(
            MosconiProtocol.parseStatusResponse(withGoodChecksum.copyOf().also { it[1] = 0x00 }),
        ) // wrong header
        assertNull(
            MosconiProtocol.parseStatusResponse(
                withGoodChecksum.copyOf().also { it[it.size - 1] = it[it.size - 1] xor 0xFF },
            ),
        ) // corrupted checksum
    }
}
