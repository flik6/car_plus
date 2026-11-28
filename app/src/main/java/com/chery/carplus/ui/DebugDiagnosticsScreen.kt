package com.chery.carplus.ui

import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
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
import com.chery.carplus.ble.PassiveEntryPrerequisiteChecker
import com.chery.carplus.ble.PassiveEntryRuntime
import com.chery.carplus.ble.PassiveEntryStore
import com.chery.carplus.cloud.AppUpdater
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.widget.CarWidgetBarProvider
import com.chery.carplus.widget.CarWidgetCompactProvider
import com.chery.carplus.widget.CarWidgetLargeProvider
import com.chery.carplus.widget.CarWidgetManager
import com.chery.carplus.widget.CarWidgetMediumProvider
import com.chery.carplus.widget.CarWidgetSnapshot
import com.chery.carplus.widget.CarWidgetStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hidden developer diagnostics screen unlocked by 5 rapid taps on the version
 * number in "关于 Qiri+".
 *
 * Structured into 3 clean layers following DESIGN_SYSTEM.md (Minimalist Automotive Dark):
 * 1. Compact live status overview card (云端 / 蓝牙 / 车辆 / 3D / 模式 + masked identity)
 * 2. Categorized 3-column concentric action grids (状态与同步 / 蓝牙诊断 / 控车测试 / 会话与数据)
 * 3. Sliding-segmented inspection & live log panel (状态快照 / 蓝牙TLV / 运行日志)
 *    with shared-axis transitions, smooth collapsible sections, and filter chips.
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
    private val onBack: () -> Unit,
    private val isDemoModeProvider: () -> Boolean = { false },
    private val vehicleCountProvider: () -> Int = { 1 },
    private val onRefreshNow: (() -> Unit)? = null,
    private val onFetchVehicles: (() -> Unit)? = null,
    private val onReload3dModel: (() -> Unit)? = null,
    private val onReconnectBle: (() -> Unit)? = null,
    private val onReadBleStatus: (() -> Unit)? = null,
    private val onOpenPassiveEntry: (() -> Unit)? = null,
    private val onDisconnectBle: (() -> Unit)? = null,
    private val onTriggerCarControl: ((actionKey: String) -> Unit)? = null,
    private val onValidateToken: ((onResult: (ok: Boolean, summary: String) -> Unit) -> Unit)? = null,
    private val onSyncConfig: (() -> Unit)? = null
) {

    private enum class InspectTab(val label: String) {
        SNAPSHOT("状态快照"),
        TLV("蓝牙TLV"),
        LOGS("运行日志")
    }

    private enum class LogFilter(val label: String) {
        ALL("全部"),
        ERROR("错误"),
        BLE("蓝牙"),
        ACTION("操作")
    }

    private data class ActionTileSpec(
        val id: String,
        val label: String,
        val icon: AppIconType? = null,
        val accent: Boolean = false,
        val haptic: HapticLevel = HapticLevel.LIGHT_TICK,
        val onClick: () -> Unit
    )

    private data class StatusMetricRefs(
        val dot: View,
        val valueView: TextView
    )

    private data class TileRefs(
        val root: LinearLayout,
        val labelView: TextView,
        val iconView: AppIconView?
    )

    private data class AccordionSectionState(
        var expanded: Boolean,
        var bodyContainer: LinearLayout? = null,
        var chevronView: AppIconView? = null,
        var summaryView: TextView? = null
    )

    private data class LiveSnapshot(
        val profile: VehicleProfile,
        val ble: CheryBleController.BleDebugSnapshot,
        val telemetry: VehicleTelemetry?,
        val modelName: String,
        val plateNo: String,
        val isDemoMode: Boolean,
        val vehicleCount: Int,
        val resolvedModelKey: String,
        val exactModelMatch: Boolean,
        val cacheInfos: List<VehicleModelRepository.ModelCacheDebugInfo>,
        val widgetSnap: CarWidgetSnapshot,
        val verName: String,
        val verCode: Int,
        val passiveEnabled: Boolean,
        val passiveUnlock: Boolean,
        val passiveLock: Boolean,
        val passiveUnlockRssi: Int,
        val passiveLockRssi: Int,
        val passiveSensitivity: String,
        val passiveWakeChannel: String,
        val passiveWakeReady: Boolean,
        val passiveServiceRunning: Boolean,
        val passiveStateLabel: String,
        val passiveRawRssi: Int?,
        val passiveFilteredRssi: Int?,
        val passivePrereqSummary: String,
        val passiveLastEvent: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var autoRefreshEnabled = true

    private var activeTab: InspectTab = InspectTab.SNAPSHOT
    private var activeLogFilter: LogFilter = LogFilter.ALL
    private var lastInspectSignature: String = ""

    private val uiActionLogs = ArrayDeque<String>(MAX_LOCAL_LOGS)
    private val sectionStates = linkedMapOf(
        SECTION_TELEMETRY to AccordionSectionState(expanded = true),
        SECTION_BLE_PASSIVE to AccordionSectionState(expanded = true),
        SECTION_CACHE_ENV to AccordionSectionState(expanded = false)
    )

    // Persistent references for flicker-free in-place updates
    private var overviewModelTv: TextView? = null
    private var overviewPlateTv: TextView? = null
    private var overviewCountTv: TextView? = null
    private var overviewModePill: LinearLayout? = null
    private var overviewModeDot: View? = null
    private var overviewModeTv: TextView? = null
    private var overviewAutoPill: TextView? = null
    private var overviewMetaTv: TextView? = null
    private val statusMetrics = HashMap<String, StatusMetricRefs>()
    private val actionTiles = HashMap<String, TileRefs>()
    private var inspectSegBar: FrameLayout? = null
    private var inspectHost: FrameLayout? = null

    private val refreshTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (autoRefreshEnabled) {
                refreshInPlace(forceInspectRebuild = false)
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun buildView(): Pair<View, () -> Unit> {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
        }

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "调试诊断",
                onBack = onBack,
                rightText = "复制",
                onRightClick = { copySanitizedReport() }
            )
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(scroll)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(32))
        }

        // 1. Compact Live Status Overview Card
        body.addView(buildOverviewCard(), lp(top = dp(4), bottom = dp(12)))

        // 2. Categorized Action Grids (4 groups × 6 buttons = 24 actions)
        body.addView(buildActionGridsSection(), lp(bottom = dp(12)))

        // 3. Spatial-Continuity Inspection & Live Log Panel
        body.addView(buildInspectionPanel(), lp())

        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        appendLocalLog("进入调试诊断")
        refreshInPlace(forceInspectRebuild = true)
        MotionSpec.staggerChildren(body, stepMs = 20L, maxDelayMs = 100L)
        mainHandler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)

        val onDestroy = {
            destroyed = true
            mainHandler.removeCallbacks(refreshTick)
            sectionStates.values.forEach { state ->
                state.bodyContainer?.animate()?.cancel()
                state.chevronView?.animate()?.cancel()
                state.bodyContainer = null
                state.chevronView = null
                state.summaryView = null
            }
            statusMetrics.clear()
            actionTiles.clear()
            overviewModelTv = null
            overviewPlateTv = null
            overviewCountTv = null
            overviewModePill = null
            overviewModeDot = null
            overviewModeTv = null
            overviewAutoPill = null
            overviewMetaTv = null
            inspectSegBar = null
            inspectHost = null
        }
        return root to onDestroy
    }

    // -------------------------------------------------------------------------
    // Section 1: Compact Live Status Overview Card
    // -------------------------------------------------------------------------

    private fun buildOverviewCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardSurfaceDrawable(radiusDp = 18f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // Header Row: Vehicle + Plate + Vehicle Count + Mode Pill + Auto-refresh Pill
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }

        val modelTv = text("--", 15f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        overviewModelTv = modelTv

        val plateTv = text("--", 10.5f, ThemeTokens.TEXT_BODY, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            applyTnum()
            background = pillDrawable(Color.parseColor("#252831"), Color.parseColor("#323642"), 11f)
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 1
        }
        overviewPlateTv = plateTv

        val countTv = text("", 10.5f, ThemeTokens.TEXT_SUB, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            applyTnum()
            visibility = View.GONE
            setPadding(dp(6), 0, dp(2), 0)
        }
        overviewCountTv = countTv

        val leftCluster = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        leftCluster.addView(modelTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        leftCluster.addView(
            plateTv,
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(22)).apply { marginStart = dp(8) }
        )
        leftCluster.addView(countTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        headerRow.addView(leftCluster, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Mode Badge (正式 / 演示)
        val modeDot = statusDot(STATUS_GREEN, sizeDp = 6)
        val modeTv = text("正式", 10.5f, STATUS_GREEN, Typeface.BOLD).apply {
            includeFontPadding = false
        }
        val modePill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isBaselineAligned = false
            background = pillDrawable(Color.parseColor("#132C24"), Color.parseColor("#1F4B3C"), 12f)
            setPadding(dp(8), 0, dp(8), 0)
            addView(modeDot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(4) })
            addView(modeTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        overviewModeDot = modeDot
        overviewModeTv = modeTv
        overviewModePill = modePill
        headerRow.addView(
            modePill,
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(24)).apply { marginStart = dp(8) }
        )

        // Live Heartbeat Toggle Pill
        val autoPill = text("实时", 10.5f, ThemeTokens.ACCENT_ACTIVE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            applyTnum()
            background = pillDrawable(Color.parseColor("#182840"), Color.parseColor("#2A4A73"), 12f)
            setPadding(dp(8), 0, dp(8), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                toggleAutoRefresh()
            }
        }
        MotionSpec.attachPressPhysics(autoPill, pressedScale = 0.94f, haptic = false)
        overviewAutoPill = autoPill
        headerRow.addView(
            autoPill,
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(24)).apply { marginStart = dp(6) }
        )

        card.addView(headerRow, lp())

        // 5-Pill Live Status Strip: 云端 / 蓝牙 / 车辆 / 3D / 无感
        val stripRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        val metricKeys = listOf(
            METRIC_CLOUD to "云端",
            METRIC_BLE to "蓝牙",
            METRIC_VEHICLE to "车辆",
            METRIC_MODEL3D to "3D",
            METRIC_PASSIVE to "无感"
        )
        metricKeys.forEachIndexed { idx, (key, title) ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = tileDrawable(
                    fillColor = TILE_IDLE_BG,
                    strokeColor = TILE_IDLE_STROKE,
                    radiusDp = 10f
                )
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val topLine = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isBaselineAligned = false
            }
            val dot = statusDot(TEXT_MUTED, sizeDp = 5)
            val labelTv = text(title, 10f, ThemeTokens.TEXT_SUB).apply {
                includeFontPadding = false
                maxLines = 1
            }
            topLine.addView(dot, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(4) })
            topLine.addView(labelTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

            val valTv = text("--", 11.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                includeFontPadding = false
                applyTnum()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }
            cell.addView(topLine, lp(width = WRAP_CONTENT))
            cell.addView(valTv, lp(width = MATCH_PARENT, top = dp(4)))

            statusMetrics[key] = StatusMetricRefs(dot = dot, valueView = valTv)
            stripRow.addView(
                cell,
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (idx < metricKeys.lastIndex) marginEnd = dp(6)
                }
            )
        }
        card.addView(stripRow, lp(top = dp(12)))

        // Compact Identity Footer (Masked phone + Masked VIN + Version)
        card.addView(
            View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, dpPx(0.6f)).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        )
        val metaTv = text("--", 11f, ThemeTokens.TEXT_SUB).apply {
            applyTnum()
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        overviewMetaTv = metaTv
        card.addView(metaTv, lp())

        return wrapHorizontalInset(card)
    }

    // -------------------------------------------------------------------------
    // Section 2: Categorized Action Button Grids (4 groups × 6 buttons)
    // -------------------------------------------------------------------------

    private fun buildActionGridsSection(): View {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val groups = listOf(
            Triple(
                "状态与同步",
                "云端 / 缓存",
                listOf(
                    ActionTileSpec(TILE_REFRESH, "立即刷新", accent = true) { handleRefreshNow() },
                    ActionTileSpec(TILE_FETCH_CARS, "拉取车辆") { handleFetchVehicles() },
                    ActionTileSpec(TILE_WIDGETS, "刷新小组件") { handleRefreshWidgets() },
                    ActionTileSpec(TILE_RELOAD_3D, "重载3D") { handleReload3d() },
                    ActionTileSpec(TILE_CHECK_3D, "检查3D") { handleCheck3dUpdate() },
                    ActionTileSpec(TILE_CLEAR_3D, "清3D缓存") { handleClear3dCache() }
                )
            ),
            Triple(
                "蓝牙诊断",
                "链路 / 无感",
                listOf(
                    ActionTileSpec(TILE_BLE_SCAN, "扫描蓝牙", accent = true) { handleBleScan() },
                    ActionTileSpec(TILE_BLE_RECONNECT, "重连蓝牙") { handleBleReconnect() },
                    ActionTileSpec(TILE_BLE_READ, "读车况") { handleBleReadStatus() },
                    ActionTileSpec(TILE_PASSIVE, "无感状态") { handleOpenPassiveStatus() },
                    ActionTileSpec(TILE_BLE_DISCONNECT, "断开蓝牙") { handleBleDisconnect() },
                    ActionTileSpec(TILE_PAIR_PREVIEW, "配对弹窗") { handlePreviewPairing() }
                )
            ),
            Triple(
                "控车测试",
                "直连 / 远程",
                listOf(
                    ActionTileSpec(TILE_CTRL_FLASH, "寻车闪灯", AppIconType.BOLT_FLASH, haptic = HapticLevel.MEDIUM_CONFIRM) {
                        handleCarControl("flash", "寻车闪灯")
                    },
                    ActionTileSpec(TILE_CTRL_LOCK, "锁车", AppIconType.LOCK_CLOSED, haptic = HapticLevel.MEDIUM_CONFIRM) {
                        handleCarControl("lock", "锁车")
                    },
                    ActionTileSpec(TILE_CTRL_UNLOCK, "解锁", AppIconType.LOCK_OPEN, haptic = HapticLevel.MEDIUM_CONFIRM) {
                        handleCarControl("unlock", "解锁")
                    },
                    ActionTileSpec(TILE_CTRL_AC, "空调开关", AppIconType.FAN_BLADE, haptic = HapticLevel.MEDIUM_CONFIRM) {
                        handleCarControl("ac_toggle", "空调开关")
                    },
                    ActionTileSpec(TILE_CTRL_TRUNK, "后备箱", AppIconType.TRUNK_OPEN, haptic = HapticLevel.MEDIUM_CONFIRM) {
                        handleCarControl("trunk", "后备箱")
                    },
                    ActionTileSpec(TILE_CTRL_ENGINE, "启动引擎", AppIconType.ENGINE_POWER, haptic = HapticLevel.MEDIUM_CONFIRM) {
                        handleCarControl("engine_toggle", "启动引擎")
                    }
                )
            ),
            Triple(
                "会话与数据",
                "脱敏 / 日志",
                listOf(
                    ActionTileSpec(TILE_TOKEN, "校验Token") { handleValidateToken() },
                    ActionTileSpec(TILE_SYNC_CFG, "同步配置") { handleSyncConfig() },
                    ActionTileSpec(TILE_COPY_DIAG, "复制诊断") { copySanitizedReport() },
                    ActionTileSpec(TILE_EXPORT_LOG, "导出日志") { handleExportLogs() },
                    ActionTileSpec(TILE_CLEAR_LOG, "清空日志") { handleClearLogs() },
                    ActionTileSpec(TILE_AUTO_REFRESH, "暂停刷新") { toggleAutoRefresh() }
                )
            )
        )

        groups.forEachIndexed { idx, (title, tag, items) ->
            wrapper.addView(
                buildActionGroupCard(title, tag, items),
                lp(top = if (idx == 0) 0 else dp(8))
            )
        }

        return wrapper
    }

    private fun buildActionGroupCard(
        title: String,
        subtitleTag: String,
        items: List<ActionTileSpec>
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardSurfaceDrawable(radiusDp = 18f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(dp(6), dp(2), dp(6), dp(8))
        }
        header.addView(
            text(title, 12.5f, ThemeTokens.TEXT_BODY, Typeface.BOLD).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        header.addView(
            text(subtitleTag, 10.5f, TEXT_MUTED).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )
        card.addView(header, lp())

        val cols = 3
        val rows = items.chunked(cols)
        rows.forEachIndexed { rowIdx, rowItems ->
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            for (c in 0 until cols) {
                val spec = rowItems.getOrNull(c)
                if (spec != null) {
                    val tile = createActionTile(spec)
                    rowLayout.addView(
                        tile,
                        LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            if (c < cols - 1) marginEnd = dp(8)
                        }
                    )
                } else {
                    rowLayout.addView(
                        View(context),
                        LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            if (c < cols - 1) marginEnd = dp(8)
                        }
                    )
                }
            }
            card.addView(rowLayout, lp(top = if (rowIdx == 0) 0 else dp(8)))
        }

        return wrapHorizontalInset(card)
    }

    private fun createActionTile(spec: ActionTileSpec): LinearLayout {
        val fill = if (spec.accent) TILE_ACCENT_BG else TILE_IDLE_BG
        val stroke = if (spec.accent) TILE_ACCENT_STROKE else TILE_IDLE_STROKE
        val textColor = if (spec.accent) ThemeTokens.ACCENT_ACTIVE else ThemeTokens.TEXT_BODY

        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isBaselineAligned = false
            background = tileDrawable(fill, stroke, radiusDp = 10f)
            setPadding(dp(6), 0, dp(6), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppHaptics.perform(it, spec.haptic)
                spec.onClick()
            }
        }

        var iconView: AppIconView? = null
        if (spec.icon != null) {
            iconView = AppIconView(context, spec.icon, textColor)
            tile.addView(
                iconView,
                LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) }
            )
        }

        val labelTv = text(spec.label, 12f, textColor, Typeface.BOLD).apply {
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        tile.addView(labelTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        MotionSpec.attachPressPhysics(tile, pressedScale = 0.95f, haptic = false)
        actionTiles[spec.id] = TileRefs(root = tile, labelView = labelTv, iconView = iconView)
        return tile
    }

    // -------------------------------------------------------------------------
    // Section 3: Sliding-Segmented Inspection & Live Log Panel
    // -------------------------------------------------------------------------

    private fun buildInspectionPanel(): View {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val segBar = MotionSpec.createSlidingSegmentedBar(
            context = context,
            labels = InspectTab.values().map { it.label },
            initial = activeTab.ordinal,
            bgColor = ThemeTokens.SEGMENT_TRACK,
            pillColor = ThemeTokens.SEGMENT_PILL,
            activeTextColor = ThemeTokens.TEXT_PRIMARY,
            inactiveTextColor = ThemeTokens.TEXT_SUB,
            trackStrokeColor = ThemeTokens.SEGMENT_TRACK_BORDER,
            pillStrokeColor = ThemeTokens.SEGMENT_PILL_BORDER
        ) { newIndex ->
            val next = InspectTab.values().getOrElse(newIndex) { InspectTab.SNAPSHOT }
            if (next != activeTab) {
                val direction = if (next.ordinal > activeTab.ordinal) 1 else -1
                activeTab = next
                renderActiveInspectTab(captureSnapshot(), direction = direction)
            }
        }
        inspectSegBar = segBar
        wrapper.addView(wrapHorizontalInset(segBar), lp(bottom = dp(10)))

        val host = FrameLayout(context)
        inspectHost = host
        wrapper.addView(host, lp())

        return wrapper
    }

    private fun renderActiveInspectTab(snap: LiveSnapshot, direction: Int) {
        val host = inspectHost ?: return
        val contentView = when (activeTab) {
            InspectTab.SNAPSHOT -> buildSnapshotTabView(snap)
            InspectTab.TLV -> buildTlvTabView(snap)
            InspectTab.LOGS -> buildLogsTabView(snap)
        }
        lastInspectSignature = computeInspectSignature(snap)

        if (direction == 0 && host.childCount > 0) {
            host.removeAllViews()
            host.addView(contentView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            MotionSpec.transitionContainer(host, contentView, direction = direction)
        }
    }

    private fun buildSnapshotTabView(snap: LiveSnapshot): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val t = snap.telemetry
        val tpms = t?.let {
            "FL ${it.lFrontTyre.ifBlank { "-" }}  FR ${it.rFrontTyre.ifBlank { "-" }}  RL ${it.lRearTyre.ifBlank { "-" }}  RR ${it.rRearTyre.ifBlank { "-" }}"
        } ?: SensitiveUiText.MISSING

        val telemetryRows = listOf(
            "油电 / 续航" to "${t?.fuelPercent?.let { "${it.toInt()}%" } ?: "--"} · ${t?.remainingRangeKm?.let { "${it.toInt()} km" } ?: "--"}",
            "总里程" to (t?.odometerKm?.let { String.format(Locale.US, "%,d km", it.toInt()) } ?: "--"),
            "电瓶 / 在线" to "${t?.batteryVoltage?.let { String.format(Locale.US, "%.1fV", it) } ?: "--"} · ${onlineText(t?.online)}",
            "门锁 / 引擎" to "${boolLabel(t?.doorLocked, "已锁", "未锁")} · ${boolLabel(t?.engineRunning, "运行", "熄火")}",
            "空调 / 尾箱" to "${boolLabel(t?.acOn, "开启", "关闭")} · ${boolLabel(t?.trunkOpen, "开启", "关闭")}",
            "天窗 / 防盗" to "${t?.skylightState?.ifBlank { "--" } ?: "--"} · ${boolLabel(antiTheftEnabledProvider(), "开", "关")}",
            "四轮胎压" to tpms,
            "上报时间" to (t?.time?.ifBlank { "--" } ?: "--")
        )
        col.addView(
            buildCollapsibleCard(
                sectionKey = SECTION_TELEMETRY,
                title = "车辆遥测",
                summary = if (t != null) "${t.fuelPercent?.toInt() ?: "--"}% · ${boolLabel(t.doorLocked, "已锁", "未锁")}" else "未同步",
                rows = telemetryRows
            ),
            lp(bottom = dp(8))
        )

        val ble = snap.ble
        val bleConnSummary = if (ble.connectedAddress.isNotBlank()) {
            "${ble.connectedAddress}${ble.currentRssi?.let { " · ${it}dBm" }.orEmpty()}"
        } else {
            "未连接"
        }
        val blePassiveRows = listOf(
            "蓝牙开关" to "${if (ble.bluetoothEnabled) "开" else "关"} · ${if (ble.scanning) "扫描中" else "空闲"} (${ble.totalScannedDevices})",
            "链路阶段" to "${ble.stage}${if (ble.isSessionReady) " · 就绪" else ""}",
            "目标设备" to "${ble.targetBleName.ifBlank { "--" }} · ${ble.targetBleMac.ifBlank { "--" }}",
            "当前连接" to bleConnSummary,
            "无感策略" to "${if (snap.passiveEnabled) "开启" else "关闭"} · 解锁:${yn(snap.passiveUnlock)} 上锁:${yn(snap.passiveLock)}",
            "无感状态" to "${snap.passiveStateLabel} · ${snap.passiveRawRssi?.let { "${it}dBm" } ?: "--"}/${snap.passiveFilteredRssi?.let { "${it}dBm" } ?: "--"}",
            "无感阈值" to "解>=${snap.passiveUnlockRssi} 锁<=${snap.passiveLockRssi} (${snap.passiveSensitivity})",
            "唤醒通道" to "${snap.passiveWakeChannel} · 通道:${yn(snap.passiveWakeReady)} 服务:${yn(snap.passiveServiceRunning)}",
            "前置条件" to SensitiveUiText.maskPhonesInText(snap.passivePrereqSummary.ifBlank { "--" }),
            "最近触发" to SensitiveUiText.maskPhonesInText(snap.passiveLastEvent.ifBlank { "--" })
        )
        col.addView(
            buildCollapsibleCard(
                sectionKey = SECTION_BLE_PASSIVE,
                title = "蓝牙与无感",
                summary = if (ble.isSessionReady) "已就绪" else ble.stage,
                rows = blePassiveRows
            ),
            lp(bottom = dp(8))
        )

        val mgr = AppWidgetManager.getInstance(context)
        val cBar = mgr?.getAppWidgetIds(ComponentName(context, CarWidgetBarProvider::class.java))?.size ?: 0
        val cCompact = mgr?.getAppWidgetIds(ComponentName(context, CarWidgetCompactProvider::class.java))?.size ?: 0
        val cMedium = mgr?.getAppWidgetIds(ComponentName(context, CarWidgetMediumProvider::class.java))?.size ?: 0
        val cLarge = mgr?.getAppWidgetIds(ComponentName(context, CarWidgetLargeProvider::class.java))?.size ?: 0
        val dm = context.resources.displayMetrics

        val envRows = mutableListOf(
            "映射模型" to "${snap.resolvedModelKey} · ${if (snap.exactModelMatch) "精准" else "回退"}",
            "账号手机" to formatAccountMobile(snap.profile.mobileNo),
            "VIN / PIN" to "${SensitiveUiText.identifier(snap.profile.vin)} · ${if (requirePinEveryTimeProvider()) "每次PIN" else "免密"}",
            "桌面小组件" to "4×1:$cBar  2×2:$cCompact  4×2:$cMedium  4×3:$cLarge",
            "组件同步" to if (snap.widgetSnap.updatedAtMs > 0L) {
                "${formatShortTime(snap.widgetSnap.updatedAtMs)} · ${snap.widgetSnap.primaryStatus} ${snap.widgetSnap.fuelPercent}%"
            } else {
                "未同步"
            },
            "云端令牌" to "AT:${if (snap.profile.accessToken.isNotBlank()) "有效(${snap.profile.accessToken.length}B)" else "空"} · SDK:${if (snap.profile.sdkToken.isNotBlank()) "有效" else "空"}",
            "运行环境" to "${Build.MODEL} · API ${Build.VERSION.SDK_INT} · ${dm.widthPixels}×${dm.heightPixels}"
        )
        snap.cacheInfos.forEach { ci ->
            val sizeStr = if (ci.cached) String.format(Locale.US, "%.1fMB", ci.sizeBytes / 1024.0 / 1024.0) else "未缓存"
            envRows += "3D ${ci.displayName}" to "$sizeStr · v${ci.version} · ${ci.sha256Short.ifBlank { "--" }}"
        }

        val cachedCount = snap.cacheInfos.count { it.cached }
        col.addView(
            buildCollapsibleCard(
                sectionKey = SECTION_CACHE_ENV,
                title = "3D缓存与环境",
                summary = "缓存 $cachedCount/${snap.cacheInfos.size}",
                rows = envRows
            ),
            lp()
        )

        return col
    }

    private fun buildCollapsibleCard(
        sectionKey: String,
        title: String,
        summary: String,
        rows: List<Pair<String, String>>
    ): View {
        val state = sectionStates.getOrPut(sectionKey) { AccordionSectionState(expanded = true) }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardSurfaceDrawable(radiusDp = 18f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(dp(2), dp(4), dp(2), dp(4))
            isClickable = true
            isFocusable = true
        }
        header.addView(
            text(title, 13f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val summaryTv = text(summary, 11f, ThemeTokens.TEXT_SUB).apply {
            applyTnum()
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(
            summaryTv,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) }
        )
        val chevron = AppIconView(context, AppIconType.CHEVRON_DOWN, TEXT_MUTED).apply {
            rotation = if (state.expanded) 180f else 0f
        }
        header.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)))
        MotionSpec.attachPressPhysics(header, pressedScale = 0.985f, haptic = false)

        val bodyBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (state.expanded) View.VISIBLE else View.GONE
            alpha = if (state.expanded) 1f else 0f
        }
        bodyBox.addView(
            View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, dpPx(0.6f)).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        )
        populateKeyValueRows(bodyBox, rows)

        state.bodyContainer = bodyBox
        state.chevronView = chevron
        state.summaryView = summaryTv

        header.setOnClickListener {
            toggleAccordionSection(state)
        }

        card.addView(header, lp())
        card.addView(bodyBox, lp())

        return wrapHorizontalInset(card)
    }

    private fun toggleAccordionSection(state: AccordionSectionState) {
        val body = state.bodyContainer ?: return
        val nextExpanded = !state.expanded
        state.expanded = nextExpanded
        MotionSpec.animateExpandCollapse(body, nextExpanded, state.chevronView)
    }

    private fun buildTlvTabView(snap: LiveSnapshot): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val ble = snap.ble

        // Card 1: GATT Handshake & TLV State
        val tlvCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardSurfaceDrawable(radiusDp = 18f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        tlvCard.addView(
            text("GATT 握手与密钥", 13f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                includeFontPadding = false
            },
            lp(bottom = dp(10))
        )

        // 6-stage handshake chips
        val stages = listOf(
            "SVC" to ble.servicesReady,
            "NTF" to ble.notificationsReady,
            "FFE1" to ble.ffe1Ready,
            "0x02" to ble.auth02Done,
            "0x03" to ble.session03Done,
            "0x80" to ble.account80Done
        )
        val stageRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        stages.forEachIndexed { idx, (label, ok) ->
            val chip = text(label, 10.5f, if (ok) STATUS_GREEN else TEXT_MUTED, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                applyTnum()
                background = tileDrawable(
                    fillColor = if (ok) Color.parseColor("#132C24") else TILE_IDLE_BG,
                    strokeColor = if (ok) Color.parseColor("#235A46") else TILE_IDLE_STROKE,
                    radiusDp = 8f
                )
            }
            stageRow.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                    if (idx < stages.lastIndex) marginEnd = dp(6)
                }
            )
        }
        tlvCard.addView(stageRow, lp(bottom = dp(10)))

        val p = snap.profile
        populateKeyValueRows(
            tlvCard,
            listOf(
                "FFE1 状态帧" to ble.ffe1Hex.ifBlank { SensitiveUiText.MISSING },
                "MTU / 配对" to "${ble.mtuPayloadChunkSize}B · ${if (ble.waitingForBond) "等待绑定" else "常态"}",
                "密钥字节" to "root ${p.rootKeyHex.length / 2}B · info ${p.infoHex.length / 2}B · edf ${p.edfHex.length / 2}B",
                "账号 / UID" to "${formatAccountMobile(p.mobileNo)} · ${SensitiveUiText.identifier(p.userId)}",
                "匹配模式" to "严格校验 MAC / Name"
            )
        )
        col.addView(wrapHorizontalInset(tlvCard), lp(bottom = dp(8)))

        // Card 2: Nearby Chery BLE Radar
        val radarCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardSurfaceDrawable(radiusDp = 18f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val radarHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        radarHeader.addView(
            text("周边奇瑞雷达", 13f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        radarHeader.addView(
            text("${ble.nearbyCheryVehicles.size} 台", 11f, ThemeTokens.TEXT_SUB, Typeface.BOLD).apply {
                applyTnum()
                includeFontPadding = false
            }
        )
        radarCard.addView(radarHeader, lp(bottom = dp(8)))

        if (ble.nearbyCheryVehicles.isEmpty()) {
            radarCard.addView(
                text("暂无周边广播，点击「扫描蓝牙」探测", 12f, ThemeTokens.TEXT_SUB).apply {
                    setPadding(0, dp(6), 0, dp(4))
                },
                lp()
            )
        } else {
            val nowMs = System.currentTimeMillis()
            ble.nearbyCheryVehicles.forEachIndexed { idx, dev ->
                val ageSec = ((nowMs - dev.lastSeenMs) / 1000L).coerceAtLeast(0L)
                val bondLabel = when (dev.bondState) {
                    BluetoothDevice.BOND_BONDED -> "已配对"
                    BluetoothDevice.BOND_BONDING -> "配对中"
                    else -> "未配对"
                }
                val itemBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = tileDrawable(
                        fillColor = TILE_IDLE_BG,
                        strokeColor = if (dev.isMatchedTarget) ThemeTokens.ACCENT_ACTIVE else TILE_IDLE_STROKE,
                        radiusDp = 10f
                    )
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isBaselineAligned = false
                }
                topRow.addView(
                    text(dev.name.ifBlank { "未知设备" }, 13f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                )
                val badge = text(
                    if (dev.isMatchedTarget) "本车匹配" else "已忽略",
                    10f,
                    if (dev.isMatchedTarget) ThemeTokens.ACCENT_ACTIVE else STATUS_AMBER,
                    Typeface.BOLD
                ).apply {
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    background = pillDrawable(
                        fillColor = if (dev.isMatchedTarget) Color.parseColor("#182840") else Color.parseColor("#332818"),
                        strokeColor = Color.TRANSPARENT,
                        radiusDp = 10f
                    )
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                }
                topRow.addView(badge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                itemBox.addView(topRow, lp())

                itemBox.addView(
                    text(
                        "${dev.address} · ${dev.rssi} dBm · $bondLabel · ${ageSec}s前",
                        11f,
                        ThemeTokens.TEXT_BODY
                    ).apply {
                        typeface = Typeface.MONOSPACE
                        applyTnum()
                    },
                    lp(top = dp(4))
                )
                itemBox.addView(
                    text(
                        SensitiveUiText.maskPhonesInText("判定: ${dev.matchReason}"),
                        11f,
                        ThemeTokens.TEXT_SUB
                    ),
                    lp(top = dp(2))
                )
                if (dev.serviceUuids.isNotEmpty()) {
                    val shortUuids = dev.serviceUuids.joinToString(", ") {
                        if (it.length >= 8) it.substring(4, 8).uppercase(Locale.US) else it
                    }
                    itemBox.addView(
                        text("UUID: $shortUuids", 10.5f, TEXT_MUTED).apply {
                            typeface = Typeface.MONOSPACE
                            applyTnum()
                        },
                        lp(top = dp(2))
                    )
                }

                radarCard.addView(itemBox, lp(top = if (idx == 0) 0 else dp(8)))
            }
        }
        col.addView(wrapHorizontalInset(radarCard), lp())

        return col
    }

    private fun buildLogsTabView(snap: LiveSnapshot): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardSurfaceDrawable(radiusDp = 18f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // Toolbar: Filter chips + Copy/Clear
        val toolRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }

        val chipsWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        LogFilter.values().forEachIndexed { idx, filter ->
            val selected = filter == activeLogFilter
            val chip = text(
                filter.label,
                11f,
                if (selected) ThemeTokens.TEXT_PRIMARY else ThemeTokens.TEXT_SUB,
                if (selected) Typeface.BOLD else Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = pillDrawable(
                    fillColor = if (selected) ThemeTokens.SEGMENT_PILL else TILE_IDLE_BG,
                    strokeColor = if (selected) ThemeTokens.SEGMENT_PILL_BORDER else TILE_IDLE_STROKE,
                    radiusDp = 12f
                )
                setPadding(dp(10), 0, dp(10), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (activeLogFilter != filter) {
                        val dir = if (filter.ordinal > activeLogFilter.ordinal) 1 else -1
                        AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                        activeLogFilter = filter
                        renderActiveInspectTab(captureSnapshot(), direction = 0)
                        inspectHost?.getChildAt(0)?.let { panel ->
                            MotionSpec.animatePanelSwitch(panel, direction = dir)
                        }
                    }
                }
            }
            MotionSpec.attachPressPhysics(chip, pressedScale = 0.94f, haptic = false)
            chipsWrap.addView(
                chip,
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(26)).apply {
                    if (idx < LogFilter.values().lastIndex) marginEnd = dp(6)
                }
            )
        }
        toolRow.addView(chipsWrap, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val copyLogBtn = text("复制", 11.5f, ThemeTokens.ACCENT_ACTIVE, Typeface.BOLD).apply {
            setPadding(dp(8), dp(4), dp(6), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                copyFilteredLogs(snap)
            }
        }
        val clearLogBtn = text("清空", 11.5f, ThemeTokens.DANGER, Typeface.BOLD).apply {
            setPadding(dp(6), dp(4), dp(2), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                handleClearLogs()
            }
        }
        MotionSpec.attachPressPhysics(copyLogBtn, pressedScale = 0.92f, haptic = false)
        MotionSpec.attachPressPhysics(clearLogBtn, pressedScale = 0.92f, haptic = false)
        toolRow.addView(copyLogBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        toolRow.addView(clearLogBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        card.addView(toolRow, lp(bottom = dp(10)))

        val merged = buildFilteredLogs(snap)
        if (merged.isEmpty()) {
            card.addView(
                text("暂无匹配日志", 12f, ThemeTokens.TEXT_SUB).apply {
                    setPadding(dp(2), dp(12), dp(2), dp(12))
                },
                lp()
            )
        } else {
            val console = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = tileDrawable(
                    fillColor = ThemeTokens.SEGMENT_TRACK,
                    strokeColor = ThemeTokens.SEGMENT_TRACK_BORDER,
                    radiusDp = 10f
                )
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            merged.take(60).forEach { rawLine ->
                val safeLine = SensitiveUiText.maskPhonesInText(rawLine)
                val lineColor = when {
                    safeLine.contains("ERR", ignoreCase = true) || safeLine.contains("失败") -> ThemeTokens.DANGER
                    safeLine.contains("成功") || safeLine.contains("命中") || safeLine.contains("就绪") -> STATUS_GREEN
                    safeLine.contains("[控车]") || safeLine.contains("配对") -> ThemeTokens.ACCENT_ACTIVE
                    safeLine.contains("[同步]") || safeLine.contains("[会话]") || safeLine.contains("[3D]") -> Color.parseColor("#C7D2FE")
                    else -> Color.parseColor("#B8BEC8")
                }
                console.addView(
                    text(safeLine, 10.5f, lineColor).apply {
                        typeface = Typeface.MONOSPACE
                        applyTnum()
                        setLineSpacing(dpF(2f), 1f)
                    },
                    lp(bottom = dp(3))
                )
            }
            card.addView(console, lp())
        }

        return wrapHorizontalInset(card)
    }

    // -------------------------------------------------------------------------
    // Flicker-Free In-Place Refresh
    // -------------------------------------------------------------------------

    private fun refreshInPlace(forceInspectRebuild: Boolean) {
        if (destroyed) return
        val snap = captureSnapshot()

        // 1. Overview header
        overviewModelTv?.text = snap.modelName
        overviewPlateTv?.text = snap.plateNo
        overviewCountTv?.apply {
            if (snap.vehicleCount > 1) {
                visibility = View.VISIBLE
                text = "${snap.vehicleCount}台"
            } else {
                visibility = View.GONE
            }
        }

        // Mode pill (正式 / 演示)
        if (snap.isDemoMode) {
            overviewModeTv?.text = "演示"
            overviewModeTv?.setTextColor(STATUS_AMBER)
            setDotColor(overviewModeDot, STATUS_AMBER)
            overviewModePill?.background = pillDrawable(Color.parseColor("#332818"), Color.parseColor("#57401C"), 12f)
        } else {
            overviewModeTv?.text = "正式"
            overviewModeTv?.setTextColor(STATUS_GREEN)
            setDotColor(overviewModeDot, STATUS_GREEN)
            overviewModePill?.background = pillDrawable(Color.parseColor("#132C24"), Color.parseColor("#1F4B3C"), 12f)
        }

        // Auto-refresh pill & button sync
        syncAutoRefreshControls()

        // 5-cell live status strip
        val cloudOk = snap.isDemoMode || snap.profile.accessToken.isNotBlank()
        updateMetric(
            METRIC_CLOUD,
            label = when {
                snap.isDemoMode -> "演示"
                snap.telemetry != null && cloudOk -> "在线"
                cloudOk -> "就绪"
                else -> "未登录"
            },
            dotColor = when {
                snap.isDemoMode -> STATUS_AMBER
                cloudOk -> STATUS_GREEN
                else -> ThemeTokens.DANGER
            }
        )

        val ble = snap.ble
        updateMetric(
            METRIC_BLE,
            label = when {
                ble.isSessionReady -> "已就绪"
                ble.scanning -> "扫描中"
                ble.connectedAddress.isNotBlank() -> "握手中"
                ble.bluetoothEnabled -> "待机"
                else -> "未开启"
            },
            dotColor = when {
                ble.isSessionReady -> STATUS_GREEN
                ble.scanning || ble.connectedAddress.isNotBlank() -> ThemeTokens.ACCENT_ACTIVE
                ble.bluetoothEnabled -> STATUS_AMBER
                else -> ThemeTokens.DANGER
            }
        )

        val t = snap.telemetry
        updateMetric(
            METRIC_VEHICLE,
            label = when {
                t == null -> "待同步"
                t.engineRunning == true -> "已启动"
                t.doorLocked == true -> "已锁止"
                t.doorLocked == false -> "已解锁"
                else -> "在线"
            },
            dotColor = when {
                t == null -> TEXT_MUTED
                t.engineRunning == true -> ThemeTokens.ACCENT_ACTIVE
                t.doorLocked == true -> STATUS_GREEN
                else -> STATUS_AMBER
            }
        )

        val activeModelCached = snap.cacheInfos.any { it.key == snap.resolvedModelKey && it.cached }
        updateMetric(
            METRIC_MODEL3D,
            label = if (activeModelCached) "已缓存" else "待拉取",
            dotColor = if (activeModelCached) STATUS_GREEN else STATUS_AMBER
        )

        updateMetric(
            METRIC_PASSIVE,
            label = if (snap.passiveEnabled) {
                if (snap.passiveServiceRunning) "运行中" else "已开启"
            } else {
                "已关闭"
            },
            dotColor = when {
                snap.passiveEnabled && snap.passiveServiceRunning -> STATUS_GREEN
                snap.passiveEnabled -> STATUS_AMBER
                else -> TEXT_MUTED
            }
        )

        // Masked identity footer
        val maskedAccount = formatAccountMobile(snap.profile.mobileNo)
        val maskedVin = SensitiveUiText.identifier(snap.profile.vin)
        overviewMetaTv?.text = "账号 $maskedAccount · VIN $maskedVin · v${snap.verName}(${snap.verCode})"

        // Dynamic scan tile label
        actionTiles[TILE_BLE_SCAN]?.let { tile ->
            tile.labelView.text = if (ble.scanning) "扫描中…" else "扫描蓝牙"
        }

        // Inspection panel: rebuild only when data signature actually changes (zero flicker)
        val sig = computeInspectSignature(snap)
        if (forceInspectRebuild || sig != lastInspectSignature) {
            renderActiveInspectTab(snap, direction = 0)
        }
    }

    private fun updateMetric(key: String, label: String, dotColor: Int) {
        val ref = statusMetrics[key] ?: return
        ref.valueView.text = label
        setDotColor(ref.dot, dotColor)
    }

    private fun syncAutoRefreshControls() {
        overviewAutoPill?.apply {
            if (autoRefreshEnabled) {
                text = "实时"
                setTextColor(ThemeTokens.ACCENT_ACTIVE)
                background = pillDrawable(Color.parseColor("#182840"), Color.parseColor("#2A4A73"), 12f)
            } else {
                text = "已暂停"
                setTextColor(STATUS_AMBER)
                background = pillDrawable(Color.parseColor("#332818"), Color.parseColor("#57401C"), 12f)
            }
        }
        actionTiles[TILE_AUTO_REFRESH]?.let { tile ->
            tile.labelView.text = if (autoRefreshEnabled) "暂停刷新" else "恢复刷新"
            tile.labelView.setTextColor(if (autoRefreshEnabled) ThemeTokens.TEXT_BODY else STATUS_AMBER)
        }
    }

    // -------------------------------------------------------------------------
    // Action Handlers
    // -------------------------------------------------------------------------

    private fun handleRefreshNow() {
        appendLocalLog("[同步] 立即刷新")
        onRefreshNow?.invoke()
        toast("已触发立即刷新")
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleFetchVehicles() {
        appendLocalLog("[同步] 拉取车辆列表")
        if (onFetchVehicles != null) {
            onFetchVehicles.invoke()
            toast("已触发拉取车辆")
        } else {
            toast("当前未挂载拉取回调")
        }
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleRefreshWidgets() {
        CarWidgetManager.updateAllWidgets(context)
        CarWidgetManager.triggerBackgroundSyncIfStale(context, minStaleMs = 0L)
        appendLocalLog("[同步] 刷新全部小组件")
        toast("小组件已刷新")
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleReload3d() {
        appendLocalLog("[3D] 重载车模")
        if (onReload3dModel != null) {
            onReload3dModel.invoke()
            toast("已重载 3D 车模")
        } else {
            toast("未挂载重载3D回调")
        }
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleCheck3dUpdate() {
        val modelName = modelNameProvider().ifBlank { "未知车型" }
        appendLocalLog("[3D] 检查更新: $modelName")
        toast("正在检查 3D 模型更新")
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                VehicleModelRepository.checkAndUpdateInBackground(context, modelName, force = true)
            }
            val msg = when {
                res.updated -> "已更新 ${res.key} 模型"
                res.checked -> "${res.key} 已是最新"
                else -> "检查失败或离线"
            }
            appendLocalLog("[3D] $msg")
            toast(msg)
            if (!destroyed) refreshInPlace(forceInspectRebuild = true)
        }
    }

    private fun handleClear3dCache() {
        val count = VehicleModelRepository.clearAllModelCaches(context)
        appendLocalLog("[3D] 清理缓存 $count 个")
        toast("已清 $count 个模型缓存")
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleBleScan() {
        if (onRequestBlePermissions()) {
            bleController.startDiagnosticScan(10_000L)
            appendLocalLog("[蓝牙] 主动扫描 10s")
            toast("开始扫描周边蓝牙")
            refreshInPlace(forceInspectRebuild = true)
        } else {
            appendLocalLog("[蓝牙] ERR: 缺少蓝牙权限")
            toast("请先授予蓝牙权限")
        }
    }

    private fun handleBleReconnect() {
        appendLocalLog("[蓝牙] 发起重连")
        if (onReconnectBle != null) {
            onReconnectBle.invoke()
            toast("已触发蓝牙重连")
        } else if (onRequestBlePermissions()) {
            bleController.triggerImmediatePairingOrConnect(
                profileProvider = { profileProvider() },
                onRequestEnableBluetooth = { toast("请先开启蓝牙") }
            )
            toast("已发起蓝牙重连")
        } else {
            toast("请先授予蓝牙权限")
        }
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleBleReadStatus() {
        appendLocalLog("[蓝牙] 读取车况")
        if (onReadBleStatus != null) {
            onReadBleStatus.invoke()
            toast("已触发读车况")
        } else {
            val rssiRequested = bleController.requestRemoteRssi()
            onRefreshNow?.invoke()
            toast(if (rssiRequested) "已请求蓝牙信号与车况" else "蓝牙未连接，已刷新快照")
        }
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleOpenPassiveStatus() {
        appendLocalLog("[蓝牙] 查看无感状态")
        if (onOpenPassiveEntry != null) {
            onOpenPassiveEntry.invoke()
        } else {
            val snap = captureSnapshot()
            sectionStates[SECTION_BLE_PASSIVE]?.expanded = true
            if (activeTab != InspectTab.SNAPSHOT) {
                val tabRow = inspectSegBar?.getChildAt(1) as? LinearLayout
                val firstTab = tabRow?.getChildAt(InspectTab.SNAPSHOT.ordinal)
                if (firstTab != null) {
                    firstTab.performClick()
                } else {
                    activeTab = InspectTab.SNAPSHOT
                    renderActiveInspectTab(snap, direction = -1)
                }
            } else {
                renderActiveInspectTab(snap, direction = 0)
            }
            toast("无感: ${if (snap.passiveEnabled) snap.passiveStateLabel else "已关闭"}")
        }
    }

    private fun handleBleDisconnect() {
        appendLocalLog("[蓝牙] 断开连接")
        if (onDisconnectBle != null) {
            onDisconnectBle.invoke()
        } else {
            bleController.disconnect()
        }
        toast("已断开蓝牙")
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handlePreviewPairing() {
        val ble = bleController.getDebugSnapshot(profileProvider())
        val samplePin = lastPairingPinProvider().ifBlank { "482915" }
        val devName = ble.targetBleName.ifBlank { modelNameProvider().ifBlank { "Qiri+" } }
        val devMac = ble.targetBleMac.ifBlank { "AA:BB:CC:DD:EE:FF" }
        appendLocalLog("[蓝牙] 预览配对弹窗")
        onPreviewPairingDrawer(samplePin, devName, devMac)
    }

    private fun handleCarControl(actionKey: String, label: String) {
        appendLocalLog("[控车] $label ($actionKey)")
        if (onTriggerCarControl != null) {
            onTriggerCarControl.invoke(actionKey)
        } else {
            val sent = bleController.sendVehicleAction(actionKey)
            toast(if (sent) "已下发 $label" else "未连接，无法执行 $label")
        }
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleValidateToken() {
        appendLocalLog("[会话] 校验 Token")
        if (onValidateToken != null) {
            toast("正在校验 Token")
            onValidateToken.invoke { ok, summary ->
                mainHandler.post {
                    val safeSummary = SensitiveUiText.maskPhonesInText(summary)
                    appendLocalLog("[会话] ${if (ok) "成功" else "ERR"}: $safeSummary")
                    toast(safeSummary)
                    if (!destroyed) refreshInPlace(forceInspectRebuild = true)
                }
            }
        } else {
            val p = profileProvider()
            val msg = when {
                isDemoModeProvider() -> "演示模式免校验"
                p.accessToken.isNotBlank() && p.sdkToken.isNotBlank() -> "Token 本地有效"
                p.accessToken.isNotBlank() -> "AT有效 / SDK缺失"
                else -> "Token 缺失"
            }
            appendLocalLog("[会话] $msg")
            toast(msg)
            refreshInPlace(forceInspectRebuild = true)
        }
    }

    private fun handleSyncConfig() {
        appendLocalLog("[会话] 同步配置")
        if (onSyncConfig != null) {
            onSyncConfig.invoke()
            toast("已触发同步配置")
        } else {
            onRefreshNow?.invoke()
            CarWidgetManager.updateAllWidgets(context)
            toast("已同步本地配置")
        }
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleExportLogs() {
        val report = buildSanitizedReportText()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        runCatching {
            val outFile = File(context.cacheDir, "qiri_diag_$ts.txt")
            outFile.writeText(report)
        }
        appendLocalLog("[会话] 导出脱敏日志")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Qiri+ 脱敏诊断日志 $ts")
            putExtra(Intent.EXTRA_TEXT, report)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(Intent.createChooser(shareIntent, "导出诊断日志"))
            true
        }.getOrDefault(false)
        if (!launched) {
            copyTextToClipboard("Qiri+ Debug Log", report)
            toast("已复制到剪贴板")
        } else {
            toast("已生成脱敏日志")
        }
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun handleClearLogs() {
        bleController.clearDebugLogs()
        synchronized(uiActionLogs) { uiActionLogs.clear() }
        toast("日志已清空")
        refreshInPlace(forceInspectRebuild = true)
    }

    private fun toggleAutoRefresh() {
        autoRefreshEnabled = !autoRefreshEnabled
        syncAutoRefreshControls()
        appendLocalLog(if (autoRefreshEnabled) "[会话] 恢复实时刷新" else "[会话] 暂停实时刷新")
        toast(if (autoRefreshEnabled) "已恢复自动刷新" else "已暂停自动刷新")
        if (autoRefreshEnabled) {
            refreshInPlace(forceInspectRebuild = true)
        }
    }

    // -------------------------------------------------------------------------
    // Report & Sanitization
    // -------------------------------------------------------------------------

    private fun copySanitizedReport() {
        val report = buildSanitizedReportText()
        copyTextToClipboard("Qiri+ Debug Report", report)
        appendLocalLog("[会话] 复制脱敏报告")
        toast("已复制脱敏报告")
    }

    private fun copyFilteredLogs(snap: LiveSnapshot) {
        val lines = buildFilteredLogs(snap).map { SensitiveUiText.maskPhonesInText(it) }
        val payload = if (lines.isEmpty()) "暂无日志" else lines.joinToString("\n")
        copyTextToClipboard("Qiri+ Debug Logs", payload)
        toast("已复制 ${lines.size} 条脱敏日志")
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, SensitiveUiText.maskPhonesInText(text)))
    }

    private fun buildSanitizedReportText(): String {
        val snap = captureSnapshot()
        val ble = snap.ble
        val p = snap.profile
        val raw = buildString {
            appendLine("=== Qiri+ Debug Report (Sanitized) ===")
            appendLine("Mode: ${if (snap.isDemoMode) "DEMO" else "PROD"} (${snap.vehicleCount} vehicles)")
            appendLine("App: v${snap.verName} (Build ${snap.verCode})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Account: ${formatAccountMobile(p.mobileNo)} / UID=${SensitiveUiText.identifier(p.userId)}")
            appendLine("Vehicle: ${snap.modelName} (${snap.plateNo}) / VIN=${SensitiveUiText.identifier(p.vin)}")
            appendLine("Target BLE: mac=${ble.targetBleMac.ifBlank { "--" }} name=${ble.targetBleName.ifBlank { "--" }}")
            appendLine("BLE Stage: ${ble.stage} ready=${ble.isSessionReady} connected=${ble.connectedAddress} rssi=${ble.currentRssi}")
            appendLine("Passive: enabled=${snap.passiveEnabled} state=${snap.passiveStateLabel} wake=${snap.passiveWakeChannel}")
            appendLine("Nearby Chery BLE (${ble.nearbyCheryVehicles.size}):")
            ble.nearbyCheryVehicles.forEach { d ->
                appendLine("  - ${d.name} [${d.address}] ${d.rssi}dBm matched=${d.isMatchedTarget} bond=${d.bondState}")
            }
            appendLine("Model Cache:")
            snap.cacheInfos.forEach { c ->
                appendLine("  - ${c.key}: cached=${c.cached} bytes=${c.sizeBytes} sha=${c.sha256Short}")
            }
            val logs = buildFilteredLogs(snap, LogFilter.ALL)
            appendLine("Recent Logs (${logs.size}):")
            logs.take(50).forEach { appendLine("  $it") }
        }
        return SensitiveUiText.maskPhonesInText(raw)
    }

    private fun buildFilteredLogs(snap: LiveSnapshot, filter: LogFilter = activeLogFilter): List<String> {
        val local = synchronized(uiActionLogs) { uiActionLogs.toList() }
        val combined = ArrayList<String>(local.size + snap.ble.recentLogs.size)
        combined.addAll(local)
        combined.addAll(snap.ble.recentLogs)
        return when (filter) {
            LogFilter.ALL -> combined
            LogFilter.ERROR -> combined.filter {
                it.contains("ERR", ignoreCase = true) || it.contains("失败") || it.contains("超时")
            }
            LogFilter.BLE -> combined.filter {
                it.contains("[蓝牙]") || it.contains("BLE", ignoreCase = true) ||
                    it.contains("GATT", ignoreCase = true) || it.contains("配对") ||
                    it.contains("扫描") || it.contains("RSSI", ignoreCase = true)
            }
            LogFilter.ACTION -> combined.filter {
                it.contains("[同步]") || it.contains("[控车]") || it.contains("[3D]") ||
                    it.contains("[会话]") || it.contains("[操作]")
            }
        }
    }

    private fun appendLocalLog(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val safeMsg = SensitiveUiText.maskPhonesInText(message)
        synchronized(uiActionLogs) {
            while (uiActionLogs.size >= MAX_LOCAL_LOGS) {
                uiActionLogs.removeLast()
            }
            uiActionLogs.addFirst("[$ts] $safeMsg")
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot Capture & Signature
    // -------------------------------------------------------------------------

    private fun captureSnapshot(): LiveSnapshot {
        val profile = profileProvider()
        val ble = bleController.getDebugSnapshot(profile)
        val telemetry = telemetryProvider()
        val modelName = modelNameProvider().ifBlank { "未知车型" }
        val plateNo = plateNoProvider().ifBlank { "--" }
        val resolvedKey = VehicleModelRepository.resolveModelKey(modelName)
        val exactMatch = VehicleModelRepository.isExactModelMatch(modelName)
        val cacheInfos = VehicleModelRepository.getDebugCacheStatus(context)
        val widgetSnap = CarWidgetStateStore.load(context)
        val (verName, verCode) = AppUpdater.getCurrentVersion(context)
        val passiveCfg = PassiveEntryStore.load(context, profile.vin)
        val passiveSnap = PassiveEntryRuntime.currentSnapshot()
        val passivePrereq = PassiveEntryPrerequisiteChecker.evaluate(context, profile)

        return LiveSnapshot(
            profile = profile,
            ble = ble,
            telemetry = telemetry,
            modelName = modelName,
            plateNo = plateNo,
            isDemoMode = isDemoModeProvider(),
            vehicleCount = vehicleCountProvider().coerceAtLeast(1),
            resolvedModelKey = resolvedKey,
            exactModelMatch = exactMatch,
            cacheInfos = cacheInfos,
            widgetSnap = widgetSnap,
            verName = verName,
            verCode = verCode,
            passiveEnabled = passiveCfg.enabled,
            passiveUnlock = passiveCfg.autoUnlockEnabled,
            passiveLock = passiveCfg.autoLockEnabled,
            passiveUnlockRssi = passiveCfg.effectiveUnlockRssi,
            passiveLockRssi = passiveCfg.effectiveLockRssi,
            passiveSensitivity = passiveCfg.sensitivity.label,
            passiveWakeChannel = passiveSnap.wakeChannelLabel,
            passiveWakeReady = passiveSnap.wakeChannelActive,
            passiveServiceRunning = passiveSnap.serviceRunning,
            passiveStateLabel = passiveSnap.state.label,
            passiveRawRssi = passiveSnap.currentRawRssi,
            passiveFilteredRssi = passiveSnap.filteredRssi,
            passivePrereqSummary = passivePrereq.summaryText,
            passiveLastEvent = passiveSnap.lastEventText.ifBlank { passiveCfg.lastTriggerSummary }
        )
    }

    private fun computeInspectSignature(snap: LiveSnapshot): String {
        val ble = snap.ble
        val t = snap.telemetry
        return when (activeTab) {
            InspectTab.SNAPSHOT -> listOf(
                snap.modelName, snap.plateNo, snap.isDemoMode, snap.vehicleCount,
                t?.time, t?.fuelPercent, t?.remainingRangeKm, t?.doorLocked, t?.engineRunning,
                t?.acOn, t?.trunkOpen, t?.batteryVoltage,
                ble.stage, ble.scanning, ble.isSessionReady, ble.connectedAddress, ble.currentRssi,
                ble.totalScannedDevices, snap.passiveEnabled, snap.passiveStateLabel,
                snap.passiveRawRssi, snap.passiveFilteredRssi, snap.widgetSnap.updatedAtMs,
                snap.cacheInfos.count { it.cached }
            ).joinToString("|")

            InspectTab.TLV -> listOf(
                ble.servicesReady, ble.notificationsReady, ble.ffe1Ready, ble.auth02Done,
                ble.session03Done, ble.account80Done, ble.ffe1Hex, ble.mtuPayloadChunkSize,
                ble.waitingForBond, ble.nearbyCheryVehicles.size,
                ble.nearbyCheryVehicles.joinToString(",") { "${it.address}:${it.rssi}:${it.isMatchedTarget}:${it.bondState}" }
            ).joinToString("|")

            InspectTab.LOGS -> {
                val localSize = synchronized(uiActionLogs) { uiActionLogs.size }
                val localHead = synchronized(uiActionLogs) { uiActionLogs.firstOrNull().orEmpty() }
                "${activeLogFilter.name}|$localSize|$localHead|${ble.recentLogs.size}|${ble.recentLogs.firstOrNull().orEmpty()}"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared UI Primitives
    // -------------------------------------------------------------------------

    private fun populateKeyValueRows(container: LinearLayout, rows: List<Pair<String, String>>) {
        rows.forEachIndexed { idx, (k, v) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                isBaselineAligned = false
                setPadding(dp(2), dp(5), dp(2), dp(5))
            }
            row.addView(
                text(k, 11.8f, ThemeTokens.TEXT_SUB).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(dp(92), WRAP_CONTENT)
            )
            row.addView(
                text(v, 11.8f, ThemeTokens.TEXT_PRIMARY).apply {
                    typeface = Typeface.MONOSPACE
                    applyTnum()
                    gravity = Gravity.END
                    setLineSpacing(dpF(2f), 1f)
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
            )
            container.addView(row, lp())
            if (idx < rows.lastIndex) {
                container.addView(
                    View(context).apply { setBackgroundColor(Color.parseColor("#23262E")) },
                    LinearLayout.LayoutParams(MATCH_PARENT, dpPx(0.6f))
                )
            }
        }
    }

    private fun wrapHorizontalInset(child: View): FrameLayout = FrameLayout(context).apply {
        setPadding(dp(16), 0, dp(16), 0)
        addView(child, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun statusDot(color: Int, sizeDp: Int): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun setDotColor(dot: View?, color: Int) {
        val bg = dot?.background as? GradientDrawable ?: return
        bg.setColor(color)
    }

    private fun cardSurfaceDrawable(radiusDp: Float = 18f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(ThemeTokens.BG_SURFACE)
            setStroke(dpPx(0.8f), Color.parseColor("#282B33"))
        }

    private fun tileDrawable(fillColor: Int, strokeColor: Int, radiusDp: Float = 10f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dpPx(0.8f), strokeColor)
            }
        }

    private fun pillDrawable(fillColor: Int, strokeColor: Int, radiusDp: Float = 12f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dpPx(0.8f), strokeColor)
            }
        }

    private fun formatAccountMobile(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return SensitiveUiText.MISSING
        if (clean.equals("demo", ignoreCase = true)) return "demo"
        return SensitiveUiText.maskPhone(clean, SensitiveUiText.MISSING)
    }

    private fun onlineText(raw: String?): String = when (raw?.trim()?.lowercase(Locale.US)) {
        "1", "true", "online" -> "在线"
        "0", "false", "offline" -> "离线"
        null, "" -> "--"
        else -> raw
    }

    private fun boolLabel(flag: Boolean?, yes: String, no: String): String = when (flag) {
        true -> yes
        false -> no
        null -> "--"
    }

    private fun yn(flag: Boolean): String = if (flag) "开" else "关"

    private fun formatShortTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun TextView.applyTnum() {
        fontFeatureSettings = "tnum"
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif", style)
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    private fun dpF(value: Float): Float = value * context.resources.displayMetrics.density
    private fun dpPx(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

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
        private const val REFRESH_INTERVAL_MS = 1_500L
        private const val MAX_LOCAL_LOGS = 80

        private const val METRIC_CLOUD = "cloud"
        private const val METRIC_BLE = "ble"
        private const val METRIC_VEHICLE = "vehicle"
        private const val METRIC_MODEL3D = "model3d"
        private const val METRIC_PASSIVE = "passive"

        private const val SECTION_TELEMETRY = "telemetry"
        private const val SECTION_BLE_PASSIVE = "ble_passive"
        private const val SECTION_CACHE_ENV = "cache_env"

        private const val TILE_REFRESH = "refresh"
        private const val TILE_FETCH_CARS = "fetch_cars"
        private const val TILE_WIDGETS = "widgets"
        private const val TILE_RELOAD_3D = "reload_3d"
        private const val TILE_CHECK_3D = "check_3d"
        private const val TILE_CLEAR_3D = "clear_3d"
        private const val TILE_BLE_SCAN = "ble_scan"
        private const val TILE_BLE_RECONNECT = "ble_reconnect"
        private const val TILE_BLE_READ = "ble_read"
        private const val TILE_PASSIVE = "passive"
        private const val TILE_BLE_DISCONNECT = "ble_disconnect"
        private const val TILE_PAIR_PREVIEW = "pair_preview"
        private const val TILE_CTRL_FLASH = "ctrl_flash"
        private const val TILE_CTRL_LOCK = "ctrl_lock"
        private const val TILE_CTRL_UNLOCK = "ctrl_unlock"
        private const val TILE_CTRL_AC = "ctrl_ac"
        private const val TILE_CTRL_TRUNK = "ctrl_trunk"
        private const val TILE_CTRL_ENGINE = "ctrl_engine"
        private const val TILE_TOKEN = "token"
        private const val TILE_SYNC_CFG = "sync_cfg"
        private const val TILE_COPY_DIAG = "copy_diag"
        private const val TILE_EXPORT_LOG = "export_log"
        private const val TILE_CLEAR_LOG = "clear_log"
        private const val TILE_AUTO_REFRESH = "auto_refresh"

        private val STATUS_GREEN = Color.parseColor("#34D399")
        private val STATUS_AMBER = Color.parseColor("#FBBF24")
        private val TEXT_MUTED = Color.parseColor("#5C606A")
        private val TILE_IDLE_BG = Color.parseColor("#22242A")
        private val TILE_IDLE_STROKE = Color.parseColor("#2E323D")
        private val TILE_ACCENT_BG = Color.parseColor("#182840")
        private val TILE_ACCENT_STROKE = Color.parseColor("#2F5A92")
    }
}
