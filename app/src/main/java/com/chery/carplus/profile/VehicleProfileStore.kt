package com.chery.carplus.profile

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * A complete digital-key profile belonging to one vehicle/account pairing.
 *
 * Key material is intentionally retained as exact hexadecimal bytes so it can be
 * transferred to the BLE session layer without normalizing or re-encoding it.
 */
data class VehicleProfile(
    val vin: String,
    val userId: String,
    val mobileNo: String,
    val bleName: String,
    val bleMac: String,
    val deviceId: String,
    val accountType: Int = 0,
    val rootKeyHex: String,
    val infoHex: String,
    val edfHex: String,
    val validFrom: String = "",
    val validTo: String = "",
    val secAHex: String = "",
    val secBHex: String = "",
    val keyRole: Int = 1,
    val accessToken: String = "",
    val sdkToken: String = "",
    val controlPin: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun rootKeyBytes(): ByteArray = VckInfoParser.hexToBytes(rootKeyHex)

    fun infoBytes(): ByteArray = VckInfoParser.hexToBytes(infoHex)

    fun edfBytes(): ByteArray = VckInfoParser.hexToBytes(edfHex)

    /** secA lives at info.bin[6..37], with secAHex retained as a fallback. */
    fun secABytes(): ByteArray {
        val info = infoBytes()
        return when {
            info.size >= 38 -> info.copyOfRange(6, 38)
            secAHex.isNotBlank() -> VckInfoParser.hexToBytes(secAHex)
            else -> ByteArray(0)
        }
    }

    /** secB lives at info.bin[87..118], with secBHex retained as a fallback. */
    fun secBBytes(): ByteArray {
        val info = infoBytes()
        return when {
            info.size >= 119 -> info.copyOfRange(87, 119)
            secBHex.isNotBlank() -> VckInfoParser.hexToBytes(secBHex)
            else -> ByteArray(0)
        }
    }
}

/** Parsed fields of the fixed 147-byte Chery vckInfo payload. */
data class VckInfoParsed(
    val infoLen: Int,
    val headerHex: String,
    val secAHex: String,
    val validFrom: String,
    val validTo: String,
    val vin: String,
    val secBHex: String,
    val mobileNo: String,
    val keyRole: Int,
    val tailHex: String
)

object VckInfoParser {
    const val INFO_SIZE = 147

    /**
     * Parses the verified timestamp-v1 layout:
     * [0:6] header, [6:38] secA, [38:54] validFrom, [54:70] validTo,
     * [70:87] VIN, [87:119] secB, [119:130] mobile, [130] role,
     * [131:147] tail.
     */
    fun parse(infoBytes: ByteArray): VckInfoParsed {
        require(infoBytes.size == INFO_SIZE) {
            "vckInfo must be exactly $INFO_SIZE bytes, got ${infoBytes.size}"
        }
        return VckInfoParsed(
            infoLen = infoBytes.size,
            headerHex = bytesToHex(infoBytes.copyOfRange(0, 6)),
            secAHex = bytesToHex(infoBytes.copyOfRange(6, 38)),
            validFrom = String(infoBytes.copyOfRange(38, 54), Charsets.US_ASCII),
            validTo = String(infoBytes.copyOfRange(54, 70), Charsets.US_ASCII),
            vin = String(infoBytes.copyOfRange(70, 87), Charsets.US_ASCII),
            secBHex = bytesToHex(infoBytes.copyOfRange(87, 119)),
            mobileNo = String(infoBytes.copyOfRange(119, 130), Charsets.US_ASCII),
            keyRole = infoBytes[130].toInt() and 0xFF,
            tailHex = bytesToHex(infoBytes.copyOfRange(131, 147))
        )
    }

    /**
     * Decodes an i-se filename prefix generated from hex(userId + VIN + " ").
     * The optional _info/_edf/_key suffix and a leading path are tolerated.
     */
    fun decodeIseFilenamePrefix(hexPrefix: String): Pair<String, String>? {
        var value = hexPrefix.trim().substringAfterLast('/').substringAfterLast('\\')
        for (suffix in listOf("_info", "_edf", "_key")) {
            if (value.endsWith(suffix, ignoreCase = true)) {
                value = value.dropLast(suffix.length)
                break
            }
        }
        if (value.isBlank() || value.length % 2 != 0) return null

        val decoded = runCatching {
            String(hexToBytes(value), Charsets.US_ASCII)
        }.getOrNull() ?: return null
        val identity = decoded.trimEnd(' ', '\u0000')
        if (identity.length <= 17) return null
        val userId = identity.dropLast(17)
        val vin = identity.takeLast(17)
        if (userId.isBlank() || vin.isBlank()) return null
        return userId to vin
    }

    /** Chery Android device ID: lowercase MD5("0" + androidId). */
    fun computeCheryDeviceId(androidId: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(("0" + androidId.trim()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xFF) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val compact = hex.trim()
            .replace(Regex("[\\s:_-]+"), "")
        if (compact.isEmpty()) return ByteArray(0)
        require(compact.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(compact.length / 2) { index ->
            val pair = compact.substring(index * 2, index * 2 + 2)
            pair.toIntOrNull(16)?.toByte() ?: throw IllegalArgumentException("invalid hex byte: $pair")
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }
}

/** JSON-backed, multi-vehicle profile store with one active VIN. */
class VehicleProfileStore(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private var accountDraft: VehicleProfile? = null

    init {
        synchronized(lock) {
            ensureSeedLocked()
        }
    }

    fun listProfiles(): List<VehicleProfile> = synchronized(lock) {
        ensureSeedLocked()
        readProfilesLocked()
    }

    /**
     * Returns the selected vehicle profile, or an empty non-persisted draft
     * before the first vehicle is selected. Account login may temporarily save
     * a VIN-less draft so the bound-vehicle list can be retrieved without
     * seeding any test vehicle into persistent storage.
     */
    fun getActiveProfile(): VehicleProfile = synchronized(lock) {
        ensureSeedLocked()
        val profiles = readProfilesLocked()
        val activeVin = prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
        val active = profiles.firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
            ?: profiles.firstOrNull()
            ?: accountDraft
            ?: emptyProfile()
        if (active.vin.isNotBlank() && !active.vin.equals(activeVin, ignoreCase = true)) {
            prefs.edit().putString(KEY_ACTIVE_VIN, active.vin).apply()
        }
        active
    }

    /** MD5("0" + Settings.Secure.ANDROID_ID), matching the zyhl device ID. */
    fun defaultDeviceId(): String {
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        return if (androidId.isBlank()) "" else VckInfoParser.computeCheryDeviceId(androidId)
    }

    fun setActiveVin(vin: String): VehicleProfile? {
        synchronized(lock) {
            ensureSeedLocked()
            val match = readProfilesLocked().firstOrNull { it.vin.equals(vin, ignoreCase = true) }
                ?: return null
            prefs.edit().putString(KEY_ACTIVE_VIN, match.vin).apply()
            return match
        }
    }

    fun saveProfile(profile: VehicleProfile, activate: Boolean = true): VehicleProfile = synchronized(lock) {
        val normalized = profile.copy(
            vin = profile.vin.trim().uppercase(Locale.US),
            userId = profile.userId.trim(),
            mobileNo = profile.mobileNo.trim(),
            bleName = profile.bleName.trim(),
            bleMac = profile.bleMac.trim().uppercase(Locale.US),
            deviceId = profile.deviceId.trim(),
            rootKeyHex = compactHex(profile.rootKeyHex),
            infoHex = compactHex(profile.infoHex),
            edfHex = compactHex(profile.edfHex),
            secAHex = compactHex(profile.secAHex),
            secBHex = compactHex(profile.secBHex),
            updatedAt = System.currentTimeMillis()
        )

        if (normalized.vin.isBlank()) {
            accountDraft = normalized
            return@synchronized normalized
        }

        val profiles = readProfilesLocked()
            .filterNot { it.vin.equals(normalized.vin, ignoreCase = true) }
            .toMutableList()
        profiles.add(0, normalized)
        accountDraft = null

        val activeVin = if (activate) {
            normalized.vin
        } else {
            prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
                .takeIf { active -> profiles.any { it.vin.equals(active, ignoreCase = true) } }
                ?: normalized.vin
        }
        persistLocked(profiles, activeVin)
        normalized
    }

    fun deleteProfile(vin: String) = synchronized(lock) {
        if (vin.isBlank()) {
            accountDraft = null
            return@synchronized
        }
        val remaining = readProfilesLocked()
            .filterNot { it.vin.equals(vin, ignoreCase = true) }
            .toMutableList()
        val previousActive = prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
        val nextActive = when {
            remaining.isEmpty() -> ""
            remaining.any { it.vin.equals(previousActive, ignoreCase = true) } -> previousActive
            else -> remaining.first().vin
        }
        persistLocked(remaining, nextActive)
    }

    /** Removes every persisted profile and account-only draft from the app. */
    fun clearAllProfiles() = synchronized(lock) {
        accountDraft = null
        prefs.edit().clear().commit()
    }

    fun importFromRawHex(
        userId: String,
        mobileNo: String,
        vin: String,
        bleName: String,
        bleMac: String,
        deviceId: String,
        accountType: Int,
        rootKeyHex: String,
        infoHex: String,
        edfHex: String,
        accessToken: String = "",
        sdkToken: String = "",
        controlPin: String = ""
    ): VehicleProfile {
        val normalizedVin = vin.trim().uppercase(Locale.US)
        require(normalizedVin.isNotBlank()) { "VIN is required" }

        val rootKey = VckInfoParser.hexToBytes(rootKeyHex)
        val info = VckInfoParser.hexToBytes(infoHex)
        val edf = VckInfoParser.hexToBytes(edfHex)
        require(rootKey.size == 16) { "K_root must be 16 bytes, got ${rootKey.size}" }
        require(info.size == VckInfoParser.INFO_SIZE) {
            "vckInfo must be ${VckInfoParser.INFO_SIZE} bytes, got ${info.size}"
        }
        require(edf.isNotEmpty()) { "edf is required" }

        val parsed = VckInfoParser.parse(info)
        if (parsed.vin.isNotBlank()) {
            require(parsed.vin.equals(normalizedVin, ignoreCase = true)) {
                "vckInfo VIN ${parsed.vin} does not match profile VIN $normalizedVin"
            }
        }

        return saveProfile(
            VehicleProfile(
                vin = normalizedVin,
                userId = userId.trim(),
                mobileNo = mobileNo.trim(),
                bleName = bleName.trim(),
                bleMac = bleMac.trim().uppercase(Locale.US),
                deviceId = deviceId.trim(),
                accountType = accountType,
                rootKeyHex = VckInfoParser.bytesToHex(rootKey),
                infoHex = VckInfoParser.bytesToHex(info),
                edfHex = VckInfoParser.bytesToHex(edf),
                validFrom = parsed.validFrom,
                validTo = parsed.validTo,
                secAHex = parsed.secAHex,
                secBHex = parsed.secBHex,
                keyRole = parsed.keyRole,
                accessToken = accessToken,
                sdkToken = sdkToken,
                controlPin = controlPin
            ),
            activate = true
        )
    }

    private fun ensureSeedLocked() {
        val existing = readProfilesLocked()
        if (existing.isEmpty()) {
            if (prefs.getString(KEY_ACTIVE_VIN, "").orEmpty().isNotBlank()) {
                prefs.edit().putString(KEY_ACTIVE_VIN, "").apply()
            }
            return
        }
        val active = prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
        if (active.isBlank() || existing.none { it.vin.equals(active, ignoreCase = true) }) {
            prefs.edit().putString(KEY_ACTIVE_VIN, existing.first().vin).apply()
        }
    }

    private fun emptyProfile(): VehicleProfile = defaultProfile()

    private fun readProfilesLocked(): List<VehicleProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(profileFromJson(it)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistLocked(profiles: List<VehicleProfile>, activeVin: String) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_ACTIVE_VIN, activeVin)
            .commit()
    }

    private fun profileFromJson(json: JSONObject): VehicleProfile = VehicleProfile(
        vin = json.optString("vin"),
        userId = json.optString("userId"),
        mobileNo = json.optString("mobileNo"),
        bleName = json.optString("bleName"),
        bleMac = json.optString("bleMac"),
        deviceId = json.optString("deviceId"),
        accountType = json.optInt("accountType", 0),
        rootKeyHex = json.optString("rootKeyHex"),
        infoHex = json.optString("infoHex"),
        edfHex = json.optString("edfHex"),
        validFrom = json.optString("validFrom"),
        validTo = json.optString("validTo"),
        secAHex = json.optString("secAHex"),
        secBHex = json.optString("secBHex"),
        keyRole = json.optInt("keyRole", 1),
        accessToken = json.optString("accessToken"),
        sdkToken = json.optString("sdkToken"),
        controlPin = json.optString("controlPin"),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun VehicleProfile.toJson(): JSONObject = JSONObject().apply {
        put("vin", vin)
        put("userId", userId)
        put("mobileNo", mobileNo)
        put("bleName", bleName)
        put("bleMac", bleMac)
        put("deviceId", deviceId)
        put("accountType", accountType)
        put("rootKeyHex", rootKeyHex)
        put("infoHex", infoHex)
        put("edfHex", edfHex)
        put("validFrom", validFrom)
        put("validTo", validTo)
        put("secAHex", secAHex)
        put("secBHex", secBHex)
        put("keyRole", keyRole)
        put("accessToken", accessToken)
        put("sdkToken", sdkToken)
        put("controlPin", controlPin)
        put("updatedAt", updatedAt)
    }

    companion object {
        const val PREFS_NAME = "car_plus_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_VIN = "activeVin"

        // Kept as empty source-compatible defaults. Vehicle/account material is
        // never seeded into the app; profiles are created from login/provisioning.
        const val DEFAULT_VIN = ""
        const val DEFAULT_USER_ID = ""
        const val DEFAULT_MOBILE_NO = ""
        const val DEFAULT_BLE_NAME = ""
        const val DEFAULT_BLE_MAC = ""
        const val DEFAULT_DEVICE_ID = ""
        const val DEFAULT_ROOT_KEY_HEX = ""
        const val DEFAULT_INFO_HEX = ""
        const val DEFAULT_EDF_HEX = ""

        fun defaultProfile(deviceId: String = DEFAULT_DEVICE_ID): VehicleProfile = VehicleProfile(
            vin = DEFAULT_VIN,
            userId = DEFAULT_USER_ID,
            mobileNo = DEFAULT_MOBILE_NO,
            bleName = DEFAULT_BLE_NAME,
            bleMac = DEFAULT_BLE_MAC,
            deviceId = deviceId,
            accountType = 0,
            rootKeyHex = DEFAULT_ROOT_KEY_HEX,
            infoHex = DEFAULT_INFO_HEX,
            edfHex = DEFAULT_EDF_HEX
        )
    }
}

private fun compactHex(value: String): String =
    value.trim().replace(Regex("[\\s:_-]+"), "").uppercase(Locale.US)
