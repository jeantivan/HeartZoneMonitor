package com.example.heartzonemonitor.domain.model

enum class HeartZone(
    val zoneIndex: Int,
    val zoneName: String,
    val minPercent: Double,
    val maxPercent: Double,
    val colorArgb: Long,
    val colorHex: String
) {
    ZONE_1(
        zoneIndex = 1,
        zoneName = "Active Recovery",
        minPercent = 0.50,
        maxPercent = 0.60,
        colorArgb = 0xFF64748B,
        colorHex = "#64748B"
    ),
    ZONE_2(
        zoneIndex = 2,
        zoneName = "Aerobic Base",
        minPercent = 0.60,
        maxPercent = 0.70,
        colorArgb = 0xFF3B82F6,
        colorHex = "#3B82F6"
    ),
    ZONE_3(
        zoneIndex = 3,
        zoneName = "Tempo",
        minPercent = 0.70,
        maxPercent = 0.80,
        colorArgb = 0xFF10B981,
        colorHex = "#10B981"
    ),
    ZONE_4(
        zoneIndex = 4,
        zoneName = "Lactate Threshold",
        minPercent = 0.80,
        maxPercent = 0.90,
        colorArgb = 0xFFF59E0B,
        colorHex = "#F59E0B"
    ),
    ZONE_5(
        zoneIndex = 5,
        zoneName = "VO2max",
        minPercent = 0.90,
        maxPercent = 1.00,
        colorArgb = 0xFFEF4444,
        colorHex = "#EF4444"
    );

    companion object {
        fun fromIndex(index: Int): HeartZone {
            return entries.firstOrNull { it.zoneIndex == index }
                ?: throw IllegalArgumentException("Invalid zone index: $index. Must be between 1 and 5.")
        }
    }
}
