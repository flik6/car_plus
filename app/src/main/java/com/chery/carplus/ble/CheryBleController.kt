package com.chery.carplus.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.chery.carplus.profile.VehicleProfile
import java.util.ArrayDeque
import java.util.UUID

/**
 * Android BLE transport and handshake state machine for the authorized Chery
 * digital-key evaluation.  It uses only Android SDK and Kotlin/JCA primitives.
 */
@SuppressLint("MissingPermission")
class CheryBleController(
    context: Context,
    private val config: Config = Config(),
    var listener: Listener? = null
) {
    data class Config(
        val vehicleServiceUuid: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
        val writeCharacteristicUuid: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
        val notifyCharacteristicUuid: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
        val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        val statusServiceUuid: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        val statusCharacteristicUuid: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
        val autoPairEnabled: Boolean = true,
        val requestMtu: Int = 203,
        val maxPayloadChunkSize: Int = 194,
        val scanTimeoutMs: Long = 12_000,
        val connectTimeoutMs: Long = 15_000,
        val handshakeTimeoutMs: Long = 15_000,
        val accountAckTimeoutMs: Long = 6_000,
        val actionAckTimeoutMs: Long = 6_000,
        val activeBleMac: String? = null,
        val activeBleName: String? = null
    )

    data class AccountBinding(
        val accountType: Int = 0,
        val userId: String,
        val deviceId: String
    )

    data class SessionMaterial(
        val rootKey16: ByteArray,
        val infoBin147: ByteArray,
        val edfBin: ByteArray,
        val accountBinding: AccountBinding,
        val bleMac: String? = null,
        val bleName: String? = null
    ) {
        init {
            require(rootKey16.size == 16) { "rootKey16 must be 16 bytes" }
            require(infoBin147.size == 147) { "infoBin147 must be 147 bytes" }
            require(edfBin.size in 68..74) { "edfBin must be 68..74 bytes" }
        }
    }

    data class ScannedDevice(
        val device: BluetoothDevice,
        val name: String?,
        val address: String,
        val rssi: Int,
        val advRandomHex: String,
        val candidatePins: PairingPinCandidates
    )

    interface Listener {
        fun onDeviceScanned(
            device: BluetoothDevice,
            rssi: Int,
            advRandomHex: String,
            candidatePins: PairingPinCandidates
        ) = Unit

        fun onHandshakeStageChanged(
            stageName: String,
            ffe1Hex: String?,
            auth02Done: Boolean,
            session03Done: Boolean,
            account80Done: Boolean
        ) = Unit

        fun onPacketLog(
            direction: String,
            msgIdHex: String,
            serial: Int,
            rawHex: String,
            decryptedSummary: String
        ) = Unit

        fun onVehicleResponse(parsedResponse: ParsedTBoxResponse) = Unit

        fun onConnectionStateChanged(connected: Boolean, stateName: String) = Unit

        fun onAuthenticated(sessionSnapshot: SessionSnapshot) = Unit

        fun onActionDispatched(actionKey: String, serial: Int) = Unit

        fun onPairingPinAvailable(pin: String, address: String) = Unit

        fun onChannelModeChanged(
            isBleConnected: Boolean,
            rssi: Int?,
            statusText: String
        ) = Unit

        fun onError(message: String, throwable: Throwable? = null) = Unit
    }

    enum class Stage(val label: String) {
        IDLE("空闲"),
        SCANNING("扫描设备"),
        CONNECTING("连接中"),
        CONNECTED("GATT 已连接"),
        SERVICES_DISCOVERED("服务已发现"),
        WAITING_BOND("等待系统配对"),
        AUTH_REQUEST_SENT("已发送 0x02 认证请求"),
        WAITING_AUTH_CHALLENGE("等待 0x02 挑战"),
        SESSION_REQUEST_SENT("已发送 0x03 会话请求"),
        WAITING_SESSION_RESPONSE("等待 0x03 会话应答"),
        ACCOUNT_SETTING_SENT("已发送 0x80 账号绑定"),
        WAITING_ACCOUNT_ACK("等待账号绑定应答"),
        AUTHENTICATED("会话与账号绑定完成"),
        ACTION_SENT("车控指令已发送"),
        DISCONNECTED("连接已断开"),
        ERROR("错误")
    }

    private data class WriteOperation(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val writeType: Int,
        val msgId: Int,
        val serial: Int,
        val label: String,
        val onComplete: (() -> Unit)?
    )

    private data class IngeekReassemblyState(val expected: Int, val body: MutableList<Byte>)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private var currentGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private var sessionMaterial: SessionMaterial? = null
    private var session: InGeekBleSession? = null
    private var pendingActionKey: String? = null
    private var autoConnectAddress: String? = null
    private var autoConnectName: String? = null

    private var servicesReady = false
    private var notificationsReady = false
    private var ffe1Ready = false
    private var ffe1Hex: String? = null
    private var authStarted = false
    private var auth02Done = false
    private var session03Done = false
    private var account80Done = false
    private var authenticated = false
    private var waitingForBond = false
    private var connectingAddress: String? = null
    private var serviceDiscoveryStarted = false

    private var mtuPayloadChunkSize = 14
    private var serialCounter = 4
    private var currentStage = Stage.IDLE

    private var scanning = false
    private var scanStopRunnable: Runnable? = null
    private var receiverRegistered = false
    private var closed = false

    private var autoProximityEnabled = false
    private var bluetoothEnableRequested = false
    private var proximityTick: Runnable? = null
    private var proximityProfileProvider: (() -> VehicleProfile?)? = null
    private var proximityEnableRequester: (() -> Unit)? = null
    private var currentRssi: Int? = null
    private var proximityTargetMatched = false

    private val writeQueue = ArrayDeque<WriteOperation>()
    private var writeInFlight = false
    private val txFrameHashes = LinkedHashSet<String>()
    private val pinsByAddress = mutableMapOf<String, PairingPinCandidates>()
    private val randomByAddress = mutableMapOf<String, ByteArray>()
    private val pendingActionBySerial = mutableMapOf<Int, String>()
    private val actionTimeoutRunnables = mutableMapOf<Int, Runnable>()

    private val extendedAssembler = BleFrameAssembler(BleWholeProtocol.FrameFormat.INGEEK_EXTENDED)
    private val ingeekReassemblies = linkedMapOf<Pair<Int, Int>, IngeekReassemblyState>()

    private var connectTimeout: Runnable? = null
    private var handshakeTimeout: Runnable? = null
    private var accountTimeout: Runnable? = null
    private var bondTimeout: Runnable? = null

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> handlePairingRequest(intent)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> handleBondStateChanged(intent)
            }
        }
    }

    init {
        registerPairingReceiver()
    }

    fun setAutoPairEnabled(enabled: Boolean) {
        // The receiver reads this live through the effective config helper below.
        autoPairOverride = enabled
    }

    private var autoPairOverride: Boolean? = null
    private fun autoPairEnabled(): Boolean = autoPairOverride ?: config.autoPairEnabled

    val isAuthenticated: Boolean get() = authenticated

    /**
     * True only after the complete FFE1 pairing check, 0x02 authentication,
     * 0x03 session establishment and 0x80 account binding have all succeeded.
     * A command sent from this state can use the active 0x04 vehicle channel.
     */
    fun isSessionReady(): Boolean =
        authenticated && ffe1Ready && auth02Done && session03Done &&
            account80Done && servicesReady && notificationsReady

    val isVehicleNearby: Boolean get() = proximityTargetMatched
    val lastProximityRssi: Int? get() = currentRssi

    val currentSessionSnapshot: SessionSnapshot?
        get() = session?.snapshot()

    /**
     * Consumer-mode proximity watcher. The host Activity owns the Bluetooth
     * enable prompt; this controller runs a low-power 5-second scan every 20 seconds
     * (5s scan / 15s idle) while disconnected and silently switches to BLE once nearby.
     */
    fun startAutoProximityWatch(
        profileProvider: () -> VehicleProfile?,
        onRequestEnableBluetooth: () -> Unit
    ) = runOnMain {
        if (closed) return@runOnMain
        autoProximityEnabled = true
        proximityProfileProvider = profileProvider
        proximityEnableRequester = onRequestEnableBluetooth
        if (adapter?.isEnabled == true) bluetoothEnableRequested = false
        scheduleProximityTick(0)
    }

    fun stopAutoProximityWatch() = runOnMain {
        autoProximityEnabled = false
        proximityProfileProvider = null
        proximityEnableRequester = null
        bluetoothEnableRequested = false
        proximityTick?.let(mainHandler::removeCallbacks)
        proximityTick = null
        stopScanInternal()
    }

    fun scanForDevices(durationMs: Long = config.scanTimeoutMs) = runOnMain {
        startScanInternal(durationMs)
    }

    fun connectAndAuthenticate(
        device: BluetoothDevice,
        material: SessionMaterial,
        actionAfterAuth: String? = null
    ) = runOnMain {
        if (closed) return@runOnMain
        if (isSessionReady()) {
            if (actionAfterAuth != null) sendVehicleAction(actionAfterAuth)
            return@runOnMain
        }
        pendingActionKey = actionAfterAuth
        sessionMaterial = material
        session = try {
            InGeekBleSession(material.rootKey16, material.infoBin147, material.edfBin)
        } catch (error: Throwable) {
            fail("钥匙材料初始化失败", error)
            return@runOnMain
        }
        rememberPairingPins(device, material)
        connectGatt(device)
    }

    fun executeActionWithAutoConnect(
        device: BluetoothDevice,
        material: SessionMaterial,
        actionKey: String
    ) = runOnMain {
        if (isSessionReady()) {
            sendVehicleAction(actionKey)
        } else {
            connectAndAuthenticate(device, material, actionKey)
        }
    }

    /**
     * Scans for the configured/active peripheral and dispatches the requested
     * action after the session and mandatory 0x80 account binding are complete.
     */
    fun executeActionWithAutoConnect(
        actionKey: String,
        material: SessionMaterial,
        targetAddress: String? = null,
        targetName: String? = null
    ) = runOnMain {
        if (isSessionReady()) {
            sendVehicleAction(actionKey)
            return@runOnMain
        }
        sessionMaterial = material
        pendingActionKey = actionKey
        autoConnectAddress = targetAddress ?: material.bleMac ?: config.activeBleMac
        autoConnectName = targetName ?: material.bleName ?: config.activeBleName
        startScanInternal(config.scanTimeoutMs)
    }

    fun sendVehicleAction(actionKey: String): Boolean {
        var accepted = false
        runOnMain {
            if (!isSessionReady()) {
                if (autoProximityEnabled || currentGatt != null || scanning) {
                    pendingActionKey = actionKey
                    accepted = true
                    listener?.onChannelModeChanged(
                        false,
                        currentRssi,
                        "蓝牙数字钥匙正在连接，指令已排队"
                    )
                } else {
                    fail("蓝牙数字钥匙会话尚未建立")
                }
                return@runOnMain
            }
            val command = try {
                TBoxTlvBuilder.buildAction(actionKey, temperatureCelsius = 24.0, durationMin = 10)
            } catch (error: Throwable) {
                fail("车控动作参数不合法：$actionKey", error)
                return@runOnMain
            }
            accepted = transmitEncryptedTlv(command.actionKey, command.tlv, command.actionKey)
        }
        return accepted
    }

    @Suppress("UNUSED_PARAMETER")
    fun sendVehicleAction(
        actionKey: String,
        temperatureCelsius: Double,
        durationMin: Int = 10
    ): Boolean {
        var accepted = false
        runOnMain {
            if (!isSessionReady()) {
                if (autoProximityEnabled || currentGatt != null || scanning) {
                    pendingActionKey = actionKey
                    accepted = true
                    listener?.onChannelModeChanged(
                        false,
                        currentRssi,
                        "蓝牙数字钥匙正在连接，指令已排队"
                    )
                } else {
                    fail("蓝牙数字钥匙会话尚未建立")
                }
                return@runOnMain
            }
            val command = try {
                TBoxTlvBuilder.buildAction(actionKey, temperatureCelsius, durationMin)
            } catch (error: Throwable) {
                fail("车控动作参数不合法：$actionKey", error)
                return@runOnMain
            }
            accepted = transmitEncryptedTlv(command.actionKey, command.tlv, command.actionKey)
        }
        return accepted
    }

    fun sendCustomTlv(hexString: String): Boolean {
        var accepted = false
        runOnMain {
            if (!authenticated) {
                fail("会话尚未建立，无法发送自定义 TLV")
                return@runOnMain
            }
            val tlv = try {
                TBoxTlvBuilder.customTlv(hexString)
            } catch (error: Throwable) {
                fail("自定义 TLV 格式不合法", error)
                return@runOnMain
            }
            accepted = transmitEncryptedTlv("custom_tlv", tlv, "自定义 TLV")
        }
        return accepted
    }

    fun disconnect() = runOnMain {
        cancelTimers()
        currentGatt?.disconnect()
        currentGatt?.close()
        currentGatt = null
        resetLinkState()
        currentRssi = null
        proximityTargetMatched = false
        currentStage = Stage.DISCONNECTED
        emitStage()
        listener?.onConnectionStateChanged(false, currentStage.label)
        listener?.onChannelModeChanged(false, null, "远程云端连接 (HTTP)")
        if (autoProximityEnabled) scheduleProximityTick(1_500)
    }

    fun close() {
        closed = true
        mainHandler.post {
            autoProximityEnabled = false
            proximityTick?.let(mainHandler::removeCallbacks)
            proximityTick = null
            stopScanInternal()
            unregisterPairingReceiver()
            cancelTimers()
            writeQueue.clear()
            currentGatt?.disconnect()
            currentGatt?.close()
            currentGatt = null
            authenticated = false
            currentStage = Stage.DISCONNECTED
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun scheduleProximityTick(delayMs: Long) {
        if (!autoProximityEnabled || closed) return
        proximityTick?.let(mainHandler::removeCallbacks)
        proximityTick = Runnable {
            proximityTick = null
            runProximityCycle()
        }.also { mainHandler.postDelayed(it, delayMs.coerceAtLeast(0)) }
    }

    private fun runProximityCycle() {
        if (!autoProximityEnabled || closed) return
        val profile = proximityProfileProvider?.invoke()
        if (profile == null || !profileHasCompleteKeyMaterial(profile)) {
            listener?.onChannelModeChanged(
                false,
                null,
                "远程网络控制 · 等待同步蓝牙数字钥匙"
            )
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
            return
        }

        val currentAdapter = adapter
        if (currentAdapter == null) {
            listener?.onError("当前设备不支持蓝牙", null)
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
            return
        }
        if (!currentAdapter.isEnabled) {
            listener?.onChannelModeChanged(false, null, "远程网络控制 · 云端在线")
            if (!bluetoothEnableRequested) {
                bluetoothEnableRequested = true
                proximityEnableRequester?.invoke()
            }
            scheduleProximityTick(BLUETOOTH_RETRY_MS)
            return
        }
        bluetoothEnableRequested = false

        if (isSessionReady() || currentGatt != null || scanning) {
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
            return
        }

        val material = buildSessionMaterial(profile)
        if (material == null) {
            listener?.onError(
                "蓝牙数字钥匙材料不完整；请从云端重新拉取并解封钥匙",
                null
            )
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
            return
        }
        sessionMaterial = material
        session = runCatching {
            InGeekBleSession(material.rootKey16, material.infoBin147, material.edfBin)
        }.getOrNull()
        if (session == null) {
            listener?.onError("蓝牙数字钥匙材料初始化失败", null)
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
            return
        }
        autoConnectAddress = profile.bleMac.ifBlank { null }
        autoConnectName = profile.bleName.ifBlank { null }
        pendingActionKey = null
        proximityTargetMatched = false
        startScanInternal(PROXIMITY_SCAN_WINDOW_MS, ScanSettings.SCAN_MODE_LOW_POWER)
        scheduleProximityTick(PROXIMITY_INTERVAL_MS)
    }

    private fun profileHasCompleteKeyMaterial(profile: VehicleProfile): Boolean {
        if (profile.rootKeyHex.isBlank() || profile.infoHex.isBlank() || profile.edfHex.isBlank()) {
            return false
        }
        if (profile.userId.isBlank() || profile.deviceId.isBlank()) return false
        val material = buildSessionMaterial(profile) ?: return false
        return material.rootKey16.size == 16 &&
            material.infoBin147.size == 147 &&
            material.edfBin.size in 68..74
    }

    private fun buildSessionMaterial(profile: VehicleProfile): SessionMaterial? = runCatching {
        val root = profile.rootKeyBytes()
        val info = profile.infoBytes()
        val edf = profile.edfBytes()
        if (root.size != 16 || info.size != 147 || edf.size !in 68..74) return@runCatching null
        SessionMaterial(
            rootKey16 = root,
            infoBin147 = info,
            edfBin = edf,
            accountBinding = AccountBinding(
                accountType = profile.accountType,
                userId = profile.userId,
                deviceId = profile.deviceId
            ),
            bleMac = profile.bleMac.ifBlank { null },
            bleName = profile.bleName.ifBlank { null }
        )
    }.getOrNull()

    private fun startScanInternal(
        durationMs: Long,
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_POWER
    ) {
        if (closed) return
        val permissionError = scanPermissionError()
        if (permissionError != null) {
            fail(permissionError)
            return
        }
        val currentAdapter = adapter
        if (currentAdapter == null || !currentAdapter.isEnabled) {
            fail("蓝牙未开启或设备不支持 BLE")
            return
        }
        val currentScanner = scanner
        if (currentScanner == null) {
            fail("无法获取 BLE 扫描器")
            return
        }
        stopScanInternal()
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(
                if (scanMode == ScanSettings.SCAN_MODE_LOW_POWER) {
                    ScanSettings.MATCH_MODE_STICKY
                } else {
                    ScanSettings.MATCH_MODE_AGGRESSIVE
                }
            )
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        scanning = true
        currentStage = Stage.SCANNING
        emitStage()
        try {
            currentScanner.startScan(null, settings, leScanCallback)
        } catch (error: Throwable) {
            scanning = false
            fail("启动 BLE 扫描失败", error)
            return
        }
        val stopTask = Runnable { if (scanning) stopScanInternal() }
        scanStopRunnable = stopTask
        mainHandler.postDelayed(stopTask, durationMs.coerceAtLeast(1_000))
    }

    private fun stopScanInternal() {
        scanStopRunnable?.let(mainHandler::removeCallbacks)
        scanStopRunnable = null
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(leScanCallback) }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            mainHandler.post { handleScanResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result -> mainHandler.post { handleScanResult(result) } }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                scanning = false
                fail("BLE 扫描失败，错误码 $errorCode")
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        if (closed) return
        val device = result.device ?: return
        val record = result.scanRecord
        val random = BlePairingPinCalculator.extractAdvRandomBytes(record)
        val randomHex = random?.toHex() ?: ""
        if (random != null) randomByAddress[device.address] = random

        val name = safeDeviceName(device) ?: record?.deviceName
        val effectiveMac = config.activeBleMac
            ?: sessionMaterial?.bleMac
            ?: BlePairingPinCalculator.extractBleNameSuffix(name)
            ?: device.address
        val pins = runCatching {
            BlePairingPinCalculator.calculateCandidates(
                activeMacOr12Hex = effectiveMac,
                bleName = name ?: config.activeBleName ?: sessionMaterial?.bleName,
                random4 = random ?: ByteArray(4)
            )
        }.getOrNull()
        if (pins != null) pinsByAddress[device.address] = pins

        if (pins != null) {
            listener?.onDeviceScanned(device, result.rssi, randomHex, pins)
        }

        val proximityMatches = autoProximityEnabled &&
            sessionMaterial != null &&
            proximityTargetMatches(device, name, result)
        if (proximityMatches) {
            proximityTargetMatched = true
            currentRssi = result.rssi
            listener?.onChannelModeChanged(
                false,
                result.rssi,
                "发现附近车辆蓝牙 · 正在自动切换蓝牙控制..."
            )
            pins?.primaryPin?.let { pin ->
                listener?.onPairingPinAvailable(pin, device.address)
            }
            stopScanInternal()
            connectAndAuthenticate(device, sessionMaterial!!, null)
            return
        }

        val targetMatches = autoConnectTargetMatches(device, name, result)
        if (targetMatches && pendingActionKey != null && sessionMaterial != null) {
            stopScanInternal()
            connectAndAuthenticate(device, sessionMaterial!!, pendingActionKey)
        }
    }

    private fun proximityTargetMatches(
        device: BluetoothDevice,
        name: String?,
        result: ScanResult
    ): Boolean {
        val profile = sessionMaterial
        val configuredMac = profile?.bleMac ?: config.activeBleMac
        val configuredName = profile?.bleName ?: config.activeBleName
        val normalizedAddress = device.address.replace(":", "")
        val configuredMacCompact = configuredMac.orEmpty().replace(":", "").replace("-", "")
        if (configuredMacCompact.isNotBlank() &&
            normalizedAddress.equals(configuredMacCompact, ignoreCase = true)
        ) {
            return true
        }
        if (!configuredName.isNullOrBlank() &&
            configuredName.equals(name, ignoreCase = true)
        ) {
            return true
        }
        val upperName = name?.trim()?.uppercase().orEmpty()
        if (CHERY_VEHICLE_NAME.matches(upperName)) return true
        if (CHERY_DK_NAME.matches(upperName)) return true
        if (upperName.contains("INGEEK")) return true
        val uuids = result.scanRecord?.serviceUuids?.map {
            it.uuid.toString().lowercase()
        }.orEmpty()
        return uuids.any {
            it.endsWith("0000fff0-0000-1000-8000-00805f9b34fb") ||
                it.endsWith("0000ffe0-0000-1000-8000-00805f9b34fb")
        }
    }

    private fun autoConnectTargetMatches(
        device: BluetoothDevice,
        name: String?,
        result: ScanResult
    ): Boolean {
        if (autoConnectAddress != null && autoConnectAddress.equals(device.address, ignoreCase = true)) return true
        if (autoConnectName != null && autoConnectName.equals(name, ignoreCase = true)) return true
        val upperName = name?.uppercase().orEmpty()
        if (BlePairingPinCalculator.extractBleNameSuffix(name) != null) return true
        if (upperName.contains("INGEEK")) return true
        val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty()
        return uuids.any { it.endsWith("0000fff0-0000-1000-8000-00805f9b34fb") ||
            it.endsWith("0000ffe0-0000-1000-8000-00805f9b34fb") }
    }

    private fun rememberPairingPins(device: BluetoothDevice, material: SessionMaterial) {
        val random = randomByAddress[device.address] ?: ByteArray(4)
        val mac = material.bleMac ?: config.activeBleMac ?: device.address
        val name = material.bleName ?: config.activeBleName ?: safeDeviceName(device)
        runCatching {
            BlePairingPinCalculator.calculateCandidates(mac, name, random)
        }.getOrNull()?.let { pinsByAddress[device.address] = it }
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (closed) return
        val permissionError = connectPermissionError()
        if (permissionError != null) {
            fail(permissionError)
            return
        }
        resetLinkState()
        connectingAddress = device.address
        currentStage = Stage.CONNECTING
        emitStage()
        listener?.onConnectionStateChanged(false, currentStage.label)
        currentGatt = try {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: Throwable) {
            fail("connectGatt 调用失败", error)
            return
        }
        if (currentGatt == null) {
            fail("connectGatt 返回空 GATT")
            return
        }
        connectTimeout = Runnable {
            if (currentStage == Stage.CONNECTING) fail("BLE GATT 连接超时")
        }.also { mainHandler.postDelayed(it, config.connectTimeoutMs) }
    }

    private fun resetLinkState() {
        cancelTimers()
        writeQueue.clear()
        writeInFlight = false
        extendedAssembler.reset()
        ingeekReassemblies.clear()
        txFrameHashes.clear()
        servicesReady = false
        notificationsReady = false
        ffe1Ready = false
        ffe1Hex = null
        authStarted = false
        auth02Done = false
        session03Done = false
        account80Done = false
        authenticated = false
        waitingForBond = false
        serviceDiscoveryStarted = false
        mtuPayloadChunkSize = 14
        writeCharacteristic = null
        notifyCharacteristic = null
        statusCharacteristic = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post { handleConnectionState(gatt, status, newState) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                if (gatt !== currentGatt) return@post
                mtuPayloadChunkSize = if (status == BluetoothGatt.GATT_SUCCESS) {
                    minOf(config.maxPayloadChunkSize, (mtu - 9).coerceAtLeast(14))
                } else {
                    14
                }
                logPacket(
                    "INFO",
                    "-",
                    0,
                    "",
                    "MTU=${mtu}, status=$status, payloadChunk=$mtuPayloadChunkSize"
                )
                startServiceDiscovery(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post { handleServicesDiscovered(gatt, status) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            mainHandler.post { handleDescriptorWrite(gatt, descriptor, status) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            mainHandler.post { handleCharacteristicRead(gatt, characteristic, characteristic.value, status) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            mainHandler.post { handleCharacteristicChanged(gatt, characteristic, characteristic.value) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            mainHandler.post { finishWrite(status == BluetoothGatt.GATT_SUCCESS, status) }
        }
    }

    private fun handleConnectionState(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (gatt !== currentGatt) {
            runCatching { gatt.close() }
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                connectTimeout?.let(mainHandler::removeCallbacks)
                connectTimeout = null
                currentStage = Stage.CONNECTED
                emitStage()
                listener?.onConnectionStateChanged(true, currentStage.label)
                if (autoProximityEnabled) {
                    listener?.onChannelModeChanged(
                        false,
                        currentRssi,
                        "附近蓝牙数字钥匙正在握手..."
                    )
                }
                val mtuRequested = runCatching { gatt.requestMtu(config.requestMtu) }.getOrDefault(false)
                if (!mtuRequested) {
                    mtuPayloadChunkSize = 14
                    startServiceDiscovery(gatt)
                } else {
                    // Some stacks do not deliver onMtuChanged reliably; a delayed
                    // fallback keeps discovery moving without overlapping MTU I/O.
                    mainHandler.postDelayed({ startServiceDiscovery(gatt) }, 1_200)
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                if (gatt === currentGatt) {
                    currentGatt = null
                    runCatching { gatt.close() }
                }
                resetLinkState()
                currentRssi = null
                proximityTargetMatched = false
                currentStage = Stage.DISCONNECTED
                emitStage()
                listener?.onConnectionStateChanged(false, currentStage.label)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener?.onError("GATT 连接断开，status=$status", null)
                }
                listener?.onChannelModeChanged(false, null, "远程云端连接 (HTTP)")
                if (autoProximityEnabled) scheduleProximityTick(1_500)
            }
        }
    }

    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (gatt !== currentGatt || serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        if (!gatt.discoverServices()) fail("启动 GATT 服务发现失败")
    }

    private fun handleServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (gatt !== currentGatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("GATT 服务发现失败，status=$status")
            return
        }
        val vehicleService = gatt.getService(config.vehicleServiceUuid)
        writeCharacteristic = vehicleService?.getCharacteristic(config.writeCharacteristicUuid)
        notifyCharacteristic = vehicleService?.getCharacteristic(config.notifyCharacteristicUuid)
        val statusService = gatt.getService(config.statusServiceUuid)
        statusCharacteristic = statusService?.getCharacteristic(config.statusCharacteristicUuid)

        val writable = writeCharacteristic
        val notifiable = notifyCharacteristic
        if (writable == null || notifiable == null) {
            fail("未发现 FFF1 写特征或 FFF2 通知特征")
            return
        }
        servicesReady = true
        currentStage = Stage.SERVICES_DISCOVERED
        emitStage()

        val notificationsEnabled = runCatching {
            gatt.setCharacteristicNotification(notifiable, true)
        }.getOrDefault(false)
        if (!notificationsEnabled) {
            fail("启用 FFF2 本地通知失败")
            return
        }
        val cccd = notifiable.getDescriptor(config.cccdUuid)
        if (cccd == null) {
            notificationsReady = true
            readStatusCharacteristic(gatt)
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(cccd)) {
                fail("写入 FFF2 CCCD 失败")
                return
            }
        }
        maybeBeginHandshake()
    }

    private fun handleDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        if (gatt !== currentGatt) return
        if (descriptor.uuid == config.cccdUuid) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("FFF2 通知订阅失败，status=$status")
                return
            }
            notificationsReady = true
            readStatusCharacteristic(gatt)
            maybeBeginHandshake()
        }
    }

    private fun readStatusCharacteristic(gatt: BluetoothGatt) {
        val status = statusCharacteristic
        if (status == null) {
            ffe1Ready = true
            maybeBeginHandshake()
            return
        }
        if (!gatt.readCharacteristic(status)) {
            fail("读取 FFE1 配对状态失败")
        }
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int
    ) {
        if (gatt !== currentGatt) return
        if (characteristic.uuid != config.statusCharacteristicUuid) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("FFE1 配对状态读取失败，status=$status")
            return
        }
        val bytes = value ?: ByteArray(0)
        ffe1Hex = bytes.toHex()
        currentStage = Stage.WAITING_BOND
        emitStage()
        when (bytes.firstOrNull()?.toInt()?.and(0xFF)) {
            0x81 -> {
                ffe1Ready = true
                waitingForBond = false
                maybeBeginHandshake()
            }
            0x10, 0x20 -> requestBond(gatt.device)
            null -> {
                ffe1Ready = true
                maybeBeginHandshake()
            }
            else -> {
                ffe1Ready = true
                maybeBeginHandshake()
            }
        }
    }

    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?
    ) {
        if (gatt !== currentGatt || value == null || value.isEmpty()) return
        when (characteristic.uuid) {
            config.statusCharacteristicUuid -> {
                ffe1Hex = value.toHex()
                when (value[0].toInt() and 0xFF) {
                    0x81 -> {
                        ffe1Ready = true
                        waitingForBond = false
                        maybeBeginHandshake()
                    }
                    0x10, 0x20 -> requestBond(gatt.device)
                }
            }
            config.notifyCharacteristicUuid -> processNotification(value)
        }
    }

    private fun requestBond(device: BluetoothDevice) {
        if (waitingForBond) return
        waitingForBond = true
        currentStage = Stage.WAITING_BOND
        emitStage()
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            waitingForBond = false
            ffe1Ready = true
            maybeBeginHandshake()
            return
        }
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            scheduleBondTimeout()
            return
        }
        val started = runCatching { device.createBond() }.getOrDefault(false)
        if (!started) {
            fail("发起系统蓝牙配对失败")
            return
        }
        scheduleBondTimeout()
    }

    private fun scheduleBondTimeout() {
        bondTimeout?.let(mainHandler::removeCallbacks)
        bondTimeout = Runnable {
            if (waitingForBond) fail("等待系统蓝牙配对超时")
        }.also { mainHandler.postDelayed(it, config.handshakeTimeoutMs) }
    }

    private fun handlePairingRequest(intent: Intent) {
        if (!autoPairEnabled()) return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val pin = pinsByAddress[device.address]?.primaryPin
            ?: runCatching {
                val random = randomByAddress[device.address] ?: return@runCatching null
                val mac = config.activeBleMac ?: sessionMaterial?.bleMac ?: device.address
                val name = config.activeBleName ?: sessionMaterial?.bleName ?: safeDeviceName(device)
                BlePairingPinCalculator.calculateCandidates(mac, name, random).primaryPin
            }.getOrNull()
        if (pin.isNullOrEmpty()) {
            listener?.onError("配对请求已收到，但尚无可用动态 PIN", null)
            return
        }
        runCatching {
            device.setPin(pin.toByteArray(Charsets.UTF_8))
            device.setPairingConfirmation(true)
        }.onFailure { error ->
            listener?.onError("自动填写配对码失败（需要系统蓝牙权限）", error)
        }
    }

    private fun handleBondStateChanged(intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
        if (device.address != connectingAddress) return
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                bondTimeout?.let(mainHandler::removeCallbacks)
                bondTimeout = null
                waitingForBond = false
                ffe1Ready = true
                maybeBeginHandshake()
            }
            BluetoothDevice.BOND_NONE -> {
                if (waitingForBond) {
                    waitingForBond = false
                    fail("系统蓝牙配对未完成")
                }
            }
        }
    }

    private fun maybeBeginHandshake() {
        if (closed || authenticated || authStarted) return
        val material = sessionMaterial ?: return
        val activeSession = session ?: return
        if (!servicesReady || !notificationsReady || !ffe1Ready) return
        authStarted = true
        auth02Done = false
        session03Done = false
        account80Done = false
        currentStage = Stage.AUTH_REQUEST_SENT
        emitStage()
        startHandshakeTimeout()

        val payload = activeSession.build0x02AuthPayload()
        val frames = BleWholeProtocol.packIngeekExtended(
            BleWholeProtocol.MSG_AUTH_REQUEST,
            serialNum = 1,
            payload = payload,
            maxPayloadChunkSize = mtuPayloadChunkSize
        )
        transmitFrames(
            frames = frames,
            msgId = BleWholeProtocol.MSG_AUTH_REQUEST,
            serialNum = 1,
            label = "RequestAuth(0x02)",
            summary = "edf(${material.edfBin.size}B)+info(147B)，共 ${payload.size}B"
        ) {
            currentStage = Stage.WAITING_AUTH_CHALLENGE
            emitStage()
        }
    }

    private fun processNotification(raw: ByteArray) {
        val rawHex = raw.toHex()
        if (rawHex.isEmpty()) return
        if (txFrameHashes.any { it == rawHex || rawHex.startsWith(it) || it.startsWith(rawHex) }) return
        val packet = BleWholeProtocol.parseIngeekPacket(raw)
        if (packet != null) {
            logPacket(
                "RX",
                "0x%02X".format(packet.messageId),
                packet.serialNum,
                rawHex,
                "FFF2 fragment ${packet.fragment.size}B, flag=0x%02X".format(packet.packFlag)
            )
        } else {
            logPacket("RX", "-", 0, rawHex, "FFF2 notification")
        }

        val completeFrames = try {
            reassembleIngeekPacket(raw)
        } catch (error: Throwable) {
            extendedAssembler.reset()
            fail("FFF2 响应重组失败", error)
            return
        }
        completeFrames.forEach(::handleCompleteFrame)
    }

    private fun reassembleIngeekPacket(raw: ByteArray): List<BleWholeProtocol.Frame> {
        val packet = BleWholeProtocol.parseIngeekPacket(raw) ?: return emptyList()
        val key = packet.messageId to packet.serialNum
        if (packet.packFlag == BleWholeProtocol.INGEEK_SINGLE_PACKET) {
            if (packet.fragment.size < packet.lengthField) return emptyList()
            return listOf(
                BleWholeProtocol.Frame(
                    format = BleWholeProtocol.FrameFormat.INGEEK_EXTENDED,
                    version = 1,
                    encryptionType = 0,
                    serialNum = packet.serialNum,
                    messageId = packet.messageId,
                    payload = packet.fragment.copyOfRange(0, packet.lengthField),
                    raw = raw
                )
            )
        }
        if ((packet.packFlag and BleWholeProtocol.INGEEK_START_MASK) == BleWholeProtocol.INGEEK_START_MASK) {
            ingeekReassemblies[key] = IngeekReassemblyState(
                expected = packet.lengthField,
                body = packet.fragment.toMutableList()
            )
        } else {
            val state = ingeekReassemblies[key] ?: return emptyList()
            state.body.addAll(packet.fragment.toList())
        }
        val state = ingeekReassemblies[key] ?: return emptyList()
        if (state.body.size < state.expected) return emptyList()
        ingeekReassemblies.remove(key)
        val payload = ByteArray(state.expected) { state.body[it] }
        return listOf(
            BleWholeProtocol.Frame(
                format = BleWholeProtocol.FrameFormat.INGEEK_EXTENDED,
                version = 1,
                encryptionType = 0,
                serialNum = packet.serialNum,
                messageId = packet.messageId,
                payload = payload,
                raw = raw
            )
        )
    }

    private fun handleCompleteFrame(frame: BleWholeProtocol.Frame) {
        when (frame.messageId) {
            BleWholeProtocol.MSG_BASE_RESPONSE,
            BleWholeProtocol.MSG_VEHICLE_CONTROL_RESPONSE -> handleBaseResponseFrame(frame)
            BleWholeProtocol.MSG_VEHICLE_INFO -> {
                val parsed = TBoxTlvBuilder.parseResponse(frame.messageId, frame.payload)
                listener?.onVehicleResponse(parsed)
                logPacket(
                    "INFO",
                    "0x10",
                    frame.serialNum,
                    frame.payload.toHex(),
                    parsed.summaryText
                )
            }
            else -> logPacket(
                "INFO",
                "0x%02X".format(frame.messageId),
                frame.serialNum,
                frame.payload.toHex(),
                "未处理的整车消息"
            )
        }
    }

    private fun handleBaseResponseFrame(frame: BleWholeProtocol.Frame) {
        val base = BleBaseResponse.parse(frame.payload)
        if (base == null) {
            if (frame.messageId == BleWholeProtocol.MSG_VEHICLE_CONTROL_RESPONSE) {
                decryptAndParseVehicleResponse(frame, null)
                return
            }
            fail("0x%02X 响应体不足 6 字节".format(frame.messageId))
            return
        }
        logPacket(
            "INFO",
            "0x%02X".format(frame.messageId),
            base.answerSerial,
            frame.payload.toHex(),
            "answerId=0x%02X stage=0x%02X res=0x%02X err=0x%02X"
                .format(base.answerId, base.stage, base.res, base.errCode)
        )
        when (base.answerId) {
            0x02 -> handleAuthChallenge(base)
            0x03 -> handleSessionResponse(base)
            0x04 -> decryptAndParseVehicleResponse(frame, base)
            else -> {
                if (base.errCode != 0) {
                    fail("车端返回错误 answerId=0x%02X err=0x%02X".format(base.answerId, base.errCode))
                }
            }
        }
    }

    private fun handleAuthChallenge(base: BleBaseResponse) {
        if (auth02Done) return
        if (base.errCode != 0) {
            fail("0x02 身份认证被拒绝，err=0x%02X".format(base.errCode))
            return
        }
        if (base.data.size < 32) {
            fail("0x02 挑战载荷不足 32B")
            return
        }
        val activeSession = session ?: run {
            fail("会话上下文未初始化")
            return
        }
        val challenge = base.data.copyOfRange(0, 32)
        val encryptedSessionRequest = try {
            activeSession.process0x02ChallengeAndBuild0x03(challenge)
        } catch (error: Throwable) {
            fail("0x02 挑战解密或 0x03 组装失败", error)
            return
        }
        auth02Done = true
        currentStage = Stage.SESSION_REQUEST_SENT
        emitStage()
        val frames = BleWholeProtocol.packIngeekExtended(
            BleWholeProtocol.MSG_SESSION_REQUEST,
            serialNum = 2,
            payload = encryptedSessionRequest,
            maxPayloadChunkSize = mtuPayloadChunkSize
        )
        transmitFrames(
            frames,
            BleWholeProtocol.MSG_SESSION_REQUEST,
            2,
            "RequestSession(0x03)",
            "80B 根密钥 CBC 封装载荷"
        ) {
            currentStage = Stage.WAITING_SESSION_RESPONSE
            emitStage()
        }
    }

    private fun handleSessionResponse(base: BleBaseResponse) {
        if (session03Done) return
        if (base.errCode != 0) {
            fail("0x03 会话建立被拒绝，err=0x%02X".format(base.errCode))
            return
        }
        val activeSession = session ?: run {
            fail("会话上下文未初始化")
            return
        }
        if (base.data.size != 80) {
            fail("0x03 会话应答必须为 80B，实际 ${base.data.size}B")
            return
        }
        val verified = activeSession.verify0x03SessionResponse(base.data)
        if (!verified) {
            fail("0x03 会话应答校验失败")
            return
        }
        session03Done = true
        startAccountBinding(activeSession)
    }

    private fun startAccountBinding(activeSession: InGeekBleSession) {
        val material = sessionMaterial ?: run {
            fail("账号绑定材料缺失")
            return
        }
        val accountTlv = try {
            TBoxTlvBuilder.buildAccountSettingTlv(
                material.accountBinding.accountType,
                material.accountBinding.userId,
                material.accountBinding.deviceId
            )
        } catch (error: Throwable) {
            fail("0x80 账号绑定 TLV 构造失败", error)
            return
        }
        val encrypted = try {
            activeSession.encrypt0x04Payload(accountTlv)
        } catch (error: Throwable) {
            fail("0x80 账号绑定加密失败", error)
            return
        }
        serialCounter = 4
        val frames = BleWholeProtocol.packIngeekExtended(
            BleWholeProtocol.MSG_VEHICLE_CONTROL,
            serialNum = 3,
            payload = encrypted,
            maxPayloadChunkSize = mtuPayloadChunkSize
        )
        currentStage = Stage.ACCOUNT_SETTING_SENT
        emitStage()
        accountTimeout?.let(mainHandler::removeCallbacks)
        accountTimeout = Runnable {
            if (!account80Done) fail("等待 0x80 账号绑定应答超时")
        }.also { mainHandler.postDelayed(it, config.accountAckTimeoutMs) }
        transmitFrames(
            frames,
            BleWholeProtocol.MSG_VEHICLE_CONTROL,
            3,
            "AccountSetting(0x80)",
            "serial=3, 明文=${accountTlv.toHex()}"
        ) {
            currentStage = Stage.WAITING_ACCOUNT_ACK
            emitStage()
        }
    }

    private fun decryptAndParseVehicleResponse(
        frame: BleWholeProtocol.Frame,
        base: BleBaseResponse?
    ) {
        val activeSession = session ?: run {
            fail("车辆应答到达时会话未建立")
            return
        }
        val encryptedData = base?.data ?: frame.payload
        if (encryptedData.isEmpty()) {
            if (base != null && base.answerSerial == 3 && base.errCode == 0) {
                completeAccountBinding(emptyTBoxResponse(frame.messageId))
            } else {
                fail("车控应答载荷为空")
            }
            return
        }
        val (plain, cmacValid) = activeSession.decryptIncomingPayload(encryptedData)
        if (!cmacValid) {
            fail("0x%02X 车控应答 AES-CMAC 校验失败".format(frame.messageId))
            return
        }
        if (plain.isEmpty()) {
            fail("0x%02X 车控应答填充校验失败".format(frame.messageId))
            return
        }
        logPacket(
            "RX",
            "0x%02X".format(frame.messageId),
            base?.answerSerial ?: frame.serialNum,
            plain.toHex(),
            "解密成功，CMAC 校验通过"
        )
        val parsed = TBoxTlvBuilder.parseResponse(frame.messageId, plain)
        listener?.onVehicleResponse(parsed)

        val serial = base?.answerSerial ?: frame.serialNum
        if (serial == 3) {
            val resultRejected = parsed.resultCode != null && parsed.resultCode != 1
            if ((base?.errCode ?: 0) != 0 || resultRejected) {
                fail("0x80 账号绑定失败：${parsed.summaryText}")
            } else {
                completeAccountBinding(parsed)
            }
            return
        }
        cancelActionTimeout(serial)
        if ((base?.errCode ?: 0) != 0) {
            listener?.onError(
                "车控应答错误 serial=$serial err=0x%02X：%s"
                    .format(base?.errCode ?: 0, parsed.summaryText),
                null
            )
        }
        pendingActionBySerial.remove(serial)
    }

    private fun emptyTBoxResponse(messageId: Int) = ParsedTBoxResponse(
        messageId = messageId,
        serviceTag = TBoxService.ACCOUNT_SETTING,
        resultCode = 1,
        errorCode = 0,
        summaryText = "账号绑定成功（空业务载荷 ACK）",
        success = true,
        rawHex = ""
    )

    private fun completeAccountBinding(parsed: ParsedTBoxResponse) {
        accountTimeout?.let(mainHandler::removeCallbacks)
        accountTimeout = null
        account80Done = true
        authenticated = true
        currentStage = Stage.AUTHENTICATED
        emitStage()
        session?.snapshot()?.let { listener?.onAuthenticated(it) }
        listener?.onChannelModeChanged(
            true,
            currentRssi,
            "蓝牙数字钥匙已直连"
        )
        logPacket("INFO", "-", 0, "", "会话建立并完成 0x80：${parsed.summaryText}")
        val action = pendingActionKey
        pendingActionKey = null
        if (action != null) {
            mainHandler.postDelayed({ sendVehicleAction(action) }, 120)
        }
    }

    private fun transmitEncryptedTlv(actionKey: String, tlv: ByteArray, summary: String): Boolean {
        val activeSession = session ?: run {
            fail("会话上下文未初始化")
            return false
        }
        val encrypted = try {
            activeSession.encrypt0x04Payload(tlv)
        } catch (error: Throwable) {
            fail("车控 TLV 加密失败", error)
            return false
        }
        val serial = nextSerial()
        val frames = BleWholeProtocol.packIngeekExtended(
            BleWholeProtocol.MSG_VEHICLE_CONTROL,
            serialNum = serial,
            payload = encrypted,
            maxPayloadChunkSize = mtuPayloadChunkSize
        )
        pendingActionBySerial[serial] = actionKey
        scheduleActionTimeout(serial, actionKey)
        currentStage = Stage.ACTION_SENT
        emitStage()
        return transmitFrames(
            frames,
            BleWholeProtocol.MSG_VEHICLE_CONTROL,
            serial,
            "VehicleControl(0x04)/$actionKey",
            "$summary, serial=$serial, TLV=${tlv.toHex()}"
        ) {
            listener?.onActionDispatched(actionKey, serial)
        }
    }

    private fun transmitFrames(
        frames: List<ByteArray>,
        msgId: Int,
        serialNum: Int,
        label: String,
        summary: String,
        onComplete: (() -> Unit)? = null
    ): Boolean {
        val characteristic = writeCharacteristic ?: run {
            fail("FFF1 写特征尚未就绪")
            return false
        }
        if (frames.isEmpty()) {
            fail("待发送帧为空")
            return false
        }
        val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        frames.forEachIndexed { index, frame ->
            writeQueue.add(
                WriteOperation(
                    characteristic = characteristic,
                    data = frame,
                    writeType = writeType,
                    msgId = msgId,
                    serial = serialNum,
                    label = label,
                    onComplete = if (index == frames.lastIndex) onComplete else null
                )
            )
        }
        logPacket("TX", "0x%02X".format(msgId), serialNum, frames.joinToString("") { it.toHex() }, summary)
        pumpWrites()
        return true
    }

    private fun pumpWrites() {
        if (writeInFlight) return
        val operation = writeQueue.pollFirst() ?: return
        val char = operation.characteristic
        char.writeType = operation.writeType
        char.value = operation.data
        val started = runCatching { currentGatt?.writeCharacteristic(char) == true }.getOrDefault(false)
        if (!started) {
            writeQueue.clear()
            inFlightCompletion = null
            fail("GATT 写入失败：${operation.label}")
            return
        }
        writeInFlight = true
        inFlightCompletion = operation.onComplete
        rememberTxFrame(operation.data)
        if (operation.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            mainHandler.postDelayed({ finishWrite(success = true, status = BluetoothGatt.GATT_SUCCESS) }, 25)
        }
    }

    private fun finishWrite(success: Boolean, status: Int) {
        if (!writeInFlight) return
        writeInFlight = false
        if (!success) {
            writeQueue.clear()
            inFlightCompletion = null
            fail("GATT 写入回调失败，status=$status")
            return
        }
        // The completed operation is the one that was in flight. Its callback is
        // held in a small side slot so NO_RESPONSE writes share the same path.
        val completion = inFlightCompletion
        inFlightCompletion = null
        completion?.invoke()
        pumpWrites()
    }

    private var inFlightCompletion: (() -> Unit)? = null

    private fun rememberTxFrame(frame: ByteArray) {
        txFrameHashes.add(frame.toHex())
        while (txFrameHashes.size > 128) txFrameHashes.remove(txFrameHashes.first())
    }

    private fun nextSerial(): Int {
        var candidate = serialCounter
        serialCounter = if (serialCounter >= 0xFFFE) 4 else serialCounter + 1
        while (candidate == 0 || candidate == 1 || candidate == 2 || candidate == 3) {
            candidate = serialCounter
            serialCounter = if (serialCounter >= 0xFFFE) 4 else serialCounter + 1
        }
        return candidate
    }

    private fun scheduleActionTimeout(serial: Int, actionKey: String) {
        cancelActionTimeout(serial)
        val runnable = Runnable {
            if (pendingActionBySerial.remove(serial) != null) {
                listener?.onError("车控动作 $actionKey (serial=$serial) 等待应答超时", null)
            }
            actionTimeoutRunnables.remove(serial)
        }
        actionTimeoutRunnables[serial] = runnable
        mainHandler.postDelayed(runnable, config.actionAckTimeoutMs)
    }

    private fun cancelActionTimeout(serial: Int) {
        actionTimeoutRunnables.remove(serial)?.let(mainHandler::removeCallbacks)
    }

    private fun startHandshakeTimeout() {
        handshakeTimeout?.let(mainHandler::removeCallbacks)
        handshakeTimeout = Runnable {
            if (!authenticated) fail("BLE 握手超时：0x02/0x03 会话未完成")
        }.also { mainHandler.postDelayed(it, config.handshakeTimeoutMs) }
    }

    private fun cancelTimers() {
        connectTimeout?.let(mainHandler::removeCallbacks)
        handshakeTimeout?.let(mainHandler::removeCallbacks)
        accountTimeout?.let(mainHandler::removeCallbacks)
        bondTimeout?.let(mainHandler::removeCallbacks)
        connectTimeout = null
        handshakeTimeout = null
        accountTimeout = null
        bondTimeout = null
        actionTimeoutRunnables.values.toList().forEach(mainHandler::removeCallbacks)
        actionTimeoutRunnables.clear()
    }

    private fun emitStage() {
        listener?.onHandshakeStageChanged(
            currentStage.label,
            ffe1Hex,
            auth02Done,
            session03Done,
            account80Done
        )
    }

    private fun logPacket(
        direction: String,
        msgIdHex: String,
        serial: Int,
        rawHex: String,
        decryptedSummary: String
    ) {
        listener?.onPacketLog(direction, msgIdHex, serial, rawHex, decryptedSummary)
    }

    private fun fail(message: String, throwable: Throwable? = null) {
        currentStage = Stage.ERROR
        emitStage()
        listener?.onError(message, throwable)
        if (autoProximityEnabled) {
            listener?.onChannelModeChanged(false, currentRssi, "远程云端连接 (HTTP)")
            mainHandler.postDelayed({
                if (!isSessionReady() && currentGatt != null) {
                    runCatching { currentGatt?.disconnect() }
                    runCatching { currentGatt?.close() }
                    currentGatt = null
                    resetLinkState()
                    currentRssi = null
                    proximityTargetMatched = false
                    scheduleProximityTick(1_500)
                }
            }, 900L)
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String? =
        runCatching { device.name }.getOrNull()

    private fun scanPermissionError(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                return "缺少 BLUETOOTH_SCAN 权限"
            }
        } else if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "缺少 ACCESS_FINE_LOCATION 权限"
        }
        return null
    }

    private fun connectPermissionError(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return "缺少 BLUETOOTH_CONNECT 权限"
        }
        return null
    }

    private fun registerPairingReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(pairingReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(pairingReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun unregisterPairingReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(pairingReceiver) }
        receiverRegistered = false
    }

    companion object {
        private const val PROXIMITY_SCAN_WINDOW_MS = 5_000L
        private const val PROXIMITY_INTERVAL_MS = 20_000L // 5s scan + 15s idle
        private const val BLUETOOTH_RETRY_MS = 4_000L
        private val CHERY_VEHICLE_NAME = Regex("^CHR[0-9A-F]{12}$")
        private val CHERY_DK_NAME = Regex("^BLE_DK_[0-9A-F]{12}$")
    }
}
