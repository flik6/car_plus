package com.chery.carplus.ble

import android.content.Context
import java.util.Locale

/**
 * Sensitivity presets for Bluetooth passive unlock / walk-away lock.
 * Lock thresholds maintain a 16-20 dB hysteresis gap below unlock thresholds
 * to suppress pocket attenuation and garage multipath bounce.
 */
enum class PassiveSensitivity(
    val key: String,
    val label: String,
    val defaultUnlockRssi: Int,
    val defaultLockRssi: Int,
    val description: String
) {
    NEAR(
        key = "near",
        label = "近距离",
        defaultUnlockRssi = -58,
        defaultLockRssi = -78,
        description = "走到车门旁解锁，离开数米后上锁"
    ),
    STANDARD(
        key = "standard",
        label = "标准",
        defaultUnlockRssi = -65,
        defaultLockRssi = -84,
        description = "靠近 2–3 米解锁，离开 8–12 米上锁"
    ),
    FAR(
        key = "far",
        label = "远距离",
        defaultUnlockRssi = -72,
        defaultLockRssi = -90,
        description = "提前更早解锁，远离更远后上锁"
    );

    companion object {
        fun fromKey(raw: String?): PassiveSensitivity =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: STANDARD
    }
}

data class PassiveEntryConfig(
    val vin: String,
    val enabled: Boolean = false,
    val autoUnlockEnabled: Boolean = true,
    val autoLockEnabled: Boolean = true,
    val sensitivity: PassiveSensitivity = PassiveSensitivity.STANDARD,
    val calibratedUnlockRssi: Int? = null,
    val cdmAssociatedMac: String = "",
    val lastTriggerSummary: String = "",
    val lastTriggerAtMs: Long = 0L
) {
    val effectiveUnlockRssi: Int
        get() = (calibratedUnlockRssi ?: sensitivity.defaultUnlockRssi).coerceIn(-82, -45)

    val effectiveLockRssi: Int
        get() {
            val raw = calibratedUnlockRssi?.minus(18) ?: sensitivity.defaultLockRssi
            return raw.coerceIn(-96, effectiveUnlockRssi - 12)
        }
}

data class VehicleSafetyHint(
    val doorsClosed: Boolean? = null,
    val engineStopped: Boolean? = null,
    val locked: Boolean? = null,
    val updatedAtMs: Long = 0L
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = 10 * 60_000L): Boolean =
        updatedAtMs > 0L && (nowMs - updatedAtMs) in 0L..maxAgeMs
}

enum class PassiveLockSuppressionReason(val key: String, val label: String) {
    NONE("", ""),
    MANUAL_LOCK("manual_lock", "手动锁车"),
    WALK_AWAY_LOCK("walk_away_lock", "离车自动上锁");

    companion object {
        fun fromKey(raw: String?): PassiveLockSuppressionReason =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

data class PassiveLockSuppression(
    val reason: PassiveLockSuppressionReason = PassiveLockSuppressionReason.NONE,
    val recordedAtMs: Long = 0L
)

object PassiveEntryStore {
    private const val PREFS_NAME = "car_plus_passive_entry"
    private const val KEY_ACTIVE_VIN = "active_enabled_vin"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefix(vin: String): String =
        "v_${vin.trim().uppercase(Locale.US)}_"

    fun load(context: Context, vin: String): PassiveEntryConfig {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return PassiveEntryConfig(vin = "")
        val p = prefs(context)
        val k = prefix(normVin)
        val hasCalibrated = p.contains("${k}cal_unlock_rssi")
        val calRssi = if (hasCalibrated) p.getInt("${k}cal_unlock_rssi", -65) else null
        return PassiveEntryConfig(
            vin = normVin,
            enabled = p.getBoolean("${k}enabled", false),
            autoUnlockEnabled = p.getBoolean("${k}auto_unlock", true),
            autoLockEnabled = p.getBoolean("${k}auto_lock", true),
            sensitivity = PassiveSensitivity.fromKey(p.getString("${k}sensitivity", PassiveSensitivity.STANDARD.key)),
            calibratedUnlockRssi = calRssi,
            cdmAssociatedMac = p.getString("${k}cdm_mac", "").orEmpty(),
            lastTriggerSummary = p.getString("${k}last_summary", "").orEmpty(),
            lastTriggerAtMs = p.getLong("${k}last_trigger_at", 0L)
        )
    }

    fun save(context: Context, config: PassiveEntryConfig) {
        val normVin = config.vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return
        val k = prefix(normVin)
        prefs(context).edit().apply {
            putBoolean("${k}enabled", config.enabled)
            putBoolean("${k}auto_unlock", config.autoUnlockEnabled)
            putBoolean("${k}auto_lock", config.autoLockEnabled)
            putString("${k}sensitivity", config.sensitivity.key)
            if (config.calibratedUnlockRssi != null) {
                putInt("${k}cal_unlock_rssi", config.calibratedUnlockRssi)
            } else {
                remove("${k}cal_unlock_rssi")
            }
            putString("${k}cdm_mac", config.cdmAssociatedMac.trim().uppercase(Locale.US))
            putString("${k}last_summary", config.lastTriggerSummary)
            putLong("${k}last_trigger_at", config.lastTriggerAtMs)
            if (config.enabled) {
                putString(KEY_ACTIVE_VIN, normVin)
            } else if (prefs(context).getString(KEY_ACTIVE_VIN, "").equals(normVin, ignoreCase = true)) {
                remove(KEY_ACTIVE_VIN)
            }
        }.commit()
    }

    fun recordTrigger(context: Context, vin: String, summary: String, atMs: Long = System.currentTimeMillis()) {
        val current = load(context, vin)
        if (current.vin.isBlank()) return
        save(context, current.copy(lastTriggerSummary = summary, lastTriggerAtMs = atMs))
    }

    fun getActiveEnabledVin(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_VIN, "").orEmpty().trim().uppercase(Locale.US)

    fun setActiveEnabledVin(context: Context, vin: String) {
        val normVin = vin.trim().uppercase(Locale.US)
        prefs(context).edit().apply {
            if (normVin.isBlank()) remove(KEY_ACTIVE_VIN) else putString(KEY_ACTIVE_VIN, normVin)
        }.commit()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    fun updateVehicleSafetyHint(
        context: Context,
        vin: String,
        doorsClosed: Boolean?,
        engineStopped: Boolean?,
        locked: Boolean?,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return
        val k = prefix(normVin)
        prefs(context).edit().apply {
            putInt("${k}hint_doors", triStateToInt(doorsClosed))
            putInt("${k}hint_engine", triStateToInt(engineStopped))
            putInt("${k}hint_locked", triStateToInt(locked))
            putLong("${k}hint_at", updatedAtMs)
        }.apply()
    }

    fun getVehicleSafetyHint(context: Context, vin: String): VehicleSafetyHint {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return VehicleSafetyHint()
        val p = prefs(context)
        val k = prefix(normVin)
        return VehicleSafetyHint(
            doorsClosed = intToTriState(p.getInt("${k}hint_doors", -1)),
            engineStopped = intToTriState(p.getInt("${k}hint_engine", -1)),
            locked = intToTriState(p.getInt("${k}hint_locked", -1)),
            updatedAtMs = p.getLong("${k}hint_at", 0L)
        )
    }

    fun updateVehicleLockHint(
        context: Context,
        vin: String,
        locked: Boolean,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return
        val existing = getVehicleSafetyHint(context, normVin).takeIf { it.isFresh(updatedAtMs) }
        updateVehicleSafetyHint(
            context = context,
            vin = normVin,
            doorsClosed = existing?.doorsClosed,
            engineStopped = existing?.engineStopped,
            locked = locked,
            updatedAtMs = updatedAtMs
        )
    }

    fun recordLockSuppression(
        context: Context,
        vin: String,
        reason: PassiveLockSuppressionReason,
        recordedAtMs: Long = System.currentTimeMillis()
    ) {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return
        val k = prefix(normVin)
        prefs(context).edit().apply {
            if (reason == PassiveLockSuppressionReason.NONE) {
                remove("${k}suppress_reason")
                remove("${k}suppress_at")
            } else {
                putString("${k}suppress_reason", reason.key)
                putLong("${k}suppress_at", recordedAtMs)
            }
        }.apply()
    }

    fun getLockSuppression(context: Context, vin: String): PassiveLockSuppression {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return PassiveLockSuppression()
        val p = prefs(context)
        val k = prefix(normVin)
        val reason = PassiveLockSuppressionReason.fromKey(p.getString("${k}suppress_reason", ""))
        val atMs = p.getLong("${k}suppress_at", 0L)
        return if (reason == PassiveLockSuppressionReason.NONE || atMs <= 0L) {
            PassiveLockSuppression()
        } else {
            PassiveLockSuppression(reason = reason, recordedAtMs = atMs)
        }
    }

    fun clearLockSuppression(context: Context, vin: String) {
        recordLockSuppression(context, vin, PassiveLockSuppressionReason.NONE, 0L)
    }

    private fun triStateToInt(value: Boolean?): Int = when (value) {
        true -> 1
        false -> 0
        null -> -1
    }

    private fun intToTriState(raw: Int): Boolean? = when (raw) {
        1 -> true
        0 -> false
        else -> null
    }
}
