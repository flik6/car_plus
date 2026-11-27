package com.chery.carplus.cloud

import android.util.Base64
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.profile.VehicleProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class CheryLoginResult(
    val accessToken: String,
    val refreshToken: String,
    val openId: String,
    val accountId: String,
    val expireAt: Long,
    val refreshExpireAt: Long,
    val rawJson: String,
    val sdkToken: String = "",
    val accountType: String = "",
    val telephone: String = "",
    val message: String = "",
    val userInfo: CheryUserInfo? = null,
    val vehicles: List<VehicleSummary> = emptyList()
)

data class CheryUserInfo(
    val userId: String = "",
    val telephone: String = "",
    val account: String = "",
    val nickname: String = "",
    val rawJson: String = "",
    val isTracksOpen: Boolean? = null
)

data class SdkTokenInfo(
    val vin: String,
    val userId: String,
    val sdkToken: String,
    val sdkTokenExpire: String,
    val sdkRefreshToken: String,
    val sdkRefreshTokenExpire: String,
    val rawJson: String
)

enum class VehiclePowertrain(val code: String, val label: String) {
    ICE("3", "燃油车"),
    PHEV("2", "插电混动"),
    EV("1", "纯电动");

    val hasElectricDrive: Boolean get() = this == EV || this == PHEV
    val hasFuelEngine: Boolean get() = this == ICE || this == PHEV

    companion object {
        fun fromEngineTypeCode(raw: String): VehiclePowertrain? = when (raw.trim()) {
            "1" -> EV
            "2" -> PHEV
            "3" -> ICE
            else -> null
        }

        fun resolve(
            engineType: String = "",
            modelName: String = "",
            telemetry: VehicleTelemetry? = null
        ): VehiclePowertrain {
            fromEngineTypeCode(engineType)?.let { return it }
            val hasElec = telemetry != null && (
                telemetry.batteryPercent != null ||
                    telemetry.electricRangeKm != null ||
                    telemetry.chargingFlag == "1" ||
                    telemetry.chargingGun == "1"
                )
            val hasFuel = telemetry?.fuelPercent != null
            if (hasElec && hasFuel) return PHEV
            if (hasElec && !hasFuel) return EV

            val upper = modelName.trim().uppercase(Locale.US)
            if (upper.isNotBlank()) {
                if (upper.contains("PHEV") || upper.contains("C-DM") || upper.contains("CDM") ||
                    upper.contains("混动") || upper.contains("插混") || upper.contains("增程")
                ) return PHEV
                if (upper.contains("纯电") || upper.contains("小蚂蚁") || upper.contains("冰淇淋") ||
                    upper.contains("舒享家") || Regex("(^|[^A-Z])(EV|EQ\\d?)([^A-Z]|$)").containsMatchIn(upper)
                ) return EV
            }
            return ICE
        }
    }
}

data class VehicleSummary(
    val vin: String,
    val bleMac: String,
    val bleName: String,
    val seriesName: String,
    val plateNo: String,
    val vehicleCode: String,
    val standardName: String,
    val vehicleName: String,
    val vehicleTypeId: String,
    val vehicleId: String,
    val airType: String,
    val sunroofType: String,
    val rawJson: String,
    val bleNamePrefix: String = "CHR",
    val bleNameType: String = "",
    val isBluetoothAuth: String = "",
    val selected: String = "",
    val vehicleType: String = "",
    val tempLo: String = "",
    val tempHi: String = "",
    val openGesturePass: String = "",
    val passType: String = "",
    val odometer: String = "",
    val batteryLevel: String = "",
    val remainedOil: String = "",
    val accountType: Int = 0,
    val isTracksOpen: Boolean? = null,
    val colorName: String = "",
    val seriesCode: String = "",
    val isBluetoothSupported: Boolean = true,
    val iccid: String = "",
    val sn: String = "",
    val engineNumber: String = "",
    val hostName: String = "",
    val purchaseTime: String = "",
    val autMethod: String = "0",
    val simStatus: String = "1",
    val isBoundVehicle: Boolean = true,
    val authDateListSummary: String = "",
    val engineType: String = "",
    val openAir: String = ""
) {
    val isSelected: Boolean get() = selected == "1"
    val modelName: String get() = firstNonBlank(vehicleName, seriesName, standardName)
    val powertrain: VehiclePowertrain get() = VehiclePowertrain.resolve(engineType, modelName, null)
}

/**
 * Normalized telemetry while retaining the complete zyhl DataResp911 field set
 * in [conditionFields]. The named raw fields below mirror the protocol payload;
 * the normalized fields are consumed by the existing CarPlus UI.
 */
data class VehicleTelemetry(
    val vin: String,
    val doorLockState: String,
    val doorLocked: Boolean?,
    val doorStates: Map<String, String>,
    val windowStates: Map<String, String>,
    val trunkState: String,
    val trunkOpen: Boolean?,
    val tailgateState: String,
    val skylightState: String,
    val engineState: String,
    val engineRunning: Boolean?,
    val acState: String,
    val acOn: Boolean?,
    val batteryVoltage: Double?,
    val fuelPercent: Double?,
    val remainingRangeKm: Double?,
    val odometerKm: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val rawJson: String,
    val conditionFields: Map<String, String> = emptyMap(),
    val doorLock: String = "",
    val frontLeftDoor: String = "",
    val frontRightDoor: String = "",
    val backLeftDoor: String = "",
    val backRightDoor: String = "",
    val trunkDoor: String = "",
    val trunkLock: String = "",
    val hood: String = "",
    val frontLeftWindowState: String = "",
    val frontRightWindowState: String = "",
    val backLeftWindowState: String = "",
    val backRightWindowState: String = "",
    val sunroofState: String = "",
    val liftgateState: String = "",
    val airState: String = "",
    val odometer: String = "",
    val oilSurplus: String = "",
    val electricitySurplus: String = "",
    val mileageSurplus: String = "",
    val lFrontTyre: String = "",
    val rFrontTyre: String = "",
    val lRearTyre: String = "",
    val rRearTyre: String = "",
    val lon: String = "",
    val lat: String = "",
    val online: String = "",
    val time: String = "",
    val seq: String = "",
    val result: String = "",
    val msg: String = "",
    val licensePlate: String = "",
    val averageFuel: String = "",
    val averageElectricity: String = "",
    val electricityMileage: String = "",
    val chargingFlag: String = "",
    val chargingGun: String = "",
    val remainingCharge: String = "",
    val chargeAppintStatus: String = "",
    val dSeatHeatingState: String = "",
    val pSeatHeatingState: String = "",
    val dSeatVentilateState: String = "",
    val pSeatVentilateState: String = "",
    val fWinHeatingState: String = "",
    val rWinHeatingState: String = "",
    val airPurification: String = "",
    val lFrontTyreTemp: String = "",
    val rFrontTyreTemp: String = "",
    val lRearTyreTemp: String = "",
    val rRearTyreTemp: String = "",
    val tyreUnit: String = "",
    val tripSwitchStatus: String = ""
) {
    val isCharging: Boolean get() = chargingFlag.trim() == "1"
    val isChargingGunConnected: Boolean get() = chargingGun.trim() == "1"
    val batteryPercent: Double?
        get() = electricitySurplus.trim()
            .takeIf { !it.equals("FFFF", ignoreCase = true) }
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..100.0 }
    val electricRangeKm: Double?
        get() = electricityMileage.trim()
            .takeIf { !it.equals("FFFF", ignoreCase = true) }
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.1..5_000.0 }
}

data class VehicleTrafficInfo(
    val usedTraffic: String = "",
    val totalTraffic: String = "",
    val unlimited: Boolean = false,
    val packageState: String = "",
    val dueDate: String = "",
    val rawJson: String = ""
)

data class ChargingScheduleItem(
    val chargeId: String = "",
    val startTime: String = "",
    val durationHours: Int = 1,
    val days: List<Int> = emptyList(),
    val enabled: Boolean = false,
    val rawJson: String = ""
)

data class ChargingScheduleSummary(
    val masterEnabled: Boolean = false,
    val items: List<ChargingScheduleItem> = emptyList(),
    val rawJson: String = ""
)

data class TravelScheduleItem(
    val startTime: String = "",
    val enabled: Boolean = false,
    val rawJson: String = ""
)

data class TravelScheduleSummary(
    val masterEnabled: Boolean = false,
    val items: List<TravelScheduleItem> = emptyList(),
    val rawJson: String = ""
)

data class PoiSearchResult(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double
)

enum class RemoteCommand {
    DOOR_UNLOCK,
    DOOR_LOCK,
    ENGINE_START,
    ENGINE_STOP,
    AC_ON,
    AC_OFF,
    WINDOW_OPEN,
    WINDOW_VENTILATE,
    WINDOW_CLOSE,
    TAILGATE_OPEN,
    TAILGATE_CLOSE,
    TRUNK_UNLOCK,
    FIND_CAR,
    SKYLIGHT_OPEN,
    SKYLIGHT_TILT,
    SKYLIGHT_CLOSE
}

data class RemoteCommandResult(
    val success: Boolean,
    val command: RemoteCommand,
    val requestId: String,
    val resultCode: String,
    val resultMessage: String,
    val seq: String,
    val responseSign: String,
    val rawResponse: String,
    val error: String? = null,
    val isPinError: Boolean = false,
    val pinVerified: Boolean = false
)

data class DeviceBindingSmsResult(
    val success: Boolean,
    val resultCode: String,
    val message: String,
    val telephone: String = "",
    val rawJson: String = ""
) {
    val sent: Boolean get() = success
    val confirmed: Boolean get() = success
    val resultMsg: String get() = message
}

data class GpsTrackPoint(val lat: Double, val lon: Double)

data class TripRecordItem(
    val startTime: String,
    val endTime: String,
    val mileageKm: String,
    val durationOrDistance: String,
    val startAddress: String,
    val endAddress: String,
    val avgSpeed: String = "",
    val maxSpeed: String = "",
    val fuelConsumption: String = "",
    val curDate: String = "",
    val points: List<GpsTrackPoint> = emptyList(),
    val rawJson: String = ""
)

data class TripDayGroup(
    val dateKey: String,
    val dateLabel: String,
    val trips: List<TripRecordItem>
)

data class TripHistoryResult(
    val dayGroups: List<TripDayGroup>,
    val summary140: DrivingSummaryInfo? = null,
    val statusCode: String = "",
    val statusMessage: String = ""
)

data class DrivingSummaryInfo(
    val lastConsumeTime: String,
    val totalConsumeTime: String,
    val lastDistanceKm: String,
    val totalDistanceKm: String,
    val lastTrip: TripRecordItem? = null,
    val rawJson: String = ""
)

data class ZyhlMessageItem(
    val functionId: String,
    val type: String,
    val typeDesc: String,
    val title: String,
    val description: String,
    val content: String,
    val messageTime: String,
    val url: String = "",
    val img: String = "",
    val rawJson: String = ""
)

data class AntiTheftStatus(
    val theftRemindEnabled: Boolean,
    val speedLimitEnabled: Boolean,
    val speedLimitKmh: String,
    val rawJson: String = ""
)

data class GeofenceItem(
    val keyId: String,
    val name: String,
    val shape: String,
    val status: String,
    val type: String,
    val lat: String = "",
    val lon: String = "",
    val radiusMeters: String = "",
    val rawJson: String = ""
)

data class BindVehicleResult(
    val success: Boolean,
    val resultCode: String,
    val message: String,
    val simStatus: String = ""
)

data class VehicleUpgradeInfo(
    val upgradeTaskId: String,
    val currentVersion: String,
    val state: String,
    val releaseNote: String,
    val descriptionText: String,
    val upgradeTime: String,
    val upgradeStartTime: String,
    val percentage: Int = 0,
    val surplusTime: String = "",
    val hasUpgrade: Boolean = false,
    val rawJson: String = ""
)

data class VehicleAuthorizationItem(
    val keyId: String,
    val authType: String,
    val receiverName: String,
    val receiverMobile: String,
    val startTime: String,
    val endTime: String,
    val status: String,
    val levelFlag: String = "",
    val authority: String = "",
    val authorityDesc: String = "",
    val rawJson: String = "",
    val vin: String = "",
    val authorizedUserId: String = "",
    val authorizedName: String = receiverName,
    val authorizedMobile: String = receiverMobile
) {
    val receiverAccountId: String get() = authorizedUserId
    val receiverAccountName: String get() = authorizedName
    val enabled: Boolean get() = status == "0"
    val permissionLabel: String
        get() = if (levelFlag == "1" || authority == "1") "查车 + 控车" else "查车"
}

data class AuthorizationOperationRecordItem(
    val action: String,
    val time: String,
    val rawJson: String = ""
)

class CheryDeviceBindingRequiredException(
    message: String,
    val telephone: String = "",
    val resultCode: String = RESULT_DEVICE_BIND_REQUIRED,
    cause: Throwable? = null,
    val mobileNo: String = telephone,
    val loginMethod: String = "",
    val deviceId: String = "",
    val smsSent: Boolean = false,
    val serverMessage: String = message
) : Exception(message, cause) {
    companion object {
        const val RESULT_DEVICE_BIND_REQUIRED = "0843"
    }
}

class CherySessionExpiredException(
    val resultCode: String = "0406",
    val serverMessage: String = "登录已失效，请重新登录"
) : IOException(serverMessage.ifBlank { "登录已失效，请重新登录" })

/** CarPlus transport for the zyhl `/cheryT15/app/command.do` protocol. */
class CheryHttpClient(
    private val baseUrl: String = DEFAULT_PRIMARY_BASE_URL,
    private val fallbackBaseUrl: String = DEFAULT_SECONDARY_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 30_000,
    private val defaultDeviceId: String = ""
) {
    @Volatile
    var onSessionExpired: ((String) -> Unit)? = null

    @Volatile
    private var sessionGlobalId: String = ""

    @Volatile
    private var sessionSdkToken: String = ""

    @Volatile
    private var sessionRefreshToken: String = ""

    @Volatile
    private var sessionAccessToken: String = ""

    @Volatile
    private var sessionUserId: String = ""

    @Volatile
    private var sessionMobileNo: String = ""

    @Volatile
    private var sessionBackend: CloudBackend = CloudBackend.ZYHL

    /**
     * zyhl com.lib.common.tools.l.SHA256:
     * Base64(SHA-256(plain).hexLower UTF-8 bytes), without gratuitous re-hashing.
     */
    fun hashPasswordOrPin(plain: String): String {
        if (isAlreadyZyhlHash(plain)) return plain
        return sha256HexToBase64(plain)
    }

    private fun sha256HexToBase64(plain: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(plain.toByteArray(Charsets.UTF_8))
        val hexLower = digest.joinToString("") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xFF)
        }
        return Base64.encodeToString(hexLower.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun newRequestId(): String = UUID.randomUUID().toString()
        .replace("-", "")
        .lowercase(Locale.US)

    /** Clears the in-memory globalId/session tokens without touching profiles. */
    fun clearSession() {
        sessionGlobalId = ""
        sessionSdkToken = ""
        sessionRefreshToken = ""
        sessionAccessToken = ""
        sessionUserId = ""
        sessionMobileNo = ""
        sessionBackend = CloudBackend.ZYHL
        clearAllPinSessions()
    }

    fun isSessionExpired(resultCode: String, message: String = ""): Boolean {
        val code = resultCode.trim()
        if (code == RESULT_SUCCESS) return false
        return code == RESULT_SESSION_EXPIRED ||
            message.contains("登录失效") ||
            message.contains("重新登录") ||
            message.contains("会话过期") ||
            message.contains("token失效", ignoreCase = true) ||
            message.contains("未登录")
    }

    /** Reports the single active protocol backend for legacy callers. */
    fun currentBackend(): String = sessionBackend.name

    fun buildRequestEnvelope(
        serviceType: String,
        data: JSONObject = JSONObject(),
        globalId: String = ""
    ): JSONObject = JSONObject().apply {
        put("requestId", newRequestId())
        put("version", PROTOCOL_VERSION)
        put("globalId", globalId)
        put("serviceType", serviceType)
        put("businessId", BUSINESS_ID)
        put("data", data)
    }

    /** Sends exactly one zyhl 920 login SMS. */
    fun sendSmsCode(
        mobileNo: String,
        ticket: String = "",
        randstr: String = "",
        type: String = SMS_TYPE_LOGIN
    ): Boolean {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        return sendSmsCodeZyhl(mobileNo, type)
    }

    private fun sendSmsCodeZyhl(
        mobileNo: String,
        type: String,
        appName: String = APP_NAME
    ): Boolean {
        val smsType = type.ifBlank { SMS_TYPE_LOGIN }
        val response = postEnvelope(
            serviceType = SERVICE_SEND_SMS,
            data = JSONObject().apply {
                put("appName", appName)
                put("telephone", mobileNo.trim())
                put("type", smsType)
            },
            globalId = ""
        )
        return isSuccessCode(response.resultCode)
    }

    /** Validates an SMS code through zyhl service 921. */
    fun verifySmsCode(
        mobileNo: String,
        smsCode: String,
        type: String = SMS_TYPE_LOGIN
    ): Boolean {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        require(smsCode.isNotBlank()) { "smsCode is required" }
        return verifySmsCodeZyhl(mobileNo, smsCode, type)
    }

    private fun verifySmsCodeZyhl(
        mobileNo: String,
        smsCode: String,
        type: String
    ): Boolean = isSuccessCode(verifySmsCodeZyhlResponse(mobileNo, smsCode, type).resultCode)

    private fun verifySmsCodeZyhlResponse(
        mobileNo: String,
        smsCode: String,
        type: String
    ): ZyhlResponse = postEnvelope(
        serviceType = SERVICE_VERIFY_SMS,
        data = JSONObject().apply {
            put("telephone", mobileNo.trim())
            put("type", type.ifBlank { SMS_TYPE_LOGIN })
            put("validateCode", smsCode.trim())
        },
        globalId = ""
    )

    /** 107 request the device-binding SMS for password (1) or SMS (2) login. */
    fun requestDeviceBindSms(loginName: String, loginMethod: String = LOGIN_METHOD_PASSWORD): Boolean =
        requestDeviceBindSmsDetailed(loginName, loginMethod).success

    fun requestDeviceBindSmsDetailed(
        loginName: String,
        loginMethod: String = LOGIN_METHOD_PASSWORD
    ): DeviceBindingSmsResult {
        require(loginName.isNotBlank()) { "loginName is required" }
        val response = postEnvelope(
            serviceType = SERVICE_REQUEST_BIND_SMS,
            data = JSONObject().apply {
                put("loginName", loginName.trim())
                put("loginMethod", loginMethod.ifBlank { LOGIN_METHOD_PASSWORD })
                put("appName", APP_NAME)
            },
            globalId = ""
        )
        val data = response.dataObject()
        return DeviceBindingSmsResult(
            success = isSuccessCode(response.resultCode),
            resultCode = response.resultCode,
            message = response.message,
            telephone = data.optString("telephone"),
            rawJson = response.rawBody
        )
    }

    /** 108 confirm the device-binding SMS. */
    fun confirmDeviceBindSms(
        loginName: String,
        validateCode: String,
        loginMethod: String = LOGIN_METHOD_PASSWORD,
        deviceId: String = ""
    ): Boolean = confirmDeviceBindSmsDetailed(loginName, validateCode, loginMethod, deviceId).success

    fun confirmDeviceBindSmsDetailed(
        loginName: String,
        validateCode: String,
        loginMethod: String = LOGIN_METHOD_PASSWORD,
        deviceId: String = ""
    ): DeviceBindingSmsResult {
        require(loginName.isNotBlank()) { "loginName is required" }
        require(validateCode.isNotBlank()) { "validateCode is required" }
        val resolvedDeviceId = resolveDeviceId(deviceId)
        val response = postEnvelope(
            serviceType = SERVICE_CONFIRM_BIND_SMS,
            data = JSONObject().apply {
                put("loginName", loginName.trim())
                put("loginMethod", loginMethod.ifBlank { LOGIN_METHOD_PASSWORD })
                put("validateCode", validateCode.trim())
                put("appName", APP_NAME)
                put("deviceId", resolvedDeviceId)
            },
            globalId = ""
        )
        return DeviceBindingSmsResult(
            success = isSuccessCode(response.resultCode),
            resultCode = response.resultCode,
            message = response.message,
            rawJson = response.rawBody
        )
    }

    /** Password login through zyhl service 101. */
    fun loginWithPassword(
        mobileNo: String,
        password: String,
        deviceId: String = ""
    ): CheryLoginResult {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        require(password.isNotEmpty()) { "password is required" }
        return loginWithPasswordZyhl(
            mobileNo.trim(),
            password,
            resolveDeviceId(deviceId)
        )
    }

    /**
     * zyhl 101 compatibility path. A successful login is followed by 112 user
     * info and 900 bound-vehicle discovery.
     */
    private fun loginWithPasswordZyhl(
        mobileNo: String,
        password: String,
        deviceId: String
    ): CheryLoginResult {
        val resolvedDeviceId = resolveDeviceId(deviceId)
        val response = postEnvelope(
            serviceType = SERVICE_LOGIN_PASSWORD,
            data = JSONObject().apply {
                put("userAccount", mobileNo.trim())
                put("userPasswd", hashPasswordOrPin(password))
                put("appName", APP_NAME)
                put("appType", APP_TYPE_ANDROID)
                put("deviceId", resolvedDeviceId)
                put("token", resolvedDeviceId)
            },
            globalId = ""
        )

        if (response.resultCode == RESULT_DEVICE_BIND_REQUIRED) {
            val bind = runCatching {
                requestDeviceBindSmsDetailed(mobileNo, LOGIN_METHOD_PASSWORD)
            }.getOrNull()
            val target = bind?.telephone.orEmpty().ifBlank { mobileNo.trim() }
            throw CheryDeviceBindingRequiredException(
                message = if (bind?.success == true) {
                    "该设备尚未完成智云互联设备绑定；设备绑定短信（107）已发送至 $target。" +
                        "请完成设备绑定验证后继续登录。"
                } else {
                    "该设备需要完成智云互联设备绑定，但 107 绑定短信请求未成功：" +
                        bind?.message.orEmpty().ifBlank { response.message }.ifBlank { "未知错误" }
                },
                telephone = target,
                resultCode = response.resultCode,
                mobileNo = mobileNo.trim(),
                loginMethod = LOGIN_METHOD_PASSWORD,
                deviceId = resolvedDeviceId,
                smsSent = bind?.sent == true,
                serverMessage = firstNonBlank(bind?.message, response.message)
            )
        }

        requireSuccess(response, SERVICE_LOGIN_PASSWORD)
        sessionBackend = CloudBackend.ZYHL
        return finishLogin(response, mobileNo, resolvedDeviceId)
    }

    /** SMS login through zyhl services 921 and 105. */
    fun loginWithSms(
        mobileNo: String,
        smsCode: String,
        deviceId: String = "",
        verifySmsFirst: Boolean = false,
        password: String = ""
    ): CheryLoginResult {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        require(smsCode.isNotBlank()) { "smsCode is required" }
        return loginWithSmsZyhl(
            mobileNo = mobileNo.trim(),
            smsCode = smsCode.trim(),
            deviceId = resolveDeviceId(deviceId),
            verifySmsFirst = true,
            password = password
        )
    }

    /** zyhl 105 login with the official 921 -> 105 -> (0843 -> 107) sequence. */
    private fun loginWithSmsZyhl(
        mobileNo: String,
        smsCode: String,
        deviceId: String,
        verifySmsFirst: Boolean,
        @Suppress("UNUSED_PARAMETER")
        password: String
    ): CheryLoginResult {
        val resolvedDeviceId = resolveDeviceId(deviceId)

        if (verifySmsFirst) {
            val verifyResp = verifySmsCodeZyhlResponse(mobileNo, smsCode, type = SMS_TYPE_LOGIN)
            if (!isSuccessCode(verifyResp.resultCode)) {
                error(
                    verifyResp.message.ifBlank {
                        "短信验证码校验失败[${verifyResp.resultCode.ifBlank { "921" }}]"
                    }
                )
            }
        }

        val response = loginSmsRequest(mobileNo, smsCode, resolvedDeviceId)
        if (response.resultCode == RESULT_DEVICE_BIND_REQUIRED) {
            val bindSms = runCatching {
                requestDeviceBindSmsDetailed(mobileNo, LOGIN_METHOD_SMS)
            }.getOrNull()
            val target = bindSms?.telephone.orEmpty().ifBlank { mobileNo.trim() }
            val smsSent = bindSms?.sent == true
            throw CheryDeviceBindingRequiredException(
                message = if (smsSent) {
                    "检测到新设备登录，已向 $target 发送换机绑定验证码，请输入刚刚收到的短信验证码。"
                } else {
                    "检测到新设备登录，但换机绑定短信发送未成功：" +
                        bindSms?.message.orEmpty().ifBlank { response.message }.ifBlank { "请稍后点击重新发送" }
                },
                telephone = target,
                resultCode = response.resultCode,
                mobileNo = mobileNo.trim(),
                loginMethod = LOGIN_METHOD_SMS,
                deviceId = resolvedDeviceId,
                smsSent = smsSent,
                serverMessage = firstNonBlank(bindSms?.message, response.message)
            )
        }

        requireSuccess(response, SERVICE_LOGIN_SMS)
        sessionBackend = CloudBackend.ZYHL
        return finishLogin(response, mobileNo, resolvedDeviceId)
    }

    private fun loginSmsRequest(
        mobileNo: String,
        smsCode: String,
        resolvedDeviceId: String
    ): ZyhlResponse = postEnvelope(
        serviceType = SERVICE_LOGIN_SMS,
        data = JSONObject().apply {
            put("telephone", mobileNo.trim())
            put("validateCode", smsCode.trim())
            put("appName", APP_NAME)
            put("appType", APP_TYPE_ANDROID)
            put("deviceId", resolvedDeviceId)
            put("token", resolvedDeviceId)
        },
        globalId = ""
    )

    /** Fetches user identity through zyhl service 112. */
    fun fetchUserInfo(accessToken: String = ""): CheryUserInfo {
        return fetchUserInfoZyhl(resolveGlobalId(accessToken))
    }

    private fun fetchUserInfoZyhl(accessToken: String): CheryUserInfo {
        val authToken = resolveAuthToken(accessToken)
        require(authToken.isNotBlank()) { "accessToken/globalId is required" }
        val response = postEnvelope(SERVICE_USER_INFO, JSONObject(), authToken)
        requireSuccess(response, SERVICE_USER_INFO)
        val data = response.dataObject()
        return CheryUserInfo(
            userId = data.optString("userId"),
            telephone = data.optString("telephone"),
            account = data.optString("account"),
            nickname = data.optString("nickname").ifBlank { data.optString("nickName") },
            rawJson = response.rawBody,
            isTracksOpen = parseFlagBoolean(firstNonBlank(jsonText(data, "isTracksOpen"), jsonText(data, "is_tracks_open")))
        )
    }

    /** Fetches the bound vehicle list through zyhl service 900. */
    fun fetchVehicleList(accessToken: String = ""): List<VehicleSummary> {
        return fetchVehicleListZyhl(resolveGlobalId(accessToken))
    }

    private fun fetchVehicleListZyhl(accessToken: String): List<VehicleSummary> {
        val authToken = resolveAuthToken(accessToken)
        require(authToken.isNotBlank()) { "accessToken/globalId is required" }
        val response = postEnvelope(SERVICE_VEHICLE_LIST, JSONObject(), authToken)
        if (response.resultCode == RESULT_EMPTY_DATA) return emptyList()
        requireSuccess(response, SERVICE_VEHICLE_LIST)

        val data = response.dataObject()
        val array = data.optJSONArray("vehicleList")
            ?: response.envelope.optJSONArray("data")
            ?: JSONArray()
        var vehicles = buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { item ->
                    add(parseVehicleSummary(item))
                }
            }
        }.sortedByDescending { it.selected }

        if (vehicles.isEmpty()) return vehicles

        if (vehicles.any { it.plateNo.isBlank() }) {
            val vehicleInfoResponse = runCatching {
                postEnvelope(SERVICE_VEHICLE_INFO, JSONObject(), authToken)
            }.getOrNull()
            val vehicleInfoData = vehicleInfoResponse?.dataObject()
            val vehicleInfoArray = vehicleInfoData?.optJSONArray("vehicleList")
                ?: vehicleInfoResponse?.envelope?.optJSONArray("vehicleList")
                ?: (vehicleInfoResponse?.envelope?.opt("data") as? JSONArray)
            if (vehicleInfoArray != null) {
                val platesByVin = linkedMapOf<String, String>()
                for (index in 0 until vehicleInfoArray.length()) {
                    val item = vehicleInfoArray.optJSONObject(index) ?: continue
                    val vin = firstNonBlank(
                        item.optString("vin"),
                        item.optJSONObject("vehicleInfo")?.optString("vin").orEmpty(),
                        item.optJSONObject("carInfo")?.optString("vin").orEmpty()
                    ).uppercase(Locale.US)
                    val plateNo = vehiclePlateNo(item)
                    if (vin.isNotBlank() && plateNo.isNotBlank()) platesByVin[vin] = plateNo
                }
                if (platesByVin.isNotEmpty()) {
                    vehicles = vehicles.map { vehicle ->
                        if (vehicle.plateNo.isNotBlank()) {
                            vehicle
                        } else {
                            vehicle.copy(
                                plateNo = platesByVin[vehicle.vin.uppercase(Locale.US)].orEmpty()
                            )
                        }
                    }
                }
            }
        }

        val primaryIndex = vehicles.indexOfFirst { it.selected == "1" }.takeIf { it >= 0 } ?: 0
        val primary = vehicles[primaryIndex]
        if (primary.plateNo.isBlank() && primary.vin.isNotBlank()) {
            val telemetryPlate = runCatching {
                fetchVehicleStatusZyhl(primary.vin, authToken).licensePlate
            }.getOrDefault("")
            if (telemetryPlate.isNotBlank()) {
                vehicles = vehicles.toMutableList().also { list ->
                    list[primaryIndex] = primary.copy(plateNo = telemetryPlate)
                }
            }
        }
        return vehicles
    }

    /** Fetches realtime telemetry through zyhl service 911. */
    fun fetchVehicleStatus(
        vin: String,
        accessToken: String,
        sdkToken: String,
        userId: String
    ): VehicleTelemetry {
        require(vin.isNotBlank()) { "vin is required" }
        return fetchVehicleStatusZyhl(vin, resolveGlobalId(accessToken))
    }

    /**
     * zyhl 911 compatibility path. The session is authorised by globalId; the
     * legacy sdkToken and userId parameters remain for UI source compatibility.
     */
    private fun fetchVehicleStatusZyhl(vin: String, accessToken: String): VehicleTelemetry {
        val authToken = resolveAuthToken(accessToken)
        require(authToken.isNotBlank()) { "accessToken/globalId is required" }
        val response = postEnvelope(
            serviceType = SERVICE_VEHICLE_STATUS,
            data = JSONObject().apply { put("vin", vin.trim().uppercase(Locale.US)) },
            globalId = authToken
        )
        if (response.resultCode == RESULT_EMPTY_DATA) {
            return emptyTelemetry(vin.trim().uppercase(Locale.US), response.rawBody)
        }
        requireSuccess(response, SERVICE_VEHICLE_STATUS)
        return parseTelemetry(vin.trim().uppercase(Locale.US), response.dataObject(), response.rawBody)
    }

    /** Verifies the vehicle control PIN through zyhl service 125. */
    fun verifyVehiclePin(
        vin: String,
        pin: String,
        accessToken: String = ""
    ): Boolean {
        require(vin.isNotBlank()) { "vin is required" }
        require(pin.isNotEmpty()) { "pin is required" }
        return verifyVehiclePinZyhl(vin, pin, resolveGlobalId(accessToken))
    }

    private fun verifyVehiclePinZyhl(vin: String, pin: String, accessToken: String): Boolean {
        val authToken = resolveAuthToken(accessToken)
        require(authToken.isNotBlank()) { "accessToken/globalId is required" }
        val pinHash = hashPasswordOrPin(pin)
        val response = postPinSessionVerification(vin, pinHash, authToken)
        val success = response.resultCode == RESULT_SUCCESS
        if (success) {
            markPinSessionWarm(authToken, vin, pinHash)
        } else if (!response.resultCode.startsWith("05") && !response.resultCode.startsWith("5")) {
            invalidatePinSessionForVin(vin)
        }
        return success
    }

    /** Queries vehicle SIM certification status through zyhl service 935. */
    fun queryVehicleSimStatus(accessToken: String = "", vin: String): String {
        require(vin.isNotBlank()) { "vin is required" }
        val response = postEnvelope(
            serviceType = "935",
            data = JSONObject().put("vin", vin.trim().uppercase(Locale.US)),
            globalId = resolveAuthToken(accessToken)
        )
        requireSuccess(response, "935")
        return response.dataObject().optString("simStatus", "1")
    }

    /** Sets the default vehicle through zyhl service 932. */
    fun setDefaultVehicle(accessToken: String = "", vin: String): Boolean {
        require(vin.isNotBlank()) { "vin is required" }
        val response = postEnvelope(
            serviceType = "932",
            data = JSONObject().put("vin", vin.trim().uppercase(Locale.US)),
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "932")
        return true
    }

    /** Saves editable vehicle information through zyhl service 933. */
    fun saveVehicleInfo(
        accessToken: String = "",
        vin: String,
        plateNo: String,
        hostName: String = "",
        purchaseDate: String = ""
    ): Boolean {
        require(vin.isNotBlank()) { "vin is required" }
        require(plateNo.isNotBlank()) { "plateNo is required" }
        val response = postEnvelope(
            serviceType = "933",
            data = JSONObject().apply {
                put("hostName", hostName.trim())
                put("licenseNum", plateNo.trim())
                put("purchaseDate", purchaseDate.trim())
                put("vin", vin.trim().uppercase(Locale.US))
            },
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "933")
        return true
    }

    /** Saves a missing license plate through zyhl service 933. */
    fun updateVehicleLicensePlate(
        vin: String,
        plateNo: String,
        accessToken: String = "",
        hostName: String = "",
        purchaseDate: String = ""
    ): Boolean = saveVehicleInfo(accessToken, vin, plateNo, hostName, purchaseDate)

    /** Unbinds a vehicle through zyhl service 931 after optional PIN verification. */
    fun unbindVehicle(accessToken: String = "", vin: String, pinPlain: String): Boolean {
        require(vin.isNotBlank()) { "vin is required" }
        require(pinPlain.isNotBlank()) { "pin is required" }
        val resolvedVin = vin.trim().uppercase(Locale.US)
        if (!verifyVehiclePin(resolvedVin, pinPlain, accessToken)) {
            return false
        }
        val response = postEnvelope(
            serviceType = "931",
            data = JSONObject().apply {
                put("openGesturePass", "0")
                put("vehiclePass", hashPasswordOrPin(pinPlain.trim()))
                put("vin", resolvedVin)
            },
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "解绑车辆(931)")
        return true
    }

    /** Cancels vehicle certification through zyhl service 934. */
    fun cancelVehicleCertification(
        accessToken: String = "",
        vin: String,
        iccid: String,
        pinPlain: String,
        reason: String = "",
        description: String = ""
    ): Boolean {
        require(vin.isNotBlank()) { "vin is required" }
        val resolvedVin = vin.trim().uppercase(Locale.US)
        if (pinPlain.isNotBlank() && !verifyVehiclePin(resolvedVin, pinPlain, accessToken)) {
            return false
        }
        val response = postEnvelope(
            serviceType = "934",
            data = JSONObject().apply {
                put("description", description)
                put("iccid", iccid.trim())
                put("openGesturePass", "0")
                put("ownercertid", "")
                put("ownercerttype", "")
                put("reason", reason)
                put("vehiclePass", hashPasswordOrPin(pinPlain.trim()))
                put("vin", resolvedVin)
            },
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "取消车辆认证(934)")
        return true
    }

    /** Binds a new vehicle through zyhl service 930. */
    fun bindNewVehicle(
        accessToken: String = "",
        vin: String,
        sn: String,
        iccid: String,
        pinPlain: String,
        plateNo: String = "",
        hostName: String = "",
        purchaseDate: String = "",
        isDefault: Boolean = true
    ): Boolean {
        require(vin.isNotBlank()) { "vin is required" }
        val response = postEnvelope(
            serviceType = "930",
            data = JSONObject().apply {
                put("licenseNum", plateNo.trim())
                put("vin", vin.trim().uppercase(Locale.US))
                put("sn", sn.trim())
                put("iccid", iccid.trim())
                put("hostName", hostName.trim())
                put("purchaseDate", purchaseDate.trim())
                put("controlPass", hashPasswordOrPin(pinPlain.trim()))
                put("isDefault", if (isDefault) "1" else "0")
            },
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "绑定车辆(930)")
        return true
    }

    /** Sends a remote command through zyhl service 970. */
    fun sendRemoteCommand(
        vin: String,
        userId: String,
        accessToken: String,
        sdkToken: String = "",
        command: RemoteCommand,
        pin: String = "",
        params: Map<String, String> = emptyMap(),
        verifyPinFirst: Boolean = false
    ): RemoteCommandResult {
        return sendRemoteCommandZyhl(
            vin = vin,
            globalId = firstNonBlank(accessToken, sessionGlobalId, sessionAccessToken),
            command = command,
            pinPlain = pin,
            verifyPinFirst = verifyPinFirst,
            params = params
        )
    }

    private fun postPinSessionVerification(
        vin: String,
        pin: String,
        authToken: String
    ): ZyhlResponse = postEnvelope(
        serviceType = SERVICE_VERIFY_PIN,
        data = JSONObject().apply {
            put("vin", vin.trim().uppercase(Locale.US))
            put("vehiclePass", hashPasswordOrPin(pin))
            put("openGesturePass", "0")
        },
        globalId = authToken
    )

    private fun isConfirmed125PinFailure(resultCode: String, resultMessage: String): Boolean {
        val code = resultCode.trim()
        if (code.isBlank() || code == RESULT_SUCCESS || code == RESULT_SESSION_EXPIRED) return false
        if (code.startsWith("05") || code.startsWith("5")) return false
        if (isPinRejection(code, resultMessage)) return true
        // Explicit server-side 125 PIN rejection when message is blank/generic
        return resultMessage.isBlank() && code != "0400" && code != "0204"
    }

    fun isPinRejectionEnvelope(envelope: JSONObject): Boolean {
        val code = jsonText(envelope, "resultCode").ifBlank { jsonText(envelope, "code") }
        val msg = firstNonBlank(
            jsonText(envelope, "resultMsg"),
            jsonText(envelope, "resultMessage"),
            jsonText(envelope, "message"),
            jsonText(envelope, "error")
        )
        return isPinRejection(code, msg)
    }

    private fun isPinRejection(resultCode: String, resultMessage: String): Boolean {
        val code = resultCode.trim()
        if (code == RESULT_SESSION_EXPIRED || code == "0406") return false
        val msg = resultMessage.trim()
        if (msg.isEmpty()) return false
        val hasPinTerm = msg.contains("密码") ||
            msg.contains("安全码") ||
            msg.contains("PIN", ignoreCase = true)
        if (!hasPinTerm) return false
        // Reject broad non-PIN vehicle security/anti-theft messages; match explicit PIN validation failures.
        return msg.contains("错") ||
            msg.contains("误") ||
            msg.contains("不正确") ||
            msg.contains("无效") ||
            msg.contains("失败") ||
            msg.contains("不匹配") ||
            msg.contains("为空") ||
            msg.contains("未设置") ||
            msg.contains("锁定") ||
            msg.contains("超限") ||
            msg.contains("校验") ||
            msg.contains("验证") ||
            msg.contains("不正常") ||
            msg.contains("异常")
    }

    /** zyhl 970 command path, validating safePasswd via 125 when requested or when recovering a rejection. */
    private fun sendRemoteCommandZyhl(
        vin: String,
        globalId: String,
        command: RemoteCommand,
        pinPlain: String = "",
        verifyPinFirst: Boolean = false,
        params: Map<String, String> = emptyMap()
    ): RemoteCommandResult {
        val requestId = newRequestId()
        return try {
            val authToken = resolveAuthToken(globalId)
            require(authToken.isNotBlank()) { "accessToken/globalId is required" }
            val normVin = vin.trim().uppercase(Locale.US)
            val pinHash = if (pinPlain.isNotBlank()) hashPasswordOrPin(pinPlain) else ""
            var pinVerifiedBy125 = false
            val needPreVerify125 = pinHash.isNotBlank() &&
                (verifyPinFirst || !isPinSessionWarm(authToken, normVin, pinHash))
            if (needPreVerify125) {
                val pinResp = postPinSessionVerification(normVin, pinHash, authToken)
                if (pinResp.resultCode != RESULT_SUCCESS) {
                    val msg = pinResp.message.ifBlank { "PIN 码校验失败" }
                    val confirmedPinMismatch = isConfirmed125PinFailure(pinResp.resultCode, pinResp.message)
                    if (confirmedPinMismatch) invalidatePinSessionForVin(normVin)
                    return RemoteCommandResult(
                        success = false,
                        command = command,
                        requestId = pinResp.envelope.optString("requestId", requestId).ifBlank { requestId },
                        resultCode = pinResp.resultCode,
                        resultMessage = msg,
                        seq = "",
                        responseSign = pinResp.envelope.optString("sign"),
                        rawResponse = pinResp.rawBody,
                        error = msg,
                        isPinError = confirmedPinMismatch,
                        pinVerified = false
                    )
                }
                markPinSessionWarm(authToken, normVin, pinHash)
                pinVerifiedBy125 = true
            }
            val spec = commandSpec(command, params)
            val commandData = JSONObject().apply {
                put("vin", normVin)
                put("instruction", spec.instruction)
                spec.isSetTemp?.let { put("isSetTemp", it) }
                put("paras", spec.paras)
                put("passwordType", PASSWORD_TYPE_VEHICLE)
                if (pinHash.isNotBlank()) put("safePasswd", pinHash)
            }
            var response = postEnvelope(
                serviceType = SERVICE_REMOTE_COMMAND,
                data = commandData,
                globalId = authToken
            )
            var resultData = response.dataObjectOrNull()
            var dataResult = if (resultData != null) jsonText(resultData, "result") else ""
            var dataAccepted = dataResult.isEmpty() || dataResult == "1"
            var success = response.resultCode == RESULT_SUCCESS && dataAccepted
            var confirmedPinError = false
            var pinErrorOverrideMsg = ""

            if (success && pinHash.isNotBlank()) {
                markPinSessionWarm(authToken, normVin, pinHash)
            } else if (!pinVerifiedBy125 && pinHash.isNotBlank() &&
                isPinRejection(response.resultCode, response.message)
            ) {
                val verifyResp = try {
                    postPinSessionVerification(normVin, pinHash, authToken)
                } catch (err: Exception) {
                    if (err is CherySessionExpiredException || err is CheryDeviceBindingRequiredException) throw err
                    null
                }
                if (verifyResp?.resultCode == RESULT_SUCCESS) {
                    markPinSessionWarm(authToken, normVin, pinHash)
                    pinVerifiedBy125 = true
                    response = postEnvelope(
                        serviceType = SERVICE_REMOTE_COMMAND,
                        data = commandData,
                        globalId = authToken
                    )
                    resultData = response.dataObjectOrNull()
                    dataResult = if (resultData != null) jsonText(resultData, "result") else ""
                    dataAccepted = dataResult.isEmpty() || dataResult == "1"
                    success = response.resultCode == RESULT_SUCCESS && dataAccepted
                    confirmedPinError = false
                    if (success) markPinSessionWarm(authToken, normVin, pinHash)
                } else if (verifyResp != null &&
                    isConfirmed125PinFailure(verifyResp.resultCode, verifyResp.message)
                ) {
                    invalidatePinSessionForVin(normVin)
                    confirmedPinError = true
                    pinErrorOverrideMsg = verifyResp.message
                }
            } else if (!success && !pinVerifiedBy125 && pinHash.isBlank() &&
                isPinRejection(response.resultCode, response.message)
            ) {
                confirmedPinError = true
            }
            val effectiveResultMsg = pinErrorOverrideMsg.ifBlank { response.message }
            val errMsg = if (success) {
                null
            } else {
                val baseMsg = effectiveResultMsg.ifBlank {
                    if (!dataAccepted) "云端未受理指令 (result=$dataResult)" else "云端未受理指令"
                }
                if (response.resultCode.isNotBlank() && response.resultCode != RESULT_SUCCESS) {
                    "$baseMsg (${response.resultCode})"
                } else {
                    baseMsg
                }
            }
            RemoteCommandResult(
                success = success,
                command = command,
                requestId = response.envelope.optString("requestId", requestId).ifBlank { requestId },
                resultCode = response.resultCode,
                resultMessage = effectiveResultMsg,
                seq = if (resultData != null) jsonText(resultData, "seq") else "",
                responseSign = response.envelope.optString("sign"),
                rawResponse = response.rawBody,
                error = errMsg,
                isPinError = confirmedPinError,
                pinVerified = pinVerifiedBy125 || success
            )
        } catch (error: Exception) {
            if (error is CheryDeviceBindingRequiredException) throw error
            if (error is CherySessionExpiredException) throw error
            RemoteCommandResult(
                success = false,
                command = command,
                requestId = requestId,
                resultCode = "",
                resultMessage = "",
                seq = "",
                responseSign = "",
                rawResponse = "",
                error = error.message ?: error.javaClass.simpleName,
                isPinError = false
            )
        }
    }

    /** Returns the zyhl global identity token used by downstream SDK flows. */
    fun exchangeSdkToken(
        accessToken: String,
        vin: String = "",
        verificationCode: String = ""
    ): SdkTokenInfo = SdkTokenInfo(
        vin = vin.trim().uppercase(Locale.US),
        userId = sessionUserId,
        sdkToken = resolveGlobalId(accessToken),
        sdkTokenExpire = "",
        sdkRefreshToken = "",
        sdkRefreshTokenExpire = "",
        rawJson = "{}"
    )

    /**
     * zyhl source-compatible SDK exchange: validates user identity through 112
     * and returns the token captured from the 101/105 data.token field.
     */
    private fun exchangeSdkTokenZyhl(
        accessToken: String,
        vin: String,
        verificationCode: String
    ): SdkTokenInfo {
        val authToken = resolveAuthToken(accessToken)
        require(authToken.isNotBlank()) { "accessToken/globalId is required" }
        val userInfo = fetchUserInfoZyhl(authToken)
        if (verificationCode.isNotEmpty() && vin.isNotBlank()) {
            runCatching { verifyVehiclePinZyhl(vin, verificationCode, authToken) }
        }
        return SdkTokenInfo(
            vin = vin.trim().uppercase(Locale.US),
            userId = userInfo.userId,
            sdkToken = sessionSdkToken,
            sdkTokenExpire = "",
            sdkRefreshToken = sessionRefreshToken,
            sdkRefreshTokenExpire = "",
            rawJson = userInfo.rawJson
        )
    }

    /**
     * One-call account onboarding used by the new CarPlus flow:
     * login -> user/TSP identity -> primary bound vehicle -> durable profile ->
     * cloud K_root/info.bin/edf.bin provisioning.
     *
     * The account profile is persisted before provisioning so a transient cloud
     * key failure can be retried without repeating login. A provisioning
     * failure is surfaced as IOException; on success the returned profile and
     * login result retain the account and SDK tokens.
     */
    fun loginAndProvisionPrimaryVehicle(
        mobileNo: String,
        passwordOrSmsCode: String,
        bySms: Boolean,
        deviceId: String,
        provisioner: InGeekCloudProvisioner,
        profileStore: VehicleProfileStore
    ): CheryLoginResult {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        require(passwordOrSmsCode.isNotBlank()) {
            if (bySms) "smsCode is required" else "password is required"
        }

        val login = if (bySms) {
            loginWithSms(mobileNo, passwordOrSmsCode, deviceId)
        } else {
            loginWithPassword(mobileNo, passwordOrSmsCode, deviceId)
        }
        val accessToken = resolveUserAccessToken(login.accessToken)
        require(accessToken.isNotBlank()) { "login response has no access token" }

        val user = login.userInfo ?: fetchUserInfo(accessToken)
        val vehicles = login.vehicles.ifEmpty { fetchVehicleList(accessToken) }
        require(vehicles.any { it.vin.isNotBlank() }) { "账号未返回已绑定车辆，无法解封新车钥匙" }
        val primary = selectPrimaryVehicle(vehicles, profileStore)

        val sdk = runCatching {
            exchangeSdkToken(accessToken, primary.vin)
        }.getOrNull()
        val sdkToken = firstNonBlank(sdk?.sdkToken, login.sdkToken)
        val userId = firstNonBlank(sdk?.userId, user.userId, login.accountId)
        require(userId.isNotBlank()) { "账号缺少 TSP userId，无法执行云端钥匙解封" }

        val resolvedDeviceId = resolveDeviceId(deviceId)
            .ifBlank { profileStore.defaultDeviceId() }
        val existing = profileStore.listProfiles()
            .firstOrNull { it.vin.equals(primary.vin, ignoreCase = true) }
        val mobile = firstNonBlank(user.telephone, login.telephone, mobileNo)
        val profile = VehicleProfile(
            vin = primary.vin.trim().uppercase(Locale.US),
            userId = userId,
            mobileNo = mobile,
            bleName = primary.bleName.ifBlank { existing?.bleName.orEmpty() },
            bleMac = primary.bleMac.ifBlank { existing?.bleMac.orEmpty() },
            deviceId = resolvedDeviceId,
            accountType = primary.accountType,
            rootKeyHex = existing?.rootKeyHex.orEmpty(),
            infoHex = existing?.infoHex.orEmpty(),
            edfHex = existing?.edfHex.orEmpty(),
            validFrom = existing?.validFrom.orEmpty(),
            validTo = existing?.validTo.orEmpty(),
            secAHex = existing?.secAHex.orEmpty(),
            secBHex = existing?.secBHex.orEmpty(),
            keyRole = existing?.keyRole ?: 1,
            accessToken = accessToken,
            sdkToken = sdkToken,
            controlPin = existing?.controlPin.orEmpty()
        )
        val saved = profileStore.saveProfile(profile, activate = true)

        val provision = provisioner.provisionVehicleKey(
            userId = userId,
            mobileNo = mobile,
            vin = saved.vin,
            bleName = saved.bleName,
            bleMac = saved.bleMac,
            deviceId = resolvedDeviceId,
            accountType = primary.accountType,
            profileStore = profileStore
        )
        if (!provision.success) {
            throw IOException(
                "登录与车辆选择已完成，但云端钥匙解封失败：" +
                    (provision.error ?: provision.message).ifBlank { "未知错误" }
            )
        }

        // InGeekCloudProvisioner writes only key material. Re-merge the account
        // tokens so later HTTP/BLE mode switching keeps working after provisioning.
        val provisioned = provision.profile ?: saved
        profileStore.saveProfile(
            provisioned.copy(
                userId = userId,
                mobileNo = mobile,
                deviceId = resolvedDeviceId,
                accountType = primary.accountType,
                accessToken = accessToken,
                sdkToken = sdkToken,
                controlPin = existing?.controlPin.orEmpty()
            ),
            activate = true
        )

        return login.copy(
            accountId = userId,
            telephone = mobile,
            sdkToken = sdkToken,
            message = "登录、主车辆选择与云端钥匙解封成功",
            userInfo = user,
            vehicles = vehicles
        )
    }

    private fun selectPrimaryVehicle(
        vehicles: List<VehicleSummary>,
        profileStore: VehicleProfileStore
    ): VehicleSummary {
        val activeVin = profileStore.getActiveProfile().vin
        return vehicles.firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
            ?: vehicles.firstOrNull { it.selected == "1" || it.isBluetoothAuth == "1" }
            ?: vehicles.first()
    }

    private fun finishLogin(
        loginResponse: ZyhlResponse,
        mobileNo: String,
        resolvedDeviceId: String
    ): CheryLoginResult {
        val loginData = loginResponse.dataObject()
        val accessToken = updateSessionFrom(loginResponse)
            .ifBlank { loginData.optString("token") }
        require(accessToken.isNotBlank()) { "login response has no globalId/token" }

        val dataToken = loginData.optString("token")
        if (dataToken.isNotBlank()) sessionSdkToken = dataToken
        val refreshToken = firstNonBlank(
            loginData.optString("refreshToken"),
            loginData.optString("refresh_token")
        )
        if (refreshToken.isNotBlank()) sessionRefreshToken = refreshToken

        val userInfo = runCatching { fetchUserInfoZyhl(accessToken) }.getOrNull()
        val vehicles = runCatching { fetchVehicleListZyhl(accessToken) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.selected }
        val userId = firstNonBlank(
            userInfo?.userId,
            loginData.optString("userId"),
            loginData.optString("accountId")
        )
        sessionGlobalId = accessToken
        sessionAccessToken = ""
        sessionUserId = userId
        sessionMobileNo = firstNonBlank(userInfo?.telephone, mobileNo.trim())

        return CheryLoginResult(
            accessToken = accessToken,
            refreshToken = refreshToken,
            openId = loginData.optString("openId"),
            accountId = userId,
            expireAt = 0L,
            refreshExpireAt = 0L,
            rawJson = loginResponse.rawBody,
            sdkToken = dataToken,
            accountType = loginData.optString("accountType"),
            telephone = firstNonBlank(userInfo?.telephone, mobileNo.trim()),
            message = "智云互联登录成功",
            userInfo = userInfo,
            vehicles = vehicles
        )
    }

    private fun parseVehicleSummary(item: JSONObject): VehicleSummary {
        val vin = item.optString("vin").trim().uppercase(Locale.US)
        val rawBleMac = firstNonBlank(
            item.optString("bleMac"),
            item.optString("bluetoothMac"),
            item.optString("btMac"),
            item.optString("bleAddress"),
            item.optString("mac")
        )
        val cleanMac = rawBleMac.replace(Regex("[^0-9A-Fa-f]"), "").uppercase(Locale.US)
        val prefix = item.optString("bleNamePrefix").trim().ifBlank { DEFAULT_BLE_NAME_PREFIX }
        val explicitBleName = firstNonBlank(
            item.optString("bleName"),
            item.optString("bleNameValue"),
            item.optString("bluetoothName")
        )
        val bleName = explicitBleName.ifBlank {
            if (cleanMac.length == 12 && cleanMac.matches(Regex("[0-9A-F]{12}"))) {
                "$prefix$cleanMac"
            } else {
                ""
            }
        }
        val isBluetoothAuth = firstNonBlank(
            item.optString("isBluetoothAuth"),
            item.optString("autMethod")
        )
        val isSelected = item.optString("selected") == "1" ||
            item.optBoolean("selected", false) ||
            item.optString("defaultCar") == "1" ||
            item.optBoolean("isDefault", false)
        val autMethod = firstNonBlank(item.optString("autMethod"), "0")
        val dateList = item.optJSONArray("dateList")
            ?: item.optJSONObject("vehicleInfo")?.optJSONArray("dateList")
            ?: item.optJSONObject("carInfo")?.optJSONArray("dateList")
        return VehicleSummary(
            vin = vin,
            bleMac = rawBleMac,
            bleName = bleName,
            seriesName = firstNonBlank(
                item.optString("standardName"),
                item.optString("typeDesc"),
                item.optString("vehicleType")
            ),
            plateNo = vehiclePlateNo(item),
            vehicleCode = firstNonBlank(item.optString("vehicleCode"), item.optString("vehicleTypeID")),
            standardName = item.optString("standardName"),
            vehicleName = firstNonBlank(item.optString("vehicleName"), item.optString("vehicleType")),
            vehicleTypeId = firstNonBlank(item.optString("vehicleTypeID"), item.optString("vehicleTypeId")),
            vehicleId = firstNonBlank(item.optString("vehicleId"), item.optString("id")),
            airType = item.optString("airType"),
            sunroofType = item.optString("sunroofType"),
            rawJson = item.toString(),
            bleNamePrefix = prefix,
            bleNameType = item.optString("bleNameType"),
            isBluetoothAuth = isBluetoothAuth,
            selected = if (isSelected) "1" else "",
            vehicleType = item.optString("vehicleType"),
            tempLo = item.optString("tempLo"),
            tempHi = item.optString("tempHi"),
            openGesturePass = item.optString("openGesturePass"),
            passType = item.optString("passType"),
            odometer = item.optString("odometer"),
            batteryLevel = item.optString("batteryLevel"),
            remainedOil = item.optString("remainedOil"),
            accountType = when (isBluetoothAuth.trim()) {
                "0" -> 0 // owner account
                "1" -> 1 // shared child account
                else -> 0
            },
            isTracksOpen = parseFlagBoolean(firstNonBlank(jsonText(item, "is_tracks_open"), jsonText(item, "isTracksOpen"))),
            colorName = firstNonBlank(item.optString("colorName"), item.optString("color")),
            seriesCode = firstNonBlank(item.optString("seriesCode"), item.optString("series")),
            iccid = firstNonBlank(item.optString("iccid"), item.optString("ICCID")),
            sn = firstNonBlank(item.optString("sn"), item.optString("tboxSn"), item.optString("SN")),
            engineNumber = firstNonBlank(
                item.optString("engineNumber"),
                item.optString("enginenum"),
                item.optString("engineNo")
            ),
            hostName = firstNonBlank(
                item.optString("hostName"),
                item.optString("ownerName"),
                item.optString("username")
            ),
            purchaseTime = firstNonBlank(
                item.optString("purchaseTime"),
                item.optString("purchaseDate"),
                item.optString("buydate")
            ),
            autMethod = autMethod,
            simStatus = firstNonBlank(item.optString("simStatus"), "1"),
            isBoundVehicle = autMethod == "0" || autMethod.isBlank(),
            authDateListSummary = parseAuthorizationDateSummary(dateList),
            engineType = firstNonBlank(item.optString("engineType"), item.optString("fuelType")),
            openAir = item.optString("openAir").trim()
        )
    }

    private fun parseAuthorizationDateSummary(dateList: JSONArray?): String {
        if (dateList == null) return ""
        val ranges = mutableListOf<String>()
        for (index in 0 until dateList.length()) {
            val item = dateList.optJSONObject(index) ?: continue
            val startDate = firstNonBlank(
                item.optString("startDate"),
                item.optString("starttime"),
                item.optString("startTime")
            )
            val endDate = firstNonBlank(
                item.optString("endDate"),
                item.optString("endtime"),
                item.optString("endTime")
            )
            when {
                startDate.isNotBlank() && endDate.isNotBlank() -> ranges.add("$startDate ~ $endDate")
                startDate.isNotBlank() -> ranges.add(startDate)
                endDate.isNotBlank() -> ranges.add(endDate)
            }
        }
        return ranges.distinct().joinToString("；")
    }

    private fun vehiclePlateNo(vararg candidates: JSONObject?): String {
        val plateKeys = listOf(
            "licensenum",
            "licenseNum",
            "licensePlate",
            "licenseNo",
            "plateNo",
            "plateNum",
            "plateNumber",
            "carNo",
            "carNumber",
            "vehicleNo",
            "vehicleLicense"
        )
        val sources = candidates.filterNotNull().flatMap { candidate ->
            listOfNotNull(
                candidate,
                candidate.optJSONObject("vehicleInfo"),
                candidate.optJSONObject("carInfo")
            )
        }
        return firstNonBlank(
            *sources.flatMap { source ->
                plateKeys.map { key ->
                    source.optString(key).takeUnless {
                        it.isBlank() || it.equals("null", ignoreCase = true)
                    }.orEmpty()
                }
            }.toTypedArray()
        )
    }

    private fun parseTelemetry(
        requestedVin: String,
        status: JSONObject,
        rawBody: String
    ): VehicleTelemetry {
        val conditionFields = linkedMapOf<String, String>().apply {
            status.keys().forEach { key ->
                val value = status.opt(key)
                if (value != null && value != JSONObject.NULL) put(key, value.toString())
            }
        }
        val data = status.optJSONObject("data") ?: status
        val vin = firstNonBlank(status.optString("vin"), requestedVin)
        val doorLock = firstNonBlank(
            status.optString("doorLock"),
            status.optString("doorLockState"),
            status.optString("lockState")
        )
        val frontLeftDoor = status.optString("frontLeftDoor")
        val frontRightDoor = status.optString("frontRightDoor")
        val backLeftDoor = status.optString("backLeftDoor")
        val backRightDoor = status.optString("backRightDoor")
        val trunkDoor = firstNonBlank(status.optString("trunkDoor"), status.optString("trunkState"))
        val trunkLock = firstNonBlank(status.optString("trunkLock"), status.optString("trunkLockState"))
        val hood = status.optString("hood")
        val frontLeftWindowState = status.optString("frontLeftWindowState")
        val frontRightWindowState = status.optString("frontRightWindowState")
        val backLeftWindowState = status.optString("backLeftWindowState")
        val backRightWindowState = status.optString("backRightWindowState")
        val sunroofState = firstNonBlank(status.optString("sunroofState"), status.optString("skylightState"))
        val sunroofOperateState = status.optString("sunroofOperateState")
        val liftgateState = firstNonBlank(status.optString("liftgateState"), status.optString("tailgateState"))
        val liftgateOperateState = status.optString("liftgateOperateState")
        val engineState = firstNonBlank(status.optString("engineState"), status.optString("engineStatus"))
        val airState = firstNonBlank(status.optString("airState"), status.optString("acState"))
        val odometer = status.optString("odometer")
        val oilSurplus = status.optString("oilSurplus")
        val electricitySurplus = status.optString("electricitySurplus")
        val mileageSurplus = status.optString("mileageSurplus")
        val lon = firstNonBlank(status.optString("lon"), status.optString("longitude"))
        val lat = firstNonBlank(status.optString("lat"), status.optString("latitude"))
        val licensePlate = firstNonBlank(
            status.optString("licensePlate"),
            status.optString("licensenum"),
            status.optString("licenseNum"),
            status.optString("licenseNo"),
            status.optString("plateNo"),
            status.optString("plateNum"),
            status.optString("carNo"),
            status.optString("carNumber"),
            data.optString("licensePlate"),
            data.optString("licensenum")
        )
        if (licensePlate.isNotBlank() && "licensePlate" !in conditionFields) {
            conditionFields["licensePlate"] = licensePlate
        }

        val doorStates = linkedMapOf(
            "frontLeft" to frontLeftDoor,
            "frontRight" to frontRightDoor,
            "backLeft" to backLeftDoor,
            "backRight" to backRightDoor,
            "trunkDoor" to trunkDoor,
            "trunkLock" to trunkLock,
            "hood" to hood
        )
        val windowStates = linkedMapOf(
            "frontLeft" to parseWindowState(frontLeftWindowState),
            "frontRight" to parseWindowState(frontRightWindowState),
            "backLeft" to parseWindowState(backLeftWindowState),
            "backRight" to parseWindowState(backRightWindowState),
            "sunroof" to parseSunroofState(sunroofState, sunroofOperateState),
            "liftgate" to parseLiftgateState(liftgateState.ifBlank { liftgateOperateState })
        )

        return VehicleTelemetry(
            vin = vin,
            doorLockState = doorLock,
            doorLocked = parseDoorLocked(doorLock),
            doorStates = doorStates,
            windowStates = windowStates,
            trunkState = trunkDoor.ifBlank { trunkLock },
            trunkOpen = parseTrunkOpen(trunkDoor, liftgateState, liftgateOperateState),
            tailgateState = liftgateState,
            skylightState = sunroofState,
            engineState = engineState,
            engineRunning = parseEngineRunning(engineState),
            acState = airState,
            acOn = parseAcOn(airState),
            batteryVoltage = firstDouble(status, "batteryVoltage", "voltage", "electricityVoltage")
                ?.takeIf { it in 5.0..100.0 },
            fuelPercent = firstDouble(status, "oilSurplus")
                ?.takeIf { it in 0.0..100.0 },
            remainingRangeKm = firstDouble(status, "mileageSurplus")
                ?.takeIf { it in 0.0..5_000.0 },
            odometerKm = firstDouble(status, "odometer")
                ?.takeIf { it in 0.0..2_000_000.0 },
            latitude = firstDouble(status, "lat"),
            longitude = firstDouble(status, "lon"),
            rawJson = if (status.length() == 0) rawBody else status.toString(),
            conditionFields = conditionFields,
            doorLock = doorLock,
            frontLeftDoor = frontLeftDoor,
            frontRightDoor = frontRightDoor,
            backLeftDoor = backLeftDoor,
            backRightDoor = backRightDoor,
            trunkDoor = trunkDoor,
            trunkLock = trunkLock,
            hood = hood,
            frontLeftWindowState = frontLeftWindowState,
            frontRightWindowState = frontRightWindowState,
            backLeftWindowState = backLeftWindowState,
            backRightWindowState = backRightWindowState,
            sunroofState = sunroofState,
            liftgateState = liftgateState,
            airState = airState,
            odometer = odometer,
            oilSurplus = oilSurplus,
            electricitySurplus = electricitySurplus,
            mileageSurplus = mileageSurplus,
            lFrontTyre = firstNonBlank(
                status.optString("lFrontTyre"),
                status.optString("leftFrontTyrekPa"),
                status.optString("lFrontTyrekPa")
            ),
            rFrontTyre = firstNonBlank(
                status.optString("rFrontTyre"),
                status.optString("rightFrontTyrekPa"),
                status.optString("rFrontTyrekPa")
            ),
            lRearTyre = firstNonBlank(
                status.optString("lRearTyre"),
                status.optString("leftRearTyrekPa"),
                status.optString("lRearTyrekPa")
            ),
            rRearTyre = firstNonBlank(
                status.optString("rRearTyre"),
                status.optString("rightRearTyrekPa"),
                status.optString("rRearTyrekPa")
            ),
            lon = lon,
            lat = lat,
            online = firstNonBlank(status.optString("online"), status.optString("isOnline")),
            time = firstNonBlank(
                status.optString("time"),
                status.optString("systemDatetime"),
                status.optString("reportTime")
            ),
            seq = status.optString("seq"),
            result = status.optString("result"),
            msg = status.optString("msg"),
            licensePlate = licensePlate,
            averageFuel = status.optString("averageFuel"),
            averageElectricity = status.optString("averageElectricity"),
            electricityMileage = firstNonBlank(
                status.optString("electricitymileage"),
                status.optString("electricityMileage")
            ),
            chargingFlag = status.optString("chargingFlag"),
            chargingGun = status.optString("chargingGun"),
            remainingCharge = status.optString("remainingCharge"),
            chargeAppintStatus = status.optString("chargeAppintStatus"),
            dSeatHeatingState = status.optString("dSeatHeatingState"),
            pSeatHeatingState = status.optString("pSeatHeatingState"),
            dSeatVentilateState = status.optString("dSeatVentilateState"),
            pSeatVentilateState = status.optString("pSeatVentilateState"),
            fWinHeatingState = status.optString("fWinHeatingState"),
            rWinHeatingState = status.optString("rWinHeatingState"),
            airPurification = status.optString("airPurification"),
            lFrontTyreTemp = status.optString("lFrontTyreTemp"),
            rFrontTyreTemp = status.optString("rFrontTyreTemp"),
            lRearTyreTemp = status.optString("lRearTyreTemp"),
            rRearTyreTemp = status.optString("rRearTyreTemp"),
            tyreUnit = status.optString("tyreUnit"),
            tripSwitchStatus = status.optString("tripSwitchStatus")
        )
    }

    private fun emptyTelemetry(vin: String, rawBody: String): VehicleTelemetry = VehicleTelemetry(
        vin = vin,
        doorLockState = "",
        doorLocked = null,
        doorStates = emptyMap(),
        windowStates = emptyMap(),
        trunkState = "",
        trunkOpen = null,
        tailgateState = "",
        skylightState = "",
        engineState = "",
        engineRunning = null,
        acState = "",
        acOn = null,
        batteryVoltage = null,
        fuelPercent = null,
        remainingRangeKm = null,
        odometerKm = null,
        latitude = null,
        longitude = null,
        rawJson = rawBody
    )

    private fun commandSpec(
        command: RemoteCommand,
        params: Map<String, String>
    ): CommandSpec = when (command) {
        RemoteCommand.FIND_CAR -> CommandSpec("1101", JSONObject())
        RemoteCommand.ENGINE_START -> CommandSpec(
            "1103",
            JSONObject().apply {
                put("0002", 1)
                val duration = params["controlTime"]?.trim()?.toIntOrNull()
                if (duration == 5 || duration == 10 || duration == 15) {
                    put("300D", duration)
                }
            }
        )
        RemoteCommand.ENGINE_STOP -> CommandSpec(
            "1103",
            JSONObject().put("0002", 0)
        )
        RemoteCommand.DOOR_UNLOCK -> CommandSpec(
            "1104",
            JSONObject().put("0002", 1)
        )
        RemoteCommand.DOOR_LOCK -> CommandSpec(
            "1104",
            JSONObject().put("0002", 0)
        )
        RemoteCommand.AC_ON -> CommandSpec(
            "1105",
            JSONObject().apply {
                put("0002", 1)
                put("3001", parseIntParam(params, 24, "temperature", "airTemperature", "temp"))
                put("3010", parseIntParam(params, 1, "airType"))
                val duration = params["controlTime"]?.trim()?.toIntOrNull()
                if (duration == 5 || duration == 10 || duration == 15) {
                    put("300D", duration)
                }
                params["airPM"]?.trim()?.toIntOrNull()?.takeIf { it == 0 || it == 1 }?.let {
                    put("300F", it)
                }
                params["frontDefrost"]?.trim()?.toIntOrNull()?.takeIf { it == 0 || it == 1 }?.let {
                    put("3013", it)
                }
                params["rearDefrost"]?.trim()?.toIntOrNull()?.takeIf { it == 0 || it == 1 }?.let {
                    put("3014", it)
                }
                params["driverHeating"]?.trim()?.toIntOrNull()?.let {
                    put("3015", if (it > 0) 2 else 0)
                }
                params["passengerHeating"]?.trim()?.toIntOrNull()?.let {
                    put("3016", if (it > 0) 2 else 0)
                }
                params["driverVent"]?.trim()?.toIntOrNull()?.takeIf { it == 0 || it == 1 }?.let {
                    put("3029", it)
                }
                params["passengerVent"]?.trim()?.toIntOrNull()?.takeIf { it == 0 || it == 1 }?.let {
                    put("302A", it)
                }
            },
            isSetTemp = "1"
        )
        RemoteCommand.AC_OFF -> CommandSpec(
            "1105",
            JSONObject().apply {
                put("0002", 0)
                put("3001", 0)
                params["airType"]?.trim()?.toIntOrNull()?.takeIf { it == 0 || it == 1 }?.let {
                    put("3010", it)
                }
            }
        )
        RemoteCommand.WINDOW_OPEN -> CommandSpec(
            "1107",
            JSONObject().put("3011", 3)
        )
        RemoteCommand.WINDOW_VENTILATE -> CommandSpec(
            "1107",
            JSONObject().put("3011", 1)
        )
        RemoteCommand.WINDOW_CLOSE -> CommandSpec(
            "1107",
            JSONObject().put("3011", 0)
        )
        RemoteCommand.TAILGATE_OPEN -> CommandSpec(
            "1108",
            JSONObject().put("3023", 1)
        )
        RemoteCommand.TAILGATE_CLOSE -> CommandSpec(
            "1108",
            JSONObject().put("3023", 0)
        )
        RemoteCommand.TRUNK_UNLOCK -> CommandSpec(
            "1106",
            JSONObject().put("0002", 1)
        )
        RemoteCommand.SKYLIGHT_OPEN -> CommandSpec(
            "1109",
            JSONObject().apply {
                put("3017", 1)
                put("3025", 11)
                params["sunroofType"]?.trim()?.toIntOrNull()?.takeIf { it in 0..1 }?.let { put("3026", it) }
            }
        )
        RemoteCommand.SKYLIGHT_TILT -> CommandSpec(
            "1109",
            JSONObject().apply {
                put("3017", 2)
                put("3025", 11)
                params["sunroofType"]?.trim()?.toIntOrNull()?.takeIf { it in 0..1 }?.let { put("3026", it) }
            }
        )
        RemoteCommand.SKYLIGHT_CLOSE -> CommandSpec(
            "1109",
            JSONObject().apply {
                put("3017", 0)
                put("3025", 11)
                params["sunroofType"]?.trim()?.toIntOrNull()?.takeIf { it in 0..1 }?.let { put("3026", it) }
            }
        )
    }

    private fun parseIntParam(
        params: Map<String, String>,
        defaultValue: Int,
        vararg keys: String
    ): Int = keys.firstNotNullOfOrNull { key ->
        params[key]?.trim()?.toDoubleOrNull()?.let { Math.round(it).toInt() }
    } ?: defaultValue

    fun executeZyhlService(
        serviceType: String,
        data: JSONObject = JSONObject(),
        accessToken: String = ""
    ): JSONObject {
        val authToken = resolveAuthToken(accessToken)
        if (serviceType == SERVICE_REMOTE_COMMAND) {
            val normVin = data.optString("vin").trim().uppercase(Locale.US)
            val safePasswd = data.optString("safePasswd").trim()
            if (normVin.isNotBlank() && safePasswd.isNotBlank()) {
                val pinHash = hashPasswordOrPin(safePasswd)
                var preVerified = false
                if (!isPinSessionWarm(authToken, normVin, pinHash)) {
                    val pinResp = postPinSessionVerification(normVin, pinHash, authToken)
                    if (pinResp.resultCode != RESULT_SUCCESS) {
                        if (isConfirmed125PinFailure(pinResp.resultCode, pinResp.message)) {
                            invalidatePinSessionForVin(normVin)
                        }
                        return pinResp.envelope
                    }
                    markPinSessionWarm(authToken, normVin, pinHash)
                    preVerified = true
                }

                val response = postEnvelope(serviceType, data, authToken)
                if (is970CommandAccepted(response.envelope)) {
                    markPinSessionWarm(authToken, normVin, pinHash)
                    return response.envelope
                }

                if (!preVerified && isPinRejection(response.resultCode, response.message)) {
                    val pinResp = runCatching {
                        postPinSessionVerification(normVin, pinHash, authToken)
                    }.getOrElse { err ->
                        if (err is CherySessionExpiredException ||
                            err is CheryDeviceBindingRequiredException
                        ) {
                            throw err
                        }
                        null
                    }
                    if (pinResp?.resultCode == RESULT_SUCCESS) {
                        markPinSessionWarm(authToken, normVin, pinHash)
                        val retryResp = postEnvelope(serviceType, data, authToken)
                        if (is970CommandAccepted(retryResp.envelope)) {
                            markPinSessionWarm(authToken, normVin, pinHash)
                        }
                        return retryResp.envelope
                    } else if (pinResp != null &&
                        isConfirmed125PinFailure(pinResp.resultCode, pinResp.message)
                    ) {
                        invalidatePinSessionForVin(normVin)
                        return pinResp.envelope
                    }
                }
                return response.envelope
            }
        }
        return postEnvelope(serviceType, data, authToken).envelope
    }

    /** Source-compatible alias for callers that use the generic service entry. */
    fun executeService(
        serviceType: String,
        data: JSONObject = JSONObject(),
        accessToken: String = ""
    ): JSONObject = executeZyhlService(serviceType, data, accessToken)

    fun fetchLastDrivingInfo(vin: String, accessToken: String = ""): DrivingSummaryInfo {
        val summaryEnvelope = executeZyhlService(
            serviceType = "140",
            data = JSONObject().put("vin", vin),
            accessToken = accessToken
        )
        val summaryData = zyhlDataObject(summaryEnvelope)

        val tripEnvelope = executeZyhlService(
            serviceType = "141",
            data = JSONObject().put("vin", vin),
            accessToken = accessToken
        )
        val tripData = zyhlDataObject(tripEnvelope)
        var lastTrip = if (jsonText(tripData, "startTime").isNotBlank()) {
            parseTripRecord(tripData)
        } else {
            null
        }
        if (lastTrip == null) {
            lastTrip = fetchTripRecords(vin, "", accessToken).firstOrNull()
        }

        return DrivingSummaryInfo(
            lastConsumeTime = jsonText(summaryData, "consumeTime"),
            totalConsumeTime = jsonText(summaryData, "consumeTimeSum"),
            lastDistanceKm = jsonText(summaryData, "distance"),
            totalDistanceKm = jsonText(summaryData, "distanceSum"),
            lastTrip = lastTrip,
            rawJson = tripEnvelope.toString()
        )
    }

    private fun isValidTripDateToken(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return false
        // Filter out opaque index-only tokens like "0", "1", "2" (978 dateId) and weekday "01".."07" (973 curDate)
        if (trimmed.length <= 2 && trimmed.all { it.isDigit() }) return false
        return true
    }

    private fun extractDateKeyFromTrip(trip: TripRecordItem, fallback: String = ""): String {
        val candidates = listOf(
            fallback,
            trip.startTime.substringBefore(' '),
            trip.endTime.substringBefore(' '),
            trip.curDate
        )
        for (c in candidates) {
            val cleaned = c.trim()
            if (isValidTripDateToken(cleaned)) return cleaned
        }
        return "最近行程"
    }

    private fun zyhlDataArray(envelope: JSONObject): JSONArray? {
        val raw = envelope.opt("data") ?: return null
        return when (raw) {
            is JSONArray -> raw
            is String -> raw.trim().takeUnless { it.isBlank() || it == "null" }
                ?.let { runCatching { JSONArray(it) }.getOrNull() }
            else -> null
        }
    }

    private fun isNonEmptyTrip(trip: TripRecordItem): Boolean =
        trip.startTime.isNotBlank() ||
            trip.endTime.isNotBlank() ||
            trip.points.isNotEmpty() ||
            trip.mileageKm.isNotBlank()

    /**
     * Fetches historical trips grouped by real calendar date (newest-first), along with
     * structured status diagnostics (`resultCode` / `resultMsg`) and `140` driving summary fallback.
     *
     * Protocol verified against zyhl JADX (`TravelRecordsActivity.java`, `DataResp973a/b`, `DataResp978a`, `DataResp979a`, `140`, `141`):
     * - Primary: `973` with `searchDateId=""` returns `dateList[]` of `{ date: "yyyy-MM-dd", curDate: "01".."07" (weekday!), list: [DataResp973b] }`.
     *   `resultCode == "0204"` means no trip records in the recent 7-day window.
     * - Secondary fallback: `978` (`dateId` + `dateStr`) newest-first (bounded to 10) paired with `979` / `973`.
     * - Final fallback: `141` latest trip (`DataResp973b`) + `140` driving summary (`DrivingSummaryInfo`).
     */
    fun fetchTripHistoryDetailed(
        vin: String,
        accessToken: String = ""
    ): TripHistoryResult {
        val groups = linkedMapOf<String, MutableList<TripRecordItem>>()
        var statusCode = ""
        var statusMessage = ""

        // 1. Primary: 973 with empty searchDateId (exact zyhl TravelRecordsActivity behavior)
        runCatching {
            val envelope973 = executeZyhlService(
                serviceType = "973",
                data = JSONObject().apply {
                    put("vin", vin)
                    put("searchDateId", "")
                },
                accessToken = accessToken
            )
            statusCode = jsonText(envelope973, "resultCode")
            statusMessage = firstNonBlank(
                jsonText(envelope973, "resultMsg"),
                jsonText(envelope973, "msg")
            )
            val data973 = zyhlDataObject(envelope973)
            val dateArray = data973.optJSONArray("dateList")
                ?: data973.optJSONArray("list")
                ?: zyhlDataArray(envelope973)
            val dateItems = objectList(dateArray)
            // 973 returns chronological order (oldest -> newest); iterate newest-first
            for (dateItem in dateItems.asReversed()) {
                // Note: DataResp973a.curDate is weekday "01".."07", NOT a calendar date — only use `date`
                val rawDate = jsonText(dateItem, "date")
                val trips = objectList(dateItem.optJSONArray("list"))
                    .map { parseTripRecord(it, fallbackDate = rawDate) }
                    .filter(::isNonEmptyTrip)
                if (trips.isEmpty()) continue
                val dateKey = if (isValidTripDateToken(rawDate)) {
                    rawDate
                } else {
                    extractDateKeyFromTrip(trips.first())
                }
                groups.getOrPut(dateKey) { mutableListOf() }.addAll(trips)
            }
        }.onFailure { err ->
            if (err is CherySessionExpiredException) throw err
            if (statusMessage.isBlank()) {
                statusMessage = err.message.orEmpty()
            }
        }

        // 2. Secondary fallback: 978 + (979 -> 973 per-date) newest-first, bounded to 10 dates
        if (groups.isEmpty()) {
            runCatching {
                val envelope978 = executeZyhlService(
                    serviceType = "978",
                    data = JSONObject().put("vin", vin),
                    accessToken = accessToken
                )
                val data978 = zyhlDataObject(envelope978)
                val dateEntries = objectList(data978.optJSONArray("list") ?: zyhlDataArray(envelope978))
                for (entry in dateEntries.asReversed().take(10)) {
                    val queryId = firstNonBlank(jsonText(entry, "dateId"), jsonText(entry, "dateStr"))
                    val displayDate = firstNonBlank(jsonText(entry, "dateStr"), jsonText(entry, "date"))
                    if (queryId.isBlank()) continue

                    var trips = runCatching {
                        val env979 = executeZyhlService(
                            serviceType = "979",
                            data = JSONObject().apply {
                                put("vin", vin)
                                put("searchDateId", queryId)
                            },
                            accessToken = accessToken
                        )
                        val d979 = zyhlDataObject(env979)
                        objectList(d979.optJSONArray("list") ?: zyhlDataArray(env979))
                            .map { parseTripRecord(it, fallbackDate = displayDate) }
                            .filter(::isNonEmptyTrip)
                    }.onFailure { if (it is CherySessionExpiredException) throw it }.getOrDefault(emptyList())

                    if (trips.isEmpty()) {
                        val tryIds = listOf(queryId, displayDate).filter { it.isNotBlank() }.distinct()
                        for (candidateId in tryIds) {
                            trips = runCatching {
                                val env973Day = executeZyhlService(
                                    serviceType = "973",
                                    data = JSONObject().apply {
                                        put("vin", vin)
                                        put("searchDateId", candidateId)
                                    },
                                    accessToken = accessToken
                                )
                                val d973 = zyhlDataObject(env973Day)
                                objectList(d973.optJSONArray("dateList") ?: zyhlDataArray(env973Day)).flatMap { di ->
                                    val dayStr = jsonText(di, "date").ifBlank { displayDate }
                                    objectList(di.optJSONArray("list"))
                                        .map { parseTripRecord(it, fallbackDate = dayStr) }
                                        .filter(::isNonEmptyTrip)
                                }
                            }.onFailure { if (it is CherySessionExpiredException) throw it }.getOrDefault(emptyList())
                            if (trips.isNotEmpty()) break
                        }
                    }

                    if (trips.isEmpty()) continue
                    val dateKey = if (isValidTripDateToken(displayDate)) {
                        displayDate
                    } else {
                        extractDateKeyFromTrip(trips.first())
                    }
                    groups.getOrPut(dateKey) { mutableListOf() }.addAll(trips)
                }
            }.onFailure { if (it is CherySessionExpiredException) throw it }
        }

        // 3. Final fallback: 141 latest trip detail (DataResp973b)
        if (groups.isEmpty()) {
            runCatching {
                val env141 = executeZyhlService(
                    serviceType = "141",
                    data = JSONObject().put("vin", vin),
                    accessToken = accessToken
                )
                val tripData = zyhlDataObject(env141)
                val latest = parseTripRecord(tripData)
                if (isNonEmptyTrip(latest)) {
                    val dateKey = extractDateKeyFromTrip(latest)
                    groups.getOrPut(dateKey) { mutableListOf() }.add(latest)
                }
            }.onFailure { if (it is CherySessionExpiredException) throw it }
        }

        // 4. When no trajectories exist, also fetch 140 driving summary so user sees real totals/last trip summary
        var summary140: DrivingSummaryInfo? = null
        if (groups.isEmpty()) {
            summary140 = runCatching {
                val env140 = executeZyhlService(
                    serviceType = "140",
                    data = JSONObject().put("vin", vin),
                    accessToken = accessToken
                )
                val d140 = zyhlDataObject(env140)
                val info = DrivingSummaryInfo(
                    lastConsumeTime = jsonText(d140, "consumeTime"),
                    totalConsumeTime = jsonText(d140, "consumeTimeSum"),
                    lastDistanceKm = jsonText(d140, "distance"),
                    totalDistanceKm = jsonText(d140, "distanceSum"),
                    lastTrip = null,
                    rawJson = env140.toString()
                )
                if (info.lastDistanceKm.isNotBlank() || info.lastConsumeTime.isNotBlank() ||
                    info.totalDistanceKm.isNotBlank() || info.totalConsumeTime.isNotBlank()
                ) info else null
            }.onFailure { if (it is CherySessionExpiredException) throw it }.getOrNull()
        }

        val dayGroups = groups.entries.map { (dateKey, list) ->
            TripDayGroup(
                dateKey = dateKey,
                dateLabel = dateKey,
                trips = list
            )
        }
        return TripHistoryResult(
            dayGroups = dayGroups,
            summary140 = summary140,
            statusCode = statusCode,
            statusMessage = statusMessage
        )
    }

    private val reverseGeocodeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun parseFlagBoolean(raw: String): Boolean? = when (raw.trim().lowercase(Locale.US)) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }

    /**
     * Reverse-geocodes GCJ-02/WGS-84 vehicle coordinates into a concise Chinese approximate
     * address (e.g. "杭州市西湖区 · 古墩路附近"), cached in-memory by `%.3f,%.3f`.
     * Returns `null` cleanly when offline or on timeout (4s).
     */
    fun reverseGeocodeApproxAddress(lat: Double, lon: Double): String? {
        if (!lat.isFinite() || !lon.isFinite() || (lat == 0.0 && lon == 0.0)) return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        val cacheKey = String.format(Locale.US, "%.3f,%.3f", lat, lon)
        reverseGeocodeCache[cacheKey]?.let { return it }

        val (wgsLat, wgsLon) = gcjToWgsApprox(lat, lon)
        val result = runCatching {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=16&accept-language=zh-CN",
                    wgsLat,
                    wgsLon
                )
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                setRequestProperty("User-Agent", "CarPlus/2.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val root = JSONObject(body)
                val addr = root.optJSONObject("address") ?: return@runCatching null
                val city = firstNonBlank(
                    jsonText(addr, "city"),
                    jsonText(addr, "town"),
                    jsonText(addr, "municipality"),
                    jsonText(addr, "state")
                )
                val district = firstNonBlank(
                    jsonText(addr, "city_district"),
                    jsonText(addr, "district"),
                    jsonText(addr, "suburb"),
                    jsonText(addr, "county")
                )
                val road = firstNonBlank(
                    jsonText(addr, "road"),
                    jsonText(addr, "pedestrian"),
                    jsonText(addr, "neighbourhood"),
                    jsonText(addr, "quarter"),
                    jsonText(addr, "village")
                )
                val area = buildString {
                    if (city.isNotBlank()) append(city)
                    if (district.isNotBlank() && !city.contains(district)) append(district)
                }
                val formatted = when {
                    area.isNotBlank() && road.isNotBlank() -> "$area · ${road}附近"
                    area.isNotBlank() -> area
                    road.isNotBlank() -> "${road}附近"
                    else -> null
                }
                formatted
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

        if (!result.isNullOrBlank()) {
            reverseGeocodeCache[cacheKey] = result
        }
        return result
    }

    private fun gcjToWgsApprox(lat: Double, lon: Double): Pair<Double, Double> {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return lat to lon
        val a = 6378245.0
        val ee = 0.00669342162296594323
        val x = lon - 105.0
        val y = lat - 35.0
        var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
        dLat += (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        dLat += (20.0 * kotlin.math.sin(y * Math.PI) + 40.0 * kotlin.math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        dLat += (160.0 * kotlin.math.sin(y / 12.0 * Math.PI) + 320.0 * kotlin.math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
        dLon += (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        dLon += (20.0 * kotlin.math.sin(x * Math.PI) + 40.0 * kotlin.math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        dLon += (150.0 * kotlin.math.sin(x / 12.0 * Math.PI) + 300.0 * kotlin.math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        val radLat = lat / 180.0 * Math.PI
        var magic = kotlin.math.sin(radLat)
        magic = 1.0 - ee * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1.0 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * kotlin.math.cos(radLat) * Math.PI)
        return (lat - dLat) to (lon - dLon)
    }

    /** Approximate WGS-84 -> GCJ-02 conversion for China map tiles & Chery head-unit navigation (943). */
    fun wgsToGcjApprox(lat: Double, lon: Double): Pair<Double, Double> {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return lat to lon
        val a = 6378245.0
        val ee = 0.00669342162296594323
        val x = lon - 105.0
        val y = lat - 35.0
        var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
        dLat += (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        dLat += (20.0 * kotlin.math.sin(y * Math.PI) + 40.0 * kotlin.math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        dLat += (160.0 * kotlin.math.sin(y / 12.0 * Math.PI) + 320.0 * kotlin.math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
        dLon += (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        dLon += (20.0 * kotlin.math.sin(x * Math.PI) + 40.0 * kotlin.math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        dLon += (150.0 * kotlin.math.sin(x / 12.0 * Math.PI) + 300.0 * kotlin.math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        val radLat = lat / 180.0 * Math.PI
        var magic = kotlin.math.sin(radLat)
        magic = 1.0 - ee * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1.0 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * kotlin.math.cos(radLat) * Math.PI)
        return (lat + dLat) to (lon + dLon)
    }

    /**
     * Searches Chinese/global POIs via OpenStreetMap Nominatim and returns GCJ-02 coordinates
     * directly compatible with Amap tiles and Chery head-unit navigation (`943`).
     */
    fun searchPoi(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null
    ): List<PoiSearchResult> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
        val viewboxPart = if (biasLat != null && biasLon != null && (biasLat != 0.0 || biasLon != 0.0)) {
            val (wLat, wLon) = gcjToWgsApprox(biasLat, biasLon)
            String.format(
                Locale.US,
                "&viewbox=%.4f,%.4f,%.4f,%.4f",
                wLon - 0.8,
                wLat + 0.8,
                wLon + 0.8,
                wLat - 0.8
            )
        } else {
            ""
        }
        return runCatching {
            val url = URL(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&q=$encoded&limit=8&accept-language=zh-CN&addressdetails=1$viewboxPart"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", "CarPlus/2.9 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching emptyList()
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val arr = JSONArray(body)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val wLat = obj.optString("lat").toDoubleOrNull() ?: continue
                        val wLon = obj.optString("lon").toDoubleOrNull() ?: continue
                        val (gLat, gLon) = wgsToGcjApprox(wLat, wLon)
                        val display = obj.optString("display_name").trim()
                        val name = obj.optString("name").trim().ifBlank {
                            display.substringBefore(",").trim().ifBlank { clean }
                        }
                        val address = display.ifBlank { name }
                        add(PoiSearchResult(name = name, address = address, lat = gLat, lon = gLon))
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Resolves a shared location text or map short-link (Amap / Baidu / WeChat / geo:) into
     * coordinates + place name when possible. Follows HTTP redirects on short map links.
     */
    fun resolveSharedUrlRedirect(urlString: String): String {
        val clean = urlString.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return clean
        return runCatching {
            var current = clean
            repeat(3) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("User-Agent", "CarPlus/2.9 (Android)")
                }
                try {
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location").orEmpty().trim()
                    if (code in 300..399 && loc.isNotBlank()) {
                        current = if (loc.startsWith("http://", true) || loc.startsWith("https://", true)) {
                            loc
                        } else {
                            URL(URL(current), loc).toString()
                        }
                    } else {
                        return@runCatching current
                    }
                } finally {
                    conn.disconnect()
                }
            }
            current
        }.getOrDefault(clean)
    }

    fun fetchTripHistoryByDay(
        vin: String,
        accessToken: String = ""
    ): List<TripDayGroup> = fetchTripHistoryDetailed(vin, accessToken).dayGroups

    fun fetchTripDates(vin: String, accessToken: String = ""): List<String> {
        val fromHistory = fetchTripHistoryByDay(vin, accessToken).map { it.dateKey }
        if (fromHistory.isNotEmpty()) return fromHistory

        val envelope = executeZyhlService(
            serviceType = "978",
            data = JSONObject().put("vin", vin),
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        return objectList(data.optJSONArray("list")).mapNotNull { item ->
            jsonText(item, "dateStr").takeIf { isValidTripDateToken(it) }
        }
    }

    fun fetchTripRecords(
        vin: String,
        searchDateId: String = "",
        accessToken: String = ""
    ): List<TripRecordItem> {
        val allGroups = fetchTripHistoryByDay(vin, accessToken)
        if (allGroups.isNotEmpty()) {
            if (searchDateId.isBlank()) {
                return allGroups.flatMap { it.trips }
            }
            allGroups.firstOrNull { it.dateKey == searchDateId || it.dateLabel == searchDateId }
                ?.let { return it.trips }
        }

        val requestData = JSONObject().apply {
            put("vin", vin)
            put("searchDateId", searchDateId)
        }
        val primaryEnvelope = executeZyhlService("979", requestData, accessToken)
        val primaryData = zyhlDataObject(primaryEnvelope)
        val primaryRecords = objectList(primaryData.optJSONArray("list")).map { parseTripRecord(it) }
        if (primaryRecords.isNotEmpty()) return primaryRecords

        val fallbackEnvelope = executeZyhlService(
            serviceType = "973",
            data = JSONObject().apply {
                put("vin", vin)
                put("searchDateId", "")
            },
            accessToken = accessToken
        )
        val fallbackData = zyhlDataObject(fallbackEnvelope)
        return objectList(fallbackData.optJSONArray("dateList")).asReversed().flatMap { dateItem ->
            val rawDate = firstNonBlank(jsonText(dateItem, "date"), jsonText(dateItem, "curDate"))
            objectList(dateItem.optJSONArray("list")).map { parseTripRecord(it, fallbackDate = rawDate) }
        }
    }

    /**
     * Binds/adds a vehicle to the current zyhl account via serviceType 930 (`DataReq930`).
     * Required: 17-char uppercase [vin], [sn], [iccid], and 6-digit [controlPin] (hashed via SHA-256).
     */
    fun bindVehicle(
        vin: String,
        sn: String,
        iccid: String,
        controlPin: String,
        licenseNum: String = "",
        hostName: String = "",
        purchaseDate: String = "",
        isDefault: Boolean = true,
        accessToken: String = ""
    ): BindVehicleResult {
        val normalizedVin = vin.trim().uppercase(Locale.US)
        val envelope = executeZyhlService(
            serviceType = "930",
            data = JSONObject().apply {
                put("vin", normalizedVin)
                put("sn", sn.trim())
                put("iccid", iccid.trim())
                put("controlPass", hashPasswordOrPin(controlPin.trim()))
                put("licenseNum", licenseNum.trim())
                put("hostName", hostName.trim())
                put("purchaseDate", purchaseDate.trim())
                put("isDefault", if (isDefault) "1" else "0")
            },
            accessToken = accessToken
        )
        val resultCode = jsonText(envelope, "resultCode")
        val message = firstNonBlank(
            jsonText(envelope, "resultMsg"),
            jsonText(envelope, "msg"),
            jsonText(envelope, "message")
        )
        val data = zyhlDataObject(envelope)
        val simStatus = jsonText(data, "simStatus")
        val ok = isSuccessCode(resultCode) || zyhlEnvelopeSucceeded(envelope)
        return BindVehicleResult(
            success = ok,
            resultCode = resultCode,
            message = if (ok) message.ifBlank { "车辆绑定成功" } else message.ifBlank { "车辆绑定失败 ($resultCode)" },
            simStatus = simStatus
        )
    }

    fun setTripTrackSwitch(
        vin: String,
        enabled: Boolean,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "127",
            data = JSONObject().apply {
                put("vin", vin)
                put("openTracks", if (enabled) "1" else "0")
                put("res01", "")
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun fetchMessages(
        page: Int = 1,
        pageSize: Int = 20,
        accessToken: String = ""
    ): List<ZyhlMessageItem> {
        val envelope = executeZyhlService(
            serviceType = "963",
            data = JSONObject().apply {
                put("pagination", page.toString())
                put("count", pageSize.toString())
            },
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        return objectList(data.optJSONArray("list")).map { item ->
            ZyhlMessageItem(
                functionId = jsonText(item, "functionId"),
                type = jsonText(item, "type"),
                typeDesc = jsonText(item, "typeDesc"),
                title = jsonText(item, "title"),
                description = jsonText(item, "description"),
                content = jsonText(item, "content"),
                messageTime = jsonText(item, "messageTime"),
                rawJson = item.toString()
            )
        }
    }

    fun fetchMessageDetail(
        functionId: String,
        type: String,
        accessToken: String = ""
    ): ZyhlMessageItem {
        val envelope = executeZyhlService(
            serviceType = "216",
            data = JSONObject().apply {
                put("functionId", functionId)
                put("type", type)
            },
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        val content = jsonText(data, "content")
        return ZyhlMessageItem(
            functionId = jsonText(data, "id").ifBlank { functionId },
            type = type,
            typeDesc = "",
            title = jsonText(data, "title"),
            description = content,
            content = content,
            messageTime = jsonText(data, "time"),
            url = jsonText(data, "url"),
            img = jsonText(data, "img"),
            rawJson = data.toString()
        )
    }

    fun fetchMaintenanceReminders(
        vin: String,
        page: Int = 1,
        pageSize: Int = 20,
        accessToken: String = ""
    ): List<ZyhlMessageItem> {
        val envelope = executeZyhlService(
            serviceType = "980",
            data = JSONObject().apply {
                put("vin", vin)
                put("pagination", page.toString())
                put("count", pageSize.toString())
            },
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        return objectList(data.optJSONArray("list")).map { item ->
            val description = jsonText(item, "remindDesc")
            ZyhlMessageItem(
                functionId = jsonText(item, "remindId"),
                type = "maintenance",
                typeDesc = "",
                title = description,
                description = description,
                content = description,
                messageTime = jsonText(item, "remindTime"),
                rawJson = item.toString()
            )
        }
    }

    fun fetchAntiTheftSettings(vin: String, accessToken: String = ""): AntiTheftStatus {
        val envelope = executeZyhlService(
            serviceType = "950",
            data = JSONObject().put("vin", vin),
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        return AntiTheftStatus(
            theftRemindEnabled = jsonBoolean(data, "theftRemind"),
            speedLimitEnabled = jsonBoolean(data, "speedLimited"),
            speedLimitKmh = jsonText(data, "speed"),
            rawJson = data.toString()
        )
    }

    fun fetchGeofences(vin: String, accessToken: String = ""): List<GeofenceItem> {
        val envelope = executeZyhlService(
            serviceType = "940",
            data = JSONObject().put("vin", vin),
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        return objectList(data.optJSONArray("fenceList")).map { item ->
            val fence = objectList(item.optJSONArray("fence")).firstOrNull()
            GeofenceItem(
                keyId = jsonText(item, "keyId"),
                name = jsonText(item, "name"),
                shape = jsonText(item, "shape"),
                status = jsonText(item, "status"),
                type = jsonText(item, "type"),
                lat = fence?.let { jsonText(it, "lat") }.orEmpty(),
                lon = fence?.let { jsonText(it, "lon") }.orEmpty(),
                radiusMeters = fence?.let { jsonText(it, "radius") }.orEmpty(),
                rawJson = item.toString()
            )
        }
    }

    fun saveGeofence(
        vin: String,
        name: String,
        lat: String,
        lon: String,
        radiusMeters: String = "1000",
        type: String = "1",
        shape: String = "1",
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "942",
            data = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("name", name)
                put("radius", if (shape == "1") "" else radiusMeters)
                put("shape", shape)
                put("type", type)
                put("length", 0)
                put(
                    "pointList",
                    JSONArray().put(
                        JSONObject().apply {
                            put("lat", lat)
                            put("lon", lon)
                            put("radius", radiusMeters)
                        }
                    )
                )
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun deleteOrToggleGeofence(
        keyId: String,
        type: String = "1",
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "941",
            data = JSONObject().apply {
                put("keyId", keyId)
                put("type", type)
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun fetchVehicleDiagnosis(vin: String, accessToken: String = ""): JSONObject {
        val envelope = executeZyhlService(
            serviceType = "961",
            data = JSONObject().put("vin", vin),
            accessToken = accessToken
        )
        return zyhlDataObject(envelope)
    }

    fun checkVehicleUpgrade(vin: String, accessToken: String = ""): VehicleUpgradeInfo {
        val envelope = executeZyhlService(
            serviceType = "148",
            data = JSONObject().put("vin", vin),
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        val upgradeTaskId = jsonText(data, "upgradeTaskId")
        val state = jsonText(data, "state")
        return VehicleUpgradeInfo(
            upgradeTaskId = upgradeTaskId,
            currentVersion = jsonText(data, "whlVsn"),
            state = state,
            releaseNote = jsonText(data, "rlsNt"),
            descriptionText = jsonText(data, "text"),
            upgradeTime = jsonText(data, "upgTm"),
            upgradeStartTime = jsonText(data, "upgradeStartTime"),
            hasUpgrade = upgradeTaskId.isNotBlank() || (state.isNotBlank() && state != "0"),
            rawJson = data.toString()
        )
    }

    fun fetchVehicleUpgradeStatus(
        vin: String,
        upgradeTaskId: String,
        accessToken: String = ""
    ): VehicleUpgradeInfo {
        val envelope = executeZyhlService(
            serviceType = "150",
            data = JSONObject().apply {
                put("vin", vin)
                put("upgradeTaskId", upgradeTaskId)
            },
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        val state = jsonText(data, "state")
        val percentage = jsonInt(data, "percentage")
        return VehicleUpgradeInfo(
            upgradeTaskId = upgradeTaskId,
            currentVersion = "",
            state = state,
            releaseNote = "",
            descriptionText = "",
            upgradeTime = "",
            upgradeStartTime = "",
            percentage = percentage,
            surplusTime = jsonText(data, "surplusTime"),
            hasUpgrade = state.isNotBlank() || percentage > 0,
            rawJson = data.toString()
        )
    }

    fun triggerVehicleUpgrade(
        vin: String,
        upgradeTaskId: String,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "152",
            data = JSONObject().apply {
                put("vin", vin)
                put("upgradeTaskId", upgradeTaskId)
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun scheduleVehicleUpgrade(
        vin: String,
        startTime: String,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "145",
            data = JSONObject().apply {
                put("vin", vin)
                put("time", startTime)
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun changeLoginPassword(
        oldPassword: String,
        newPassword: String,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "121",
            data = JSONObject().apply {
                put("oldPass", hashPasswordOrPin(oldPassword))
                put("newPass", hashPasswordOrPin(newPassword))
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun resetLoginPasswordBySms(
        mobileNo: String,
        smsCode: String,
        newPassword: String
    ): Boolean {
        val cleanMobile = mobileNo.trim()
        val cleanCode = smsCode.trim()
        val verifyResp = verifySmsCodeZyhlResponse(
            mobileNo = cleanMobile,
            smsCode = cleanCode,
            type = SMS_TYPE_FORGET_PASSWORD
        )
        if (!isSuccessCode(verifyResp.resultCode)) return false
        val response = postEnvelope(
            serviceType = "123",
            data = JSONObject().apply {
                put("telephone", cleanMobile)
                put("validateCode", cleanCode)
                put("newPassword", hashPasswordOrPin(newPassword))
            },
            globalId = ""
        )
        return isSuccessCode(response.resultCode)
    }

    fun changeVehicleControlPin(
        vin: String,
        oldPin: String,
        newPin: String,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "122",
            data = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("oldVehiclePass", hashPasswordOrPin(oldPin))
                put("newVehiclePass", hashPasswordOrPin(newPin))
                put("openGesturePass", "0")
            },
            accessToken = accessToken
        )
        val success = zyhlEnvelopeSucceeded(envelope)
        if (success) invalidatePinSessionForVin(vin)
        return success
    }

    /** Sets graphic gesture password via zyhl service 126 (DataReq126). */
    fun setVehicleControlPin(
        vin: String,
        newPin: String,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "126",
            data = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("password", hashPasswordOrPin(newPin))
                put("openGesturePass", "0")
            },
            accessToken = accessToken
        )
        val success = zyhlEnvelopeSucceeded(envelope)
        if (success) invalidatePinSessionForVin(vin)
        return success
    }

    /** Verifies vehicle VIN/SN/ICCID before resetting a forgotten PIN (zyhl 922). */
    fun verifyVehicleIdentityForReset(
        vin: String,
        sn: String,
        iccid: String,
        accessToken: String = ""
    ): Boolean {
        val response = postEnvelope(
            serviceType = "922",
            data = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("sn", sn.trim())
                put("iccid", iccid.trim())
            },
            globalId = resolveAuthToken(accessToken)
        )
        return response.resultCode == RESULT_SUCCESS
    }

    /** Resets a forgotten 6-digit vehicle PIN via 921(type="2") + 124 (DataReq124). */
    fun resetVehicleControlPinByVehicleInfo(
        vin: String,
        sn: String,
        iccid: String,
        telephone: String,
        validateCode: String,
        newPin: String,
        accessToken: String = ""
    ): Boolean {
        val cleanMobile = telephone.trim()
        val cleanCode = validateCode.trim()
        val verifyResp = verifySmsCodeZyhlResponse(
            mobileNo = cleanMobile,
            smsCode = cleanCode,
            type = SMS_TYPE_FORGET_PASSWORD
        )
        if (verifyResp.resultCode != RESULT_SUCCESS) return false
        val envelope = executeZyhlService(
            serviceType = "124",
            data = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("sn", sn.trim())
                put("iccid", iccid.trim())
                put("telephone", cleanMobile)
                put("validateCode", cleanCode)
                put("password", hashPasswordOrPin(newPin.trim()))
            },
            accessToken = accessToken
        )
        val success = zyhlEnvelopeSucceeded(envelope)
        if (success) invalidatePinSessionForVin(vin)
        return success
    }

    /** Updates one cloud user profile field via zyhl 113 (0=nickname, 1=sex, 2=email, 3=region). */
    fun updateUserProfileField(
        updateType: String,
        content: String,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "113",
            data = JSONObject().apply {
                put("updateType", updateType.trim())
                put("content", content.trim())
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    /** Sets or disables speed-limit reminder via zyhl 970 instruction 1203 (4005: 0=off, 60..120=km/h). */
    fun setSpeedLimitReminder(
        vin: String,
        speedKmh: Int,
        pin: String,
        accessToken: String = ""
    ): Boolean {
        val payload = JSONObject().apply {
            put("vin", vin.trim().uppercase(Locale.US))
            put("instruction", "1203")
            put("paras", JSONObject().put("4005", if (speedKmh <= 0) 0 else speedKmh.coerceIn(60, 120)))
            put("passwordType", PASSWORD_TYPE_VEHICLE)
            if (pin.isNotBlank()) put("safePasswd", hashPasswordOrPin(pin))
        }
        val env = executeZyhlService("970", payload, accessToken)
        return is970CommandAccepted(env)
    }

    /** Validates zyhl 970 responses: outer resultCode == "0200" AND data.result == "1" (when present). */
    fun is970CommandAccepted(envelope: JSONObject): Boolean {
        val code = jsonText(envelope, "resultCode").ifBlank { jsonText(envelope, "code") }
        if (code != RESULT_SUCCESS) return false
        val data = zyhlDataObject(envelope)
        val innerResult = jsonText(data, "result")
        return innerResult.isEmpty() || innerResult == "1"
    }

    /** Toggles cabin auto air-ventilation via zyhl 975 (DataReq975). */
    fun setAutoAirVentilation(
        vin: String,
        enabled: Boolean,
        accessToken: String = ""
    ): Boolean {
        val env = executeZyhlService(
            serviceType = "975",
            data = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("openAir", if (enabled) "1" else "0")
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(env)
    }

    /** Queries vehicle connected data-traffic package via zyhl 967 (DataResp967). */
    fun fetchVehicleTrafficPackage(
        vin: String,
        accessToken: String = ""
    ): VehicleTrafficInfo {
        val env = executeZyhlService(
            serviceType = "967",
            data = JSONObject().put("vin", vin.trim().uppercase(Locale.US)),
            accessToken = accessToken
        )
        val data = zyhlDataObject(env)
        return VehicleTrafficInfo(
            usedTraffic = jsonText(data, "usedTraffic"),
            totalTraffic = jsonText(data, "totalTraffic"),
            unlimited = jsonText(data, "unlimited") == "1",
            packageState = jsonText(data, "packageState"),
            dueDate = jsonText(data, "dueDate"),
            rawJson = data.toString()
        )
    }

    /** Queries EV/PHEV scheduled charging entries via zyhl 974 (DataResp974). */
    fun fetchChargingSchedules(
        vin: String,
        accessToken: String = ""
    ): ChargingScheduleSummary {
        val env = executeZyhlService(
            serviceType = "974",
            data = JSONObject().put("vin", vin.trim().uppercase(Locale.US)),
            accessToken = accessToken
        )
        val data = zyhlDataObject(env)
        val items = objectList(data.optJSONArray("chargeAppintlist")).map { item ->
            val days = objectList(item.optJSONArray("dateList")).mapNotNull { d ->
                jsonText(d, "date").toIntOrNull()?.takeIf { it in 1..7 }
            }.distinct().sorted()
            ChargingScheduleItem(
                chargeId = jsonText(item, "chargeId"),
                startTime = jsonText(item, "beStarttime"),
                durationHours = jsonText(item, "chargingTime").toIntOrNull()?.coerceIn(1, 24) ?: 2,
                days = days,
                enabled = jsonText(item, "chargeAppintStatus") == "1",
                rawJson = item.toString()
            )
        }
        return ChargingScheduleSummary(
            masterEnabled = jsonText(data, "chargeAppintStatus") == "1",
            items = items,
            rawJson = data.toString()
        )
    }

    /** Saves EV/PHEV charging schedules via zyhl 970 instruction 1110 (3A04 + 3A19). */
    fun saveChargingSchedules(
        vin: String,
        masterEnabled: Boolean,
        schedules: List<ChargingScheduleItem>,
        pin: String,
        accessToken: String = ""
    ): Boolean {
        val dayCodes = arrayOf("01", "02", "03", "04", "05", "06", "07")
        val listArr = JSONArray()
        schedules.forEach { item ->
            val mins = parseHhMmToMinutes(item.startTime)
            if (mins >= 0) {
                val entry = JSONObject().apply {
                    put("3A05", mins)
                    put("3A1A", item.durationHours.coerceIn(1, 24))
                    val daysArr = JSONArray()
                    item.days.filter { it in 1..7 }.distinct().sorted().forEach { d ->
                        daysArr.put(dayCodes[d - 1])
                    }
                    put("3A1B", daysArr)
                    put("3A04", if (item.enabled) 1 else 0)
                }
                listArr.put(entry)
            }
        }
        val payload = JSONObject().apply {
            put("vin", vin.trim().uppercase(Locale.US))
            put("instruction", "1110")
            put(
                "paras",
                JSONObject().apply {
                    put("3A04", if (masterEnabled) 1 else 0)
                    put("3A19", listArr)
                }
            )
            put("passwordType", PASSWORD_TYPE_VEHICLE)
            if (pin.isNotBlank()) put("safePasswd", hashPasswordOrPin(pin))
        }
        val env = executeZyhlService("970", payload, accessToken)
        return is970CommandAccepted(env)
    }

    /** Queries departure/travel pre-conditioning schedules via zyhl 143 (DataResp143). */
    fun fetchTravelSchedules(
        vin: String,
        accessToken: String = ""
    ): TravelScheduleSummary {
        val env = executeZyhlService(
            serviceType = "143",
            data = JSONObject().put("vin", vin.trim().uppercase(Locale.US)),
            accessToken = accessToken
        )
        val data = zyhlDataObject(env)
        val items = objectList(data.optJSONArray("travellist")).map { item ->
            TravelScheduleItem(
                startTime = firstNonBlank(jsonText(item, "travelStartTime"), jsonText(item, "travelStart")),
                enabled = jsonText(item, "travelStatus") == "1",
                rawJson = item.toString()
            )
        }
        val master = jsonText(data, "travelStatus") == "1" || jsonText(data, "status") == "1"
        return TravelScheduleSummary(
            masterEnabled = master,
            items = items,
            rawJson = data.toString()
        )
    }

    /** Saves departure/travel pre-conditioning schedule via zyhl 970 instruction 1111 (0002 + 3010 + 302E). */
    fun saveTravelSchedule(
        vin: String,
        enabled: Boolean,
        startTimeHhMm: String,
        airType: Int = 1,
        pin: String,
        accessToken: String = ""
    ): Boolean {
        val mins = parseHhMmToMinutes(startTimeHhMm)
        val entry = JSONObject().apply {
            if (mins >= 0) put("302D", mins)
            put("0002", if (enabled) 1 else 0)
        }
        val payload = JSONObject().apply {
            put("vin", vin.trim().uppercase(Locale.US))
            put("instruction", "1111")
            put(
                "paras",
                JSONObject().apply {
                    put("0002", if (enabled) 1 else 0)
                    put("3010", airType.coerceIn(0, 1))
                    put("302E", JSONArray().put(entry))
                }
            )
            put("passwordType", PASSWORD_TYPE_VEHICLE)
            if (pin.isNotBlank()) put("safePasswd", hashPasswordOrPin(pin))
        }
        val env = executeZyhlService("970", payload, accessToken)
        return is970CommandAccepted(env)
    }

    /** Sends a navigation POI destination to the vehicle head-unit via zyhl 943. */
    fun sendPoiToVehicle(
        vin: String,
        name: String,
        address: String,
        lat: String,
        lon: String,
        telephone: String = "",
        accessToken: String = ""
    ): Boolean {
        val fmtLat = lat.trim().toDoubleOrNull()?.let { String.format(Locale.US, "%.6f", it) } ?: lat.trim()
        val fmtLon = lon.trim().toDoubleOrNull()?.let { String.format(Locale.US, "%.6f", it) } ?: lon.trim()
        val timeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(java.util.Date())
        val env = executeZyhlService(
            serviceType = "943",
            data = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("name", name.trim())
                put("address", address.trim())
                put("latitude", fmtLat)
                put("longitude", fmtLon)
                put("poiid", " ")
                put("telephone", telephone.trim())
                put("time", timeFmt)
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(env)
    }

    private fun parseHhMmToMinutes(hhMm: String): Int {
        val parts = hhMm.trim().split(":")
        if (parts.size < 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    /** Queries remote authorizations through zyhl service 901. */
    fun queryRemoteAuthorizations(
        accessToken: String = "",
        vin: String
    ): List<VehicleAuthorizationItem> {
        require(vin.isNotBlank()) { "vin is required" }
        val resolvedVin = vin.trim().uppercase(Locale.US)
        val response = postEnvelope(
            serviceType = "901",
            data = JSONObject().put("vin", resolvedVin),
            globalId = resolveAuthToken(accessToken)
        )
        if (response.resultCode == RESULT_EMPTY_DATA) return emptyList()
        requireSuccess(response, "901")

        val data = response.dataObject()
        val array = data.optJSONArray("list")
            ?: data.optJSONArray("authorizeList")
            ?: data.optJSONArray("vehicleList")
            ?: response.envelope.optJSONArray("data")
            ?: JSONArray()
        return objectList(array).map { item -> parseRemoteAuthorization(item, resolvedVin) }
    }

    /** Creates a remote authorization through zyhl service 902. */
    fun createRemoteAuthorization(
        accessToken: String = "",
        vin: String,
        mobile: String,
        name: String,
        startTime: String,
        endTime: String,
        authority: String = "1",
        pinPlain: String = ""
    ): Boolean {
        require(vin.isNotBlank()) { "vin is required" }
        val levelFlag = when {
            authority.trim() == "1" || authority.trim() == "1,2" ||
                authority.trim() == "2" || authority.contains("控") -> "1"
            authority.trim() == "0" -> "0"
            else -> "0"
        }
        val response = postEnvelope(
            serviceType = "902",
            data = JSONObject().apply {
                put("receiverMobile", mobile.trim())
                put("password", hashPasswordOrPin(pinPlain.trim()))
                put("starttime", startTime.trim())
                put("endtime", endTime.trim())
                put("levelFlag", levelFlag)
                put("vin", vin.trim().uppercase(Locale.US))
                put("receiverAccountName", name.trim())
            },
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "新增远程授权(902)")
        return true
    }

    /** Updates a remote authorization through zyhl service 903. */
    fun updateRemoteAuthorization(
        accessToken: String = "",
        keyId: String,
        vin: String = "",
        mobile: String = "",
        name: String,
        startTime: String,
        endTime: String,
        authority: String = "",
        status: String = "0",
        pinPlain: String = ""
    ): Boolean {
        require(keyId.isNotBlank()) { "keyId is required" }
        val response = postEnvelope(
            serviceType = "903",
            data = JSONObject().apply {
                put("starttime", startTime.trim())
                put("endtime", endTime.trim())
                put("keyId", keyId.trim())
                put("receiverAccountName", name.trim())
            },
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "编辑远程授权(903)")
        return true
    }

    /** Enables or disables a remote authorization through zyhl service 905. */
    fun toggleRemoteAuthorization(
        accessToken: String = "",
        keyId: String,
        enabled: Boolean,
        vin: String = ""
    ): Boolean {
        require(keyId.isNotBlank()) { "keyId is required" }
        val response = postEnvelope(
            serviceType = "905",
            data = JSONObject().apply {
                put("keyId", keyId.trim())
                put("status", if (enabled) "0" else "1")
            },
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "切换远程授权状态(905)")
        return true
    }

    /** Revokes a remote authorization through zyhl service 904. */
    fun deleteRemoteAuthorization(
        accessToken: String = "",
        keyId: String,
        vin: String = "",
        pinPlain: String = ""
    ): Boolean {
        require(keyId.isNotBlank()) { "keyId is required" }
        val response = postEnvelope(
            serviceType = "904",
            data = JSONObject().apply {
                put("keyId", keyId.trim())
                put("status", "1")
            },
            globalId = resolveAuthToken(accessToken)
        )
        requireCommandSuccess(response, "撤销远程授权(904)")
        return true
    }

    fun revokeRemoteAuthorization(
        accessToken: String = "",
        keyId: String,
        vin: String = "",
        pinPlain: String = ""
    ): Boolean = deleteRemoteAuthorization(accessToken, keyId, vin, pinPlain)

    /** Queries remote-authorization operation records through zyhl service 137. */
    fun queryAuthorizationOperationRecords(
        accessToken: String = "",
        vin: String,
        subAccountId: String,
        page: Int = 1,
        count: Int = 20
    ): List<AuthorizationOperationRecordItem> {
        require(vin.isNotBlank()) { "vin is required" }
        val resolvedVin = vin.trim().uppercase(Locale.US)
        val response = postEnvelope(
            serviceType = "137",
            data = JSONObject().apply {
                put("subAccountId", subAccountId.trim())
                put("pagination", page.toString())
                put("count", count.toString())
                put("vin", resolvedVin)
            },
            globalId = resolveAuthToken(accessToken)
        )
        if (response.resultCode == RESULT_EMPTY_DATA) return emptyList()
        requireSuccess(response, "137")
        return objectList(response.dataObject().optJSONArray("list")).map { item ->
            AuthorizationOperationRecordItem(
                action = jsonText(item, "action"),
                time = jsonText(item, "time"),
                rawJson = item.toString()
            )
        }
    }

    fun fetchBleAuthList(vin: String, accessToken: String = ""): List<VehicleAuthorizationItem> =
        queryRemoteAuthorizations(accessToken, vin)

    fun grantBleAuth(
        vin: String,
        receiverMobile: String,
        receiverName: String,
        startTime: String,
        endTime: String,
        passwordOrPin: String,
        levelFlag: String = "1",
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "902",
            data = JSONObject().apply {
                put("vin", vin)
                put("receiverMobile", receiverMobile)
                put("receiverAccountName", receiverName)
                put("starttime", startTime)
                put("endtime", endTime)
                put("levelFlag", levelFlag)
                put("password", hashPasswordOrPin(passwordOrPin))
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun updateBleAuth(
        keyId: String,
        receiverName: String,
        startTime: String,
        endTime: String,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "903",
            data = JSONObject().apply {
                put("keyId", keyId)
                put("receiverAccountName", receiverName)
                put("starttime", startTime)
                put("endtime", endTime)
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun revokeBleAuth(
        keyId: String,
        status: String = "1",
        accessToken: String = ""
    ): Boolean {
        val requestData = JSONObject().apply {
            put("keyId", keyId)
            put("status", status)
        }
        val primary = runCatching {
            executeZyhlService("904", requestData, accessToken)
        }.getOrNull()
        if (primary != null && zyhlEnvelopeSucceeded(primary)) return true
        val fallback = executeZyhlService("905", requestData, accessToken)
        return zyhlEnvelopeSucceeded(fallback)
    }

    fun fetchRemoteAuthList(
        vin: String,
        accessToken: String = ""
    ): List<VehicleAuthorizationItem> {
        val primaryEnvelope = executeZyhlService(
            serviceType = "130",
            data = JSONObject().put("vin", vin),
            accessToken = accessToken
        )
        val primaryData = zyhlDataObject(primaryEnvelope)
        val primary = objectList(primaryData.optJSONArray("list"))
            .map { item -> parseRemoteAuthorization(item) }
        if (primary.isNotEmpty()) return primary

        val fallbackEnvelope = executeZyhlService(
            serviceType = "1003",
            data = JSONObject().apply {
                put("vin", vin)
                put("account", "")
                put("searchType", "0")
            },
            accessToken = accessToken
        )
        val fallbackData = zyhlDataObject(fallbackEnvelope)
        return objectList(fallbackData.optJSONArray("authList"))
            .map { item -> parseRemoteAuthorization(item) }
    }

    fun fetchRemoteAuthDetail(
        keyId: String,
        accessToken: String = ""
    ): VehicleAuthorizationItem {
        val envelope = executeZyhlService(
            serviceType = "132",
            data = JSONObject().put("keyId", keyId),
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        val username = jsonText(data, "username")
        return VehicleAuthorizationItem(
            keyId = keyId,
            authType = "",
            receiverName = username,
            receiverMobile = username,
            startTime = jsonText(data, "dateStart"),
            endTime = jsonText(data, "dateUntil"),
            status = "",
            authority = jsonText(data, "authority"),
            authorityDesc = jsonText(data, "authorityDesc"),
            rawJson = data.toString()
        )
    }

    fun grantRemoteAuth(
        vin: String,
        receiverMobile: String,
        startTime: String,
        endTime: String,
        pin: String,
        authority: String = "1,2,3",
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "133",
            data = JSONObject().apply {
                put("vin", vin)
                put("username", receiverMobile)
                put("dateStart", startTime)
                put("dateUntil", endTime)
                put("authority", authority)
                put("controlPass", hashPasswordOrPin(pin))
                put("password", hashPasswordOrPin(pin))
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun updateRemoteAuth(
        vin: String,
        subAccountId: String,
        startTime: String,
        endTime: String,
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "134",
            data = JSONObject().apply {
                put("vin", vin)
                put("subAccountId", subAccountId)
                put("dateStart", startTime)
                put("dateUntil", endTime)
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun revokeRemoteAuth(
        vin: String,
        keyId: String,
        pin: String,
        status: String = "0",
        accessToken: String = ""
    ): Boolean {
        val envelope = executeZyhlService(
            serviceType = "131",
            data = JSONObject().apply {
                put("vin", vin)
                put("keyId", keyId)
                put("status", status)
                put("safePasswd", hashPasswordOrPin(pin))
                put("openGesturePass", "0")
            },
            accessToken = accessToken
        )
        return zyhlEnvelopeSucceeded(envelope)
    }

    fun fetchAuthorizedOperateRecords(
        vin: String,
        subAccountId: String = "",
        page: Int = 1,
        pageSize: Int = 20,
        accessToken: String = ""
    ): List<JSONObject> {
        val envelope = executeZyhlService(
            serviceType = "137",
            data = JSONObject().apply {
                put("vin", vin)
                put("subAccountId", subAccountId)
                put("pagination", page.toString())
                put("count", pageSize.toString())
            },
            accessToken = accessToken
        )
        val data = zyhlDataObject(envelope)
        return objectList(data.optJSONArray("list"))
    }

    private fun zyhlDataObject(envelope: JSONObject): JSONObject {
        val raw = envelope.opt("data") ?: return JSONObject()
        return when (raw) {
            is JSONObject -> raw
            is String -> raw.trim().takeUnless { it.isBlank() || it == "null" }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
            else -> JSONObject()
        }
    }

    private fun objectList(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        val result = mutableListOf<JSONObject>()
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(result::add)
        }
        return result
    }

    private fun jsonText(json: JSONObject, key: String): String {
        val raw = json.opt(key) ?: return ""
        if (raw == JSONObject.NULL) return ""
        return raw.toString().trim().takeUnless { it == "null" }.orEmpty()
    }

    private fun jsonBoolean(json: JSONObject, key: String): Boolean =
        when (val raw = json.opt(key)) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().lowercase(Locale.US) in setOf("1", "true", "yes", "on")
            else -> false
        }

    private fun jsonInt(json: JSONObject, key: String): Int =
        when (val raw = json.opt(key)) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: 0
            else -> 0
        }

    private fun parseGpsTrackPoints(array: JSONArray?): List<GpsTrackPoint> {
        if (array == null) return emptyList()
        val result = mutableListOf<GpsTrackPoint>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val lat = firstDouble(item, "lat") ?: continue
            val lon = firstDouble(item, "lon", "lng") ?: continue
            result.add(GpsTrackPoint(lat = lat, lon = lon))
        }
        return result
    }

    private fun parseTripRecord(item: JSONObject, fallbackDate: String = ""): TripRecordItem = TripRecordItem(
        startTime = jsonText(item, "startTime"),
        endTime = jsonText(item, "endTime"),
        mileageKm = jsonText(item, "mileage"),
        durationOrDistance = jsonText(item, "distance"),
        startAddress = jsonText(item, "start_Address").ifBlank {
            jsonText(item, "startAddress")
        },
        endAddress = jsonText(item, "end_Address").ifBlank {
            jsonText(item, "endAddress")
        },
        avgSpeed = jsonText(item, "avgSpeed"),
        maxSpeed = jsonText(item, "maxSpeed"),
        fuelConsumption = jsonText(item, "fuelConsumption"),
        curDate = jsonText(item, "curDate").ifBlank { fallbackDate },
        points = parseGpsTrackPoints(item.optJSONArray("points")),
        rawJson = item.toString()
    )

    private fun parseBleAuthorization(item: JSONObject): VehicleAuthorizationItem {
        val receiverAccountId = firstNonBlank(
            jsonText(item, "receiverAccountId"),
            jsonText(item, "subAccountId"),
            jsonText(item, "userId")
        )
        val receiverAccountName = firstNonBlank(
            jsonText(item, "receiverAccountName"),
            jsonText(item, "userName"),
            jsonText(item, "name")
        )
        val receiverMobile = firstNonBlank(
            jsonText(item, "receiverMobile"),
            jsonText(item, "telephone"),
            jsonText(item, "mobile")
        )
        val levelFlag = firstNonBlank(jsonText(item, "levelFlag"), jsonText(item, "authority"), "0")
        return VehicleAuthorizationItem(
            keyId = jsonText(item, "keyId"),
            authType = jsonText(item, "authType").ifBlank { levelFlag },
            receiverName = receiverAccountName,
            receiverMobile = receiverMobile,
            startTime = firstNonBlank(jsonText(item, "starttime"), jsonText(item, "startTime")),
            endTime = firstNonBlank(jsonText(item, "endtime"), jsonText(item, "endTime")),
            status = firstNonBlank(jsonText(item, "status"), "0"),
            levelFlag = levelFlag,
            authority = jsonText(item, "authority"),
            authorityDesc = jsonText(item, "authorityDesc"),
            rawJson = item.toString(),
            vin = jsonText(item, "vin"),
            authorizedUserId = receiverAccountId,
            authorizedName = receiverAccountName,
            authorizedMobile = receiverMobile
        )
    }

    private fun parseRemoteAuthorization(
        item: JSONObject,
        fallbackVin: String = ""
    ): VehicleAuthorizationItem {
        val receiverAccountId = firstNonBlank(
            jsonText(item, "receiverAccountId"),
            jsonText(item, "subAccountId"),
            jsonText(item, "userId")
        )
        val receiverAccountName = firstNonBlank(
            jsonText(item, "receiverAccountName"),
            jsonText(item, "userName"),
            jsonText(item, "name")
        )
        val receiverMobile = firstNonBlank(
            jsonText(item, "receiverMobile"),
            jsonText(item, "telephone"),
            jsonText(item, "mobile")
        )
        val authority = jsonText(item, "authority")
        val levelFlag = firstNonBlank(jsonText(item, "levelFlag"), authority, "0")
        return VehicleAuthorizationItem(
            keyId = firstNonBlank(jsonText(item, "keyId"), jsonText(item, "subAccountId")),
            authType = firstNonBlank(jsonText(item, "authType"), jsonText(item, "type"), levelFlag),
            receiverName = receiverAccountName,
            receiverMobile = receiverMobile,
            startTime = firstNonBlank(
                jsonText(item, "starttime"),
                jsonText(item, "startTime"),
                jsonText(item, "dateStart")
            ),
            endTime = firstNonBlank(
                jsonText(item, "endtime"),
                jsonText(item, "endTime"),
                jsonText(item, "dateUntil")
            ),
            status = firstNonBlank(jsonText(item, "status"), "0"),
            levelFlag = levelFlag,
            authority = authority,
            authorityDesc = jsonText(item, "authorityDesc"),
            rawJson = item.toString(),
            vin = firstNonBlank(jsonText(item, "vin"), fallbackVin),
            authorizedUserId = receiverAccountId,
            authorizedName = receiverAccountName,
            authorizedMobile = receiverMobile
        )
    }

    private fun zyhlEnvelopeSucceeded(envelope: JSONObject): Boolean {
        val resultCode = envelope.optString("resultCode").trim()
        if (resultCode == RESULT_SUCCESS || resultCode == "200" || resultCode == "0") return true
        if (resultCode.isNotEmpty()) return false
        val success = envelope.opt("success")
        if (success is Boolean && success) return true
        if (success is String && success.equals("true", ignoreCase = true)) return true
        if (envelope.optInt("status", -1) == 200) return true
        val code = envelope.opt("code")
        return (code is Number && code.toInt() == 0) ||
            (code is String && code.trim() == "0")
    }

    private fun resolveDeviceId(requested: String): String =
        requested.trim().ifBlank { defaultDeviceId.trim() }

    private fun resolveAuthToken(explicit: String): String =
        explicit.trim().ifBlank { sessionGlobalId }

    private fun resolveGlobalId(explicit: String): String =
        firstNonBlank(explicit, sessionGlobalId, sessionAccessToken)

    private fun updateSessionFrom(response: ZyhlResponse, requestGlobalId: String = ""): String {
        val globalId = response.envelope.optString("globalId").trim()
        val reqId = requestGlobalId.trim()
        val curId = sessionGlobalId.trim()
        val canAdopt = reqId.isBlank() || curId.isBlank() || reqId == curId
        if (globalId.isNotBlank() && canAdopt) {
            sessionGlobalId = globalId
        }
        return globalId
    }

    private fun resolveUserAccessToken(explicit: String): String =
        explicit.trim().ifBlank { sessionAccessToken }.ifBlank { sessionGlobalId }

    private fun postEnvelope(
        serviceType: String,
        data: JSONObject,
        globalId: String
    ): ZyhlResponse {
        if (!isUnauthenticatedService(serviceType)) {
            require(globalId.isNotBlank()) { "accessToken/globalId is required for service $serviceType" }
        }
        val envelope = buildRequestEnvelope(serviceType, data, globalId)
        val body = envelope.toString()
        var lastError: Exception? = null
        val attempts = endpointBases()

        for (base in attempts) {
            val url = endpointUrl(base)
            try {
                val http = postJson(url, body)
                if (http.statusCode !in 200..299) {
                    throw IOException(
                        "HTTP ${http.statusCode} from ${URL(url).host}: ${http.body.take(300)}"
                    )
                }
                val responseJson = JSONObject(http.body)
                val response = ZyhlResponse(
                    envelope = responseJson,
                    rawBody = http.body,
                    gateway = base
                )
                throwIfSessionExpired(response, serviceType, globalId)
                updateSessionFrom(response, globalId)
                return response
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                if (error is CherySessionExpiredException) throw error
                lastError = error
            }
        }

        throw IOException(
            "智云互联网关请求失败（已尝试 ${attempts.size} 个入口：" +
                attempts.joinToString() + "）",
            lastError
        )
    }

    private fun postJson(url: String, body: String): HttpResponse =
        httpRequest("POST", url, body)

    private fun httpRequest(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val parsedUrl = URL(url)
        val connection = parsedUrl.openConnection() as HttpURLConnection
        connection.requestMethod = method.uppercase(Locale.US)
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.doOutput = body != null
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", userAgent)
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

        if (connection is HttpsURLConnection &&
            parsedUrl.host.equals(DEFAULT_PRIMARY_HOST, ignoreCase = true) &&
            parsedUrl.port == DEFAULT_PRIMARY_PORT
        ) {
            connection.sslSocketFactory = permissiveSslSocketFactory
            connection.hostnameVerifier = permissiveHostnameVerifier
        }

        try {
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { stream -> stream.write(bytes) }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { reader -> reader.readText() }
                .orEmpty()
            return HttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun endpointBases(): List<String> = listOf(baseUrl, fallbackBaseUrl)
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.US) }

    private fun endpointUrl(base: String): String {
        val normalized = base.trim().trimEnd('/')
        return if (normalized.endsWith(COMMAND_PATH, ignoreCase = true)) {
            normalized
        } else {
            "$normalized$COMMAND_PATH"
        }
    }

    private fun requireSuccess(response: ZyhlResponse, serviceType: String) {
        throwIfSessionExpired(response, serviceType)
        val code = response.resultCode
        when {
            isSuccessCode(code) -> Unit
            code == RESULT_SESSION_EXPIRED && !isUnauthenticatedService(serviceType) ->
                throw CherySessionExpiredException()
            code == RESULT_DEVICE_BIND_REQUIRED -> throw CheryDeviceBindingRequiredException(
                message = "服务 $serviceType 要求完成新设备短信绑定（107 + 108）"
            )
            else -> error(
                "智云互联 $serviceType 失败" +
                    (if (code.isBlank()) "" else "[$code]") +
                    "：${response.message.ifBlank { "服务端未返回错误说明" }}"
            )
        }
    }

    private fun requireCommandSuccess(response: ZyhlResponse, serviceType: String) {
        requireSuccess(response, serviceType)
        if (response.resultCode != RESULT_SUCCESS) {
            error(
                "智云互联 $serviceType 失败[${response.resultCode}]：" +
                    response.message.ifBlank { "服务端未返回成功结果" }
            )
        }
    }

    private fun throwIfSessionExpired(
        response: ZyhlResponse,
        serviceType: String,
        requestGlobalId: String = ""
    ) {
        if (isUnauthenticatedService(serviceType)) return
        if (!isSessionExpired(response.resultCode, response.message)) return
        val currentGlobalId = sessionGlobalId.trim()
        val reqGlobalId = requestGlobalId.trim()
        if (currentGlobalId.isNotBlank() && reqGlobalId.isNotBlank() && reqGlobalId != currentGlobalId) {
            // Stale in-flight request from an old session: do not nuke the newly established session.
            throw CherySessionExpiredException(response.resultCode.ifBlank { RESULT_SESSION_EXPIRED }, "旧会话已失效")
        }
        clearSession()
        val msg = response.message.ifBlank { "登录已失效，请重新登录" }
        runCatching { onSessionExpired?.invoke(msg) }
        throw CherySessionExpiredException(response.resultCode.ifBlank { RESULT_SESSION_EXPIRED }, msg)
    }

    private fun isUnauthenticatedService(serviceType: String): Boolean =
        serviceType.trim() in UNAUTHENTICATED_SERVICE_TYPES

    private fun isAlreadyZyhlHash(value: String): Boolean {
        if (value.length != ZYHL_HASH_BASE64_LENGTH || !value.endsWith("==")) return false
        val decoded = runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull() ?: return false
        if (decoded.size != SHA256_HEX_LENGTH) return false
        val text = String(decoded, Charsets.UTF_8)
        return text.length == SHA256_HEX_LENGTH && text.matches(Regex("[0-9a-f]{64}"))
    }

    private fun parseDoorLocked(value: String): Boolean? = when (value.normalizedState()) {
        "0", "locked", "close", "closed", "true" -> true
        "1", "unlocked", "open", "false" -> false
        "3", "255", "0xff", "" -> null
        else -> null
    }

    private fun parseTrunkOpen(
        trunkDoor: String,
        liftgateState: String,
        liftgateOperateState: String
    ): Boolean? = when {
        trunkDoor.isNotBlank() -> when (trunkDoor.normalizedState()) {
            "1" -> true
            "0", "3", "255", "0xff" -> false
            else -> null
        }
        liftgateState.isNotBlank() -> parseLiftgateState(liftgateState) == "open"
        liftgateOperateState.isNotBlank() -> parseLiftgateState(liftgateOperateState) == "open"
        else -> null
    }

    private fun parseLiftgateState(value: String): String = when (value.normalizedState()) {
        "1", "100", "open" -> "open"
        "0", "2", "0xff", "255", "close", "closed", "" -> "closed"
        else -> ""
    }

    private fun parseEngineRunning(value: String): Boolean? = when (value.normalizedState()) {
        "1", "2", "3", "run", "running", "on", "true" -> true
        "0", "255", "0xff", "off", "stop", "stopped", "false" -> false
        else -> null
    }

    private fun parseAcOn(value: String): Boolean? = when (value.normalizedState()) {
        "1", "on", "open", "true" -> true
        "0", "255", "0xff", "off", "close", "closed", "false" -> false
        else -> null
    }

    private fun parseWindowState(value: String): String = when (value.normalizedState()) {
        "0", "2", "8", "255", "0xff" -> "closed"
        "1", "9" -> "ventilate"
        "3", "10", "11", "12", "13", "14", "15" -> "open"
        else -> ""
    }

    private fun parseSunroofState(state: String, operateState: String): String {
        val normalizedState = state.normalizedState()
        when (normalizedState) {
            "0", "1", "255", "0xff", "close", "closed" -> return "closed"
            "12", "cock", "tilt" -> return "tilt"
            "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
            "13", "14", "15", "16", "open" -> return "open"
        }
        return when (operateState.normalizedState()) {
            "1", "8", "12", "cock", "tilt" -> "tilt"
            "3", "6", "7", "open" -> "open"
            else -> ""
        }
    }

    private fun String.normalizedState(): String = trim().lowercase(Locale.US)

    private fun firstDouble(json: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val raw = json.opt(key)
            if (raw == null || raw == JSONObject.NULL) continue
            raw.toString().trim().toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun isSuccessCode(code: String): Boolean =
        code == RESULT_SUCCESS || code == RESULT_EMPTY_DATA

    private fun isDeviceBindingMessage(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("设备") ||
            normalized.contains("绑定") ||
            normalized.contains("device") ||
            normalized.contains("bind")
    }

    private val permissiveSslSocketFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), null)
        context.socketFactory
    }

    private val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

    private data class CommandSpec(
        val instruction: String,
        val paras: JSONObject,
        val isSetTemp: String? = null
    )

    private enum class CloudBackend {
        UNKNOWN,
        CHERY,
        ZYHL
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String
    )

    private data class ZyhlResponse(
        val envelope: JSONObject,
        val rawBody: String,
        val gateway: String
    ) {
        val resultCode: String
            get() = envelope.optString("resultCode").trim()

        val resultMessage: String
            get() = firstNonBlank(
                envelope.optString("resultMsg"),
                envelope.optString("resultMessage"),
                envelope.optString("message"),
                envelope.optString("error")
            )

        val resultMsg: String
            get() = resultMessage

        val message: String
            get() = resultMessage

        fun dataObjectOrNull(): JSONObject? {
            val raw = envelope.opt("data") ?: return null
            return when (raw) {
                is JSONObject -> raw
                is String -> {
                    val trimmed = raw.trim()
                    if (trimmed.isBlank() || trimmed == "null") null
                    else runCatching { JSONObject(trimmed) }.getOrNull()
                }
                JSONObject.NULL -> null
                else -> null
            }
        }

        fun dataObject(): JSONObject = dataObjectOrNull() ?: JSONObject()
    }

    companion object {
        private const val PIN_SESSION_WINDOW_MS = 45_000L
        private val pinVerifiedWindowBySessionVin =
            java.util.concurrent.ConcurrentHashMap<String, Long>()

        private fun pinSessionKey(authToken: String, vin: String, pinHash: String): String =
            "${authToken.trim()}|${vin.trim().uppercase(Locale.US)}|${pinHash.trim()}"

        private fun isPinSessionWarm(authToken: String, vin: String, pinHash: String): Boolean {
            if (authToken.isBlank() || vin.isBlank() || pinHash.isBlank()) return false
            val last = pinVerifiedWindowBySessionVin[pinSessionKey(authToken, vin, pinHash)] ?: return false
            val elapsed = System.currentTimeMillis() - last
            return elapsed in 0L..PIN_SESSION_WINDOW_MS
        }

        private fun markPinSessionWarm(authToken: String, vin: String, pinHash: String) {
            if (authToken.isNotBlank() && vin.isNotBlank() && pinHash.isNotBlank()) {
                pinVerifiedWindowBySessionVin[pinSessionKey(authToken, vin, pinHash)] =
                    System.currentTimeMillis()
            }
        }

        private fun invalidatePinSessionForVin(vin: String) {
            val marker = "|${vin.trim().uppercase(Locale.US)}|"
            if (marker.length <= 2) return
            pinVerifiedWindowBySessionVin.keys.removeAll { it.contains(marker) }
        }

        private fun clearAllPinSessions() {
            pinVerifiedWindowBySessionVin.clear()
        }

        const val COMMAND_PATH = "/cheryT15/app/command.do"
        const val PROTOCOL_VERSION = "0300"
        const val BUSINESS_ID = "100"
        const val APP_NAME = "0"
        const val APP_TYPE_ANDROID = "0"
        const val SMS_TYPE_FORGET_PASSWORD = "2"
        const val SMS_TYPE_LOGIN = "7"
        const val LOGIN_METHOD_PASSWORD = "1"
        const val LOGIN_METHOD_SMS = "2"
        const val PASSWORD_TYPE_VEHICLE = "0"
        const val DEFAULT_BLE_NAME_PREFIX = "CHR"

        const val SERVICE_LOGIN_PASSWORD = "101"
        const val SERVICE_LOGIN_SMS = "105"
        const val SERVICE_REQUEST_BIND_SMS = "107"
        const val SERVICE_CONFIRM_BIND_SMS = "108"
        const val SERVICE_USER_INFO = "112"
        const val SERVICE_VERIFY_PIN = "125"
        const val SERVICE_UPDATE_LICENSE_PLATE = "933"
        const val SERVICE_VEHICLE_INFO = "1001"
        const val SERVICE_VEHICLE_LIST = "900"
        const val SERVICE_VEHICLE_STATUS = "911"
        const val SERVICE_SEND_SMS = "920"
        const val SERVICE_VERIFY_SMS = "921"
        const val SERVICE_REMOTE_COMMAND = "970"

        private val UNAUTHENTICATED_SERVICE_TYPES = setOf(
            "101", "104", "105", "107", "108", "111", "123", "920", "921", "922", "923"
        )

        const val RESULT_SUCCESS = "0200"
        const val RESULT_EMPTY_DATA = "0204"
        const val RESULT_SESSION_EXPIRED = "0406"
        const val RESULT_DEVICE_BIND_REQUIRED = "0843"

        const val SHA256_HEX_LENGTH = 64
        const val ZYHL_HASH_BASE64_LENGTH = 88

        const val DEFAULT_PRIMARY_HOST = "pkica.mychery.com"
        const val DEFAULT_PRIMARY_PORT = 648
        const val DEFAULT_PRIMARY_BASE_URL = "https://pkica.mychery.com:648"
        const val DEFAULT_SECONDARY_BASE_URL = "https://cloudrivechery.mychery.com"
        const val DEFAULT_USER_AGENT = "Android Device"

    }
}

private fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
