package com.example.heartzonemonitor.data.ble

/**
 * Sensor skin-contact state reported by bits 1–2 of the Bluetooth SIG Heart Rate Measurement
 * (`0x2A37`) flags byte.
 */
enum class SensorContactStatus {
    NOT_SUPPORTED,
    NOT_DETECTED,
    DETECTED
}

/**
 * Decoded Bluetooth SIG Heart Rate Measurement (`0x2A37`) payload from a Heart Rate Monitor (HRM).
 */
data class HeartRateMeasurement(
    val bpm: Int,
    val is16BitFormat: Boolean,
    val sensorContactStatus: SensorContactStatus,
    val energyExpendedKj: Int? = null,
    val rrIntervals1024: List<Int> = emptyList(),
    val timestampEpochMs: Long = 0L
) {
    /**
     * Whether the Heart Rate Monitor (HRM) indicates support for the skin-contact detection feature.
     */
    val isSensorContactSupported: Boolean
        get() = sensorContactStatus != SensorContactStatus.NOT_SUPPORTED

    /**
     * Effective skin-contact status for auto-pause logic: returns `false` only when the
     * Heart Rate Monitor (HRM) explicitly reports [SensorContactStatus.NOT_DETECTED], preventing
     * false auto-pauses on HRMs that do not support contact detection.
     */
    val sensorContact: Boolean
        get() = sensorContactStatus != SensorContactStatus.NOT_DETECTED

    /**
     * RR-intervals converted from `1/1024` second units into milliseconds.
     */
    val rrIntervalsMs: List<Double>
        get() = rrIntervals1024.map { it * MILLIS_PER_SECOND / RR_TICKS_PER_SECOND }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val RR_TICKS_PER_SECOND = 1024.0
    }
}

/**
 * Pure Kotlin parser for Bluetooth SIG Heart Rate Measurement (`0x2A37`) Little-Endian byte arrays.
 */
object HeartRateMeasurementParser {

    private const val FLAG_HEART_RATE_16_BIT = 0x01
    private const val MASK_SENSOR_CONTACT = 0x06
    private const val SENSOR_CONTACT_NOT_DETECTED = 0x04
    private const val SENSOR_CONTACT_DETECTED = 0x06
    private const val FLAG_ENERGY_EXPENDED_PRESENT = 0x08
    private const val FLAG_RR_INTERVAL_PRESENT = 0x10

    /**
     * Parses a raw `0x2A37` payload into a [HeartRateMeasurement], returning `null` if the payload
     * is empty, truncated, or malformed.
     */
    fun parse(payload: ByteArray, timestampEpochMs: Long = 0L): HeartRateMeasurement? {
        if (payload.isEmpty()) return null

        val flags = payload[0].toInt() and 0xFF
        var offset = 1

        val is16BitFormat = (flags and FLAG_HEART_RATE_16_BIT) != 0
        val bpmByteCount = if (is16BitFormat) 2 else 1
        if (payload.size < offset + bpmByteCount) return null

        val bpm = if (is16BitFormat) {
            readUInt16Le(payload, offset)
        } else {
            payload[offset].toInt() and 0xFF
        }
        offset += bpmByteCount

        val sensorContactStatus = when (flags and MASK_SENSOR_CONTACT) {
            SENSOR_CONTACT_NOT_DETECTED -> SensorContactStatus.NOT_DETECTED
            SENSOR_CONTACT_DETECTED -> SensorContactStatus.DETECTED
            else -> SensorContactStatus.NOT_SUPPORTED
        }

        val energyExpendedKj = if ((flags and FLAG_ENERGY_EXPENDED_PRESENT) != 0) {
            if (payload.size < offset + 2) return null
            readUInt16Le(payload, offset).also { offset += 2 }
        } else {
            null
        }

        val rrIntervals1024 = if ((flags and FLAG_RR_INTERVAL_PRESENT) != 0) {
            val remainingBytes = payload.size - offset
            if (remainingBytes < 2 || remainingBytes % 2 != 0) return null

            val intervalCount = remainingBytes / 2
            List(intervalCount) { index ->
                readUInt16Le(payload, offset + index * 2)
            }
        } else {
            emptyList()
        }

        return HeartRateMeasurement(
            bpm = bpm,
            is16BitFormat = is16BitFormat,
            sensorContactStatus = sensorContactStatus,
            energyExpendedKj = energyExpendedKj,
            rrIntervals1024 = rrIntervals1024,
            timestampEpochMs = timestampEpochMs
        )
    }

    private fun readUInt16Le(payload: ByteArray, offset: Int): Int =
        (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
}

/**
 * Pure Kotlin parser for Bluetooth SIG Battery Level (`0x2A19`) characteristic payloads.
 */
object BatteryLevelParser {

    private const val MAX_BATTERY_LEVEL_PERCENT = 100

    /**
     * Parses a raw `0x2A19` payload into a battery percentage in `0..100`, or returns `null` if
     * the payload is empty or exceeds `100`.
     */
    fun parse(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        val level = payload[0].toInt() and 0xFF
        return if (level <= MAX_BATTERY_LEVEL_PERCENT) level else null
    }
}
