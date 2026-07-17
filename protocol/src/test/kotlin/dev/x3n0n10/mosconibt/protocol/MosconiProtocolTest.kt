package dev.x3n0n10.mosconibt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `sub geo and preset all patch the output buffer regardless of volume target`() {
        val state = MosconiProtocol.State()
        state.setVolume(MosconiProtocol.VolumeTarget.INPUT, 10) // should not affect output buffer

        assertEquals(listOf(8, 0x43, 0, 5, 64, 64, 0, 0x00), state.setSub(5).toList())
        assertEquals(listOf(8, 0x43, 0, 5, 80, 64, 0, 0x00), state.setGeoX(32).toList())
        assertEquals(listOf(8, 0x43, 0, 5, 80, 48, 0, 0x00), state.setGeoY(0).toList())
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
        assertEquals(48, state.setGeoX(-1)[4])
        assertEquals(48 + MosconiProtocol.GEO_STEPS, state.setGeoY(999)[5])
    }

    @Test
    fun `toByteArray maps 0-255 ints straight to bytes`() {
        val bytes = intArrayOf(8, 0x43, 210, 15, 64, 64, 0, 0x80).toByteArray()
        assertEquals(8, bytes.size)
        assertEquals(0x80.toByte(), bytes[7])
        assertEquals(210.toByte(), bytes[2]) // wraps to a negative Byte, which is correct/expected
    }
}
