package com.chery.carplus.cloud

import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.ui.VehicleModelRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Isolated, mutable in-memory backend for Qiri+ Demo Mode (`demo` / `demo`).
 *
 * Dynamically provisions one complete demo vehicle for EVERY supported 3D model
 * returned by [VehicleModelRepository.listSupportedModels] (so as the catalog
 * grows from 2 to N vehicles, Demo Mode automatically exposes all N models).
 *
 * All quick controls, telemetry, vehicle switcher, drivers, geofences, trips,
 * messages, charging/travel schedules, OTA check, and profile edits mutate local
 * in-memory state cleanly with zero network or BLE errors and zero writes to
 * real user accounts or SharedPreferences.
 */
class DemoVehicleBackend(
    initialModels: List<VehicleModelRepository.ModelCatalogEntry>,
    private val deviceId: String
) {
    private val lock = Any()

    private data class DemoVehicleEntry(
        val modelKey: String,
        val displayName: String,
        val bodyType: String,
        val vin: String,
        var plateNo: String,
        var hostName: String,
        var purchaseDate: String,
        val engineNumber: String,
        val sn: String,
        val iccid: String,
        val bleMac: String,
        val bleName: String,
        val engineType: String, // "1"=EV, "2"=PHEV, "3"=ICE
        val colorName: String,
        var doorLocked: Boolean = true,
        var acOn: Boolean = false,
        var acTemp: Int = 24,
        var trunkOpen: Boolean = false,
        var engineRunning: Boolean = false,
        var windowsState: String = "closed",
        var sunroofState: String = "closed",
        var driverSeatHeat: Boolean = false,
        var passengerSeatHeat: Boolean = false,
        var frontDefrost: Boolean = false,
        var rearDefrost: Boolean = false,
        var airPurification: Boolean = false,
        var openAir: Boolean = true,
        var isTracksOpen: Boolean = true,
        var theftRemind: Boolean = true,
        var speedLimited: Boolean = false,
        var speedLimitKmh: String = "120",
        var fuelPercent: Double?,
        var batteryPercent: Double?,
        var electricRangeKm: Double?,
        var remainingRangeKm: Double,
        var odometerKm: Double,
        var batteryVoltage: Double = 12.6,
        var lat: Double,
        var lon: Double,
        var address: String,
        var tyresBar: List<String>,
        var tyreTempsC: List<String>,
        var averageFuel: String,
        var averageElectricity: String,
        var chargingMasterEnabled: Boolean,
        val chargingSchedules: MutableList<ChargingScheduleItem>,
        var travelMasterEnabled: Boolean,
        val travelSchedules: MutableList<TravelScheduleItem>,
        val geofences: MutableList<GeofenceItem>,
        val drivers: MutableList<VehicleAuthorizationItem>,
        val driverRecords: MutableList<AuthorizationOperationRecordItem>,
        var otaState: String = "1",
        var otaProgress: Int = 0
    )

    private val vehicles = LinkedHashMap<String, DemoVehicleEntry>()
    private var activeVin: String = ""
    private var defaultVin: String = ""
    private var userNickname: String = "演示体验官"
    private var userSex: String = "1"
    private var nextIdCounter = 100

    private val demoMessages = listOf(
        ZyhlMessageItem(
            functionId = "demo_msg_1",
            type = "1",
            typeDesc = "车况提醒",
            title = "欢迎进入 Qiri+ 演示模式",
            description = "已为您加载全部支持车型，支持车控、定位、行程、围栏、驾驶员授权与充电预约等全功能体验。",
            content = "当前处于离线演示沙箱环境，支持在左上角自由切换全部支持车型并体验 3D 联动控车、电子围栏、行程轨迹、驾驶员管理及 OTA 检查。退出登录不会影响您的真实账号数据。",
            messageTime = formatRelativeTime(-15)
        ),
        ZyhlMessageItem(
            functionId = "demo_msg_2",
            type = "2",
            typeDesc = "安全通知",
            title = "车辆已安全驻车并上锁",
            description = "四门、车窗与天窗均已关闭，胎压状态正常。",
            content = "最近一次驻车自检已完成：四门已锁止，四窗与天窗处于关闭状态，四轮胎压均衡正常，蓄电池电压 12.6V。",
            messageTime = formatRelativeTime(-95)
        ),
        ZyhlMessageItem(
            functionId = "demo_msg_3",
            type = "3",
            typeDesc = "系统更新",
            title = "发现车机系统新版本 V2.9.16",
            description = "优化座舱语音唤醒速度与远程空调响应延迟。",
            content = "本次升级包包含智能座舱流畅度优化、远程控车低功耗唤醒优化及导航车机互联体验提升，可在「车辆信息 → 车辆版本」中预览升级流程。",
            messageTime = formatRelativeTime(-360)
        )
    )

    init {
        syncModels(initialModels)
    }

    fun syncModels(models: List<VehicleModelRepository.ModelCatalogEntry>): List<VehicleProfile> = synchronized(lock) {
        val effectiveModels = models.ifEmpty { VehicleModelRepository.listSupportedModels() }
        effectiveModels.forEachIndexed { idx, entry ->
            val vin = vinForIndex(idx)
            if (!vehicles.containsKey(vin)) {
                vehicles[vin] = createVehicleEntry(idx, entry, vin)
            }
        }
        if (activeVin.isBlank() || !vehicles.containsKey(activeVin)) {
            activeVin = vehicles.keys.firstOrNull().orEmpty()
        }
        if (defaultVin.isBlank() || !vehicles.containsKey(defaultVin)) {
            defaultVin = activeVin
        }
        buildProfilesLocked()
    }

    fun vehicleCount(): Int = synchronized(lock) { vehicles.size }

    fun setActiveVin(vin: String) = synchronized(lock) {
        val norm = vin.trim().uppercase(Locale.US)
        if (vehicles.containsKey(norm)) {
            activeVin = norm
        }
    }

    fun modelKeyForVin(vin: String): String = synchronized(lock) {
        vehicles[vin.trim().uppercase(Locale.US)]?.modelKey
            ?: vehicles[activeVin]?.modelKey
            ?: "arrizo8"
    }

    fun buildProfiles(): List<VehicleProfile> = synchronized(lock) { buildProfilesLocked() }

    private fun buildProfilesLocked(): List<VehicleProfile> = vehicles.values.map { v ->
        VehicleProfile(
            vin = v.vin,
            userId = DEMO_USER_ID,
            mobileNo = DEMO_MOBILE,
            bleName = v.bleName,
            bleMac = v.bleMac,
            deviceId = deviceId,
            accountType = 0,
            rootKeyHex = DEMO_ROOT_KEY_HEX,
            infoHex = "",
            edfHex = "",
            validFrom = "2026-01-01 00:00:00",
            validTo = "2028-12-31 23:59:59",
            keyRole = 1,
            accessToken = DEMO_ACCESS_TOKEN,
            sdkToken = DEMO_SDK_TOKEN,
            controlPin = DEMO_PIN
        )
    }

    fun listVehicleSummaries(): List<VehicleSummary> = synchronized(lock) {
        vehicles.values.map { toVehicleSummary(it) }
    }

    fun summaryFor(vin: String): VehicleSummary? = synchronized(lock) {
        val entry = resolveEntryLocked(vin) ?: return@synchronized null
        toVehicleSummary(entry)
    }

    fun telemetryFor(vin: String): VehicleTelemetry? = synchronized(lock) {
        val entry = resolveEntryLocked(vin) ?: return@synchronized null
        toTelemetry(entry)
    }

    fun userInfo(): CheryUserInfo = synchronized(lock) {
        val tracksOpen = vehicles[activeVin]?.isTracksOpen ?: true
        CheryUserInfo(
            userId = DEMO_USER_ID,
            telephone = DEMO_MOBILE,
            account = DEMO_ACCOUNT,
            nickname = userNickname,
            rawJson = JSONObject().apply {
                put("userId", DEMO_USER_ID)
                put("telephone", DEMO_MOBILE)
                put("account", DEMO_ACCOUNT)
                put("nickname", userNickname)
                put("sex", userSex)
                put("isTracksOpen", if (tracksOpen) "1" else "0")
            }.toString(),
            isTracksOpen = tracksOpen
        )
    }

    fun applyQuickCommand(
        vin: String,
        actionKey: String,
        temperatureCelsius: Double? = null,
        extraParams: Map<String, String> = emptyMap()
    ): VehicleTelemetry? = synchronized(lock) {
        val v = resolveEntryLocked(vin) ?: return@synchronized null
        when (actionKey) {
            "lock" -> v.doorLocked = true
            "unlock" -> v.doorLocked = false
            "ac_on" -> {
                v.acOn = true
                temperatureCelsius?.let { v.acTemp = it.roundToInt().coerceIn(16, 32) }
                extraParams["airPM"]?.let { v.airPurification = it == "1" }
                extraParams["frontDefrost"]?.let { v.frontDefrost = it == "1" }
                extraParams["rearDefrost"]?.let { v.rearDefrost = it == "1" }
                extraParams["driverHeating"]?.let { v.driverSeatHeat = it != "0" }
                extraParams["passengerHeating"]?.let { v.passengerSeatHeat = it != "0" }
            }
            "ac_off" -> {
                v.acOn = false
                v.frontDefrost = false
                v.rearDefrost = false
                v.driverSeatHeat = false
                v.passengerSeatHeat = false
            }
            "trunk_open", "tailgate_open" -> v.trunkOpen = true
            "tailgate_close", "trunk_close" -> v.trunkOpen = false
            "engine_start" -> v.engineRunning = true
            "engine_stop" -> v.engineRunning = false
            "window_open" -> v.windowsState = "open"
            "window_ventilate" -> v.windowsState = "vent"
            "window_close" -> v.windowsState = "closed"
            "skylight_open" -> v.sunroofState = "open"
            "skylight_tilt" -> v.sunroofState = "tilt"
            "skylight_close" -> v.sunroofState = "closed"
            "find_car" -> Unit
        }
        toTelemetry(v)
    }

    fun reverseGeocode(lat: Double, lon: Double): String = synchronized(lock) {
        val matched = vehicles.values.minByOrNull { v ->
            val dLat = v.lat - lat
            val dLon = v.lon - lon
            dLat * dLat + dLon * dLon
        }
        matched?.address ?: "安徽省芜湖市鸠江区鞍山路 8 号"
    }

    fun searchPoi(query: String, biasLat: Double?, biasLon: Double?): List<PoiSearchResult> = synchronized(lock) {
        val baseLat = biasLat ?: vehicles[activeVin]?.lat ?: 31.3526
        val baseLon = biasLon ?: vehicles[activeVin]?.lon ?: 118.4329
        val q = query.trim().ifBlank { "奇瑞" }
        listOf(
            PoiSearchResult(
                name = "$q · 示范中心",
                address = "安徽省芜湖市鸠江区鞍山路 8 号",
                lat = baseLat + 0.0085,
                lon = baseLon + 0.0112
            ),
            PoiSearchResult(
                name = "$q 停车场（地上东区）",
                address = "安徽省芜湖市镜湖区滨江大道 168 号",
                lat = baseLat - 0.0124,
                lon = baseLon - 0.0195
            ),
            PoiSearchResult(
                name = "$q 超级充电站",
                address = "安徽省芜湖市弋江区中山南路 218 号",
                lat = baseLat - 0.0210,
                lon = baseLon + 0.0094
            )
        )
    }

    fun handleService(serviceType: String, data: JSONObject): JSONObject = synchronized(lock) {
        val vin = data.optString("vin").trim().uppercase(Locale.US).ifBlank { activeVin }
        val entry = resolveEntryLocked(vin)

        when (serviceType.trim()) {
            CheryHttpClient.SERVICE_USER_INFO -> okEnvelope(
                JSONObject().apply {
                    put("userId", DEMO_USER_ID)
                    put("telephone", DEMO_MOBILE)
                    put("account", DEMO_ACCOUNT)
                    put("nickname", userNickname)
                    put("nickName", userNickname)
                    put("sex", userSex)
                    put("isTracksOpen", if (entry?.isTracksOpen != false) "1" else "0")
                }
            )

            "113" -> {
                val updateType = data.optString("updateType")
                val content = data.optString("content").trim()
                if (updateType == "1" && content.isNotBlank()) userNickname = content
                if (updateType == "2" && content.isNotBlank()) userSex = content
                okEnvelope(JSONObject().put("updated", true))
            }

            CheryHttpClient.SERVICE_VEHICLE_LIST, CheryHttpClient.SERVICE_VEHICLE_INFO -> {
                val arr = JSONArray()
                vehicles.values.forEach { arr.put(toVehicleJson(it)) }
                okEnvelope(JSONObject().put("vehicleList", arr))
            }

            CheryHttpClient.SERVICE_VEHICLE_STATUS -> {
                val target = entry ?: return@synchronized okEnvelope(JSONObject())
                okEnvelope(toTelemetryJson(target))
            }

            CheryHttpClient.SERVICE_VERIFY_PIN -> {
                // Accept any valid 6-digit PIN (or hashed PIN) in Demo Mode so users are never blocked
                okEnvelope(JSONObject().put("verified", true))
            }

            "935" -> okEnvelope(JSONObject().put("simStatus", "1"))

            "932" -> {
                if (entry != null) defaultVin = entry.vin
                okEnvelope(JSONObject().put("vin", entry?.vin.orEmpty()))
            }

            "933" -> {
                if (entry != null) {
                    val plate = data.optString("licenseNum").trim()
                    val host = data.optString("hostName").trim()
                    val buy = data.optString("purchaseDate").trim()
                    if (plate.isNotBlank()) entry.plateNo = plate
                    if (host.isNotBlank()) entry.hostName = host
                    if (buy.isNotBlank()) entry.purchaseDate = buy
                }
                okEnvelope(JSONObject().put("saved", true))
            }

            "931", "934" -> {
                okEnvelope(JSONObject().put("demoIgnored", true), "演示模式已模拟完成")
            }

            "930" -> okEnvelope(JSONObject().put("simStatus", "1"), "演示模式已模拟绑定")

            CheryHttpClient.SERVICE_REMOTE_COMMAND -> {
                val instruction = data.optString("instruction").trim()
                val paras = data.optJSONObject("paras")
                if (entry != null) {
                    when (instruction) {
                        "1110" -> {
                            entry.chargingMasterEnabled = paras?.optInt("3A04", 0) == 1
                            val arr = paras?.optJSONArray("3A19")
                            if (arr != null) {
                                entry.chargingSchedules.clear()
                                for (i in 0 until arr.length()) {
                                    val obj = arr.optJSONObject(i) ?: continue
                                    val mins = obj.optInt("3A05", 1380)
                                    val hh = (mins / 60).coerceIn(0, 23)
                                    val mm = (mins % 60).coerceIn(0, 59)
                                    val dur = obj.optInt("3A1A", 4).coerceIn(1, 24)
                                    val daysArr = obj.optJSONArray("3A1B")
                                    val days = mutableListOf<Int>()
                                    if (daysArr != null) {
                                        for (d in 0 until daysArr.length()) {
                                            daysArr.optString(d).toIntOrNull()?.takeIf { it in 1..7 }?.let { days.add(it) }
                                        }
                                    }
                                    entry.chargingSchedules.add(
                                        ChargingScheduleItem(
                                            chargeId = "demo_chg_${++nextIdCounter}",
                                            startTime = String.format(Locale.US, "%02d:%02d", hh, mm),
                                            durationHours = dur,
                                            days = days.ifEmpty { listOf(1, 2, 3, 4, 5) },
                                            enabled = obj.optInt("3A04", 1) == 1
                                        )
                                    )
                                }
                            }
                        }
                        "1111" -> {
                            entry.travelMasterEnabled = paras?.optInt("0002", 0) == 1
                            val arr = paras?.optJSONArray("302E")
                            if (arr != null) {
                                entry.travelSchedules.clear()
                                for (i in 0 until arr.length()) {
                                    val obj = arr.optJSONObject(i) ?: continue
                                    val mins = obj.optInt("302D", 480)
                                    val hh = (mins / 60).coerceIn(0, 23)
                                    val mm = (mins % 60).coerceIn(0, 59)
                                    entry.travelSchedules.add(
                                        TravelScheduleItem(
                                            startTime = String.format(Locale.US, "%02d:%02d", hh, mm),
                                            enabled = obj.optInt("0002", 1) == 1
                                        )
                                    )
                                }
                            }
                        }
                        "1115" -> {
                            entry.otaState = "2"
                            entry.otaProgress = 25
                        }
                        else -> applyInstructionToEntry(entry, instruction, paras)
                    }
                }
                okEnvelope(
                    JSONObject().apply {
                        put("seq", "demo_${System.currentTimeMillis()}")
                        put("status", "1")
                        put("result", "1")
                    },
                    "演示指令执行成功"
                )
            }

            "127" -> {
                if (entry != null) {
                    entry.isTracksOpen = data.optString("openTracks") == "1"
                }
                okEnvelope(JSONObject().put("openTracks", if (entry?.isTracksOpen != false) "1" else "0"))
            }

            "975" -> {
                if (entry != null) {
                    entry.openAir = data.optString("openAir") == "1"
                }
                okEnvelope(JSONObject().put("openAir", if (entry?.openAir != false) "1" else "0"))
            }

            "950" -> okEnvelope(
                JSONObject().apply {
                    put("theftRemind", if (entry?.theftRemind != false) "1" else "0")
                    put("speedLimited", if (entry?.speedLimited == true) "1" else "0")
                    put("speed", entry?.speedLimitKmh ?: "120")
                }
            )

            "121", "122", "123", "124", "126", "922" -> okEnvelope(JSONObject().put("ok", true))

            "963" -> {
                val arr = JSONArray()
                demoMessages.forEach { m ->
                    arr.put(
                        JSONObject().apply {
                            put("functionId", m.functionId)
                            put("type", m.type)
                            put("typeDesc", m.typeDesc)
                            put("title", m.title)
                            put("description", m.description)
                            put("content", m.content)
                            put("messageTime", m.messageTime)
                        }
                    )
                }
                okEnvelope(JSONObject().put("list", arr))
            }

            "216" -> {
                val fid = data.optString("functionId")
                val matched = demoMessages.firstOrNull { it.functionId == fid } ?: demoMessages.first()
                okEnvelope(
                    JSONObject().apply {
                        put("id", matched.functionId)
                        put("title", matched.title)
                        put("content", matched.content)
                        put("time", matched.messageTime)
                    }
                )
            }

            "980" -> okEnvelope(
                JSONObject().put(
                    "list",
                    JSONArray().put(
                        JSONObject().apply {
                            put("remindId", "demo_maint_1")
                            put("remindDesc", "距下次常规保养还有 2,520 公里")
                            put("remindTime", formatDateOffset(0))
                        }
                    )
                )
            )

            "940" -> {
                val arr = JSONArray()
                entry?.geofences?.forEach { g ->
                    arr.put(
                        JSONObject().apply {
                            put("keyId", g.keyId)
                            put("name", g.name)
                            put("shape", g.shape)
                            put("status", g.status)
                            put("type", g.type)
                            put(
                                "fence",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("lat", g.lat)
                                        put("lon", g.lon)
                                        put("radius", g.radiusMeters)
                                    }
                                )
                            )
                        }
                    )
                }
                okEnvelope(JSONObject().put("fenceList", arr))
            }

            "942" -> {
                if (entry != null) {
                    val pts = data.optJSONArray("pointList")
                    val p0 = pts?.optJSONObject(0)
                    val newFence = GeofenceItem(
                        keyId = "demo_fence_${++nextIdCounter}",
                        name = data.optString("name").ifBlank { "演示围栏" },
                        shape = data.optString("shape", "1"),
                        status = "1",
                        type = data.optString("type", "1"),
                        lat = p0?.optString("lat").orEmpty().ifBlank { entry.lat.toString() },
                        lon = p0?.optString("lon").orEmpty().ifBlank { entry.lon.toString() },
                        radiusMeters = p0?.optString("radius").orEmpty().ifBlank { "1000" }
                    )
                    entry.geofences.add(0, newFence)
                }
                okEnvelope(JSONObject().put("saved", true))
            }

            "941" -> {
                val keyId = data.optString("keyId")
                val opType = data.optString("type", "1")
                if (entry != null) {
                    if (opType == "3" || opType == "delete") {
                        entry.geofences.removeAll { it.keyId == keyId }
                    } else {
                        val idx = entry.geofences.indexOfFirst { it.keyId == keyId }
                        if (idx >= 0) {
                            val cur = entry.geofences[idx]
                            val nextStatus = when (opType) {
                                "1" -> "1"
                                "2", "0" -> "0"
                                else -> if (cur.status == "1") "0" else "1"
                            }
                            entry.geofences[idx] = cur.copy(status = nextStatus)
                        }
                    }
                }
                okEnvelope(JSONObject().put("ok", true))
            }

            "961" -> okEnvelope(
                JSONObject().apply {
                    put("score", 98)
                    put("status", "正常")
                    put("batteryVoltage", entry?.batteryVoltage ?: 12.6)
                    put("tyreStatus", "四轮胎压正常")
                    put("engineStatus", "动力系统正常")
                }
            )

            "148" -> okEnvelope(
                JSONObject().apply {
                    put("upgradeTaskId", "DEMO_OTA_2916")
                    put("whlVsn", "V2.9.15-CN")
                    put("state", entry?.otaState ?: "1")
                    put("rlsNt", "1. 优化座舱 3D 车控响应与语音唤醒速度\n2. 提升远程空调预热/预冷能效表现\n3. 修复偶发蓝牙钥匙近场重连延迟")
                    put("text", "预计耗时约 18 分钟，升级期间请保持驻车。")
                    put("upgTm", "18 分钟")
                    put("upgradeStartTime", "")
                }
            )

            "150" -> {
                if (entry != null && entry.otaState == "2") {
                    entry.otaProgress = (entry.otaProgress + 25).coerceAtMost(100)
                    if (entry.otaProgress >= 100) entry.otaState = "4"
                }
                okEnvelope(
                    JSONObject().apply {
                        put("state", entry?.otaState ?: "2")
                        put("percentage", entry?.otaProgress ?: 50)
                        put("surplusTime", "${((100 - (entry?.otaProgress ?: 50)) / 10).coerceAtLeast(1)} 分钟")
                    }
                )
            }

            "152", "145" -> {
                if (entry != null) {
                    entry.otaState = "2"
                    entry.otaProgress = 20
                }
                okEnvelope(JSONObject().put("ok", true))
            }

            "967" -> okEnvelope(
                JSONObject().apply {
                    put("usedTraffic", "4.6 GB")
                    put("totalTraffic", "20.0 GB")
                    put("unlimited", "0")
                    put("packageState", "生效中")
                    put("dueDate", "2027-12-31")
                }
            )

            "974" -> {
                val listArr = JSONArray()
                entry?.chargingSchedules?.forEach { item ->
                    val dArr = JSONArray()
                    item.days.forEach { d -> dArr.put(JSONObject().put("date", d.toString())) }
                    listArr.put(
                        JSONObject().apply {
                            put("chargeId", item.chargeId)
                            put("beStarttime", item.startTime)
                            put("chargingTime", item.durationHours.toString())
                            put("chargeAppintStatus", if (item.enabled) "1" else "0")
                            put("dateList", dArr)
                        }
                    )
                }
                okEnvelope(
                    JSONObject().apply {
                        put("chargeAppintStatus", if (entry?.chargingMasterEnabled == true) "1" else "0")
                        put("chargeAppintlist", listArr)
                    }
                )
            }

            "143" -> {
                val listArr = JSONArray()
                entry?.travelSchedules?.forEach { item ->
                    listArr.put(
                        JSONObject().apply {
                            put("travelStartTime", item.startTime)
                            put("travelStatus", if (item.enabled) "1" else "0")
                        }
                    )
                }
                okEnvelope(
                    JSONObject().apply {
                        put("travelStatus", if (entry?.travelMasterEnabled == true) "1" else "0")
                        put("travellist", listArr)
                    }
                )
            }

            "943" -> okEnvelope(JSONObject().put("sent", true), "已发送目的地至演示车机")

            "140" -> okEnvelope(
                JSONObject().apply {
                    put("lastConsumeTime", "26 分钟")
                    put("totalConsumeTime", "186 小时")
                    put("lastDistance", "14.8")
                    put("totalDistance", (entry?.odometerKm ?: 12480.0).roundToInt().toString())
                }
            )

            "141" -> okEnvelope(buildDemoTripJson(entry, 0, "08:25", "08:51", "14.8", "26 分钟", "鸠江区鞍山路 8 号", "镜湖区滨江大道 168 号"))

            "973" -> {
                val dateList = JSONArray()
                val yesterday = formatDateOffset(-1)
                val today = formatDateOffset(0)
                dateList.put(
                    JSONObject().apply {
                        put("date", yesterday)
                        put(
                            "list",
                            JSONArray().put(
                                buildDemoTripJson(entry, -1, "18:10", "18:42", "18.6", "32 分钟", "镜湖区滨江大道 168 号", "鸠江区鞍山路 8 号")
                            )
                        )
                    }
                )
                dateList.put(
                    JSONObject().apply {
                        put("date", today)
                        put(
                            "list",
                            JSONArray().put(
                                buildDemoTripJson(entry, 0, "08:25", "08:51", "14.8", "26 分钟", "鸠江区鞍山路 8 号", "镜湖区滨江大道 168 号")
                            )
                        )
                    }
                )
                okEnvelope(JSONObject().put("dateList", dateList))
            }

            "978" -> okEnvelope(
                JSONObject().put(
                    "list",
                    JSONArray()
                        .put(JSONObject().put("dateId", formatDateOffset(-1)).put("dateStr", formatDateOffset(-1)))
                        .put(JSONObject().put("dateId", formatDateOffset(0)).put("dateStr", formatDateOffset(0)))
                )
            )

            "979" -> okEnvelope(
                JSONObject().put(
                    "list",
                    JSONArray().put(
                        buildDemoTripJson(entry, 0, "08:25", "08:51", "14.8", "26 分钟", "鸠江区鞍山路 8 号", "镜湖区滨江大道 168 号")
                    )
                )
            )

            "901", "130", "1003" -> {
                val arr = JSONArray()
                entry?.drivers?.forEach { d ->
                    arr.put(
                        JSONObject().apply {
                            put("keyId", d.keyId)
                            put("vin", entry.vin)
                            put("receiverAccountId", d.authorizedUserId)
                            put("receiverAccountName", d.receiverName)
                            put("receiverMobile", d.receiverMobile)
                            put("starttime", d.startTime)
                            put("endtime", d.endTime)
                            put("status", d.status)
                            put("levelFlag", d.levelFlag)
                            put("authority", d.authority)
                            put("authorityDesc", d.permissionLabel)
                        }
                    )
                }
                okEnvelope(JSONObject().put("list", arr))
            }

            "902", "132" -> {
                if (entry != null) {
                    val keyId = "demo_drv_${++nextIdCounter}"
                    val name = data.optString("receiverAccountName").ifBlank { "家庭成员" }
                    val mobile = data.optString("receiverMobile").ifBlank { "13900139099" }
                    val level = data.optString("levelFlag", "1").ifBlank { "1" }
                    entry.drivers.add(
                        0,
                        VehicleAuthorizationItem(
                            keyId = keyId,
                            authType = level,
                            receiverName = name,
                            receiverMobile = mobile,
                            startTime = data.optString("starttime").ifBlank { formatDateOffset(0) + " 00:00:00" },
                            endTime = data.optString("endtime").ifBlank { "2027-12-31 23:59:59" },
                            status = "0",
                            levelFlag = level,
                            authority = level,
                            vin = entry.vin,
                            authorizedUserId = "demo_sub_$nextIdCounter"
                        )
                    )
                }
                okEnvelope(JSONObject().put("created", true))
            }

            "903", "134" -> {
                val keyId = data.optString("keyId")
                if (entry != null) {
                    val idx = entry.drivers.indexOfFirst { it.keyId == keyId }
                    if (idx >= 0) {
                        val cur = entry.drivers[idx]
                        entry.drivers[idx] = cur.copy(
                            receiverName = data.optString("receiverAccountName").ifBlank { cur.receiverName },
                            authorizedName = data.optString("receiverAccountName").ifBlank { cur.receiverName },
                            startTime = data.optString("starttime").ifBlank { cur.startTime },
                            endTime = data.optString("endtime").ifBlank { cur.endTime }
                        )
                    }
                }
                okEnvelope(JSONObject().put("updated", true))
            }

            "905" -> {
                val keyId = data.optString("keyId")
                val status = data.optString("status", "0")
                if (entry != null) {
                    val idx = entry.drivers.indexOfFirst { it.keyId == keyId }
                    if (idx >= 0) {
                        entry.drivers[idx] = entry.drivers[idx].copy(status = status)
                    }
                }
                okEnvelope(JSONObject().put("toggled", true))
            }

            "904", "131" -> {
                val keyId = data.optString("keyId")
                entry?.drivers?.removeAll { it.keyId == keyId }
                okEnvelope(JSONObject().put("deleted", true))
            }

            "137" -> {
                val arr = JSONArray()
                entry?.driverRecords?.forEach { r ->
                    arr.put(JSONObject().put("action", r.action).put("time", r.time))
                }
                okEnvelope(JSONObject().put("list", arr))
            }

            else -> okEnvelope(JSONObject().put("demo", true))
        }
    }

    private fun applyInstructionToEntry(entry: DemoVehicleEntry, instruction: String, paras: JSONObject?) {
        when (instruction) {
            "1101", "lock" -> entry.doorLocked = true
            "1102", "unlock" -> entry.doorLocked = false
            "1103", "ac_on" -> entry.acOn = true
            "1104", "ac_off" -> entry.acOn = false
            "1105", "engine_start" -> entry.engineRunning = true
            "1106", "engine_stop" -> entry.engineRunning = false
            "1107", "trunk_open", "tailgate_open" -> entry.trunkOpen = true
            "1108", "tailgate_close" -> entry.trunkOpen = false
            else -> {
                val temp = paras?.optString("temperature")?.toIntOrNull()
                if (temp != null) {
                    entry.acOn = true
                    entry.acTemp = temp.coerceIn(16, 32)
                }
            }
        }
    }

    private fun createVehicleEntry(
        idx: Int,
        model: VehicleModelRepository.ModelCatalogEntry,
        vin: String
    ): DemoVehicleEntry {
        val keyLower = model.key.lowercase(Locale.US)
        val nameLower = model.displayName.lowercase(Locale.US)
        val engineType = when {
            keyLower.contains("ev") || nameLower.contains("纯电") || nameLower.contains("蚂蚁") || nameLower.contains("星纪元") -> "1"
            keyLower == "tiggo8" || keyLower.contains("cdm") || keyLower.contains("phev") || nameLower.contains("混动") -> "2"
            else -> "3"
        }
        val plate = "皖B·D${"%04d".format(Locale.US, idx + 1)}"
        val macSuffix = "%02X".format(Locale.US, (idx + 1) and 0xFF)
        val bleMac = "AA:BB:CC:DE:00:$macSuffix"
        val bleName = "CHR_DEMO_${model.key.uppercase(Locale.US)}"
        val baseLat = 31.3526 - idx * 0.0145
        val baseLon = 118.4329 - idx * 0.0280
        val addr = when (idx % 3) {
            0 -> "安徽省芜湖市鸠江区鞍山路 8 号"
            1 -> "安徽省芜湖市镜湖区滨江大道 168 号"
            else -> "安徽省芜湖市弋江区中山南路 218 号"
        }

        val (fuelPct, batPct, elecKm, rangeKm, odoKm, avgFuel, avgElec) = when (engineType) {
            "1" -> Septuple(null, 86.0, 442.0, 442.0, 6_340.0 + idx * 920.0, "", "13.2")
            "2" -> Septuple(68.0, 84.0, 108.0, 826.0, 8_920.0 + idx * 640.0, "4.8", "13.8")
            else -> Septuple(78.0, null, null, 562.0, 12_480.0 + idx * 1_150.0, "6.4", "")
        }

        val tyres = if (model.bodyType.equals("suv", ignoreCase = true)) {
            listOf("2.5", "2.5", "2.4", "2.4")
        } else {
            listOf("2.4", "2.4", "2.3", "2.3")
        }

        return DemoVehicleEntry(
            modelKey = model.key,
            displayName = model.displayName,
            bodyType = model.bodyType,
            vin = vin,
            plateNo = plate,
            hostName = "奇瑞体验官",
            purchaseDate = "2025-06-18",
            engineNumber = "SQRF4J16DEMO${"%03d".format(Locale.US, idx + 1)}",
            sn = "TBXDEMO2026${"%04d".format(Locale.US, idx + 1)}",
            iccid = "898604A11925DEMO${"%04d".format(Locale.US, idx + 1)}",
            bleMac = bleMac,
            bleName = bleName,
            engineType = engineType,
            colorName = if (model.bodyType == "suv") "量子灰" else "石墨黑",
            fuelPercent = fuelPct,
            batteryPercent = batPct,
            electricRangeKm = elecKm,
            remainingRangeKm = rangeKm,
            odometerKm = odoKm,
            lat = baseLat,
            lon = baseLon,
            address = addr,
            tyresBar = tyres,
            tyreTempsC = listOf("28", "28", "27", "27"),
            averageFuel = avgFuel,
            averageElectricity = avgElec,
            chargingMasterEnabled = engineType != "3",
            chargingSchedules = mutableListOf(
                ChargingScheduleItem(
                    chargeId = "demo_chg_${idx}_1",
                    startTime = "23:00",
                    durationHours = 6,
                    days = listOf(1, 2, 3, 4, 5),
                    enabled = true
                )
            ),
            travelMasterEnabled = false,
            travelSchedules = mutableListOf(
                TravelScheduleItem(startTime = "08:00", enabled = false)
            ),
            geofences = mutableListOf(
                GeofenceItem(
                    keyId = "demo_fence_${idx}_1",
                    name = "公司园区安全围栏",
                    shape = "1",
                    status = "1",
                    type = "1",
                    lat = String.format(Locale.US, "%.6f", baseLat),
                    lon = String.format(Locale.US, "%.6f", baseLon),
                    radiusMeters = "1500"
                )
            ),
            drivers = mutableListOf(
                VehicleAuthorizationItem(
                    keyId = "demo_drv_${idx}_1",
                    authType = "1",
                    receiverName = "林女士（家人）",
                    receiverMobile = "13900139012",
                    startTime = "2026-01-01 00:00:00",
                    endTime = "2027-12-31 23:59:59",
                    status = "0",
                    levelFlag = "1",
                    authority = "1",
                    vin = vin,
                    authorizedUserId = "demo_sub_${idx}_1"
                ),
                VehicleAuthorizationItem(
                    keyId = "demo_drv_${idx}_2",
                    authType = "0",
                    receiverName = "陈先生（访客）",
                    receiverMobile = "13600136088",
                    startTime = "2026-11-01 00:00:00",
                    endTime = "2026-12-31 23:59:59",
                    status = "1",
                    levelFlag = "0",
                    authority = "0",
                    vin = vin,
                    authorizedUserId = "demo_sub_${idx}_2"
                )
            ),
            driverRecords = mutableListOf(
                AuthorizationOperationRecordItem(action = "远程解锁车门", time = formatRelativeTime(-42)),
                AuthorizationOperationRecordItem(action = "查看实时车况", time = formatRelativeTime(-130))
            )
        )
    }

    private fun resolveEntryLocked(vin: String): DemoVehicleEntry? {
        val norm = vin.trim().uppercase(Locale.US)
        return vehicles[norm] ?: vehicles[activeVin] ?: vehicles.values.firstOrNull()
    }

    private fun toVehicleJson(v: DemoVehicleEntry): JSONObject = JSONObject().apply {
        put("vin", v.vin)
        put("bleMac", v.bleMac)
        put("bleName", v.bleName)
        put("standardName", v.displayName)
        put("typeDesc", v.displayName)
        put("vehicleName", v.displayName)
        put("vehicleType", v.displayName)
        put("vehicleCode", v.modelKey)
        put("seriesCode", v.modelKey)
        put("licensenum", v.plateNo)
        put("plateNo", v.plateNo)
        put("selected", if (v.vin == defaultVin) "1" else "0")
        put("airType", "1")
        put("sunroofType", "1")
        put("tempLo", "16")
        put("tempHi", "32")
        put("engineType", v.engineType)
        put("openAir", if (v.openAir) "1" else "0")
        put("odometer", v.odometerKm.roundToInt().toString())
        put("remainedOil", v.fuelPercent?.roundToInt()?.toString().orEmpty())
        put("batteryLevel", v.batteryPercent?.roundToInt()?.toString().orEmpty())
        put("is_tracks_open", if (v.isTracksOpen) "1" else "0")
        put("colorName", v.colorName)
        put("iccid", v.iccid)
        put("sn", v.sn)
        put("engineNumber", v.engineNumber)
        put("hostName", v.hostName)
        put("purchaseTime", v.purchaseDate)
        put("autMethod", "0")
        put("simStatus", "1")
    }

    private fun toVehicleSummary(v: DemoVehicleEntry): VehicleSummary = VehicleSummary(
        vin = v.vin,
        bleMac = v.bleMac,
        bleName = v.bleName,
        seriesName = v.displayName,
        plateNo = v.plateNo,
        vehicleCode = v.modelKey,
        standardName = v.displayName,
        vehicleName = v.displayName,
        vehicleTypeId = v.modelKey,
        vehicleId = v.vin,
        airType = "1",
        sunroofType = "1",
        rawJson = toVehicleJson(v).toString(),
        selected = if (v.vin == defaultVin) "1" else "",
        vehicleType = v.displayName,
        tempLo = "16",
        tempHi = "32",
        odometer = v.odometerKm.roundToInt().toString(),
        batteryLevel = v.batteryPercent?.roundToInt()?.toString().orEmpty(),
        remainedOil = v.fuelPercent?.roundToInt()?.toString().orEmpty(),
        accountType = 0,
        isTracksOpen = v.isTracksOpen,
        colorName = v.colorName,
        seriesCode = v.modelKey,
        iccid = v.iccid,
        sn = v.sn,
        engineNumber = v.engineNumber,
        hostName = v.hostName,
        purchaseTime = v.purchaseDate,
        autMethod = "0",
        simStatus = "1",
        isBoundVehicle = true,
        engineType = v.engineType,
        openAir = if (v.openAir) "1" else "0"
    )

    private fun toTelemetryJson(v: DemoVehicleEntry): JSONObject = JSONObject().apply {
        put("vin", v.vin)
        put("doorLock", if (v.doorLocked) "0" else "1")
        put("doorLockState", if (v.doorLocked) "0" else "1")
        put("frontLeftDoor", "0")
        put("frontRightDoor", "0")
        put("backLeftDoor", "0")
        put("backRightDoor", "0")
        put("trunkDoor", if (v.trunkOpen) "1" else "0")
        put("liftgateState", if (v.trunkOpen) "1" else "0")
        put("hood", "0")
        put("frontLeftWindowState", v.windowsState)
        put("frontRightWindowState", v.windowsState)
        put("backLeftWindowState", v.windowsState)
        put("backRightWindowState", v.windowsState)
        put("sunroofState", v.sunroofState)
        put("engineState", if (v.engineRunning) "1" else "0")
        put("airState", if (v.acOn) "1" else "0")
        put("insideTemp", if (v.acOn) v.acTemp.toString() else "23.5")
        put("batteryVoltage", String.format(Locale.US, "%.1f", v.batteryVoltage))
        put("odometer", v.odometerKm.roundToInt().toString())
        v.fuelPercent?.let { put("oilSurplus", it.roundToInt().toString()) }
        v.batteryPercent?.let { put("electricitySurplus", it.roundToInt().toString()) }
        v.electricRangeKm?.let { put("electricityMileage", it.roundToInt().toString()) }
        put("mileageSurplus", v.remainingRangeKm.roundToInt().toString())
        put("lFrontTyre", v.tyresBar[0])
        put("rFrontTyre", v.tyresBar[1])
        put("lRearTyre", v.tyresBar[2])
        put("rRearTyre", v.tyresBar[3])
        put("lFrontTyreTemp", v.tyreTempsC[0])
        put("rFrontTyreTemp", v.tyreTempsC[1])
        put("lRearTyreTemp", v.tyreTempsC[2])
        put("rRearTyreTemp", v.tyreTempsC[3])
        put("tyreUnit", "bar")
        put("lat", String.format(Locale.US, "%.6f", v.lat))
        put("lon", String.format(Locale.US, "%.6f", v.lon))
        put("online", "1")
        put("licensePlate", v.plateNo)
        put("averageFuel", v.averageFuel)
        put("averageElectricity", v.averageElectricity)
        put("chargingFlag", "0")
        put("chargingGun", "0")
        put("chargeAppintStatus", if (v.chargingMasterEnabled) "1" else "0")
        put("dSeatHeatingState", if (v.driverSeatHeat) "1" else "0")
        put("pSeatHeatingState", if (v.passengerSeatHeat) "1" else "0")
        put("fWinHeatingState", if (v.frontDefrost) "1" else "0")
        put("rWinHeatingState", if (v.rearDefrost) "1" else "0")
        put("airPurification", if (v.airPurification) "1" else "0")
        put("tripSwitchStatus", if (v.isTracksOpen) "1" else "0")
        put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
    }

    private fun toTelemetry(v: DemoVehicleEntry): VehicleTelemetry {
        val json = toTelemetryJson(v)
        val cond = linkedMapOf<String, String>()
        json.keys().forEach { k -> cond[k] = json.optString(k) }
        return VehicleTelemetry(
            vin = v.vin,
            doorLockState = if (v.doorLocked) "0" else "1",
            doorLocked = v.doorLocked,
            doorStates = mapOf(
                "frontLeft" to "0",
                "frontRight" to "0",
                "backLeft" to "0",
                "backRight" to "0",
                "trunkDoor" to if (v.trunkOpen) "1" else "0",
                "hood" to "0"
            ),
            windowStates = mapOf(
                "frontLeft" to v.windowsState,
                "frontRight" to v.windowsState,
                "backLeft" to v.windowsState,
                "backRight" to v.windowsState,
                "sunroof" to v.sunroofState,
                "liftgate" to if (v.trunkOpen) "open" else "closed"
            ),
            trunkState = if (v.trunkOpen) "1" else "0",
            trunkOpen = v.trunkOpen,
            tailgateState = if (v.trunkOpen) "1" else "0",
            skylightState = v.sunroofState,
            engineState = if (v.engineRunning) "1" else "0",
            engineRunning = v.engineRunning,
            acState = if (v.acOn) "1" else "0",
            acOn = v.acOn,
            batteryVoltage = v.batteryVoltage,
            fuelPercent = v.fuelPercent,
            remainingRangeKm = v.remainingRangeKm,
            odometerKm = v.odometerKm,
            latitude = v.lat,
            longitude = v.lon,
            rawJson = json.toString(),
            conditionFields = cond,
            doorLock = if (v.doorLocked) "0" else "1",
            frontLeftDoor = "0",
            frontRightDoor = "0",
            backLeftDoor = "0",
            backRightDoor = "0",
            trunkDoor = if (v.trunkOpen) "1" else "0",
            hood = "0",
            frontLeftWindowState = v.windowsState,
            frontRightWindowState = v.windowsState,
            backLeftWindowState = v.windowsState,
            backRightWindowState = v.windowsState,
            sunroofState = v.sunroofState,
            liftgateState = if (v.trunkOpen) "1" else "0",
            airState = if (v.acOn) "1" else "0",
            odometer = v.odometerKm.roundToInt().toString(),
            oilSurplus = v.fuelPercent?.roundToInt()?.toString().orEmpty(),
            electricitySurplus = v.batteryPercent?.roundToInt()?.toString().orEmpty(),
            mileageSurplus = v.remainingRangeKm.roundToInt().toString(),
            lFrontTyre = v.tyresBar[0],
            rFrontTyre = v.tyresBar[1],
            lRearTyre = v.tyresBar[2],
            rRearTyre = v.tyresBar[3],
            lon = String.format(Locale.US, "%.6f", v.lon),
            lat = String.format(Locale.US, "%.6f", v.lat),
            online = "1",
            time = json.optString("time"),
            licensePlate = v.plateNo,
            averageFuel = v.averageFuel,
            averageElectricity = v.averageElectricity,
            electricityMileage = v.electricRangeKm?.roundToInt()?.toString().orEmpty(),
            chargingFlag = "0",
            chargingGun = "0",
            chargeAppintStatus = if (v.chargingMasterEnabled) "1" else "0",
            dSeatHeatingState = if (v.driverSeatHeat) "1" else "0",
            pSeatHeatingState = if (v.passengerSeatHeat) "1" else "0",
            fWinHeatingState = if (v.frontDefrost) "1" else "0",
            rWinHeatingState = if (v.rearDefrost) "1" else "0",
            airPurification = if (v.airPurification) "1" else "0",
            lFrontTyreTemp = v.tyreTempsC[0],
            rFrontTyreTemp = v.tyreTempsC[1],
            lRearTyreTemp = v.tyreTempsC[2],
            rRearTyreTemp = v.tyreTempsC[3],
            tyreUnit = "bar",
            tripSwitchStatus = if (v.isTracksOpen) "1" else "0"
        )
    }

    private fun buildDemoTripJson(
        entry: DemoVehicleEntry?,
        dayOffset: Int,
        startHm: String,
        endHm: String,
        mileageKm: String,
        duration: String,
        startAddr: String,
        endAddr: String
    ): JSONObject {
        val dateStr = formatDateOffset(dayOffset)
        val lat0 = entry?.lat ?: 31.3526
        val lon0 = entry?.lon ?: 118.4329
        val pts = JSONArray()
        for (step in 0..8) {
            val f = step / 8.0
            pts.put(
                JSONObject().apply {
                    put("lat", String.format(Locale.US, "%.6f", lat0 - 0.018 * f))
                    put("lon", String.format(Locale.US, "%.6f", lon0 - 0.045 * f))
                }
            )
        }
        return JSONObject().apply {
            put("curDate", dateStr)
            put("startTime", "$dateStr $startHm:00")
            put("endTime", "$dateStr $endHm:00")
            put("mileage", mileageKm)
            put("distance", duration)
            put("start_Address", startAddr)
            put("end_Address", endAddr)
            put("avgSpeed", "38")
            put("maxSpeed", "66")
            put("fuelConsumption", entry?.averageFuel?.ifBlank { "5.8" } ?: "5.8")
            put("points", pts)
        }
    }

    private fun okEnvelope(data: JSONObject, message: String = "成功"): JSONObject = JSONObject().apply {
        put("resultCode", "0000")
        put("resultMsg", message)
        put("resultMessage", message)
        put("message", message)
        put("globalId", DEMO_ACCESS_TOKEN)
        put("data", data)
    }

    private data class Septuple<A, B, C, D, E, F, G>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
        val e: E,
        val f: F,
        val g: G
    )

    companion object {
        const val DEMO_ACCOUNT = "demo"
        const val DEMO_PASSWORD = "demo"
        const val DEMO_ACCESS_TOKEN = "demo_session_token_qiri_plus"
        const val DEMO_SDK_TOKEN = "demo_sdk_token_qiri_plus"
        const val DEMO_USER_ID = "demo_user_qiri_plus"
        const val DEMO_MOBILE = "13800138078"
        const val DEMO_PIN = "123456"
        private const val DEMO_ROOT_KEY_HEX = "00112233445566778899AABBCCDDEEFF"

        fun isDemoCredentials(account: String, secret: String): Boolean =
            account.trim().equals(DEMO_ACCOUNT, ignoreCase = true) &&
                secret.trim().equals(DEMO_PASSWORD, ignoreCase = true)

        fun vinForIndex(index: Int): String =
            "LVVDBDEMO${"%08d".format(Locale.US, index + 1)}"

        private fun formatDateOffset(dayOffset: Int): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }

        private fun formatRelativeTime(minutesOffset: Int): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MINUTE, minutesOffset)
            return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(cal.time)
        }
    }
}
