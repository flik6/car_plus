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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
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
import android.os.ParcelUuid
import android.os.SystemClock
import com.chery.carplus.cloud.InGeekCloudProvisioner
import com.chery.carplus.profile.VckInfoParser
import com.chery.carplus.profile.VehicleProfile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    data class NearbyBleVehicle(
        val address: String,
        val name: String,
        val rssi: Int,
        val serviceUuids: List<String>,
        val advRandomHex: String,
        val calculatedPin: String,
        val isMatchedTarget: Boolean,
        val matchReason: String,
        val bondState: Int,
        val lastSeenMs: Long
    )

    data class BleDebugSnapshot(
        val bluetoothEnabled: Boolean,
        val stage: String,
        val scanning: Boolean,
        val autoProximityEnabled: Boolean,
        val isSessionReady: Boolean,
        val targetBleMac: String,
        val targetBleName: String,
        val connectedAddress: String,
        val connectedName: String,
        val currentRssi: Int?,
        val mtuPayloadChunkSize: Int,
        val ffe1Hex: String,
        val servicesReady: Boolean,
        val notificationsReady: Boolean,
        val ffe1Ready: Boolean,
        val auth02Done: Boolean,
        val session03Done: Boolean,
        val account80Done: Boolean,
        val waitingForBond: Boolean,
        val nearbyCheryVehicles: List<NearbyBleVehicle>,
        val totalScannedDevices: Int,
        val recentLogs: List<String>
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

        fun onActionResult(
            actionKey: String,
            serial: Int,
            success: Boolean,
            parsedResponse: ParsedTBoxResponse?,
            errorMessage: String? = null
        ) = Unit

        fun onPairingPinAvailable(pin: String, address: String) = Unit

        fun onManualPairingRequired(pin: String, deviceName: String, address: String) = Unit

        fun onBondCompleted(success: Boolean, address: String) = Unit

        fun onChannelModeChanged(
            isBleConnected: Boolean,
            rssi: Int?,
            statusText: String
        ) = Unit

        fun onRssiUpdated(rssi: Int) = Unit

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

    private data class PendingAction(
        val actionKey: String,
        val temperatureCelsius: Double = 24.0,
        val durationMin: Int = 10
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    @Volatile
    private var currentGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private var sessionMaterial: SessionMaterial? = null
    private var session: InGeekBleSession? = null
    private var pendingAction: PendingAction? = null
    private var autoConnectAddress: String? = null
    private var autoConnectName: String? = null
    private var cachedTargetBleMac: String? = null
    private var cachedTargetBleName: String? = null

    private var servicesReady = false
    private var notificationsReady = false
    private var ffe1Ready = false
    private var ffe1Hex: String? = null
    private var authStarted = false
    private var auth02Done = false
    private var session03Done = false
    private var account80Done = false
    @Volatile
    private var authenticated = false
    private var waitingForBond = false
    private var lastPairingAddress: String? = null
    private var connectingAddress: String? = null
    private var serviceDiscoveryStarted = false

    private var mtuPayloadChunkSize = 14
    private var mtuNegotiated = false
    private var mtuRetryAttempted = false
    private var serialCounter = 4
    private var currentStage = Stage.IDLE

    @Volatile
    private var scanning = false
    private var scanStopRunnable: Runnable? = null
    private var receiverRegistered = false
    @Volatile
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
    private var inFlightWriteSeq = 0
    private var noResponseFallbackRunnable: Runnable? = null
    private val txFrameHashes = LinkedHashSet<String>()
    private val pinsByAddress = mutableMapOf<String, PairingPinCandidates>()
    private val randomByAddress = mutableMapOf<String, ByteArray>()
    private val randomTimestampByAddress = mutableMapOf<String, Long>()
    private val pendingRandomWaitSince = mutableMapOf<String, Long>()
    private var pendingRandomConnectRunnable: Runnable? = null
    private var pendingScanRetryRunnable: Runnable? = null
    private var consecutiveFilteredEmptyScans = 0
    private val nearbyCheryDevices = LinkedHashMap<String, NearbyBleVehicle>()
    private val allScannedAddresses = LinkedHashSet<String>()
    private val debugLogs = ArrayDeque<String>()
    @Volatile
    private var diagnosticScanOnly = false
    private var ffe1ReportedUnbonded = false
    private val pendingActionBySerial = mutableMapOf<Int, String>()
    private val actionTimeoutRunnables = mutableMapOf<Int, Runnable>()

    private val ingeekFrameAssembler = BleFrameAssembler(
        format = BleWholeProtocol.FrameFormat.INGEEK_EXTENDED,
        maximumPayloadLength = 4096
    )

    private var mtuFallbackRunnable: Runnable? = null
    private var connectTimeout: Runnable? = null
    private var handshakeTimeout: Runnable? = null
    private var accountTimeout: Runnable? = null
    private var bondTimeout: Runnable? = null

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> handlePairingRequest(intent)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> handleBondStateChanged(intent)
                BluetoothAdapter.ACTION_STATE_CHANGED -> handleBluetoothAdapterStateChanged(intent)
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
     * Consumer-mode proximity watcher. Uses balanced scan duty cycle with aggressive
     * matching and hardware ScanFilters while staying strictly below Android's
     * 5-scans-per-30s throttle ceiling.
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
        // Defer first scan cycle slightly so Activity launch/resume frames render without BLE IPC jank.
        scheduleProximityTick(250L)
    }

    /**
     * High-priority manual scan/pairing trigger (e.g. tapping the Bluetooth icon or
     * "去配对"). Preempts any slower background scan and immediately starts a
     * LOW_LATENCY + AGGRESSIVE hardware-filtered scan for maximum discovery speed.
     */
    fun triggerImmediatePairingOrConnect(
        profileProvider: () -> VehicleProfile?,
        onRequestEnableBluetooth: () -> Unit,
        durationMs: Long = 10_000L
    ) = runOnMain {
        if (closed) return@runOnMain
        autoProximityEnabled = true
        proximityProfileProvider = profileProvider
        proximityEnableRequester = onRequestEnableBluetooth
        if (isSessionReady()) return@runOnMain

        // Clean up errored GATT first, and NEVER touch active session/material while a GATT handshake is in flight.
        if (currentGatt != null && currentStage == Stage.ERROR) {
            runCatching { currentGatt?.disconnect() }
            runCatching { currentGatt?.close() }
            currentGatt = null
            resetLinkState()
        }
        if (currentGatt != null) return@runOnMain

        val profile = profileProvider()
        if (profile == null || !profileHasCompleteKeyMaterial(profile)) {
            listener?.onChannelModeChanged(false, null, "远程网络控制 · 等待同步蓝牙数字钥匙")
            return@runOnMain
        }
        val currentAdapter = adapter
        if (currentAdapter == null || !currentAdapter.isEnabled) {
            onRequestEnableBluetooth()
            return@runOnMain
        }
        bluetoothEnableRequested = false

        val material = buildSessionMaterial(profile) ?: return@runOnMain
        sessionMaterial = material
        session = runCatching {
            InGeekBleSession(material.rootKey16, material.infoBin147, material.edfBin)
        }.getOrNull() ?: return@runOnMain
        autoConnectAddress = profile.bleMac.ifBlank { null }
        autoConnectName = profile.bleName.ifBlank { null }
        cachedTargetBleMac = autoConnectAddress
        cachedTargetBleName = autoConnectName
        proximityTargetMatched = false
        diagnosticScanOnly = false
        cancelPendingRandomConnect()
        pendingRandomWaitSince.clear()
        pinsByAddress.clear()
        randomByAddress.clear()
        randomTimestampByAddress.clear()

        val useFilter = consecutiveFilteredEmptyScans == 0
        appendDebugLog("主动触发高灵敏度蓝牙扫描 (${durationMs / 1000}s, LOW_LATENCY, filter=$useFilter)")
        startScanInternal(
            durationMs = durationMs,
            scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
            useHardwareFilter = useFilter,
            forcePreempt = true,
            diagnosticOnly = false
        )
        scheduleProximityTick(durationMs + 4_000L)
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
        diagnosticScanOnly = false
        startScanInternal(durationMs)
    }

    /**
     * Runs a high-sensitivity BLE discovery scan specifically for the Debug Diagnostics panel
     * so all nearby Chery / InGeek vehicles (matched and non-matched) appear immediately.
     */
    fun startDiagnosticScan(durationMs: Long = 10_000L) = runOnMain {
        refreshCachedTargetIdentity()
        appendDebugLog("启动调试模式全量 BLE 扫描 (${durationMs / 1000}s)")
        startScanInternal(
            durationMs = durationMs,
            scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
            useHardwareFilter = false,
            forcePreempt = true,
            diagnosticOnly = true
        )
    }

    private fun refreshCachedTargetIdentity() {
        val activeProfile = runCatching { proximityProfileProvider?.invoke() }.getOrNull()
        if (activeProfile != null) {
            cachedTargetBleMac = activeProfile.bleMac.ifBlank { null }
            cachedTargetBleName = activeProfile.bleName.ifBlank { null }
        }
    }

    fun clearDebugLogs() = runOnMain {
        debugLogs.clear()
        nearbyCheryDevices.clear()
        allScannedAddresses.clear()
    }

    fun getDebugSnapshot(profile: VehicleProfile? = null): BleDebugSnapshot {
        val targetMac = (profile?.bleMac?.ifBlank { null }
            ?: sessionMaterial?.bleMac
            ?: autoConnectAddress
            ?: config.activeBleMac).orEmpty()
        val targetName = (profile?.bleName?.ifBlank { null }
            ?: sessionMaterial?.bleName
            ?: autoConnectName
            ?: config.activeBleName).orEmpty()
        val gattDevice = currentGatt?.device
        val sortedNearby = nearbyCheryDevices.values.sortedWith(
            compareByDescending<NearbyBleVehicle> { it.isMatchedTarget }
                .thenByDescending { it.lastSeenMs / 10_000L }
                .thenByDescending { it.rssi }
        )
        return BleDebugSnapshot(
            bluetoothEnabled = adapter?.isEnabled == true,
            stage = currentStage.label,
            scanning = scanning,
            autoProximityEnabled = autoProximityEnabled,
            isSessionReady = isSessionReady(),
            targetBleMac = targetMac,
            targetBleName = targetName,
            connectedAddress = gattDevice?.address ?: connectingAddress.orEmpty(),
            connectedName = gattDevice?.let { safeDeviceName(it) }.orEmpty(),
            currentRssi = currentRssi,
            mtuPayloadChunkSize = mtuPayloadChunkSize,
            ffe1Hex = ffe1Hex.orEmpty(),
            servicesReady = servicesReady,
            notificationsReady = notificationsReady,
            ffe1Ready = ffe1Ready,
            auth02Done = auth02Done,
            session03Done = session03Done,
            account80Done = account80Done,
            waitingForBond = waitingForBond,
            nearbyCheryVehicles = sortedNearby,
            totalScannedDevices = allScannedAddresses.size,
            recentLogs = debugLogs.toList().asReversed()
        )
    }

    private fun appendDebugLog(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        if (debugLogs.size >= 80) {
            debugLogs.removeFirst()
        }
        debugLogs.addLast("[$ts] $message")
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
        if (actionAfterAuth != null && pendingAction?.actionKey != actionAfterAuth) {
            pendingAction = PendingAction(actionAfterAuth)
        }
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

    /**
     * Connects directly to a known paired vehicle BLE MAC address without waiting
     * for a full foreground scan cycle, then runs the standard FFE1 -> 0x02 -> 0x03 -> 0x80 handshake.
     */
    fun connectDirectlyByAddress(
        address: String,
        material: SessionMaterial,
        bleName: String? = null
    ): Boolean {
        if (closed) return false
        val normalized = normalizeBleMacAddress(address) ?: return false
        val currentAdapter = adapter ?: return false
        if (!currentAdapter.isEnabled) return false
        val device = runCatching { currentAdapter.getRemoteDevice(normalized) }.getOrNull() ?: return false
        runOnMain {
            if (closed) return@runOnMain
            if (isSessionReady()) return@runOnMain
            if (currentGatt != null &&
                connectingAddress.equals(normalized, ignoreCase = true) &&
                currentStage != Stage.ERROR
            ) {
                return@runOnMain
            }
            stopScanInternal()
            autoConnectAddress = normalized
            autoConnectName = bleName ?: material.bleName
            sessionMaterial = material.copy(
                bleMac = normalized,
                bleName = bleName ?: material.bleName
            )
            session = try {
                InGeekBleSession(material.rootKey16, material.infoBin147, material.edfBin)
            } catch (error: Throwable) {
                fail("钥匙材料初始化失败", error)
                return@runOnMain
            }
            rememberPairingPins(device, sessionMaterial!!)
            appendDebugLog("后台直连本车蓝牙 $normalized")
            connectGatt(device)
        }
        return true
    }

    /**
     * Requests a live RSSI reading on the active GATT link (result delivered via [Listener.onRssiUpdated]).
     */
    fun requestRemoteRssi(): Boolean {
        val gatt = currentGatt ?: return false
        if (closed || connectPermissionError() != null) return false
        return runCatching { gatt.readRemoteRssi() }.getOrDefault(false)
    }

    private fun normalizeBleMacAddress(raw: String): String? {
        val compact = raw.trim().replace(":", "").replace("-", "").uppercase(Locale.US)
        if (compact.length != 12 || !compact.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return compact.chunked(2).joinToString(":")
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
        pendingAction = PendingAction(actionKey)
        autoConnectAddress = targetAddress ?: material.bleMac ?: config.activeBleMac
        autoConnectName = targetName ?: material.bleName ?: config.activeBleName
        startScanInternal(config.scanTimeoutMs)
    }

    fun sendVehicleAction(actionKey: String): Boolean = runOnMainSync {
        if (!isSessionReady()) {
            if (autoProximityEnabled || currentGatt != null || scanning) {
                pendingAction = PendingAction(actionKey)
                listener?.onChannelModeChanged(
                    false,
                    currentRssi,
                    "蓝牙数字钥匙正在连接，指令已排队"
                )
                return@runOnMainSync true
            } else {
                listener?.onError("蓝牙数字钥匙会话尚未建立", null)
                return@runOnMainSync false
            }
        }
        val command = try {
            TBoxTlvBuilder.buildAction(actionKey, temperatureCelsius = 24.0, durationMin = 10)
        } catch (error: Throwable) {
            listener?.onError("车控动作参数不合法：$actionKey", error)
            return@runOnMainSync false
        }
        transmitEncryptedTlv(command.actionKey, command.tlv, command.actionKey)
    }

    fun sendVehicleAction(
        actionKey: String,
        temperatureCelsius: Double,
        durationMin: Int = 10
    ): Boolean = runOnMainSync {
        if (!isSessionReady()) {
            if (autoProximityEnabled || currentGatt != null || scanning) {
                pendingAction = PendingAction(actionKey, temperatureCelsius, durationMin)
                listener?.onChannelModeChanged(
                    false,
                    currentRssi,
                    "蓝牙数字钥匙正在连接，指令已排队"
                )
                return@runOnMainSync true
            } else {
                listener?.onError("蓝牙数字钥匙会话尚未建立", null)
                return@runOnMainSync false
            }
        }
        val command = try {
            TBoxTlvBuilder.buildAction(actionKey, temperatureCelsius, durationMin)
        } catch (error: Throwable) {
            listener?.onError("车控动作参数不合法：$actionKey", error)
            return@runOnMainSync false
        }
        transmitEncryptedTlv(command.actionKey, command.tlv, command.actionKey)
    }

    fun sendCustomTlv(hexString: String): Boolean = runOnMainSync {
        if (!authenticated) {
            listener?.onError("会话尚未建立，无法发送自定义 TLV", null)
            return@runOnMainSync false
        }
        val tlv = try {
            TBoxTlvBuilder.customTlv(hexString)
        } catch (error: Throwable) {
            listener?.onError("自定义 TLV 格式不合法", error)
            return@runOnMainSync false
        }
        transmitEncryptedTlv("custom_tlv", tlv, "自定义 TLV")
    }

    fun disconnect() = runOnMain {
        val abortedPairingAddr = if (waitingForBond) lastPairingAddress ?: currentGatt?.device?.address else null
        failPendingActions("蓝牙连接已断开")
        cancelTimers()
        currentGatt?.disconnect()
        currentGatt?.close()
        currentGatt = null
        resetLinkState()
        currentRssi = null
        proximityTargetMatched = false
        currentStage = Stage.DISCONNECTED
        emitStage()
        if (!abortedPairingAddr.isNullOrBlank()) {
            listener?.onBondCompleted(false, abortedPairingAddr)
        }
        listener?.onConnectionStateChanged(false, currentStage.label)
        listener?.onChannelModeChanged(false, null, "远程网络控制")
        if (autoProximityEnabled) scheduleProximityTick(1_500)
    }

    /**
     * Fully clears target vehicle identity, cached pairing material, and active link.
     * Used on logout, vehicle switch, or vehicle unbind so the previous vehicle can
     * never be reconnected or matched against a newly selected car.
     */
    fun resetTargetAndDisconnect() = runOnMain {
        val abortedPairingAddr = if (waitingForBond) lastPairingAddress ?: currentGatt?.device?.address else null
        failPendingActions("蓝牙连接已重置")
        autoProximityEnabled = false
        proximityProfileProvider = null
        proximityEnableRequester = null
        bluetoothEnableRequested = false
        proximityTick?.let(mainHandler::removeCallbacks)
        proximityTick = null
        stopScanInternal()
        cancelTimers()
        runCatching { currentGatt?.disconnect() }
        runCatching { currentGatt?.close() }
        currentGatt = null
        resetLinkState()
        sessionMaterial = null
        session = null
        autoConnectAddress = null
        autoConnectName = null
        cachedTargetBleMac = null
        cachedTargetBleName = null
        pendingAction = null
        consecutiveFilteredEmptyScans = 0
        pinsByAddress.clear()
        randomByAddress.clear()
        randomTimestampByAddress.clear()
        pendingRandomWaitSince.clear()
        nearbyCheryDevices.clear()
        allScannedAddresses.clear()
        currentRssi = null
        proximityTargetMatched = false
        currentStage = Stage.DISCONNECTED
        emitStage()
        if (!abortedPairingAddr.isNullOrBlank()) {
            listener?.onBondCompleted(false, abortedPairingAddr)
        }
        listener?.onConnectionStateChanged(false, currentStage.label)
        listener?.onChannelModeChanged(false, null, "远程网络控制")
    }

    fun close() {
        closed = true
        runOnMain {
            autoProximityEnabled = false
            proximityTick?.let(mainHandler::removeCallbacks)
            proximityTick = null
            stopScanInternal()
            unregisterPairingReceiver()
            failPendingActions("蓝牙控制器已关闭")
            cancelTimers()
            cancelPendingRandomConnect()
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

    private fun runOnMainSync(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicBoolean(false)
        val claimed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            if (claimed.compareAndSet(false, true)) {
                try {
                    result.set(runCatching(block).getOrDefault(false))
                } finally {
                    latch.countDown()
                }
            }
        }
        if (!posted) return false
        val completed = runCatching { latch.await(2_000L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (completed) {
            return result.get()
        }
        if (claimed.compareAndSet(false, true)) {
            return false
        }
        // Main thread already claimed execution right at the timeout boundary; await its outcome.
        runCatching { latch.await(2_000L, TimeUnit.MILLISECONDS) }
        return result.get()
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
        if (isSessionReady() || currentGatt != null || scanning) {
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
            return
        }
        val profile = proximityProfileProvider?.invoke()
        val material = profile?.takeIf {
            it.rootKeyHex.isNotBlank() &&
                it.infoHex.isNotBlank() &&
                it.edfHex.isNotBlank() &&
                it.userId.isNotBlank() &&
                it.deviceId.isNotBlank()
        }?.let(::buildSessionMaterial)
        if (profile == null || material == null) {
            listener?.onChannelModeChanged(
                false,
                null,
                "远程网络控制 · 等待同步蓝牙数字钥匙"
            )
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
            return
        }
        cachedTargetBleMac = profile.bleMac.ifBlank { null }
        cachedTargetBleName = profile.bleName.ifBlank { null }

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

        sessionMaterial = material
        session = runCatching {
            InGeekBleSession(material.rootKey16, material.infoBin147, material.edfBin)
        }.getOrNull()
        if (session == null) {
            listener?.onError("蓝牙数字钥匙材料初始化失败", null)
            scheduleProximityTick(PROXIMITY_INTERVAL_MS)
            return
        }
        autoConnectAddress = cachedTargetBleMac
        autoConnectName = cachedTargetBleName
        proximityTargetMatched = false
        val throttleWaitMs = computeScanThrottleDelayMs(isManualTrigger = false)
        if (throttleWaitMs > 0L) {
            appendDebugLog("避让系统 30s 扫描频控，${throttleWaitMs}ms 后自动续扫")
            scheduleProximityTick(throttleWaitMs)
            return
        }
        val useFilter = consecutiveFilteredEmptyScans < 2
        if (!useFilter) {
            appendDebugLog("连续过滤扫描未命中，本次回退全量前台扫描以兼容非标广播")
        }
        startScanInternal(
            durationMs = PROXIMITY_SCAN_WINDOW_MS,
            scanMode = ScanSettings.SCAN_MODE_BALANCED,
            useHardwareFilter = useFilter,
            forcePreempt = false,
            diagnosticOnly = false
        )
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
        val resolvedAccountType = if (profile.keyRole > 0) {
            InGeekCloudProvisioner.accountTypeForKeyRole(profile.keyRole)
        } else {
            profile.accountType.coerceIn(0, 1)
        }
        SessionMaterial(
            rootKey16 = root,
            infoBin147 = info,
            edfBin = edf,
            accountBinding = AccountBinding(
                accountType = resolvedAccountType,
                userId = profile.userId,
                deviceId = profile.deviceId
            ),
            bleMac = profile.bleMac.ifBlank { null },
            bleName = profile.bleName.ifBlank { null }
        )
    }.getOrNull()

    private fun computeScanThrottleDelayMs(isManualTrigger: Boolean): Long = synchronized(scanHistoryLock) {
        val nowMs = SystemClock.elapsedRealtime()
        while (processScanStartTimestamps.isNotEmpty() &&
            nowMs - processScanStartTimestamps.first() >= SCAN_THROTTLE_WINDOW_MS
        ) {
            processScanStartTimestamps.removeFirst()
        }
        val maxAllowed = if (isManualTrigger) MAX_SCANS_PER_WINDOW_MANUAL else MAX_SCANS_PER_WINDOW_AUTO
        if (processScanStartTimestamps.size < maxAllowed) return@synchronized 0L
        val oldest = processScanStartTimestamps.first()
        (oldest + SCAN_THROTTLE_WINDOW_MS + 400L - nowMs).coerceAtLeast(500L)
    }

    private fun recordScanStartTimestamp() = synchronized(scanHistoryLock) {
        processScanStartTimestamps.addLast(SystemClock.elapsedRealtime())
    }

    private fun buildHardwareScanFilters(): List<ScanFilter>? {
        val filters = mutableListOf<ScanFilter>()
        filters += ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(config.vehicleServiceUuid))
            .build()
        filters += ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(config.statusServiceUuid))
            .build()
        filters += ScanFilter.Builder()
            .setManufacturerData(0x0759, ByteArray(0))
            .build()
        val targetMacRaw = sessionMaterial?.bleMac
            ?: autoConnectAddress
            ?: cachedTargetBleMac
            ?: config.activeBleMac
        val targetName = (sessionMaterial?.bleName
            ?: autoConnectName
            ?: cachedTargetBleName
            ?: config.activeBleName).orEmpty().trim()
        val normalizedMac = targetMacRaw?.let { normalizeBleMacAddress(it) }
        if (normalizedMac != null) {
            filters += ScanFilter.Builder()
                .setDeviceAddress(normalizedMac)
                .build()
            if (targetName.isBlank()) {
                val compact = normalizedMac.replace(":", "")
                filters += ScanFilter.Builder().setDeviceName("CHR$compact").build()
                filters += ScanFilter.Builder().setDeviceName("BLE_DK_$compact").build()
            }
        }
        if (targetName.isNotBlank()) {
            filters += ScanFilter.Builder()
                .setDeviceName(targetName)
                .build()
        }
        return filters.takeIf { it.isNotEmpty() }
    }

    private fun startScanInternal(
        durationMs: Long,
        scanMode: Int = ScanSettings.SCAN_MODE_BALANCED,
        useHardwareFilter: Boolean = false,
        forcePreempt: Boolean = false,
        diagnosticOnly: Boolean = false
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
        val throttleDelay = computeScanThrottleDelayMs(isManualTrigger = forcePreempt || diagnosticOnly)
        if (throttleDelay > 0L) {
            pendingScanRetryRunnable?.let(mainHandler::removeCallbacks)
            pendingScanRetryRunnable = Runnable {
                pendingScanRetryRunnable = null
                if (!closed && currentGatt == null && !isSessionReady()) {
                    startScanInternal(durationMs, scanMode, useHardwareFilter, forcePreempt, diagnosticOnly)
                }
            }.also { mainHandler.postDelayed(it, throttleDelay) }
            if (scanning) {
                appendDebugLog("系统扫描频控保护中，沿用当前窗口并于 ${throttleDelay}ms 后升级扫描")
            } else {
                appendDebugLog("系统扫描频控保护，${throttleDelay}ms 后自动重试")
            }
            return
        }
        stopScanInternal()
        diagnosticScanOnly = diagnosticOnly
        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settingsBuilder.setLegacy(true)
        }
        val settings = settingsBuilder.build()
        val filters = if (!diagnosticOnly && useHardwareFilter) buildHardwareScanFilters() else null
        scanning = true
        currentStage = Stage.SCANNING
        emitStage()
        try {
            recordScanStartTimestamp()
            currentScanner.startScan(filters, settings, leScanCallback)
        } catch (error: Throwable) {
            scanning = false
            fail("启动 BLE 扫描失败", error)
            return
        }
        val stopTask = Runnable {
            if (scanning) {
                if (!diagnosticScanOnly && filters != null && !proximityTargetMatched && currentGatt == null) {
                    consecutiveFilteredEmptyScans = (consecutiveFilteredEmptyScans + 1).coerceAtMost(3)
                } else if (filters == null && !proximityTargetMatched && currentGatt == null) {
                    consecutiveFilteredEmptyScans = 0
                }
                stopScanInternal(cancelPendingRetry = false)
            }
        }
        scanStopRunnable = stopTask
        mainHandler.postDelayed(stopTask, durationMs.coerceAtLeast(1_000))
    }

    private fun stopScanInternal(cancelPendingRetry: Boolean = true) {
        scanStopRunnable?.let(mainHandler::removeCallbacks)
        scanStopRunnable = null
        if (cancelPendingRetry) {
            pendingScanRetryRunnable?.let(mainHandler::removeCallbacks)
            pendingScanRetryRunnable = null
        }
        cancelPendingRandomConnect()
        diagnosticScanOnly = false
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(leScanCallback) }
    }

    private fun cancelPendingRandomConnect() {
        pendingRandomConnectRunnable?.let(mainHandler::removeCallbacks)
        pendingRandomConnectRunnable = null
    }

    private fun getFreshRandom(address: String): ByteArray? {
        val ts = randomTimestampByAddress[address] ?: return null
        if (SystemClock.elapsedRealtime() - ts > RANDOM_CACHE_TTL_MS) {
            randomByAddress.remove(address)
            randomTimestampByAddress.remove(address)
            return null
        }
        return randomByAddress[address]
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (closed || !scanning) return
            if (!diagnosticScanOnly && (currentGatt != null || authenticated)) return
            mainHandler.post { handleScanResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (closed || !scanning) return
            if (!diagnosticScanOnly && (currentGatt != null || authenticated)) return
            val snapshot = results.toList()
            mainHandler.post { snapshot.forEach(::handleScanResult) }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                stopScanInternal()
                fail("BLE 扫描失败，错误码 $errorCode")
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        if (closed) return
        val device = result.device ?: return
        val record = result.scanRecord
        allScannedAddresses.add(device.address)

        val recordName = record?.deviceName?.takeIf { it.isNotBlank() }
        val uuids = record?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty()
        val hasCheryMfgData = record?.getManufacturerSpecificData(0x0759) != null
        var name = recordName
        var matchEval = evaluateStrictTargetMatch(device, name)
        var isCheryCandidate = matchEval.matched || hasCheryMfgData || isLikelyCheryVehicle(name, uuids)

        // Only perform BluetoothDevice.name Binder IPC when advertisement omitted the local name
        // AND we are either in diagnostic mode or the device already looks like a Chery peripheral.
        if (name == null && !matchEval.matched && (diagnosticScanOnly || isCheryCandidate)) {
            val cachedName = safeDeviceName(device)?.takeIf { it.isNotBlank() }
            if (cachedName != null) {
                name = cachedName
                matchEval = evaluateStrictTargetMatch(device, name)
                isCheryCandidate = matchEval.matched || hasCheryMfgData || isLikelyCheryVehicle(name, uuids)
            }
        }

        // Fast-path reject non-Chery peripherals so random nearby BLE traffic never runs
        // double SHA-256 PIN derivation or bond-state Binder IPC on Main. Preserve exact
        // 6-byte `06 FF <rand4> <flag>` ADV_IND randoms so RPA vehicles splitting ADV_IND
        // from SCAN_RSP (`CHR*` / `0x0759`) still have their rolling 4B random cached.
        if (!isCheryCandidate) {
            val splitAdvRandom = extractExactIngeekAdvRandom(record?.bytes)
            if (splitAdvRandom != null) {
                randomByAddress[device.address] = splitAdvRandom
                randomTimestampByAddress[device.address] = SystemClock.elapsedRealtime()
            }
            return
        }

        val random = BlePairingPinCalculator.extractAdvRandomBytes(record)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (random != null) {
            randomByAddress[device.address] = random
            randomTimestampByAddress[device.address] = nowElapsed
        }
        val effectiveRandom = random ?: getFreshRandom(device.address)
        val randomHex = effectiveRandom?.toHex() ?: ""

        val effectiveMac = config.activeBleMac
            ?: sessionMaterial?.bleMac
            ?: BlePairingPinCalculator.extractBleNameSuffix(name)
            ?: device.address
        val pins = if (effectiveRandom != null) {
            runCatching {
                BlePairingPinCalculator.calculateCandidates(
                    activeMacOr12Hex = effectiveMac,
                    bleName = name ?: config.activeBleName ?: sessionMaterial?.bleName,
                    random4 = effectiveRandom
                )
            }.getOrNull()
        } else {
            null
        }
        if (pins != null) {
            pinsByAddress[device.address] = pins
        } else if (getFreshRandom(device.address) == null) {
            pinsByAddress.remove(device.address)
        }

        val resolvedPins = if (effectiveRandom != null) pinsByAddress[device.address] ?: pins else null
        val prev = nearbyCheryDevices[device.address]
        val bond = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        nearbyCheryDevices[device.address] = NearbyBleVehicle(
            address = device.address,
            name = name.orEmpty().ifBlank { "(未命名设备)" },
            rssi = result.rssi,
            serviceUuids = uuids,
            advRandomHex = randomHex,
            calculatedPin = resolvedPins?.primaryPin.orEmpty(),
            isMatchedTarget = matchEval.matched,
            matchReason = matchEval.reason,
            bondState = bond,
            lastSeenMs = System.currentTimeMillis()
        )
        if (prev == null) {
            appendDebugLog(
                "发现周边奇瑞BLE: ${name.orEmpty().ifBlank { device.address }} [${device.address}] ${result.rssi}dBm -> ${matchEval.reason}"
            )
        }

        if (resolvedPins != null) {
            listener?.onDeviceScanned(device, result.rssi, randomHex, resolvedPins)
        }

        if (diagnosticScanOnly || !scanning || currentGatt != null || isSessionReady()) return

        val shouldConnectProximity = autoProximityEnabled && sessionMaterial != null && matchEval.matched
        val shouldConnectAction = !shouldConnectProximity &&
            matchEval.matched &&
            pendingAction != null &&
            sessionMaterial != null
        if (!shouldConnectProximity && !shouldConnectAction) return

        consecutiveFilteredEmptyScans = 0
        currentRssi = result.rssi
        val actionToRun = if (shouldConnectAction) pendingAction?.actionKey else null
        val bondState = bond
        if (bondState != BluetoothDevice.BOND_BONDED && effectiveRandom == null) {
            val isFirstWait = !pendingRandomWaitSince.containsKey(device.address)
            val firstWaitMs = pendingRandomWaitSince.getOrPut(device.address) { nowElapsed }
            if (nowElapsed - firstWaitMs < RANDOM_WAIT_TIMEOUT_MS) {
                if (isFirstWait) {
                    appendDebugLog("已命中本车蓝牙 ${device.address}，短等 4B 随机数以计算配对码...")
                    cancelPendingRandomConnect()
                    val capturedMaterial = sessionMaterial!!
                    val capturedReason = matchEval.reason
                    val capturedRssi = result.rssi
                    val fallbackTask = Runnable {
                        pendingRandomConnectRunnable = null
                        if (closed || currentGatt != null || isSessionReady()) return@Runnable
                        pendingRandomWaitSince.remove(device.address)
                        proximityTargetMatched = true
                        appendDebugLog("等待随机数超时，继续建连本车蓝牙 ${device.address} ($capturedReason)")
                        if (shouldConnectProximity) {
                            listener?.onChannelModeChanged(false, capturedRssi, "发现本车蓝牙 · 正在自动切换蓝牙控制...")
                            if (getFreshRandom(device.address) != null) {
                                pinsByAddress[device.address]?.primaryPin?.let { pin ->
                                    listener?.onPairingPinAvailable(pin, device.address)
                                }
                            }
                        }
                        stopScanInternal()
                        connectAndAuthenticate(device, capturedMaterial, actionToRun)
                    }
                    pendingRandomConnectRunnable = fallbackTask
                    mainHandler.postDelayed(fallbackTask, RANDOM_WAIT_TIMEOUT_MS)
                }
                return
            }
        }
        cancelPendingRandomConnect()
        pendingRandomWaitSince.remove(device.address)
        proximityTargetMatched = true
        if (shouldConnectProximity) {
            appendDebugLog("精准命中本车蓝牙 ${device.address} (${matchEval.reason})，开始建立连接")
            listener?.onChannelModeChanged(
                false,
                result.rssi,
                "发现本车蓝牙 · 正在自动切换蓝牙控制..."
            )
            resolvedPins?.primaryPin?.let { pin ->
                listener?.onPairingPinAvailable(pin, device.address)
            }
            stopScanInternal()
            connectAndAuthenticate(device, sessionMaterial!!, null)
        } else {
            appendDebugLog("主动控车命中本车蓝牙 ${device.address} (${matchEval.reason})")
            stopScanInternal()
            connectAndAuthenticate(device, sessionMaterial!!, actionToRun)
        }
    }

    private data class TargetMatchEval(val matched: Boolean, val reason: String)

    private fun isLikelyCheryVehicle(name: String?, uuids: List<String>): Boolean {
        val upperName = name?.trim()?.uppercase().orEmpty()
        if (CHERY_VEHICLE_NAME.matches(upperName)) return true
        if (CHERY_DK_NAME.matches(upperName)) return true
        if (upperName.contains("INGEEK") || upperName.contains("CHERY")) return true
        if (BlePairingPinCalculator.extractBleNameSuffix(name) != null) return true
        return uuids.any {
            it.endsWith("0000fff0-0000-1000-8000-00805f9b34fb") ||
                it.endsWith("0000ffe0-0000-1000-8000-00805f9b34fb")
        }
    }

    /**
     * Strictly matches the scanned peripheral against the current vehicle's cloud-provisioned
     * `bleMac` / `bleName`. Never falls through to generic Chery name/UUID matching when a
     * vehicle identity is configured, preventing accidental connection to strangers' Chery cars.
     */
    private fun evaluateStrictTargetMatch(device: BluetoothDevice, name: String?): TargetMatchEval {
        val configuredMac = sessionMaterial?.bleMac
            ?: autoConnectAddress
            ?: cachedTargetBleMac
            ?: config.activeBleMac
        val configuredName = sessionMaterial?.bleName
            ?: autoConnectName
            ?: cachedTargetBleName
            ?: config.activeBleName

        val normalizedAddress = device.address.replace(":", "").replace("-", "").trim()
        val configuredMacCompact = configuredMac.orEmpty().replace(":", "").replace("-", "").trim()
        val nameMacSuffix = BlePairingPinCalculator.extractBleNameSuffix(name).orEmpty()

        if (configuredMacCompact.isNotBlank()) {
            if (normalizedAddress.equals(configuredMacCompact, ignoreCase = true)) {
                return TargetMatchEval(true, "蓝牙 MAC 精准匹配")
            }
            if (nameMacSuffix.isNotBlank() &&
                nameMacSuffix.equals(configuredMacCompact, ignoreCase = true)
            ) {
                return TargetMatchEval(true, "广播名 MAC 后缀精准匹配")
            }
            if (!configuredName.isNullOrBlank() && configuredName.equals(name?.trim(), ignoreCase = true)) {
                return TargetMatchEval(true, "蓝牙名称精准匹配")
            }
            return TargetMatchEval(false, "其他奇瑞车辆（MAC 不匹配，已忽略）")
        }

        if (!configuredName.isNullOrBlank()) {
            if (configuredName.equals(name?.trim(), ignoreCase = true)) {
                return TargetMatchEval(true, "蓝牙名称精准匹配")
            }
            return TargetMatchEval(false, "其他奇瑞车辆（名称不匹配，已忽略）")
        }

        return TargetMatchEval(false, "未配置本车蓝牙 MAC/名称（已防误连）")
    }

    private fun rememberPairingPins(device: BluetoothDevice, material: SessionMaterial) {
        val random = getFreshRandom(device.address)
        if (random == null) {
            pinsByAddress.remove(device.address)
            return
        }
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
        currentGatt?.let { prior ->
            runCatching { prior.disconnect() }
            runCatching { prior.close() }
            currentGatt = null
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
        noResponseFallbackRunnable?.let(mainHandler::removeCallbacks)
        noResponseFallbackRunnable = null
        inFlightWriteSeq++
        inFlightCompletion = null
        writeQueue.clear()
        writeInFlight = false
        pendingActionBySerial.clear()
        ingeekFrameAssembler.reset()
        txFrameHashes.clear()
        servicesReady = false
        notificationsReady = false
        ffe1Ready = false
        ffe1Hex = null
        ffe1ReportedUnbonded = false
        authStarted = false
        auth02Done = false
        session03Done = false
        account80Done = false
        authenticated = false
        waitingForBond = false
        lastPairingAddress = null
        connectingAddress = null
        serviceDiscoveryStarted = false
        mtuPayloadChunkSize = 14
        mtuNegotiated = false
        mtuRetryAttempted = false
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
                mtuFallbackRunnable?.let(mainHandler::removeCallbacks)
                mtuFallbackRunnable = null
                mtuNegotiated = (status == BluetoothGatt.GATT_SUCCESS)
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
                if (!serviceDiscoveryStarted) {
                    startServiceDiscovery(gatt)
                } else if (servicesReady && notificationsReady && ffe1Ready && !authStarted) {
                    maybeBeginHandshake()
                }
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

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf()
            mainHandler.post { handleCharacteristicRead(gatt, characteristic, copy, status) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val copy = value.copyOf()
            mainHandler.post { handleCharacteristicRead(gatt, characteristic, copy, status) }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf()
            mainHandler.post { handleCharacteristicChanged(gatt, characteristic, copy) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val copy = value.copyOf()
            mainHandler.post { handleCharacteristicChanged(gatt, characteristic, copy) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            mainHandler.post { finishWrite(gatt, status == BluetoothGatt.GATT_SUCCESS, status) }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            mainHandler.post {
                if (gatt !== currentGatt || status != BluetoothGatt.GATT_SUCCESS) return@post
                currentRssi = rssi
                listener?.onRssiUpdated(rssi)
            }
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
                // Guard discovery / CCCD / FFE1 against silent stack stalls.
                startHandshakeTimeout()
                listener?.onConnectionStateChanged(true, currentStage.label)
                if (autoProximityEnabled) {
                    listener?.onChannelModeChanged(
                        false,
                        currentRssi,
                        "附近蓝牙数字钥匙正在握手..."
                    )
                }
                mtuFallbackRunnable?.let(mainHandler::removeCallbacks)
                mtuFallbackRunnable = null
                val mtuRequested = runCatching { gatt.requestMtu(config.requestMtu) }.getOrDefault(false)
                if (!mtuRequested) {
                    mtuPayloadChunkSize = 14
                    startServiceDiscovery(gatt)
                } else {
                    // Some stacks do not deliver onMtuChanged reliably; track & cancel on MTU/disconnect.
                    val fallback = Runnable {
                        mtuFallbackRunnable = null
                        if (gatt === currentGatt) {
                            startServiceDiscovery(gatt)
                        }
                    }
                    mtuFallbackRunnable = fallback
                    mainHandler.postDelayed(fallback, 1_800)
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val abortedPairingAddr = if (waitingForBond) lastPairingAddress ?: gatt.device.address else null
                val wasUnauthStaleBond = !authenticated &&
                    ffe1ReportedUnbonded &&
                    runCatching { gatt.device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                val disconnectReason = if (status != BluetoothGatt.GATT_SUCCESS) {
                    "GATT 连接断开，status=$status"
                } else {
                    "蓝牙连接已断开"
                }
                failPendingActions(disconnectReason)
                if (gatt === currentGatt) {
                    currentGatt = null
                    runCatching { gatt.close() }
                }
                resetLinkState()
                currentRssi = null
                proximityTargetMatched = false
                currentStage = Stage.DISCONNECTED
                emitStage()
                if (!abortedPairingAddr.isNullOrBlank()) {
                    listener?.onBondCompleted(false, abortedPairingAddr)
                }
                listener?.onConnectionStateChanged(false, currentStage.label)
                if (wasUnauthStaleBond) {
                    listener?.onError("车端要求重新配对，请在手机「设置 → 蓝牙」取消配对旧记录后重试", null)
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener?.onError("GATT 连接断开，status=$status", null)
                }
                listener?.onChannelModeChanged(false, null, "远程网络控制")
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
            if (!writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
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
                if (status in AUTH_REQUIRED_GATT_STATUSES &&
                    runCatching { gatt.device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) != BluetoothDevice.BOND_BONDED
                ) {
                    appendDebugLog("FFF2 CCCD 触发加密认证要求 (status=$status)，转入系统配对")
                    requestBond(gatt.device)
                    return
                }
                fail("FFF2 通知订阅失败，status=$status")
                return
            }
            notificationsReady = true
            if (!ffe1Ready) {
                readStatusCharacteristic(gatt)
            } else {
                maybeBeginHandshake()
            }
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
            if (status in AUTH_REQUIRED_GATT_STATUSES &&
                runCatching { gatt.device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) != BluetoothDevice.BOND_BONDED
            ) {
                appendDebugLog("FFE1 读取触发加密认证要求 (status=$status)，转入系统配对")
                requestBond(gatt.device)
                return
            }
            fail("FFE1 配对状态读取失败，status=$status")
            return
        }
        val bytes = value ?: ByteArray(0)
        ffe1Hex = bytes.toHex()
        currentStage = Stage.WAITING_BOND
        emitStage()
        when (bytes.firstOrNull()?.toInt()?.and(0xFF)) {
            0x81 -> {
                bondTimeout?.let(mainHandler::removeCallbacks)
                bondTimeout = null
                ffe1Ready = true
                ffe1ReportedUnbonded = false
                waitingForBond = false
                maybeBeginHandshake()
            }
            0x10, 0x20 -> {
                ffe1ReportedUnbonded = true
                requestBond(gatt.device)
            }
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
                        bondTimeout?.let(mainHandler::removeCallbacks)
                        bondTimeout = null
                        ffe1Ready = true
                        ffe1ReportedUnbonded = false
                        waitingForBond = false
                        maybeBeginHandshake()
                    }
                    0x10, 0x20 -> {
                        ffe1ReportedUnbonded = true
                        requestBond(gatt.device)
                    }
                }
            }
            config.notifyCharacteristicUuid -> processNotification(value)
        }
    }

    private fun resolvePinForDevice(device: BluetoothDevice): String? {
        val freshRandom = getFreshRandom(device.address) ?: return null
        return runCatching {
            val mac = sessionMaterial?.bleMac ?: config.activeBleMac ?: device.address
            val name = sessionMaterial?.bleName ?: config.activeBleName ?: safeDeviceName(device)
            BlePairingPinCalculator.calculateCandidates(mac, name, freshRandom).also {
                pinsByAddress[device.address] = it
            }.primaryPin
        }.getOrNull()
    }

    private fun requestBond(device: BluetoothDevice) {
        if (waitingForBond) return
        waitingForBond = true
        lastPairingAddress = device.address
        handshakeTimeout?.let(mainHandler::removeCallbacks)
        handshakeTimeout = null
        currentStage = Stage.WAITING_BOND
        emitStage()
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            waitingForBond = false
            lastPairingAddress = null
            ffe1Ready = true
            if (ffe1ReportedUnbonded) {
                appendDebugLog("警告：车端 FFE1 要求配对 ($ffe1Hex)，但手机系统仍保留旧配对记录 (${device.address})")
            }
            startHandshakeTimeout()
            maybeBeginHandshake()
            return
        }
        val pin = resolvePinForDevice(device)
        val displayName = safeDeviceName(device)
            ?: sessionMaterial?.bleName
            ?: config.activeBleName
            ?: device.address
        if (pin.isNullOrBlank()) {
            waitingForBond = false
            lastPairingAddress = null
            appendDebugLog("尚未采集到本车有效 4B 广播随机数，暂不发起空配对码绑定，等待下轮扫描")
            fail("尚未获取到本车动态配对码，请靠近车辆稍后重试")
            return
        }
        appendDebugLog("首次蓝牙配对启动 ($displayName / ${device.address})，弹出顶部配对码提示")
        listener?.onManualPairingRequired(pin, displayName, device.address)
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            scheduleBondTimeout()
            return
        }
        val started = runCatching { device.createBond() }.getOrDefault(false)
        if (!started) {
            waitingForBond = false
            lastPairingAddress = null
            listener?.onBondCompleted(false, device.address)
            fail("发起系统蓝牙配对失败")
            return
        }
        scheduleBondTimeout()
    }

    private fun scheduleBondTimeout() {
        bondTimeout?.let(mainHandler::removeCallbacks)
        bondTimeout = Runnable {
            if (waitingForBond) {
                val addr = lastPairingAddress ?: connectingAddress
                waitingForBond = false
                lastPairingAddress = null
                addr?.let { listener?.onBondCompleted(false, it) }
                fail("等待系统蓝牙配对超时")
            }
        }.also { mainHandler.postDelayed(it, 60_000L) }
    }

    private fun handlePairingRequest(intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (currentGatt == null ||
            connectingAddress == null ||
            !device.address.equals(connectingAddress, ignoreCase = true)
        ) {
            return
        }
        waitingForBond = true
        lastPairingAddress = device.address
        handshakeTimeout?.let(mainHandler::removeCallbacks)
        handshakeTimeout = null
        scheduleBondTimeout()
        val pin = resolvePinForDevice(device)
        val displayName = safeDeviceName(device)
            ?: sessionMaterial?.bleName
            ?: config.activeBleName
            ?: device.address
        if (pin.isNullOrEmpty()) {
            appendDebugLog("收到系统配对请求 (${device.address})，但暂无可用动态配对码")
            listener?.onError("配对请求已收到，但尚无可用动态配对码，请重新靠近扫描", null)
            return
        }
        // Standard Android apps cannot auto-fill system Bluetooth pairing PINs;
        // surface the 6-digit PIN in the top sliding drawer for manual entry.
        appendDebugLog("收到系统蓝牙配对请求 ($displayName)，等待用户在系统框手动输入 6 位配对码")
        listener?.onManualPairingRequired(pin, displayName, device.address)
    }

    private fun handleBondStateChanged(intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
        if (connectingAddress == null || !device.address.equals(connectingAddress, ignoreCase = true)) return
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                bondTimeout?.let(mainHandler::removeCallbacks)
                bondTimeout = null
                waitingForBond = false
                lastPairingAddress = null
                ffe1Ready = true
                ffe1ReportedUnbonded = false
                appendDebugLog("系统蓝牙配对成功 (${device.address})")
                listener?.onBondCompleted(true, device.address)
                startHandshakeTimeout()
                val activeGatt = currentGatt
                if (activeGatt != null && servicesReady && !notificationsReady) {
                    val notifiable = notifyCharacteristic
                    val cccd = notifiable?.getDescriptor(config.cccdUuid)
                    if (cccd != null) {
                        if (writeDescriptorCompat(activeGatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                            return
                        }
                        fail("写入 FFF2 CCCD 失败")
                        return
                    } else {
                        notificationsReady = true
                    }
                }
                maybeBeginHandshake()
            }
            BluetoothDevice.BOND_NONE -> {
                if (waitingForBond) {
                    bondTimeout?.let(mainHandler::removeCallbacks)
                    bondTimeout = null
                    waitingForBond = false
                    lastPairingAddress = null
                    randomByAddress.remove(device.address)
                    randomTimestampByAddress.remove(device.address)
                    pinsByAddress.remove(device.address)
                    appendDebugLog("系统蓝牙配对未完成或已取消 (${device.address})")
                    listener?.onBondCompleted(false, device.address)
                    fail("系统蓝牙配对未完成")
                }
            }
        }
    }

    private fun handleBluetoothAdapterStateChanged(intent: Intent) {
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        when (state) {
            BluetoothAdapter.STATE_TURNING_OFF,
            BluetoothAdapter.STATE_OFF -> {
                appendDebugLog("系统蓝牙已关闭，立即清理扫描与 GATT 链路")
                val abortedPairingAddr = if (waitingForBond) lastPairingAddress ?: currentGatt?.device?.address else null
                failPendingActions("系统蓝牙已关闭")
                stopScanInternal()
                cancelTimers()
                val gatt = currentGatt
                currentGatt = null
                runCatching { gatt?.close() }
                resetLinkState()
                currentRssi = null
                proximityTargetMatched = false
                currentStage = Stage.DISCONNECTED
                emitStage()
                if (!abortedPairingAddr.isNullOrBlank()) {
                    listener?.onBondCompleted(false, abortedPairingAddr)
                }
                listener?.onConnectionStateChanged(false, currentStage.label)
                listener?.onChannelModeChanged(false, null, "远程网络控制 · 蓝牙已关闭")
            }
            BluetoothAdapter.STATE_ON -> {
                appendDebugLog("系统蓝牙已重新开启")
                bluetoothEnableRequested = false
                if (autoProximityEnabled && !closed) {
                    scheduleProximityTick(500L)
                }
                if (PassiveEntryStore.getActiveEnabledVin(appContext).isNotBlank()) {
                    PassiveEntryWakeManager.restoreIfEnabled(appContext)
                }
            }
        }
    }

    private fun maybeBeginHandshake() {
        if (closed || authenticated || authStarted) return
        val material = sessionMaterial ?: return
        val activeSession = session ?: return
        if (!servicesReady || !notificationsReady || !ffe1Ready) return

        // Validate vckInfo validFrom/validTo before burning a handshake cycle (P2-36).
        val parsedInfo = runCatching { VckInfoParser.parse(material.infoBin147) }.getOrNull()
        if (parsedInfo != null) {
            val validityError = InGeekCloudProvisioner.validateVckInfoValidity(
                parsedInfo.validFrom,
                parsedInfo.validTo
            )
            if (validityError != null) {
                fail(validityError)
                return
            }
        }

        val payload = activeSession.build0x02AuthPayload()
        // InGeek extended packFlag supports at most 15 chunks (0xF2..0xFF); prevent wrap at MTU=23 fallback (P1-13).
        if (payload.size > mtuPayloadChunkSize * BleWholeProtocol.INGEEK_MAX_MULTI_PACKETS) {
            val activeGatt = currentGatt
            if (activeGatt != null && !mtuRetryAttempted) {
                mtuRetryAttempted = true
                appendDebugLog("当前 MTU 分片(${mtuPayloadChunkSize}B)不足以发送 ${payload.size}B 认证帧，重试请求 MTU=${config.requestMtu}")
                val retried = runCatching { activeGatt.requestMtu(config.requestMtu) }.getOrDefault(false)
                if (retried) {
                    startHandshakeTimeout()
                    return
                }
            }
            fail("BLE MTU 协商不足（分片 ${mtuPayloadChunkSize}B，需发送 ${payload.size}B），请重试连接")
            return
        }

        val frames = try {
            BleWholeProtocol.packIngeekExtended(
                BleWholeProtocol.MSG_AUTH_REQUEST,
                serialNum = 1,
                payload = payload,
                maxPayloadChunkSize = mtuPayloadChunkSize
            )
        } catch (error: Throwable) {
            fail("0x02 认证帧分包失败", error)
            return
        }

        authStarted = true
        auth02Done = false
        session03Done = false
        account80Done = false
        currentStage = Stage.AUTH_REQUEST_SENT
        emitStage()
        startHandshakeTimeout()

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
            ingeekFrameAssembler.ingest(raw)
        } catch (error: Throwable) {
            ingeekFrameAssembler.reset()
            fail("FFF2 响应重组失败", error)
            return
        }
        completeFrames.forEach(::handleCompleteFrame)
    }

    private fun extractExactIngeekAdvRandom(raw: ByteArray?): ByteArray? {
        if (raw == null || raw.size < 7) return null
        var offset = 0
        while (offset < raw.size) {
            val len = raw[offset].toInt() and 0xFF
            if (len == 0) break
            val next = offset + 1 + len
            if (next > raw.size) break
            if (len == 6 && (raw[offset + 1].toInt() and 0xFF) == 0xFF) {
                return raw.copyOfRange(offset + 2, offset + 6)
            }
            offset = next
        }
        return null
    }

    private fun handleCompleteFrame(frame: BleWholeProtocol.Frame) {
        when (frame.messageId) {
            BleWholeProtocol.MSG_BASE_RESPONSE,
            BleWholeProtocol.MSG_VEHICLE_CONTROL_RESPONSE -> handleBaseResponseFrame(frame)
            BleWholeProtocol.MSG_VEHICLE_INFO -> {
                val activeSession = session
                val payloadToParse = if (authenticated &&
                    activeSession != null &&
                    frame.payload.size >= 32 &&
                    (frame.payload.size - 16) % 16 == 0
                ) {
                    val (plain, cmacValid) = activeSession.decryptIncomingPayload(frame.payload)
                    if (cmacValid && plain.isNotEmpty()) plain else frame.payload
                } else {
                    frame.payload
                }
                val parsed = TBoxTlvBuilder.parseResponse(frame.messageId, payloadToParse)
                listener?.onVehicleResponse(parsed)
                logPacket(
                    "INFO",
                    "0x10",
                    frame.serialNum,
                    payloadToParse.toHex(),
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
            fail("0x02 身份认证被拒绝（%s）".format(describeClientAnswerCode(base.errCode)))
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
        val frames = try {
            BleWholeProtocol.packIngeekExtended(
                BleWholeProtocol.MSG_SESSION_REQUEST,
                serialNum = 2,
                payload = encryptedSessionRequest,
                maxPayloadChunkSize = mtuPayloadChunkSize
            )
        } catch (error: Throwable) {
            fail("0x03 会话帧分包失败", error)
            return
        }
        auth02Done = true
        currentStage = Stage.SESSION_REQUEST_SENT
        emitStage()
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
            fail("0x03 会话建立被拒绝（%s）".format(describeClientAnswerCode(base.errCode)))
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
        val frames = try {
            BleWholeProtocol.packIngeekExtended(
                BleWholeProtocol.MSG_VEHICLE_CONTROL,
                serialNum = 3,
                payload = encrypted,
                maxPayloadChunkSize = mtuPayloadChunkSize
            )
        } catch (error: Throwable) {
            fail("0x80 账号绑定帧分包失败", error)
            return
        }
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
        val serial = base?.answerSerial ?: frame.serialNum
        val errCode = base?.errCode ?: 0
        val encryptedData = base?.data ?: frame.payload

        // Check link-layer errCode BEFORE empty payload so command errors on serial >= 4
        // do NOT tear down the authenticated GATT session (P1-11).
        // Per official BleBaseResponse & zyhl蓝牙协议分析.md:130,406,763, T-Box returns
        // res=1 with errCode=0 on success; gate strictly on errCode == 0.
        if (encryptedData.isEmpty()) {
            if (serial == 3) {
                if (base != null && errCode == 0) {
                    completeAccountBinding(emptyTBoxResponse(frame.messageId))
                } else {
                    fail("0x80 账号绑定被拒绝（%s）".format(describeClientAnswerCode(errCode)))
                }
                return
            }
            cancelActionTimeout(serial)
            val actionKey = pendingActionBySerial.remove(serial).orEmpty()
            if (errCode != 0) {
                val errMsg = "车控应答错误 serial=$serial（%s）".format(describeClientAnswerCode(errCode))
                listener?.onActionResult(actionKey, serial, success = false, parsedResponse = null, errorMessage = errMsg)
                listener?.onError(errMsg, null)
            } else if (base != null) {
                listener?.onActionResult(actionKey, serial, success = true, parsedResponse = null, errorMessage = null)
            } else {
                val errMsg = "车控应答载荷为空 (serial=$serial)"
                listener?.onActionResult(actionKey, serial, success = false, parsedResponse = null, errorMessage = errMsg)
                listener?.onError(errMsg, null)
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
            serial,
            plain.toHex(),
            "解密成功，CMAC 校验通过"
        )
        val parsed = TBoxTlvBuilder.parseResponse(frame.messageId, plain)
        listener?.onVehicleResponse(parsed)

        if (serial == 3) {
            val resultRejected = (parsed.resultCode != null && parsed.resultCode != 1) ||
                (parsed.errorCode != null && parsed.errorCode != 0)
            if (errCode != 0 || resultRejected) {
                fail("0x80 账号绑定失败：${parsed.summaryText}")
            } else {
                completeAccountBinding(parsed)
            }
            return
        }

        cancelActionTimeout(serial)
        val actionKey = pendingActionBySerial.remove(serial).orEmpty()
        val tboxRejected = errCode != 0 ||
            !parsed.success ||
            (parsed.resultCode != null && parsed.resultCode != 1) ||
            (parsed.errorCode != null && parsed.errorCode != 0)
        if (tboxRejected) {
            val linkDetail = if (errCode != 0) "${describeClientAnswerCode(errCode)}；" else ""
            val errMsg = "车控执行失败 serial=$serial：${linkDetail}${parsed.summaryText}"
            listener?.onActionResult(actionKey, serial, success = false, parsedResponse = parsed, errorMessage = errMsg)
            listener?.onError(errMsg, null)
        } else {
            listener?.onActionResult(actionKey, serial, success = true, parsedResponse = parsed, errorMessage = null)
        }
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
        ffe1ReportedUnbonded = false
        currentStage = Stage.AUTHENTICATED
        emitStage()
        session?.snapshot()?.let { listener?.onAuthenticated(it) }
        listener?.onChannelModeChanged(
            true,
            currentRssi,
            "蓝牙数字钥匙已直连"
        )
        logPacket("INFO", "-", 0, "", "会话建立并完成 0x80：${parsed.summaryText}")
        val action = pendingAction
        pendingAction = null
        if (action != null) {
            mainHandler.postDelayed({
                if (isSessionReady()) {
                    sendVehicleAction(action.actionKey, action.temperatureCelsius, action.durationMin)
                }
            }, 120)
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
        val frames = try {
            BleWholeProtocol.packIngeekExtended(
                BleWholeProtocol.MSG_VEHICLE_CONTROL,
                serialNum = serial,
                payload = encrypted,
                maxPayloadChunkSize = mtuPayloadChunkSize
            )
        } catch (error: Throwable) {
            listener?.onError("车控帧分包失败（MTU 分片不足）", error)
            return false
        }
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

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }.getOrDefault(false)

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }.getOrDefault(false)

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
        val activeGatt = currentGatt ?: return
        val operation = writeQueue.pollFirst() ?: return
        val started = writeCharacteristicCompat(
            activeGatt,
            operation.characteristic,
            operation.data,
            operation.writeType
        )
        if (!started) {
            writeQueue.clear()
            inFlightCompletion = null
            fail("GATT 写入失败：${operation.label}")
            return
        }
        writeInFlight = true
        val seq = ++inFlightWriteSeq
        inFlightCompletion = operation.onComplete
        rememberTxFrame(operation.data)
        noResponseFallbackRunnable?.let(mainHandler::removeCallbacks)
        noResponseFallbackRunnable = null
        if (operation.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            val fallback = Runnable {
                noResponseFallbackRunnable = null
                if (inFlightWriteSeq == seq) {
                    finishWrite(activeGatt, success = true, status = BluetoothGatt.GATT_SUCCESS)
                }
            }
            noResponseFallbackRunnable = fallback
            mainHandler.postDelayed(fallback, 25)
        }
    }

    private fun finishWrite(gatt: BluetoothGatt, success: Boolean, status: Int) {
        if (gatt !== currentGatt || !writeInFlight) return
        noResponseFallbackRunnable?.let(mainHandler::removeCallbacks)
        noResponseFallbackRunnable = null
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
                val errMsg = "车控动作 $actionKey (serial=$serial) 等待应答超时"
                listener?.onActionResult(actionKey, serial, success = false, parsedResponse = null, errorMessage = errMsg)
                listener?.onError(errMsg, null)
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
        mtuFallbackRunnable?.let(mainHandler::removeCallbacks)
        connectTimeout?.let(mainHandler::removeCallbacks)
        handshakeTimeout?.let(mainHandler::removeCallbacks)
        accountTimeout?.let(mainHandler::removeCallbacks)
        bondTimeout?.let(mainHandler::removeCallbacks)
        mtuFallbackRunnable = null
        connectTimeout = null
        handshakeTimeout = null
        accountTimeout = null
        bondTimeout = null
        actionTimeoutRunnables.values.toList().forEach(mainHandler::removeCallbacks)
        actionTimeoutRunnables.clear()
    }

    private fun describeClientAnswerCode(errCode: Int): String {
        val code = errCode and 0xFF
        val desc = when (code) {
            0 -> "成功"
            1 -> "CRC 校验失败"
            2 -> "输入无效参数"
            3 -> "SE 返回非 9000"
            11 -> "有效期校验失败"
            16, 97 -> "数字钥匙已过期"
            17 -> "车控指令检查失败"
            26 -> "TBOX 休眠/应急模式"
            32 -> "帧序列错误"
            33 -> "流水号错误"
            34 -> "包长度不符"
            36 -> "组包校验长度错误"
            37 -> "消息 ID 不支持或分包丢包"
            57, 83 -> "车端验签失败"
            59 -> "车端 AES 解密失败"
            61, 63 -> "车端 CMAC 校验失败"
            62 -> "会话挑战数据不合法"
            67 -> "钥匙期限无效"
            68, 70 -> "车端 RTC 校时失败"
            69 -> "VCKInfo 格式错误"
            72 -> "车控权限不足"
            96 -> "数字钥匙未生效"
            110 -> "车辆未激活"
            else -> "车端错误"
        }
        return "$desc(err=0x%02X)".format(code)
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
        appendDebugLog("$direction [$msgIdHex #$serial] $decryptedSummary")
        listener?.onPacketLog(direction, msgIdHex, serial, rawHex, decryptedSummary)
    }

    private fun failPendingActions(reason: String) {
        if (pendingActionBySerial.isEmpty()) return
        val pending = pendingActionBySerial.entries.map { it.key to it.value }
        pendingActionBySerial.clear()
        for ((serial, actionKey) in pending) {
            cancelActionTimeout(serial)
            listener?.onActionResult(
                actionKey = actionKey,
                serial = serial,
                success = false,
                parsedResponse = null,
                errorMessage = reason
            )
        }
    }

    private fun fail(message: String, throwable: Throwable? = null) {
        val activeGattDevice = currentGatt?.device
        val abortedPairingAddr = if (waitingForBond) lastPairingAddress ?: activeGattDevice?.address else null
        val staleBondHint = if (ffe1ReportedUnbonded &&
            activeGattDevice != null &&
            runCatching { activeGattDevice.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        ) {
            "车端要求重新配对，请在手机「设置 → 蓝牙」取消配对旧记录后重试"
        } else {
            message
        }
        failPendingActions(staleBondHint)
        cancelTimers()
        appendDebugLog("ERR: $staleBondHint${throwable?.message?.let { " ($it)" }.orEmpty()}")
        currentStage = Stage.ERROR
        proximityTargetMatched = false
        currentRssi = null
        emitStage()
        val failedGatt = currentGatt
        if (failedGatt != null) {
            currentGatt = null
            runCatching { failedGatt.disconnect() }
            runCatching { failedGatt.close() }
        }
        resetLinkState()
        if (!abortedPairingAddr.isNullOrBlank()) {
            listener?.onBondCompleted(false, abortedPairingAddr)
        }
        listener?.onError(staleBondHint, throwable)
        if (autoProximityEnabled) {
            listener?.onChannelModeChanged(false, null, "远程网络控制")
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
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
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
        private val scanHistoryLock = Any()
        private val processScanStartTimestamps = ArrayDeque<Long>()
        private val AUTH_REQUIRED_GATT_STATUSES = setOf(5, 15, 137)
        private const val PROXIMITY_SCAN_WINDOW_MS = 6_000L
        private const val PROXIMITY_INTERVAL_MS = 14_000L // 6s scan + 8s idle (< 3 scans / 30s)
        private const val SCAN_THROTTLE_WINDOW_MS = 30_500L
        private const val MAX_SCANS_PER_WINDOW_AUTO = 3
        private const val MAX_SCANS_PER_WINDOW_MANUAL = 4
        private const val RANDOM_WAIT_TIMEOUT_MS = 1_800L
        private const val RANDOM_CACHE_TTL_MS = 60_000L
        private const val BLUETOOTH_RETRY_MS = 4_000L
        private val CHERY_VEHICLE_NAME = Regex("^CHR[0-9A-F]{12}$")
        private val CHERY_DK_NAME = Regex("^BLE_DK_[0-9A-F]{12}$")
    }
}
