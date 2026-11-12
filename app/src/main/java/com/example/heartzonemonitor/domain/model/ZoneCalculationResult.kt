package com.example.heartzonemonitor.domain.model

data class ZoneCalculationResult(
    val hrMax: Int,
    val calculationMode: ZoneCalculationMode,
    val zones: List<ZoneRange>
) {
    val size: Int get() = zones.size
    val zone1: ZoneRange get() = zones[0]
    val zone2: ZoneRange get() = zones[1]
    val zone3: ZoneRange get() = zones[2]
    val zone4: ZoneRange get() = zones[3]
    val zone5: ZoneRange get() = zones[4]

    fun getZoneForBpm(bpm: Int): HeartZone? {
        if (bpm < zone1.minBpm) return null
        return zones.lastOrNull { bpm >= it.minBpm }?.zone ?: HeartZone.ZONE_5
    }
}
