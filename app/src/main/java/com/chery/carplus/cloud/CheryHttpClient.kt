package com.chery.carplus.cloud

import android.util.Base64
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
    val rawJson: String = ""
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
    val accountType: Int = 0
)

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
    val msg: String = ""
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
    val error: String? = null
)

data class DeviceBindingSmsResult(
    val success: Boolean,
    val resultCode: String,
    val message: String,
    val telephone: String = "",
    val rawJson: String = ""
)

class CheryDeviceBindingRequiredException(
    message: String,
    val telephone: String = "",
    val resultCode: String = RESULT_DEVICE_BIND_REQUIRED,
    cause: Throwable? = null
) : Exception(message, cause) {
    companion object {
        const val RESULT_DEVICE_BIND_REQUIRED = "0843"
    }
}

class CherySessionExpiredException(
    message: String = "智云互联会话已过期，请重新登录"
) : Exception(message)

/**
 * Authentic zyhl (`com.chery.zhiyunhulian.assistant`) HTTP transport.
 *
 * All business calls use POST /cheryT15/app/command.do with the 0300
 * BaseReqEntity envelope. The 648-port PKI gateway is probed first and the
 * USER gateway is retried automatically for transport/TLS/non-2xx failures.
 */
class CheryHttpClient(
    private val baseUrl: String = DEFAULT_PRIMARY_BASE_URL,
    private val fallbackBaseUrl: String = DEFAULT_SECONDARY_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 30_000,
    private val defaultDeviceId: String = ""
) {
    @Volatile
    private var sessionGlobalId: String = ""

    @Volatile
    private var sessionSdkToken: String = ""

    @Volatile
    private var sessionRefreshToken: String = ""

    /**
     * zyhl com.lib.common.tools.l.SHA256:
     * Base64(SHA-256(plain).hexLower UTF-8 bytes), without gratuitous re-hashing.
     */
    fun hashPasswordOrPin(plain: String): String {
        if (isAlreadyZyhlHash(plain)) return plain
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
    }

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

    /**
     * 920 send-SMS. `type` defaults to 7 (login); 1/2 are register/reset.
     * `ticket` and `randstr` remain source-compatible legacy parameters.
     */
    fun sendSmsCode(
        mobileNo: String,
        ticket: String = "",
        randstr: String = "",
        type: String = SMS_TYPE_LOGIN
    ): Boolean {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        val smsType = type.ifBlank { SMS_TYPE_LOGIN }
        val response = postEnvelope(
            serviceType = SERVICE_SEND_SMS,
            data = JSONObject().apply {
                put("appName", APP_NAME)
                put("telephone", mobileNo.trim())
                put("type", smsType)
            },
            globalId = ""
        )
        if (isSuccessCode(response.resultCode)) return true

        // A password/SMS login from a new handset can require the 107 bind SMS.
        if (smsType == SMS_TYPE_LOGIN &&
            (response.resultCode == RESULT_DEVICE_BIND_REQUIRED || isDeviceBindingMessage(response.message))
        ) {
            return requestDeviceBindSms(mobileNo, LOGIN_METHOD_PASSWORD)
        }
        return false
    }

    /** 921 verify-SMS for login/register/reset flows. */
    fun verifySmsCode(
        mobileNo: String,
        smsCode: String,
        type: String = SMS_TYPE_LOGIN
    ): Boolean {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        require(smsCode.isNotBlank()) { "smsCode is required" }
        val response = postEnvelope(
            serviceType = SERVICE_VERIFY_SMS,
            data = JSONObject().apply {
                put("telephone", mobileNo.trim())
                put("type", type.ifBlank { SMS_TYPE_LOGIN })
                put("validateCode", smsCode.trim())
            },
            globalId = ""
        )
        return isSuccessCode(response.resultCode)
    }

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

    /**
     * 101 password login. A successful login is followed by 112 user info and
     * 900 bound-vehicle discovery. 0843 automatically triggers 107 and raises a
     * user-action exception with the binding-SMS instruction.
     */
    fun loginWithPassword(
        mobileNo: String,
        password: String,
        deviceId: String = ""
    ): CheryLoginResult {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        require(password.isNotEmpty()) { "password is required" }
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
            if (bind?.success == true) {
                throw CheryDeviceBindingRequiredException(
                    message = "该设备尚未完成智云互联设备绑定；设备绑定短信（107）已发送至 $target。" +
                        "请使用短信验证码登录，客户端将自动执行 108 设备确认后继续登录。",
                    telephone = target,
                    resultCode = response.resultCode
                )
            }
            throw CheryDeviceBindingRequiredException(
                message = "该设备需要完成智云互联设备绑定，但 107 绑定短信请求未成功：" +
                    bind?.message.orEmpty().ifBlank { response.message }.ifBlank { "未知错误" },
                telephone = target,
                resultCode = response.resultCode
            )
        }

        requireSuccess(response, SERVICE_LOGIN_PASSWORD)
        return finishLogin(response, mobileNo, resolvedDeviceId)
    }

    /**
     * 105 SMS login. The normal path can optionally pre-check through 921. If
     * 105 returns 0843, 108 is executed and 105 is retried once. Supplying a
     * password also performs 108 before the 105 exchange.
     */
    fun loginWithSms(
        mobileNo: String,
        smsCode: String,
        deviceId: String = "",
        verifySmsFirst: Boolean = false,
        password: String = ""
    ): CheryLoginResult {
        require(mobileNo.isNotBlank()) { "mobileNo is required" }
        require(smsCode.isNotBlank()) { "smsCode is required" }
        val resolvedDeviceId = resolveDeviceId(deviceId)

        if (password.isNotEmpty()) {
            val confirmation = confirmDeviceBindSmsDetailed(
                loginName = mobileNo,
                validateCode = smsCode,
                loginMethod = LOGIN_METHOD_SMS,
                deviceId = resolvedDeviceId
            )
            if (!confirmation.success) {
                throw CheryDeviceBindingRequiredException(
                    message = "108 设备绑定确认未成功：${confirmation.message.ifBlank { "未知错误" }}",
                    telephone = mobileNo.trim(),
                    resultCode = confirmation.resultCode
                )
            }
        }

        if (verifySmsFirst) {
            val verify = postEnvelope(
                serviceType = SERVICE_VERIFY_SMS,
                data = JSONObject().apply {
                    put("telephone", mobileNo.trim())
                    put("type", SMS_TYPE_LOGIN)
                    put("validateCode", smsCode.trim())
                },
                globalId = ""
            )
            if (!isSuccessCode(verify.resultCode)) {
                if (verify.resultCode == RESULT_DEVICE_BIND_REQUIRED) {
                    val confirmation = confirmDeviceBindSmsDetailed(
                        mobileNo,
                        smsCode,
                        LOGIN_METHOD_SMS,
                        resolvedDeviceId
                    )
                    if (!confirmation.success) {
                        throw CheryDeviceBindingRequiredException(
                            message = "新设备需要短信绑定，108 确认未成功：${confirmation.message}",
                            telephone = mobileNo,
                            resultCode = confirmation.resultCode
                        )
                    }
                } else {
                    requireSuccess(verify, SERVICE_VERIFY_SMS)
                }
            }
        }

        var response = loginSmsRequest(mobileNo, smsCode, resolvedDeviceId)
        if (response.resultCode == RESULT_DEVICE_BIND_REQUIRED) {
            val confirmation = confirmDeviceBindSmsDetailed(
                mobileNo,
                smsCode,
                LOGIN_METHOD_SMS,
                resolvedDeviceId
            )
            if (!confirmation.success) {
                throw CheryDeviceBindingRequiredException(
                    message = "新设备需要短信绑定，108 确认未成功：${confirmation.message}。" +
                        "请重新发送 107 绑定短信后再试。",
                    telephone = mobileNo,
                    resultCode = confirmation.resultCode
                )
            }
            response = loginSmsRequest(mobileNo, smsCode, resolvedDeviceId)
        }

        if (response.resultCode == RESULT_DEVICE_BIND_REQUIRED) {
            throw CheryDeviceBindingRequiredException(
                message = "该设备仍需完成智云互联设备绑定。请先发送 107 绑定短信并完成 108 确认。",
                telephone = mobileNo.trim(),
                resultCode = response.resultCode
            )
        }

        requireSuccess(response, SERVICE_LOGIN_SMS)
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

    /** 112 fetch-user-info / session exchange. */
    fun fetchUserInfo(accessToken: String = ""): CheryUserInfo {
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
            rawJson = response.rawBody
        )
    }

    /** 900 fetch the account's bound vehicle list (DataResp900/DataResp900a). */
    fun fetchVehicleList(accessToken: String = ""): List<VehicleSummary> {
        val authToken = resolveAuthToken(accessToken)
        require(authToken.isNotBlank()) { "accessToken/globalId is required" }
        val response = postEnvelope(SERVICE_VEHICLE_LIST, JSONObject(), authToken)
        if (response.resultCode == RESULT_EMPTY_DATA) return emptyList()
        requireSuccess(response, SERVICE_VEHICLE_LIST)

        val data = response.dataObject()
        val array = data.optJSONArray("vehicleList")
            ?: response.envelope.optJSONArray("data")
            ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { item ->
                    add(parseVehicleSummary(item))
                }
            }
        }
    }

    /**
     * 911 fetch realtime vehicle status (net910 / DataResp910 / DataResp911).
     * The zyhl session is authorised by the top-level globalId; the legacy
     * sdkToken and userId parameters remain for UI source compatibility.
     */
    fun fetchVehicleStatus(
        vin: String,
        accessToken: String,
        sdkToken: String,
        userId: String
    ): VehicleTelemetry {
        require(vin.isNotBlank()) { "vin is required" }
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

    /** 125 verify the vehicle control PIN. */
    fun verifyVehiclePin(
        vin: String,
        pin: String,
        accessToken: String = ""
    ): Boolean {
        require(vin.isNotBlank()) { "vin is required" }
        require(pin.isNotEmpty()) { "pin is required" }
        val authToken = resolveAuthToken(accessToken)
        require(authToken.isNotBlank()) { "accessToken/globalId is required" }
        val response = postEnvelope(
            serviceType = SERVICE_VERIFY_PIN,
            data = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("vehiclePass", hashPasswordOrPin(pin))
                put("openGesturePass", "0")
            },
            globalId = authToken
        )
        return isSuccessCode(response.resultCode)
    }

    /**
     * 970 send remote command. A supplied PIN is carried as safePasswd in the
     * exact same authenticated envelope; 125 remains available separately.
     */
    fun sendRemoteCommand(
        vin: String,
        userId: String,
        accessToken: String,
        sdkToken: String,
        command: RemoteCommand,
        pin: String = "",
        params: Map<String, String> = emptyMap()
    ): RemoteCommandResult {
        val requestId = newRequestId()
        return try {
            val authToken = resolveAuthToken(accessToken)
            require(authToken.isNotBlank()) { "accessToken/globalId is required" }
            val spec = commandSpec(command, params)
            val commandData = JSONObject().apply {
                put("vin", vin.trim().uppercase(Locale.US))
                put("instruction", spec.instruction)
                spec.isSetTemp?.let { put("isSetTemp", it) }
                put("paras", spec.paras)
                put("passwordType", PASSWORD_TYPE_VEHICLE)
                if (pin.isNotEmpty()) put("safePasswd", hashPasswordOrPin(pin))
            }
            val response = postEnvelope(
                serviceType = SERVICE_REMOTE_COMMAND,
                data = commandData,
                globalId = authToken
            )
            val success = isSuccessCode(response.resultCode)
            val resultData = response.dataObjectOrNull()
            RemoteCommandResult(
                success = success,
                command = command,
                requestId = response.envelope.optString("requestId", requestId).ifBlank { requestId },
                resultCode = response.resultCode,
                resultMessage = response.message,
                seq = resultData?.optString("seq").orEmpty(),
                responseSign = response.envelope.optString("sign"),
                rawResponse = response.rawBody,
                error = if (success) null else response.message.ifBlank { "remote command rejected" }
            )
        } catch (error: Exception) {
            RemoteCommandResult(
                success = false,
                command = command,
                requestId = requestId,
                resultCode = "",
                resultMessage = "",
                seq = "",
                responseSign = "",
                rawResponse = "",
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    /**
     * Source-compatible SDK exchange entry: validates user identity through 112
     * and returns the token captured from the 101/105 data.token field.
     */
    fun exchangeSdkToken(
        accessToken: String,
        vin: String,
        verificationCode: String = ""
    ): SdkTokenInfo {
        val authToken = resolveAuthToken(accessToken)
        require(authToken.isNotBlank()) { "accessToken/globalId is required" }
        val userInfo = fetchUserInfo(authToken)
        if (verificationCode.isNotEmpty() && vin.isNotBlank()) {
            runCatching { verifyVehiclePin(vin, verificationCode, authToken) }
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

        val userInfo = runCatching { fetchUserInfo(accessToken) }.getOrNull()
        val vehicles = runCatching { fetchVehicleList(accessToken) }.getOrDefault(emptyList())
        val userId = firstNonBlank(
            userInfo?.userId,
            loginData.optString("userId"),
            loginData.optString("accountId")
        )

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
        val rawBleMac = item.optString("bleMac").trim()
        val cleanMac = rawBleMac.replace(Regex("[^0-9A-Fa-f]"), "").uppercase(Locale.US)
        val prefix = item.optString("bleNamePrefix").trim().ifBlank { DEFAULT_BLE_NAME_PREFIX }
        val explicitBleName = firstNonBlank(
            item.optString("bleName"),
            item.optString("bleNameValue")
        )
        val bleName = explicitBleName.ifBlank {
            if (cleanMac.length == 12 && cleanMac.matches(Regex("[0-9A-F]{12}"))) {
                "$prefix$cleanMac"
            } else {
                ""
            }
        }
        val isBluetoothAuth = item.optString("isBluetoothAuth")
        return VehicleSummary(
            vin = vin,
            bleMac = rawBleMac,
            bleName = bleName,
            seriesName = firstNonBlank(item.optString("typeDesc"), item.optString("vehicleType")),
            plateNo = item.optString("licensenum"),
            vehicleCode = firstNonBlank(item.optString("vehicleCode"), item.optString("vehicleTypeID")),
            standardName = item.optString("standardName"),
            vehicleName = firstNonBlank(item.optString("vehicleName"), item.optString("vehicleType")),
            vehicleTypeId = firstNonBlank(item.optString("vehicleTypeID"), item.optString("vehicleTypeId")),
            vehicleId = item.optString("vehicleId"),
            airType = item.optString("airType"),
            sunroofType = item.optString("sunroofType"),
            rawJson = item.toString(),
            bleNamePrefix = prefix,
            bleNameType = item.optString("bleNameType"),
            isBluetoothAuth = isBluetoothAuth,
            selected = item.optString("selected"),
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
            }
        )
    }

    private fun parseTelemetry(
        requestedVin: String,
        status: JSONObject,
        rawBody: String
    ): VehicleTelemetry {
        val conditionFields = buildMap {
            status.keys().forEach { key ->
                val value = status.opt(key)
                if (value != null && value != JSONObject.NULL) put(key, value.toString())
            }
        }
        val vin = status.optString("vin").ifBlank { requestedVin }
        val doorLock = status.optString("doorLock")
        val frontLeftDoor = status.optString("frontLeftDoor")
        val frontRightDoor = status.optString("frontRightDoor")
        val backLeftDoor = status.optString("backLeftDoor")
        val backRightDoor = status.optString("backRightDoor")
        val trunkDoor = status.optString("trunkDoor")
        val trunkLock = status.optString("trunkLock")
        val hood = status.optString("hood")
        val frontLeftWindowState = status.optString("frontLeftWindowState")
        val frontRightWindowState = status.optString("frontRightWindowState")
        val backLeftWindowState = status.optString("backLeftWindowState")
        val backRightWindowState = status.optString("backRightWindowState")
        val sunroofState = status.optString("sunroofState")
        val liftgateState = status.optString("liftgateState")
        val engineState = status.optString("engineState")
        val airState = status.optString("airState")
        val odometer = status.optString("odometer")
        val oilSurplus = status.optString("oilSurplus")
        val electricitySurplus = status.optString("electricitySurplus")
        val mileageSurplus = status.optString("mileageSurplus")
        val lon = status.optString("lon")
        val lat = status.optString("lat")

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
            "frontLeft" to frontLeftWindowState,
            "frontRight" to frontRightWindowState,
            "backLeft" to backLeftWindowState,
            "backRight" to backRightWindowState,
            "sunroof" to sunroofState,
            "liftgate" to liftgateState
        )

        return VehicleTelemetry(
            vin = vin,
            doorLockState = doorLock,
            doorLocked = stateToBoolean(doorLock, "1", "0"),
            doorStates = doorStates,
            windowStates = windowStates,
            trunkState = trunkDoor.ifBlank { trunkLock },
            trunkOpen = stateToBoolean(trunkDoor.ifBlank { trunkLock }, "1", "0"),
            tailgateState = liftgateState,
            skylightState = sunroofState,
            engineState = engineState,
            engineRunning = stateToBoolean(engineState, "1", "0"),
            acState = airState,
            acOn = stateToBoolean(airState, "1", "0"),
            batteryVoltage = firstDouble(status, "batteryVoltage", "voltage", "electricityVoltage"),
            fuelPercent = firstDouble(status, "oilSurplus"),
            remainingRangeKm = firstDouble(status, "mileageSurplus"),
            odometerKm = firstDouble(status, "odometer"),
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
            lFrontTyre = status.optString("lFrontTyre"),
            rFrontTyre = status.optString("rFrontTyre"),
            lRearTyre = status.optString("lRearTyre"),
            rRearTyre = status.optString("rRearTyre"),
            lon = lon,
            lat = lat,
            online = status.optString("online"),
            time = status.optString("time"),
            seq = status.optString("seq"),
            result = status.optString("result"),
            msg = status.optString("msg")
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
            JSONObject().put("0002", 1)
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
                put("3028", parseIntParam(params, 1, "airType"))
            },
            isSetTemp = "1"
        )
        RemoteCommand.AC_OFF -> CommandSpec(
            "1105",
            JSONObject().apply {
                put("0002", 0)
                put("3001", 0)
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
            }
        )
        RemoteCommand.SKYLIGHT_TILT -> CommandSpec(
            "1109",
            JSONObject().apply {
                put("3017", 2)
                put("3025", 11)
            }
        )
        RemoteCommand.SKYLIGHT_CLOSE -> CommandSpec(
            "1109",
            JSONObject().apply {
                put("3017", 0)
                put("3025", 11)
            }
        )
    }

    private fun parseIntParam(
        params: Map<String, String>,
        defaultValue: Int,
        vararg keys: String
    ): Int = keys.firstNotNullOfOrNull { key -> params[key]?.trim()?.toIntOrNull() } ?: defaultValue

    private fun resolveDeviceId(requested: String): String =
        requested.trim().ifBlank { defaultDeviceId.trim() }

    private fun resolveAuthToken(explicit: String): String =
        explicit.trim().ifBlank { sessionGlobalId }

    private fun updateSessionFrom(response: ZyhlResponse): String {
        val globalId = response.envelope.optString("globalId").trim()
        if (globalId.isNotBlank()) sessionGlobalId = globalId
        return globalId
    }

    private fun postEnvelope(
        serviceType: String,
        data: JSONObject,
        globalId: String
    ): ZyhlResponse {
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
                updateSessionFrom(response)
                return response
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                lastError = error
            }
        }

        throw IOException(
            "智云互联网关请求失败（已尝试 ${attempts.size} 个入口：" +
                attempts.joinToString() + "）",
            lastError
        )
    }

    private fun postJson(url: String, body: String): HttpResponse {
        val parsedUrl = URL(url)
        val connection = parsedUrl.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.doOutput = true
        connection.useCaches = false
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", userAgent)

        if (connection is HttpsURLConnection &&
            parsedUrl.host.equals(DEFAULT_PRIMARY_HOST, ignoreCase = true) &&
            parsedUrl.port == DEFAULT_PRIMARY_PORT
        ) {
            connection.sslSocketFactory = permissiveSslSocketFactory
            connection.hostnameVerifier = permissiveHostnameVerifier
        }

        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { stream -> stream.write(bytes) }
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
        val code = response.resultCode
        when {
            isSuccessCode(code) -> Unit
            code == RESULT_SESSION_EXPIRED -> throw CherySessionExpiredException()
            code == RESULT_DEVICE_BIND_REQUIRED -> throw CheryDeviceBindingRequiredException(
                message = "服务 $serviceType 要求完成新设备短信绑定（107 + 108）"
            )
            else -> error(
                "智云互联 $serviceType 失败" +
                    if (code.isBlank()) "" else "[$code]" +
                        "：${response.message.ifBlank { "服务端未返回错误说明" }}"
            )
        }
    }

    private fun isAlreadyZyhlHash(value: String): Boolean {
        if (value.length != ZYHL_HASH_BASE64_LENGTH || !value.endsWith("==")) return false
        val decoded = runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull() ?: return false
        if (decoded.size != SHA256_HEX_LENGTH) return false
        val text = String(decoded, Charsets.UTF_8)
        return text.length == SHA256_HEX_LENGTH && text.matches(Regex("[0-9a-f]{64}"))
    }

    private fun stateToBoolean(value: String, onValue: String, offValue: String): Boolean? =
        when (value.trim().uppercase(Locale.US)) {
            onValue.uppercase(Locale.US) -> true
            offValue.uppercase(Locale.US) -> false
            else -> null
        }

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
        const val COMMAND_PATH = "/cheryT15/app/command.do"
        const val PROTOCOL_VERSION = "0300"
        const val BUSINESS_ID = "100"
        const val APP_NAME = "0"
        const val APP_TYPE_ANDROID = "0"
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
        const val SERVICE_VEHICLE_LIST = "900"
        const val SERVICE_VEHICLE_STATUS = "911"
        const val SERVICE_SEND_SMS = "920"
        const val SERVICE_VERIFY_SMS = "921"
        const val SERVICE_REMOTE_COMMAND = "970"

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
