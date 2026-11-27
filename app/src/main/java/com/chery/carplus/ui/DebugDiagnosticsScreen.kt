package com.chery.carplus.ui

import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.chery.carplus.ble.CheryBleController
import com.chery.carplus.cloud.AppUpdater
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.widget.CarWidgetBarProvider
import com.chery.carplus.widget.CarWidgetCompactProvider
import com.chery.carplus.widget.CarWidgetLargeProvider
import com.chery.carplus.widget.CarWidgetManager
import com.chery.carplus.widget.CarWidgetMediumProvider
import com.chery.carplus.widget.CarWidgetStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hidden developer diagnostics screen unlocked by 5 rapid taps on the version
 * number in "关于 CarPlus". Provides live visibility into BLE matching & nearby
 * Chery vehicles, vehicle telemetry, 3D model CDN cache, widgets, and BLE logs.
 */
class DebugDiagnosticsScreen(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val bleController: CheryBleController,
    private val profileProvider: () -> VehicleProfile,
    private val modelNameProvider: () -> String,
    private val plateNoProvider: () -> String,
    private val telemetryProvider: () -> VehicleTelemetry?,
    private val lastPairingPinProvider: () -> String,
    private val requirePinEveryTimeProvider: () -> Boolean,
    private val antiTheftEnabledProvider: () -> Boolean?,
    private val onRequestBlePermissions: () -> Boolean,
    private val onPreviewPairingDrawer: (pin: String, deviceName: String, address: String) -> Unit,
    private val onBack: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var contentBody: LinearLayout? = null
    private var liveBadgeView: TextView? = null
    private val refreshTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            renderSections()
            mainHandler.postDelayed(this, 1_500L)
        }
    }

    fun buildView(): Pair<View, () -> Unit> {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
        }

        val copyReportBtn = text("复制报告", 13.5f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD).apply {
            setPadding(dp(10), dp(6), dp(4), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                copySanitizedReport()
            }
        }
        MotionSpec.attachPressPhysics(copyReportBtn, pressedScale = 0.94f, haptic = false)

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "调试诊断",
                onBack = onBack,
                rightView = copyReportBtn
            )
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(scroll)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(40))
        }
        contentBody = body
        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        renderSections()
        mainHandler.postDelayed(refreshTick, 1_500L)

        val onDestroy = {
            destroyed = true
            mainHandler.removeCallbacks(refreshTick)
            contentBody = null
            liveBadgeView = null
        }
        return root to onDestroy
    }

    private fun renderSections() {
        val body = contentBody ?: return
        body.removeAllViews()

        val profile = profileProvider()
        val ble = bleController.getDebugSnapshot(profile)
        val telemetry = telemetryProvider()
        val modelName = modelNameProvider().ifBlank { "未知车型" }
        val plateNo = plateNoProvider().ifBlank { "--" }
        val resolvedModelKey = VehicleModelRepository.resolveModelKey(modelName)
        val exactModelMatch = VehicleModelRepository.isExactModelMatch(modelName)
        val cacheInfos = VehicleModelRepository.getDebugCacheStatus(context)
        val widgetSnap = CarWidgetStateStore.load(context)
        val (verName, verCode) = AppUpdater.getCurrentVersion(context)

        // 1. Quick Action Toolbar
        body.addView(AppComponents.createSectionHeader(context, "快捷调试操作"))
        val actionWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
        }
        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(
            actionChip(
                label = if (ble.scanning) "正在扫描周边蓝牙..." else "扫描周边奇瑞蓝牙 (10s)",
                accent = true
            ) {
                if (onRequestBlePermissions()) {
                    bleController.startDiagnosticScan(10_000L)
                    renderSections()
                } else {
                    toast("请先授予蓝牙扫描权限")
                }
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        )
        row1.addView(
            actionChip("预览顶部配对弹窗", accent = false) {
                val samplePin = lastPairingPinProvider().ifBlank {
                    ble.nearbyCheryVehicles.firstOrNull { it.calculatedPin.length == 6 }?.calculatedPin
                        ?: "482915"
                }
                val devName = ble.targetBleName.ifBlank { modelName }
                val devMac = ble.targetBleMac.ifBlank { "AA:BB:CC:DD:EE:FF" }
                onPreviewPairingDrawer(samplePin, devName, devMac)
            },
            LinearLayout.LayoutParams(0, dp(40), 1f)
        )
        actionWrap.addView(row1, lp())

        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(
            actionChip("刷新全部小组件", accent = false) {
                CarWidgetManager.updateAllWidgets(context)
                CarWidgetManager.triggerBackgroundSyncIfStale(context, minStaleMs = 0L)
                toast("已触发桌面小组件同步刷新")
                renderSections()
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        )
        row2.addView(
            actionChip("检查 3D 模型更新", accent = false) {
                toast("正在通过 jsDelivr 检查 manifest.json...")
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        VehicleModelRepository.checkAndUpdateInBackground(context, modelName, force = true)
                    }
                    val msg = when {
                        res.updated -> "发现并已更新 ${res.key} 3D 模型"
                        res.checked -> "当前 ${res.key} 模型已是最新"
                        else -> "本地尚未缓存或网络不可用"
                    }
                    toast(msg)
                    if (!destroyed) renderSections()
                }
            },
            LinearLayout.LayoutParams(0, dp(40), 1f)
        )
        actionWrap.addView(row2, lp(top = dp(8)))
        body.addView(actionWrap, lp())
        body.addView(AppComponents.createHairline(context, startInsetDp = 20))

        // 2. BLE Strict Matching & Session Status
        body.addView(AppComponents.createSectionHeader(context, "蓝牙精准匹配与会话状态"))
        body.addView(
            infoCard(
                listOf(
                    "蓝牙开关 / 扫描" to "${if (ble.bluetoothEnabled) "已开启" else "未开启"} · ${if (ble.scanning) "扫描中" else "空闲"} (共发现 ${ble.totalScannedDevices} 个设备)",
                    "当前链路阶段" to "${ble.stage}${if (ble.isSessionReady) " · 会话就绪" else ""}",
                    "防误连匹配策略" to "严格校验云端 bleMac / bleName（屏蔽其他奇瑞车辆）",
                    "本车云端 BLE MAC" to ble.targetBleMac.ifBlank { "未下发 (需同步钥匙材料)" },
                    "本车云端 BLE 名称" to ble.targetBleName.ifBlank { "--" },
                    "当前连接设备" to if (ble.connectedAddress.isNotBlank()) {
                        "${ble.connectedName.ifBlank { "--" }} [${ble.connectedAddress}]${ble.currentRssi?.let { " · ${it}dBm" }.orEmpty()}"
                    } else "未连接",
                    "GATT 握手进度" to "SVC:${yn(ble.servicesReady)} NTF:${yn(ble.notificationsReady)} FFE1:${yn(ble.ffe1Ready)}(${ble.ffe1Hex.ifBlank { "--" }}) 0x02:${yn(ble.auth02Done)} 0x03:${yn(ble.session03Done)} 0x80:${yn(ble.account80Done)}",
                    "MTU 分片 / 配对中" to "${ble.mtuPayloadChunkSize}B · waitingForBond=${ble.waitingForBond}",
                    "钥匙材料完整度" to "root=${profile.rootKeyHex.length / 2}B info=${profile.infoHex.length / 2}B edf=${profile.edfHex.length / 2}B uid=${maskMiddle(profile.userId)}",
                    "最近动态配对码" to lastPairingPinProvider().ifBlank { "--" }
                )
            ),
            lp(top = dp(4), bottom = dp(10))
        )
        body.addView(AppComponents.createHairline(context, startInsetDp = 20))

        // 3. Nearby Chery Vehicles Radar
        body.addView(
            AppComponents.createSectionHeader(
                context,
                "周围奇瑞蓝牙雷达 (${ble.nearbyCheryVehicles.size} 台)"
            )
        )
        if (ble.nearbyCheryVehicles.isEmpty()) {
            val emptyBox = text(
                "暂未扫描到周边奇瑞 / InGeek 车辆蓝牙广播，点击上方「扫描周边奇瑞蓝牙」可立即主动探测。",
                12.5f,
                ThemeTokens.TEXT_SUB
            ).apply {
                setPadding(dp(20), dp(6), dp(20), dp(14))
                setLineSpacing(dpF(3f), 1f)
            }
            body.addView(emptyBox, lp())
        } else {
            val nowMs = System.currentTimeMillis()
            ble.nearbyCheryVehicles.forEach { dev ->
                val ageSec = ((nowMs - dev.lastSeenMs) / 1000L).coerceAtLeast(0L)
                val bondLabel = when (dev.bondState) {
                    BluetoothDevice.BOND_BONDED -> "系统已配对"
                    BluetoothDevice.BOND_BONDING -> "正在配对"
                    else -> "未配对"
                }
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBackground(
                        strokeColor = if (dev.isMatchedTarget) Color.parseColor("#3D9BFF") else Color.parseColor("#2A2D35")
                    )
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                }
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topRow.addView(
                    text(dev.name, 14f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                )
                val badge = text(
                    if (dev.isMatchedTarget) "本车精准匹配" else "其他奇瑞 · 已忽略",
                    10.5f,
                    if (dev.isMatchedTarget) Color.parseColor("#60A5FA") else Color.parseColor("#F59E0B"),
                    Typeface.BOLD
                ).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dpF(6f)
                        setColor(if (dev.isMatchedTarget) Color.parseColor("#1D334D") else Color.parseColor("#332818"))
                    }
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                }
                topRow.addView(badge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                card.addView(topRow, lp())

                card.addView(
                    text(
                        "MAC: ${dev.address}  ·  ${dev.rssi} dBm  ·  $bondLabel  ·  ${ageSec}s 前",
                        11.5f,
                        Color.parseColor("#C5CAD4")
                    ).apply { typeface = Typeface.MONOSPACE },
                    lp(top = dp(5))
                )
                card.addView(
                    text(
                        "判定：${dev.matchReason}",
                        11.5f,
                        ThemeTokens.TEXT_SUB
                    ),
                    lp(top = dp(3))
                )
                if (dev.advRandomHex.isNotBlank() || dev.calculatedPin.isNotBlank()) {
                    card.addView(
                        text(
                            "AdvRand: ${dev.advRandomHex.ifBlank { "--" }}  ·  推算PIN: ${dev.calculatedPin.ifBlank { "--" }}",
                            11f,
                            Color.parseColor("#8A909C")
                        ).apply { typeface = Typeface.MONOSPACE },
                        lp(top = dp(2))
                    )
                }
                if (dev.serviceUuids.isNotEmpty()) {
                    val shortUuids = dev.serviceUuids.joinToString(", ") {
                        if (it.length >= 8) it.substring(4, 8).uppercase(Locale.US) else it
                    }
                    card.addView(
                        text("Service UUIDs: $shortUuids", 10.5f, Color.parseColor("#757B86")).apply {
                            typeface = Typeface.MONOSPACE
                        },
                        lp(top = dp(2))
                    )
                }

                val holder = FrameLayout(context).apply {
                    setPadding(dp(20), dp(4), dp(20), dp(6))
                    addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                body.addView(holder, lp())
            }
        }
        body.addView(AppComponents.createHairline(context, startInsetDp = 20))

        // 4. Vehicle & Telemetry Raw Snapshot
        body.addView(AppComponents.createSectionHeader(context, "车辆身份与实时遥测快照"))
        val tpmsSummary = telemetry?.let { t ->
            listOf(
                "FL" to t.lFrontTyre,
                "FR" to t.rFrontTyre,
                "RL" to t.lRearTyre,
                "RR" to t.rRearTyre
            ).joinToString(" ") { (k, v) -> "$k:${v.ifBlank { "--" }}" }
        } ?: "--"
        val locSummary = telemetry?.let { t ->
            if (t.latitude != null && t.longitude != null) {
                String.format(Locale.US, "%.6f, %.6f", t.latitude, t.longitude)
            } else "--"
        } ?: "--"
        body.addView(
            infoCard(
                listOf(
                    "车型 / 车牌" to "$modelName · $plateNo",
                    "VIN" to maskMiddle(profile.vin, head = 6, tail = 4),
                    "PIN 码门禁" to "${if (requirePinEveryTimeProvider()) "开启 (BLE+HTTP 每次验证)" else "关闭 (免密控车)"} · 本机缓存:${if (profile.controlPin.trim().length == 6) "已缓存(6位)" else "未缓存"}",
                    "防盗提醒开关" to when (antiTheftEnabledProvider()) {
                        true -> "已开启"
                        false -> "已关闭"
                        null -> "未拉取"
                    },
                    "油量 / 续航 / 总里程" to "${telemetry?.fuelPercent?.let { "${it.toInt()}%" } ?: "--"} · ${telemetry?.remainingRangeKm?.let { "${it.toInt()}km" } ?: "--"} · ${telemetry?.odometerKm?.let { "${it.toInt()}km" } ?: "--"}",
                    "电瓶 / 在线 / 遥测时间" to "${telemetry?.batteryVoltage?.let { String.format(Locale.US, "%.1fV", it) } ?: "--"} · online=${telemetry?.online?.ifBlank { "--" } ?: "--"} · ${telemetry?.time?.ifBlank { "--" } ?: "--"}",
                    "核心状态" to if (telemetry != null) {
                        "锁:${telemetry.doorLocked} 引擎:${telemetry.engineRunning} 空调:${telemetry.acOn} 尾箱:${telemetry.trunkOpen} 天窗:${telemetry.skylightState.ifBlank { "--" }}"
                    } else "尚未拉取车况",
                    "四轮胎压原始值" to tpmsSummary,
                    "车辆坐标 (WGS-84)" to locSummary
                )
            ),
            lp(top = dp(4), bottom = dp(10))
        )
        body.addView(AppComponents.createHairline(context, startInsetDp = 20))

        // 5. 3D Model CDN & Local Cache Status
        body.addView(AppComponents.createSectionHeader(context, "3D 车模 CDN 与本地缓存"))
        val modelRows = mutableListOf(
            "当前映射模型" to "$resolvedModelKey (${VehicleModelRepository.displayNameForKey(resolvedModelKey)} · ${if (exactModelMatch) "精准匹配" else "按形态回退"})",
            "CDN 源站" to "jsDelivr (flik6/car_plus@main/models)"
        )
        cacheInfos.forEach { ci ->
            val sizeStr = if (ci.cached) String.format(Locale.US, "%.2f MB", ci.sizeBytes / 1024.0 / 1024.0) else "未缓存"
            val checkStr = if (ci.lastCheckMs > 0L) formatTime(ci.lastCheckMs) else "未检查"
            modelRows += "${ci.displayName} (${ci.key})" to "$sizeStr · v${ci.version} · sha:${ci.sha256Short.ifBlank { "--" }} · 校验:$checkStr"
        }
        body.addView(infoCard(modelRows), lp(top = dp(4), bottom = dp(6)))
        val clearCacheWrap = FrameLayout(context).apply {
            setPadding(dp(20), dp(2), dp(20), dp(12))
            addView(
                actionChip("清理本地 3D 模型缓存 (下次重新从 jsDelivr 拉取)", accent = false) {
                    val count = VehicleModelRepository.clearAllModelCaches(context)
                    toast("已清理 $count 个本地模型缓存文件")
                    renderSections()
                },
                FrameLayout.LayoutParams(MATCH_PARENT, dp(38))
            )
        }
        body.addView(clearCacheWrap, lp())
        body.addView(AppComponents.createHairline(context, startInsetDp = 20))

        // 6. Widgets & System Environment
        body.addView(AppComponents.createSectionHeader(context, "桌面小组件与系统环境"))
        val mgr = AppWidgetManager.getInstance(context)
        val cBar = mgr?.getAppWidgetIds(ComponentName(context, CarWidgetBarProvider::class.java))?.size ?: 0
        val cCompact = mgr?.getAppWidgetIds(ComponentName(context, CarWidgetCompactProvider::class.java))?.size ?: 0
        val cMedium = mgr?.getAppWidgetIds(ComponentName(context, CarWidgetMediumProvider::class.java))?.size ?: 0
        val cLarge = mgr?.getAppWidgetIds(ComponentName(context, CarWidgetLargeProvider::class.java))?.size ?: 0
        val dm = context.resources.displayMetrics
        body.addView(
            infoCard(
                listOf(
                    "已添加桌面小组件" to "4×1:$cBar · 2×2:$cCompact · 4×2:$cMedium · 4×3:$cLarge",
                    "小组件最近同步" to if (widgetSnap.updatedAtMs > 0L) "${formatTime(widgetSnap.updatedAtMs)} (${widgetSnap.primaryStatus} · ${widgetSnap.fuelPercent}%)" else "尚未同步",
                    "应用版本" to "CarPlus v$verName (Build $verCode)",
                    "设备与系统" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "屏幕参数" to "${dm.widthPixels}×${dm.heightPixels} · density=${String.format(Locale.US, "%.2f", dm.density)}",
                    "云端令牌状态" to "accessToken:${if (profile.accessToken.isNotBlank()) "有效(${profile.accessToken.length}B)" else "空"} · sdkToken:${if (profile.sdkToken.isNotBlank()) "有效" else "空"}"
                )
            ),
            lp(top = dp(4), bottom = dp(10))
        )
        body.addView(AppComponents.createHairline(context, startInsetDp = 20))

        // 7. Live BLE & System Diagnostic Logs
        val logHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        logHeaderRow.addView(
            text("蓝牙诊断日志 (${ble.recentLogs.size})", 12.5f, ThemeTokens.TEXT_SUB),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        logHeaderRow.addView(
            text("清空日志", 12f, ThemeTokens.ACCENT_LIGHT).apply {
                setPadding(dp(8), dp(4), dp(2), dp(4))
                setOnClickListener {
                    bleController.clearDebugLogs()
                    renderSections()
                }
            }
        )
        body.addView(logHeaderRow, lp())

        val logCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        if (ble.recentLogs.isEmpty()) {
            logCard.addView(text("暂无蓝牙事件日志", 11.5f, ThemeTokens.TEXT_SUB))
        } else {
            ble.recentLogs.take(50).forEach { line ->
                val lineColor = when {
                    line.contains("ERR:") -> Color.parseColor("#F87171")
                    line.contains("精准命中") || line.contains("成功") -> Color.parseColor("#4ADE80")
                    line.contains("配对") -> Color.parseColor("#60A5FA")
                    else -> Color.parseColor("#B8BEC8")
                }
                logCard.addView(
                    text(line, 10.8f, lineColor).apply {
                        typeface = Typeface.MONOSPACE
                        setLineSpacing(dpF(2f), 1f)
                    },
                    lp(bottom = dp(3))
                )
            }
        }
        val logWrap = FrameLayout(context).apply {
            setPadding(dp(20), dp(2), dp(20), dp(12))
            addView(logCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        body.addView(logWrap, lp())
    }

    private fun copySanitizedReport() {
        val profile = profileProvider()
        val ble = bleController.getDebugSnapshot(profile)
        val (verName, verCode) = AppUpdater.getCurrentVersion(context)
        val report = buildString {
            appendLine("=== CarPlus Debug Report (Sanitized) ===")
            appendLine("App: v$verName (Build $verCode)")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Vehicle: ${modelNameProvider()} / VIN=${maskMiddle(profile.vin, 6, 4)}")
            appendLine("Target BLE: mac=${ble.targetBleMac.ifBlank { "--" }} name=${ble.targetBleName.ifBlank { "--" }}")
            appendLine("BLE Stage: ${ble.stage} sessionReady=${ble.isSessionReady} connected=${ble.connectedAddress} rssi=${ble.currentRssi}")
            appendLine("Nearby Chery BLE (${ble.nearbyCheryVehicles.size}):")
            ble.nearbyCheryVehicles.forEach { d ->
                appendLine("  - ${d.name} [${d.address}] ${d.rssi}dBm matched=${d.isMatchedTarget} (${d.matchReason}) bond=${d.bondState}")
            }
            appendLine("Model Cache:")
            VehicleModelRepository.getDebugCacheStatus(context).forEach { c ->
                appendLine("  - ${c.key}: cached=${c.cached} bytes=${c.sizeBytes} sha=${c.sha256Short}")
            }
            appendLine("Recent BLE Logs (${ble.recentLogs.size}):")
            ble.recentLogs.take(30).forEach { appendLine("  $it") }
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("CarPlus Debug Report", report))
        toast("已复制脱敏诊断报告")
    }

    private fun infoCard(rows: List<Pair<String, String>>): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        rows.forEachIndexed { idx, (k, v) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(
                text(k, 11.8f, ThemeTokens.TEXT_SUB),
                LinearLayout.LayoutParams(dp(112), WRAP_CONTENT)
            )
            row.addView(
                text(v, 11.8f, ThemeTokens.TEXT_PRIMARY).apply {
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(dpF(2f), 1f)
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            card.addView(row, lp())
            if (idx < rows.lastIndex) {
                card.addView(
                    View(context).apply { setBackgroundColor(Color.parseColor("#24272F")) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
                        topMargin = dp(2)
                        bottomMargin = dp(2)
                    }
                )
            }
        }
        return FrameLayout(context).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun actionChip(label: String, accent: Boolean, onClick: () -> Unit): TextView {
        val tv = text(
            label,
            12.2f,
            if (accent) ThemeTokens.TEXT_PRIMARY else Color.parseColor("#D0D5DE"),
            Typeface.BOLD
        ).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpF(10f)
                setColor(if (accent) ThemeTokens.ACCENT_BLUE else Color.parseColor("#22252C"))
                if (!accent) setStroke(dp(1), Color.parseColor("#323642"))
            }
            setPadding(dp(10), 0, dp(10), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                onClick()
            }
        }
        MotionSpec.attachPressPhysics(tv, pressedScale = 0.96f, haptic = false)
        return tv
    }

    private fun cardBackground(strokeColor: Int = Color.parseColor("#272B33")): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dpF(12f)
            setColor(Color.parseColor("#1B1D22"))
            setStroke(dp(1), strokeColor)
        }

    private fun yn(flag: Boolean): String = if (flag) "OK" else "--"

    private fun maskMiddle(raw: String, head: Int = 4, tail: Int = 4): String {
        val s = raw.trim()
        if (s.isBlank()) return "--"
        if (s.length <= head + tail) return s
        return "${s.take(head)}****${s.takeLast(tail)}"
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.create("sans-serif", style)
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    private fun dpF(value: Float): Float = value * context.resources.displayMetrics.density

    private fun lp(
        width: Int = MATCH_PARENT,
        height: Int = WRAP_CONTENT,
        top: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
        topMargin = top
        bottomMargin = bottom
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
