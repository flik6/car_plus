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
import com.chery.carplus.ble.PrerequisiteCheckItem
import com.chery.carplus.ble.PrerequisiteId
import com.chery.carplus.profile.VehicleProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Minimalist dark bottom sheets for Bluetooth Passive Unlock / Walk-Away Lock:
 * 1. Prerequisite gate checklist sheet (blocks enabling until all required items pass).
 * 2. Passive entry strategy & live RSSI calibration sheet.
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
    private var refreshOpenSheetBlock: (() -> Unit)? = null
    private var rssiPollRunnable: Runnable? = null

    fun refreshIfOpen() {
        mainHandler.post { refreshOpenSheetBlock?.invoke() }
    }

    fun dismiss() {
        stopRssiPolling()
        refreshOpenSheetBlock = null
        activeDialog?.dismiss()
        activeDialog = null
    }

    /**
     * Shows the prerequisite gate bottom sheet. When [inspectOnly] is false, users can proceed
     * to PIN verification and enable passive entry as soon as all required items are satisfied.
     */
    fun showPrerequisiteGateSheet(
        inspectOnly: Boolean = false,
        onEnabledCallback: ((Boolean) -> Unit)? = null
    ) {
        dismiss()
        val dialog = Dialog(context, R.style.Theme_CarPlus_BottomSheetDialog)
        val outer = createSheetShell()

        val titleTv = text(
            if (inspectOnly) "无感前置条件与通道自检" else "开启无感需满足前置条件",
            19f,
            ThemeTokens.TEXT_PRIMARY,
            Typeface.BOLD
        )
        val subtitleTv = text("", 13f, ThemeTokens.TEXT_SUB).apply {
            setLineSpacing(dpF(2f), 1f)
        }
        outer.addView(titleTv, lp(top = dp(6)))
        outer.addView(subtitleTv, lp(top = dp(5), bottom = dp(12)))

        val maxScrollH = (context.resources.displayMetrics.heightPixels * 0.56f).roundToInt()
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val listBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listBody, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            height = WRAP_CONTENT
        })
        scroll.post {
            if (scroll.height > maxScrollH) {
                scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                    height = maxScrollH
                }
            }
        }

        val ctaBtn = AppComponents.createActionButton(
            context = context,
            label = "完成必备条件后开启",
            style = ButtonStyle.PRIMARY
        ) {}
        outer.addView(ctaBtn, lp(top = dp(14)))

        val render = {
            val profile = profileProvider()
            val report = PassiveEntryPrerequisiteChecker.evaluate(context, profile)
            val requiredTotal = report.items.count { it.required }
            val requiredDone = report.items.count { it.required && it.satisfied }
            subtitleTv.text = if (report.allRequiredSatisfied) {
                "已满足全部 $requiredTotal 项必备条件 · ${report.wakeChannelMode}"
            } else {
                "已完成 $requiredDone / $requiredTotal 项必备条件，请依次完成以下待处理项"
            }

            listBody.removeAllViews()
            report.items.forEachIndexed { idx, item ->
                listBody.addView(buildPrerequisiteRow(item, profile), lp())
                if (idx < report.items.lastIndex) {
                    listBody.addView(AppComponents.createHairline(context, startInsetDp = 34), lp())
                }
            }

            if (inspectOnly) {
                ctaBtn.text = "完成"
                ctaBtn.alpha = 1f
                ctaBtn.isEnabled = true
                ctaBtn.setOnClickListener { dialog.dismiss() }
            } else if (report.allRequiredSatisfied) {
                ctaBtn.text = "验证 PIN 码并开启无感"
                ctaBtn.alpha = 1f
                ctaBtn.isEnabled = true
                ctaBtn.setOnClickListener {
                    AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                    dialog.dismiss()
                    onProceedEnableAfterPrerequisites(profile) { enabled ->
                        onEnabledCallback?.invoke(enabled)
                    }
                }
            } else {
                ctaBtn.text = "还需完成 ${report.missingRequiredCount} 项必备条件"
                ctaBtn.alpha = 0.45f
                ctaBtn.isEnabled = false
                ctaBtn.setOnClickListener(null)
            }
        }

        render()
        refreshOpenSheetBlock = render

        AppSystemInsets.installBottomSheetContent(dialog, outer, dimAmount = 0.68f)
        MotionSpec.attachSheetSwipeToDismiss(outer, scroll, dialog)
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                refreshOpenSheetBlock = null
            }
        }
        activeDialog = dialog
        dialog.show()
    }

    /**
     * Shows the passive entry strategy & live RSSI calibration bottom sheet.
     */
    fun showPassiveEntryConfigSheet() {
        dismiss()
        val profile = profileProvider()
        if (profile.vin.isBlank()) {
            Toast.makeText(context, "当前暂无有效车辆信息", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(context, R.style.Theme_CarPlus_BottomSheetDialog)
        val outer = createSheetShell()

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            text("蓝牙无感解锁/上锁", 19f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val stateBadge = text("", 11.5f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD).apply {
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dpF(10f)
                setColor(Color.parseColor("#1D2E45"))
            }
        }
        titleRow.addView(stateBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        outer.addView(titleRow, lp(top = dp(6)))

        val channelInfoTv = text("", 12.5f, ThemeTokens.TEXT_SUB).apply {
            setLineSpacing(dpF(2f), 1f)
        }
        outer.addView(channelInfoTv, lp(top = dp(5), bottom = dp(10)))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        var cfg = PassiveEntryStore.load(context, profile.vin)

        // Live signal & threshold summary strip
        val rssiSummaryBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dpF(12f)
                setColor(Color.parseColor("#22242A"))
            }
        }
        val rssiLiveTv = text("", 13.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD)
        val rssiThreshTv = text("", 12f, ThemeTokens.TEXT_SUB).apply {
            setLineSpacing(dpF(2f), 1f)
        }
        val lastEventTv = text("", 11.5f, ThemeTokens.TEXT_SUB).apply {
            visibility = View.GONE
        }
        rssiSummaryBox.addView(rssiLiveTv, lp())
        rssiSummaryBox.addView(rssiThreshTv, lp(top = dp(4)))
        rssiSummaryBox.addView(lastEventTv, lp(top = dp(4)))
        body.addView(rssiSummaryBox, lp(bottom = dp(10)))

        // Sub-switch 1: Approach Auto-Unlock
        val unlockSubTv = text("", 12.5f, ThemeTokens.TEXT_SUB)
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
                subtitleView = unlockSubTv,
                toggle = unlockSwitch
            ),
            lp()
        )
        body.addView(AppComponents.createHairline(context, startInsetDp = 36), lp())

        // Sub-switch 2: Walk-Away Auto-Lock
        val lockSubTv = text("", 12.5f, ThemeTokens.TEXT_SUB)
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
                subtitleView = lockSubTv,
                toggle = lockSwitch
            ),
            lp()
        )
        body.addView(AppComponents.createHairline(context, startInsetDp = 36), lp())

        // Sensitivity segmented selector
        body.addView(
            text("触发距离灵敏度", 13f, ThemeTokens.TEXT_SUB, Typeface.BOLD),
            lp(top = dp(14), bottom = dp(8))
        )
        val sensitivities = listOf(
            PassiveSensitivity.NEAR,
            PassiveSensitivity.STANDARD,
            PassiveSensitivity.FAR
        )
        val initialIdx = sensitivities.indexOf(cfg.sensitivity).coerceAtLeast(0)
        val sensitivityDescTv = text(cfg.sensitivity.description, 12f, ThemeTokens.TEXT_SUB)
        val segBar = MotionSpec.createSlidingSegmentedBar(
            context = context,
            labels = sensitivities.map { it.label },
            initial = initialIdx,
            bgColor = Color.parseColor("#25272E"),
            pillColor = Color.parseColor("#353944"),
            activeTextColor = ThemeTokens.TEXT_PRIMARY,
            inactiveTextColor = ThemeTokens.TEXT_SUB
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

        // On-site calibration buttons
        val calRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val calibrateBtn = AppComponents.createActionButton(
            context = context,
            label = "站在车门旁一键标定解锁距离",
            style = ButtonStyle.SECONDARY
        ) {
            val snap = PassiveEntryRuntime.currentSnapshot()
            val liveRssi = liveBleRssiProvider() ?: snap.filteredRssi ?: snap.currentRawRssi
            if (liveRssi == null || liveRssi !in -95..-35) {
                Toast.makeText(context, "请先在车旁建立蓝牙直连后再标定信号", Toast.LENGTH_SHORT).show()
                return@createActionButton
            }
            val clamped = liveRssi.coerceIn(-82, -45)
            cfg = cfg.copy(calibratedUnlockRssi = clamped)
            PassiveEntryStore.save(context, cfg)
            onConfigUpdated(cfg)
            AppHaptics.perform(calRow, HapticLevel.MEDIUM_CONFIRM)
            Toast.makeText(
                context,
                "已标定：靠近 ${cfg.effectiveUnlockRssi} dBm 解锁，离开 ${cfg.effectiveLockRssi} dBm 上锁",
                Toast.LENGTH_SHORT
            ).show()
            refreshOpenSheetBlock?.invoke()
        }
        val resetCalBtn = text("恢复默认", 13f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD).apply {
            setPadding(dp(12), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                cfg = cfg.copy(calibratedUnlockRssi = null)
                PassiveEntryStore.save(context, cfg)
                onConfigUpdated(cfg)
                Toast.makeText(context, "已恢复预设距离阈值", Toast.LENGTH_SHORT).show()
                refreshOpenSheetBlock?.invoke()
            }
        }
        calRow.addView(calibrateBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        calRow.addView(resetCalBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        body.addView(calRow, lp(bottom = dp(12)))

        body.addView(AppComponents.createHairline(context, startInsetDp = 0), lp())

        // Prerequisite & wake channel inspection row
        val prereqRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                showPrerequisiteGateSheet(inspectOnly = true)
            }
        }
        val prereqTextCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val prereqTitle = text("前置条件与唤醒通道检查", 15f, ThemeTokens.TEXT_PRIMARY)
        val prereqSub = text("", 12.5f, ThemeTokens.TEXT_SUB)
        prereqTextCol.addView(prereqTitle, lp())
        prereqTextCol.addView(prereqSub, lp(top = dp(2)))
        prereqRow.addView(prereqTextCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        prereqRow.addView(
            AppIconView(context, AppIconType.CHEVRON_RIGHT, ThemeTokens.TEXT_SUB),
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

            stateBadge.text = snap.state.label
            channelInfoTv.text = "唤醒通道：${report.wakeChannelMode} · 仅在熄火且车门关闭时执行离车上锁"
            rssiLiveTv.text = when {
                bleReady && liveRssi != null -> "当前实时信号：$liveRssi dBm（蓝牙数字钥匙已直连）"
                liveRssi != null -> "最近蓝牙信号：$liveRssi dBm"
                else -> "当前信号：未靠近车辆（休眠低功耗监听）"
            }
            val calTag = if (cfg.calibratedUnlockRssi != null) "（已现场标定）" else "（${cfg.sensitivity.label}预设）"
            rssiThreshTv.text = "解锁阈值 ≥ ${cfg.effectiveUnlockRssi} dBm  ·  上锁阈值 ≤ ${cfg.effectiveLockRssi} dBm $calTag"
            unlockSubTv.text = "连续靠近达到 ${cfg.effectiveUnlockRssi} dBm 时自动解锁"
            lockSubTv.text = "离开衰减至 ${cfg.effectiveLockRssi} dBm 且熄火关门后上锁"
            resetCalBtn.visibility = if (cfg.calibratedUnlockRssi != null) View.VISIBLE else View.GONE
            prereqSub.text = report.summaryText

            val eventSummary = snap.lastEventText.ifBlank { cfg.lastTriggerSummary }
            val eventTime = if (snap.lastEventAtMs > 0L) snap.lastEventAtMs else cfg.lastTriggerAtMs
            if (eventSummary.isNotBlank() && eventTime > 0L) {
                val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(eventTime))
                lastEventTv.text = "最近触发：$ts · $eventSummary"
                lastEventTv.visibility = View.VISIBLE
            } else {
                lastEventTv.visibility = View.GONE
            }
        }

        val runtimeListener: (PassiveEntrySnapshot) -> Unit = {
            renderLive()
        }
        PassiveEntryRuntime.addListener(runtimeListener)
        renderLive()
        refreshOpenSheetBlock = renderLive
        startRssiPolling()

        AppSystemInsets.installBottomSheetContent(dialog, outer, dimAmount = 0.68f)
        MotionSpec.attachSheetSwipeToDismiss(outer, scroll, dialog)
        dialog.setOnDismissListener {
            PassiveEntryRuntime.removeListener(runtimeListener)
            stopRssiPolling()
            if (activeDialog === dialog) {
                activeDialog = null
                refreshOpenSheetBlock = null
            }
        }
        activeDialog = dialog
        dialog.show()
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

    private fun buildPrerequisiteRow(item: PrerequisiteCheckItem, profile: VehicleProfile): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
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
                setLineSpacing(dpF(2f), 1f)
            },
            lp(top = dp(3))
        )
        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })

        if (item.satisfied) {
            row.addView(
                text("已满足", 12f, Color.parseColor("#34D399"), Typeface.BOLD),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )
        } else {
            val fixBtn = text(
                item.actionLabel,
                12.5f,
                if (item.required) ThemeTokens.TEXT_PRIMARY else ThemeTokens.ACCENT_LIGHT,
                Typeface.BOLD
            ).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dpF(16f)
                    setColor(if (item.required) Color.parseColor("#2B303B") else Color.parseColor("#1D2E45"))
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
        subtitleView: TextView,
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
        col.addView(subtitleView, lp(top = dp(2)))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        row.addView(toggle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return row
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
            setPadding(dp(20), dp(10), dp(20), dp(22))
        }
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#474B55"))
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

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    private fun dpF(value: Float): Float = value * context.resources.displayMetrics.density
    private fun lp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = top
            bottomMargin = bottom
        }
}
