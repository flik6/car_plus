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
import com.chery.carplus.widget.CarWidgetManager
import com.chery.carplus.widget.CarWidgetStateStore
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
    private var foregroundLastBleVin: String = ""

    @Volatile
    private var lastSleepReason: PassiveSleepReason = PassiveSleepReason.NONE

    @Volatile
    private var lastSleepAtElapsedMs: Long = 0L

    private val lockSuppressionByVin = HashMap<String, Pair<PassiveLockSuppressionReason, Long>>()
    private val lastAutoUnlockByVin = HashMap<String, Long>()

    private const val MANUAL_LOCK_SUPPRESS_MS = 120_000L
    private const val WALK_AWAY_LOCK_SUPPRESS_MS = 300_000L
    const val AUTO_UNLOCK_COOLDOWN_MS = 20_000L

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

    fun recordLockSuppression(
        context: Context,
        vin: String,
        reason: PassiveLockSuppressionReason
    ) {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            if (reason == PassiveLockSuppressionReason.NONE) {
                lockSuppressionByVin.remove(normVin)
            } else {
                lockSuppressionByVin[normVin] = reason to nowElapsed
            }
        }
        PassiveEntryStore.recordLockSuppression(context, normVin, reason)
    }

    fun clearLockSuppression(context: Context, vin: String) {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return
        synchronized(stateLock) {
            lockSuppressionByVin.remove(normVin)
        }
        PassiveEntryStore.clearLockSuppression(context, normVin)
    }

    fun activeLockSuppressionReason(
        context: Context,
        vin: String,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): PassiveLockSuppressionReason {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return PassiveLockSuppressionReason.NONE
        val memEntry = synchronized(stateLock) { lockSuppressionByVin[normVin] }
        if (memEntry != null) {
            val (reason, recordedElapsed) = memEntry
            val maxAge = suppressionWindowMs(reason)
            val elapsed = (nowElapsed - recordedElapsed).coerceAtLeast(0L)
            if (maxAge > 0L && elapsed < maxAge) {
                return reason
            }
            synchronized(stateLock) {
                if (lockSuppressionByVin[normVin] == memEntry) {
                    lockSuppressionByVin.remove(normVin)
                }
            }
            PassiveEntryStore.clearLockSuppression(context, normVin)
            return PassiveLockSuppressionReason.NONE
        }
        val persisted = PassiveEntryStore.getLockSuppression(context, normVin)
        if (persisted.reason != PassiveLockSuppressionReason.NONE && persisted.recordedAtMs > 0L) {
            val maxAge = suppressionWindowMs(persisted.reason)
            val wallAge = System.currentTimeMillis() - persisted.recordedAtMs
            if (maxAge > 0L && wallAge in 0L until maxAge) {
                synchronized(stateLock) {
                    lockSuppressionByVin[normVin] = persisted.reason to (nowElapsed - wallAge).coerceAtLeast(0L)
                }
                return persisted.reason
            }
            PassiveEntryStore.clearLockSuppression(context, normVin)
        }
        return PassiveLockSuppressionReason.NONE
    }

    private fun suppressionWindowMs(reason: PassiveLockSuppressionReason): Long = when (reason) {
        PassiveLockSuppressionReason.MANUAL_LOCK -> MANUAL_LOCK_SUPPRESS_MS
        PassiveLockSuppressionReason.WALK_AWAY_LOCK -> WALK_AWAY_LOCK_SUPPRESS_MS
        PassiveLockSuppressionReason.NONE -> 0L
    }

    fun recordAutoUnlockDispatched(vin: String, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return
        synchronized(stateLock) {
            lastAutoUnlockByVin[normVin] = nowElapsed
        }
    }

    fun isAutoUnlockInCooldown(vin: String, nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isBlank()) return false
        val lastAt = synchronized(stateLock) { lastAutoUnlockByVin[normVin] } ?: return false
        return (nowElapsed - lastAt) in 0L until AUTO_UNLOCK_COOLDOWN_MS
    }

    fun reportForegroundAutoUnlock(
        context: Context,
        vin: String,
        label: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val normVin = vin.trim().uppercase(Locale.US)
        if (normVin.isNotBlank()) {
            PassiveEntryStore.recordTrigger(context, normVin, label, nowMs)
            PassiveEntryStore.updateVehicleLockHint(context, normVin, locked = false, updatedAtMs = nowMs)
            syncWidgetLockState(context, normVin, locked = false, hintText = "蓝牙无感：$label")
            recordAutoUnlockDispatched(normVin)
        }
        synchronized(stateLock) {
            snapshot = snapshot.copy(
                state = PassiveState.UNLOCKED_NEAR,
                lastEventText = label,
                lastEventAtMs = nowMs
            )
        }
        appendLog("前台蓝牙已连接，执行$label")
    }

    fun syncWidgetLockState(context: Context, vin: String, locked: Boolean, hintText: String) {
        val appContext = context.applicationContext
        runCatching {
            val snap = CarWidgetStateStore.load(appContext)
            if (snap.vin.isNotBlank() && snap.vin.equals(vin, ignoreCase = true)) {
                val nextPrimary = if (!snap.isCharging && !snap.engineRunning) {
                    if (locked) "已锁止" else "已解锁"
                } else {
                    snap.primaryStatus
                }
                CarWidgetStateStore.save(
                    appContext,
                    snap.copy(
                        doorLocked = locked,
                        primaryStatus = nextPrimary,
                        transientHint = hintText,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                CarWidgetManager.updateAllWidgets(appContext)
            }
        }
    }

    fun markCompanionDisappeared(context: Context? = null) {
        synchronized(stateLock) {
            if (lastSleepReason == PassiveSleepReason.LOCKED_AWAY) {
                lastSleepReason = PassiveSleepReason.NONE
                lastSleepAtElapsedMs = 0L
            }
            lockSuppressionByVin.clear()
        }
        if (context != null) {
            val activeVin = PassiveEntryStore.getActiveEnabledVin(context)
            if (activeVin.isNotBlank()) {
                PassiveEntryStore.clearLockSuppression(context, activeVin)
            }
        }
    }

    fun shouldThrottleScanWake(
        context: Context,
        vin: String,
        rssi: Int,
        cfg: PassiveEntryConfig,
        nowElapsed: Long
    ): String? {
        if (rssi < cfg.effectiveLockRssi - 8) {
            return "信号过弱 (${rssi} dBm)"
        }
        val suppression = activeLockSuppressionReason(context, vin, nowElapsed)
        if (suppression == PassiveLockSuppressionReason.MANUAL_LOCK) {
            return "手动锁车保护期内，暂不唤醒"
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
        handoffRssi: Int? = null,
        handoffVin: String = "",
        handoffAlreadyUnlocked: Boolean = false
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
            val normHandoffVin = handoffVin.trim().uppercase(Locale.US)
            val (trackedNear, trackedRssi, trackedVin) = synchronized(stateLock) {
                val triple = Triple(foregroundWasNearConnected, foregroundLastRssi, foregroundLastBleVin)
                foregroundWasNearConnected = false
                triple
            }
            val effectiveVin = normHandoffVin.ifBlank { trackedVin }
            val vinMatchesActive = activeVin.isNotBlank() &&
                (effectiveVin.isBlank() || effectiveVin.equals(activeVin, ignoreCase = true))
            val wasNear = if (vinMatchesActive) {
                handoffFromNearSession || trackedNear
            } else {
                false
            }
            val effectiveRssi = if (vinMatchesActive) {
                handoffRssi ?: trackedRssi
            } else {
                null
            }
            if (cfg?.enabled == true) {
                if (vinMatchesActive && handoffAlreadyUnlocked) {
                    PassiveEntryStore.updateVehicleLockHint(appContext, activeVin, locked = false)
                }
                val hint = PassiveEntryStore.getVehicleSafetyHint(appContext, activeVin)
                val knownUnlocked = (vinMatchesActive && handoffAlreadyUnlocked) ||
                    (hint.isFresh(maxAgeMs = 120_000L) && hint.locked == false)
                val suppressed = activeLockSuppressionReason(appContext, activeVin) != PassiveLockSuppressionReason.NONE
                val needsHandoff = wasNear && (cfg.autoLockEnabled || (cfg.autoUnlockEnabled && !knownUnlocked && !suppressed))
                if (needsHandoff) {
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
                            connectDelayMs = 550L,
                            assumeAlreadyUnlocked = knownUnlocked
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

    fun reportForegroundBleRssi(rssi: Int?, sessionReady: Boolean, vin: String = "") {
        val normVin = vin.trim().uppercase(Locale.US)
        synchronized(stateLock) {
            if (normVin.isNotBlank()) {
                foregroundLastBleVin = normVin
            }
            foregroundLastRssi = rssi
            if (sessionReady) {
                foregroundWasNearConnected = true
            } else if (rssi == null) {
                foregroundWasNearConnected = false
            }
            if (foregroundAppActive) {
                val nextState = if (!sessionReady && snapshot.state == PassiveState.UNLOCKED_NEAR) {
                    PassiveState.PAUSED_FOREGROUND
                } else {
                    snapshot.state
                }
                snapshot = snapshot.copy(
                    currentRawRssi = rssi,
                    filteredRssi = rssi,
                    sessionReady = sessionReady,
                    state = nextState
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
    private var activeVin: String = ""
    private var targetMac: String = ""

    private val rssiWindow = ArrayDeque<Int>()
    private var consecutiveUnlockHits = 0
    private var consecutiveLockHits = 0
    private var hasBeenNear = false
    private var hasEverEstablishedSession = false
    private var reconnectAttempts = 0
    private var unlockedThisVisit = false
    private var lockedThisVisit = false
    private var connectUnlockEvaluatedThisSession = false
    private var lastUnlockLabel = "连接自动解锁"
    private var lastActionElapsedMs = 0L
    private var sessionReadyElapsedMs = 0L
    private var serviceStartedElapsedMs = 0L
    private var lastNearActivityElapsedMs = 0L
    private var disconnectedSinceElapsedMs = 0L
    private var directConnectRetriedWithScan = false
    private var pendingConnectRunnable: Runnable? = null
    private var pendingReconnectRunnable: Runnable? = null
    private var pendingConnectUnlockRunnable: Runnable? = null

    // Tracks in-flight passive commands until T-Box 0x04 confirmation arrives (P2-31).
    private var inFlightPassiveActionKey: String? = null
    private var inFlightPassiveActionLabel: String = ""
    private var inFlightPassiveActionElapsedMs: Long = 0L
    private val pendingPassiveActionBySerial = mutableMapOf<Int, Pair<String, String>>()

    // Live vehicle telemetry reported directly over BLE 0x10 / 0x04 responses.
    private var liveBleLocked: Boolean? = null
    private var liveBleDoorsOpen: Boolean? = null
    private var liveBleEngineRunning: Boolean? = null
    private var liveBleUpdatedElapsedMs = 0L

    private fun resetVisitStateForVehicleSwitch(nowElapsed: Long) {
        cancelPendingConnectUnlockCheck()
        pendingConnectRunnable?.let(mainHandler::removeCallbacks)
        pendingConnectRunnable = null
        pendingReconnectRunnable?.let(mainHandler::removeCallbacks)
        pendingReconnectRunnable = null
        rssiWindow.clear()
        consecutiveUnlockHits = 0
        consecutiveLockHits = 0
        hasBeenNear = false
        hasEverEstablishedSession = false
        reconnectAttempts = 0
        unlockedThisVisit = false
        lockedThisVisit = false
        connectUnlockEvaluatedThisSession = false
        lastUnlockLabel = "连接自动解锁"
        lastActionElapsedMs = 0L
        sessionReadyElapsedMs = 0L
        serviceStartedElapsedMs = nowElapsed
        lastNearActivityElapsedMs = nowElapsed
        disconnectedSinceElapsedMs = 0L
        directConnectRetriedWithScan = false
        inFlightPassiveActionKey = null
        inFlightPassiveActionLabel = ""
        inFlightPassiveActionElapsedMs = 0L
        pendingPassiveActionBySerial.clear()
        liveBleLocked = null
        liveBleDoorsOpen = null
        liveBleEngineRunning = null
        liveBleUpdatedElapsedMs = 0L
    }

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
        val assumeUnlocked = intent?.getBooleanExtra(EXTRA_ASSUME_UNLOCKED, false) == true
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
        val wakeMac = PassiveEntryPrerequisiteChecker.normalizeMac(intent?.getStringExtra(EXTRA_BLE_MAC))
        val mac = wakeMac ?: PassiveEntryPrerequisiteChecker.normalizeMac(profile?.bleMac)
        if (profile == null || mac == null || !PassiveEntryPrerequisiteChecker.hasCompleteKeyMaterial(profile)) {
            PassiveEntryRuntime.appendLog("后台唤醒取消：钥匙材料不完整")
            shutdownAndSleep("钥匙材料不完整", PassiveSleepReason.CONNECT_FAILED)
            return START_NOT_STICKY
        }

        val switchingVehicle = (activeVin.isNotBlank() && !activeVin.equals(profile.vin, ignoreCase = true)) ||
            (activeVin.isBlank() && targetMac.isNotBlank() && !targetMac.equals(mac, ignoreCase = true))
        if (switchingVehicle) {
            resetVisitStateForVehicleSwitch(SystemClock.elapsedRealtime())
        }

        activeProfile = profile
        activeConfig = cfg
        activeVin = profile.vin
        targetMac = mac
        if (assumeNear) {
            hasBeenNear = true
        }
        if (assumeUnlocked) {
            unlockedThisVisit = true
            connectUnlockEvaluatedThisSession = true
            hasBeenNear = true
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
            val resolvedAccountType = if (profile.keyRole > 0) {
                com.chery.carplus.cloud.InGeekCloudProvisioner.accountTypeForKeyRole(profile.keyRole)
            } else {
                profile.accountType.coerceIn(0, 1)
            }
            CheryBleController.SessionMaterial(
                rootKey16 = root,
                infoBin147 = info,
                edfBin = edf,
                accountBinding = CheryBleController.AccountBinding(
                    accountType = resolvedAccountType,
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
        val nowElapsed = SystemClock.elapsedRealtime()
        hasEverEstablishedSession = true
        reconnectAttempts = 0
        disconnectedSinceElapsedMs = 0L
        sessionReadyElapsedMs = nowElapsed
        connectUnlockEvaluatedThisSession = false
        pendingReconnectRunnable?.let(mainHandler::removeCallbacks)
        pendingReconnectRunnable = null
        lastNearActivityElapsedMs = nowElapsed
        bleController?.stopAutoProximityWatch()
        val state = if (unlockedThisVisit) PassiveState.UNLOCKED_NEAR else PassiveState.NEAR_READY
        PassiveEntryRuntime.updateFromService {
            it.copy(
                sessionReady = true,
                state = state
            )
        }
        PassiveEntryRuntime.appendLog("后台蓝牙数字钥匙会话已就绪，正在检查车辆锁止状态")
        updateNotification("蓝牙数字钥匙已连接 · 正在监测车辆锁止与距离")
        bleController?.requestRemoteRssi()
        scheduleConnectAutoUnlockCheck()
    }

    private fun cancelPendingConnectUnlockCheck() {
        pendingConnectUnlockRunnable?.let(mainHandler::removeCallbacks)
        pendingConnectUnlockRunnable = null
    }

    private fun scheduleConnectAutoUnlockCheck() {
        cancelPendingConnectUnlockCheck()
        if (unlockedThisVisit || lockedThisVisit || connectUnlockEvaluatedThisSession) return
        val hasSessionLiveLock = liveBleLocked != null &&
            liveBleUpdatedElapsedMs >= (sessionReadyElapsedMs - 2_000L)
        if (hasSessionLiveLock) {
            evaluateConnectAutoUnlock(triggerSource = "蓝牙实时车况")
            return
        }
        val task = Runnable {
            pendingConnectUnlockRunnable = null
            evaluateConnectAutoUnlock(triggerSource = "蓝牙连接完成")
        }
        pendingConnectUnlockRunnable = task
        mainHandler.postDelayed(task, CONNECT_STATUS_SETTLE_MS)
    }

    private fun evaluateConnectAutoUnlock(triggerSource: String) {
        if (unlockedThisVisit || lockedThisVisit || connectUnlockEvaluatedThisSession) return
        val controller = bleController ?: return
        if (!controller.isSessionReady()) return
        val profile = activeProfile ?: return
        val cfg = PassiveEntryStore.load(this, profile.vin)
        activeConfig = cfg
        if (!cfg.enabled) {
            shutdownAndSleep("无感功能已关闭", PassiveSleepReason.DISABLED)
            return
        }
        if (!cfg.autoUnlockEnabled) {
            connectUnlockEvaluatedThisSession = true
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val suppression = PassiveEntryRuntime.activeLockSuppressionReason(this, profile.vin, nowElapsed)
        if (suppression != PassiveLockSuppressionReason.NONE) {
            connectUnlockEvaluatedThisSession = true
            PassiveEntryRuntime.appendLog(
                "蓝牙已连接（$triggerSource），处于${suppression.label}保护期，跳过连接自动解锁"
            )
            return
        }

        val liveFresh = liveBleUpdatedElapsedMs > 0L &&
            (nowElapsed - liveBleUpdatedElapsedMs) in 0L..LIVE_BLE_HINT_TTL_MS
        val hint = PassiveEntryStore.getVehicleSafetyHint(this, profile.vin)
        val hintFreshForUnlock = hint.isFresh(maxAgeMs = UNLOCK_HINT_TTL_MS)

        val engineRunning = when {
            liveFresh && liveBleEngineRunning != null -> liveBleEngineRunning == true
            hintFreshForUnlock && hint.engineStopped != null -> hint.engineStopped == false
            else -> false
        }
        if (engineRunning) {
            connectUnlockEvaluatedThisSession = true
            PassiveEntryRuntime.appendLog("蓝牙已连接（$triggerSource），车况显示处于启动状态，跳过自动解锁")
            return
        }

        val resolvedLocked: Boolean? = when {
            liveFresh && liveBleLocked != null -> liveBleLocked
            hintFreshForUnlock && hint.locked != null -> hint.locked
            else -> null
        }
        if (resolvedLocked == false) {
            connectUnlockEvaluatedThisSession = true
            unlockedThisVisit = true
            hasBeenNear = true
            PassiveEntryRuntime.updateFromService { it.copy(state = PassiveState.UNLOCKED_NEAR) }
            updateNotification("蓝牙数字钥匙已连接 · 车辆已处于解锁状态")
            PassiveEntryRuntime.appendLog("蓝牙已连接（$triggerSource），车辆已处于解锁状态，跳过自动解锁")
            return
        }

        val inCooldown = (nowElapsed - lastActionElapsedMs) < ACTION_COOLDOWN_MS ||
            PassiveEntryRuntime.isAutoUnlockInCooldown(profile.vin, nowElapsed)
        if (inCooldown) {
            connectUnlockEvaluatedThisSession = true
            PassiveEntryRuntime.appendLog("蓝牙已连接（$triggerSource），处于防抖冷却期内，跳过重复解锁")
            return
        }

        if (isPassiveActionInFlight(nowElapsed)) {
            return
        }

        lastUnlockLabel = "连接自动解锁"
        val accepted = controller.sendVehicleAction("unlock")
        if (accepted) {
            connectUnlockEvaluatedThisSession = true
            inFlightPassiveActionKey = "unlock"
            inFlightPassiveActionLabel = lastUnlockLabel
            inFlightPassiveActionElapsedMs = nowElapsed
            hasBeenNear = true
            lastNearActivityElapsedMs = nowElapsed
            consecutiveUnlockHits = 0
            updateNotification("正在执行连接自动解锁，等待车端确认...")
            val stateDesc = if (resolvedLocked == true) "车辆未解锁" else "车况未回报已解锁"
            PassiveEntryRuntime.appendLog("蓝牙已连接（$triggerSource，$stateDesc），已下发自动解锁指令，等待车端确认")
        } else {
            PassiveEntryRuntime.appendLog("连接自动解锁指令未能发送，保留靠近测距兜底")
        }
    }

    private fun isPassiveActionInFlight(nowElapsed: Long): Boolean =
        inFlightPassiveActionKey != null &&
            (nowElapsed - inFlightPassiveActionElapsedMs) in 0L until IN_FLIGHT_ACTION_GUARD_MS

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
            if (parsedResponse.doorLocked != null &&
                pendingConnectUnlockRunnable != null &&
                bleController?.isSessionReady() == true
            ) {
                cancelPendingConnectUnlockCheck()
                evaluateConnectAutoUnlock(triggerSource = "蓝牙实时车况")
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
            val label = if (inFlightPassiveActionKey == actionKey && inFlightPassiveActionLabel.isNotBlank()) {
                inFlightPassiveActionLabel
            } else {
                when (actionKey) {
                    "unlock" -> lastUnlockLabel
                    "lock" -> "离车自动上锁"
                    else -> "无感控车 ($actionKey)"
                }
            }
            pendingPassiveActionBySerial[serial] = actionKey to label
            PassiveEntryRuntime.appendLog("已写入加密指令：$label (serial=$serial)，等待车端 0x04 确认")
        }
    }

    override fun onActionResult(
        actionKey: String,
        serial: Int,
        success: Boolean,
        parsedResponse: ParsedTBoxResponse?,
        errorMessage: String?
    ) {
        mainHandler.post {
            val tracked = pendingPassiveActionBySerial.remove(serial)
            val effectiveAction = tracked?.first?.ifBlank { actionKey } ?: actionKey
            val label = tracked?.second ?: when (effectiveAction) {
                "unlock" -> lastUnlockLabel
                "lock" -> "离车自动上锁"
                else -> "无感控车 ($effectiveAction)"
            }
            if (inFlightPassiveActionKey == effectiveAction) {
                inFlightPassiveActionKey = null
                inFlightPassiveActionLabel = ""
                inFlightPassiveActionElapsedMs = 0L
            }
            val profile = activeProfile ?: return@post
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowMs = System.currentTimeMillis()
            if (!success) {
                val reason = errorMessage ?: parsedResponse?.summaryText ?: "车端未确认执行"
                PassiveEntryRuntime.appendLog("车端拒绝或未确认$label：$reason")
                updateNotification("蓝牙数字钥匙已连接 · ${label}未完成")
                return@post
            }
            when (effectiveAction) {
                "unlock" -> {
                    lastActionElapsedMs = nowElapsed
                    unlockedThisVisit = true
                    lockedThisVisit = false
                    hasBeenNear = true
                    lastNearActivityElapsedMs = nowElapsed
                    liveBleLocked = false
                    liveBleUpdatedElapsedMs = nowElapsed
                    PassiveEntryRuntime.clearLockSuppression(this, profile.vin)
                    PassiveEntryRuntime.recordAutoUnlockDispatched(profile.vin, nowElapsed)
                    PassiveEntryStore.updateVehicleLockHint(this, profile.vin, locked = false, updatedAtMs = nowMs)
                    PassiveEntryRuntime.syncWidgetLockState(this, profile.vin, locked = false, hintText = "蓝牙无感：$label")
                    PassiveEntryStore.recordTrigger(this, profile.vin, label, nowMs)
                    PassiveEntryRuntime.updateFromService {
                        it.copy(
                            state = PassiveState.UNLOCKED_NEAR,
                            lastEventText = label,
                            lastEventAtMs = nowMs
                        )
                    }
                    PassiveEntryRuntime.appendLog("车端已确认执行：$label")
                    updateNotification("已执行$label")
                    PassiveEntryRuntime.onAutoActionExecuted?.invoke(effectiveAction, profile.vin)
                }
                "lock" -> {
                    lastActionElapsedMs = nowElapsed
                    lockedThisVisit = true
                    unlockedThisVisit = false
                    liveBleLocked = true
                    liveBleUpdatedElapsedMs = nowElapsed
                    PassiveEntryRuntime.recordLockSuppression(
                        this,
                        profile.vin,
                        PassiveLockSuppressionReason.WALK_AWAY_LOCK
                    )
                    PassiveEntryStore.updateVehicleLockHint(this, profile.vin, locked = true, updatedAtMs = nowMs)
                    PassiveEntryRuntime.syncWidgetLockState(this, profile.vin, locked = true, hintText = "蓝牙无感：$label")
                    PassiveEntryStore.recordTrigger(this, profile.vin, label, nowMs)
                    PassiveEntryRuntime.updateFromService {
                        it.copy(
                            state = PassiveState.LOCKED_AWAY,
                            lastEventText = label,
                            lastEventAtMs = nowMs
                        )
                    }
                    PassiveEntryRuntime.appendLog("车端已确认执行：$label")
                    updateNotification("已执行$label")
                    PassiveEntryRuntime.onAutoActionExecuted?.invoke(effectiveAction, profile.vin)
                }
                else -> {
                    PassiveEntryStore.recordTrigger(this, profile.vin, label, nowMs)
                    PassiveEntryRuntime.updateFromService {
                        it.copy(lastEventText = label, lastEventAtMs = nowMs)
                    }
                    PassiveEntryRuntime.appendLog("车端已确认执行：$label")
                    updateNotification("已执行$label")
                    PassiveEntryRuntime.onAutoActionExecuted?.invoke(effectiveAction, profile.vin)
                }
            }
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, stateName: String) {
        mainHandler.post {
            if (!connected) {
                cancelPendingConnectUnlockCheck()
                inFlightPassiveActionKey = null
                inFlightPassiveActionLabel = ""
                inFlightPassiveActionElapsedMs = 0L
                pendingPassiveActionBySerial.clear()
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

        val inCooldown = (nowElapsed - lastActionElapsedMs) < ACTION_COOLDOWN_MS ||
            PassiveEntryRuntime.isAutoUnlockInCooldown(profile.vin, nowElapsed)

        val liveFresh = liveBleUpdatedElapsedMs > 0L && (nowElapsed - liveBleUpdatedElapsedMs) in 0L..LIVE_BLE_HINT_TTL_MS
        val suppression = PassiveEntryRuntime.activeLockSuppressionReason(this, profile.vin, nowElapsed)

        // 1. Approach Auto-Unlock (fallback & post-walk-away return to door; blocked during manual lock suppression)
        if (cfg.autoUnlockEnabled &&
            !unlockedThisVisit &&
            suppression != PassiveLockSuppressionReason.MANUAL_LOCK &&
            filteredRssi >= unlockThreshold
        ) {
            if (inCooldown) {
                consecutiveUnlockHits = 0
                return
            }
            consecutiveUnlockHits++
            consecutiveLockHits = 0
            if (consecutiveUnlockHits >= UNLOCK_CONFIRM_SAMPLES) {
                val hint = PassiveEntryStore.getVehicleSafetyHint(this, profile.vin)
                val engineRunning = when {
                    liveFresh && liveBleEngineRunning != null -> liveBleEngineRunning == true
                    hint.isFresh(maxAgeMs = UNLOCK_HINT_TTL_MS) && hint.engineStopped != null -> hint.engineStopped == false
                    else -> false
                }
                if (engineRunning) {
                    consecutiveUnlockHits = 0
                    PassiveEntryRuntime.appendLog("已靠近（${filteredRssi} dBm），但车况显示处于启动状态，跳过自动解锁")
                    return
                }
                val knownAlreadyUnlocked = when {
                    liveFresh && liveBleLocked != null -> liveBleLocked == false
                    hint.isFresh(maxAgeMs = UNLOCK_HINT_TTL_MS) && hint.locked == false -> true
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
                    if (isPassiveActionInFlight(nowElapsed)) return
                    cancelPendingConnectUnlockCheck()
                    lastUnlockLabel = "靠近自动解锁"
                    val accepted = controller.sendVehicleAction("unlock")
                    if (accepted) {
                        inFlightPassiveActionKey = "unlock"
                        inFlightPassiveActionLabel = lastUnlockLabel
                        inFlightPassiveActionElapsedMs = nowElapsed
                        connectUnlockEvaluatedThisSession = true
                        hasBeenNear = true
                        updateNotification("正在执行靠近自动解锁，等待车端确认...")
                        PassiveEntryRuntime.appendLog("已下发靠近自动解锁（滤波 ${filteredRssi} dBm >= ${unlockThreshold} dBm），等待车端确认")
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
        val lockInCooldown = (nowElapsed - lastActionElapsedMs) < ACTION_COOLDOWN_MS
        if (cfg.autoLockEnabled && hasBeenNear && !lockedThisVisit && filteredRssi <= lockThreshold) {
            if (lockInCooldown || isPassiveActionInFlight(nowElapsed)) {
                consecutiveLockHits = 0
                return
            }
            consecutiveLockHits++
            if (consecutiveLockHits >= LOCK_CONFIRM_SAMPLES) {
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
                    inFlightPassiveActionKey = "lock"
                    inFlightPassiveActionLabel = "离车自动上锁"
                    inFlightPassiveActionElapsedMs = nowElapsed
                    updateNotification("正在执行离车自动上锁，等待车端确认...")
                    PassiveEntryRuntime.appendLog("已下发离车自动上锁（滤波 ${filteredRssi} dBm <= ${lockThreshold} dBm），等待车端确认")
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
        cancelPendingConnectUnlockCheck()
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
        cancelPendingConnectUnlockCheck()
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
        private const val EXTRA_ASSUME_UNLOCKED = "extra_assume_unlocked"
        private const val EXTRA_CONNECT_DELAY_MS = "extra_connect_delay_ms"

        private const val RSSI_POLL_INTERVAL_MS = 1_200L
        private const val RSSI_WINDOW_SIZE = 5
        private const val UNLOCK_CONFIRM_SAMPLES = 2
        private const val LOCK_CONFIRM_SAMPLES = 4
        private const val ACTION_COOLDOWN_MS = 20_000L
        private const val IN_FLIGHT_ACTION_GUARD_MS = 8_000L
        private const val CONNECT_STATUS_SETTLE_MS = 600L
        private const val UNLOCK_HINT_TTL_MS = 120_000L
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
            connectDelayMs: Long = 0L,
            assumeAlreadyUnlocked: Boolean = false
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
                putExtra(EXTRA_ASSUME_UNLOCKED, assumeAlreadyUnlocked)
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
