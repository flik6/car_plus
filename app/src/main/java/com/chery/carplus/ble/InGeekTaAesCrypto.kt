package com.chery.carplus.ble

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JCE implementation of the InGeek AES-128 session primitives used by the
 * authorized BLE assessment.  No third-party cryptography is required.
 */
object InGeekTaAesCrypto {
    private const val BLOCK_SIZE = 16
    private val ZERO_IV = ByteArray(BLOCK_SIZE)

    fun iso9797Pad16(data: ByteArray): ByteArray {
        val paddedLength = ((data.size / BLOCK_SIZE) + 1) * BLOCK_SIZE
        val output = ByteArray(paddedLength)
        data.copyInto(output)
        output[data.size] = 0x80.toByte()
        return output
    }

    fun iso9797Unpad16(padded: ByteArray): ByteArray {
        require(padded.isNotEmpty() && padded.size % BLOCK_SIZE == 0) {
            "ISO 9797-1 Method 2 input must be a non-empty multiple of 16 bytes"
        }
        var marker = -1
        val lowerBound = maxOf(0, padded.size - BLOCK_SIZE)
        for (index in padded.lastIndex downTo lowerBound) {
            when (padded[index].toInt() and 0xFF) {
                0x80 -> {
                    marker = index
                    break
                }
                0x00 -> Unit
                else -> throw IllegalArgumentException("Invalid non-zero byte in ISO 9797-1 Method 2 padding")
            }
        }
        require(marker >= 0) { "Missing 0x80 ISO 9797-1 Method 2 marker" }
        return padded.copyOfRange(0, marker)
    }

    fun aes128EcbEncrypt(key16: ByteArray, block16: ByteArray): ByteArray {
        require(key16.size == BLOCK_SIZE) { "AES-128 key must be 16 bytes" }
        require(block16.size == BLOCK_SIZE) { "AES block must be 16 bytes" }
        return aesCipher(Cipher.ENCRYPT_MODE, key16, null).doFinal(block16)
    }

    fun aes128CbcEncryptNoPad(key16: ByteArray, iv16: ByteArray, data: ByteArray): ByteArray {
        require(key16.size == BLOCK_SIZE) { "AES-128 key must be 16 bytes" }
        require(iv16.size == BLOCK_SIZE) { "AES IV must be 16 bytes" }
        require(data.isNotEmpty() && data.size % BLOCK_SIZE == 0) {
            "AES-CBC input must be a non-empty multiple of 16 bytes"
        }
        return aesCipher(Cipher.ENCRYPT_MODE, key16, iv16).doFinal(data)
    }

    fun aes128CbcDecryptNoPad(key16: ByteArray, iv16: ByteArray, data: ByteArray): ByteArray {
        require(key16.size == BLOCK_SIZE) { "AES-128 key must be 16 bytes" }
        require(iv16.size == BLOCK_SIZE) { "AES IV must be 16 bytes" }
        require(data.isNotEmpty() && data.size % BLOCK_SIZE == 0) {
            "AES-CBC input must be a non-empty multiple of 16 bytes"
        }
        return aesCipher(Cipher.DECRYPT_MODE, key16, iv16).doFinal(data)
    }

    fun incrementIv128Be(iv16: ByteArray, steps: Int = 1): ByteArray {
        require(iv16.size == BLOCK_SIZE) { "IV must be 16 bytes" }
        require(steps >= 0) { "steps must be non-negative" }
        val output = iv16.copyOf()
        var carry = steps.toLong()
        for (index in output.lastIndex downTo 0) {
            val sum = (output[index].toInt() and 0xFF) + carry
            output[index] = (sum and 0xFF).toByte()
            carry = sum ushr 8
            if (carry == 0L) break
        }
        return output
    }

    fun aes128Cmac(key16: ByteArray, message: ByteArray): ByteArray {
        require(key16.size == BLOCK_SIZE) { "AES-CMAC key must be 16 bytes" }
        val zero = ByteArray(BLOCK_SIZE)
        val l = aes128EcbEncrypt(key16, zero)
        val k1 = cmacSubkey(l)
        val k2 = cmacSubkey(k1)

        val blockCount = if (message.isEmpty()) 1 else (message.size + BLOCK_SIZE - 1) / BLOCK_SIZE
        val complete = message.isNotEmpty() && message.size % BLOCK_SIZE == 0
        val lastStart = (blockCount - 1) * BLOCK_SIZE
        val lastInput = if (complete) {
            xor(message.copyOfRange(lastStart, lastStart + BLOCK_SIZE), k1)
        } else {
            val remainder = message.copyOfRange(lastStart, message.size)
            val padded = ByteArray(BLOCK_SIZE)
            remainder.copyInto(padded)
            padded[remainder.size] = 0x80.toByte()
            xor(padded, k2)
        }

        var x = ByteArray(BLOCK_SIZE)
        for (index in 0 until blockCount - 1) {
            val block = message.copyOfRange(index * BLOCK_SIZE, (index + 1) * BLOCK_SIZE)
            x = aes128EcbEncrypt(key16, xor(x, block))
        }
        return aes128EcbEncrypt(key16, xor(x, lastInput))
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    private fun cmacSubkey(input: ByteArray): ByteArray {
        val output = ByteArray(BLOCK_SIZE)
        var overflow = 0
        for (index in input.lastIndex downTo 0) {
            val value = input[index].toInt() and 0xFF
            output[index] = ((value shl 1) or overflow).toByte()
            overflow = value ushr 7
        }
        if ((input[0].toInt() and 0x80) != 0) {
            output[BLOCK_SIZE - 1] = ((output[BLOCK_SIZE - 1].toInt() and 0xFF) xor 0x87).toByte()
        }
        return output
    }

    private fun xor(left: ByteArray, right: ByteArray): ByteArray {
        require(left.size == right.size) { "XOR operands must have equal size" }
        return ByteArray(left.size) { index -> (left[index].toInt() xor right[index].toInt()).toByte() }
    }

    private fun aesCipher(mode: Int, key: ByteArray, iv: ByteArray?): Cipher {
        val transformation = if (iv == null) "AES/ECB/NoPadding" else "AES/CBC/NoPadding"
        return Cipher.getInstance(transformation).apply {
            val secretKey = SecretKeySpec(key, "AES")
            require(mode == Cipher.ENCRYPT_MODE || mode == Cipher.DECRYPT_MODE) {
                "Unsupported cipher mode"
            }
            if (iv == null) {
                init(mode, secretKey)
            } else {
                init(mode, secretKey, IvParameterSpec(iv))
            }
        }
    }

    internal fun validateMethod2PaddingAt(data: ByteArray, plaintextLength: Int): Boolean {
        if (plaintextLength < 0 || data.size <= plaintextLength) return false
        if ((data[plaintextLength].toInt() and 0xFF) != 0x80) return false
        for (index in plaintextLength + 1 until data.size) {
            if (data[index].toInt() != 0) return false
        }
        return true
    }

    internal fun xorWith(left: ByteArray, right: ByteArray): ByteArray = xor(left, right)
}

/**
 * Stateful InGeekTA_AES session.
 *
 * The field layout follows the verified 0x02/0x03/0x04 flow:
 *  - root-key challenge: rndB[0..7] || vehicleToken[8..23] || padding
 *  - 0x03 request: rndB[8] || vehicleToken[16] || rndA[8] || secA[32] || 0x80... (80B)
 *  - 0x03 response: rndB[8] || vehicleToken[16] || rndA[8] || secB[32] || 0x80... (80B)
 *  - session key: SHA-256(secB || secA || 00 00 00 01)[16..31]
 *  - initial IV: rndA || rndB
 */
class InGeekBleSession(
    kRoot16: ByteArray,
    infoBin147: ByteArray,
    edfBin: ByteArray
) {
    private val random = SecureRandom()

    val rootKey: ByteArray = kRoot16.copyOf().also {
        require(it.size == 16) { "kRoot16 must be exactly 16 bytes" }
    }
    val infoBin: ByteArray = infoBin147.copyOf().also {
        require(it.size == 147) { "infoBin147 must be exactly 147 bytes" }
    }
    val edf: ByteArray = edfBin.copyOf().also {
        require(it.size in 68..74) { "edfBin must be 68..74 bytes for the verified key profile" }
    }

    val secA: ByteArray = infoBin.copyOfRange(6, 38)
    val secB: ByteArray = infoBin.copyOfRange(87, 119)
    val vin: String = String(infoBin.copyOfRange(70, 87), Charsets.US_ASCII)
    val mobileNo: String = String(infoBin.copyOfRange(119, 130), Charsets.US_ASCII)

    var rndB: ByteArray? = null
        private set
    var vehicleToken: ByteArray? = null
        private set
    var rndA: ByteArray? = null
        private set
    var sessionKey: ByteArray? = null
        private set
    var initialIv: ByteArray? = null
        private set
    var txIv: ByteArray? = null
        private set
    var rxIv: ByteArray? = null
        private set

    val isSessionEstablished: Boolean
        get() = sessionKey != null && initialIv != null && txIv != null && rxIv != null

    @Synchronized
    fun build0x02AuthPayload(): ByteArray = edf + infoBin

    /** Decrypts the 0x02 challenge and returns the encrypted 80-byte 0x03 body. */
    @Synchronized
    fun process0x02ChallengeAndBuild0x03(
        challenge32: ByteArray,
        fixedRndA8: ByteArray? = null
    ): ByteArray {
        require(challenge32.size == 32) { "0x02 challenge must be 32 bytes" }
        if (fixedRndA8 != null) require(fixedRndA8.size == 8) { "fixedRndA8 must be 8 bytes" }

        val challengePlain = InGeekTaAesCrypto.aes128CbcDecryptNoPad(
            rootKey,
            ByteArray(16),
            challenge32
        )
        if (!InGeekTaAesCrypto.validateMethod2PaddingAt(challengePlain, 24)) {
            throw IllegalArgumentException("0x02 challenge does not contain valid ISO 9797-1 Method 2 padding")
        }
        rndB = challengePlain.copyOfRange(0, 8)
        vehicleToken = challengePlain.copyOfRange(8, 24)
        rndA = fixedRndA8?.copyOf() ?: ByteArray(8).also(random::nextBytes)

        val sessionPadding = ByteArray(16).also { it[0] = 0x80.toByte() }
        val plain80 = rndB!! + vehicleToken!! + rndA!! + secA + sessionPadding
        require(plain80.size == 80) { "Constructed 0x03 body must be 80 bytes" }
        return InGeekTaAesCrypto.aes128CbcEncryptNoPad(rootKey, ByteArray(16), plain80)
    }

    /** Verifies the 0x03 response and derives the directional session IV state. */
    @Synchronized
    fun verify0x03SessionResponse(respEncrypted: ByteArray): Boolean {
        val expectedRndB = rndB ?: return false
        val expectedToken = vehicleToken ?: return false
        val expectedRndA = rndA ?: return false
        if (respEncrypted.size != 80) return false

        val plain = runCatching {
            InGeekTaAesCrypto.aes128CbcDecryptNoPad(rootKey, ByteArray(16), respEncrypted)
        }.getOrNull() ?: return false
        if (plain.size != 80) return false

        if (!InGeekTaAesCrypto.constantTimeEquals(plain.copyOfRange(0, 8), expectedRndB)) return false
        if (!InGeekTaAesCrypto.constantTimeEquals(plain.copyOfRange(8, 24), expectedToken)) return false
        if (!InGeekTaAesCrypto.constantTimeEquals(plain.copyOfRange(24, 32), expectedRndA)) return false
        if (!InGeekTaAesCrypto.validateMethod2PaddingAt(plain, 64)) return false

        val vehicleSecB = plain.copyOfRange(32, 64)
        val digest = InGeekTaAesCrypto.sha256(vehicleSecB + secA + byteArrayOf(0, 0, 0, 1))
        val key = digest.copyOfRange(16, 32)
        val iv = expectedRndA + expectedRndB

        sessionKey = key
        initialIv = iv
        txIv = iv.copyOf()
        rxIv = iv.copyOf()
        return true
    }

    /** Encrypts one post-session TLV frame and advances the TX counter by one. */
    @Synchronized
    fun encrypt0x04Payload(plaintextTlv: ByteArray): ByteArray {
        require(plaintextTlv.isNotEmpty()) { "Vehicle-control plaintext must not be empty" }
        val key = sessionKey ?: throw IllegalStateException("Session is not established")
        val previousIv = txIv ?: throw IllegalStateException("TX IV is not initialized")

        val nextIv = InGeekTaAesCrypto.incrementIv128Be(previousIv, 1)
        val cbcIv = InGeekTaAesCrypto.aes128EcbEncrypt(key, nextIv)
        val padded = InGeekTaAesCrypto.iso9797Pad16(plaintextTlv)
        val ciphertext = InGeekTaAesCrypto.aes128CbcEncryptNoPad(key, cbcIv, padded)
        val cmac = InGeekTaAesCrypto.aes128Cmac(key, nextIv + ciphertext)
        txIv = nextIv
        return ciphertext + cmac
    }

    /**
     * Strictly monotonic forward RX-IV verification matching InGeekTA_AES::DecryptData.
     * Rejects replayed/non-incremented IVs, allows a small bounded forward skip to
     * recover from dropped BLE notifications, and commits `rxIv` only AFTER CMAC,
     * AES-CBC decryption, and ISO 9797-1 Method 2 unpadding all succeed.
     */
    @Synchronized
    fun decryptIncomingPayload(ciphertextWithMac: ByteArray): Pair<ByteArray, Boolean> {
        val key = sessionKey ?: return ByteArray(0) to false
        val currentRx = rxIv ?: return ByteArray(0) to false
        if (ciphertextWithMac.size < 32 || (ciphertextWithMac.size - 16) % 16 != 0) {
            return ByteArray(0) to false
        }

        val ciphertext = ciphertextWithMac.copyOfRange(0, ciphertextWithMac.size - 16)
        val receivedMac = ciphertextWithMac.copyOfRange(ciphertextWithMac.size - 16, ciphertextWithMac.size)

        for (step in 1..MAX_RX_IV_FORWARD_SKIP) {
            val candidateIv = InGeekTaAesCrypto.incrementIv128Be(currentRx, step)
            val expectedMac = InGeekTaAesCrypto.aes128Cmac(key, candidateIv + ciphertext)
            if (!InGeekTaAesCrypto.constantTimeEquals(receivedMac, expectedMac)) continue

            val cbcIv = InGeekTaAesCrypto.aes128EcbEncrypt(key, candidateIv)
            val padded = runCatching {
                InGeekTaAesCrypto.aes128CbcDecryptNoPad(key, cbcIv, ciphertext)
            }.getOrNull() ?: return ByteArray(0) to false
            val plain = runCatching { InGeekTaAesCrypto.iso9797Unpad16(padded) }.getOrNull()
                ?: return ByteArray(0) to false
            rxIv = candidateIv
            return plain to true
        }
        return ByteArray(0) to false
    }

    companion object {
        private const val MAX_RX_IV_FORWARD_SKIP = 4
    }

    @Synchronized
    fun snapshot(): SessionSnapshot = SessionSnapshot(
        vin = vin,
        mobileNo = mobileNo,
        rndBHex = rndB?.toHex(),
        rndAHex = rndA?.toHex(),
        sessionKeyHex = sessionKey?.toHex(),
        initialIvHex = initialIv?.toHex(),
        txIvHex = txIv?.toHex(),
        rxIvHex = rxIv?.toHex()
    )
}

data class SessionSnapshot(
    val vin: String,
    val mobileNo: String,
    val rndBHex: String?,
    val rndAHex: String?,
    val sessionKeyHex: String?,
    val initialIvHex: String?,
    val txIvHex: String?,
    val rxIvHex: String?
)
