package com.chery.carplus.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.chery.carplus.profile.VehicleProfileStore

/**
 * Receives hardware-filtered PendingIntent BLE scan matches from the Android Bluetooth stack
 * while the app is asleep or in the background, throttles bursts, and wakes [PassiveEntryService].
 */
class PassiveEntryScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PassiveEntryWakeManager.ACTION_PASSIVE_SCAN_RESULT) return
        val appContext = context.applicationContext
        val activeVin = PassiveEntryStore.getActiveEnabledVin(appContext)
        if (activeVin.isBlank()) return
        val cfg = PassiveEntryStore.load(appContext, activeVin)
        if (!cfg.enabled) return

        val store = VehicleProfileStore(appContext)
        val profile = store.listProfiles().firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
            ?: store.getActiveProfile().takeIf { it.vin.equals(activeVin, ignoreCase = true) }
            ?: return
        val targetMac = PassiveEntryPrerequisiteChecker.normalizeMac(profile.bleMac) ?: return
        val targetMacCompact = targetMac.replace(":", "")
        val targetBleName = profile.bleName.trim()

        val results: List<ScanResult> = extractScanResults(intent)
        if (results.isEmpty()) return

        val matched = results.firstOrNull { result ->
            val addr = PassiveEntryPrerequisiteChecker.normalizeMac(result.device?.address)
            if (addr != null && addr.equals(targetMac, ignoreCase = true)) {
                return@firstOrNull true
            }
            val scanName = safeScannedDeviceName(appContext, result)
            val nameSuffix = BlePairingPinCalculator.extractBleNameSuffix(scanName)
            if (!nameSuffix.isNullOrBlank() && nameSuffix.equals(targetMacCompact, ignoreCase = true)) {
                return@firstOrNull true
            }
            targetBleName.isNotBlank() &&
                !scanName.isNullOrBlank() &&
                targetBleName.equals(scanName, ignoreCase = true)
        } ?: return
        val liveMac = PassiveEntryPrerequisiteChecker.normalizeMac(matched.device?.address) ?: targetMac

        val now = SystemClock.elapsedRealtime()
        if (PassiveEntryRuntime.isServiceRunning()) {
            // Service already active and polling GATT RSSI; ignore redundant scan broadcasts.
            return
        }
        if (PassiveEntryRuntime.isForegroundAppActive()) {
            // Foreground MainActivity owns the BLE link; avoid duplicate GATT contention.
            PassiveEntryRuntime.appendLog("前台活跃期间收到 PendingIntent 广播 (${matched.rssi} dBm)，交由前台协同")
            return
        }
        val throttleReason = PassiveEntryRuntime.shouldThrottleScanWake(
            context = appContext,
            vin = profile.vin,
            rssi = matched.rssi,
            cfg = cfg,
            nowElapsed = now
        )
        if (throttleReason != null) {
            return
        }
        if (now - lastWakeElapsedMs < WAKE_THROTTLE_MS) return
        lastWakeElapsedMs = now

        PassiveEntryService.startForWake(
            context = appContext,
            vin = profile.vin,
            bleMac = liveMac,
            bleName = profile.bleName,
            initialRssi = matched.rssi,
            wakeSource = "PendingIntent"
        )
    }

    private fun safeScannedDeviceName(context: Context, result: ScanResult): String? {
        val fromRecord = result.scanRecord?.deviceName?.trim()?.ifBlank { null }
        if (fromRecord != null) return fromRecord
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            result.device?.name?.trim()?.ifBlank { null }
        } catch (_: SecurityException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun extractScanResults(intent: Intent): List<ScanResult> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java
            ).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<ScanResult>(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
            ).orEmpty()
        }
    }

    companion object {
        private const val WAKE_THROTTLE_MS = 12_000L
        @Volatile
        private var lastWakeElapsedMs: Long = 0L
    }
}

/**
 * Re-registers PendingIntent / CompanionDeviceManager passive entry wake channels
 * after device reboot or when the user toggles system Bluetooth back on.
 */
class PassiveEntryBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val shouldRestore = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> true
            BluetoothAdapter.ACTION_STATE_CHANGED ->
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                    BluetoothAdapter.STATE_ON
            else -> false
        }
        if (!shouldRestore) return
        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finishOnce = {
            if (finished.compareAndSet(false, true)) {
                runCatching { pending.finish() }
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(finishOnce, 6_000L)
        PassiveEntryWakeManager.restoreIfEnabled(context.applicationContext, onCompleted = finishOnce)
    }
}

/**
 * Android 12+ CompanionDeviceService receiving OS-level presence callbacks for the paired vehicle.
 * Provides stronger background wake reliability than standalone PendingIntent scans on OEM ROMs.
 */
@RequiresApi(Build.VERSION_CODES.S)
class PassiveEntryCompanionService : CompanionDeviceService() {

    @Deprecated("Deprecated in Android 13")
    override fun onDeviceAppeared(address: String) {
        handleAppeared(address)
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            associationInfo.deviceMacAddress?.toString()
        } else {
            null
        }
        if (!addr.isNullOrBlank()) handleAppeared(addr)
    }

    @Deprecated("Deprecated in Android 13")
    override fun onDeviceDisappeared(address: String) {
        handleDisappeared(address)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            associationInfo.deviceMacAddress?.toString()
        } else {
            null
        }
        if (!addr.isNullOrBlank()) handleDisappeared(addr)
    }

    private fun handleAppeared(rawAddress: String) {
        val appContext = applicationContext
        val targetMac = PassiveEntryPrerequisiteChecker.normalizeMac(rawAddress) ?: return
        val activeVin = PassiveEntryStore.getActiveEnabledVin(appContext)
        if (activeVin.isBlank()) return
        val cfg = PassiveEntryStore.load(appContext, activeVin)
        if (!cfg.enabled) return

        val store = VehicleProfileStore(appContext)
        val profile = store.listProfiles().firstOrNull { it.vin.equals(activeVin, ignoreCase = true) }
            ?: store.getActiveProfile().takeIf { it.vin.equals(activeVin, ignoreCase = true) }
            ?: return
        val expectedMac = PassiveEntryPrerequisiteChecker.normalizeMac(profile.bleMac) ?: return
        if (!expectedMac.equals(targetMac, ignoreCase = true)) return

        if (PassiveEntryRuntime.isServiceRunning()) {
            return
        }
        if (PassiveEntryRuntime.isForegroundAppActive()) {
            PassiveEntryRuntime.appendLog("伴随设备检测到本车出现 ($targetMac)，前台活跃中跳过后台抢连")
            return
        }
        PassiveEntryService.startForWake(
            context = appContext,
            vin = profile.vin,
            bleMac = targetMac,
            bleName = profile.bleName,
            initialRssi = null,
            wakeSource = "CompanionDevice"
        )
    }

    private fun handleDisappeared(rawAddress: String) {
        val mac = PassiveEntryPrerequisiteChecker.normalizeMac(rawAddress) ?: return
        PassiveEntryRuntime.markCompanionDisappeared(applicationContext)
        PassiveEntryRuntime.appendLog("伴随设备报告本车远离 ($mac)")
        PassiveEntryService.notifyCompanionDisappeared(applicationContext, mac)
    }
}
