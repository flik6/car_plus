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
        if (!isCompanionSupported(context)) return false
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
            cdmAssociated && pendingIntentSupported -> "伴随设备 + PendingIntent 双通道"
            cdmAssociated -> "系统伴随设备唤醒"
            pendingIntentSupported -> "PendingIntent 后台蓝牙扫描"
            else -> "前台近场蓝牙监听"
        }

        val items = mutableListOf(
            PrerequisiteCheckItem(
                id = PrerequisiteId.KEY_MATERIAL,
                title = "已同步本车蓝牙数字钥匙",
                detail = if (keyOk) {
                    "钥匙材料完整（${targetMac ?: "--"}）"
                } else {
                    "需先从云端同步本车蓝牙钥匙与车辆蓝牙地址"
                },
                satisfied = keyOk,
                required = true,
                actionLabel = "同步钥匙"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.BLUETOOTH_ON,
                title = "系统蓝牙已开启",
                detail = if (btOn) "蓝牙适配器已就绪" else "需保持系统蓝牙处于开启状态",
                satisfied = btOn,
                required = true,
                actionLabel = "开启蓝牙"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.BLE_PERMISSIONS,
                title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "附近设备蓝牙权限" else "蓝牙与后台定位权限",
                detail = if (permsOk) {
                    "已授予后台扫描与直连所需权限"
                } else {
                    "需授予扫描、连接与后台蓝牙感知权限"
                },
                satisfied = permsOk,
                required = true,
                actionLabel = "去授权"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.DEVICE_BONDED,
                title = "已完成本车系统蓝牙配对",
                detail = when {
                    !keyOk -> "请先完成数字钥匙同步"
                    !permsOk -> "请先授予附近设备权限以校验配对状态"
                    bondedOk -> "本车已在系统蓝牙配对列表中，可静默回连"
                    else -> "首次需在车旁手动输入 6 位配对码完成一次系统配对"
                },
                satisfied = bondedOk,
                required = true,
                actionLabel = "去配对"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.NOTIFICATIONS,
                title = "通知与无感状态提示权限",
                detail = if (notifOk) {
                    "已允许前台连接服务通知与解/上锁反馈"
                } else {
                    "Android 13+ 需允许通知以维持靠近建连服务"
                },
                satisfied = notifOk,
                required = true,
                actionLabel = "允许通知"
            ),
            PrerequisiteCheckItem(
                id = PrerequisiteId.BATTERY_UNRESTRICTED,
                title = "电池优化设为无限制",
                detail = if (batteryOk) {
                    "已加入电池白名单，降低息屏休眠断链概率"
                } else {
                    "需关闭省电限制，否则锁屏后后台蓝牙唤醒会被拦截"
                },
                satisfied = batteryOk,
                required = true,
                actionLabel = "设为无限制"
            )
        )

        if (cdmSupported) {
            items.add(
                PrerequisiteCheckItem(
                    id = PrerequisiteId.BACKGROUND_WAKE_CHANNEL,
                    title = "系统伴随设备关联（推荐）",
                    detail = if (cdmAssociated) {
                        "已关联本车，系统级免杀唤醒与 PendingIntent 协同生效"
                    } else {
                        "当前已支持 PendingIntent 扫描；关联伴随设备可大幅提升抗杀能力"
                    },
                    satisfied = cdmAssociated,
                    required = false,
                    actionLabel = "去关联"
                )
            )
        }

        val missingRequired = items.count { it.required && !it.satisfied }
        val summary = if (missingRequired == 0) {
            "前置条件已全部满足 · $wakeChannelMode"
        } else {
            "还需完成 $missingRequired 项必备条件后方可开启"
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
            onError("车辆蓝牙 MAC 不可用")
            return
        }
        if (!isCompanionSupported(activity)) {
            onError("当前系统不支持伴随设备关联，将使用 PendingIntent 后台扫描")
            return
        }
        if (isCompanionAssociated(activity, normalizedMac)) {
            onAlreadyAssociated()
            return
        }
        val cdm = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        if (cdm == null) {
            onError("伴随设备服务不可用")
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
                    val msg = error?.toString()?.ifBlank { "伴随设备关联失败" } ?: "伴随设备关联失败"
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
            onError(t.message ?: "无法发起伴随设备关联")
        }
    }
}
