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
     * Extracts the rolling four-byte random value from manufacturer-specific
     * data (0xFF) first, then service data, then the raw advertisement bytes.
     */
    fun extractAdvRandomBytes(scanRecord: ScanRecord?): ByteArray? {
        if (scanRecord == null) return null

        val manufacturer = scanRecord.manufacturerSpecificData
        if (manufacturer != null) {
            for (index in 0 until manufacturer.size()) {
                val companyId = manufacturer.keyAt(index)
                val value = manufacturer.valueAt(index) ?: continue
                selectRandomFromManufacturer(value, companyId)?.let { return it }
            }
        }

        scanRecord.serviceData?.values?.forEach { value ->
            selectRandomFromAdvertisingValue(value)?.let { return it }
        }

        val raw = scanRecord.bytes ?: return null
        var offset = 0
        while (offset < raw.size) {
            val declaredLength = raw[offset].toInt() and 0xFF
            if (declaredLength == 0) break
            val next = offset + 1 + declaredLength
            if (next > raw.size) break
            val type = raw[offset + 1].toInt() and 0xFF
            val value = raw.copyOfRange(offset + 2, next)
            when (type) {
                0xFF -> {
                    val companyId = if (value.size >= 2) {
                        (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
                    } else {
                        -1
                    }
                    selectRandomFromManufacturer(
                        if (value.size >= 2) value.copyOfRange(2, value.size) else value,
                        companyId
                    )?.let { return it }
                    selectRandomFromAdvertisingValue(value)?.let { return it }
                }
                0x16, 0x20 -> selectRandomFromAdvertisingValue(value)?.let { return it }
            }
            offset = next
        }
        return null
    }

    private fun selectRandomFromManufacturer(value: ByteArray, companyId: Int): ByteArray? {
        if (value.size == 5) return value.copyOfRange(0, 4)
        if (value.size >= 6 && value[0].toInt().and(0xFF) == COMPANY_ID_CHERY_LE_0 &&
            value[1].toInt().and(0xFF) == COMPANY_ID_CHERY_LE_1
        ) {
            return value.copyOfRange(2, 6)
        }
        if (companyId == 0x0759 && value.size >= 4) return value.copyOfRange(0, 4)
        return selectRandomFromAdvertisingValue(value)
    }

    private fun selectRandomFromAdvertisingValue(value: ByteArray): ByteArray? {
        if (value.size < 4) return null
        if (value.size == 5) return value.copyOfRange(0, 4)
        if (value.size >= 6 && value[0].toInt().and(0xFF) == COMPANY_ID_CHERY_LE_0 &&
            value[1].toInt().and(0xFF) == COMPANY_ID_CHERY_LE_1
        ) {
            return value.copyOfRange(2, 6)
        }
        return value.copyOfRange(0, 4)
    }

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
