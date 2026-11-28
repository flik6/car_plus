package com.chery.carplus.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.chery.carplus.profile.VehicleProfile
import java.util.Locale

enum class PrerequisiteId {
    KEY_MATERIAL,
    BLUETOOTH_ON,
    BLE_PERMISSIONS,
    DEVICE_BONDED,
    NOTIFICATIONS,
    BATTERY_UNRESTRICTED,
    BACKGROUND_WAKE_CHANNEL
}

data class PrerequisiteCheckItem(
    val id: PrerequisiteId,
    val title: String,
    val detail: String,
    val satisfied: Boolean,
    val required: Boolean,
    val actionLabel: String
)

data class PrerequisiteReport(
    val items: List<PrerequisiteCheckItem>,
    val allRequiredSatisfied: Boolean,
    val missingRequiredCount: Int,
    val summaryText: String,
    val wakeChannelMode: String
)

object PassiveEntryPrerequisiteChecker {

    fun normalizeMac(raw: String?): String? {
        val compact = raw.orEmpty().trim().replace(":", "").replace("-", "").uppercase(Locale.US)
        if (compact.length != 12 || !compact.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return compact.chunked(2).joinToString(":")
    }

    fun hasCompleteKeyMaterial(profile: VehicleProfile): Boolean {
        if (profile.vin.isBlank()) return false
        if (normalizeMac(profile.bleMac) == null) return false
        if (profile.userId.isBlank() || profile.deviceId.isBlank()) return false
        val rootOk = runCatching { profile.rootKeyBytes().size == 16 }.getOrDefault(false)
        val infoOk = runCatching { profile.infoBytes().size == 147 }.getOrDefault(false)
        val edfOk = runCatching { profile.edfBytes().size in 68..74 }.getOrDefault(false)
        return rootOk && infoOk && edfOk
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    fun missingBlePermissions(context: Context): List<String> {
        val required = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            else -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun isDeviceBonded(context: Context, bleMac: String): Boolean {
        val targetMac = normalizeMac(bleMac) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return false
        val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return false
        return bonded.any { device ->
            normalizeMac(device.address)?.equals(targetMac, ignoreCase = true) == true &&
                device.bondState == BluetoothDevice.BOND_BONDED
        }
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    fun isCompanionSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val pm = context.applicationContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) return false
        val cdm = context.applicationContext.getSystemService(Context.COMPANION_DEVICE_SERVICE)
        return cdm is CompanionDeviceManager
    }

    fun isCompanionAssociated(context: Context, bleMac: String): Boolean {
        val targetMac = normalizeMac(bleMac) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!isCompanionSupported(context)) return false
        return isCompanionAssociatedApi31(context, targetMac)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun isCompanionAssociatedApi31(context: Context, targetMac: String): Boolean {
        val cdm = context.applicationContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
            ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.myAssociations.any { assoc ->
                    normalizeMac(assoc.deviceMacAddress?.toString())?.equals(targetMac, ignoreCase = true) == true
                }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.any { addr ->
                    normalizeMac(addr)?.equals(targetMac, ignoreCase = true) == true
                }
            }
        }.getOrDefault(false)
    }

    fun evaluate(context: Context, profile: VehicleProfile): PrerequisiteReport {
        val keyOk = hasCompleteKeyMaterial(profile)
        val targetMac = normalizeMac(profile.bleMac)
        val btOn = isBluetoothEnabled(context)
        val missingPerms = missingBlePermissions(context)
        val permsOk = missingPerms.isEmpty()
        val bondedOk = keyOk && permsOk && targetMac != null && isDeviceBonded(context, targetMac)
        val notifOk = isNotificationPermissionGranted(context)
        val batteryOk = isBatteryOptimizationIgnored(context)
        val cdmSupported = isCompanionSupported(context)
        val cdmAssociated = targetMac != null && isCompanionAssociated(context, targetMac)
        val pendingIntentSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        val wakeChannelMode = when {
            cdmAssociated && pendingIntentSupported -> "系统级双通道唤醒"
            cdmAssociated -> "系统级后台唤醒"
            pendingIntentSupported -> "后台省电监听"
            else -> "前台近场监听"
        }

        val items = mutableListOf(
            PrerequisiteCheckItem(
                id = PrerequisiteId.KEY_MATERIAL,
                title = "数字钥匙已同步",
                detail = if (keyOk) {
                    "已同步本车蓝牙数字钥匙"
                } else {
                    "请先从云端同步本车数字钥匙"
                },
                satisfied = keyOk,
                required = true,
                actionLabel = "同步钥匙"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.BLUETOOTH_ON,
                title = "系统蓝牙已开启",
                detail = if (btOn) "蓝牙功能已就绪" else "请保持手机蓝牙处于开启状态",
                satisfied = btOn,
                required = true,
                actionLabel = "开启蓝牙"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.BLE_PERMISSIONS,
                title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "附近设备权限" else "蓝牙与定位权限",
                detail = if (permsOk) {
                    "已授予蓝牙感应与连接权限"
                } else {
                    "需授予扫描与连接权限以感应车辆"
                },
                satisfied = permsOk,
                required = true,
                actionLabel = "去授权"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.DEVICE_BONDED,
                title = "本车蓝牙已配对",
                detail = when {
                    !keyOk -> "请先完成数字钥匙同步"
                    !permsOk -> "请先授予附近设备权限"
                    bondedOk -> "已完成系统蓝牙配对，可自动回连"
                    else -> "首次需在车旁输入 6 位配对码完成配对"
                },
                satisfied = bondedOk,
                required = true,
                actionLabel = "去配对"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.NOTIFICATIONS,
                title = "通知提醒权限",
                detail = if (notifOk) {
                    "已允许无感连接与解上锁状态提示"
                } else {
                    "需开启通知以保持后台连接稳定"
                },
                satisfied = notifOk,
                required = true,
                actionLabel = "允许通知"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.BATTERY_UNRESTRICTED,
                title = "电池优化无限制",
                detail = if (batteryOk) {
                    "已关闭省电限制，锁屏也可稳定唤醒"
                } else {
                    "避免锁屏后后台感应服务被系统暂停"
                },
                satisfied = batteryOk,
                required = true,
                actionLabel = "去设置"
            )
        )

        if (cdmSupported) {
            items.add(
                PrerequisiteCheckItem(
                    id = PrerequisiteId.BACKGROUND_WAKE_CHANNEL,
                    title = "系统设备关联",
                    detail = if (cdmAssociated) {
                        "已关联本车，系统级后台保活已生效"
                    } else {
                        "关联后可大幅提升息屏唤醒稳定性"
                    },
                    satisfied = cdmAssociated,
                    required = false,
                    actionLabel = "去关联"
                )
            )
        }

        val missingRequired = items.count { it.required && !it.satisfied }
        val summary = if (missingRequired == 0) {
            "全部必备条件已满足 · $wakeChannelMode"
        } else {
            "还需完成 $missingRequired 项设置"
        }

        return PrerequisiteReport(
            items = items,
            allRequiredSatisfied = missingRequired == 0,
            missingRequiredCount = missingRequired,
            summaryText = summary,
            wakeChannelMode = wakeChannelMode
        )
    }

    @SuppressLint("BatteryLife")
    fun buildIgnoreBatteryOptimizationIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun buildAppNotificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun requestCompanionAssociation(
        activity: Activity,
        bleMac: String,
        bleName: String,
        onIntentSenderReady: (IntentSender) -> Unit,
        onAlreadyAssociated: () -> Unit,
        onError: (String) -> Unit
    ) {
        val normalizedMac = normalizeMac(bleMac)
        if (normalizedMac == null) {
            onError("尚未同步车辆蓝牙信息")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isCompanionSupported(activity)) {
            onError("当前系统不支持设备关联，将使用后台省电监听")
            return
        }
        if (isCompanionAssociated(activity, normalizedMac)) {
            onAlreadyAssociated()
            return
        }
        requestCompanionAssociationApi31(
            activity = activity,
            normalizedMac = normalizedMac,
            onIntentSenderReady = onIntentSenderReady,
            onAlreadyAssociated = onAlreadyAssociated,
            onError = onError
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun requestCompanionAssociationApi31(
        activity: Activity,
        normalizedMac: String,
        onIntentSenderReady: (IntentSender) -> Unit,
        onAlreadyAssociated: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cdm = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        if (cdm == null) {
            onError("系统设备关联服务不可用")
            return
        }
        try {
            val scanFilter = ScanFilter.Builder()
                .setDeviceAddress(normalizedMac)
                .build()
            val leFilter = BluetoothLeDeviceFilter.Builder()
                .setScanFilter(scanFilter)
                .build()
            val requestBuilder = AssociationRequest.Builder()
                .addDeviceFilter(leFilter)
                .setSingleDevice(true)
            val request = requestBuilder.build()

            val callback = object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Android 13")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    activity.runOnUiThread { onIntentSenderReady(chooserLauncher) }
                }

                override fun onAssociationPending(intentSender: IntentSender) {
                    activity.runOnUiThread { onIntentSenderReady(intentSender) }
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    activity.runOnUiThread { onAlreadyAssociated() }
                }

                override fun onFailure(error: CharSequence?) {
                    val msg = error?.toString()?.ifBlank { "设备关联失败" } ?: "设备关联失败"
                    activity.runOnUiThread { onError(msg) }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.associate(request, activity.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                cdm.associate(request, callback, null)
            }
        } catch (t: Throwable) {
            onError(t.message ?: "无法发起系统设备关联")
        }
    }
}
