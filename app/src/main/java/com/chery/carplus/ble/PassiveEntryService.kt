package com.chery.carplus.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chery.carplus.R
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.profile.VehicleProfileStore
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

enum class PassiveState(val label: String) {
    DISABLED("未开启"),
    SLEEPING_WATCH("监听中"),
    WAKING_CONNECT("连接中"),
    NEAR_READY("已连接"),
    UNLOCKED_NEAR("已解锁"),
    LOCKED_AWAY("已上锁"),
    PAUSED_FOREGROUND("监听中"),
    ERROR("连接异常")
}

data class PassiveEntrySnapshot(
    val enabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val wakeChannelActive: Boolean = false,
    val wakeChannelLabel: String = "未开启",
    val state: PassiveState = PassiveState.DISABLED,
    val currentRawRssi: Int? = null,
    val filteredRssi: Int? = null,
    val sessionReady: Boolean = false,
    val lastEventText: String = "",
    val lastEventAtMs: Long = 0L,
    val recentLogs: List<String> = emptyList()
)

enum class PassiveSleepReason {
    NONE,
    LOCKED_AWAY,
    IDLE_FAR,
    DISCONNECT,
    CONNECT_FAILED,
    DISABLED,
    FOREGROUND
}

/**
 * Shared in-process runtime state & foreground/background BLE handoff coordinator
 * for passive entry, observed by Security UI and Debug Diagnostics.
 */
object PassiveEntryRuntime {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(PassiveEntrySnapshot) -> Unit>()
    private val stateLock = Any()
    private val logs = ArrayDeque<String>()

    @Volatile
    private var snapshot = PassiveEntrySnapshot()

    @Volatile
    private var foregroundAppActive = false

    @Volatile
    private var foregroundWasNearConnected = false

    @Volatile
    private var foregroundLastRssi: Int? = null

    @Volatile
    private var lastSleepReason: PassiveSleepReason = PassiveSleepReason.NONE

    @Volatile
    private var lastSleepAtElapsedMs: Long = 0L

    @Volatile
    var onAutoActionExecuted: ((actionKey: String, vin: String) -> Unit)? = null

    fun currentSnapshot(): PassiveEntrySnapshot = snapshot

    fun isServiceRunning(): Boolean = snapshot.serviceRunning

    fun isForegroundAppActive(): Boolean = foregroundAppActive

    fun recordSleep(reason: PassiveSleepReason) {
        synchronized(stateLock) {
            lastSleepReason = reason
            lastSleepAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun markCompanionDisappeared() {
        synchronized(stateLock) {
            if (lastSleepReason == PassiveSleepReason.LOCKED_AWAY) {
                lastSleepReason = PassiveSleepReason.NONE
                lastSleepAtElapsedMs = 0L
            }
        }
    }

    fun shouldThrottleScanWake(rssi: Int, cfg: PassiveEntryConfig, nowElapsed: Long): String? {
        if (rssi < cfg.effectiveLockRssi - 8) {
            return "信号过弱 (${rssi} dBm)"
        }
        val (sleepReason, sleepAt) = synchronized(stateLock) { lastSleepReason to lastSleepAtElapsedMs }
        val elapsedSinceSleep = (nowElapsed - sleepAt).coerceAtLeast(0L)
        return when (sleepReason) {
            PassiveSleepReason.IDLE_FAR -> {
                if (elapsedSinceSleep < 90_000L && rssi < cfg.effectiveUnlockRssi - 4) {
                    "远场休眠冷却期"
                } else {
                    null
                }
            }
            PassiveSleepReason.CONNECT_FAILED -> {
                if (elapsedSinceSleep < 60_000L) "建连失败冷却期" else null
            }
            PassiveSleepReason.LOCKED_AWAY -> {
                if (elapsedSinceSleep < 300_000L && rssi < cfg.effectiveUnlockRssi - 4) {
                    "离车上锁后等待再次靠近"
                } else {
                    null
                }
            }
            else -> null
        }
    }

    fun addListener(listener: (PassiveEntrySnapshot) -> Unit) {
        listeners.add(listener)
        val snap = snapshot
        mainHandler.post { listener(snap) }
    }

    fun removeListener(listener: (PassiveEntrySnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun appendLog(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(stateLock) {
            if (logs.size >= 60) logs.removeFirst()
            logs.addLast("[$ts] $message")
            snapshot = snapshot.copy(recentLogs = logs.toList().asReversed())
        }
        dispatchSnapshot()
    }

    fun updateWakeChannelStatus(
        enabled: Boolean,
        active: Boolean,
        channelLabel: String,
        logMessage: String?
    ) {
        synchronized(stateLock) {
            val nextState = when {
                !enabled -> PassiveState.DISABLED
                snapshot.serviceRunning -> snapshot.state
                foregroundAppActive -> PassiveState.PAUSED_FOREGROUND
                active -> PassiveState.SLEEPING_WATCH
                else -> PassiveState.ERROR
            }
            snapshot = snapshot.copy(
                enabled = enabled,
                wakeChannelActive = active,
                wakeChannelLabel = channelLabel,
                state = nextState
            )
        }
        if (!logMessage.isNullOrBlank()) {
            appendLog(logMessage)
        } else {
            dispatchSnapshot()
        }
    }

    internal fun updateFromService(transform: (PassiveEntrySnapshot) -> PassiveEntrySnapshot) {
        synchronized(stateLock) {
            snapshot = transform(snapshot).copy(
                recentLogs = logs.toList().asReversed()
            )
        }
        dispatchSnapshot()
    }

    /**
     * Coordinates BLE ownership with MainActivity so foreground and background controllers
     * never fight over the same vehicle GATT connection.
     */
    fun setForegroundAppActive(
        context: Context,
        active: Boolean,
        handoffFromNearSession: Boolean = false,
        handoffRssi: Int? = null
    ) {
        val appContext = context.applicationContext
        foregroundAppActive = active
        val activeVin = PassiveEntryStore.getActiveEnabledVin(appContext)
        val cfg = if (activeVin.isNotBlank()) PassiveEntryStore.load(appContext, activeVin) else null

        if (active) {
            if (snapshot.serviceRunning || cfg?.enabled == true) {
                if (snapshot.serviceRunning) {
                    appendLog("App 回到前台，后台无感服务暂停让出蓝牙连接")
                }
                PassiveEntryService.stopService(appContext)
            }
            if (cfg?.enabled == true) {
                synchronized(stateLock) {
                    snapshot = snapshot.copy(
                        enabled = true,
                        state = PassiveState.PAUSED_FOREGROUND
                    )
                }
                dispatchSnapshot()
            }
        } else {
            val wasNear = synchronized(stateLock) {
                val near = handoffFromNearSession || foregroundWasNearConnected
                foregroundWasNearConnected = false
                near
            }
            val effectiveRssi = handoffRssi ?: foregroundLastRssi
            if (cfg?.enabled == true) {
                if (wasNear && cfg.autoLockEnabled) {
                    val store = VehicleProfileStore(appContext)
                    val profile = store.listProfiles().firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
                        ?: store.getActiveProfile().takeIf { it.vin.equals(activeVin, ignoreCase = true) }
                    val mac = PassiveEntryPrerequisiteChecker.normalizeMac(profile?.bleMac)
                    if (profile != null && mac != null) {
                        // Start FGS synchronously inside onStop() while the process still holds
                        // Android 14-15 foreground-service start exemption; delay only the BLE
                        // connect inside the service so foreground GATT finishes closing first.
                        appendLog("App 退至后台且刚才处于车旁连接，已交接给后台无感服务")
                        PassiveEntryService.startForWake(
                            context = appContext,
                            vin = profile.vin,
                            bleMac = mac,
                            bleName = profile.bleName,
                            initialRssi = effectiveRssi,
                            wakeSource = "ForegroundHandoff",
                            assumeAlreadyNear = true,
                            connectDelayMs = 550L
                        )
                        return
                    }
                }
                synchronized(stateLock) {
                    snapshot = snapshot.copy(
                        enabled = true,
                        state = if (snapshot.wakeChannelActive) PassiveState.SLEEPING_WATCH else snapshot.state
                    )
                }
                dispatchSnapshot()
            }
        }
    }

    fun reportForegroundBleRssi(rssi: Int?, sessionReady: Boolean) {
        synchronized(stateLock) {
            foregroundLastRssi = rssi
            if (sessionReady) {
                foregroundWasNearConnected = true
            } else if (rssi == null) {
                foregroundWasNearConnected = false
            }
            if (foregroundAppActive) {
                snapshot = snapshot.copy(
                    currentRawRssi = rssi,
                    filteredRssi = rssi,
                    sessionReady = sessionReady
                )
            }
        }
        if (foregroundAppActive) {
            dispatchSnapshot()
        }
    }

    private fun dispatchSnapshot() {
        val snap = snapshot
        mainHandler.post {
            listeners.forEach { it(snap) }
        }
    }
}

/**
 * Short-lived connectedDevice ForegroundService woken by PendingIntent BLE scan or
 * CompanionDeviceService. Establishes full BLE digital key session ahead of arrival,
 * runs median-filtered RSSI polling with hysteresis for passive unlock / walk-away lock,
 * and tears itself down automatically once away/idle to return to zero-drain sleep.
 */
class PassiveEntryService : Service(), CheryBleController.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bleController: CheryBleController? = null
    private var activeControllerMac: String = ""
    private var activeProfile: VehicleProfile? = null
    private var activeConfig: PassiveEntryConfig? = null
    private var targetMac: String = ""

    private val rssiWindow = ArrayDeque<Int>()
    private var consecutiveUnlockHits = 0
    private var consecutiveLockHits = 0
    private var hasBeenNear = false
    private var hasEverEstablishedSession = false
    private var reconnectAttempts = 0
    private var unlockedThisVisit = false
    private var lockedThisVisit = false
    private var lastActionElapsedMs = 0L
    private var serviceStartedElapsedMs = 0L
    private var lastNearActivityElapsedMs = 0L
    private var disconnectedSinceElapsedMs = 0L
    private var directConnectRetriedWithScan = false
    private var pendingConnectRunnable: Runnable? = null
    private var pendingReconnectRunnable: Runnable? = null

    // Live vehicle telemetry reported directly over BLE 0x10 / 0x04 responses.
    private var liveBleLocked: Boolean? = null
    private var liveBleDoorsOpen: Boolean? = null
    private var liveBleEngineRunning: Boolean? = null
    private var liveBleUpdatedElapsedMs = 0L

    private val rssiPollTask = object : Runnable {
        override fun run() {
            if (PassiveEntryRuntime.isForegroundAppActive()) {
                shutdownAndSleep("前台已活跃，退出后台服务", PassiveSleepReason.FOREGROUND)
                return
            }
            bleController?.takeIf { it.isSessionReady() }?.requestRemoteRssi()
            checkAutoSleep()
            if (PassiveEntryRuntime.isServiceRunning()) {
                mainHandler.postDelayed(this, RSSI_POLL_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        serviceStartedElapsedMs = SystemClock.elapsedRealtime()
        lastNearActivityElapsedMs = serviceStartedElapsedMs
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_COMPANION_DISAPPEARED) {
            val disappearedMac = intent.getStringExtra(EXTRA_BLE_MAC).orEmpty()
            if (disappearedMac.equals(targetMac, ignoreCase = true) && bleController?.isSessionReady() != true) {
                PassiveEntryRuntime.appendLog("伴随设备报告车辆已远离且链路未连接，退出前台服务")
                shutdownAndSleep("已返回低功耗监听")
            }
            return START_NOT_STICKY
        }

        // Immediately promote to foreground service to satisfy Android 8-14 5s requirement.
        val promoted = promoteToForeground("靠近车辆 · 正在建立蓝牙数字钥匙连接...")
        if (!promoted) {
            PassiveEntryRuntime.appendLog("系统限制后台启动前台服务，保持低功耗唤醒等待")
            stopSelf()
            return START_NOT_STICKY
        }
        if (PassiveEntryRuntime.isForegroundAppActive()) {
            shutdownAndSleep("前台已活跃，退出后台服务", PassiveSleepReason.FOREGROUND)
            return START_NOT_STICKY
        }

        val vin = intent?.getStringExtra(EXTRA_VIN).orEmpty().ifBlank {
            PassiveEntryStore.getActiveEnabledVin(this)
        }
        val wakeSource = intent?.getStringExtra(EXTRA_WAKE_SOURCE).orEmpty().ifBlank { "PendingIntent" }
        val assumeNear = intent?.getBooleanExtra(EXTRA_ASSUME_NEAR, false) == true
        val connectDelayMs = intent?.getLongExtra(EXTRA_CONNECT_DELAY_MS, 0L)?.coerceIn(0L, 5_000L) ?: 0L
        val initialRssi = if (intent?.hasExtra(EXTRA_INITIAL_RSSI) == true) {
            intent.getIntExtra(EXTRA_INITIAL_RSSI, -999).takeIf { it > -120 && it < 0 }
        } else {
            null
        }

        val cfg = PassiveEntryStore.load(this, vin)
        if (!cfg.enabled) {
            shutdownAndSleep("无感功能已关闭", PassiveSleepReason.DISABLED)
            return START_NOT_STICKY
        }
        val store = VehicleProfileStore(this)
        val profile = store.listProfiles().firstOrNull { it.vin.equals(vin, ignoreCase = true) }
            ?: store.getActiveProfile().takeIf { it.vin.equals(vin, ignoreCase = true) }
        val mac = PassiveEntryPrerequisiteChecker.normalizeMac(profile?.bleMac)
        if (profile == null || mac == null || !PassiveEntryPrerequisiteChecker.hasCompleteKeyMaterial(profile)) {
            PassiveEntryRuntime.appendLog("后台唤醒取消：钥匙材料不完整")
            shutdownAndSleep("钥匙材料不完整", PassiveSleepReason.CONNECT_FAILED)
            return START_NOT_STICKY
        }

        activeProfile = profile
        activeConfig = cfg
        targetMac = mac
        if (assumeNear) {
            hasBeenNear = true
            unlockedThisVisit = true
        }
        if (initialRssi != null) {
            pushRssiSample(initialRssi)
        }

        PassiveEntryRuntime.updateFromService {
            it.copy(
                enabled = true,
                serviceRunning = true,
                state = PassiveState.WAKING_CONNECT,
                currentRawRssi = initialRssi ?: it.currentRawRssi
            )
        }
        PassiveEntryRuntime.appendLog("由 $wakeSource 唤醒，准备直连本车 $mac")

        pendingConnectRunnable?.let(mainHandler::removeCallbacks)
        pendingConnectRunnable = null
        if (connectDelayMs > 0L) {
            val delayed = Runnable {
                pendingConnectRunnable = null
                if (PassiveEntryRuntime.isForegroundAppActive()) {
                    shutdownAndSleep("前台已活跃，退出后台服务", PassiveSleepReason.FOREGROUND)
                } else {
                    startBleConnection(profile, mac)
                }
            }
            pendingConnectRunnable = delayed
            mainHandler.postDelayed(delayed, connectDelayMs)
        } else {
            startBleConnection(profile, mac)
        }
        mainHandler.removeCallbacks(rssiPollTask)
        mainHandler.postDelayed(rssiPollTask, RSSI_POLL_INTERVAL_MS)
        return START_NOT_STICKY
    }

    private fun startBleConnection(profile: VehicleProfile, mac: String) {
        if (bleController != null && !activeControllerMac.equals(mac, ignoreCase = true)) {
            runCatching { bleController?.close() }
            bleController = null
            activeControllerMac = ""
        }
        if (bleController == null) {
            activeControllerMac = mac
            bleController = CheryBleController(
                context = this,
                config = CheryBleController.Config(
                    activeBleMac = mac,
                    activeBleName = profile.bleName.ifBlank { null },
                    scanTimeoutMs = 10_000L,
                    connectTimeoutMs = 12_000L,
                    handshakeTimeoutMs = 15_000L
                ),
                listener = this
            )
        }
        val controller = bleController ?: return
        if (controller.isSessionReady()) {
            handleSessionReady()
            return
        }
        val material = buildSessionMaterial(profile, mac) ?: run {
            shutdownAndSleep("钥匙材料解析失败", PassiveSleepReason.CONNECT_FAILED)
            return
        }
        directConnectRetriedWithScan = false
        val started = controller.connectDirectlyByAddress(mac, material, profile.bleName.ifBlank { null })
        if (!started) {
            fallbackScanConnect(controller, profile)
        }
    }

    private fun fallbackScanConnect(controller: CheryBleController, profile: VehicleProfile) {
        if (directConnectRetriedWithScan) return
        directConnectRetriedWithScan = true
        PassiveEntryRuntime.appendLog("直连未命中，回退短时扫描连接本车蓝牙")
        controller.startAutoProximityWatch(
            profileProvider = { profile },
            onRequestEnableBluetooth = { }
        )
    }

    private fun buildSessionMaterial(profile: VehicleProfile, mac: String): CheryBleController.SessionMaterial? =
        runCatching {
            val root = profile.rootKeyBytes()
            val info = profile.infoBytes()
            val edf = profile.edfBytes()
            if (root.size != 16 || info.size != 147 || edf.size !in 68..74) return@runCatching null
            CheryBleController.SessionMaterial(
                rootKey16 = root,
                infoBin147 = info,
                edfBin = edf,
                accountBinding = CheryBleController.AccountBinding(
                    accountType = profile.accountType,
                    userId = profile.userId,
                    deviceId = profile.deviceId
                ),
                bleMac = mac,
                bleName = profile.bleName.ifBlank { null }
            )
        }.getOrNull()

    // =========================================================================
    // CheryBleController.Listener
    // =========================================================================

    override fun onAuthenticated(sessionSnapshot: SessionSnapshot) {
        mainHandler.post { handleSessionReady() }
    }

    private fun handleSessionReady() {
        hasEverEstablishedSession = true
        reconnectAttempts = 0
        disconnectedSinceElapsedMs = 0L
        pendingReconnectRunnable?.let(mainHandler::removeCallbacks)
        pendingReconnectRunnable = null
        lastNearActivityElapsedMs = SystemClock.elapsedRealtime()
        bleController?.stopAutoProximityWatch()
        val state = if (unlockedThisVisit) PassiveState.UNLOCKED_NEAR else PassiveState.NEAR_READY
        PassiveEntryRuntime.updateFromService {
            it.copy(
                sessionReady = true,
                state = state
            )
        }
        PassiveEntryRuntime.appendLog("后台蓝牙数字钥匙会话已就绪，开始实时测距")
        updateNotification("蓝牙数字钥匙已就绪 · 实时监测靠近/离车距离")
        bleController?.requestRemoteRssi()
    }

    override fun onVehicleResponse(parsedResponse: ParsedTBoxResponse) {
        mainHandler.post {
            var changed = false
            parsedResponse.doorLocked?.let {
                liveBleLocked = it
                changed = true
            }
            parsedResponse.doorsOpen?.let {
                liveBleDoorsOpen = it
                changed = true
            }
            parsedResponse.engineRunning?.let {
                liveBleEngineRunning = it
                changed = true
            }
            if (changed) {
                liveBleUpdatedElapsedMs = SystemClock.elapsedRealtime()
                activeProfile?.vin?.takeIf { it.isNotBlank() }?.let { vin ->
                    val prev = PassiveEntryStore.getVehicleSafetyHint(this, vin).takeIf { it.isFresh() }
                    PassiveEntryStore.updateVehicleSafetyHint(
                        context = this,
                        vin = vin,
                        doorsClosed = liveBleDoorsOpen?.let { !it } ?: prev?.doorsClosed,
                        engineStopped = liveBleEngineRunning?.let { !it } ?: prev?.engineStopped,
                        locked = liveBleLocked ?: prev?.locked
                    )
                }
            }
        }
    }

    override fun onRssiUpdated(rssi: Int) {
        mainHandler.post {
            val filtered = pushRssiSample(rssi)
            evaluatePassiveStateMachine(rawRssi = rssi, filteredRssi = filtered)
        }
    }

    override fun onActionDispatched(actionKey: String, serial: Int) {
        mainHandler.post {
            val profile = activeProfile ?: return@post
            val label = when (actionKey) {
                "unlock" -> "靠近自动解锁"
                "lock" -> "离车自动上锁"
                else -> "无感控车 ($actionKey)"
            }
            val nowMs = System.currentTimeMillis()
            PassiveEntryStore.recordTrigger(this, profile.vin, label, nowMs)
            PassiveEntryRuntime.updateFromService {
                it.copy(
                    lastEventText = label,
                    lastEventAtMs = nowMs
                )
            }
            PassiveEntryRuntime.appendLog("已下发加密指令：$label")
            updateNotification("已执行$label")
            PassiveEntryRuntime.onAutoActionExecuted?.invoke(actionKey, profile.vin)
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, stateName: String) {
        mainHandler.post {
            if (!connected) {
                if (hasEverEstablishedSession && disconnectedSinceElapsedMs == 0L) {
                    disconnectedSinceElapsedMs = SystemClock.elapsedRealtime()
                }
                PassiveEntryRuntime.updateFromService {
                    it.copy(
                        sessionReady = false,
                        state = if (lockedThisVisit) PassiveState.LOCKED_AWAY else PassiveState.WAKING_CONNECT
                    )
                }
                if (hasEverEstablishedSession && !lockedThisVisit && reconnectAttempts < MAX_MID_SESSION_RECONNECTS) {
                    val profile = activeProfile
                    val mac = targetMac
                    if (profile != null && mac.isNotBlank() && pendingReconnectRunnable == null) {
                        reconnectAttempts++
                        val task = Runnable {
                            pendingReconnectRunnable = null
                            if (!PassiveEntryRuntime.isForegroundAppActive() &&
                                bleController?.isSessionReady() != true &&
                                !lockedThisVisit
                            ) {
                                PassiveEntryRuntime.appendLog("蓝牙中途断开，尝试第 $reconnectAttempts 次重连本车")
                                directConnectRetriedWithScan = false
                                startBleConnection(profile, mac)
                            }
                        }
                        pendingReconnectRunnable = task
                        mainHandler.postDelayed(task, 2_000L)
                    }
                }
            } else {
                disconnectedSinceElapsedMs = 0L
                pendingReconnectRunnable?.let(mainHandler::removeCallbacks)
                pendingReconnectRunnable = null
            }
        }
    }

    override fun onError(message: String, throwable: Throwable?) {
        mainHandler.post {
            PassiveEntryRuntime.appendLog("后台蓝牙链路提示：$message")
            val controller = bleController
            val profile = activeProfile
            if (controller != null && profile != null && !controller.isSessionReady() && !directConnectRetriedWithScan) {
                fallbackScanConnect(controller, profile)
            }
        }
    }

    // =========================================================================
    // Median-Filtered RSSI + Hysteresis Passive Unlock / Walk-Away Lock Machine
    // =========================================================================

    private fun pushRssiSample(rssi: Int): Int {
        if (rssiWindow.size >= RSSI_WINDOW_SIZE) rssiWindow.removeFirst()
        rssiWindow.addLast(rssi)
        val sorted = rssiWindow.sorted()
        return sorted[sorted.size / 2]
    }

    private fun evaluatePassiveStateMachine(rawRssi: Int, filteredRssi: Int) {
        val profile = activeProfile ?: return
        val cfg = PassiveEntryStore.load(this, profile.vin)
        activeConfig = cfg
        if (!cfg.enabled) {
            shutdownAndSleep("无感功能已关闭")
            return
        }

        val unlockThreshold = cfg.effectiveUnlockRssi
        val lockThreshold = cfg.effectiveLockRssi
        val nowElapsed = SystemClock.elapsedRealtime()

        if (filteredRssi >= unlockThreshold - 4) {
            hasBeenNear = true
            lastNearActivityElapsedMs = nowElapsed
        }

        PassiveEntryRuntime.updateFromService {
            it.copy(
                currentRawRssi = rawRssi,
                filteredRssi = filteredRssi,
                sessionReady = bleController?.isSessionReady() == true
            )
        }

        val controller = bleController ?: return
        if (!controller.isSessionReady()) return

        val inCooldown = (nowElapsed - lastActionElapsedMs) < ACTION_COOLDOWN_MS

        val liveFresh = liveBleUpdatedElapsedMs > 0L && (nowElapsed - liveBleUpdatedElapsedMs) in 0L..LIVE_BLE_HINT_TTL_MS

        // 1. Approach Auto-Unlock
        if (cfg.autoUnlockEnabled && !unlockedThisVisit && filteredRssi >= unlockThreshold) {
            consecutiveUnlockHits++
            consecutiveLockHits = 0
            if (consecutiveUnlockHits >= UNLOCK_CONFIRM_SAMPLES && !inCooldown) {
                val hint = PassiveEntryStore.getVehicleSafetyHint(this, profile.vin)
                val knownAlreadyUnlocked = when {
                    liveFresh && liveBleLocked != null -> liveBleLocked == false
                    hint.isFresh(maxAgeMs = 60_000L) && hint.locked == false -> true
                    else -> false
                }
                if (knownAlreadyUnlocked) {
                    unlockedThisVisit = true
                    hasBeenNear = true
                    consecutiveUnlockHits = 0
                    PassiveEntryRuntime.updateFromService { it.copy(state = PassiveState.UNLOCKED_NEAR) }
                    PassiveEntryRuntime.appendLog("已靠近（${filteredRssi} dBm），车况已处于解锁状态，跳过重复解锁")
                } else {
                    consecutiveUnlockHits = 0
                    val accepted = controller.sendVehicleAction("unlock")
                    if (accepted) {
                        lastActionElapsedMs = nowElapsed
                        unlockedThisVisit = true
                        lockedThisVisit = false
                        hasBeenNear = true
                        PassiveEntryRuntime.updateFromService { it.copy(state = PassiveState.UNLOCKED_NEAR) }
                        PassiveEntryRuntime.appendLog("触发靠近自动解锁（滤波 ${filteredRssi} dBm >= ${unlockThreshold} dBm）")
                    } else {
                        PassiveEntryRuntime.appendLog("靠近自动解锁指令未能发送，保留待触发状态")
                    }
                }
            }
            return
        } else {
            consecutiveUnlockHits = 0
        }

        // 2. Walk-Away Auto-Lock (requires prior near presence + live BLE & HTTP safety hint gate)
        if (cfg.autoLockEnabled && hasBeenNear && !lockedThisVisit && filteredRssi <= lockThreshold) {
            consecutiveLockHits++
            if (consecutiveLockHits >= LOCK_CONFIRM_SAMPLES && !inCooldown) {
                if (liveFresh && liveBleEngineRunning == true) {
                    consecutiveLockHits = 0
                    PassiveEntryRuntime.appendLog("离车阈值命中（${filteredRssi} dBm），但实时蓝牙车况显示未熄火，已拦截自动上锁")
                    return
                }
                if (liveFresh && liveBleDoorsOpen == true) {
                    consecutiveLockHits = 0
                    PassiveEntryRuntime.appendLog("离车阈值命中（${filteredRssi} dBm），但实时蓝牙车况显示车门未关，已拦截自动上锁")
                    return
                }
                val hint = PassiveEntryStore.getVehicleSafetyHint(this, profile.vin)
                if (hint.isFresh()) {
                    if (hint.engineStopped == false) {
                        consecutiveLockHits = 0
                        PassiveEntryRuntime.appendLog("离车阈值命中（${filteredRssi} dBm），但车况显示未熄火，已拦截自动上锁")
                        return
                    }
                    if (hint.doorsClosed == false) {
                        consecutiveLockHits = 0
                        PassiveEntryRuntime.appendLog("离车阈值命中（${filteredRssi} dBm），但车况显示车门未关，已拦截自动上锁")
                        return
                    }
                }
                consecutiveLockHits = 0
                val accepted = controller.sendVehicleAction("lock")
                if (accepted) {
                    lastActionElapsedMs = nowElapsed
                    lockedThisVisit = true
                    unlockedThisVisit = false
                    PassiveEntryRuntime.updateFromService { it.copy(state = PassiveState.LOCKED_AWAY) }
                    PassiveEntryRuntime.appendLog("触发离车自动上锁（滤波 ${filteredRssi} dBm <= ${lockThreshold} dBm）")
                } else {
                    PassiveEntryRuntime.appendLog("离车自动上锁指令未能发送，保留待触发状态")
                }
            }
        } else if (filteredRssi > lockThreshold + 3) {
            consecutiveLockHits = 0
        }
    }

    private fun checkAutoSleep() {
        val now = SystemClock.elapsedRealtime()
        val sessionReady = bleController?.isSessionReady() == true

        // Case 1: Already locked on walk-away and disconnected/far for >= 35s
        if (lockedThisVisit && (!sessionReady || (now - lastActionElapsedMs) >= POST_LOCK_SLEEP_MS)) {
            if (!sessionReady && disconnectedSinceElapsedMs > 0L && (now - disconnectedSinceElapsedMs) >= 12_000L) {
                shutdownAndSleep("离车上锁完成并已远离，退回低功耗监听", PassiveSleepReason.LOCKED_AWAY)
                return
            }
            if ((now - lastActionElapsedMs) >= POST_LOCK_SLEEP_MS) {
                shutdownAndSleep("离车上锁已稳定，释放前台连接退回低功耗监听", PassiveSleepReason.LOCKED_AWAY)
                return
            }
        }

        // Case 2: Never established session within 45s of wake
        if (!hasEverEstablishedSession && !sessionReady && (now - serviceStartedElapsedMs) >= CONNECT_GIVE_UP_MS) {
            shutdownAndSleep("超时未建连本车蓝牙，退回低功耗监听", PassiveSleepReason.CONNECT_FAILED)
            return
        }

        // Case 3: Disconnected mid-session for > 30s
        if (hasEverEstablishedSession && !sessionReady && disconnectedSinceElapsedMs > 0L && (now - disconnectedSinceElapsedMs) >= DISCONNECT_SLEEP_MS) {
            shutdownAndSleep("蓝牙已断开超过 30 秒，退回低功耗监听", PassiveSleepReason.DISCONNECT)
            return
        }

        // Case 4: Idle far from vehicle for > 120s
        if (!hasBeenNear && (now - lastNearActivityElapsedMs) >= IDLE_FAR_SLEEP_MS) {
            shutdownAndSleep("长时间未进入近场解锁区，退回低功耗监听", PassiveSleepReason.IDLE_FAR)
        }
    }

    private fun shutdownAndSleep(reason: String, sleepReason: PassiveSleepReason = PassiveSleepReason.NONE) {
        mainHandler.removeCallbacks(rssiPollTask)
        pendingConnectRunnable?.let(mainHandler::removeCallbacks)
        pendingConnectRunnable = null
        pendingReconnectRunnable?.let(mainHandler::removeCallbacks)
        pendingReconnectRunnable = null
        runCatching { bleController?.close() }
        bleController = null
        activeControllerMac = ""
        if (sleepReason != PassiveSleepReason.NONE) {
            PassiveEntryRuntime.recordSleep(sleepReason)
        }
        val activeVin = activeProfile?.vin?.ifBlank { null } ?: PassiveEntryStore.getActiveEnabledVin(this)
        val enabled = activeVin.isNotBlank() && PassiveEntryStore.load(this, activeVin).enabled
        PassiveEntryRuntime.updateFromService {
            it.copy(
                serviceRunning = false,
                sessionReady = false,
                currentRawRssi = null,
                filteredRssi = null,
                state = when {
                    !enabled -> PassiveState.DISABLED
                    PassiveEntryRuntime.isForegroundAppActive() -> PassiveState.PAUSED_FOREGROUND
                    it.wakeChannelActive -> PassiveState.SLEEPING_WATCH
                    else -> PassiveState.DISABLED
                }
            )
        }
        PassiveEntryRuntime.appendLog(reason)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(rssiPollTask)
        pendingConnectRunnable?.let(mainHandler::removeCallbacks)
        pendingConnectRunnable = null
        pendingReconnectRunnable?.let(mainHandler::removeCallbacks)
        pendingReconnectRunnable = null
        runCatching { bleController?.close() }
        bleController = null
        activeControllerMac = ""
        PassiveEntryRuntime.updateFromService {
            it.copy(
                serviceRunning = false,
                sessionReady = false,
                state = when {
                    !it.enabled -> PassiveState.DISABLED
                    PassiveEntryRuntime.isForegroundAppActive() -> PassiveState.PAUSED_FOREGROUND
                    it.wakeChannelActive -> PassiveState.SLEEPING_WATCH
                    else -> PassiveState.DISABLED
                }
            )
        }
        super.onDestroy()
    }

    private fun promoteToForeground(contentText: String): Boolean {
        return runCatching {
            val notification = buildNotification(contentText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrDefault(false)
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(contentText)) }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPi = launchIntent?.let {
            PendingIntent.getActivity(this, 42089, it, piFlags)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_qiri)
            .setContentTitle("Qiri+ 蓝牙无感钥匙运行中")
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPi)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "蓝牙无感钥匙",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "靠近自动解锁与离车自动上锁连接状态"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "car_plus_passive_entry"
        private const val NOTIFICATION_ID = 42090

        private const val ACTION_WAKE = "com.chery.carplus.ble.ACTION_PASSIVE_WAKE"
        private const val ACTION_COMPANION_DISAPPEARED = "com.chery.carplus.ble.ACTION_COMPANION_DISAPPEARED"
        private const val EXTRA_VIN = "extra_vin"
        private const val EXTRA_BLE_MAC = "extra_ble_mac"
        private const val EXTRA_BLE_NAME = "extra_ble_name"
        private const val EXTRA_INITIAL_RSSI = "extra_initial_rssi"
        private const val EXTRA_WAKE_SOURCE = "extra_wake_source"
        private const val EXTRA_ASSUME_NEAR = "extra_assume_near"
        private const val EXTRA_CONNECT_DELAY_MS = "extra_connect_delay_ms"

        private const val RSSI_POLL_INTERVAL_MS = 1_200L
        private const val RSSI_WINDOW_SIZE = 5
        private const val UNLOCK_CONFIRM_SAMPLES = 2
        private const val LOCK_CONFIRM_SAMPLES = 4
        private const val ACTION_COOLDOWN_MS = 20_000L
        private const val POST_LOCK_SLEEP_MS = 35_000L
        private const val CONNECT_GIVE_UP_MS = 45_000L
        private const val DISCONNECT_SLEEP_MS = 30_000L
        private const val IDLE_FAR_SLEEP_MS = 120_000L
        private const val LIVE_BLE_HINT_TTL_MS = 60_000L
        private const val MAX_MID_SESSION_RECONNECTS = 2

        fun startForWake(
            context: Context,
            vin: String,
            bleMac: String,
            bleName: String,
            initialRssi: Int?,
            wakeSource: String,
            assumeAlreadyNear: Boolean = false,
            connectDelayMs: Long = 0L
        ) {
            val appContext = context.applicationContext
            // Pre-check permissions and Bluetooth state before starting a connectedDevice FGS
            // to avoid Android 14-15 SecurityException crashes if permissions were revoked.
            if (!PassiveEntryPrerequisiteChecker.isBluetoothEnabled(appContext) ||
                PassiveEntryPrerequisiteChecker.missingBlePermissions(appContext).isNotEmpty()
            ) {
                PassiveEntryRuntime.appendLog("后台蓝牙权限或蓝牙开关未就绪，跳过拉起无感服务")
                return
            }
            val intent = Intent(appContext, PassiveEntryService::class.java).apply {
                action = ACTION_WAKE
                putExtra(EXTRA_VIN, vin)
                putExtra(EXTRA_BLE_MAC, bleMac)
                putExtra(EXTRA_BLE_NAME, bleName)
                putExtra(EXTRA_WAKE_SOURCE, wakeSource)
                putExtra(EXTRA_ASSUME_NEAR, assumeAlreadyNear)
                if (connectDelayMs > 0L) putExtra(EXTRA_CONNECT_DELAY_MS, connectDelayMs)
                if (initialRssi != null) putExtra(EXTRA_INITIAL_RSSI, initialRssi)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }.onFailure { err ->
                PassiveEntryRuntime.appendLog("拉起后台无感服务失败：${err.message ?: err.javaClass.simpleName}")
            }
        }

        fun notifyCompanionDisappeared(context: Context, bleMac: String) {
            if (!PassiveEntryRuntime.isServiceRunning()) return
            val appContext = context.applicationContext
            val intent = Intent(appContext, PassiveEntryService::class.java).apply {
                action = ACTION_COMPANION_DISAPPEARED
                putExtra(EXTRA_BLE_MAC, bleMac)
            }
            runCatching { appContext.startService(intent) }
        }

        fun stopService(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, PassiveEntryService::class.java)
                )
            }
        }
    }
}
