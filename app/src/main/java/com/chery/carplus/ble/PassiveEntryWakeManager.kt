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

        PassiveEntryRuntime.updateWakeChannelStatus(
            enabled = ok,
            active = ok,
            channelLabel = label,
            logMessage = msg
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
            val macToStop = PassiveEntryPrerequisiteChecker.normalizeMac(bleMac)
                ?: run {
                    val activeVin = PassiveEntryStore.getActiveEnabledVin(appContext)
                    val profile = VehicleProfileStore(appContext).listProfiles()
                        .firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
                    PassiveEntryPrerequisiteChecker.normalizeMac(profile?.bleMac)
                }
            if (cdm != null && macToStop != null) {
                runCatching {
                    @Suppress("DEPRECATION")
                    cdm.stopObservingDevicePresence(macToStop)
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

    fun restoreIfEnabled(context: Context) {
        val appContext = context.applicationContext
        val activeVin = PassiveEntryStore.getActiveEnabledVin(appContext)
        if (activeVin.isBlank()) {
            PassiveEntryRuntime.updateWakeChannelStatus(
                enabled = false,
                active = false,
                channelLabel = "未开启",
                logMessage = null
            )
            return
        }
        val config = PassiveEntryStore.load(appContext, activeVin)
        if (!config.enabled) {
            PassiveEntryStore.setActiveEnabledVin(appContext, "")
            return
        }
        val store = VehicleProfileStore(appContext)
        val profile = store.listProfiles().firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
            ?: store.getActiveProfile().takeIf { it.vin.equals(activeVin, ignoreCase = true) }
            ?: return
        val report = PassiveEntryPrerequisiteChecker.evaluate(appContext, profile)
        if (!report.allRequiredSatisfied) {
            PassiveEntryRuntime.updateWakeChannelStatus(
                enabled = true,
                active = false,
                channelLabel = "等待前置条件就绪",
                logMessage = "恢复后台扫描跳过：${report.summaryText}"
            )
            return
        }
        enableForVehicle(appContext, profile)
    }
}
