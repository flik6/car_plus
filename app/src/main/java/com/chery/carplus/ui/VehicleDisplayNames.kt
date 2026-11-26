package com.chery.carplus.ui

import com.chery.carplus.cloud.VehicleSummary
import java.util.Locale

/**
 * Resolves a human-readable Chinese vehicle model name from zyhl 900 metadata,
 * preferring Chinese descriptions (`typeDesc` / `standardName`) and mapping known
 * internal Chery platform codes (such as `M1E`) so raw engineering codes never
 * appear as the primary Home / Vehicle Management title.
 */
internal object VehicleDisplayNames {

    private val KNOWN_MODEL_CODE_MAP: Map<String, String> = mapOf(
        "M1E" to "艾瑞泽8",
        "M1D" to "艾瑞泽5 PLUS",
        "M1X" to "艾瑞泽5 GT",
        "M1A" to "艾瑞泽5",
        "T1E" to "瑞虎8 PLUS",
        "T1D" to "瑞虎8 PRO",
        "T18" to "瑞虎8",
        "T19" to "瑞虎7",
        "T19C" to "瑞虎7 PLUS",
        "T22" to "欧萌达",
        "T26" to "瑞虎9",
        "T1EJ" to "探索06",
        "S61" to "奇瑞大蚂蚁",
        "E03" to "星纪元 ES",
        "E0Y" to "星纪元 ET"
    )

    fun resolve(vehicle: VehicleSummary): String {
        val candidates = listOf(
            vehicle.standardName,
            vehicle.seriesName,
            vehicle.vehicleName,
            vehicle.vehicleType,
            vehicle.vehicleCode,
            vehicle.seriesCode
        ).map { it.trim() }.filter { it.isNotBlank() }

        // 1. Prefer real Chinese model name returned by the cloud (e.g. typeDesc -> seriesName)
        candidates.firstOrNull { containsChinese(it) }?.let { return it }

        // 2. Map known internal platform codes (e.g. M1E -> 艾瑞泽8)
        for (raw in candidates) {
            mapKnownCode(raw)?.let { return it }
        }

        // 3. Fall back to license plate if available, then first non-blank identifier
        if (vehicle.plateNo.isNotBlank()) return vehicle.plateNo.trim()
        return candidates.firstOrNull().orEmpty().ifBlank { "我的座驾" }
    }

    fun normalizeCachedName(cached: String, plateNo: String = ""): String {
        val trimmed = cached.trim()
        if (trimmed.isNotBlank()) {
            if (containsChinese(trimmed)) return trimmed
            mapKnownCode(trimmed)?.let { return it }
            return trimmed
        }
        return plateNo.trim().ifBlank { "我的座驾" }
    }

    private fun mapKnownCode(raw: String): String? {
        val upper = raw.trim().uppercase(Locale.US)
        KNOWN_MODEL_CODE_MAP[upper]?.let { return it }
        // Handle variant suffixes like "M1E-PRO" or "M1E 2023"
        val head = upper.split(" ", "-", "_").firstOrNull().orEmpty()
        return KNOWN_MODEL_CODE_MAP[head]
    }

    private fun containsChinese(text: String): Boolean =
        text.any { ch -> ch in '\u4E00'..'\u9FFF' }
}
