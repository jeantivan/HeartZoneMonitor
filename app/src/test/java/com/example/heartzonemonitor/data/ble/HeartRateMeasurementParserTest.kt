package com.example.heartzonemonitor.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateMeasurementParserTest {

    @Test
    fun `parses 8-bit BPM including values above 127`() {
        val values = listOf(0, 62, 127, 148, 200, 255)
        for (expectedBpm in values) {
            val payload = byteArrayOf(0x00, expectedBpm.toByte())
            val parsed = HeartRateMeasurementParser.parse(payload, timestampEpochMs = 12345L)

            assertNotNull("Expected valid measurement for BPM $expectedBpm", parsed)
            parsed!!
            assertEquals(expectedBpm, parsed.bpm)
            assertFalse(parsed.is16BitFormat)
            assertEquals(SensorContactStatus.NOT_SUPPORTED, parsed.sensorContactStatus)
            assertFalse(parsed.isSensorContactSupported)
            assertTrue(parsed.sensorContact)
            assertNull(parsed.energyExpendedKj)
            assertTrue(parsed.rrIntervals1024.isEmpty())
            assertEquals(12345L, parsed.timestampEpochMs)
        }
    }

    @Test
    fun `parses 16-bit Little-Endian BPM accurately`() {
        // 256 = 0x0100 -> LE [0x00, 0x01]
        val parsed256 = HeartRateMeasurementParser.parse(byteArrayOf(0x01, 0x00, 0x01))
        assertNotNull(parsed256)
        assertEquals(256, parsed256!!.bpm)
        assertTrue(parsed256.is16BitFormat)

        // 300 = 0x012C -> LE [0x2C, 0x01]
        val parsed300 = HeartRateMeasurementParser.parse(byteArrayOf(0x01, 0x2C, 0x01))
        assertNotNull(parsed300)
        assertEquals(300, parsed300!!.bpm)
        assertTrue(parsed300.is16BitFormat)

        // Unsigned high byte > 127: 0x80FE = 33022 -> LE [0xFE, 0x80]
        val parsedHigh = HeartRateMeasurementParser.parse(
            byteArrayOf(0x01, 0xFE.toByte(), 0x80.toByte())
        )
        assertNotNull(parsedHigh)
        assertEquals(33022, parsedHigh!!.bpm)
    }

    @Test
    fun `parses all sensor contact flags and boolean semantics`() {
        // Bits 1-2 = 00 (not supported)
        val bits00 = HeartRateMeasurementParser.parse(byteArrayOf(0x00, 72))!!
        assertEquals(SensorContactStatus.NOT_SUPPORTED, bits00.sensorContactStatus)
        assertFalse(bits00.isSensorContactSupported)
        assertTrue(bits00.sensorContact)

        // Bits 1-2 = 01 (0x02 -> bit 2 is 0, still not supported per Bluetooth SIG spec)
        val bits01 = HeartRateMeasurementParser.parse(byteArrayOf(0x02, 72))!!
        assertEquals(SensorContactStatus.NOT_SUPPORTED, bits01.sensorContactStatus)
        assertFalse(bits01.isSensorContactSupported)
        assertTrue(bits01.sensorContact)

        // Bits 1-2 = 10 (0x04 -> supported, contact NOT detected)
        val bits10 = HeartRateMeasurementParser.parse(byteArrayOf(0x04, 72))!!
        assertEquals(SensorContactStatus.NOT_DETECTED, bits10.sensorContactStatus)
        assertTrue(bits10.isSensorContactSupported)
        assertFalse(bits10.sensorContact)

        // Bits 1-2 = 11 (0x06 -> supported, contact DETECTED)
        val bits11 = HeartRateMeasurementParser.parse(byteArrayOf(0x06, 72))!!
        assertEquals(SensorContactStatus.DETECTED, bits11.sensorContactStatus)
        assertTrue(bits11.isSensorContactSupported)
        assertTrue(bits11.sensorContact)
    }

    @Test
    fun `parses energy expended alone and skips offset before RR`() {
        // Flags: 0x08 (energy expended), 8-bit BPM = 135 (0x87), Energy = 515 kJ (0x0203 -> [0x03, 0x02])
        val energyOnlyPayload = byteArrayOf(0x08, 0x87.toByte(), 0x03, 0x02)
        val energyOnly = HeartRateMeasurementParser.parse(energyOnlyPayload)!!
        assertEquals(135, energyOnly.bpm)
        assertEquals(515, energyOnly.energyExpendedKj)
        assertTrue(energyOnly.rrIntervals1024.isEmpty())

        // Flags: 0x1F (16-bit BPM + contact detected + energy expended + RR-intervals)
        // BPM = 300 ([0x2C, 0x01]), Energy = 1025 kJ ([0x01, 0x04]), RR = [512 (0x0200), 1024 (0x0400)]
        val combinedPayload = byteArrayOf(
            0x1F,
            0x2C, 0x01,
            0x01, 0x04,
            0x00, 0x02,
            0x00, 0x04
        )
        val combined = HeartRateMeasurementParser.parse(combinedPayload)!!
        assertEquals(300, combined.bpm)
        assertTrue(combined.is16BitFormat)
        assertEquals(SensorContactStatus.DETECTED, combined.sensorContactStatus)
        assertEquals(1025, combined.energyExpendedKj)
        assertEquals(listOf(512, 1024), combined.rrIntervals1024)
        assertEquals(500.0, combined.rrIntervalsMs[0], 1e-6)
        assertEquals(1000.0, combined.rrIntervalsMs[1], 1e-6)
    }

    @Test
    fun `unpacks single and multiple RR intervals in 1_1024s units`() {
        // Single RR-interval: 415 * 1000.0 / 1024.0 ms
        val singlePayload = byteArrayOf(
            0x16, // contact detected (0x06) + RR-interval present (0x10)
            0x94.toByte(), // 148 BPM
            0x9F.toByte(), 0x01 // 415 in Little-Endian
        )
        val singleParsed = HeartRateMeasurementParser.parse(singlePayload)!!
        assertEquals(148, singleParsed.bpm)
        assertEquals(listOf(415), singleParsed.rrIntervals1024)
        assertEquals(415 * 1000.0 / 1024.0, singleParsed.rrIntervalsMs.single(), 1e-6)

        // 9 RR-intervals fitting inside a 23-byte ATT MTU notification (1 flags + 1 BPM + 18 RR bytes)
        val nineIntervals = listOf(400, 412, 512, 600, 750, 819, 900, 1024, 1100)
        val ninePayload = ByteArray(2 + nineIntervals.size * 2).apply {
            this[0] = 0x16
            this[1] = 165.toByte()
            nineIntervals.forEachIndexed { index, rr ->
                this[2 + index * 2] = (rr and 0xFF).toByte()
                this[3 + index * 2] = ((rr shr 8) and 0xFF).toByte()
            }
        }
        val nineParsed = HeartRateMeasurementParser.parse(ninePayload)!!
        assertEquals(165, nineParsed.bpm)
        assertEquals(nineIntervals, nineParsed.rrIntervals1024)
        assertEquals(9, nineParsed.rrIntervalsMs.size)
        nineIntervals.forEachIndexed { idx, raw ->
            assertEquals(raw * 1000.0 / 1024.0, nineParsed.rrIntervalsMs[idx], 1e-6)
        }
    }

    @Test
    fun `interoperates with FakeBleTransport packet builder`() {
        val packet8Bit = FakeBleTransport.buildHeartRateMeasurementPacket(
            bpm = 174,
            sensorContactSupported = true,
            sensorContactDetected = false,
            rrIntervals1024 = listOf(355, 360)
        )
        val parsed8Bit = HeartRateMeasurementParser.parse(packet8Bit, timestampEpochMs = 999L)!!
        assertEquals(174, parsed8Bit.bpm)
        assertFalse(parsed8Bit.is16BitFormat)
        assertEquals(SensorContactStatus.NOT_DETECTED, parsed8Bit.sensorContactStatus)
        assertFalse(parsed8Bit.sensorContact)
        assertEquals(listOf(355, 360), parsed8Bit.rrIntervals1024)
        assertEquals(999L, parsed8Bit.timestampEpochMs)

        val packet16Bit = FakeBleTransport.buildHeartRateMeasurementPacket(
            bpm = 280,
            sensorContactSupported = false,
            sensorContactDetected = false,
            rrIntervals1024 = emptyList()
        )
        val parsed16Bit = HeartRateMeasurementParser.parse(packet16Bit)!!
        assertEquals(280, parsed16Bit.bpm)
        assertTrue(parsed16Bit.is16BitFormat)
        assertEquals(SensorContactStatus.NOT_SUPPORTED, parsed16Bit.sensorContactStatus)
        assertTrue(parsed16Bit.sensorContact)
    }

    @Test
    fun `returns null cleanly on empty truncated or malformed payloads`() {
        val invalidPayloads = listOf(
            byteArrayOf(), // completely empty
            byteArrayOf(0x00), // flags only, missing 8-bit BPM
            byteArrayOf(0x01, 0x48), // 16-bit flag, only 1 BPM byte
            byteArrayOf(0x08, 0x48), // energy flag set, missing energy bytes
            byteArrayOf(0x08, 0x48, 0x01), // energy flag set, truncated 1-byte energy
            byteArrayOf(0x10, 0x48), // RR flag set, 0 RR bytes remaining
            byteArrayOf(0x10, 0x48, 0x01), // RR flag set, odd byte count (1 byte)
            byteArrayOf(0x10, 0x48, 0x01, 0x02, 0x03), // RR flag set, odd byte count (3 bytes)
            byteArrayOf(0x18, 0x48, 0x10, 0x00, 0x05) // energy + RR, odd trailing RR byte
        )

        invalidPayloads.forEach { payload ->
            assertNull(
                "Expected null for malformed payload ${payload.contentToString()}",
                HeartRateMeasurementParser.parse(payload)
            )
        }
    }

    @Test
    fun `BatteryLevelParser parses valid bytes and rejects out of range`() {
        assertEquals(0, BatteryLevelParser.parse(byteArrayOf(0)))
        assertEquals(87, BatteryLevelParser.parse(byteArrayOf(87)))
        assertEquals(100, BatteryLevelParser.parse(byteArrayOf(100)))

        assertNull(BatteryLevelParser.parse(byteArrayOf()))
        assertNull(BatteryLevelParser.parse(byteArrayOf(101)))
        assertNull(BatteryLevelParser.parse(byteArrayOf(0xFF.toByte())))
    }
}
