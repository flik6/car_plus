package com.chery.carplus.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.profile.VehicleProfileStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class WakeRegistrationResult(
    val ok: Boolean,
    val pendingIntentRegistered: Boolean,
    val companionPresenceActive: Boolean,
    val channelLabel: String,
    val message: String
)

/**
 * Registers and tears down the two low-power background BLE wake channels:
 * 1. Android 8+ PendingIntent BLE scan with strict hardware ScanFilter on target vehicle MAC
 * 2. Android 12+ CompanionDeviceManager.startObservingDevicePresence (when associated)
 */
object PassiveEntryWakeManager {

    const val ACTION_PASSIVE_SCAN_RESULT = "com.chery.carplus.ble.ACTION_PASSIVE_SCAN_RESULT"
    private const val SCAN_PENDING_INTENT_REQUEST_CODE = 42088
    private val wakeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "passive-wake-io").apply { isDaemon = true }
    }
    private val wakeStateLock = Any()
    private val wakeGeneration = AtomicLong(0L)
    private val restoreQueueLock = Any()
    private var restoreRunning = false
    private var restorePending = false
    private val pendingRestoreCallbacks = mutableListOf<() -> Unit>()

    fun isPendingIntentSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private fun buildScanPendingIntent(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context.applicationContext, PassiveEntryScanReceiver::class.java).apply {
            action = ACTION_PASSIVE_SCAN_RESULT
        }
        val baseFlag = if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ BluetoothLeScanner populates EXTRA_LIST_SCAN_RESULT on delivery,
            // so PendingIntent MUST be mutable; explicit component Intent satisfies Android 14+.
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            SCAN_PENDING_INTENT_REQUEST_CODE,
            intent,
            baseFlag or mutabilityFlag
        )
    }

    @SuppressLint("MissingPermission")
    fun enableForVehicle(context: Context, profile: VehicleProfile): WakeRegistrationResult {
        val appContext = context.applicationContext
        val gen = wakeGeneration.incrementAndGet()
        synchronized(wakeStateLock) {
            if (wakeGeneration.get() != gen) {
                return WakeRegistrationResult(
                    ok = false,
                    pendingIntentRegistered = false,
                    companionPresenceActive = false,
                    channelLabel = "未开启",
                    message = "操作已被更新状态替代"
                )
            }
            return enableForVehicleLocked(
                appContext = appContext,
                profile = profile,
                expectedGen = gen,
                requirePersistedEnabled = false
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableForVehicleLocked(
        appContext: Context,
        profile: VehicleProfile,
        expectedGen: Long,
        requirePersistedEnabled: Boolean
    ): WakeRegistrationResult {
        if (wakeGeneration.get() != expectedGen) {
            return WakeRegistrationResult(
                ok = false,
                pendingIntentRegistered = false,
                companionPresenceActive = false,
                channelLabel = "未开启",
                message = "操作已被更新状态替代"
            )
        }
        val persistedConfig = PassiveEntryStore.load(appContext, profile.vin)
        val persistedActiveVin = PassiveEntryStore.getActiveEnabledVin(appContext)
        if (requirePersistedEnabled &&
            (!persistedConfig.enabled || !persistedActiveVin.equals(profile.vin, ignoreCase = true))
        ) {
            return WakeRegistrationResult(
                ok = false,
                pendingIntentRegistered = false,
                companionPresenceActive = false,
                channelLabel = "未开启",
                message = "蓝牙无感已关闭，跳过后台通道注册"
            )
        }

        val targetMac = PassiveEntryPrerequisiteChecker.normalizeMac(profile.bleMac)
            ?: return WakeRegistrationResult(
                ok = false,
                pendingIntentRegistered = false,
                companionPresenceActive = false,
                channelLabel = "未就绪",
                message = "车辆蓝牙地址无效"
            )

        if (!PassiveEntryPrerequisiteChecker.isBluetoothEnabled(appContext)) {
            return WakeRegistrationResult(
                ok = false,
                pendingIntentRegistered = false,
                companionPresenceActive = false,
                channelLabel = "蓝牙未开启",
                message = "请先开启系统蓝牙"
            )
        }
        if (PassiveEntryPrerequisiteChecker.missingBlePermissions(appContext).isNotEmpty()) {
            return WakeRegistrationResult(
                ok = false,
                pendingIntentRegistered = false,
                companionPresenceActive = false,
                channelLabel = "缺少蓝牙权限",
                message = "请先授予蓝牙扫描与连接权限"
            )
        }

        // If passive entry was previously enabled on another vehicle, tear down its CDM presence observer first.
        val previousActiveVin = PassiveEntryStore.getActiveEnabledVin(appContext)
        if (previousActiveVin.isNotBlank() && !previousActiveVin.equals(profile.vin, ignoreCase = true)) {
            val prevProfile = VehicleProfileStore(appContext).listProfiles()
                .firstOrNull { it.vin.equals(previousActiveVin, ignoreCase = true) }
            val prevCfg = PassiveEntryStore.load(appContext, previousActiveVin)
            val prevMac = PassiveEntryPrerequisiteChecker.normalizeMac(prevProfile?.bleMac)
                ?: PassiveEntryPrerequisiteChecker.normalizeMac(prevCfg.cdmAssociatedMac)
            if (prevMac != null && !prevMac.equals(targetMac, ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cdm = appContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
                if (cdm != null) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        cdm.stopObservingDevicePresence(prevMac)
                    }
                }
            }
            if (prevCfg.enabled) {
                PassiveEntryStore.save(appContext, prevCfg.copy(enabled = false))
            }
        }

        var piRegistered = false
        var cdmActive = false
        var piError = ""

        // Channel 1: PendingIntent BLE scan (Android 8.0+) with strict hardware MAC filter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val scanner = btManager?.adapter?.bluetoothLeScanner
            val pendingIntent = buildScanPendingIntent(appContext, create = true)
            if (scanner != null && pendingIntent != null) {
                runCatching { scanner.stopScan(pendingIntent) }
                val filters = buildList {
                    add(
                        ScanFilter.Builder()
                            .setDeviceAddress(targetMac)
                            .build()
                    )
                    val bleName = profile.bleName.trim()
                    if (bleName.isNotBlank()) {
                        add(
                            ScanFilter.Builder()
                                .setDeviceName(bleName)
                                .build()
                        )
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .setReportDelay(0L)
                    .build()
                val rc = runCatching {
                    scanner.startScan(filters, settings, pendingIntent)
                }.getOrElse { err ->
                    piError = err.message ?: "后台感应注册异常"
                    -1
                }
                piRegistered = (rc == 0)
                if (!piRegistered && piError.isBlank()) {
                    piError = "后台扫描注册返回码 $rc"
                }
            } else {
                piError = "蓝牙扫描器当前不可用"
            }
        }

        // Channel 2: CompanionDeviceManager device presence observation (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            PassiveEntryPrerequisiteChecker.isCompanionAssociated(appContext, targetMac)
        ) {
            val cdm = appContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
            if (cdm != null) {
                cdmActive = runCatching {
                    @Suppress("DEPRECATION")
                    cdm.startObservingDevicePresence(targetMac)
                    true
                }.getOrDefault(false)
            }
        }

        val ok = piRegistered || cdmActive
        val label = when {
            cdmActive && piRegistered -> "系统级双通道唤醒"
            cdmActive -> "系统级后台唤醒"
            piRegistered -> "后台省电监听"
            else -> "通道注册失败"
        }
        val msg = when {
            ok -> "已启动$label"
            piError.isNotBlank() -> piError
            else -> "当前系统不支持后台蓝牙感应"
        }

        val persistedEnabled = PassiveEntryStore.load(appContext, profile.vin).enabled
        val effectiveEnabled = if (requirePersistedEnabled) persistedEnabled else (ok || persistedEnabled)
        PassiveEntryRuntime.updateWakeChannelStatus(
            enabled = effectiveEnabled,
            active = ok && effectiveEnabled,
            channelLabel = if (effectiveEnabled) label else "未开启",
            logMessage = if (effectiveEnabled) msg else null
        )
        return WakeRegistrationResult(
            ok = ok,
            pendingIntentRegistered = piRegistered,
            companionPresenceActive = cdmActive,
            channelLabel = label,
            message = msg
        )
    }

    @SuppressLint("MissingPermission")
    fun disable(context: Context, bleMac: String? = null) {
        val appContext = context.applicationContext
        wakeGeneration.incrementAndGet()
        synchronized(wakeStateLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val scanner = btManager?.adapter?.bluetoothLeScanner
                val existing = buildScanPendingIntent(appContext, create = false)
                if (existing != null) {
                    runCatching { scanner?.stopScan(existing) }
                    runCatching { existing.cancel() }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cdm = appContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
                if (cdm != null) {
                    val macsToStop = linkedSetOf<String>()
                    PassiveEntryPrerequisiteChecker.normalizeMac(bleMac)?.let(macsToStop::add)
                    val activeVin = PassiveEntryStore.getActiveEnabledVin(appContext)
                    val profiles = VehicleProfileStore(appContext).listProfiles()
                    if (activeVin.isNotBlank()) {
                        profiles.firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
                            ?.let { PassiveEntryPrerequisiteChecker.normalizeMac(it.bleMac)?.let(macsToStop::add) }
                        PassiveEntryPrerequisiteChecker.normalizeMac(
                            PassiveEntryStore.load(appContext, activeVin).cdmAssociatedMac
                        )?.let(macsToStop::add)
                    }
                    if (bleMac.isNullOrBlank()) {
                        profiles.forEach { p ->
                            PassiveEntryPrerequisiteChecker.normalizeMac(p.bleMac)?.let(macsToStop::add)
                            PassiveEntryPrerequisiteChecker.normalizeMac(
                                PassiveEntryStore.load(appContext, p.vin).cdmAssociatedMac
                            )?.let(macsToStop::add)
                        }
                        collectCompanionAssociatedMacsApi31(cdm).forEach(macsToStop::add)
                    }
                    macsToStop.forEach { mac ->
                        runCatching {
                            @Suppress("DEPRECATION")
                            cdm.stopObservingDevicePresence(mac)
                        }
                    }
                }
            }

            PassiveEntryService.stopService(appContext)
            PassiveEntryRuntime.updateWakeChannelStatus(
                enabled = false,
                active = false,
                channelLabel = "未开启",
                logMessage = "已关闭蓝牙无感并停止后台感应"
            )
        }
    }

    private fun collectCompanionAssociatedMacsApi31(cdm: CompanionDeviceManager): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptySet()
        return runCatching {
            val result = linkedSetOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.myAssociations.forEach { assoc ->
                    PassiveEntryPrerequisiteChecker.normalizeMac(assoc.deviceMacAddress?.toString())
                        ?.let(result::add)
                }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.forEach { addr ->
                    PassiveEntryPrerequisiteChecker.normalizeMac(addr)?.let(result::add)
                }
            }
            result
        }.getOrDefault(emptySet())
    }

    /**
     * Offloads wake-channel restoration (SharedPreferences + VehicleProfileStore +
     * BluetoothManager/PowerManager/CompanionDeviceManager Binder IPCs) onto a
     * serialized background thread so Activity.onCreate()/onResume() never stall.
     * Concurrent calls (e.g. Bluetooth STATE_ON during an active restore) set
     * [restorePending] so a coalesced follow-up pass always runs.
     */
    fun restoreIfEnabled(context: Context, onCompleted: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        val shouldSubmit: Boolean
        synchronized(restoreQueueLock) {
            if (onCompleted != null) pendingRestoreCallbacks.add(onCompleted)
            if (restoreRunning) {
                restorePending = true
                shouldSubmit = false
            } else {
                restoreRunning = true
                restorePending = false
                shouldSubmit = true
            }
        }
        if (!shouldSubmit) return
        wakeExecutor.execute {
            while (true) {
                val batchCallbacks: List<() -> Unit>
                synchronized(restoreQueueLock) {
                    batchCallbacks = pendingRestoreCallbacks.toList()
                    pendingRestoreCallbacks.clear()
                    restorePending = false
                }
                try {
                    restoreIfEnabledSync(appContext)
                } catch (_: Throwable) {
                    // Keep serialized restore loop alive across unexpected platform exceptions.
                } finally {
                    batchCallbacks.forEach { cb -> runCatching { cb.invoke() } }
                }
                val runAgain = synchronized(restoreQueueLock) {
                    if (restorePending || pendingRestoreCallbacks.isNotEmpty()) {
                        true
                    } else {
                        restoreRunning = false
                        false
                    }
                }
                if (!runAgain) break
            }
        }
    }

    private fun restoreIfEnabledSync(appContext: Context) {
        val genAtStart = wakeGeneration.get()
        val activeVin = PassiveEntryStore.getActiveEnabledVin(appContext)
        if (activeVin.isBlank()) {
            synchronized(wakeStateLock) {
                if (wakeGeneration.get() != genAtStart) return
                PassiveEntryRuntime.updateWakeChannelStatus(
                    enabled = false,
                    active = false,
                    channelLabel = "未开启",
                    logMessage = null
                )
            }
            return
        }
        val config = PassiveEntryStore.load(appContext, activeVin)
        if (!config.enabled) {
            synchronized(wakeStateLock) {
                if (wakeGeneration.get() != genAtStart) return
                if (!PassiveEntryStore.load(appContext, activeVin).enabled &&
                    PassiveEntryStore.getActiveEnabledVin(appContext).equals(activeVin, ignoreCase = true)
                ) {
                    PassiveEntryStore.setActiveEnabledVin(appContext, "")
                }
            }
            return
        }
        val store = VehicleProfileStore(appContext)
        val profile = store.listProfiles().firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
            ?: store.getActiveProfile().takeIf { it.vin.equals(activeVin, ignoreCase = true) }
            ?: return
        val report = PassiveEntryPrerequisiteChecker.evaluate(appContext, profile)
        synchronized(wakeStateLock) {
            if (wakeGeneration.get() != genAtStart) return
            val latestActiveVin = PassiveEntryStore.getActiveEnabledVin(appContext)
            val latestConfig = PassiveEntryStore.load(appContext, activeVin)
            if (!latestConfig.enabled || !latestActiveVin.equals(activeVin, ignoreCase = true)) {
                return
            }
            if (!report.allRequiredSatisfied) {
                PassiveEntryRuntime.updateWakeChannelStatus(
                    enabled = true,
                    active = false,
                    channelLabel = "等待前置条件就绪",
                    logMessage = "恢复后台扫描跳过：${report.summaryText}"
                )
                return
            }
            enableForVehicleLocked(
                appContext = appContext,
                profile = profile,
                expectedGen = genAtStart,
                requirePersistedEnabled = true
            )
        }
    }
}
