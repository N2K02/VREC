package com.vrec.app.experimental.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpusPacketTimingTest {
    @Test fun silkAndCeltDurationsUseActualToc() {
        assertEquals(480, OpusPacketTiming.samples(byteArrayOf(0)))
        assertEquals(960, OpusPacketTiming.samples(byteArrayOf(8)))
        assertEquals(1920, OpusPacketTiming.samples(byteArrayOf(16)))
        assertEquals(2880, OpusPacketTiming.samples(byteArrayOf(24)))
        assertEquals(120, OpusPacketTiming.samples(byteArrayOf(-128)))
        assertEquals(960, OpusPacketTiming.samples(byteArrayOf(-8)))
    }

    @Test fun countCodeOneAndThreeCanContainMultipleFrames() {
        assertEquals(960, OpusPacketTiming.samples(byteArrayOf(1, 2)))
        assertEquals(1920, OpusPacketTiming.samples(byteArrayOf(11, 2)))
        assertEquals(1440, OpusPacketTiming.samples(byteArrayOf(3, 3)))
    }

    @Test fun invalidTocCannotBecomeMisleadingDuration() {
        assertThrows(IllegalArgumentException::class.java) { OpusPacketTiming.samples(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { OpusPacketTiming.samples(byteArrayOf(3)) }
        assertThrows(IllegalArgumentException::class.java) { OpusPacketTiming.samples(byteArrayOf(3, 0)) }
        assertThrows(IllegalArgumentException::class.java) { OpusPacketTiming.samples(byteArrayOf(11, 49)) }
    }
}
