package com.chery.carplus.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chery.carplus.R
import com.chery.carplus.ble.PassiveEntryConfig
import com.chery.carplus.ble.PassiveEntryPrerequisiteChecker
import com.chery.carplus.ble.PassiveEntryRuntime
import com.chery.carplus.ble.PassiveEntrySnapshot
import com.chery.carplus.ble.PassiveEntryStore
import com.chery.carplus.ble.PassiveSensitivity
import com.chery.carplus.ble.PassiveState
import com.chery.carplus.ble.PrerequisiteCheckItem
import com.chery.carplus.ble.PrerequisiteId
import com.chery.carplus.profile.VehicleProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Minimalist dark bottom sheets for Bluetooth Passive Entry ("蓝牙无感"):
 * 1. Prerequisite gate checklist sheet (blocks enabling until all required items pass).
 * 2. Compact passive entry control & distance calibration sheet.
 */
class PassiveEntrySheets(
    private val context: Context,
    private val profileProvider: () -> VehicleProfile,
    private val liveBleConnectedProvider: () -> Boolean,
    private val liveBleRssiProvider: () -> Int?,
    private val onRequestLiveRssi: () -> Unit,
    private val onFixPrerequisite: (PrerequisiteId, VehicleProfile) -> Unit,
    private val onProceedEnableAfterPrerequisites: (VehicleProfile, onCompleted: (Boolean) -> Unit) -> Unit,
    private val onConfigUpdated: (PassiveEntryConfig) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeDialog: Dialog? = null
    private var activeImmediateDismiss: (() -> Unit)? = null
    private var refreshOpenSheetBlock: (() -> Unit)? = null
    private var rssiPollRunnable: Runnable? = null

    fun refreshIfOpen() {
        mainHandler.post { refreshOpenSheetBlock?.invoke() }
    }

    fun dismiss() {
        stopRssiPolling()
        refreshOpenSheetBlock = null
        activeImmediateDismiss?.invoke() ?: activeDialog?.dismiss()
        activeImmediateDismiss = null
        activeDialog = null
    }

    /**
     * Shows the prerequisite check & wake-status bottom sheet.
     */
    fun showPrerequisiteGateSheet(
        inspectOnly: Boolean = false,
        onEnabledCallback: ((Boolean) -> Unit)? = null
    ) {
        if (!MotionSpec.isHostActivityAlive(context)) return
        dismiss()
        val outer = createSheetShell()
        val (dialog, immediateDismiss) = createAnimatedSheetDialog(outer)

        val titleTv = text(
            if (inspectOnly) "权限与连接检查" else "开启前需完成设置",
            19f,
            ThemeTokens.TEXT_PRIMARY,
            Typeface.BOLD
        )
        val subtitleTv = text("", 13f, ThemeTokens.TEXT_SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        outer.addView(titleTv, lp(top = dp(4)))
        outer.addView(subtitleTv, lp(top = dp(5), bottom = dp(12)))

        val maxScrollH = (context.resources.displayMetrics.heightPixels * 0.50f).roundToInt()
        val scroll = createMaxHeightScrollView(maxScrollH)
        val listBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listBody, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val ctaBtn = AppComponents.createActionButton(
            context = context,
            label = "完成设置后开启",
            style = ButtonStyle.PRIMARY
        ) {}
        outer.addView(ctaBtn, lp(top = dp(14)))

        val render = {
            val profile = profileProvider()
            val report = PassiveEntryPrerequisiteChecker.evaluate(context, profile)
            val requiredTotal = report.items.count { it.required }
            val requiredDone = report.items.count { it.required && it.satisfied }
            subtitleTv.text = if (report.allRequiredSatisfied) {
                "全部必备条件已就绪 · ${report.wakeChannelMode}"
            } else {
                "已完成 $requiredDone/$requiredTotal 项必备条件"
            }

            listBody.removeAllViews()
            report.items.forEachIndexed { idx, item ->
                listBody.addView(buildPrerequisiteRow(item, profile), lp())
                if (idx < report.items.lastIndex) {
                    listBody.addView(createFullHairline(startInsetDp = 32), lp())
                }
            }

            if (inspectOnly) {
                ctaBtn.text = "完成"
                ctaBtn.alpha = 1f
                ctaBtn.isEnabled = true
                ctaBtn.setOnClickListener { dialog.dismiss() }
            } else if (report.allRequiredSatisfied) {
                ctaBtn.text = "验证 PIN 码并开启"
                ctaBtn.alpha = 1f
                ctaBtn.isEnabled = true
                ctaBtn.setOnClickListener {
                    AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                    immediateDismiss()
                    onProceedEnableAfterPrerequisites(profile) { enabled ->
                        onEnabledCallback?.invoke(enabled)
                    }
                }
            } else {
                ctaBtn.text = "还需完成 ${report.missingRequiredCount} 项设置"
                ctaBtn.alpha = 0.45f
                ctaBtn.isEnabled = false
                ctaBtn.setOnClickListener(null)
            }
        }

        render()
        refreshOpenSheetBlock = render

        AppSystemInsets.installBottomSheetContent(dialog, outer, dimAmount = 0.65f)
        MotionSpec.attachSheetSwipeToDismiss(outer, scroll, dialog)
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                activeImmediateDismiss = null
                refreshOpenSheetBlock = null
            }
        }
        activeDialog = dialog
        activeImmediateDismiss = immediateDismiss
        dialog.show()
        MotionSpec.animateSheetEnter(outer)
    }

    /**
     * Shows the redesigned, compact "蓝牙无感" configuration & proximity calibration sheet.
     */
    fun showPassiveEntryConfigSheet() {
        if (!MotionSpec.isHostActivityAlive(context)) return
        dismiss()
        val profile = profileProvider()
        if (profile.vin.isBlank()) {
            Toast.makeText(context, "当前暂无有效车辆信息", Toast.LENGTH_SHORT).show()
            return
        }

        val outer = createSheetShell()
        val (dialog, immediateDismiss) = createAnimatedSheetDialog(outer)

        // Compact header: Title + compact status pill (zero multi-line wrapping subtitle)
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            text("蓝牙无感", 20f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val stateBadge = text("", 11.5f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dpF(11f)
                setColor(Color.parseColor("#1D2C44"))
                setStroke(strokePx(), Color.parseColor("#2E4870"))
            }
        }
        titleRow.addView(stateBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        outer.addView(titleRow, lp(top = dp(4), bottom = dp(14)))

        val maxBodyH = (context.resources.displayMetrics.heightPixels * 0.56f).roundToInt()
        val scroll = createMaxHeightScrollView(maxBodyH)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        var cfg = PassiveEntryStore.load(context, profile.vin)

        // Elevated status card with colored StatusDotView anchor & clear luminance contrast
        val statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dpF(13f)
                setColor(ThemeTokens.BG_CARD_ELEVATED)
                setStroke(strokePx(), ThemeTokens.BORDER_SUBTLE)
            }
        }
        val statusDot = StatusDotView(context)
        statusCard.addView(
            statusDot,
            LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(11) }
        )
        val statusTextCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val statusMainTv = text("", 14f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val statusSubTv = text("", 12f, ThemeTokens.TEXT_SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        statusTextCol.addView(statusMainTv, lp())
        statusTextCol.addView(statusSubTv, lp(top = dp(3)))
        statusCard.addView(statusTextCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(statusCard, lp(bottom = dp(10)))

        // Sub-switch 1: Approach Auto-Unlock (concise single-line subtitle, zero dBm jargon)
        val unlockSwitch = ToggleSwitch(context).apply {
            setChecked(cfg.autoUnlockEnabled, animate = false)
            onToggleAttempt = { next ->
                cfg = cfg.copy(autoUnlockEnabled = next)
                PassiveEntryStore.save(context, cfg)
                setChecked(next, animate = true)
                onConfigUpdated(cfg)
                refreshOpenSheetBlock?.invoke()
            }
        }
        body.addView(
            buildSimpleToggleRow(
                icon = AppIconType.LOCK_OPEN,
                title = "靠近自动解锁",
                subtitle = "蓝牙连接后，若车辆未解锁则自动解锁",
                toggle = unlockSwitch
            ),
            lp()
        )
        body.addView(createFullHairline(startInsetDp = 34), lp())

        // Sub-switch 2: Walk-Away Auto-Lock (safety condition stated once, cleanly)
        val lockSwitch = ToggleSwitch(context).apply {
            setChecked(cfg.autoLockEnabled, animate = false)
            onToggleAttempt = { next ->
                cfg = cfg.copy(autoLockEnabled = next)
                PassiveEntryStore.save(context, cfg)
                setChecked(next, animate = true)
                onConfigUpdated(cfg)
                refreshOpenSheetBlock?.invoke()
            }
        }
        body.addView(
            buildSimpleToggleRow(
                icon = AppIconType.LOCK_CLOSED,
                title = "离车自动上锁",
                subtitle = "熄火关门后远离车辆自动上锁",
                toggle = lockSwitch
            ),
            lp()
        )
        body.addView(createFullHairline(startInsetDp = 0), lp(top = dp(2)))

        // Trigger distance selector with recessed dark groove & crisp pill contrast
        val distanceHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        distanceHeaderRow.addView(
            text("触发距离", 13.5f, ThemeTokens.TEXT_BODY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val resetCalBtn = text("恢复默认", 12.5f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                cfg = cfg.copy(calibratedUnlockRssi = null)
                PassiveEntryStore.save(context, cfg)
                onConfigUpdated(cfg)
                Toast.makeText(context, "已恢复默认触发距离", Toast.LENGTH_SHORT).show()
                refreshOpenSheetBlock?.invoke()
            }
        }
        distanceHeaderRow.addView(resetCalBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        body.addView(distanceHeaderRow, lp(top = dp(12), bottom = dp(8)))

        val sensitivities = listOf(
            PassiveSensitivity.NEAR,
            PassiveSensitivity.STANDARD,
            PassiveSensitivity.FAR
        )
        val initialIdx = sensitivities.indexOf(cfg.sensitivity).coerceAtLeast(0)
        val sensitivityDescTv = text(cfg.sensitivity.description, 12f, ThemeTokens.TEXT_SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val segBar = MotionSpec.createSlidingSegmentedBar(
            context = context,
            labels = sensitivities.map { it.label },
            initial = initialIdx,
            bgColor = ThemeTokens.SEGMENT_TRACK,
            pillColor = ThemeTokens.SEGMENT_PILL,
            activeTextColor = ThemeTokens.TEXT_PRIMARY,
            inactiveTextColor = ThemeTokens.TEXT_SUB,
            trackStrokeColor = ThemeTokens.SEGMENT_TRACK_BORDER,
            pillStrokeColor = ThemeTokens.SEGMENT_PILL_BORDER
        ) { selectedIdx ->
            val picked = sensitivities.getOrElse(selectedIdx) { PassiveSensitivity.STANDARD }
            cfg = cfg.copy(sensitivity = picked, calibratedUnlockRssi = null)
            PassiveEntryStore.save(context, cfg)
            sensitivityDescTv.text = picked.description
            onConfigUpdated(cfg)
            refreshOpenSheetBlock?.invoke()
        }
        body.addView(segBar, lp())
        body.addView(sensitivityDescTv, lp(top = dp(6), bottom = dp(12)))

        // Tactile calibration button with visible border + leading crosshair icon
        val calibrateBtn = buildIconSecondaryButton(
            icon = AppIconType.CROSSHAIR,
            label = "以当前位置标定解锁距离"
        ) {
            val snap = PassiveEntryRuntime.currentSnapshot()
            val liveRssi = liveBleRssiProvider() ?: snap.filteredRssi ?: snap.currentRawRssi
            if (liveRssi == null || liveRssi !in -95..-35) {
                Toast.makeText(context, "请在车旁连接手机钥匙后再标定", Toast.LENGTH_SHORT).show()
                return@buildIconSecondaryButton
            }
            val clamped = liveRssi.coerceIn(-82, -45)
            cfg = cfg.copy(calibratedUnlockRssi = clamped)
            PassiveEntryStore.save(context, cfg)
            onConfigUpdated(cfg)
            AppHaptics.perform(outer, HapticLevel.MEDIUM_CONFIRM)
            Toast.makeText(context, "已将当前位置设为解锁距离", Toast.LENGTH_SHORT).show()
            refreshOpenSheetBlock?.invoke()
        }
        body.addView(calibrateBtn, lp(bottom = dp(14)))

        // Clean full-width divider separating calibration controls from inspection row
        body.addView(createFullHairline(startInsetDp = 0), lp())

        // Bottom inspection entry: left SHIELD_CHECK icon strictly aligned with upper toggle icons
        val prereqRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(4))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.985f, haptic = false)
            setOnClickListener {
                immediateDismiss()
                showPrerequisiteGateSheet(inspectOnly = true)
            }
        }
        prereqRow.addView(
            AppIconView(context, AppIconType.SHIELD_CHECK, ThemeTokens.TEXT_PRIMARY),
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) }
        )
        val prereqTextCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val prereqTitle = text("权限与连接检查", 15f, ThemeTokens.TEXT_PRIMARY)
        val prereqSub = text("", 12f, ThemeTokens.TEXT_SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        prereqTextCol.addView(prereqTitle, lp())
        prereqTextCol.addView(prereqSub, lp(top = dp(2)))
        prereqRow.addView(prereqTextCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        prereqRow.addView(
            AppIconView(context, AppIconType.CHEVRON_RIGHT, Color.parseColor("#6B707D")),
            LinearLayout.LayoutParams(dp(16), dp(16))
        )
        body.addView(prereqRow, lp())

        val renderLive = {
            val latestProfile = profileProvider()
            cfg = PassiveEntryStore.load(context, latestProfile.vin)
            val snap = PassiveEntryRuntime.currentSnapshot()
            val report = PassiveEntryPrerequisiteChecker.evaluate(context, latestProfile)
            val liveRssi = liveBleRssiProvider() ?: snap.filteredRssi ?: snap.currentRawRssi
            val bleReady = liveBleConnectedProvider() || snap.sessionReady

            stateBadge.text = if (snap.state == PassiveState.PAUSED_FOREGROUND) {
                if (bleReady) "已连接" else "监听中"
            } else {
                snap.state.label
            }
            statusDot.state = when {
                snap.state == PassiveState.ERROR || !report.allRequiredSatisfied -> StatusDotView.State.DISCONNECTED
                bleReady || snap.state == PassiveState.NEAR_READY || snap.state == PassiveState.UNLOCKED_NEAR ->
                    StatusDotView.State.CONNECTED
                snap.state == PassiveState.WAKING_CONNECT -> StatusDotView.State.CONNECTING
                else -> StatusDotView.State.IDLE
            }

            statusMainTv.text = when {
                !report.allRequiredSatisfied -> "还有 ${report.missingRequiredCount} 项必备设置待完成"
                snap.state == PassiveState.UNLOCKED_NEAR -> "已连接车辆 · 车辆已处于解锁状态"
                bleReady && liveRssi != null && liveRssi >= cfg.effectiveUnlockRssi -> "就在车旁 · 信号很强"
                bleReady -> "已连接车辆 · 蓝牙信号良好"
                liveRssi != null -> "已检测到附近车辆"
                else -> "未靠近车辆 · 后台省电监听中"
            }

            val eventSummary = snap.lastEventText.ifBlank { cfg.lastTriggerSummary }
            val eventTime = if (snap.lastEventAtMs > 0L) snap.lastEventAtMs else cfg.lastTriggerAtMs
            if (eventSummary.isNotBlank() && eventTime > 0L) {
                val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(eventTime))
                statusSubTv.text = "最近触发：$ts · $eventSummary"
                statusSubTv.visibility = View.VISIBLE
            } else {
                statusSubTv.visibility = View.GONE
            }

            sensitivityDescTv.text = if (cfg.calibratedUnlockRssi != null) {
                "已使用当前车旁标定位置，可随时恢复默认"
            } else {
                cfg.sensitivity.description
            }
            resetCalBtn.visibility = if (cfg.calibratedUnlockRssi != null) View.VISIBLE else View.GONE
            prereqSub.text = if (report.allRequiredSatisfied) {
                "全部必备条件已满足"
            } else {
                "缺少 ${report.missingRequiredCount} 项必备条件"
            }
        }

        val runtimeListener: (PassiveEntrySnapshot) -> Unit = {
            renderLive()
        }
        PassiveEntryRuntime.addListener(runtimeListener)
        renderLive()
        refreshOpenSheetBlock = renderLive
        startRssiPolling()

        AppSystemInsets.installBottomSheetContent(dialog, outer, dimAmount = 0.65f)
        MotionSpec.attachSheetSwipeToDismiss(outer, scroll, dialog)
        dialog.setOnDismissListener {
            PassiveEntryRuntime.removeListener(runtimeListener)
            stopRssiPolling()
            if (activeDialog === dialog) {
                activeDialog = null
                activeImmediateDismiss = null
                refreshOpenSheetBlock = null
            }
        }
        activeDialog = dialog
        activeImmediateDismiss = immediateDismiss
        dialog.show()
        MotionSpec.animateSheetEnter(outer)
    }

    private fun createAnimatedSheetDialog(sheetView: View): Pair<Dialog, () -> Unit> {
        var bypassImmediate = false
        val dialog = object : Dialog(context, R.style.Theme_CarPlus_BottomSheetDialog) {
            override fun dismiss() {
                if (!isShowing || bypassImmediate || MotionSpec.isBypassingSheetDismiss(this)) {
                    runCatching { super.dismiss() }
                    return
                }
                MotionSpec.dismissSheetAnimated(sheetView, this)
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val immediateDismiss = {
            bypassImmediate = true
            sheetView.animate().cancel()
            runCatching { if (dialog.isShowing) dialog.dismiss() }
            Unit
        }
        return dialog to immediateDismiss
    }

    private fun startRssiPolling() {
        stopRssiPolling()
        val task = object : Runnable {
            override fun run() {
                if (activeDialog?.isShowing != true) return
                if (liveBleConnectedProvider()) {
                    onRequestLiveRssi()
                }
                refreshOpenSheetBlock?.invoke()
                mainHandler.postDelayed(this, 1_500L)
            }
        }
        rssiPollRunnable = task
        mainHandler.postDelayed(task, 600L)
    }

    private fun stopRssiPolling() {
        rssiPollRunnable?.let(mainHandler::removeCallbacks)
        rssiPollRunnable = null
    }

    private fun buildIconSecondaryButton(
        icon: AppIconType,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(11f)
                setColor(ThemeTokens.BTN_SECONDARY)
                setStroke(strokePx(), ThemeTokens.BTN_SECONDARY_BORDER)
            }
            isClickable = true
            isFocusable = true
            addView(
                AppIconView(context, icon, Color.parseColor("#E2E8F0")),
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
            )
            addView(
                text(label, 14f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )
            MotionSpec.attachPressPhysics(this, pressedScale = 0.97f, haptic = false)
            setOnClickListener { onClick() }
        }
    }

    private fun buildPrerequisiteRow(item: PrerequisiteCheckItem, profile: VehicleProfile): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
        }

        val iconType = if (item.satisfied) AppIconType.CHECK_CIRCLE else AppIconType.INFO_CIRCLE
        val iconTint = when {
            item.satisfied -> Color.parseColor("#34D399")
            item.required -> Color.parseColor("#FBBF24")
            else -> ThemeTokens.TEXT_SUB
        }
        row.addView(
            AppIconView(context, iconType, iconTint),
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
        )

        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            text(item.title, 14.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )
        if (!item.required) {
            titleRow.addView(
                text("推荐", 10.5f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD).apply {
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    background = GradientDrawable().apply {
                        cornerRadius = dpF(6f)
                        setColor(Color.parseColor("#1D2E45"))
                    }
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }
            )
        }
        textCol.addView(titleRow, lp())
        textCol.addView(
            text(item.detail, 12f, ThemeTokens.TEXT_SUB).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            lp(top = dp(3))
        )
        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })

        if (item.satisfied) {
            row.addView(
                text("已就绪", 12f, Color.parseColor("#34D399"), Typeface.BOLD),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )
        } else {
            val fixBtn = text(
                item.actionLabel,
                12.5f,
                if (item.required) ThemeTokens.TEXT_PRIMARY else ThemeTokens.ACCENT_LIGHT,
                Typeface.BOLD
            ).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dpF(15f)
                    setColor(if (item.required) ThemeTokens.BTN_SECONDARY else Color.parseColor("#1D2E45"))
                    setStroke(strokePx(), if (item.required) ThemeTokens.BTN_SECONDARY_BORDER else Color.parseColor("#2E4870"))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                    onFixPrerequisite(item.id, profile)
                }
            }
            MotionSpec.attachPressPhysics(fixBtn, pressedScale = 0.94f, haptic = false)
            row.addView(fixBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        return row
    }

    private fun buildSimpleToggleRow(
        icon: AppIconType,
        title: String,
        subtitle: String,
        toggle: ToggleSwitch
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(
            AppIconView(context, icon, ThemeTokens.TEXT_PRIMARY),
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) }
        )
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15.5f, ThemeTokens.TEXT_PRIMARY), lp())
        col.addView(
            text(subtitle, 12f, ThemeTokens.TEXT_SUB).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            lp(top = dp(2))
        )
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        row.addView(toggle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return row
    }

    private fun createFullHairline(startInsetDp: Int): View {
        val hPx = (0.7f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        return View(context).apply {
            setBackgroundColor(ThemeTokens.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, hPx).apply {
                marginStart = dp(startInsetDp)
            }
        }
    }

    private fun createMaxHeightScrollView(maxHeightPx: Int): ScrollView {
        return object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val parentMode = MeasureSpec.getMode(heightMeasureSpec)
                val parentSize = MeasureSpec.getSize(heightMeasureSpec)
                val effectiveMax = if (parentMode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(parentSize, maxHeightPx)
                val mode = if (parentMode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(effectiveMax, mode))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    private fun createSheetShell(): LinearLayout {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeTokens.BG_SURFACE)
                cornerRadii = floatArrayOf(
                    dpF(22f), dpF(22f), dpF(22f), dpF(22f),
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(dp(20), dp(10), dp(20), dp(20))
        }
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4C515E"))
                cornerRadius = dpF(2f)
            }
        }
        outer.addView(
            handle,
            LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        )
        return outer
    }

    private fun text(
        content: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(context).apply {
        text = content
        textSize = sizeSp
        setTextColor(color)
        setTypeface(Typeface.SANS_SERIF, style)
    }

    private fun strokePx(): Int =
        (0.9f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    private fun dpF(value: Float): Float = value * context.resources.displayMetrics.density
    private fun lp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = top
            bottomMargin = bottom
        }
}
