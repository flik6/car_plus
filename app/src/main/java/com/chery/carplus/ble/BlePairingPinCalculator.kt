package com.chery.carplus.ble

import android.bluetooth.le.ScanRecord
import java.security.MessageDigest

data class PairingPinCandidates(
    val primaryMacPin: String,
    val bleNameSuffixPin: String,
    val type1IngeekPin: String,
    val normalizedMac: String,
    val normalizedBleNameSuffix: String?,
    val randomHex: String,
    val type2KeyHex: String
) {
    val primaryPin: String get() = primaryMacPin
}

/**
 * Derives the six-digit system-pairing PIN from live advertisement data.
 *
 * The Type-2 derivation is intentionally identical to the verified Swift bridge:
 * the normalized 12 hex characters are decoded into six raw MAC bytes before the
 * double SHA-256, and only key bytes 1..3 participate in the 24-bit value.
 */
object BlePairingPinCalculator {
    private val TYPE1_KEY = byteArrayOf(0xAA.toByte(), 0xC7.toByte(), 0x1E, 0xEB.toByte())
    private const val COMPANY_ID_CHERY_LE_0 = 0x59
    private const val COMPANY_ID_CHERY_LE_1 = 0x07

    fun calculateType2Pin(macOr12Hex: String, random4: ByteArray): String {
        require(random4.size == 4) { "random4 must contain exactly four bytes" }
        val normalized = normalizeMac12(macOr12Hex)
        val macBytes = normalized.hexToBytes()
        val d1 = MessageDigest.getInstance("SHA-256").digest(macBytes)
        val d2 = MessageDigest.getInstance("SHA-256").digest(d1)
        return calculatePin(d2.copyOfRange(0, 4), random4)
    }

    fun calculateType1Pin(random4: ByteArray): String {
        require(random4.size == 4) { "random4 must contain exactly four bytes" }
        return calculatePin(TYPE1_KEY, random4.reversedArray())
    }

    fun calculateCandidates(
        activeMacOr12Hex: String,
        bleName: String? = null,
        random4: ByteArray
    ): PairingPinCandidates {
        require(random4.size == 4) { "random4 must contain exactly four bytes" }
        val normalizedMac = normalizeMac12(activeMacOr12Hex)
        val nameSuffix = extractBleNameSuffix(bleName)
        val effectiveSuffix = nameSuffix ?: normalizedMac
        val d1 = MessageDigest.getInstance("SHA-256").digest(normalizedMac.hexToBytes())
        val key = MessageDigest.getInstance("SHA-256").digest(d1).copyOfRange(0, 4)

        return PairingPinCandidates(
            primaryMacPin = calculatePin(key, random4),
            bleNameSuffixPin = calculatePin(keyForSuffix(effectiveSuffix), random4),
            type1IngeekPin = calculateType1Pin(random4),
            normalizedMac = normalizedMac,
            normalizedBleNameSuffix = nameSuffix,
            randomHex = random4.toHex(),
            type2KeyHex = key.toHex()
        )
    }

    fun normalizeMac12(macOr12Hex: String): String {
        var value = macOr12Hex.trim()
        if (value.startsWith("BLE_DK_", ignoreCase = true)) value = value.substring(7)
        if (value.startsWith("CHR", ignoreCase = true)) value = value.substring(3)
        value = value.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.uppercase()
        require(value.length == 12) { "Expected 12 hexadecimal MAC characters after normalization" }
        return value
    }

    fun extractBleNameSuffix(name: String?): String? {
        if (name == null) return null
        val upper = name.trim().uppercase()
        val suffix = when {
            upper.startsWith("BLE_DK_") -> upper.substring(7)
            upper.startsWith("CHR") -> upper.substring(3)
            else -> return null
        }
        if (suffix.length != 12 || !suffix.all { it.isDigit() || it in 'A'..'F' }) return null
        return suffix
    }

    fun extractAdvRandomHex(scanRecord: ScanRecord?): String? =
        extractAdvRandomBytes(scanRecord)?.toHex()

    /**
     * Extracts the rolling four-byte random value from the 5-byte ADV_IND
     * manufacturer-specific block (`06 FF <r0> <r1> <r2> <r3> <flag>`),
     * matching InGeek `O000000o.java` and `mac_ble_bridge.swift`.
     *
     * Raw advertisement bytes are scanned FIRST because Android active scans
     * merge ADV_IND + SCAN_RSP and strip the first 2 bytes of every `0xFF`
     * block into `ScanRecord.manufacturerSpecificData`'s `companyId` key,
     * leaving only 3 bytes in `value` for the real 5-byte ADV_IND while
     * exposing the 26-byte `0x0759` SCAN_RSP (which does NOT hold the PIN
     * random) under `companyId == 0x0759`.
     */
    fun extractAdvRandomBytes(scanRecord: ScanRecord?): ByteArray? {
        if (scanRecord == null) return null

        // Pass 1: parse raw AD structures first for exact `06 FF <rand4> <flag>` (len == 6, 5-byte payload).
        val raw = scanRecord.bytes
        if (raw != null) {
            extractRandomFromRawBytes(raw, exactIngeekLengthOnly = true)?.let { return it }
        }

        // Pass 2: manufacturerSpecificData fallback (reconstructs LE 2-byte companyId when Android
        // split the 5-byte ADV_IND into 2B key + 3B value). Never pull random bytes from 0x0759 SCAN_RSP.
        val manufacturer = scanRecord.manufacturerSpecificData
        if (manufacturer != null) {
            for (index in 0 until manufacturer.size()) {
                val companyId = manufacturer.keyAt(index)
                val value = manufacturer.valueAt(index) ?: continue
                if (value.size == 3) {
                    return byteArrayOf(
                        (companyId and 0xFF).toByte(),
                        ((companyId ushr 8) and 0xFF).toByte(),
                        value[0],
                        value[1]
                    )
                }
            }
            for (index in 0 until manufacturer.size()) {
                val companyId = manufacturer.keyAt(index)
                if (companyId == COMPANY_ID_CHERY) continue
                val value = manufacturer.valueAt(index) ?: continue
                if (value.size == 5) return value.copyOfRange(0, 4)
            }
        }

        // Pass 3: non-standard / service-data fallbacks (still skipping 0x0759 SCAN_RSP).
        if (raw != null) {
            extractRandomFromRawBytes(raw, exactIngeekLengthOnly = false)?.let { return it }
        }

        scanRecord.serviceData?.values?.forEach { value ->
            selectRandomFromAdvertisingValue(value)?.let { return it }
        }

        return null
    }

    private fun extractRandomFromRawBytes(raw: ByteArray, exactIngeekLengthOnly: Boolean): ByteArray? {
        var offset = 0
        while (offset < raw.size) {
            val declaredLength = raw[offset].toInt() and 0xFF
            if (declaredLength == 0) break
            val next = offset + 1 + declaredLength
            if (next > raw.size) break
            if (declaredLength >= 2) {
                val type = raw[offset + 1].toInt() and 0xFF
                val value = raw.copyOfRange(offset + 2, next)
                if (exactIngeekLengthOnly) {
                    // Verified InGeek ADV_IND layout: AD length = 6 (1B type 0xFF + 4B random + 1B flag).
                    if (type == 0xFF && declaredLength == 6 && value.size == 5) {
                        return value.copyOfRange(0, 4)
                    }
                } else {
                    when (type) {
                        0xFF -> {
                            val isCheryScanRsp = value.size >= 2 &&
                                (value[0].toInt() and 0xFF) == COMPANY_ID_CHERY_LE_0 &&
                                (value[1].toInt() and 0xFF) == COMPANY_ID_CHERY_LE_1 &&
                                value.size != 5
                            if (!isCheryScanRsp) {
                                selectRandomFromAdvertisingValue(value)?.let { return it }
                            }
                        }
                        0x16 -> if (value.size >= 6) {
                            selectRandomFromAdvertisingValue(value.copyOfRange(2, value.size))?.let { return it }
                        }
                        0x20 -> if (value.size >= 8) {
                            selectRandomFromAdvertisingValue(value.copyOfRange(4, value.size))?.let { return it }
                        }
                    }
                }
            }
            offset = next
        }
        return null
    }

    private fun selectRandomFromAdvertisingValue(value: ByteArray): ByteArray? {
        if (value.size < 4) return null
        if (value.size == 5) return value.copyOfRange(0, 4)
        return value.copyOfRange(0, 4)
    }

    private const val COMPANY_ID_CHERY = 0x0759

    private fun keyForSuffix(suffix: String): ByteArray {
        val normalized = normalizeMac12(suffix)
        val d1 = MessageDigest.getInstance("SHA-256").digest(normalized.hexToBytes())
        return MessageDigest.getInstance("SHA-256").digest(d1).copyOfRange(0, 4)
    }

    private fun calculatePin(key4: ByteArray, random4: ByteArray): String {
        require(key4.size == 4 && random4.size == 4) { "PIN derivation requires two four-byte inputs" }
        val x1 = (key4[1].toInt() xor random4[1].toInt()) and 0xFF
        val x2 = (key4[2].toInt() xor random4[2].toInt()) and 0xFF
        val x3 = (key4[3].toInt() xor random4[3].toInt()) and 0xFF
        val value = (((x1 shl 16) or (x2 shl 8) or x3) % 1_000_000)
        val pin = if (value < 100_000) value + 100_000 else value
        return pin.toString().padStart(6, '0')
    }
}
