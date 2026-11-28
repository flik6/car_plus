package com.chery.carplus.ble

/**
 * BLE whole-protocol framing and chunk reassembly for the authorized vehicle
 * security-assessment build.
 *
 * Two on-air layouts are supported:
 *  - VERSIONED: the compact 6-byte layout requested by the Android integration
 *    contract (version/encryption, serial, message id, payload length).
 *  - INGEEK_EXTENDED: the six-byte layout used by the verified reference bridge
 *    and by com.ingeek.key.ble.bean.BleWholeProtocol
 *    (message id, pack flag, length, serial).
 *
 * The controller defaults to INGEEK_EXTENDED because that is the byte-for-byte
 * layout observed on the test vehicle.  VERSIONED remains available for the
 * newer host protocol and for offline protocol fixtures.
 */
object BleWholeProtocol {
    const val HEADER_SIZE = 6
    const val DEFAULT_CHUNK_SIZE = 194
    const val FALLBACK_CHUNK_SIZE = 20

    const val MSG_AUTH_REQUEST = 0x02
    const val MSG_SESSION_REQUEST = 0x03
    const val MSG_VEHICLE_CONTROL = 0x04
    const val MSG_VEHICLE_INFO = 0x10
    const val MSG_VEHICLE_CONTROL_RESPONSE = 0x64
    const val MSG_BASE_RESPONSE = 0x90

    const val DEFAULT_VERSION = 1
    const val DEFAULT_ENCRYPTION_TYPE = 0

    const val INGEEK_SINGLE_PACKET = 0xA1
    const val INGEEK_LAST_PACKET = 0x01
    const val INGEEK_START_MASK = 0xF0
    const val INGEEK_MAX_MULTI_PACKETS = 15

    enum class FrameFormat {
        /** Requested version/encryption/serial/message-id/length header. */
        VERSIONED,

        /** Verified reference layout: message-id/pack-flag/length/serial. */
        INGEEK_EXTENDED
    }

    data class Header(
        val version: Int,
        val encryptionType: Int,
        val serialNum: Int,
        val messageId: Int,
        val payloadLength: Int
    )

    data class Frame(
        val format: FrameFormat,
        val version: Int,
        val encryptionType: Int,
        val serialNum: Int,
        val messageId: Int,
        val payload: ByteArray,
        val raw: ByteArray
    ) {
        fun payloadHex(): String = payload.toHex()
        fun rawHex(): String = raw.toHex()
    }

    fun encodeHeader(
        messageId: Int,
        serialNum: Int,
        payloadLength: Int,
        encryptionType: Int = DEFAULT_ENCRYPTION_TYPE,
        version: Int = DEFAULT_VERSION
    ): ByteArray {
        require(messageId in 0..0xFF) { "messageId must fit in one byte" }
        require(serialNum in 0..0xFFFF) { "serialNum must fit in an unsigned 16-bit integer" }
        require(payloadLength in 0..0xFFFF) { "payloadLength must fit in an unsigned 16-bit integer" }
        require(encryptionType in 0..0x0F) { "encryptionType must be 0..15" }
        require(version in 0..0x0F) { "version must be 0..15" }
        return byteArrayOf(
            (((version and 0x0F) shl 4) or (encryptionType and 0x0F)).toByte(),
            ((serialNum ushr 8) and 0xFF).toByte(),
            (serialNum and 0xFF).toByte(),
            messageId.toByte(),
            ((payloadLength ushr 8) and 0xFF).toByte(),
            (payloadLength and 0xFF).toByte()
        )
    }

    fun parseHeader(bytes: ByteArray, offset: Int = 0): Header {
        require(offset >= 0 && offset + HEADER_SIZE <= bytes.size) { "Incomplete BLE whole-protocol header" }
        val packed = bytes[offset].toInt() and 0xFF
        return Header(
            version = (packed ushr 4) and 0x0F,
            encryptionType = packed and 0x0F,
            serialNum = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                (bytes[offset + 2].toInt() and 0xFF),
            messageId = bytes[offset + 3].toInt() and 0xFF,
            payloadLength = ((bytes[offset + 4].toInt() and 0xFF) shl 8) or
                (bytes[offset + 5].toInt() and 0xFF)
        )
    }

    /**
     * Creates the versioned logical frame and divides `header || payload` into
     * transport-sized pieces.  Chunks are raw slices of the same logical frame;
     * the receiver concatenates notifications until the declared length arrives.
     */
    fun pack(
        messageId: Int,
        serialNum: Int,
        payload: ByteArray,
        maxChunkSize: Int = DEFAULT_CHUNK_SIZE,
        encryptionType: Int = DEFAULT_ENCRYPTION_TYPE,
        version: Int = DEFAULT_VERSION
    ): List<ByteArray> {
        require(payload.size <= 0xFFFF) { "payload exceeds unsigned 16-bit length" }
        require(maxChunkSize > 0) { "maxChunkSize must be positive" }
        val whole = encodeHeader(messageId, serialNum, payload.size, encryptionType, version) + payload
        return buildList {
            var offset = 0
            while (offset < whole.size) {
                val end = minOf(offset + maxChunkSize, whole.size)
                add(whole.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    /**
     * Encodes the packet layout verified against BleDataSender and the reference
     * bridge.  `maxPayloadChunkSize` excludes the per-fragment six-byte header.
     */
    fun packIngeekExtended(
        messageId: Int,
        serialNum: Int,
        payload: ByteArray,
        maxPayloadChunkSize: Int = DEFAULT_CHUNK_SIZE
    ): List<ByteArray> {
        require(messageId in 0..0xFF) { "messageId must fit in one byte" }
        require(serialNum in 0..0xFFFF) { "serialNum must fit in an unsigned 16-bit integer" }
        require(payload.size <= 0xFFFF) { "payload exceeds unsigned 16-bit length" }
        require(maxPayloadChunkSize > 0) { "maxPayloadChunkSize must be positive" }

        fun header(packFlag: Int, lengthField: Int): ByteArray = byteArrayOf(
            messageId.toByte(),
            (packFlag and 0xFF).toByte(),
            ((lengthField ushr 8) and 0xFF).toByte(),
            (lengthField and 0xFF).toByte(),
            ((serialNum ushr 8) and 0xFF).toByte(),
            (serialNum and 0xFF).toByte()
        )

        if (payload.size <= maxPayloadChunkSize) {
            return listOf(header(INGEEK_SINGLE_PACKET, payload.size) + payload)
        }

        val chunks = buildList {
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + maxPayloadChunkSize, payload.size)
                add(payload.copyOfRange(offset, end))
                offset = end
            }
        }
        require(chunks.size <= INGEEK_MAX_MULTI_PACKETS) {
            "InGeek extended framing supports at most $INGEEK_MAX_MULTI_PACKETS chunks " +
                "(payload=${payload.size}B, chunk=${maxPayloadChunkSize}B → ${chunks.size} chunks); " +
                "negotiate a larger BLE MTU first"
        }
        return chunks.mapIndexed { index, chunk ->
            val packFlag = if (index == 0) {
                (INGEEK_START_MASK or chunks.size) and 0xFF
            } else {
                (chunks.size - index) and 0xFF
            }
            val lengthField = if (index == 0) payload.size else chunk.size
            header(packFlag, lengthField) + chunk
        }
    }

    /** Parses a single whole-protocol packet as used by FFF2 notifications. */
    fun parseIngeekPacket(packet: ByteArray): IngeekPacket? {
        if (packet.size < HEADER_SIZE) return null
        return IngeekPacket(
            messageId = packet[0].toInt() and 0xFF,
            packFlag = packet[1].toInt() and 0xFF,
            lengthField = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF),
            serialNum = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF),
            fragment = packet.copyOfRange(HEADER_SIZE, packet.size),
            raw = packet.copyOf()
        )
    }

    data class IngeekPacket(
        val messageId: Int,
        val packFlag: Int,
        val lengthField: Int,
        val serialNum: Int,
        val fragment: ByteArray,
        val raw: ByteArray
    )
}

/**
 * Parses the six-byte response body carried by msg id 0x90/0x64.
 */
data class BleBaseResponse(
    val answerSerial: Int,
    val answerId: Int,
    val stage: Int,
    val res: Int,
    val errCode: Int,
    val data: ByteArray
) {
    val isSuccess: Boolean get() = errCode == 0

    companion object {
        fun parse(payload: ByteArray): BleBaseResponse? {
            if (payload.size < 6) return null
            return BleBaseResponse(
                answerSerial = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF),
                answerId = payload[2].toInt() and 0xFF,
                stage = payload[3].toInt() and 0xFF,
                res = payload[4].toInt() and 0xFF,
                errCode = payload[5].toInt() and 0xFF,
                data = payload.copyOfRange(6, payload.size)
            )
        }

        fun parse(frame: BleWholeProtocol.Frame): BleBaseResponse? {
            if (frame.messageId != BleWholeProtocol.MSG_BASE_RESPONSE &&
                frame.messageId != BleWholeProtocol.MSG_VEHICLE_CONTROL_RESPONSE
            ) {
                return null
            }
            return parse(frame.payload)
        }
    }
}

/**
 * Streaming reassembler.  It accepts either VERSIONED logical chunks or the
 * INGEEK_EXTENDED per-fragment layout and emits complete, de-duplicated frames.
 */
class BleFrameAssembler(
    private val format: BleWholeProtocol.FrameFormat = BleWholeProtocol.FrameFormat.VERSIONED,
    private val maximumPayloadLength: Int = 0xFFFF
) {
    private data class ReassemblyKey(val messageId: Int, val serialNum: Int)
    private data class IngeekState(
        val expected: Int,
        val baseHeader: ByteArray?,
        val body: MutableList<Byte>,
        var strippedBaseHeader: Boolean = false
    )

    private val versionedBuffer = ArrayList<Byte>(512)
    private val ingeekStates = LinkedHashMap<ReassemblyKey, IngeekState>()

    fun ingest(chunk: ByteArray): List<BleWholeProtocol.Frame> = when (format) {
        BleWholeProtocol.FrameFormat.VERSIONED -> ingestVersioned(chunk)
        BleWholeProtocol.FrameFormat.INGEEK_EXTENDED -> ingestIngeek(chunk)
    }

    private fun ingestVersioned(chunk: ByteArray): List<BleWholeProtocol.Frame> {
        if (chunk.isNotEmpty()) chunk.forEach { versionedBuffer.add(it) }
        val output = ArrayList<BleWholeProtocol.Frame>()
        while (versionedBuffer.size >= BleWholeProtocol.HEADER_SIZE) {
            val rawPrefix = versionedBuffer.toByteArray()
            val header = BleWholeProtocol.parseHeader(rawPrefix)
            require(header.payloadLength <= maximumPayloadLength) {
                "Declared payload ${header.payloadLength} exceeds limit $maximumPayloadLength"
            }
            val total = BleWholeProtocol.HEADER_SIZE + header.payloadLength
            if (versionedBuffer.size < total) break
            val raw = rawPrefix.copyOfRange(0, total)
            output += BleWholeProtocol.Frame(
                format = BleWholeProtocol.FrameFormat.VERSIONED,
                version = header.version,
                encryptionType = header.encryptionType,
                serialNum = header.serialNum,
                messageId = header.messageId,
                payload = raw.copyOfRange(BleWholeProtocol.HEADER_SIZE, total),
                raw = raw
            )
            versionedBuffer.subList(0, total).clear()
        }
        return output
    }

    private fun ingestIngeek(packetBytes: ByteArray): List<BleWholeProtocol.Frame> {
        val packet = BleWholeProtocol.parseIngeekPacket(packetBytes) ?: return emptyList()
        if (packet.packFlag == BleWholeProtocol.INGEEK_SINGLE_PACKET) {
            if (packet.lengthField > maximumPayloadLength) {
                throw IllegalArgumentException("Declared payload ${packet.lengthField} exceeds limit")
            }
            val payload = packet.fragment.copyOfRange(0, minOf(packet.lengthField, packet.fragment.size))
            if (payload.size != packet.lengthField) return emptyList()
            return listOf(packet.toFrame(payload))
        }

        val key = ReassemblyKey(packet.messageId, packet.serialNum)
        val isStartPacket = packet.packFlag > BleWholeProtocol.INGEEK_START_MASK &&
            (packet.packFlag and BleWholeProtocol.INGEEK_START_MASK) == BleWholeProtocol.INGEEK_START_MASK
        if (isStartPacket) {
            if (packet.lengthField > maximumPayloadLength) {
                throw IllegalArgumentException("Declared payload ${packet.lengthField} exceeds limit")
            }
            val firstChunk = if (packet.lengthField in 1 until packet.fragment.size) {
                packet.fragment.copyOfRange(0, packet.lengthField)
            } else {
                packet.fragment
            }
            val expectedTotal = if (packet.lengthField > packet.fragment.size) packet.lengthField else 0
            val isBaseRespMsg = packet.messageId == BleWholeProtocol.MSG_BASE_RESPONSE ||
                packet.messageId == BleWholeProtocol.MSG_VEHICLE_CONTROL_RESPONSE
            val baseHeader = if (isBaseRespMsg && firstChunk.size >= 6) {
                firstChunk.copyOfRange(0, 6)
            } else {
                null
            }
            ingeekStates[key] = IngeekState(
                expected = expectedTotal,
                baseHeader = baseHeader,
                body = ArrayList<Byte>(expectedTotal.coerceAtLeast(firstChunk.size)).apply {
                    firstChunk.forEach { add(it) }
                }
            )
            return emptyList()
        }

        val state = ingeekStates[key] ?: return emptyList()
        val trimmedFragment = if (packet.lengthField in 1..packet.fragment.size) {
            packet.fragment.copyOfRange(0, packet.lengthField)
        } else {
            packet.fragment
        }
        val baseHeader = state.baseHeader
        val chunkToAppend = if (baseHeader != null &&
            trimmedFragment.size >= 6 &&
            matchesPrefix6(trimmedFragment, baseHeader)
        ) {
            // zyhl BleDataReceiver (O00000Oo.java:132-136): continuation fragments repeat the 6-byte BleBaseResponse header.
            state.strippedBaseHeader = true
            trimmedFragment.copyOfRange(6, trimmedFragment.size)
        } else {
            trimmedFragment
        }
        if (state.body.size + chunkToAppend.size > maximumPayloadLength) {
            ingeekStates.remove(key)
            throw IllegalArgumentException("Reassembled payload exceeds limit $maximumPayloadLength")
        }
        chunkToAppend.forEach { state.body.add(it) }

        val isLastPacket = packet.packFlag == BleWholeProtocol.INGEEK_LAST_PACKET
        if (isLastPacket) {
            ingeekStates.remove(key)
            val payload = if (!state.strippedBaseHeader && state.expected in 1..state.body.size) {
                ByteArray(state.expected) { state.body[it] }
            } else {
                state.body.toByteArray()
            }
            return if (payload.isEmpty()) emptyList() else listOf(packet.toFrame(payload))
        }
        if (!state.strippedBaseHeader && state.expected > 0 && state.body.size >= state.expected) {
            ingeekStates.remove(key)
            val payload = ByteArray(state.expected) { state.body[it] }
            return listOf(packet.toFrame(payload))
        }
        return emptyList()
    }

    private fun matchesPrefix6(bytes: ByteArray, prefix6: ByteArray): Boolean {
        for (i in 0 until 6) {
            if (bytes[i] != prefix6[i]) return false
        }
        return true
    }

    private fun BleWholeProtocol.IngeekPacket.toFrame(payload: ByteArray): BleWholeProtocol.Frame =
        BleWholeProtocol.Frame(
            format = BleWholeProtocol.FrameFormat.INGEEK_EXTENDED,
            version = 1,
            encryptionType = 0,
            serialNum = serialNum,
            messageId = messageId,
            payload = payload,
            raw = raw
        )

    fun reset() {
        versionedBuffer.clear()
        ingeekStates.clear()
    }
}

private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

fun ByteArray.toHex(): String {
    if (isEmpty()) return ""
    val chars = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_UPPER[value ushr 4]
        chars[i * 2 + 1] = HEX_UPPER[value and 0x0F]
    }
    return String(chars)
}

fun String.hexToBytes(): ByteArray {
    var text = trim()
    if (text.startsWith("0x", ignoreCase = true)) text = text.substring(2)
    val cleaned = text.filterNot { it.isWhitespace() || it == ':' || it == '-' || it == '_' }
    require(cleaned.length % 2 == 0) { "Hex input must contain an even number of digits" }
    val output = ByteArray(cleaned.length / 2)
    for (i in output.indices) {
        val high = Character.digit(cleaned[i * 2], 16)
        val low = Character.digit(cleaned[i * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "Invalid hex digit at position ${i * 2}" }
        output[i] = ((high shl 4) or low).toByte()
    }
    return output
}
