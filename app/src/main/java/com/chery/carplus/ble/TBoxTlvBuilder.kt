package com.chery.carplus.ble

import kotlin.math.roundToInt

/** TBox service identifiers verified from BleKeyTManager. */
object TBoxService {
    const val DOOR = 0x01
    const val TAILGATE = 0x02
    const val FIND_CAR = 0x03
    const val SKYLIGHT = 0x04
    const val WINDOW = 0x05
    const val ENGINE = 0x06
    const val AIR_CONDITIONER = 0x07
    const val TRUNK = 0x08
    const val ACCOUNT_SETTING = 0x80

    fun name(serviceId: Int): String = when (serviceId) {
        DOOR -> "车门"
        TAILGATE -> "尾门"
        FIND_CAR -> "寻车"
        SKYLIGHT -> "天窗"
        WINDOW -> "车窗"
        ENGINE -> "发动机"
        AIR_CONDITIONER -> "空调"
        TRUNK -> "后备箱"
        ACCOUNT_SETTING -> "账号绑定"
        else -> "服务 0x%02X".format(serviceId and 0xFF)
    }
}

data class TBoxTlvItem(val tag: Int, val value: ByteArray) {
    init {
        require(tag in 0..0xFF) { "TLV tag must fit in one byte" }
        require(value.size <= 0xFF) { "TLV value must fit in one byte length" }
    }

    fun encode(): ByteArray = byteArrayOf(tag.toByte(), value.size.toByte()) + value
}

data class TBoxCommand(
    val actionKey: String,
    val serviceId: Int,
    val logicalState: Int,
    val tlv: ByteArray
)

data class ParsedTBoxResponse(
    val messageId: Int,
    val serviceTag: Int?,
    val resultCode: Int?,
    val errorCode: Int?,
    val summaryText: String,
    val success: Boolean,
    val rawHex: String,
    val tlvs: List<TBoxTlvItem> = emptyList(),
    val doorLocked: Boolean? = null,
    val doorsOpen: Boolean? = null,
    val trunkOpen: Boolean? = null,
    val acOn: Boolean? = null,
    val engineRunning: Boolean? = null
)

/**
 * Builds the account-binding frame and the complete seventeen-action TBox
 * vehicle-control command set.  Wire values are intentionally kept identical to
 * mac_ble_bridge.swift and BleKeyTManager.java; the logical state values from the
 * host UI contract are retained separately in [TBoxCommand.logicalState].
 */
object TBoxTlvBuilder {
    const val TAG_RESULT = 0xE0
    const val TAG_ERROR = 0xDF

    private val actionKeys = linkedSetOf(
        "unlock", "lock", "find_car",
        "window_open", "window_ventilate", "window_close",
        "tailgate_open", "tailgate_close",
        "trunk_open", "trunk_close",
        "skylight_open", "skylight_tilt", "skylight_close",
        "engine_start", "engine_stop",
        "ac_on", "ac_off"
    )

    val supportedActions: Set<String> get() = actionKeys

    fun buildAccountSettingTlv(accountType: Int, userId: String, deviceId: String): ByteArray {
        require(accountType == 0 || accountType == 1) { "accountType must be 0 or 1" }
        val uid = ascii(userId, "userId")
        val dev = ascii(deviceId, "deviceId")
        require(uid.size in 1..0xFF) { "userId must contain 1..255 ASCII bytes" }
        require(dev.size in 1..0xFF) { "deviceId must contain 1..255 ASCII bytes" }

        val body = byteArrayOf(0x10, 0x01, accountType.toByte(), 0x11, uid.size.toByte()) +
            uid +
            byteArrayOf(0x12, dev.size.toByte()) +
            dev
        require(body.size <= 0xFF) { "account-setting body exceeds one-byte length" }
        return byteArrayOf(0x80.toByte(), body.size.toByte()) + body
    }

    fun unlock(): ByteArray = door(unlock = true)
    fun lock(): ByteArray = door(unlock = false)

    fun findCar(): ByteArray = command(TBoxService.FIND_CAR, emptyList())

    fun windowOpen(): ByteArray = window(0x03)
    fun windowVentilate(): ByteArray = window(0x01)
    fun windowClose(): ByteArray = window(0x00)

    fun tailgateOpen(): ByteArray = tailgate(open = true)
    fun tailgateClose(): ByteArray = tailgate(open = false)

    fun trunkOpen(): ByteArray = trunk(open = true)
    fun trunkClose(): ByteArray = trunk(open = false)

    fun skylightOpen(): ByteArray = skylight(0x01)
    fun skylightTilt(): ByteArray = skylight(0x02)
    fun skylightClose(): ByteArray = skylight(0x00)

    fun engineStart(durationMin: Int = 10): ByteArray = engine(start = true, durationMin)
    fun engineStop(durationMin: Int = 10): ByteArray = engine(start = false, durationMin)

    fun acOn(temperatureCelsius: Double = 24.0, durationMin: Int = 10): ByteArray =
        airConditioner(enable = true, temperatureCelsius, durationMin)

    fun acOff(temperatureCelsius: Double = 24.0, durationMin: Int = 10): ByteArray =
        airConditioner(enable = false, temperatureCelsius, durationMin)

    fun customTlv(hexString: String): ByteArray {
        val value = hexString.hexToBytes()
        require(value.isNotEmpty()) { "Custom TLV must not be empty" }
        return value
    }

    fun buildAction(
        actionKey: String,
        temperatureCelsius: Double = 24.0,
        durationMin: Int = 10
    ): TBoxCommand {
        val key = actionKey.trim().lowercase()
        require(key in actionKeys) { "Unsupported TBox action: $actionKey" }
        val tlv = when (key) {
            "unlock" -> unlock()
            "lock" -> lock()
            "find_car" -> findCar()
            "window_open" -> windowOpen()
            "window_ventilate" -> windowVentilate()
            "window_close" -> windowClose()
            "tailgate_open" -> tailgateOpen()
            "tailgate_close" -> tailgateClose()
            "trunk_open" -> trunkOpen()
            "trunk_close" -> trunkClose()
            "skylight_open" -> skylightOpen()
            "skylight_tilt" -> skylightTilt()
            "skylight_close" -> skylightClose()
            "engine_start" -> engineStart(durationMin)
            "engine_stop" -> engineStop(durationMin)
            "ac_on" -> acOn(temperatureCelsius, durationMin)
            "ac_off" -> acOff(temperatureCelsius, durationMin)
            else -> error("Unreachable action key")
        }
        return TBoxCommand(
            actionKey = key,
            serviceId = tlv[0].toInt() and 0xFF,
            logicalState = logicalState(key),
            tlv = tlv
        )
    }

    fun keyForService(serviceId: Int): String = when (serviceId) {
        TBoxService.DOOR -> "door"
        TBoxService.TAILGATE -> "tailgate"
        TBoxService.FIND_CAR -> "find_car"
        TBoxService.SKYLIGHT -> "skylight"
        TBoxService.WINDOW -> "window"
        TBoxService.ENGINE -> "engine"
        TBoxService.AIR_CONDITIONER -> "air_conditioner"
        TBoxService.TRUNK -> "trunk"
        TBoxService.ACCOUNT_SETTING -> "account_setting"
        else -> "service_0x%02x".format(serviceId and 0xFF)
    }

    fun parseResponse(messageId: Int, payload: ByteArray): ParsedTBoxResponse {
        val rawHex = payload.toHex()
        if (payload.isEmpty()) {
            return ParsedTBoxResponse(
                messageId = messageId,
                serviceTag = null,
                resultCode = null,
                errorCode = null,
                summaryText = "收到空响应（消息 0x%02X）".format(messageId and 0xFF),
                success = false,
                rawHex = rawHex
            )
        }

        val tlvs = parseTlvItems(payload)
        val resultItem = tlvs.firstOrNull { (it.tag and 0xFF) == TAG_RESULT }
        val errorItem = tlvs.firstOrNull { (it.tag and 0xFF) == TAG_ERROR }
        var resultCode = resultItem?.value?.firstOrNull()?.toInt()?.and(0xFF)
        var errorCode = errorItem?.value?.takeIf { it.isNotEmpty() }?.let { value ->
            if (value.size >= 2) {
                ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
            } else {
                value[0].toInt() and 0xFF
            }
        }

        val compact = looksLikeCompactActionResult(payload)
        if (compact) {
            if (resultCode == null) resultCode = payload[1].toInt() and 0xFF
            if (errorCode == null && payload.size >= 3) errorCode = payload[2].toInt() and 0xFF
        }

        val serviceTag = when {
            payload.size >= 2 && payload[0].toInt().and(0xFF) in 0x01..0x08 &&
                (payload[1].toInt() and 0xFF) == payload.size - 2 -> payload[0].toInt() and 0xFF
            compact -> payload[0].toInt() and 0xFF
            else -> tlvs.firstOrNull()?.tag?.and(0xFF)
        }

        val success = when {
            resultCode != null -> resultCode == 1 && (errorCode == null || errorCode == 0)
            errorCode != null -> errorCode == 0
            compact && payload.size >= 2 -> (payload[1].toInt() and 0xFF) == 1 &&
                (payload.size < 3 || (payload[2].toInt() and 0xFF) == 0)
            else -> false
        }

        var doorLocked: Boolean? = null
        var doorsOpen: Boolean? = null
        var trunkOpen: Boolean? = null
        var acOn: Boolean? = null
        var engineRunning: Boolean? = null

        when (serviceTag) {
            TBoxService.DOOR -> {
                tlvs.firstOrNull { it.tag == 0x01 }?.value?.firstOrNull()?.toInt()?.and(0xFF)?.let { state ->
                    when (state) {
                        0x00 -> doorLocked = true
                        0x01 -> doorLocked = false
                    }
                }
                tlvs.firstOrNull { it.tag == 0x10 }?.value?.toUnsignedWord()?.let { lockBits ->
                    when ((lockBits shr 2) and 0x03) {
                        0 -> if (doorLocked == null) doorLocked = true
                        1 -> if (doorLocked == null) doorLocked = false
                    }
                }
                tlvs.firstOrNull { it.tag == 0x11 }?.value?.let { doorBitsValue ->
                    val word = doorBitsValue.toUnsignedWord()
                    val flOpen = (word and 0x03) == 1
                    val frOpen = ((word shr 2) and 0x03) == 1
                    val rlOpen = ((word shr 4) and 0x03) == 1
                    val rrOpen = ((word shr 6) and 0x03) == 1
                    doorsOpen = flOpen || frOpen || rlOpen || rrOpen
                    if (doorBitsValue.size >= 2) {
                        when ((word shr 8) and 0x03) {
                            0 -> trunkOpen = false
                            1 -> trunkOpen = true
                        }
                    }
                }
            }
            TBoxService.ENGINE -> {
                tlvs.firstOrNull { it.tag == 0x01 }?.value?.firstOrNull()?.toInt()?.and(0xFF)?.let { state ->
                    when (state) {
                        0x01 -> engineRunning = true
                        0x02, 0x00 -> engineRunning = false
                    }
                }
            }
            TBoxService.AIR_CONDITIONER -> {
                tlvs.firstOrNull { it.tag == 0x01 }?.value?.firstOrNull()?.toInt()?.and(0xFF)?.let { state ->
                    when (state) {
                        0x01 -> acOn = true
                        0x02, 0x00 -> acOn = false
                    }
                }
            }
        }

        val summary = buildSummary(messageId, serviceTag, resultCode, errorCode, success, tlvs)
        return ParsedTBoxResponse(
            messageId = messageId,
            serviceTag = serviceTag,
            resultCode = resultCode,
            errorCode = errorCode,
            summaryText = summary,
            success = success,
            rawHex = rawHex,
            tlvs = tlvs,
            doorLocked = doorLocked,
            doorsOpen = doorsOpen,
            trunkOpen = trunkOpen,
            acOn = acOn,
            engineRunning = engineRunning
        )
    }

    fun parseTlvItems(payload: ByteArray): List<TBoxTlvItem> {
        val candidates = mutableListOf<List<TBoxTlvItem>>()
        val starts = linkedSetOf(0)
        if (payload.size >= 2 && (payload[1].toInt() and 0xFF) == payload.size - 2) starts += 2
        for (start in starts) {
            val parsed = runCatching { parseTlvSequence(payload, start) }.getOrNull()
            if (parsed != null) candidates += parsed
        }
        // Some responses carry an action/result/reason prefix before the TLVs.
        if (payload.size > 3) {
            for (start in 1..minOf(3, payload.size - 2)) {
                runCatching { parseTlvSequence(payload, start) }.getOrNull()?.let { candidates += it }
            }
        }
        return candidates.maxByOrNull { scoreTlvList(it) } ?: emptyList()
    }

    private fun parseTlvSequence(payload: ByteArray, start: Int): List<TBoxTlvItem> {
        val items = ArrayList<TBoxTlvItem>()
        var offset = start
        while (offset < payload.size) {
            if (offset + 2 > payload.size) throw IllegalArgumentException("Truncated TLV header")
            val tag = payload[offset].toInt() and 0xFF
            val length = payload[offset + 1].toInt() and 0xFF
            val end = offset + 2 + length
            if (end > payload.size) throw IllegalArgumentException("Truncated TLV value")
            items += TBoxTlvItem(tag, payload.copyOfRange(offset + 2, end))
            offset = end
        }
        require(items.isNotEmpty()) { "No TLVs" }
        return items
    }

    private fun scoreTlvList(items: List<TBoxTlvItem>): Int {
        var score = items.size * 10
        if (items.any { it.tag == TAG_RESULT }) score += 100
        if (items.any { it.tag == TAG_ERROR }) score += 100
        if (items.all { it.tag in 0x01..0xFF }) score += 5
        return score
    }

    private fun ByteArray.toUnsignedWord(): Int {
        val first = if (isEmpty()) 0 else this[0].toInt() and 0xFF
        val second = if (size < 2) 0 else this[1].toInt() and 0xFF
        return if (size < 2) first else (first shl 8) or second
    }

    private fun looksLikeCompactActionResult(payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        val action = payload[0].toInt() and 0xFF
        val result = payload[1].toInt() and 0xFF
        return action in 0x01..0x08 && result in 0x00..0x02
    }

    private fun buildSummary(
        messageId: Int,
        serviceTag: Int?,
        resultCode: Int?,
        errorCode: Int?,
        success: Boolean,
        tlvs: List<TBoxTlvItem>
    ): String {
        val serviceName = serviceTag?.let(TBoxService::name)
            ?: if (messageId == BleWholeProtocol.MSG_VEHICLE_INFO) "车辆状态" else "车辆服务"
        val resultText = when (resultCode) {
            1 -> "执行成功"
            2 -> "执行失败"
            null -> if (success) "已受理" else "结果未知"
            else -> "结果码 0x%02X".format(resultCode and 0xFF)
        }
        val errorText = when (errorCode) {
            null, 0 -> ""
            else -> "，错误码 0x%02X".format(errorCode and 0xFF)
        }
        val details = tlvs.joinToString(separator = "，") { item ->
            val label = when (item.tag) {
                TAG_RESULT -> "执行结果"
                TAG_ERROR -> "错误码"
                else -> "标签0x%02X".format(item.tag)
            }
            "$label=${item.value.toHex()}"
        }.take(240)
        val prefix = if (messageId == BleWholeProtocol.MSG_VEHICLE_INFO) {
            "车辆状态更新"
        } else {
            "$serviceName$resultText"
        }
        return if (details.isEmpty()) "$prefix$errorText" else "$prefix$errorText；$details"
    }

    private fun door(unlock: Boolean): ByteArray = command(
        TBoxService.DOOR,
        listOf(TBoxTlvItem(0x01, byteArrayOf(if (unlock) 0x01 else 0x00)))
    )

    private fun tailgate(open: Boolean): ByteArray = command(
        TBoxService.TAILGATE,
        listOf(TBoxTlvItem(0x20, byteArrayOf(if (open) 0x01 else 0x00)))
    )

    private fun window(wireState: Int): ByteArray = command(
        TBoxService.WINDOW,
        listOf(TBoxTlvItem(0x40, byteArrayOf(wireState.toByte())))
    )

    private fun skylight(wireState: Int): ByteArray = command(
        TBoxService.SKYLIGHT,
        listOf(
            TBoxTlvItem(0x30, byteArrayOf(wireState.toByte())),
            TBoxTlvItem(0x31, byteArrayOf(0x0B)),
            TBoxTlvItem(0x34, byteArrayOf(0x01))
        )
    )

    private fun engine(start: Boolean, durationMin: Int): ByteArray {
        val duration = validDuration(durationMin)
        return command(
            TBoxService.ENGINE,
            listOf(
                TBoxTlvItem(0x01, byteArrayOf(if (start) 0x01 else 0x00)),
                TBoxTlvItem(0x03, byteArrayOf(duration.toByte()))
            )
        )
    }

    private fun airConditioner(enable: Boolean, temperatureCelsius: Double, durationMin: Int): ByteArray {
        val duration = validDuration(durationMin)
        val scaledTemperature = (temperatureCelsius * 10.0).roundToInt().coerceIn(160, 320)
        return command(
            TBoxService.AIR_CONDITIONER,
            listOf(
                TBoxTlvItem(0x01, byteArrayOf(if (enable) 0x01 else 0x00)),
                TBoxTlvItem(0x03, byteArrayOf(duration.toByte())),
                TBoxTlvItem(
                    0x62,
                    byteArrayOf(
                        ((scaledTemperature ushr 8) and 0xFF).toByte(),
                        (scaledTemperature and 0xFF).toByte()
                    )
                )
            )
        )
    }

    private fun trunk(open: Boolean): ByteArray = command(
        TBoxService.TRUNK,
        listOf(TBoxTlvItem(0x01, byteArrayOf(if (open) 0x01 else 0x00)))
    )

    private fun command(serviceId: Int, items: List<TBoxTlvItem>): ByteArray {
        val body = items.fold(ByteArray(0)) { acc, item -> acc + item.encode() }
        require(body.size <= 0xFF) { "TBox body exceeds one-byte length" }
        return byteArrayOf(serviceId.toByte(), body.size.toByte()) + body
    }

    private fun validDuration(durationMin: Int): Int {
        require(durationMin == 5 || durationMin == 10 || durationMin == 15) {
            "durationMin must be 5, 10, or 15"
        }
        return durationMin
    }

    private fun logicalState(actionKey: String): Int = when (actionKey) {
        "unlock", "tailgate_open", "trunk_open", "skylight_tilt" -> 2
        "lock", "tailgate_close", "trunk_close" -> 1
        "find_car", "window_open", "skylight_open", "engine_start", "ac_on" -> 1
        "window_ventilate", "engine_stop", "ac_off" -> 2
        "window_close", "skylight_close" -> 3
        else -> 0
    }

    private fun ascii(value: String, fieldName: String): ByteArray {
        require(value.all { it.code in 0x20..0x7E }) { "$fieldName must contain printable ASCII only" }
        return value.toByteArray(Charsets.US_ASCII)
    }
}
