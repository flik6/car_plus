package com.chery.carplus.ui

import android.animation.ValueAnimator
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.chery.carplus.R
import com.chery.carplus.cloud.AuthorizationOperationRecordItem
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.CherySessionExpiredException
import com.chery.carplus.cloud.VehicleAuthorizationItem
import com.chery.carplus.cloud.VehicleSummary
import com.chery.carplus.profile.VehicleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen minimalist dark automotive management flows with strict Level-1 / Level-2
 * information hierarchy, consistent entry-to-topbar naming, and default-collapsed danger zones:
 *
 * 1. 「驾驶员」 (Level 1: 1 clean card per driver) -> 「驾驶员详情」 (Level 2: enable toggle 905,
 *    inline edit 903, operation records 137, default-hidden danger revoke 904) / 「添加驾驶员」 (902).
 * 2. 「车辆管理」 (Level 1: 1 clean card per vehicle) -> 「车辆详情」 (Level 2: hardware archive,
 *    inline owner/plate edit 933, set default 932, default-hidden danger cancel cert 934 & unbind 931)
 *    / 「添加车辆」 (930).
 */
internal class VehicleManagementScreens(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val httpClient: CheryHttpClient,
    private val profileProvider: () -> VehicleProfile,
    private val activeModelNameProvider: () -> String,
    private val activePlateNoProvider: () -> String = { "" },
    private val onPushOverlaySubpage: (view: View, onDestroy: (() -> Unit)?) -> Unit,
    private val onPopOverlaySubpage: () -> Unit,
    private val onSelectVehicleSummary: (VehicleSummary) -> Unit,
    private val onVehicleInfoSaved: (vin: String, plateNo: String, hostName: String, purchaseDate: String) -> Unit,
    private val onVehicleUnbound: (vin: String) -> Unit,
    private val onStatusMessage: (String) -> Unit
) {

    private var activeDialog: Dialog? = null

    fun dismissAll() {
        runCatching { activeDialog?.dismiss() }
        activeDialog = null
    }

    // =========================================================================
    // 1. 「驾驶员」一级界面 (TopBar: 驾驶员) -> 二级界面「驾驶员详情」/「添加驾驶员」
    // =========================================================================

    fun openRemoteAuthManagementScreen(
        targetVinOverride: String? = null,
        targetModelOverride: String? = null,
        targetPlateOverride: String? = null
    ) = openDriversScreen(targetVinOverride, targetModelOverride, targetPlateOverride)

    fun openDriversScreen(
        targetVinOverride: String? = null,
        targetModelOverride: String? = null,
        targetPlateOverride: String? = null
    ) {
        val profile = profileProvider()
        val targetVin = targetVinOverride?.trim()?.uppercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: profile.vin.trim().uppercase(Locale.US)
        val targetModel = targetModelOverride?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: activeModelNameProvider().ifBlank { "我的座驾" }
        val targetPlate = targetPlateOverride?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: if (targetVin.equals(profile.vin.trim(), ignoreCase = true)) activePlateNoProvider().trim() else ""

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }

        // Strict title consistency: Home entry "驾驶员" -> TopBar "驾驶员"
        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "驾驶员",
                onBack = { onPopOverlaySubpage() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Compact vehicle & driver count summary header card
        val summaryBadge = TextView(context).apply {
            text = "同步中"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.ACCENT_LIGHT)
            background = rounded(ACCENT_TINT_BG, 8f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val overviewCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val iconFrame = FrameLayout(context).apply {
            background = rounded(TILE_BG, 12f)
            addView(
                AppIconView(context, AppIconType.KEY_ROUND, ThemeTokens.TEXT_PRIMARY),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            )
        }
        overviewCard.addView(iconFrame, LinearLayout.LayoutParams(dp(40), dp(40)))

        val overviewTextCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        overviewTextCol.addView(
            text(targetModel, 16f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        val subLine = listOfNotNull(
            targetPlate.takeIf { it.isNotBlank() },
            maskVin(targetVin).takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "当前车辆驾驶员管理" }
        overviewTextCol.addView(
            text(subLine, 12.5f, ThemeTokens.TEXT_SUB).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            lp(top = dp(3))
        )
        overviewCard.addView(
            overviewTextCol,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(10)
            }
        )
        overviewCard.addView(summaryBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        body.addView(overviewCard, lp(bottom = dp(14)))

        val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listHost, lp())

        var reloadList: () -> Unit = {}
        reloadList = {
            listHost.removeAllViews()
            listHost.addView(loadingHint("正在加载驾驶员列表..."))
            val current = profileProvider()
            if (targetVin.isBlank() || current.accessToken.isBlank()) {
                summaryBadge.text = "未绑定"
                listHost.removeAllViews()
                listHost.addView(buildEmptyDriversGuideCard(hasVehicle = false))
            } else {
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.queryRemoteAuthorizations(
                                accessToken = current.accessToken,
                                vin = targetVin
                            )
                        }
                    }
                    listHost.removeAllViews()
                    result.onSuccess { items ->
                        val activeCount = items.count { it.enabled }
                        summaryBadge.text = if (items.isEmpty()) {
                            "0 位驾驶员"
                        } else {
                            "启用 $activeCount / ${items.size}"
                        }
                        if (items.isEmpty()) {
                            listHost.addView(buildEmptyDriversGuideCard(hasVehicle = true))
                        } else {
                            items.forEachIndexed { idx, item ->
                                listHost.addView(
                                    buildDriverSummaryCard(
                                        item = item,
                                        vin = targetVin,
                                        modelName = targetModel,
                                        onChanged = reloadList
                                    ),
                                    lp(top = if (idx == 0) 0 else dp(12))
                                )
                            }
                        }
                    }.onFailure { err ->
                        if (err is CherySessionExpiredException) return@onFailure
                        summaryBadge.text = "加载异常"
                        listHost.addView(errorBanner("驾驶员列表加载失败：${err.message.orEmpty().take(90)}"))
                        listHost.addView(
                            AppComponents.createActionButton(context, "重新加载", ButtonStyle.SECONDARY) {
                                reloadList()
                            },
                            lp(top = dp(12), height = dp(44))
                        )
                    }
                }
            }
        }

        // Pinned bottom CTA: ＋ 添加驾驶员
        root.addView(
            buildPinnedBottomCta("＋ 添加驾驶员") {
                if (targetVin.isBlank()) {
                    toast("当前暂无可授权车辆")
                    return@buildPinnedBottomCta
                }
                openAddDriverScreen(vin = targetVin, onSaved = { reloadList() })
            },
            lp()
        )

        onPushOverlaySubpage(root, null)
        reloadList()
    }

    /**
     * Level-1 minimalist driver card: essential identity, status, mobile, permission & validity only.
     * All management actions (toggle 905, edit 903, records 137, danger revoke 904) live in Level 2.
     */
    private fun buildDriverSummaryCard(
        item: VehicleAuthorizationItem,
        vin: String,
        modelName: String,
        onChanged: () -> Unit
    ): LinearLayout {
        val granteeName = item.receiverAccountName.ifBlank { item.receiverMobile.ifBlank { "驾驶员" } }
        val isControlLevel = item.levelFlag == "1" || item.authority == "1"

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)
            setOnClickListener {
                openDriverDetailScreen(
                    item = item,
                    vin = vin,
                    modelName = modelName,
                    onChanged = onChanged
                )
            }
        }

        // Top Row: Name + Status Pill (left) | Right Chevron
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            text(granteeName, 16.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        topRow.addView(
            TextView(context).apply {
                text = if (item.enabled) "已启用" else "已停用"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (item.enabled) SUCCESS_GREEN else ThemeTokens.TEXT_SUB)
                background = rounded(if (item.enabled) SUCCESS_TINT_BG else MUTED_TINT_BG, 6f)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
        )
        topRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        topRow.addView(
            AppIconView(context, AppIconType.CHEVRON_RIGHT, Color.parseColor("#676C78")),
            LinearLayout.LayoutParams(dp(16), dp(16))
        )
        card.addView(topRow, lp())

        // Middle Row: Mobile + Permission Pill
        val midRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        midRow.addView(
            text(item.receiverMobile.ifBlank { "--" }, 13.5f, ThemeTokens.TEXT_BODY),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        midRow.addView(
            TextView(context).apply {
                text = item.permissionLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isControlLevel) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_BODY)
                background = rounded(if (isControlLevel) ACCENT_TINT_BG else MUTED_TINT_BG, 7f)
                setPadding(dp(9), dp(3), dp(9), dp(3))
            }
        )
        card.addView(midRow, lp(top = dp(10)))

        // Bottom Row: Validity period
        val period = "${formatAuthTime(item.startTime)} ~ ${formatAuthTime(item.endTime)}"
        card.addView(
            text(period, 12f, ThemeTokens.TEXT_SUB).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            lp(top = dp(8))
        )

        return card
    }

    /**
     * Level-2 Driver Detail screen (TopBar: 驾驶员详情):
     * - Enable switch (905)
     * - Edit driver name & period (903)
     * - Operation records entry (137)
     * - Default-hidden collapsible Danger Zone: Revoke driver authorization (904) with confirm dialog
     */
    private fun openDriverDetailScreen(
        item: VehicleAuthorizationItem,
        vin: String,
        modelName: String,
        onChanged: () -> Unit
    ) {
        var currentEnabled = item.enabled
        val granteeName = item.receiverAccountName.ifBlank { item.receiverMobile.ifBlank { "驾驶员" } }
        val isControlLevel = item.levelFlag == "1" || item.authority == "1"
        var startValue = formatAuthTime(item.startTime).takeUnless { it == "--" }.orEmpty()
        var endValue = formatAuthTime(item.endTime).takeUnless { it == "--" }.orEmpty()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }
        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "驾驶员详情",
                onBack = { onPopOverlaySubpage() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(34))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Card 1: Driver Status & Enable Switch (905)
        val statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val statusBadge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        fun applyStatusBadge(enabled: Boolean) {
            statusBadge.text = if (enabled) "已启用" else "已停用"
            statusBadge.setTextColor(if (enabled) SUCCESS_GREEN else ThemeTokens.TEXT_SUB)
            statusBadge.background = rounded(if (enabled) SUCCESS_TINT_BG else MUTED_TINT_BG, 6f)
        }
        applyStatusBadge(currentEnabled)

        val identityHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameTitleTv = text(granteeName, 18.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD)
        identityHeader.addView(nameTitleTv)
        identityHeader.addView(
            statusBadge,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
        )
        identityHeader.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        identityHeader.addView(
            TextView(context).apply {
                text = item.permissionLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isControlLevel) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_BODY)
                background = rounded(if (isControlLevel) ACCENT_TINT_BG else MUTED_TINT_BG, 7f)
                setPadding(dp(9), dp(3), dp(9), dp(3))
            }
        )
        statusCard.addView(identityHeader, lp())

        val metaSub = listOfNotNull(
            modelName.takeIf { it.isNotBlank() },
            item.receiverMobile.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        statusCard.addView(text(metaSub, 13f, ThemeTokens.TEXT_SUB), lp(top = dp(6)))

        statusCard.addView(
            View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) },
            lp(top = dp(14), bottom = dp(14), height = dpF(0.7f).roundToInt().coerceAtLeast(1))
        )

        var toggling = false
        val enableSwitch = ToggleSwitch(context).apply {
            checked = currentEnabled
            onToggleAttempt = { targetEnabled ->
                if (!toggling) {
                    toggling = true
                    setChecked(targetEnabled, animate = true)
                    applyStatusBadge(targetEnabled)
                    val active = profileProvider()
                    scope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.toggleRemoteAuthorization(
                                    accessToken = active.accessToken,
                                    keyId = item.keyId,
                                    enabled = targetEnabled,
                                    vin = vin
                                )
                            }
                        }
                        toggling = false
                        res.onSuccess { ok ->
                            if (ok) {
                                currentEnabled = targetEnabled
                                toast(if (targetEnabled) "已启用该驾驶员权限" else "已停用该驾驶员权限")
                                onChanged()
                            } else {
                                setChecked(currentEnabled, animate = true)
                                applyStatusBadge(currentEnabled)
                                toast("状态切换失败")
                            }
                        }.onFailure { err ->
                            setChecked(currentEnabled, animate = true)
                            applyStatusBadge(currentEnabled)
                            if (err !is CherySessionExpiredException) {
                                toast("操作失败：${err.message.orEmpty().take(70)}")
                            }
                        }
                    }
                }
            }
        }
        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggleCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        toggleCol.addView(text("启用该驾驶员权限", 15f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD))
        toggleCol.addView(text("关闭后将临时暂停对方的查车与控车能力", 12f, ThemeTokens.TEXT_SUB), lp(top = dp(3)))
        toggleRow.addView(toggleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        toggleRow.addView(enableSwitch, LinearLayout.LayoutParams(dp(46), dp(26)).apply { marginStart = dp(12) })
        statusCard.addView(toggleRow, lp())
        body.addView(statusCard, lp())

        // Card 2: Edit Driver Info (903)
        val editCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        editCard.addView(text("编辑驾驶员信息", 15.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD), lp(bottom = dp(14)))

        editCard.addView(formFieldHeader("驾驶员姓名", required = true))
        val (nameBox, nameInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "请输入驾驶员姓名",
            initialValue = item.receiverAccountName,
            inputType = InputType.TYPE_CLASS_TEXT,
            maxLength = 20
        )
        editCard.addView(nameBox, lp(top = dp(2)))

        editCard.addView(formFieldHeader("手机号", required = false, note = "不可修改"), lp(top = dp(16)))
        val (mobileBox, mobileInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "",
            initialValue = item.receiverMobile,
            inputType = InputType.TYPE_CLASS_PHONE
        )
        mobileInput.isEnabled = false
        mobileInput.setTextColor(ThemeTokens.TEXT_SUB)
        editCard.addView(mobileBox, lp(top = dp(2)))

        editCard.addView(formFieldHeader("开始时间", required = true), lp(top = dp(16)))
        val (startRow, getStartVal) = createMinuteDateTimePickerField(
            hint = "请选择开始时间",
            initialValue = startValue
        ) { startValue = it }
        editCard.addView(startRow, lp(top = dp(2)))

        editCard.addView(formFieldHeader("结束时间", required = true), lp(top = dp(16)))
        val (endRow, getEndVal) = createMinuteDateTimePickerField(
            hint = "请选择结束时间",
            initialValue = endValue
        ) { endValue = it }
        editCard.addView(endRow, lp(top = dp(2)))

        var saving = false
        val saveBtn = AppComponents.createActionButton(context, "保存修改", ButtonStyle.PRIMARY) {
            if (saving) return@createActionButton
            val cleanName = nameInput.text.toString().trim()
            val cleanStart = getStartVal().trim()
            val cleanEnd = getEndVal().trim()
            if (cleanName.isBlank()) {
                AppHaptics.perform(editCard, HapticLevel.ERROR_REJECT)
                toast("请输入驾驶员姓名")
                return@createActionButton
            }
            if (cleanStart.isBlank() || cleanEnd.isBlank() || compareMinuteDateTime(cleanEnd, cleanStart) <= 0) {
                AppHaptics.perform(editCard, HapticLevel.ERROR_REJECT)
                toast("结束时间必须晚于开始时间")
                return@createActionButton
            }
            val active = profileProvider()
            saving = true
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.updateRemoteAuthorization(
                            accessToken = active.accessToken,
                            keyId = item.keyId,
                            vin = vin,
                            mobile = item.receiverMobile,
                            name = cleanName,
                            startTime = cleanStart,
                            endTime = cleanEnd
                        )
                    }
                }
                saving = false
                res.onSuccess { ok ->
                    if (ok) {
                        AppHaptics.perform(editCard, HapticLevel.MEDIUM_CONFIRM)
                        nameTitleTv.text = cleanName
                        toast("驾驶员信息已更新")
                        onChanged()
                    } else {
                        toast("保存修改失败")
                    }
                }.onFailure { err ->
                    if (err !is CherySessionExpiredException) {
                        toast("保存失败：${err.message.orEmpty().take(80)}")
                    }
                }
            }
        }
        editCard.addView(saveBtn, lp(top = dp(20), height = dp(46)))
        body.addView(editCard, lp(top = dp(12)))

        // Card 3: Operation Records Entry (137)
        val recordsCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)
            setOnClickListener {
                openDriverOperationRecordsScreen(item = item, vin = vin)
            }
        }
        val recIconBox = FrameLayout(context).apply {
            background = rounded(TILE_BG, 10f)
            addView(
                AppIconView(context, AppIconType.CLOCK, ThemeTokens.TEXT_PRIMARY),
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            )
        }
        recordsCard.addView(recIconBox, LinearLayout.LayoutParams(dp(36), dp(36)))
        val recTextCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        recTextCol.addView(text("操作记录", 15f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD))
        recTextCol.addView(text("查看该驾驶员的远程控车记录", 12f, ThemeTokens.TEXT_SUB), lp(top = dp(2)))
        recordsCard.addView(
            recTextCol,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        )
        recordsCard.addView(
            AppIconView(context, AppIconType.CHEVRON_RIGHT, Color.parseColor("#676C78")),
            LinearLayout.LayoutParams(dp(16), dp(16))
        )
        body.addView(recordsCard, lp(top = dp(12)))

        // Card 4: Default-Hidden Collapsible Danger Zone -> Revoke Driver (904)
        val dangerSection = createCollapsibleDangerSection(title = "危险操作") { content ->
            content.addView(
                text("撤销后对方将立即失去该车辆的查车与远程控车权限。", 12.5f, ThemeTokens.TEXT_SUB).apply {
                    setLineSpacing(dpF(2f), 1f)
                },
                lp(bottom = dp(12))
            )
            val revokeBtn = TextView(context).apply {
                text = "撤销该驾驶员授权"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(ThemeTokens.DANGER)
                background = rounded(DANGER_TINT_BG, 11f, DANGER_BORDER, 0.8f)
                MotionSpec.attachPressPhysics(this, pressedScale = 0.96f)
                setOnClickListener {
                    activeDialog?.dismiss()
                    activeDialog = AppComponents.showConfirmDialog(
                        context = context,
                        title = "撤销驾驶员授权",
                        message = "确认撤销「${nameTitleTv.text}」的驾驶员权限？撤销后对方将无法查看车况或远程控车。",
                        confirmText = "确认撤销",
                        cancelText = "取消",
                        dangerConfirm = true
                    ) {
                        val active = profileProvider()
                        scope.launch {
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    httpClient.deleteRemoteAuthorization(
                                        accessToken = active.accessToken,
                                        keyId = item.keyId,
                                        vin = vin
                                    )
                                }
                            }
                            res.onSuccess { ok ->
                                if (ok) {
                                    AppHaptics.perform(root, HapticLevel.MEDIUM_CONFIRM)
                                    toast("已撤销驾驶员授权")
                                    onPopOverlaySubpage()
                                    onChanged()
                                } else {
                                    toast("撤销失败，请重试")
                                }
                            }.onFailure { err ->
                                if (err !is CherySessionExpiredException) {
                                    toast("撤销失败：${err.message.orEmpty().take(80)}")
                                }
                            }
                        }
                    }
                }
            }
            content.addView(revokeBtn, lp(height = dp(44)))
        }
        body.addView(dangerSection, lp(top = dp(14)))

        onPushOverlaySubpage(root, null)
    }

    /**
     * Level-2 Add Driver screen (TopBar: 添加驾驶员, service 902).
     */
    private fun openAddDriverScreen(
        vin: String,
        onSaved: () -> Unit
    ) {
        val (defaultStart, defaultEnd) = defaultAuthWindowMinutes()
        var startValue = defaultStart
        var endValue = defaultEnd
        var selectedLevel = "1"

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "添加驾驶员",
                onBack = { onPopOverlaySubpage() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val formCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(18), dp(18), dp(18), dp(20))
        }

        formCard.addView(formFieldHeader("驾驶员姓名", required = true))
        val (nameBox, nameInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "请输入驾驶员姓名",
            inputType = InputType.TYPE_CLASS_TEXT,
            maxLength = 20
        )
        formCard.addView(nameBox, lp(top = dp(2)))

        formCard.addView(formFieldHeader("手机号", required = true), lp(top = dp(18)))
        val (mobileBox, mobileInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "请输入 11 位手机号",
            inputType = InputType.TYPE_CLASS_PHONE,
            maxLength = 11
        )
        formCard.addView(mobileBox, lp(top = dp(2)))

        formCard.addView(formFieldHeader("开始时间", required = true), lp(top = dp(18)))
        val (startRow, getStartVal) = createMinuteDateTimePickerField(
            hint = "请选择开始时间",
            initialValue = startValue
        ) { startValue = it }
        formCard.addView(startRow, lp(top = dp(2)))

        formCard.addView(formFieldHeader("结束时间", required = true), lp(top = dp(18)))
        val (endRow, getEndVal) = createMinuteDateTimePickerField(
            hint = "请选择结束时间",
            initialValue = endValue
        ) { endValue = it }
        formCard.addView(endRow, lp(top = dp(2)))

        formCard.addView(formFieldHeader("车控密码", required = true), lp(top = dp(18)))
        val (pinRow, pinInput) = createPasswordToggleUnderlineInput("请输入 6 位车控密码", maxLength = 6)
        formCard.addView(pinRow, lp(top = dp(2)))

        formCard.addView(formFieldHeader("确认密码", required = true), lp(top = dp(18)))
        val (confirmRow, confirmPinInput) = createPasswordToggleUnderlineInput("请再次输入车控密码", maxLength = 6)
        formCard.addView(confirmRow, lp(top = dp(2)))

        formCard.addView(formFieldHeader("授权权限", required = true), lp(top = dp(20)))
        val permRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val optViewOnly = createPermissionOptionButton("车况查看")
        val optViewAndControl = createPermissionOptionButton("车况查看+远程控车")

        fun renderPermSelection() {
            stylePermissionOptionButton(optViewOnly, selected = selectedLevel == "0", disabled = false)
            stylePermissionOptionButton(optViewAndControl, selected = selectedLevel == "1", disabled = false)
        }
        renderPermSelection()

        optViewOnly.setOnClickListener {
            AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
            selectedLevel = "0"
            renderPermSelection()
        }
        optViewAndControl.setOnClickListener {
            AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
            selectedLevel = "1"
            renderPermSelection()
        }
        permRow.addView(optViewOnly, LinearLayout.LayoutParams(0, dp(44), 1f))
        permRow.addView(optViewAndControl, LinearLayout.LayoutParams(0, dp(44), 1.35f).apply {
            marginStart = dp(10)
        })
        formCard.addView(permRow, lp(top = dp(10)))
        body.addView(formCard, lp())

        var submitting = false
        root.addView(
            buildPinnedBottomCta("确认添加") {
                if (submitting) return@buildPinnedBottomCta
                val name = nameInput.text.toString().trim()
                val mobile = mobileInput.text.toString().trim()
                val start = getStartVal().trim()
                val end = getEndVal().trim()
                val pin = pinInput.text.toString().trim()
                val confirm = confirmPinInput.text.toString().trim()

                if (name.isBlank()) {
                    toast("请输入驾驶员姓名")
                    return@buildPinnedBottomCta
                }
                if (mobile.length != 11 || !mobile.all { it.isDigit() }) {
                    toast("请输入正确的 11 位手机号")
                    return@buildPinnedBottomCta
                }
                if (start.isBlank() || end.isBlank() || compareMinuteDateTime(end, start) <= 0) {
                    toast("结束时间必须晚于开始时间")
                    return@buildPinnedBottomCta
                }
                if (pin.length != 6 || !pin.all { it.isDigit() }) {
                    toast("请输入 6 位数字车控密码")
                    return@buildPinnedBottomCta
                }
                if (pin != confirm) {
                    toast("两次输入的车控密码不一致")
                    return@buildPinnedBottomCta
                }

                val active = profileProvider()
                submitting = true
                scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.createRemoteAuthorization(
                                accessToken = active.accessToken,
                                vin = vin,
                                mobile = mobile,
                                name = name,
                                startTime = start,
                                endTime = end,
                                authority = selectedLevel,
                                pinPlain = pin
                            )
                        }
                    }
                    submitting = false
                    res.onSuccess { ok ->
                        if (ok) {
                            AppHaptics.perform(root, HapticLevel.MEDIUM_CONFIRM)
                            toast("已添加驾驶员")
                            onPopOverlaySubpage()
                            onSaved()
                        } else {
                            toast("添加失败，请重试")
                        }
                    }.onFailure { err ->
                        if (err !is CherySessionExpiredException) {
                            toast(err.message.orEmpty().ifBlank { "请求失败，请检查信息后重试" }.take(90))
                        }
                    }
                }
            },
            lp()
        )

        onPushOverlaySubpage(root, null)
    }

    private fun openDriverOperationRecordsScreen(
        item: VehicleAuthorizationItem,
        vin: String
    ) {
        val granteeName = item.receiverAccountName.ifBlank { item.receiverMobile.ifBlank { "驾驶员" } }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }
        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "操作记录",
                onBack = { onPopOverlaySubpage() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(28))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD_BG, 16f, CARD_BORDER, 0.8f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        header.addView(
            text(granteeName, 15.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        if (item.receiverMobile.isNotBlank()) {
            header.addView(text(item.receiverMobile, 13f, ThemeTokens.TEXT_SUB))
        }
        body.addView(header, lp(bottom = dp(12)))

        val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listHost, lp())

        fun loadRecords() {
            listHost.removeAllViews()
            listHost.addView(loadingHint("正在查询操作记录..."))
            val active = profileProvider()
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.queryAuthorizationOperationRecords(
                            accessToken = active.accessToken,
                            vin = vin,
                            subAccountId = item.receiverAccountId,
                            page = 1,
                            count = 30
                        )
                    }
                }
                listHost.removeAllViews()
                res.onSuccess { records ->
                    if (records.isEmpty()) {
                        val emptyCard = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
                            addView(
                                AppComponents.createEmptyState(
                                    context = context,
                                    icon = AppIconType.CLOCK,
                                    title = "暂无操作记录",
                                    subtitle = "该驾驶员的远程控车记录将在此按时间展示"
                                )
                            )
                        }
                        listHost.addView(emptyCard, lp())
                    } else {
                        val timelineCard = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
                            setPadding(dp(16), dp(6), dp(16), dp(6))
                        }
                        records.forEachIndexed { idx, rec ->
                            timelineCard.addView(buildOperationRecordRow(rec), lp())
                            if (idx < records.lastIndex) {
                                timelineCard.addView(cardHairline())
                            }
                        }
                        listHost.addView(timelineCard, lp())
                    }
                }.onFailure { err ->
                    if (err is CherySessionExpiredException) return@onFailure
                    listHost.addView(errorBanner("记录加载失败：${err.message.orEmpty().take(90)}"))
                    listHost.addView(
                        AppComponents.createActionButton(context, "重新加载", ButtonStyle.SECONDARY) {
                            loadRecords()
                        },
                        lp(top = dp(12), height = dp(44))
                    )
                }
            }
        }

        onPushOverlaySubpage(root, null)
        loadRecords()
    }

    private fun buildOperationRecordRow(rec: AuthorizationOperationRecordItem): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(13), dp(2), dp(13))
            addView(
                text(rec.action.ifBlank { "远程控车操作" }, 15f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD)
            )
            addView(
                text(rec.time.ifBlank { "--" }, 12.5f, ThemeTokens.TEXT_SUB),
                lp(top = dp(4))
            )
        }
    }

    private fun buildEmptyDriversGuideCard(hasVehicle: Boolean): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(20), dp(30), dp(20), dp(24))
        }

        val halo = FrameLayout(context).apply {
            background = ovalDrawable(ACCENT_TINT_BG)
            addView(
                AppIconView(context, AppIconType.KEY_ROUND, ThemeTokens.ACCENT_LIGHT),
                FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            )
        }
        card.addView(halo, LinearLayout.LayoutParams(dp(58), dp(58)))

        card.addView(
            text(
                if (hasVehicle) "暂未添加驾驶员" else "暂未绑定车辆",
                16.5f,
                ThemeTokens.TEXT_PRIMARY,
                Typeface.BOLD
            ).apply { gravity = Gravity.CENTER },
            lp(top = dp(16))
        )
        card.addView(
            text(
                if (hasVehicle) {
                    "可将当前座驾临时分享给家人或朋友，支持设置起止时段并在详情页随时启停或撤销。"
                } else {
                    "请先在车辆管理中绑定车辆后再添加驾驶员。"
                },
                13f,
                ThemeTokens.TEXT_SUB
            ).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dpF(3f), 1f)
            },
            lp(top = dp(8))
        )

        card.addView(
            View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) },
            lp(top = dp(22), bottom = dp(16), height = dpF(0.7f).roundToInt().coerceAtLeast(1))
        )
        card.addView(
            text("支持两种权限级别", 12f, ThemeTokens.TEXT_SUB, Typeface.BOLD).apply {
                gravity = Gravity.START
            },
            lp(bottom = dp(10))
        )

        val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        grid.addView(
            buildPermissionGuideTile("车况查看", "实时查看车辆位置、油量续航与门窗状态"),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        grid.addView(
            buildPermissionGuideTile("车况查看 + 远程控车", "支持远程解闭锁、空调与寻车控制"),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
        )
        card.addView(grid, lp())
        return card
    }

    private fun buildPermissionGuideTile(title: String, desc: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(TILE_BG, 12f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(text(title, 13f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD))
            addView(
                text(desc, 11.5f, ThemeTokens.TEXT_SUB).apply {
                    setLineSpacing(dpF(2f), 1f)
                },
                lp(top = dp(5))
            )
        }
    }

    // =========================================================================
    // 2. 「车辆管理」一级界面 (一车一卡) -> 二级界面「车辆详情」/「添加车辆」
    // =========================================================================

    fun openMyVehiclesScreen(
        @Suppress("UNUSED_PARAMETER") focusVin: String? = null,
        @Suppress("UNUSED_PARAMETER") scrollToEditSection: Boolean = false
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }
        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "车辆管理",
                onBack = { onPopOverlaySubpage() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val listHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        scroll.addView(listHost, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val simStatusByVin = mutableMapOf<String, String>()
        var loadedVehicles: List<VehicleSummary> = emptyList()
        var reloadVehicles: () -> Unit = {}

        fun renderVehicleCards() {
            listHost.removeAllViews()
            if (loadedVehicles.isEmpty()) {
                listHost.addView(buildNoVehiclesEmptyCard {
                    openAddVehicleScreen(onAdded = { reloadVehicles() })
                })
                return
            }
            loadedVehicles.forEachIndexed { idx, v ->
                val resolvedSim = simStatusByVin[v.vin.uppercase(Locale.US)] ?: v.simStatus
                listHost.addView(
                    buildVehicleSummaryCard(
                        vehicle = v.copy(simStatus = resolvedSim),
                        onReload = reloadVehicles
                    ),
                    lp(top = if (idx == 0) 0 else dp(12))
                )
            }
        }

        reloadVehicles = {
            listHost.removeAllViews()
            listHost.addView(loadingHint("正在同步车辆列表..."))
            val active = profileProvider()
            if (active.accessToken.isBlank()) {
                listHost.removeAllViews()
                listHost.addView(errorBanner("请先登录后查看车辆管理"))
            } else {
                scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.fetchVehicleList(active.accessToken)
                        }
                    }
                    res.onSuccess { list ->
                        loadedVehicles = list.filter { it.vin.isNotBlank() }
                        loadedVehicles.forEach { v ->
                            if (v.simStatus.isNotBlank()) {
                                simStatusByVin.putIfAbsent(v.vin.uppercase(Locale.US), v.simStatus)
                            }
                        }
                        renderVehicleCards()

                        // Refresh real-time certification status (935) for bound vehicles
                        val boundVins = loadedVehicles.filter { it.isBoundVehicle }.map { it.vin }
                        if (boundVins.isNotEmpty()) {
                            val updatedStatuses = withContext(Dispatchers.IO) {
                                boundVins.map { vin ->
                                    async {
                                        val st = runCatching {
                                            httpClient.queryVehicleSimStatus(active.accessToken, vin)
                                        }.getOrNull()
                                        vin.uppercase(Locale.US) to st
                                    }
                                }.awaitAll()
                            }
                            var changed = false
                            updatedStatuses.forEach { (vinKey, st) ->
                                if (!st.isNullOrBlank() && simStatusByVin[vinKey] != st) {
                                    simStatusByVin[vinKey] = st
                                    changed = true
                                }
                            }
                            if (changed) renderVehicleCards()
                        }
                    }.onFailure { err ->
                        listHost.removeAllViews()
                        if (err is CherySessionExpiredException) return@onFailure
                        listHost.addView(errorBanner("车辆列表加载失败：${err.message.orEmpty().take(90)}"))
                        listHost.addView(
                            AppComponents.createActionButton(context, "重新加载", ButtonStyle.SECONDARY) {
                                reloadVehicles()
                            },
                            lp(top = dp(12), height = dp(44))
                        )
                    }
                }
            }
        }

        // Pinned bottom CTA: ＋ 添加车辆
        root.addView(
            buildPinnedBottomCta("＋ 添加车辆") {
                openAddVehicleScreen(onAdded = { reloadVehicles() })
            },
            lp()
        )

        onPushOverlaySubpage(root, null)
        reloadVehicles()
    }

    /**
     * Level-1 Vehicle Management Card: ONE rich, restrained card per vehicle displaying ONLY
     * model name, status badges (certified/default), license plate plaque, VIN, and detail chevron.
     * Tapping pushes Level-2 「车辆详情」.
     */
    private fun buildVehicleSummaryCard(
        vehicle: VehicleSummary,
        onReload: () -> Unit
    ): LinearLayout {
        val activeVin = profileProvider().vin.trim().uppercase(Locale.US)
        val isCurrentActive = vehicle.vin.equals(activeVin, ignoreCase = true)
        val modelTitle = vehicle.modelName.ifBlank { "我的座驾" }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = if (isCurrentActive) {
                rounded(CARD_BG, 18f, ThemeTokens.ACCENT_BLUE, 1.0f)
            } else {
                rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            }
            setPadding(dp(18), dp(18), dp(18), dp(16))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)
            setOnClickListener {
                openVehicleDetailScreen(
                    initialVehicle = vehicle,
                    vinFallback = vehicle.vin,
                    onUpdated = onReload
                )
            }
        }

        // Row 1: Model Name + Status Badges (left) | Right Chevron
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCluster = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleCluster.addView(
            text(modelTitle, 19.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        if (vehicle.isBoundVehicle) {
            val (simText, simFg, simBg) = simStatusAppearance(vehicle.simStatus)
            titleCluster.addView(
                TextView(context).apply {
                    text = simText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(simFg)
                    background = rounded(simBg, 7f)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
            )
        } else {
            titleCluster.addView(
                TextView(context).apply {
                    text = "被授权"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ThemeTokens.ACCENT_LIGHT)
                    background = rounded(ACCENT_TINT_BG, 7f)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
            )
        }
        if (vehicle.isSelected) {
            titleCluster.addView(
                TextView(context).apply {
                    text = "默认车辆"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ThemeTokens.TEXT_PRIMARY)
                    background = rounded(ThemeTokens.ACCENT_BLUE, 7f)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }
            )
        }
        topRow.addView(titleCluster, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topRow.addView(
            AppIconView(context, AppIconType.CHEVRON_RIGHT, Color.parseColor("#676C78")),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) }
        )
        card.addView(topRow, lp())

        // Row 2: Compact Studio Emblem + Metallic Plate Plaque + Active Status
        val studioRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(HERO_BANNER_BG, 14f, Color.parseColor("#2A2E3A"), 0.7f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val emblemBox = FrameLayout(context).apply {
            background = rounded(Color.parseColor("#1D2028"), 11f)
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_app_logo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "CarPlus Logo"
                },
                FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
            )
        }
        studioRow.addView(emblemBox, LinearLayout.LayoutParams(dp(48), dp(48)))

        val plateCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        plateCol.addView(
            TextView(context).apply {
                text = vehicle.plateNo.ifBlank { "未设置车牌号" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(if (vehicle.plateNo.isNotBlank()) ThemeTokens.TEXT_PRIMARY else ThemeTokens.TEXT_SUB)
                background = rounded(
                    Color.parseColor("#252934"),
                    7f,
                    if (vehicle.plateNo.isNotBlank()) Color.parseColor("#475166") else CARD_BORDER,
                    0.9f
                )
                setPadding(dp(11), dp(4), dp(11), dp(4))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )
        val dotRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dotRow.addView(
            View(context).apply {
                background = ovalDrawable(if (isCurrentActive) SUCCESS_GREEN else ThemeTokens.TEXT_SUB)
            },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) }
        )
        dotRow.addView(
            text(
                if (isCurrentActive) "当前控车中" else "轻触查看详情与管理",
                12f,
                if (isCurrentActive) SUCCESS_GREEN else ThemeTokens.TEXT_SUB
            )
        )
        plateCol.addView(dotRow, lp(top = dp(6)))
        studioRow.addView(
            plateCol,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        )
        card.addView(studioRow, lp(top = dp(14)))

        // Row 3: Clean VIN line + subtle detail cue
        val vinRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        vinRow.addView(text("车架号", 12f, ThemeTokens.TEXT_SUB))
        vinRow.addView(
            TextView(context).apply {
                text = vehicle.vin
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextColor(ThemeTokens.TEXT_BODY)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
        )
        vinRow.addView(text("查看详情", 12f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD))
        card.addView(vinRow, lp(top = dp(12)))

        return card
    }

    /**
     * Level-2 Vehicle Detail screen (TopBar: 车辆详情):
     * - Card 1: Identity, live certification badge, set default (932), switch active car, Drivers shortcut
     * - Card 2: Complete 2x2 Hardware & Real-Name Archive (VIN / Engine / ICCID / SN, tap to copy)
     * - Card 3: Inline Owner & License Info Editor (plate / owner / purchase date + Save 933)
     * - Card 4: Default-Hidden Collapsible Danger Zone (Cancel Cert 934 & Unbind 931, double confirm + 6-digit PIN)
     */
    fun openEditVehicleScreen(
        initialVehicle: VehicleSummary? = null,
        vinFallback: String = "",
        onUpdated: () -> Unit = {}
    ) = openVehicleDetailScreen(initialVehicle, vinFallback, onUpdated)

    fun openVehicleDetailScreen(
        initialVehicle: VehicleSummary? = null,
        vinFallback: String = "",
        onUpdated: () -> Unit = {}
    ) {
        val activeProfile = profileProvider()
        val targetVin = firstNonBlank(initialVehicle?.vin.orEmpty(), vinFallback, activeProfile.vin)
            .trim()
            .uppercase(Locale.US)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }
        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "车辆详情",
                onBack = { onPopOverlaySubpage() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(36))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        fun renderDetail(vehicle: VehicleSummary) {
            body.removeAllViews()
            val activeVin = profileProvider().vin.trim().uppercase(Locale.US)
            val isCurrentActive = vehicle.vin.equals(activeVin, ignoreCase = true)
            val modelTitle = vehicle.modelName.ifBlank { "我的座驾" }
            var liveSimStatus = vehicle.simStatus

            // Card 1: Identity & Primary Vehicle Actions
            val headerCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
                setPadding(dp(18), dp(16), dp(18), dp(16))
            }
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(
                text(modelTitle, 20f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
            val simBadge = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            fun updateSimBadge() {
                if (vehicle.isBoundVehicle) {
                    val (t, fg, bg) = simStatusAppearance(liveSimStatus)
                    simBadge.text = t
                    simBadge.setTextColor(fg)
                    simBadge.background = rounded(bg, 7f)
                } else {
                    simBadge.text = "被授权"
                    simBadge.setTextColor(ThemeTokens.ACCENT_LIGHT)
                    simBadge.background = rounded(ACCENT_TINT_BG, 7f)
                }
            }
            updateSimBadge()
            topRow.addView(
                simBadge,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
            )
            topRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

            if (vehicle.isSelected) {
                topRow.addView(
                    TextView(context).apply {
                        text = "默认车辆"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(ThemeTokens.TEXT_PRIMARY)
                        background = rounded(ThemeTokens.ACCENT_BLUE, 8f)
                        setPadding(dp(9), dp(4), dp(9), dp(4))
                    }
                )
            } else if (vehicle.isBoundVehicle) {
                topRow.addView(
                    pillActionButton("设为默认车辆", accent = false) {
                        val active = profileProvider()
                        scope.launch {
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    httpClient.setDefaultVehicle(active.accessToken, vehicle.vin)
                                }
                            }
                            res.onSuccess { ok ->
                                if (ok) {
                                    AppHaptics.perform(headerCard, HapticLevel.MEDIUM_CONFIRM)
                                    toast("已将 $modelTitle 设为默认车辆")
                                    onSelectVehicleSummary(vehicle.copy(selected = "1"))
                                    renderDetail(vehicle.copy(selected = "1", simStatus = liveSimStatus))
                                    onUpdated()
                                } else {
                                    toast("设置默认车辆失败")
                                }
                            }.onFailure { err ->
                                if (err !is CherySessionExpiredException) {
                                    toast("设置失败：${err.message.orEmpty().take(70)}")
                                }
                            }
                        }
                    },
                    LinearLayout.LayoutParams(WRAP_CONTENT, dp(28))
                )
            }
            headerCard.addView(topRow, lp())

            val platePreview = TextView(context).apply {
                text = vehicle.plateNo.ifBlank { "未设置车牌号" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(if (vehicle.plateNo.isNotBlank()) ThemeTokens.TEXT_BODY else ThemeTokens.TEXT_SUB)
            }
            headerCard.addView(platePreview, lp(top = dp(6)))

            if (!vehicle.isBoundVehicle && vehicle.authDateListSummary.isNotBlank()) {
                headerCard.addView(
                    text("授权时段：${vehicle.authDateListSummary}", 12.5f, ThemeTokens.TEXT_SUB),
                    lp(top = dp(6))
                )
            }

            if (!isCurrentActive || vehicle.isBoundVehicle) {
                val quickRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (!isCurrentActive) {
                    quickRow.addView(
                        cardActionChip("切换为当前控车", accent = true) {
                            onSelectVehicleSummary(vehicle)
                            onPopOverlaySubpage()
                            onPopOverlaySubpage()
                        },
                        LinearLayout.LayoutParams(0, dp(38), 1f)
                    )
                }
                if (vehicle.isBoundVehicle) {
                    quickRow.addView(
                        cardActionChip("驾驶员", accent = false) {
                            openDriversScreen(
                                targetVinOverride = vehicle.vin,
                                targetModelOverride = modelTitle,
                                targetPlateOverride = platePreview.text.toString().takeUnless { it == "未设置车牌号" }
                            )
                        },
                        LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                            if (!isCurrentActive) marginStart = dp(10)
                        }
                    )
                }
                headerCard.addView(quickRow, lp(top = dp(14)))
            }
            body.addView(headerCard, lp())

            // Card 2: Hardware & Real-Name Archive (2x2 Grid, tap to copy)
            val hwCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            val hwHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            hwHeader.addView(
                text("硬件档案", 15.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            hwHeader.addView(text("轻触编号可复制", 11.5f, ThemeTokens.TEXT_SUB))
            hwCard.addView(hwHeader, lp(bottom = dp(12)))

            val gridRow1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            gridRow1.addView(
                buildHardwareSpecTile("车架号 (VIN)", vehicle.vin.ifBlank { "--" }),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            gridRow1.addView(
                buildHardwareSpecTile("发动机号", vehicle.engineNumber.ifBlank { "--" }),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
            )
            hwCard.addView(gridRow1, lp())

            val gridRow2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            gridRow2.addView(
                buildHardwareSpecTile("车辆 ICCID", vehicle.iccid.ifBlank { "--" }),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            gridRow2.addView(
                buildHardwareSpecTile("车辆 SN 码", vehicle.sn.ifBlank { "--" }),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
            )
            hwCard.addView(gridRow2, lp(top = dp(10)))
            body.addView(hwCard, lp(top = dp(12)))

            // Card 3: Owner & License Editable Form (933)
            if (vehicle.isBoundVehicle) {
                val editCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
                    setPadding(dp(18), dp(16), dp(18), dp(18))
                }
                editCard.addView(
                    text("车主与牌照信息", 15.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD),
                    lp(bottom = dp(14))
                )

                editCard.addView(formFieldHeader("车牌号", required = true))
                val (plateBox, plateInput) = AppComponents.createUnderlineInput(
                    context = context,
                    hint = "请输入车牌号",
                    initialValue = vehicle.plateNo,
                    inputType = InputType.TYPE_CLASS_TEXT,
                    maxLength = 12
                )
                editCard.addView(plateBox, lp(top = dp(2)))

                editCard.addView(formFieldHeader("车主姓名", required = false), lp(top = dp(16)))
                val (ownerBox, ownerInput) = AppComponents.createUnderlineInput(
                    context = context,
                    hint = "请输入车主姓名",
                    initialValue = vehicle.hostName,
                    inputType = InputType.TYPE_CLASS_TEXT,
                    maxLength = 24
                )
                editCard.addView(ownerBox, lp(top = dp(2)))

                editCard.addView(formFieldHeader("购车时间", required = false, note = "选填"), lp(top = dp(16)))
                val (purchaseDateBox, getPurchaseDate) = AppComponents.createDateTimePickerField(
                    context = context,
                    hint = "请选择购车时间",
                    initialValue = normalizeDateOnly(vehicle.purchaseTime),
                    mode = DateTimeMode.DATE
                )
                editCard.addView(purchaseDateBox, lp(top = dp(2)))

                var saving = false
                val saveBtn = AppComponents.createActionButton(context, "保存", ButtonStyle.PRIMARY) {
                    if (saving) return@createActionButton
                    val cleanPlate = plateInput.text.toString().trim().uppercase(Locale.US)
                    val cleanHost = ownerInput.text.toString().trim()
                    val cleanDate = normalizeDateOnly(getPurchaseDate())
                    if (cleanPlate.isBlank()) {
                        AppHaptics.perform(editCard, HapticLevel.ERROR_REJECT)
                        toast("请输入车牌号")
                        return@createActionButton
                    }
                    val active = profileProvider()
                    saving = true
                    scope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.saveVehicleInfo(
                                    accessToken = active.accessToken,
                                    vin = vehicle.vin,
                                    plateNo = cleanPlate,
                                    hostName = cleanHost,
                                    purchaseDate = cleanDate
                                )
                            }
                        }
                        saving = false
                        res.onSuccess { ok ->
                            if (ok) {
                                AppHaptics.perform(editCard, HapticLevel.MEDIUM_CONFIRM)
                                platePreview.text = cleanPlate
                                platePreview.setTextColor(ThemeTokens.TEXT_BODY)
                                onVehicleInfoSaved(vehicle.vin, cleanPlate, cleanHost, cleanDate)
                                onStatusMessage("车辆信息已保存")
                                toast("车辆信息已保存")
                                onUpdated()
                            } else {
                                toast("保存车辆信息失败")
                            }
                        }.onFailure { err ->
                            if (err !is CherySessionExpiredException) {
                                toast("保存失败：${err.message.orEmpty().take(80)}")
                            }
                        }
                    }
                }
                editCard.addView(saveBtn, lp(top = dp(20), height = dp(46)))
                body.addView(editCard, lp(top = dp(12)))

                // Card 4: Default-Hidden Collapsible Danger Zone (Cancel Cert 934 & Unbind 931)
                val dangerSection = createCollapsibleDangerSection(title = "危险操作") { content ->
                    content.addView(
                        text("取消认证或解除绑定均属于不可逆敏感操作，执行前需二次确认并验证 6 位车控安全密码。", 12.5f, ThemeTokens.TEXT_SUB).apply {
                            setLineSpacing(dpF(2f), 1f)
                        },
                        lp(bottom = dp(12))
                    )

                    val actionRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val cancelCertBtn = TextView(context).apply {
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        setTypeface(null, Typeface.BOLD)
                    }
                    fun updateCancelCertBtn() {
                        val certified = liveSimStatus == "1"
                        cancelCertBtn.text = if (certified) "取消认证" else "未实名认证"
                        cancelCertBtn.setTextColor(if (certified) ThemeTokens.TEXT_PRIMARY else ThemeTokens.TEXT_SUB)
                        cancelCertBtn.background = rounded(
                            if (certified) TILE_BG else Color.parseColor("#1F2127"),
                            10f,
                            CARD_BORDER,
                            0.8f
                        )
                        cancelCertBtn.alpha = if (certified) 1f else 0.55f
                    }
                    updateCancelCertBtn()
                    MotionSpec.attachPressPhysics(cancelCertBtn, pressedScale = 0.96f)
                    cancelCertBtn.setOnClickListener {
                        if (liveSimStatus != "1") {
                            toast("当前车辆未处于已认证状态")
                            return@setOnClickListener
                        }
                        activeDialog?.dismiss()
                        activeDialog = AppComponents.showConfirmDialog(
                            context = context,
                            title = "取消车辆实名认证",
                            message = "取消认证后将影响车联网通信及远程控车功能，需验证 6 位车控安全密码。确定要取消认证吗？",
                            confirmText = "继续验证",
                            cancelText = "取消",
                            dangerConfirm = true
                        ) {
                            activeDialog = SixDigitPinSheet.showVerifyPinSheet(
                                context = context,
                                title = "验证车控安全密码",
                                subtitle = "请输入 6 位车控安全密码以取消车辆认证"
                            ) { pin, showError, dismiss ->
                                val active = profileProvider()
                                scope.launch {
                                    val res = runCatching {
                                        withContext(Dispatchers.IO) {
                                            httpClient.cancelVehicleCertification(
                                                accessToken = active.accessToken,
                                                vin = vehicle.vin,
                                                iccid = vehicle.iccid,
                                                pinPlain = pin
                                            )
                                        }
                                    }
                                    res.onSuccess { ok ->
                                        if (ok) {
                                            dismiss()
                                            liveSimStatus = "0"
                                            updateSimBadge()
                                            updateCancelCertBtn()
                                            toast("已取消车辆实名认证")
                                            onUpdated()
                                        } else {
                                            showError("车控密码验证失败，请重试")
                                        }
                                    }.onFailure { err ->
                                        if (err is CherySessionExpiredException) {
                                            dismiss()
                                        } else {
                                            showError(err.message.orEmpty().ifBlank { "取消认证失败" }.take(60))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val unbindBtn = TextView(context).apply {
                        text = "解除绑定"
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(ThemeTokens.DANGER)
                        background = rounded(DANGER_TINT_BG, 10f, DANGER_BORDER, 0.8f)
                        MotionSpec.attachPressPhysics(this, pressedScale = 0.96f)
                        setOnClickListener {
                            activeDialog?.dismiss()
                            activeDialog = AppComponents.showConfirmDialog(
                                context = context,
                                title = "解除车辆绑定",
                                message = "解除绑定后，当前账号将移除该车辆的所有远程控车与数字钥匙权限，需验证 6 位车控安全密码。确定继续吗？",
                                confirmText = "继续解绑",
                                cancelText = "取消",
                                dangerConfirm = true
                            ) {
                                activeDialog = SixDigitPinSheet.showVerifyPinSheet(
                                    context = context,
                                    title = "验证车控安全密码",
                                    subtitle = "请输入 6 位车控安全密码以解除车辆绑定"
                                ) { pin, showError, dismiss ->
                                    val active = profileProvider()
                                    scope.launch {
                                        val res = runCatching {
                                            withContext(Dispatchers.IO) {
                                                httpClient.unbindVehicle(
                                                    accessToken = active.accessToken,
                                                    vin = vehicle.vin,
                                                    pinPlain = pin
                                                )
                                            }
                                        }
                                        res.onSuccess { ok ->
                                            if (ok) {
                                                dismiss()
                                                toast("车辆已解除绑定")
                                                onPopOverlaySubpage()
                                                onVehicleUnbound(vehicle.vin)
                                                onUpdated()
                                            } else {
                                                showError("车控密码验证失败，请重试")
                                            }
                                        }.onFailure { err ->
                                            if (err is CherySessionExpiredException) {
                                                dismiss()
                                            } else {
                                                showError(err.message.orEmpty().ifBlank { "解绑失败" }.take(60))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    actionRow.addView(cancelCertBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
                    actionRow.addView(unbindBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(10) })
                    content.addView(actionRow, lp())
                }
                body.addView(dangerSection, lp(top = dp(14)))
            }
        }

        onPushOverlaySubpage(root, null)

        if (initialVehicle != null) {
            renderDetail(initialVehicle)
        } else {
            body.addView(loadingHint("正在加载车辆详情..."))
            scope.launch {
                val active = profileProvider()
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        val list = httpClient.fetchVehicleList(active.accessToken)
                        val matched = list.firstOrNull { it.vin.equals(targetVin, ignoreCase = true) }
                            ?: list.firstOrNull()
                            ?: throw IllegalStateException("未找到对应车辆档案")
                        val liveSim = runCatching {
                            httpClient.queryVehicleSimStatus(active.accessToken, matched.vin)
                        }.getOrNull()
                        if (!liveSim.isNullOrBlank()) matched.copy(simStatus = liveSim) else matched
                    }
                }
                res.onSuccess { fetched ->
                    renderDetail(fetched)
                }.onFailure { err ->
                    body.removeAllViews()
                    if (err is CherySessionExpiredException) return@onFailure
                    body.addView(errorBanner("加载车辆详情失败：${err.message.orEmpty().take(90)}"))
                }
            }
        }
    }

    // =========================================================================
    // 3. 「添加车辆」二级界面 (TopBar: 添加车辆, service 930)
    // =========================================================================

    private fun openAddVehicleScreen(onAdded: () -> Unit) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }
        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "添加车辆",
                onBack = { onPopOverlaySubpage() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val formCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(18), dp(18), dp(18), dp(20))
        }

        formCard.addView(formFieldHeader("车架号 (VIN)", required = true))
        val (vinBox, vinInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "请输入 17 位车辆识别代号",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
            maxLength = 17
        )
        formCard.addView(vinBox, lp(top = dp(2)))

        formCard.addView(formFieldHeader("车辆 SN 码", required = true), lp(top = dp(16)))
        val (snBox, snInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "请输入车载终端序列号 (SN)",
            inputType = InputType.TYPE_CLASS_TEXT
        )
        formCard.addView(snBox, lp(top = dp(2)))

        formCard.addView(formFieldHeader("车辆 ICCID", required = true), lp(top = dp(16)))
        val (iccidBox, iccidInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "请输入车载 SIM 卡 ICCID",
            inputType = InputType.TYPE_CLASS_TEXT
        )
        formCard.addView(iccidBox, lp(top = dp(2)))

        formCard.addView(formFieldHeader("车牌号", required = false, note = "选填"), lp(top = dp(16)))
        val (plateBox, plateInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "请输入车牌号",
            inputType = InputType.TYPE_CLASS_TEXT,
            maxLength = 12
        )
        formCard.addView(plateBox, lp(top = dp(2)))

        formCard.addView(formFieldHeader("车主姓名", required = false, note = "选填"), lp(top = dp(16)))
        val (hostBox, hostInput) = AppComponents.createUnderlineInput(
            context = context,
            hint = "请输入车主姓名",
            inputType = InputType.TYPE_CLASS_TEXT,
            maxLength = 24
        )
        formCard.addView(hostBox, lp(top = dp(2)))

        formCard.addView(formFieldHeader("购车时间", required = false, note = "选填"), lp(top = dp(16)))
        val (dateBox, getPurchaseDate) = AppComponents.createDateTimePickerField(
            context = context,
            hint = "选填",
            mode = DateTimeMode.DATE
        )
        formCard.addView(dateBox, lp(top = dp(2)))

        formCard.addView(formFieldHeader("车控安全密码", required = true), lp(top = dp(16)))
        val (pinBox, pinInput) = createPasswordToggleUnderlineInput("请设置 6 位数字车控密码", maxLength = 6)
        formCard.addView(pinBox, lp(top = dp(2)))

        var setAsDefault = true
        val defaultSwitch = ToggleSwitch(context).apply {
            checked = true
            onToggleAttempt = { next ->
                setAsDefault = next
                setChecked(next, animate = true)
            }
        }
        val defaultRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(4))
            addView(
                text("绑定后设为默认车辆", 14.5f, ThemeTokens.TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            addView(defaultSwitch, LinearLayout.LayoutParams(dp(46), dp(26)))
        }
        formCard.addView(defaultRow, lp())
        body.addView(formCard, lp())

        var binding = false
        root.addView(
            buildPinnedBottomCta("确认添加") {
                if (binding) return@buildPinnedBottomCta
                val vin = vinInput.text.toString().trim().uppercase(Locale.US)
                val sn = snInput.text.toString().trim()
                val iccid = iccidInput.text.toString().trim()
                val plate = plateInput.text.toString().trim().uppercase(Locale.US)
                val host = hostInput.text.toString().trim()
                val purchaseDate = normalizeDateOnly(getPurchaseDate())
                val pin = pinInput.text.toString().trim()

                if (vin.length != 17) {
                    toast("请输入完整的 17 位车架号 (VIN)")
                    return@buildPinnedBottomCta
                }
                if (sn.isBlank() || iccid.isBlank()) {
                    toast("请填写车辆 SN 码与 ICCID")
                    return@buildPinnedBottomCta
                }
                if (pin.length != 6 || !pin.all { it.isDigit() }) {
                    toast("请输入 6 位数字车控密码")
                    return@buildPinnedBottomCta
                }

                val active = profileProvider()
                binding = true
                scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.bindNewVehicle(
                                accessToken = active.accessToken,
                                vin = vin,
                                sn = sn,
                                iccid = iccid,
                                pinPlain = pin,
                                plateNo = plate,
                                hostName = host,
                                purchaseDate = purchaseDate,
                                isDefault = setAsDefault
                            )
                        }
                    }
                    binding = false
                    res.onSuccess { ok ->
                        if (ok) {
                            AppHaptics.perform(root, HapticLevel.MEDIUM_CONFIRM)
                            toast("车辆添加成功")
                            onPopOverlaySubpage()
                            onAdded()
                        } else {
                            toast("车辆添加失败")
                        }
                    }.onFailure { err ->
                        if (err !is CherySessionExpiredException) {
                            toast("添加失败：${err.message.orEmpty().take(80)}")
                        }
                    }
                }
            },
            lp()
        )

        onPushOverlaySubpage(root, null)
    }

    // =========================================================================
    // Smooth Animated Collapsible Danger Section (Default Hidden / Collapsed)
    // =========================================================================

    private fun createCollapsibleDangerSection(
        title: String,
        buildBody: (LinearLayout) -> Unit
    ): LinearLayout {
        var expanded = false
        var activeAnim: ValueAnimator? = null

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#191B20"), 16f, CARD_BORDER, 0.8f)
            clipChildren = true
            clipToPadding = true
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
        }
        val titleTv = text(title, 14f, ThemeTokens.TEXT_SUB, Typeface.BOLD)
        val chevron = AppIconView(context, AppIconType.CHEVRON_DOWN, ThemeTokens.TEXT_SUB)
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        headerRow.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)))
        wrapper.addView(headerRow, lp())

        val bodyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(16))
            visibility = View.GONE
            alpha = 0f
        }
        buildBody(bodyContainer)
        wrapper.addView(bodyContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0))

        MotionSpec.attachPressPhysics(headerRow, pressedScale = 0.985f, haptic = false)
        headerRow.setOnClickListener {
            AppHaptics.perform(headerRow, HapticLevel.LIGHT_TICK)
            expanded = !expanded
            activeAnim?.cancel()

            titleTv.setTextColor(if (expanded) ThemeTokens.DANGER else ThemeTokens.TEXT_SUB)
            chevron.animate().cancel()
            chevron.animate()
                .rotation(if (expanded) 180f else 0f)
                .setDuration(240L)
                .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                .start()

            if (expanded) {
                val widthSpec = View.MeasureSpec.makeMeasureSpec(
                    wrapper.width.takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels - dp(32)),
                    View.MeasureSpec.EXACTLY
                )
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                bodyContainer.measure(widthSpec, heightSpec)
                val targetHeight = bodyContainer.measuredHeight.coerceAtLeast(dp(64))

                bodyContainer.visibility = View.VISIBLE
                bodyContainer.layoutParams = (bodyContainer.layoutParams as LinearLayout.LayoutParams).apply {
                    height = 0
                }
                bodyContainer.alpha = 0f

                activeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 260L
                    interpolator = MotionSpec.EASE_OUT_EXPO
                    addUpdateListener { anim ->
                        val f = anim.animatedValue as Float
                        val lpCur = bodyContainer.layoutParams as LinearLayout.LayoutParams
                        lpCur.height = (targetHeight * f).roundToInt()
                        bodyContainer.layoutParams = lpCur
                        bodyContainer.alpha = f
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            val lpCur = bodyContainer.layoutParams as LinearLayout.LayoutParams
                            lpCur.height = WRAP_CONTENT
                            bodyContainer.layoutParams = lpCur
                            bodyContainer.alpha = 1f
                        }
                    })
                    start()
                }
            } else {
                val startHeight = bodyContainer.height.coerceAtLeast(1)
                activeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 220L
                    interpolator = MotionSpec.EASE_STANDARD
                    addUpdateListener { anim ->
                        val f = anim.animatedValue as Float
                        val lpCur = bodyContainer.layoutParams as LinearLayout.LayoutParams
                        lpCur.height = (startHeight * f).roundToInt()
                        bodyContainer.layoutParams = lpCur
                        bodyContainer.alpha = f
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            bodyContainer.visibility = View.GONE
                            val lpCur = bodyContainer.layoutParams as LinearLayout.LayoutParams
                            lpCur.height = 0
                            bodyContainer.layoutParams = lpCur
                            bodyContainer.alpha = 0f
                        }
                    })
                    start()
                }
            }
        }

        return wrapper
    }

    // =========================================================================
    // Reusable Minimalist Dark Automotive UI Helpers
    // =========================================================================

    private fun buildHardwareSpecTile(label: String, value: String): LinearLayout {
        val canCopy = value.isNotBlank() && value != "--"
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(TILE_BG, 12f)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            if (canCopy) {
                isClickable = true
                isFocusable = true
                MotionSpec.attachPressPhysics(this, pressedScale = 0.97f)
                setOnClickListener {
                    AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
                    toast("${label}已复制")
                }
            }
            addView(text(label, 11.5f, ThemeTokens.TEXT_SUB))
            addView(
                TextView(context).apply {
                    text = value
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(if (canCopy) ThemeTokens.TEXT_PRIMARY else ThemeTokens.TEXT_SUB)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                },
                lp(top = dp(5))
            )
        }
    }

    private fun buildNoVehiclesEmptyCard(onBindClick: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            setPadding(dp(22), dp(34), dp(22), dp(26))
            addView(
                AppComponents.createEmptyState(
                    context = context,
                    icon = AppIconType.CAR_CONTROLS,
                    title = "当前账号暂未绑定车辆",
                    subtitle = "添加车辆后即可在此查看车辆详情、修改车牌与管理驾驶员"
                )
            )
            addView(
                AppComponents.createActionButton(context, "＋ 添加车辆", ButtonStyle.PRIMARY, onBindClick),
                lp(top = dp(12), height = dp(46))
            )
        }
    }

    private fun createMinuteDateTimePickerField(
        hint: String,
        initialValue: String,
        onChanged: (String) -> Unit
    ): Pair<LinearLayout, () -> String> {
        var currentVal = formatAuthTime(initialValue).takeUnless { it == "--" }.orEmpty()
        val cal = Calendar.getInstance()
        runCatching {
            val parts = currentVal.split(" ")
            if (parts.isNotEmpty() && parts[0].contains("-")) {
                val d = parts[0].split("-")
                if (d.size == 3) {
                    cal.set(Calendar.YEAR, d[0].toInt())
                    cal.set(Calendar.MONTH, d[1].toInt() - 1)
                    cal.set(Calendar.DAY_OF_MONTH, d[2].toInt())
                }
            }
            if (parts.size >= 2 && parts[1].contains(":")) {
                val t = parts[1].split(":")
                if (t.size >= 2) {
                    cal.set(Calendar.HOUR_OF_DAY, t[0].toInt())
                    cal.set(Calendar.MINUTE, t[1].toInt())
                }
            }
        }

        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val valueTv = TextView(context).apply {
            text = currentVal.ifBlank { hint }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (currentVal.isNotBlank()) ThemeTokens.TEXT_PRIMARY else HINT_COLOR)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(valueTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(
                AppIconView(context, AppIconType.CALENDAR, ThemeTokens.TEXT_SUB),
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) }
            )
        }
        val underline = View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) }

        MotionSpec.attachPressPhysics(outer, pressedScale = 0.985f)
        outer.setOnClickListener {
            DatePickerDialog(
                context,
                android.R.style.Theme_DeviceDefault_Dialog_Alert,
                { _, year, month, day ->
                    cal.set(Calendar.YEAR, year)
                    cal.set(Calendar.MONTH, month)
                    cal.set(Calendar.DAY_OF_MONTH, day)
                    TimePickerDialog(
                        context,
                        android.R.style.Theme_DeviceDefault_Dialog_Alert,
                        { _, hour, minute ->
                            cal.set(Calendar.HOUR_OF_DAY, hour)
                            cal.set(Calendar.MINUTE, minute)
                            val formatted = String.format(
                                Locale.US,
                                "%04d-%02d-%02d %02d:%02d",
                                year, month + 1, day, hour, minute
                            )
                            currentVal = formatted
                            valueTv.text = formatted
                            valueTv.setTextColor(ThemeTokens.TEXT_PRIMARY)
                            onChanged(formatted)
                        },
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        true
                    ).show()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        outer.addView(row, lp())
        outer.addView(underline, lp(height = dpF(0.8f).roundToInt().coerceAtLeast(1)))
        return outer to { currentVal }
    }

    private fun createPasswordToggleUnderlineInput(
        hint: String,
        maxLength: Int
    ): Pair<LinearLayout, EditText> {
        var visible = false
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val underline = View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val edit = EditText(context).apply {
            this.hint = hint
            setHintTextColor(HINT_COLOR)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(10), 0, dp(10))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            setOnFocusChangeListener { _, hasFocus ->
                underline.setBackgroundColor(if (hasFocus) ThemeTokens.ACCENT_ACTIVE else ThemeTokens.DIVIDER)
            }
        }
        val toggleBtn = TextView(context).apply {
            text = "显示"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(ThemeTokens.TEXT_SUB)
            setPadding(dp(10), dp(6), dp(2), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                visible = !visible
                val sel = edit.selectionEnd.coerceAtLeast(0)
                edit.inputType = if (visible) {
                    InputType.TYPE_CLASS_NUMBER
                } else {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
                edit.setSelection(sel.coerceAtMost(edit.text?.length ?: 0))
                text = if (visible) "隐藏" else "显示"
                setTextColor(if (visible) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_SUB)
            }
        }
        row.addView(edit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(toggleBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        container.addView(row, lp())
        container.addView(underline, lp(height = dpF(0.8f).roundToInt().coerceAtLeast(1)))
        return container to edit
    }

    private fun createPermissionOptionButton(label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTypeface(null, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        isFocusable = true
        MotionSpec.attachPressPhysics(this, pressedScale = 0.97f, haptic = false)
    }

    private fun stylePermissionOptionButton(button: TextView, selected: Boolean, disabled: Boolean) {
        if (selected) {
            button.setTextColor(ThemeTokens.TEXT_PRIMARY)
            button.background = rounded(ACCENT_TINT_BG, 10f, ThemeTokens.ACCENT_BLUE, 1.2f)
        } else {
            button.setTextColor(ThemeTokens.TEXT_SUB)
            button.background = rounded(TILE_BG, 10f, CARD_BORDER, 0.8f)
        }
        button.alpha = if (disabled && !selected) 0.45f else 1f
    }

    private fun buildPinnedBottomCta(label: String, onClick: () -> Unit): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
        }
        container.addView(
            View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) },
            lp(height = dpF(0.6f).roundToInt().coerceAtLeast(1))
        )
        val inner = FrameLayout(context).apply {
            setPadding(dp(18), dp(12), dp(18), dp(22))
        }
        val btn = AppComponents.createActionButton(context, label, ButtonStyle.PRIMARY, onClick)
        inner.addView(btn, FrameLayout.LayoutParams(MATCH_PARENT, dp(48)))
        container.addView(inner, lp())
        return container
    }

    private fun formFieldHeader(label: String, required: Boolean, note: String? = null): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (required) {
                addView(
                    text("*", 13.5f, ThemeTokens.DANGER, Typeface.BOLD),
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(4) }
                )
            }
            addView(text(label, 13f, ThemeTokens.TEXT_SUB, Typeface.BOLD))
            if (!note.isNullOrBlank()) {
                addView(
                    text("（$note）", 11.5f, HINT_COLOR),
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(4) }
                )
            }
        }
    }

    private fun cardHairline(): View = View(context).apply {
        setBackgroundColor(ThemeTokens.DIVIDER)
        layoutParams = lp(height = dpF(0.6f).roundToInt().coerceAtLeast(1))
    }

    private fun pillActionButton(label: String, accent: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (accent) ThemeTokens.TEXT_PRIMARY else ThemeTokens.TEXT_BODY)
            background = rounded(
                if (accent) ThemeTokens.ACCENT_BLUE else TILE_BG,
                14f,
                if (accent) null else CARD_BORDER,
                if (accent) 0f else 0.8f
            )
            setPadding(dp(11), 0, dp(11), 0)
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener { onClick() }
        }
    }

    private fun cardActionChip(label: String, accent: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (accent) ThemeTokens.TEXT_PRIMARY else ThemeTokens.TEXT_BODY)
            background = rounded(
                if (accent) ThemeTokens.ACCENT_BLUE else TILE_BG,
                10f,
                if (accent) null else CARD_BORDER,
                if (accent) 0f else 0.8f
            )
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.96f)
            setOnClickListener { onClick() }
        }
    }

    private fun simStatusAppearance(simStatus: String): Triple<String, Int, Int> = when (simStatus.trim()) {
        "1" -> Triple("已认证", SUCCESS_GREEN, SUCCESS_TINT_BG)
        "2" -> Triple("认证失败", ThemeTokens.DANGER, DANGER_TINT_BG)
        "3" -> Triple("认证中", ThemeTokens.ACCENT_LIGHT, ACCENT_TINT_BG)
        else -> Triple("未认证", ThemeTokens.TEXT_SUB, MUTED_TINT_BG)
    }

    private fun loadingHint(message: String): TextView = text(message, 13.5f, ThemeTokens.TEXT_SUB).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(44), dp(16), dp(44))
    }

    private fun errorBanner(message: String): TextView = text(message, 13f, ThemeTokens.DANGER).apply {
        background = rounded(DANGER_TINT_BG, 14f, DANGER_BORDER, 0.8f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setLineSpacing(0f, 1.35f)
    }

    private fun defaultAuthWindowMinutes(): Pair<String, String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        return fmt.format(start.time) to fmt.format(end.time)
    }

    private fun formatAuthTime(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "--"
        return if (trimmed.length >= 16 && trimmed[4] == '-' && trimmed[13] == ':') {
            trimmed.substring(0, 16)
        } else {
            trimmed
        }
    }

    private fun normalizeDateOnly(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.length >= 10 && trimmed[4] == '-' && trimmed[7] == '-') {
            trimmed.substring(0, 10)
        } else {
            trimmed
        }
    }

    private fun compareMinuteDateTime(a: String, b: String): Int {
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val da = fmt.parse(formatAuthTime(a)) ?: return a.compareTo(b)
            val db = fmt.parse(formatAuthTime(b)) ?: return a.compareTo(b)
            da.compareTo(db)
        }.getOrElse { a.compareTo(b) }
    }

    private fun maskVin(vin: String): String {
        val clean = vin.trim().uppercase(Locale.US)
        return if (clean.length >= 10) "${clean.take(4)}****${clean.takeLast(4)}" else clean
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create("sans-serif", style)
        includeFontPadding = false
    }

    private fun rounded(
        fillColor: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 0f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        cornerRadius = dpF(radiusDp)
        if (strokeColor != null && strokeWidthDp > 0f) {
            setStroke(dpF(strokeWidthDp).roundToInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun ovalDrawable(fillColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fillColor)
    }

    private fun lp(
        top: Int = 0,
        bottom: Int = 0,
        height: Int = WRAP_CONTENT
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(MATCH_PARENT, height).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun dpF(value: Float): Float = value * context.resources.displayMetrics.density

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        private val CARD_BG = Color.parseColor("#1C1E24")
        private val TILE_BG = Color.parseColor("#232630")
        private val HERO_BANNER_BG = Color.parseColor("#15171D")
        private val CARD_BORDER = Color.parseColor("#2B2F3A")
        private val HINT_COLOR = Color.parseColor("#5C606A")
        private val SUCCESS_GREEN = Color.rgb(52, 211, 153)
        private val SUCCESS_TINT_BG = Color.parseColor("#163327")
        private val ACCENT_TINT_BG = Color.parseColor("#182A49")
        private val MUTED_TINT_BG = Color.parseColor("#2A2D36")
        private val DANGER_TINT_BG = Color.parseColor("#2B1C20")
        private val DANGER_BORDER = Color.parseColor("#522B31")
    }
}
