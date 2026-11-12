package com.example.heartzonemonitor.domain.model

data class ZoneRange(
    val zone: HeartZone,
    val minBpm: Int,
    val maxBpm: Int
)
