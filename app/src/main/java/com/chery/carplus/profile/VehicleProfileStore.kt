package com.chery.carplus.profile

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    /** Lowercase MD5 hex helper (used for raw UUID fallback matching zyhl k.getIdToReDevice()). */
    fun md5Hex(raw: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(raw.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xFF) }
    }

    /** Chery Android device ID: lowercase MD5("0" + androidId). */
    fun computeCheryDeviceId(androidId: String): String = md5Hex("0" + androidId.trim())

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
    private val lock: Any get() = storeLock

    private fun readAccountDraftLocked(): VehicleProfile? {
        val raw = prefs.getString(KEY_ACCOUNT_DRAFT, "").orEmpty().trim()
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val needsMigration = hasLegacyPlaintextSecrets(json)
        val parsed = runCatching { profileFromJson(json) }.getOrNull() ?: return null
        if (needsMigration && ProfileSecretCipher.isAvailable()) {
            writeAccountDraftLocked(parsed)
        }
        return parsed
    }

    private fun writeAccountDraftLocked(draft: VehicleProfile?) {
        val editor = prefs.edit()
        if (draft == null) {
            editor.remove(KEY_ACCOUNT_DRAFT)
        } else {
            editor.putString(KEY_ACCOUNT_DRAFT, draft.toJson().toString())
        }
        editor.commit()
    }

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
            ?: readAccountDraftLocked()
            ?: emptyProfile()
        if (active.vin.isNotBlank() && !active.vin.equals(activeVin, ignoreCase = true)) {
            prefs.edit().putString(KEY_ACTIVE_VIN, active.vin).apply()
        }
        active
    }

    /**
     * MD5("0" + Settings.Secure.ANDROID_ID), matching the zyhl device ID.
     * Falls back to a persisted random UUID when ANDROID_ID is unavailable,
     * mirroring official zyhl k.getIdToReDevice() so login/108 never sends an
     * empty deviceId.
     */
    fun defaultDeviceId(): String = synchronized(lock) {
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().trim()
        if (androidId.isNotBlank()) {
            return@synchronized VckInfoParser.computeCheryDeviceId(androidId)
        }
        val persistedFallback = prefs.getString(KEY_FALLBACK_DEVICE_UUID, "").orEmpty().trim()
        val seed = persistedFallback.ifBlank {
            java.util.UUID.randomUUID().toString().also { generated ->
                prefs.edit().putString(KEY_FALLBACK_DEVICE_UUID, generated).apply()
            }
        }
        // zyhl k.getIdToReDevice() hashes raw fallback UUID directly without prepending "0".
        VckInfoParser.md5Hex(seed)
    }

    /**
     * Removes persisted vehicle profiles (and any account draft) that do not belong
     * to the given [userId] / [mobileNo] account. Safe no-op if both identifiers are blank.
     * Prevents cross-account vehicle/PIN/BLE-key contamination after 0406 session expiry.
     */
    fun pruneProfilesNotMatchingAccount(userId: String, mobileNo: String): Int = synchronized(lock) {
        val normUserId = userId.trim()
        val normMobile = normalizeMobileDigits(mobileNo)
        if (normUserId.isBlank() && normMobile.isBlank()) return@synchronized 0

        val existing = readProfilesLocked()
        val kept = existing.filter { matchesAccount(it, normUserId, normMobile) }
        val removed = existing.size - kept.size

        val draft = readAccountDraftLocked()
        if (draft != null && !matchesAccount(draft, normUserId, normMobile)) {
            writeAccountDraftLocked(null)
        }

        if (removed > 0) {
            val previousActive = prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
            val nextActive = previousActive
                .takeIf { active -> kept.any { it.vin.equals(active, ignoreCase = true) } }
                ?: kept.firstOrNull()?.vin.orEmpty()
            persistLocked(kept, nextActive)
        }
        removed
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

    fun saveProfile(
        profile: VehicleProfile,
        activate: Boolean = true,
        preserveExistingPinIfBlank: Boolean = true,
        preserveExistingBleKeysIfBlank: Boolean = true
    ): VehicleProfile = synchronized(lock) {
        val existingList = readProfilesLocked()
        val existingForVin = existingList.firstOrNull { it.vin.equals(profile.vin.trim(), ignoreCase = true) }
        val sameAccount = existingForVin != null && isSameAccount(existingForVin, profile)
        val resolvedPin = profile.controlPin.trim().ifBlank {
            if (sameAccount && preserveExistingPinIfBlank) {
                existingForVin?.controlPin?.trim().orEmpty()
            } else {
                ""
            }
        }
        val preserveBle = sameAccount && preserveExistingBleKeysIfBlank && existingForVin != null
        val incomingRoot = compactHex(profile.rootKeyHex)
        val incomingInfo = compactHex(profile.infoHex)
        val incomingEdf = compactHex(profile.edfHex)
        val incomingHasKeys = incomingRoot.isNotBlank() || incomingInfo.isNotBlank() || incomingEdf.isNotBlank()
        val normalized = profile.copy(
            vin = profile.vin.trim().uppercase(Locale.US),
            userId = profile.userId.trim().ifBlank {
                if (sameAccount) existingForVin?.userId.orEmpty() else ""
            },
            mobileNo = profile.mobileNo.trim().ifBlank {
                if (sameAccount) existingForVin?.mobileNo.orEmpty() else ""
            },
            bleName = profile.bleName.trim().ifBlank {
                if (preserveBle) existingForVin?.bleName.orEmpty() else ""
            },
            bleMac = profile.bleMac.trim().uppercase(Locale.US).ifBlank {
                if (preserveBle) existingForVin?.bleMac.orEmpty() else ""
            },
            deviceId = profile.deviceId.trim().ifBlank {
                if (preserveBle) existingForVin?.deviceId.orEmpty() else ""
            },
            rootKeyHex = incomingRoot.ifBlank {
                if (preserveBle) existingForVin?.rootKeyHex.orEmpty() else ""
            },
            infoHex = incomingInfo.ifBlank {
                if (preserveBle) existingForVin?.infoHex.orEmpty() else ""
            },
            edfHex = incomingEdf.ifBlank {
                if (preserveBle) existingForVin?.edfHex.orEmpty() else ""
            },
            validFrom = profile.validFrom.trim().ifBlank {
                if (preserveBle) existingForVin?.validFrom.orEmpty() else ""
            },
            validTo = profile.validTo.trim().ifBlank {
                if (preserveBle) existingForVin?.validTo.orEmpty() else ""
            },
            secAHex = compactHex(profile.secAHex).ifBlank {
                if (preserveBle) existingForVin?.secAHex.orEmpty() else ""
            },
            secBHex = compactHex(profile.secBHex).ifBlank {
                if (preserveBle) existingForVin?.secBHex.orEmpty() else ""
            },
            keyRole = if (!incomingHasKeys && preserveBle) {
                existingForVin?.keyRole ?: profile.keyRole
            } else {
                profile.keyRole
            },
            controlPin = resolvedPin,
            updatedAt = System.currentTimeMillis()
        )

        if (normalized.vin.isBlank()) {
            writeAccountDraftLocked(normalized)
            return@synchronized normalized
        }

        val profiles = existingList
            .filterNot { it.vin.equals(normalized.vin, ignoreCase = true) }
            .toMutableList()
        profiles.add(0, normalized)
        writeAccountDraftLocked(null)

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

    /**
     * Atomically updates only BLE digital key material for an existing [vin] without
     * overwriting accessToken, sdkToken, controlPin, or KEY_ACTIVE_VIN.
     * Returns null if [vin] no longer exists in the store (e.g. after logout or unbind).
     */
    fun updateKeyMaterial(
        vin: String,
        rootKeyHex: String,
        infoHex: String,
        edfHex: String,
        validFrom: String,
        validTo: String,
        secAHex: String,
        secBHex: String,
        keyRole: Int,
        deviceId: String = "",
        bleName: String = "",
        bleMac: String = ""
    ): VehicleProfile? = synchronized(lock) {
        val targetVin = vin.trim().uppercase(Locale.US)
        if (targetVin.isBlank()) return@synchronized null
        val profiles = readProfilesLocked().toMutableList()
        val index = profiles.indexOfFirst { it.vin.equals(targetVin, ignoreCase = true) }
        if (index < 0) return@synchronized null
        val existing = profiles[index]
        val updated = existing.copy(
            deviceId = deviceId.trim().ifBlank { existing.deviceId },
            bleName = bleName.trim().ifBlank { existing.bleName },
            bleMac = bleMac.trim().uppercase(Locale.US).ifBlank { existing.bleMac },
            rootKeyHex = compactHex(rootKeyHex),
            infoHex = compactHex(infoHex),
            edfHex = compactHex(edfHex),
            validFrom = validFrom.ifBlank { existing.validFrom },
            validTo = validTo.ifBlank { existing.validTo },
            secAHex = compactHex(secAHex).ifBlank { existing.secAHex },
            secBHex = compactHex(secBHex).ifBlank { existing.secBHex },
            keyRole = keyRole,
            updatedAt = System.currentTimeMillis()
        )
        profiles[index] = updated
        val activeVin = prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
            .takeIf { active -> profiles.any { it.vin.equals(active, ignoreCase = true) } }
            ?: updated.vin
        persistLocked(profiles, activeVin)
        updated
    }

    /**
     * Clears cloud session tokens (accessToken/sdkToken) while preserving offline BLE
     * key material and deviceId fallback UUID (used on 0406 session expiry).
     */
    fun clearTokensPreserveOfflineKeys() = synchronized(lock) {
        val profiles = readProfilesLocked().map {
            it.copy(accessToken = "", sdkToken = "", updatedAt = System.currentTimeMillis())
        }
        val activeVin = prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
        writeAccountDraftLocked(null)
        persistLocked(profiles, activeVin)
    }

    /**
     * Atomically updates only [VehicleProfile.controlPin] for [vin] without touching
     * freshly provisioned BLE key material, tokens, or other vehicles' PINs.
     */
    fun updateControlPin(vin: String, newPin: String): VehicleProfile? = synchronized(lock) {
        val targetVin = vin.trim().uppercase(Locale.US)
        if (targetVin.isBlank()) return@synchronized null
        val profiles = readProfilesLocked().toMutableList()
        val index = profiles.indexOfFirst { it.vin.equals(targetVin, ignoreCase = true) }
        if (index < 0) return@synchronized null
        val updated = profiles[index].copy(
            controlPin = newPin.trim(),
            updatedAt = System.currentTimeMillis()
        )
        profiles[index] = updated
        val activeVin = prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
            .takeIf { active -> profiles.any { it.vin.equals(active, ignoreCase = true) } }
            ?: updated.vin
        persistLocked(profiles, activeVin)
        updated
    }

    fun deleteProfile(vin: String) = synchronized(lock) {
        if (vin.isBlank()) {
            writeAccountDraftLocked(null)
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

    /** Removes every persisted profile and account-only draft while keeping stable fallback device UUID. */
    fun clearAllProfiles() = synchronized(lock) {
        val fallbackUuid = prefs.getString(KEY_FALLBACK_DEVICE_UUID, "").orEmpty()
        prefs.edit().apply {
            clear()
            if (fallbackUuid.isNotBlank()) {
                putString(KEY_FALLBACK_DEVICE_UUID, fallbackUuid)
            }
        }.commit()
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
        require(edf.size in 68..74) {
            "edf must be 68..74 bytes, got ${edf.size}"
        }

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
        var needsMigration = false
        val profiles = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { obj ->
                        if (hasLegacyPlaintextSecrets(obj)) needsMigration = true
                        add(profileFromJson(obj))
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (needsMigration && profiles.isNotEmpty() && ProfileSecretCipher.isAvailable()) {
            val activeVin = prefs.getString(KEY_ACTIVE_VIN, "").orEmpty()
                .ifBlank { profiles.first().vin }
            persistLocked(profiles, activeVin)
        }
        return profiles
    }

    private fun persistLocked(profiles: List<VehicleProfile>, activeVin: String) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_ACTIVE_VIN, activeVin)
            .commit()
    }

    private fun hasLegacyPlaintextSecrets(json: JSONObject): Boolean =
        SECRET_JSON_FIELDS.any { key ->
            val raw = json.optString(key).trim()
            raw.isNotEmpty() && !ProfileSecretCipher.isEncrypted(raw)
        }

    private fun normalizeMobileDigits(mobile: String): String =
        mobile.trim().filter(Char::isDigit)

    private fun matchesAccount(
        profile: VehicleProfile,
        targetUserId: String,
        targetMobileDigits: String
    ): Boolean {
        val profUserId = profile.userId.trim()
        val profMobile = normalizeMobileDigits(profile.mobileNo)
        if (targetUserId.isNotBlank() && profUserId.isNotBlank()) {
            return profUserId.equals(targetUserId, ignoreCase = true)
        }
        if (targetMobileDigits.isNotBlank() && profMobile.isNotBlank()) {
            return profMobile == targetMobileDigits
        }
        return false
    }

    private fun isSameAccount(existing: VehicleProfile, incoming: VehicleProfile): Boolean {
        val exUser = existing.userId.trim()
        val inUser = incoming.userId.trim()
        val exMobile = normalizeMobileDigits(existing.mobileNo)
        val inMobile = normalizeMobileDigits(incoming.mobileNo)
        if (exUser.isNotBlank() && inUser.isNotBlank()) {
            return exUser.equals(inUser, ignoreCase = true)
        }
        if (exMobile.isNotBlank() && inMobile.isNotBlank()) {
            return exMobile == inMobile
        }
        // Only treat as same account when one side carries no identity at all (partial VIN update);
        // disjoint non-blank identities (e.g. only userId vs only mobileNo) must NOT merge across accounts.
        val incomingAnonymous = inUser.isBlank() && inMobile.isBlank()
        val existingAnonymous = exUser.isBlank() && exMobile.isBlank()
        return incomingAnonymous || existingAnonymous
    }

    private fun profileFromJson(json: JSONObject): VehicleProfile = VehicleProfile(
        vin = json.optString("vin"),
        userId = json.optString("userId"),
        mobileNo = json.optString("mobileNo"),
        bleName = json.optString("bleName"),
        bleMac = json.optString("bleMac"),
        deviceId = json.optString("deviceId"),
        accountType = json.optInt("accountType", 0),
        rootKeyHex = ProfileSecretCipher.decrypt(json.optString("rootKeyHex")),
        infoHex = ProfileSecretCipher.decrypt(json.optString("infoHex")),
        edfHex = ProfileSecretCipher.decrypt(json.optString("edfHex")),
        validFrom = json.optString("validFrom"),
        validTo = json.optString("validTo"),
        secAHex = ProfileSecretCipher.decrypt(json.optString("secAHex")),
        secBHex = ProfileSecretCipher.decrypt(json.optString("secBHex")),
        keyRole = json.optInt("keyRole", 1),
        accessToken = ProfileSecretCipher.decrypt(json.optString("accessToken")),
        sdkToken = ProfileSecretCipher.decrypt(json.optString("sdkToken")),
        controlPin = ProfileSecretCipher.decrypt(json.optString("controlPin")),
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
        put("rootKeyHex", ProfileSecretCipher.encrypt(rootKeyHex))
        put("infoHex", ProfileSecretCipher.encrypt(infoHex))
        put("edfHex", ProfileSecretCipher.encrypt(edfHex))
        put("validFrom", validFrom)
        put("validTo", validTo)
        put("secAHex", ProfileSecretCipher.encrypt(secAHex))
        put("secBHex", ProfileSecretCipher.encrypt(secBHex))
        put("keyRole", keyRole)
        put("accessToken", ProfileSecretCipher.encrypt(accessToken))
        put("sdkToken", ProfileSecretCipher.encrypt(sdkToken))
        put("controlPin", ProfileSecretCipher.encrypt(controlPin))
        put("updatedAt", updatedAt)
    }

    private object ProfileSecretCipher {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "qiri_profile_secrets_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "enc:v1:"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128

        private val cipherLock = Any()
        @Volatile
        private var encryptionOperational: Boolean? = null

        fun isEncrypted(raw: String): Boolean = raw.startsWith(PREFIX)

        fun isAvailable(): Boolean {
            encryptionOperational?.let { return it }
            val probe = encrypt("probe")
            val ok = probe.startsWith(PREFIX)
            encryptionOperational = ok
            return ok
        }

        fun encrypt(plain: String): String {
            if (plain.isEmpty()) return ""
            if (plain.startsWith(PREFIX)) return plain
            return synchronized(cipherLock) {
                val firstAttempt = runCatching {
                    val key = getOrCreateSecretKey() ?: return@runCatching null
                    encryptWithKey(key, plain)
                }.getOrNull()
                if (firstAttempt != null) {
                    encryptionOperational = true
                    return@synchronized firstAttempt
                }
                // Key may be corrupted or permanently invalidated; delete & regenerate once.
                val retryAttempt = runCatching {
                    val freshKey = regenerateSecretKey()
                    encryptWithKey(freshKey, plain)
                }.getOrNull()
                if (retryAttempt != null) {
                    encryptionOperational = true
                    retryAttempt
                } else {
                    encryptionOperational = false
                    plain
                }
            }
        }

        fun decrypt(stored: String): String {
            if (stored.isEmpty()) return ""
            if (!stored.startsWith(PREFIX)) return stored
            return synchronized(cipherLock) {
                runCatching {
                    val key = getOrCreateSecretKey() ?: return@synchronized ""
                    val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
                    if (packed.size <= GCM_IV_LEN) return@synchronized ""
                    val iv = packed.copyOfRange(0, GCM_IV_LEN)
                    val cipherBytes = packed.copyOfRange(GCM_IV_LEN, packed.size)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                    String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                }.getOrDefault("")
            }
        }

        private fun encryptWithKey(key: SecretKey, plain: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(cipherBytes, 0, packed, iv.size, cipherBytes.size)
            return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        }

        private fun getOrCreateSecretKey(): SecretKey? {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = runCatching { ks.getKey(KEY_ALIAS, null) as? SecretKey }.getOrNull()
            if (existing != null) return existing
            return generateNewSecretKey()
        }

        private fun regenerateSecretKey(): SecretKey {
            runCatching {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) {
                    ks.deleteEntry(KEY_ALIAS)
                }
            }
            return generateNewSecretKey()
        }

        private fun generateNewSecretKey(): SecretKey {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
    }

    companion object {
        private val storeLock = Any()
        private val SECRET_JSON_FIELDS = listOf(
            "rootKeyHex", "infoHex", "edfHex", "secAHex", "secBHex",
            "accessToken", "sdkToken", "controlPin"
        )
        const val PREFS_NAME = "car_plus_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_VIN = "activeVin"
        private const val KEY_ACCOUNT_DRAFT = "accountDraft"
        private const val KEY_FALLBACK_DEVICE_UUID = "fallbackDeviceUuid"

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
