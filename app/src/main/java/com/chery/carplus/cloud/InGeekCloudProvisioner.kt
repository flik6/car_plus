package com.chery.carplus.cloud

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.chery.carplus.profile.VckInfoParser
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.profile.VehicleProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import android.util.Base64
import java.util.Locale
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

data class InGeekTokenResponse(
    val httpStatus: Int,
    val authorization: String,
    val body: String,
    val resCode: String,
    val resMsg: String,
    val responseHeaders: Map<String, String>
)

data class InGeekKeyReloadResponse(
    val httpStatus: Int,
    val outerBody: String,
    val resDataHex: String,
    val payload: JSONObject,
    val vckInfo: ByteArray,
    val df: ByteArray,
    val encKey: ByteArray,
    val keyId: String,
    val keyDownloadTime: String
)

data class ProvisionResult(
    val success: Boolean,
    val authToken: String = "",
    val keyId: String = "",
    val keyDownloadTime: String = "",
    val kRootHex: String = "",
    val infoHex: String = "",
    val edfHex: String = "",
    val parsedInfo: com.chery.carplus.profile.VckInfoParsed? = null,
    val profile: VehicleProfile? = null,
    val tokenResponse: JSONObject? = null,
    val keyResponse: JSONObject? = null,
    val message: String = "",
    val error: String? = null
)

/**
 * Authorized-test software implementation of InGeek DKtrans provisioning.
 *
 * It deliberately avoids AndroidKeyStore WrappedKeyEntry so the RSA-OAEP +
 * AES-256-GCM unwrap path also works on Android 10 through Android 17 devices
 * whose KeyMint implementation rejects hardware-wrapped SHA-1 OAEP operations.
 */
class InGeekCloudProvisioner(
    private val context: Context? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appId: String = DEFAULT_APP_ID,
    private val apkSign: String = DEFAULT_APK_SIGN,
    private val taUuid: String = DEFAULT_TA_UUID,
    private val connectTimeoutMs: Int = 25_000,
    private val readTimeoutMs: Int = 25_000
) {
    fun aesCbcEncrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(STATIC_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(ByteArray(16))
        )
        return bytesToHexLower(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun aesCbcDecrypt(encryptedHex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(STATIC_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(ByteArray(16))
        )
        return String(cipher.doFinal(decodeHexOrBase64(encryptedHex)), Charsets.UTF_8)
    }

    /**
     * Step 1: TokenRequest ("00"). The task-mandated fields are included along
     * with the device/RSA metadata required by the verified shipped client.
     */
    fun requestToken(
        userId: String,
        mobileNo: String,
        baseUrl: String = DEFAULT_BASE_URL,
        appId: String = DEFAULT_APP_ID,
        apkSign: String = DEFAULT_APK_SIGN,
        deviceId: String = "",
        teeUuidBase64: String = "",
        taUuid: String = DEFAULT_TA_UUID,
        brand: String = "Android",
        model: String = Build.MODEL.ifBlank { "Android" },
        system: String = Build.VERSION.RELEASE.ifBlank { "0" },
        session: String = "0000",
        token: String = "0000"
    ): InGeekTokenResponse {
        val parameters = JSONObject().apply {
            put("appId", appId)
            put("brand", brand)
            if (deviceId.isNotBlank()) put("deviceId", deviceId)
            put("mobileNo", mobileNo)
            put("model", model)
            put("session", session)
            put("sign", apkSign)
            put("system", system)
            put("taUUID", taUuid)
            if (teeUuidBase64.isNotBlank()) put("teeUUID", teeUuidBase64)
            put("token", token)
            put("userid", userId)
        }
        val outer = JSONObject().apply {
            put("operation", "00")
            put("parameters", aesCbcEncrypt(parameters.toString()))
            put(
                "publicData",
                JSONObject().apply {
                    put("mobileNo", mobileNo)
                    put("userid", userId)
                }
            )
        }
        val response = postJson(
            requestUrl(baseUrl),
            outer.toString(),
            buildHeaders(userId, deviceId, appId, authorization = "")
        )
        val bodyObject = runCatching { JSONObject(response.body) }.getOrDefault(JSONObject())
        val resCode = firstNonBlank(
            bodyObject.optString("resCode"),
            bodyObject.optString("code")
        )
        val resMsg = firstNonBlank(
            bodyObject.optString("resMsg"),
            bodyObject.optString("msg"),
            bodyObject.optString("message")
        )
        val authorization = response.headers["authorization"].orEmpty().trim()
        check(response.statusCode in 200..299) {
            "TokenRequest HTTP ${response.statusCode}: ${response.body.take(500)}"
        }
        check(authorization.isNotBlank()) {
            "TokenRequest did not return an Authorization header: ${response.body.take(500)}"
        }
        return InGeekTokenResponse(
            httpStatus = response.statusCode,
            authorization = authorization,
            body = response.body,
            resCode = resCode,
            resMsg = resMsg,
            responseHeaders = response.headers
        )
    }

    fun queryOwnedAndSharedKeys(
        userId: String,
        mobileNo: String,
        vin: String,
        operation: String = "30",
        baseUrl: String = DEFAULT_BASE_URL
    ): JSONObject = postBusinessOperation(
        userId = userId,
        mobileNo = mobileNo,
        operation = operation,
        parameters = JSONObject().put("vin", vin),
        baseUrl = baseUrl
    )

    fun shareDigitalKey(
        userId: String,
        mobileNo: String,
        vin: String,
        borrowerMobile: String,
        startTime: String,
        endTime: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): JSONObject {
        val publicData = JSONObject().apply {
            put("vin", vin)
            put("recMobileNo", borrowerMobile)
            put("startDate", startTime)
            put("endDate", endTime)
        }
        val parameters = JSONObject().apply {
            put("vin", vin)
            put("recMobileNo", borrowerMobile)
            put("startDate", startTime)
            put("endDate", endTime)
            put("style", "1")
            put("kpre", "1")
            put("publicData", publicData)
        }
        return postBusinessOperation(
            userId = userId,
            mobileNo = mobileNo,
            operation = "10",
            parameters = parameters,
            baseUrl = baseUrl
        )
    }

    fun revokeSharedDigitalKey(
        userId: String,
        mobileNo: String,
        vin: String,
        keyId: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): JSONObject {
        val publicData = JSONObject().apply {
            put("vin", vin)
            put("keyId", keyId)
        }
        val parameters = JSONObject().apply {
            put("keyId", keyId)
            put("vin", vin)
            put("publicData", publicData)
        }
        return postBusinessOperation(
            userId = userId,
            mobileNo = mobileNo,
            operation = "11",
            parameters = parameters,
            baseUrl = baseUrl
        )
    }

    /** Step 2: software RSA-2048 key pair; teeUUID is the X.509 SPKI Base64. */
    fun generateSoftwareRsa2048KeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    fun teeUuidBase64(keyPair: KeyPair): String =
        Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

    /** Step 3: KeyReLoadRequest ("31"), then AES-CBC decrypt data.resData. */
    fun requestKeyReload(
        authToken: String,
        vin: String,
        deviceId: String,
        teeUuidBase64: String,
        taUuid: String,
        operation: String = "31",
        userId: String = ""
    ): InGeekKeyReloadResponse {
        val parameters = JSONObject().apply {
            put("vin", vin.trim().uppercase(Locale.US))
            put("system", Build.VERSION.RELEASE.ifBlank { "0" })
            put("model", Build.MODEL.ifBlank { "Android" })
            put("brand", "Android")
            if (deviceId.isNotBlank()) put("deviceId", deviceId)
            put("taUUID", taUuid)
            put("teeUUID", teeUuidBase64)
        }
        val outer = JSONObject().apply {
            put("operation", operation)
            put("parameters", aesCbcEncrypt(parameters.toString()))
        }
        val response = postJson(
            requestUrl(baseUrl),
            outer.toString(),
            buildHeaders(userId, deviceId, appId, authorization = authToken)
        )
        check(response.statusCode in 200..299) {
            "KeyReLoadRequest HTTP ${response.statusCode}: ${response.body.take(500)}"
        }

        val outerObject = JSONObject(response.body)
        val data = outerObject.optJSONObject("data")
            ?: throw IllegalStateException("KeyReLoad response has no data object")
        val resData = data.optString("resData")
        check(resData.isNotBlank()) { "KeyReLoad response has no data.resData" }

        val clearJson = aesCbcDecrypt(resData)
        val payload = JSONObject(clearJson)
        val vckInfo = decodeBinaryField(payload.optString("vckInfo"))
        val df = decodeBinaryField(payload.optString("df"))
        val encKey = decodeBinaryField(payload.optString("encKey"))
        require(vckInfo.size == VckInfoParser.INFO_SIZE) {
            "vckInfo must be ${VckInfoParser.INFO_SIZE} bytes, got ${vckInfo.size}"
        }
        require(df.size in 68..80) { "df must be an ECDSA DER signature, got ${df.size} bytes" }
        require(encKey.size >= 390) { "encKey must be at least 390 bytes, got ${encKey.size}" }

        return InGeekKeyReloadResponse(
            httpStatus = response.statusCode,
            outerBody = response.body,
            resDataHex = resData,
            payload = payload,
            vckInfo = vckInfo,
            df = df,
            encKey = encKey,
            keyId = payload.optString("keyId"),
            keyDownloadTime = payload.optString("keyDownloadTime")
        )
    }

    /**
     * Step 4: exact SecureKeyWrapper parser and RSA-OAEP/AES-GCM unwrap.
     *
     * The wrapper is normally encKey[15..389]. A direct 0x30 0x82... wrapper is
     * also accepted. keyDescription's complete DER TLV is used as GCM AAD.
     */
    fun unwrapEncKey(encKeyBytes: ByteArray, privateKey: PrivateKey): ByteArray {
        val wrapper = locateSecureKeyWrapper(encKeyBytes)
        val sequence = parseDerTlv(wrapper, 0)
        require(sequence.tag == 0x30) { "SecureKeyWrapper is not a DER SEQUENCE" }
        require(sequence.end == wrapper.size) { "SecureKeyWrapper has trailing bytes" }

        val children = mutableListOf<DerTlv>()
        var offset = 0
        while (offset < sequence.value.size) {
            val child = parseDerTlv(sequence.value, offset)
            children.add(child)
            offset = child.end
        }
        require(children.size >= 6) {
            "expected 6 SecureKeyWrapper children, got ${children.size}"
        }
        require(children[0].tag == 0x02) { "version is not DER INTEGER" }
        require(derIntegerValue(children[0].value) == 0) { "unsupported SecureKeyWrapper version" }
        require(children[1].tag == 0x04) { "encryptedTransportKey is not OCTET STRING" }
        require(children[2].tag == 0x04) { "initializationVector is not OCTET STRING" }
        require(children[3].tag == 0x30) { "keyDescription is not DER SEQUENCE" }
        require(children[4].tag == 0x04) { "encryptedKey is not OCTET STRING" }
        require(children[5].tag == 0x04) { "tag is not OCTET STRING" }

        val encryptedTransportKey = children[1].value
        val iv = children[2].value
        val keyDescriptionDer = children[3].raw
        val encryptedKey = children[4].value
        val gcmTag = children[5].value

        require(encryptedTransportKey.size == 256) {
            "encryptedTransportKey must be 256 bytes, got ${encryptedTransportKey.size}"
        }
        require(iv.size == 12) { "GCM IV must be 12 bytes, got ${iv.size}" }
        require(keyDescriptionDer.size in 56..58) {
            "keyDescription DER must be 56-58 bytes, got ${keyDescriptionDer.size}"
        }
        require(encryptedKey.size == 16) {
            "encryptedKey must be 16 bytes, got ${encryptedKey.size}"
        }
        require(gcmTag.size == 16) { "GCM tag must be 16 bytes, got ${gcmTag.size}" }

        val oaepCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        oaepCipher.init(
            Cipher.DECRYPT_MODE,
            privateKey,
            OAEPParameterSpec(
                "SHA-1",
                "MGF1",
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT
            )
        )
        val transportKey = oaepCipher.doFinal(encryptedTransportKey)
        require(transportKey.size == 32) {
            "K_transport must be 32 bytes, got ${transportKey.size}"
        }

        val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
        gcmCipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(transportKey, "AES"),
            GCMParameterSpec(128, iv)
        )
        gcmCipher.updateAAD(keyDescriptionDer)
        val rootKey = gcmCipher.doFinal(encryptedKey + gcmTag)
        require(rootKey.size == 16) { "K_root must be 16 bytes, got ${rootKey.size}" }
        return rootKey
    }

    /**
     * High-level TokenRequest + KeyReLoad + software unwrap + profile persistence.
     * Network and crypto failures are returned as an unsuccessful ProvisionResult.
     */
    fun provisionVehicleKey(
        userId: String,
        mobileNo: String,
        vin: String,
        bleName: String = "",
        bleMac: String = "",
        deviceId: String = "",
        accountType: Int = 0,
        profileStore: VehicleProfileStore? = null,
        logger: ((String) -> Unit)? = null
    ): ProvisionResult {
        var authToken = ""
        var tokenResponse: JSONObject? = null
        var keyResponse: JSONObject? = null
        var keyId = ""
        var keyDownloadTime = ""
        var kRootHex = ""
        var infoHex = ""
        var edfHex = ""

        return try {
            val normalizedVin = vin.trim().uppercase(Locale.US)
            require(normalizedVin.isNotBlank()) { "VIN is required" }
            require(userId.isNotBlank()) { "userId is required" }
            require(mobileNo.isNotBlank()) { "mobileNo is required" }

            val resolvedDeviceId = resolveDeviceId(deviceId)
            logger?.invoke("Generating software RSA-2048 key pair")
            val keyPair = generateSoftwareRsa2048KeyPair()
            val teeUuid = teeUuidBase64(keyPair)

            logger?.invoke("TokenRequest operation=00")
            val token = requestToken(
                userId = userId,
                mobileNo = mobileNo,
                baseUrl = baseUrl,
                appId = appId,
                apkSign = apkSign,
                deviceId = resolvedDeviceId,
                teeUuidBase64 = teeUuid,
                taUuid = taUuid
            )
            authToken = token.authorization
            tokenResponse = runCatching { JSONObject(token.body) }.getOrNull()

            logger?.invoke("KeyReLoadRequest operation=31")
            val reload = requestKeyReload(
                authToken = authToken,
                vin = normalizedVin,
                deviceId = resolvedDeviceId,
                teeUuidBase64 = teeUuid,
                taUuid = taUuid,
                operation = "31",
                userId = userId
            )
            keyId = reload.keyId
            keyDownloadTime = reload.keyDownloadTime
            keyResponse = reload.payload

            logger?.invoke("Unwrapping encKey with RSA-OAEP-SHA1 + AES-256-GCM")
            val rootKey = unwrapEncKey(reload.encKey, keyPair.private)
            kRootHex = bytesToHexUpper(rootKey)
            infoHex = bytesToHexUpper(reload.vckInfo)
            edfHex = bytesToHexUpper(reload.df)

            val parsed = VckInfoParser.parse(reload.vckInfo)
            if (parsed.vin.isNotBlank()) {
                require(parsed.vin.equals(normalizedVin, ignoreCase = true)) {
                    "vckInfo VIN ${parsed.vin} does not match requested VIN $normalizedVin"
                }
            }
            if (parsed.mobileNo.isNotBlank() && mobileNo.isNotBlank()) {
                require(parsed.mobileNo.trim() == mobileNo.trim()) {
                    "vckInfo mobile ${parsed.mobileNo} does not match requested mobile $mobileNo"
                }
            }

            val profile = VehicleProfile(
                vin = normalizedVin,
                userId = userId.trim(),
                mobileNo = mobileNo.trim(),
                bleName = bleName.trim(),
                bleMac = bleMac.trim().uppercase(Locale.US),
                deviceId = resolvedDeviceId,
                accountType = accountType,
                rootKeyHex = kRootHex,
                infoHex = infoHex,
                edfHex = edfHex,
                validFrom = parsed.validFrom,
                validTo = parsed.validTo,
                secAHex = parsed.secAHex,
                secBHex = parsed.secBHex,
                keyRole = parsed.keyRole,
                controlPin = ""
            )

            val saved = profileStore?.saveProfile(profile, activate = true) ?: profile
            logger?.invoke("Provisioning completed; VIN=$normalizedVin keyId=$keyId")
            ProvisionResult(
                success = true,
                authToken = authToken,
                keyId = keyId,
                keyDownloadTime = keyDownloadTime,
                kRootHex = kRootHex,
                infoHex = infoHex,
                edfHex = edfHex,
                parsedInfo = parsed,
                profile = saved,
                tokenResponse = tokenResponse,
                keyResponse = keyResponse,
                message = "Digital-key profile provisioned and activated"
            )
        } catch (error: Exception) {
            logger?.invoke("Provisioning failed: ${error.message ?: error.javaClass.simpleName}")
            ProvisionResult(
                success = false,
                authToken = authToken,
                keyId = keyId,
                keyDownloadTime = keyDownloadTime,
                kRootHex = kRootHex,
                infoHex = infoHex,
                edfHex = edfHex,
                tokenResponse = tokenResponse,
                keyResponse = keyResponse,
                message = "Digital-key provisioning failed",
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    suspend fun provisionVehicleKeyAsync(
        userId: String,
        mobileNo: String,
        vin: String,
        bleName: String = "",
        bleMac: String = "",
        deviceId: String = "",
        accountType: Int = 0,
        profileStore: VehicleProfileStore? = null,
        logger: ((String) -> Unit)? = null
    ): ProvisionResult = withContext(Dispatchers.IO) {
        provisionVehicleKey(
            userId = userId,
            mobileNo = mobileNo,
            vin = vin,
            bleName = bleName,
            bleMac = bleMac,
            deviceId = deviceId,
            accountType = accountType,
            profileStore = profileStore,
            logger = logger
        )
    }

    private fun postBusinessOperation(
        userId: String,
        mobileNo: String,
        operation: String,
        parameters: JSONObject,
        baseUrl: String
    ): JSONObject {
        val authToken = requestToken(userId, mobileNo, baseUrl).authorization
        val outer = JSONObject().apply {
            put("operation", operation)
            put("parameters", aesCbcEncrypt(parameters.toString()))
        }
        val response = postJson(
            requestUrl(baseUrl),
            outer.toString(),
            buildHeaders(userId, "", appId, authorization = authToken)
        )
        check(response.statusCode in 200..299) {
            "DkBusinessRequest[$operation] HTTP ${response.statusCode}: ${response.body.take(500)}"
        }
        return JSONObject(response.body)
    }

    private fun resolveDeviceId(requested: String): String {
        if (requested.isNotBlank()) return requested.trim()
        val androidId = context?.let {
            Settings.Secure.getString(it.contentResolver, Settings.Secure.ANDROID_ID)
        }.orEmpty()
        return if (androidId.isNotBlank()) {
            VckInfoParser.computeCheryDeviceId(androidId)
        } else {
            ""
        }
    }

    private fun buildHeaders(
        userId: String,
        deviceId: String,
        appId: String,
        authorization: String
    ): Map<String, String> = linkedMapOf(
        "Content-type" to "application/json;charset=UTF-8",
        "Service-Type" to "vck.service",
        "User-Agent" to "vck.product.1.0",
        "Authorization" to authorization,
        "CompanyId" to "0000",
        "accessSessionId" to "",
        "X-Ingeek-RootId" to makeRootId(userId),
        "App-Session" to "0000",
        "AppId" to appId,
        "Cookie" to "userid=$userId; usersig=",
        "X-Ingeek-UserId" to userId,
        "X-Ingeek-DeviceId" to deviceId,
        "X-Ingeek-Date" to "0",
        "X-Ingeek-Color" to "orange",
        "X-Ingeek-Identifier" to "",
        "Nonce" to "0",
        "Signature" to "0",
        "X-Ingeek-SDK-Version" to "3.0.0",
        "Accept-Encoding" to "identity"
    )

    private fun requestUrl(overrideBaseUrl: String): String {
        val trimmed = overrideBaseUrl.trimEnd('/')
        return if (trimmed.endsWith(DKTRANS_PATH)) trimmed else "$trimmed$DKTRANS_PATH"
    }

    private fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { reader -> reader.readText() }
                .orEmpty()
            val responseHeaders = buildMap {
                connection.headerFields.forEach { (name, values) ->
                    if (name != null) {
                        put(name.lowercase(Locale.US), values.joinToString(","))
                    }
                }
            }
            return HttpResult(status, responseBody, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }

    private fun locateSecureKeyWrapper(encKey: ByteArray): ByteArray {
        require(encKey.size >= 16) { "encKey is too short" }

        val offset = when {
            encKey.size >= 15 && encKey.copyOfRange(0, 6).contentEquals(INGEEK_HEADER_PREFIX) -> 15
            encKey.size >= 2 && encKey[0] == 0x30.toByte() && encKey[1] == 0x82.toByte() -> 0
            else -> (0..minOf(encKey.size - 2, 32)).firstOrNull { index ->
                encKey[index] == 0x30.toByte() && encKey[index + 1] == 0x82.toByte()
            } ?: throw IllegalArgumentException("SecureKeyWrapper DER SEQUENCE not found")
        }
        val tlv = parseDerTlv(encKey, offset)
        require(tlv.tag == 0x30) { "located wrapper is not a DER SEQUENCE" }
        val raw = encKey.copyOfRange(offset, tlv.end)
        require(raw.size == 375) { "SecureKeyWrapper must be 375 bytes, got ${raw.size}" }
        return raw
    }

    private fun derIntegerValue(value: ByteArray): Int {
        require(value.isNotEmpty() && value.size <= 4) { "invalid DER INTEGER" }
        var result = 0
        value.forEach { byte ->
            result = (result shl 8) or (byte.toInt() and 0xFF)
        }
        return result
    }

    private fun parseDerTlv(buffer: ByteArray, offset: Int): DerTlv {
        require(offset + 2 <= buffer.size) { "truncated DER TLV" }
        val tag = buffer[offset].toInt() and 0xFF
        val firstLength = buffer[offset + 1].toInt() and 0xFF
        var cursor = offset + 2
        val length: Int
        if (firstLength < 0x80) {
            length = firstLength
        } else {
            val count = firstLength and 0x7F
            require(count in 1..4 && cursor + count <= buffer.size) { "invalid DER length" }
            var parsed = 0
            repeat(count) {
                parsed = (parsed shl 8) or (buffer[cursor].toInt() and 0xFF)
                cursor++
            }
            require(parsed >= 0) { "DER length overflow" }
            length = parsed
        }
        val end = cursor + length
        require(end <= buffer.size) { "DER value exceeds buffer" }
        return DerTlv(
            tag = tag,
            value = buffer.copyOfRange(cursor, end),
            raw = buffer.copyOfRange(offset, end),
            end = end
        )
    }

    private fun decodeBinaryField(value: String): ByteArray {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ByteArray(0)
        val compactHex = trimmed.replace(Regex("\\s+"), "")
        if (compactHex.length % 2 == 0 && compactHex.matches(Regex("[0-9A-Fa-f]+"))) {
            return ByteArray(compactHex.length / 2) { index ->
                compactHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
        return Base64.decode(trimmed, Base64.DEFAULT)
    }

    private fun decodeHexOrBase64(value: String): ByteArray {
        val compact = value.trim().replace(Regex("[\\s:_-]+"), "")
        if (compact.length % 2 == 0 && compact.matches(Regex("[0-9A-Fa-f]+"))) {
            return ByteArray(compact.length / 2) { index ->
                compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
        return Base64.decode(value.trim(), Base64.DEFAULT)
    }

    private fun makeRootId(userId: String): String {
        val now = System.currentTimeMillis()
        return "default$now-$userId-${now / 3_600_000}-${Random().nextInt(100)}"
    }

    private fun bytesToHexLower(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xFF) }

    private fun bytesToHexUpper(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }

    private data class DerTlv(
        val tag: Int,
        val value: ByteArray,
        val raw: ByteArray,
        val end: Int
    )

    private data class HttpResult(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, String>
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://cloudriveapp.mychery.com:5081"
        const val DKTRANS_PATH = "/DKtrans/business"
        const val STATIC_AES_KEY = "1234567887654321"
        const val DEFAULT_APP_ID = "d66a3de6db80b2bc"
        const val DEFAULT_APK_SIGN = "B00822E28414EC998BFE2E185A6FA95C31854422"
        const val DEFAULT_TA_UUID = "4986299e-7a50-11e9-b210-d663bd873d93"

        private val INGEEK_HEADER_PREFIX = byteArrayOf(
            0x49, 0x4E, 0x47, 0x45, 0x45, 0x4B
        )
    }
}

private fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
