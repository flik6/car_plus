package com.chery.carplus.ui

import android.app.Dialog
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
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.chery.carplus.cloud.AuthorizationOperationRecordItem
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.CherySessionExpiredException
import com.chery.carplus.cloud.VehicleAuthorizationItem
import com.chery.carplus.cloud.VehicleSummary
import com.chery.carplus.cloud.VehicleTrafficInfo
import com.chery.carplus.cloud.VehicleUpgradeInfo
import com.chery.carplus.cloud.VehicleUpgradeStatus
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
 *    top-right in-place edit 903, operation records 137, bottom danger revoke 904) / 「添加驾驶员」 (902).
 * 2. 「车辆管理」 (Level 1: 1 clean card per vehicle) -> unified 「车辆信息」 (Level 2: live status,
 *    top-right in-place plate/owner/purchase-date edit 933, hardware archive, OTA upgrade,
 *    set default 932, bottom cancel cert 934 & unbind 931) / 「添加车辆」 (930).
 */
internal class VehicleManagementScreens(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val httpClient: CheryHttpClient,
    private val profileProvider: () -> VehicleProfile,
    private val onPushOverlaySubpage: (view: View, onDestroy: (() -> Unit)?) -> Unit,
    private val onPopOverlaySubpage: () -> Unit,
    private val onSelectVehicleSummary: (VehicleSummary) -> Unit,
    private val onVehicleInfoSaved: (vin: String, plateNo: String, hostName: String, purchaseDate: String) -> Unit,
    private val onVehicleUnbound: (vin: String) -> Unit,
    private val onStatusMessage: (String) -> Unit,
    private val vehicleLiveSpecsProvider: (vin: String) -> List<Pair<String, String>> = { emptyList() },
    private val onOpenVehicleUpgrade: (VehicleUpgradeInfo?) -> Unit = {}
) {

    private var activeDialog: Dialog? = null
    private var activeDropdown: PopupWindow? = null
    private val cachedVehiclesByVin = mutableMapOf<String, VehicleSummary>()
    private var cachedVehicleSession = ""
    private var openVehicleInfoRefresh: (() -> Unit)? = null

    fun refreshOpenVehicleInfo() {
        openVehicleInfoRefresh?.invoke()
    }

    fun cachedVehicleSummary(vin: String): VehicleSummary? {
        if (cachedVehicleSession != profileProvider().accessToken) return null
        return cachedVehiclesByVin[vin.trim().uppercase(Locale.US)]
    }

    fun rememberVehicleSummaries(vehicles: List<VehicleSummary>, session: String) {
        if (session.isBlank() || session != profileProvider().accessToken) return
        if (session != cachedVehicleSession) {
            cachedVehiclesByVin.clear()
            cachedVehicleSession = session
        }
        vehicles.filter { it.vin.isNotBlank() }.forEach { vehicle ->
            cachedVehiclesByVin[vehicle.vin.trim().uppercase(Locale.US)] = vehicle
        }
    }

    fun dismissAll() {
        runCatching { activeDialog?.dismiss() }
        activeDialog = null
        runCatching { activeDropdown?.dismiss() }
        activeDropdown = null
        openVehicleInfoRefresh = null
        cachedVehiclesByVin.clear()
        cachedVehicleSession = ""
    }

    // =========================================================================
    // 1. 「驾驶员」一级界面 (TopBar: 驾驶员) -> 二级界面「驾驶员详情」/「添加驾驶员」
    // =========================================================================

    fun openRemoteAuthManagementScreen() = openDriversScreen()

    fun openDriversScreen() {
        val profile = profileProvider()
        val targetVin = profile.vin.trim().uppercase(Locale.US)

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
        AppSystemInsets.applyBottomNavPadding(scroll)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listHost, lp())

        var reloadList: () -> Unit = {}
        reloadList = {
            listHost.removeAllViews()
            listHost.addView(loadingHint("正在加载驾驶员列表..."))
            val current = profileProvider()
            if (targetVin.isBlank() || current.accessToken.isBlank()) {
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
                        if (items.isEmpty()) {
                            listHost.addView(buildEmptyDriversGuideCard(hasVehicle = true))
                        } else {
                            items.forEachIndexed { idx, item ->
                                listHost.addView(
                                    buildDriverSummaryCard(
                                        item = item,
                                        vin = targetVin,
                                        onChanged = reloadList
                                    ),
                                    lp(top = if (idx == 0) 0 else dp(12))
                                )
                            }
                        }
                    }.onFailure { err ->
                        if (err is CherySessionExpiredException) return@onFailure
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
     * Level-2 Driver Detail screen (TopBar: 驾驶员详情 + right "编辑" / "保存"):
     * Clean borderless dark layout matching Settings/Profile. Tapping top-right "编辑"
     * converts only editable fields (姓名、授权时段) into in-place inputs + wheel range picker.
     */
    private fun openDriverDetailScreen(
        item: VehicleAuthorizationItem,
        vin: String,
        onChanged: () -> Unit
    ) {
        var currentEnabled = item.enabled
        var currentName = item.receiverAccountName.ifBlank { item.receiverMobile.ifBlank { "驾驶员" } }
        var currentStart = formatAuthTime(item.startTime).takeUnless { it == "--" }.orEmpty()
        var currentEnd = formatAuthTime(item.endTime).takeUnless { it == "--" }.orEmpty()
        var isEditing = false
        var saving = false
        var toggling = false

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }
        val topBarHost = FrameLayout(context)
        root.addView(topBarHost, lp())

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(scroll)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(32))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        var draftNameInput: EditText? = null
        var getDraftRange: (() -> Pair<String, String>)? = null

        lateinit var renderScreen: () -> Unit

        fun submitDriverEdit() {
            if (saving) return
            val cleanName = draftNameInput?.text?.toString()?.trim().orEmpty()
            val (cleanStart, cleanEnd) = getDraftRange?.invoke() ?: (currentStart to currentEnd)
            if (cleanName.isBlank()) {
                AppHaptics.perform(body, HapticLevel.ERROR_REJECT)
                toast("请输入驾驶员姓名")
                return
            }
            if (cleanStart.isBlank() || cleanEnd.isBlank() || compareMinuteDateTime(cleanEnd, cleanStart) <= 0) {
                AppHaptics.perform(body, HapticLevel.ERROR_REJECT)
                toast("结束时间必须晚于开始时间")
                return
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
                        AppHaptics.perform(body, HapticLevel.MEDIUM_CONFIRM)
                        currentName = cleanName
                        currentStart = cleanStart
                        currentEnd = cleanEnd
                        isEditing = false
                        toast("驾驶员信息已保存")
                        renderScreen()
                        onChanged()
                    } else {
                        toast("保存失败")
                    }
                }.onFailure { err ->
                    if (err !is CherySessionExpiredException) {
                        toast("保存失败：${err.message.orEmpty().take(80)}")
                    }
                }
            }
        }

        renderScreen = {
            draftNameInput = null
            getDraftRange = null

            topBarHost.removeAllViews()
            topBarHost.addView(
                AppComponents.createTopBar(
                    context = context,
                    title = "驾驶员详情",
                    onBack = {
                        if (isEditing) {
                            isEditing = false
                            renderScreen()
                        } else {
                            onPopOverlaySubpage()
                        }
                    },
                    rightText = if (isEditing) "保存" else "编辑",
                    onRightClick = {
                        if (isEditing) {
                            submitDriverEdit()
                        } else {
                            isEditing = true
                            renderScreen()
                        }
                    }
                ),
                FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )

            body.removeAllViews()

            // Compact Hero Identity
            val hero = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(18))
            }
            hero.addView(
                text(currentName, 22f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
            val statusSub = listOf(
                if (currentEnabled) "已启用" else "已停用",
                item.permissionLabel
            ).filter { it.isNotBlank() }.joinToString(" · ")
            hero.addView(text(statusSub, 13.5f, ThemeTokens.TEXT_SUB), lp(top = dp(5)))
            body.addView(hero, lp())
            body.addView(AppComponents.createHairline(context, startInsetDp = 20))

            // Enable Toggle Row (view mode)
            if (!isEditing) {
                val enableSwitch = ToggleSwitch(context).apply {
                    checked = currentEnabled
                    onToggleAttempt = { targetEnabled ->
                        if (!toggling) {
                            toggling = true
                            setChecked(targetEnabled, animate = true)
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
                                        renderScreen()
                                        onChanged()
                                    } else {
                                        setChecked(currentEnabled, animate = true)
                                        toast("状态切换失败")
                                    }
                                }.onFailure { err ->
                                    setChecked(currentEnabled, animate = true)
                                    if (err !is CherySessionExpiredException) {
                                        toast("操作失败：${err.message.orEmpty().take(70)}")
                                    }
                                }
                            }
                        }
                    }
                }
                body.addView(
                    AppComponents.createListRow(
                        context = context,
                        icon = null,
                        title = "启用权限",
                        subtitle = "关闭后将临时暂停对方的查车与控车",
                        trailingView = enableSwitch,
                        showChevron = false
                    ),
                    lp()
                )
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            }

            // Section: 授权信息 (editable fields become in-place inputs in edit mode)
            body.addView(AppComponents.createSectionHeader(context, "授权信息"), lp())

            if (isEditing) {
                val (nameBox, nameInput) = AppComponents.createUnderlineInput(
                    context = context,
                    hint = "请输入驾驶员姓名",
                    initialValue = currentName,
                    inputType = InputType.TYPE_CLASS_TEXT,
                    maxLength = 20
                )
                draftNameInput = nameInput
                body.addView(buildInlineEditRow("姓名", nameBox), lp())
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            } else {
                body.addView(buildBorderlessInfoRow("姓名", currentName), lp())
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            }

            body.addView(
                buildBorderlessInfoRow(
                    label = "手机号",
                    value = item.receiverMobile.ifBlank { SensitiveUiText.MISSING },
                    dimmed = isEditing
                ),
                lp()
            )
            body.addView(AppComponents.createHairline(context, startInsetDp = 20))

            body.addView(
                buildBorderlessInfoRow(
                    label = "授权权限",
                    value = item.permissionLabel,
                    dimmed = isEditing
                ),
                lp()
            )
            body.addView(AppComponents.createHairline(context, startInsetDp = 20))

            if (isEditing) {
                val (rangeBox, rangeProvider) = AppComponents.createDateTimeRangePickerField(
                    context = context,
                    hint = "请选择授权时段",
                    initialStart = currentStart,
                    initialEnd = currentEnd,
                    mode = WheelDateTimeMode.DATE_TIME_RANGE,
                    title = "选择授权时段"
                )
                getDraftRange = rangeProvider
                body.addView(buildInlineEditRow("授权时段", rangeBox), lp())
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            } else {
                body.addView(buildBorderlessInfoRow("开始时间", currentStart.ifBlank { SensitiveUiText.MISSING }), lp())
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
                body.addView(buildBorderlessInfoRow("结束时间", currentEnd.ifBlank { SensitiveUiText.MISSING }), lp())
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            }

            if (!isEditing) {
                body.addView(
                    AppComponents.createListRow(
                        context = context,
                        icon = AppIconType.CLOCK,
                        title = "操作记录",
                        showChevron = true,
                        onClick = { openDriverOperationRecordsScreen(item = item, vin = vin) }
                    ),
                    lp()
                )

                body.addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
                var revokingAuth = false
                lateinit var revokeBtn: TextView
                revokeBtn = AppComponents.createActionButton(context, "撤销驾驶员授权", ButtonStyle.DANGER_TEXT) {
                    if (revokingAuth) return@createActionButton
                    activeDialog?.dismiss()
                    activeDialog = AppComponents.showConfirmDialog(
                        context = context,
                        title = "撤销驾驶员授权",
                        message = "确认撤销「$currentName」的驾驶员权限？撤销后对方将无法查看车况或远程控车。",
                        confirmText = "确认撤销",
                        cancelText = "取消",
                        dangerConfirm = true
                    ) {
                        if (revokingAuth) return@showConfirmDialog
                        revokingAuth = true
                        revokeBtn.isEnabled = false
                        revokeBtn.alpha = 0.5f
                        revokeBtn.text = "正在撤销..."
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
                            revokingAuth = false
                            revokeBtn.isEnabled = true
                            revokeBtn.alpha = 1f
                            revokeBtn.text = "撤销驾驶员授权"
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
                body.addView(revokeBtn, lp(top = dp(4), height = dp(54)))
            }
        }

        renderScreen()
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
        AppSystemInsets.applyBottomNavPadding(scroll)
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

        formCard.addView(formFieldHeader("PIN 码", required = true), lp(top = dp(18)))
        val (pinRow, pinInput) = createPasswordToggleUnderlineInput("请输入 6 位 PIN 码", maxLength = 6)
        formCard.addView(pinRow, lp(top = dp(2)))

        formCard.addView(formFieldHeader("确认 PIN 码", required = true), lp(top = dp(18)))
        val (confirmRow, confirmPinInput) = createPasswordToggleUnderlineInput("请再次输入 PIN 码", maxLength = 6)
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
        permRow.addView(optViewOnly.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        permRow.addView(optViewAndControl.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.35f).apply {
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
                    toast("请输入 6 位数字 PIN 码")
                    return@buildPinnedBottomCta
                }
                if (pin != confirm) {
                    toast("两次输入的 PIN 码不一致")
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
        AppSystemInsets.applyBottomNavPadding(scroll)
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
    // 2. 「车辆管理」一级界面 (一车一卡) -> 统一「车辆信息」/「添加车辆」
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
        AppSystemInsets.applyBottomNavPadding(scroll)
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
                        rememberVehicleSummaries(loadedVehicles, active.accessToken)
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
     * Level-1 Vehicle Management Card: one restrained summary card per vehicle.
     * It intentionally exposes only model, plate and one status; detail and privacy-masked
     * identifiers belong to the Level-2 screen.
     */
    private fun buildVehicleSummaryCard(
        vehicle: VehicleSummary,
        onReload: () -> Unit
    ): LinearLayout {
        val activeVin = profileProvider().vin.trim().uppercase(Locale.US)
        val isCurrentActive = vehicle.vin.equals(activeVin, ignoreCase = true)
        val modelTitle = VehicleDisplayNames.resolve(vehicle)
        val (statusText, statusFg, statusBg) = when {
            isCurrentActive -> Triple("当前控车", ThemeTokens.ACCENT_LIGHT, ACCENT_TINT_BG)
            vehicle.isSelected -> Triple("默认车辆", ThemeTokens.TEXT_PRIMARY, ThemeTokens.ACCENT_BLUE)
            !vehicle.isBoundVehicle -> Triple("被授权", ThemeTokens.ACCENT_LIGHT, ACCENT_TINT_BG)
            vehicle.simStatus.isNotBlank() -> simStatusAppearance(vehicle.simStatus)
            else -> Triple("已绑定", ThemeTokens.TEXT_SUB, MUTED_TINT_BG)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = if (isCurrentActive) {
                rounded(CARD_BG, 18f, ThemeTokens.ACCENT_BLUE, 1.0f)
            } else {
                rounded(CARD_BG, 18f, CARD_BORDER, 0.8f)
            }
            minimumHeight = dp(92)
            setPadding(dp(18), dp(15), dp(16), dp(15))
            isClickable = true
            isFocusable = true
            contentDescription = "$modelTitle，${vehicle.plateNo.ifBlank { "未设置车牌号" }}，$statusText"
            MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)
            setOnClickListener {
                openVehicleInfoScreen(
                    initialVehicle = vehicle,
                    vinFallback = vehicle.vin,
                    onUpdated = onReload
                )
            }
        }

        // Row 1: Model + one status + the single detail entry affordance.
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            text(modelTitle, 19.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        topRow.addView(
            TextView(context).apply {
                text = statusText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(statusFg)
                background = rounded(statusBg, 7f)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
        )
        topRow.addView(
            AppIconView(context, AppIconType.CHEVRON_RIGHT, Color.parseColor("#676C78")),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) }
        )
        card.addView(topRow, lp())

        card.addView(
            text(
                vehicle.plateNo.ifBlank { "未设置车牌号" },
                13.5f,
                if (vehicle.plateNo.isNotBlank()) ThemeTokens.TEXT_BODY else ThemeTokens.TEXT_SUB
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            lp(top = dp(8))
        )

        return card
    }

    /**
     * Unified Vehicle Info screen (TopBar: 车辆信息 + right "编辑" / "保存"):
     * Consolidates the previous Vehicle Info and Vehicle Detail screens into one clean
     * borderless screen: live telemetry status, top-right in-place editable fields,
     * masked vehicle identifiers, OTA upgrade, and bottom destructive actions.
     */
    fun openEditVehicleScreen(
        initialVehicle: VehicleSummary? = null,
        vinFallback: String = "",
        onUpdated: () -> Unit = {}
    ) = openVehicleInfoScreen(
        initialVehicle = initialVehicle,
        vinFallback = vinFallback,
        startInEditMode = true,
        onUpdated = onUpdated
    )

    fun openVehicleDetailScreen(
        initialVehicle: VehicleSummary? = null,
        vinFallback: String = "",
        startInEditMode: Boolean = false,
        onUpdated: () -> Unit = {}
    ) = openVehicleInfoScreen(
        initialVehicle = initialVehicle,
        vinFallback = vinFallback,
        startInEditMode = startInEditMode,
        onUpdated = onUpdated
    )

    fun openVehicleInfoScreen(
        initialVehicle: VehicleSummary? = null,
        vinFallback: String = "",
        startInEditMode: Boolean = false,
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
        val topBarHost = FrameLayout(context)
        topBarHost.addView(
            AppComponents.createTopBar(
                context = context,
                title = "车辆信息",
                onBack = { onPopOverlaySubpage() }
            ),
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        root.addView(topBarHost, lp())

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(scroll)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(36))
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        var isEditing = startInEditMode
        var saving = false
        var settingDefaultVehicle = false
        var currentVehicle: VehicleSummary? = initialVehicle
        var cachedUpgradeInfo: VehicleUpgradeInfo? = null
        var upgradeCheckedOnce = false
        var upgradeChecking = false
        var upgradeCheckFailed = false
        var cachedTrafficInfo: VehicleTrafficInfo? = null
        var trafficFetchedOnce = false
        var trafficFetching = false
        var applyTrafficToCurrentRow: ((VehicleTrafficInfo?) -> Unit)? = null
        var refreshVersionCurrentRow: (() -> Unit)? = null

        fun renderDetail(vehicle: VehicleSummary) {
            currentVehicle = vehicle
            rememberVehicleSummaries(listOf(vehicle), activeProfile.accessToken)
            val activeVin = profileProvider().vin.trim().uppercase(Locale.US)
            val isCurrentActive = vehicle.vin.equals(activeVin, ignoreCase = true)
            val modelTitle = VehicleDisplayNames.resolve(vehicle)
            val canEdit = vehicle.isBoundVehicle
            if (!canEdit) isEditing = false
            var liveSimStatus = vehicle.simStatus

            var plateInputRef: EditText? = null
            var ownerInputRef: EditText? = null
            var getPurchaseDateRef: (() -> String)? = null

            fun launchCancelCertificationFlow() {
                activeDialog?.dismiss()
                activeDialog = AppComponents.showConfirmDialog(
                    context = context,
                    title = "取消车辆实名认证",
                    message = "取消认证后将影响车联网通信及远程控车功能，需验证 6 位 PIN 码。确定要取消认证吗？",
                    confirmText = "继续验证",
                    cancelText = "取消",
                    dangerConfirm = true
                ) {
                    activeDialog = SixDigitPinSheet.showVerifyPinSheet(
                        context = context,
                        title = "验证 PIN 码",
                        subtitle = "请输入 6 位 PIN 码以取消车辆认证"
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
                                    toast("已取消车辆实名认证")
                                    renderDetail(vehicle.copy(simStatus = "0"))
                                    onUpdated()
                                } else {
                                    showError("PIN 码验证失败，请重试")
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

            fun launchUnbindVehicleFlow() {
                activeDialog?.dismiss()
                activeDialog = AppComponents.showConfirmDialog(
                    context = context,
                    title = "解除车辆绑定",
                    message = "解除绑定后，当前账号将移除该车辆的所有远程控车与数字钥匙权限，需验证 6 位 PIN 码。确定继续吗？",
                    confirmText = "继续解绑",
                    cancelText = "取消",
                    dangerConfirm = true
                ) {
                    activeDialog = SixDigitPinSheet.showVerifyPinSheet(
                        context = context,
                        title = "验证 PIN 码",
                        subtitle = "请输入 6 位 PIN 码以解除车辆绑定"
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
                                    showError("PIN 码验证失败，请重试")
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

            fun submitVehicleSave() {
                if (saving) return
                val cleanPlate = plateInputRef?.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
                val cleanHost = ownerInputRef?.text?.toString()?.trim().orEmpty()
                val cleanDate = normalizeDateOnly(getPurchaseDateRef?.invoke().orEmpty())
                if (cleanPlate.isBlank()) {
                    AppHaptics.perform(body, HapticLevel.ERROR_REJECT)
                    toast("请输入车牌号")
                    return
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
                            AppHaptics.perform(body, HapticLevel.MEDIUM_CONFIRM)
                            isEditing = false
                            onVehicleInfoSaved(vehicle.vin, cleanPlate, cleanHost, cleanDate)
                            onStatusMessage("车辆信息已保存")
                            toast("车辆信息已保存")
                            renderDetail(
                                vehicle.copy(
                                    plateNo = cleanPlate,
                                    hostName = cleanHost,
                                    purchaseTime = cleanDate,
                                    simStatus = liveSimStatus
                                )
                            )
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

            topBarHost.removeAllViews()
            topBarHost.addView(
                AppComponents.createTopBar(
                    context = context,
                    title = "车辆信息",
                    onBack = {
                        if (isEditing) {
                            isEditing = false
                            renderDetail(vehicle)
                        } else {
                            onPopOverlaySubpage()
                        }
                    },
                    rightText = if (!canEdit) null else if (isEditing) "保存" else "编辑",
                    onRightClick = if (!canEdit) null else {
                        {
                            if (isEditing) {
                                submitVehicleSave()
                            } else {
                                isEditing = true
                                renderDetail(vehicle)
                            }
                        }
                    }
                ),
                FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )

            body.removeAllViews()

            // Hero Header: Model name + inline certification tag + trailing "更多" overflow menu
            val hero = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(12), dp(16))
            }
            val heroTopRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heroTopRow.addView(
                text(modelTitle, 22f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )

            val (tagText, tagFg, tagBg) = if (vehicle.isBoundVehicle) {
                simStatusAppearance(liveSimStatus)
            } else {
                Triple("被授权", ThemeTokens.ACCENT_LIGHT, ACCENT_TINT_BG)
            }
            heroTopRow.addView(
                TextView(context).apply {
                    text = tagText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(tagFg)
                    background = rounded(tagBg, 6f)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = dp(10)
                }
            )
            heroTopRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

            if (vehicle.isBoundVehicle && !isEditing) {
                val moreBtn = FrameLayout(context).apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = "更多车辆操作"
                    addView(
                        AppIconView(context, AppIconType.MORE_HORIZ, ThemeTokens.TEXT_SUB),
                        FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                    )
                }
                MotionSpec.attachPressPhysics(moreBtn, pressedScale = 0.88f)
                moreBtn.setOnClickListener { anchor ->
                    activeDropdown?.dismiss()
                    val options = buildList {
                        if (liveSimStatus == "1") {
                            add(
                                DropdownOption(
                                    id = "cancel_cert",
                                    title = "取消实名认证",
                                    subtitle = "取消后将影响车联网远程控车",
                                    danger = true
                                )
                            )
                        }
                        add(
                            DropdownOption(
                                id = "unbind_vehicle",
                                title = "解除车辆绑定",
                                subtitle = "移除车辆的控车与数字钥匙权限",
                                danger = true
                            )
                        )
                    }
                    activeDropdown = AppComponents.showAnchoredDropdown(
                        context = context,
                        anchor = anchor,
                        options = options,
                        widthDp = 228,
                        alignEnd = true
                    ) { selected ->
                        when (selected.id) {
                            "cancel_cert" -> launchCancelCertificationFlow()
                            "unbind_vehicle" -> launchUnbindVehicleFlow()
                        }
                    }
                }
                heroTopRow.addView(moreBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            hero.addView(heroTopRow, lp())

            if (!vehicle.isBoundVehicle && vehicle.authDateListSummary.isNotBlank()) {
                hero.addView(
                    text("授权时段：${vehicle.authDateListSummary}", 12.5f, ThemeTokens.TEXT_SUB),
                    lp(top = dp(6))
                )
            }

            if (!isEditing) {
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
                if (vehicle.isBoundVehicle && !vehicle.isSelected) {
                    lateinit var setDefaultChip: TextView
                    setDefaultChip = cardActionChip("设为默认车辆", accent = false) {
                        if (settingDefaultVehicle) return@cardActionChip
                        settingDefaultVehicle = true
                        setDefaultChip.isEnabled = false
                        setDefaultChip.alpha = 0.55f
                        setDefaultChip.text = "设置中..."
                        val active = profileProvider()
                        scope.launch {
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    httpClient.setDefaultVehicle(active.accessToken, vehicle.vin)
                                }
                            }
                            settingDefaultVehicle = false
                            setDefaultChip.isEnabled = true
                            setDefaultChip.alpha = 1f
                            setDefaultChip.text = "设为默认车辆"
                            res.onSuccess { ok ->
                                if (ok) {
                                    AppHaptics.perform(hero, HapticLevel.MEDIUM_CONFIRM)
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
                    }
                    quickRow.addView(
                        setDefaultChip,
                        LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                            if (quickRow.childCount > 0) marginStart = dp(10)
                        }
                    )
                }
                if (quickRow.childCount > 0) {
                    hero.addView(
                        quickRow,
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                            topMargin = dp(14)
                            marginEnd = dp(8)
                        }
                    )
                }
            }
            body.addView(hero, lp())
            body.addView(AppComponents.createHairline(context, startInsetDp = 20))

            // Section 1: 基本信息 (the 3 editable fields switch cleanly in-place + 车辆版本 row)
            body.addView(AppComponents.createSectionHeader(context, "基本信息"), lp())

            if (isEditing) {
                val (plateBox, plateInput) = AppComponents.createUnderlineInput(
                    context = context,
                    hint = "请输入车牌号",
                    initialValue = vehicle.plateNo,
                    inputType = InputType.TYPE_CLASS_TEXT,
                    maxLength = 12
                )
                plateInputRef = plateInput
                body.addView(buildInlineEditRow("车牌号", plateBox), lp())
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))

                val (ownerBox, ownerInput) = AppComponents.createUnderlineInput(
                    context = context,
                    hint = "请输入车主姓名",
                    initialValue = vehicle.hostName,
                    inputType = InputType.TYPE_CLASS_TEXT,
                    maxLength = 24
                )
                ownerInputRef = ownerInput
                body.addView(buildInlineEditRow("车主姓名", ownerBox), lp())
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))

                val (purchaseDateBox, getPurchaseDate) = AppComponents.createDateTimePickerField(
                    context = context,
                    hint = "请选择购车时间",
                    initialValue = normalizeDateOnly(vehicle.purchaseTime),
                    mode = DateTimeMode.DATE,
                    title = "选择购车时间"
                )
                getPurchaseDateRef = getPurchaseDate
                body.addView(buildInlineEditRow("购车时间", purchaseDateBox), lp())
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            } else {
                body.addView(
                    buildBorderlessInfoRow("车牌号", vehicle.plateNo.ifBlank { "未设置" }),
                    lp()
                )
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
                body.addView(
                    buildBorderlessInfoRow("动力类型", vehicle.powertrain.label),
                    lp()
                )
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
                body.addView(
                    buildBorderlessInfoRow("车主姓名", SensitiveUiText.personName(vehicle.hostName)),
                    lp()
                )
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
                body.addView(
                    buildBorderlessInfoRow("购车时间", SensitiveUiText.year(vehicle.purchaseTime)),
                    lp()
                )
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))

                // 车机流量套餐 & 流量有效期 (zyhl 967)
                if (isCurrentActive) {
                    val trafficRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(52)
                        setPadding(dp(20), dp(10), dp(20), dp(10))
                    }
                    trafficRow.addView(
                        text("车机流量", 14.5f, ThemeTokens.TEXT_SUB),
                        LinearLayout.LayoutParams(dp(96), WRAP_CONTENT)
                    )
                    trafficRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    val trafficValueTv = text("查询中...", 14.5f, ThemeTokens.TEXT_SUB).apply {
                        gravity = Gravity.END
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    trafficRow.addView(trafficValueTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                    body.addView(trafficRow, lp())
                    body.addView(AppComponents.createHairline(context, startInsetDp = 20))

                    val trafficExpiryRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(52)
                        setPadding(dp(20), dp(10), dp(20), dp(10))
                    }
                    trafficExpiryRow.addView(
                        text("流量有效期", 14.5f, ThemeTokens.TEXT_SUB),
                        LinearLayout.LayoutParams(dp(96), WRAP_CONTENT)
                    )
                    trafficExpiryRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    val trafficExpiryValueTv = text("查询中...", 14.5f, ThemeTokens.TEXT_SUB).apply {
                        gravity = Gravity.END
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    trafficExpiryRow.addView(trafficExpiryValueTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                    body.addView(trafficExpiryRow, lp())
                    body.addView(AppComponents.createHairline(context, startInsetDp = 20))

                    fun formatTrafficMb(raw: String): String {
                        val mb = raw.trim().toDoubleOrNull() ?: return raw.trim()
                        if (mb >= 1024.0) {
                            val gb = mb / 1024.0
                            return if (kotlin.math.abs(gb - gb.roundToInt()) < 0.05) {
                                "${gb.roundToInt()} GB"
                            } else {
                                String.format(Locale.US, "%.1f GB", gb)
                            }
                        }
                        return if (kotlin.math.abs(mb - mb.roundToInt()) < 0.05) {
                            "${mb.roundToInt()} MB"
                        } else {
                            String.format(Locale.US, "%.1f MB", mb)
                        }
                    }

                    fun applyTrafficToRow(traffic: VehicleTrafficInfo?) {
                        if (traffic == null) {
                            trafficValueTv.text = "暂不可用"
                            trafficValueTv.setTextColor(ThemeTokens.TEXT_SUB)
                            trafficExpiryValueTv.text = "—"
                            trafficExpiryValueTv.setTextColor(ThemeTokens.TEXT_SUB)
                            return
                        }
                        val formatted = when {
                            traffic.unlimited -> "无限流量"
                            traffic.totalTraffic.isNotBlank() -> {
                                val usedStr = formatTrafficMb(traffic.usedTraffic.ifBlank { "0" })
                                val totalStr = formatTrafficMb(traffic.totalTraffic)
                                "$usedStr / $totalStr"
                            }
                            traffic.usedTraffic.isNotBlank() -> "已用 ${formatTrafficMb(traffic.usedTraffic)}"
                            else -> "未开通"
                        }
                        trafficValueTv.text = formatted
                        trafficValueTv.setTextColor(ThemeTokens.TEXT_PRIMARY)

                        val dueDate = traffic.dueDate.trim().take(10)
                        if (dueDate.isNotBlank()) {
                            trafficExpiryValueTv.text = dueDate
                            trafficExpiryValueTv.setTextColor(ThemeTokens.TEXT_PRIMARY)
                        } else {
                            trafficExpiryValueTv.text = "—"
                            trafficExpiryValueTv.setTextColor(ThemeTokens.TEXT_SUB)
                        }
                    }

                    applyTrafficToCurrentRow = ::applyTrafficToRow

                    if (trafficFetchedOnce) {
                        applyTrafficToRow(cachedTrafficInfo)
                    } else if (!trafficFetching) {
                        trafficFetching = true
                        scope.launch {
                            val active = profileProvider()
                            if (active.accessToken.isBlank()) {
                                trafficFetching = false
                                applyTrafficToCurrentRow?.invoke(null)
                                return@launch
                            }
                            val traffic = runCatching {
                                withContext(Dispatchers.IO) {
                                    httpClient.fetchVehicleTrafficPackage(vehicle.vin, active.accessToken)
                                }
                            }.getOrNull()
                            trafficFetching = false
                            trafficFetchedOnce = true
                            cachedTrafficInfo = traffic
                            applyTrafficToCurrentRow?.invoke(traffic)
                        }
                    }
                }

                // 车辆版本 row inside 基本信息: opens upgrade sheet if update exists, else shows toast
                if (isCurrentActive) {
                    val versionRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(52)
                        setPadding(dp(20), dp(10), dp(20), dp(10))
                        isClickable = true
                        isFocusable = true
                    }
                    MotionSpec.attachPressPhysics(versionRow, pressedScale = 0.985f, haptic = false)
                    versionRow.addView(
                        text("车辆版本", 14.5f, ThemeTokens.TEXT_SUB),
                        LinearLayout.LayoutParams(dp(96), WRAP_CONTENT)
                    )
                    versionRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

                    val versionDot = View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(ThemeTokens.ACCENT_LIGHT)
                        }
                        visibility = View.GONE
                    }
                    versionRow.addView(
                        versionDot,
                        LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) }
                    )

                    val versionValueTv = text("检查中...", 14.5f, ThemeTokens.TEXT_SUB).apply {
                        gravity = Gravity.END
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    versionRow.addView(versionValueTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

                    val versionChevron = AppIconView(context, AppIconType.CHEVRON_RIGHT, Color.parseColor("#5C606A")).apply {
                        visibility = View.GONE
                    }
                    versionRow.addView(
                        versionChevron,
                        LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginStart = dp(4) }
                    )

                    fun refreshVersionRowUi() {
                        val info = cachedUpgradeInfo
                        val dotDrawable = versionDot.background as? GradientDrawable
                        when {
                            upgradeChecking -> {
                                versionDot.visibility = View.GONE
                                versionChevron.visibility = View.GONE
                                versionValueTv.text = "检查中..."
                                versionValueTv.setTextColor(ThemeTokens.TEXT_SUB)
                                versionValueTv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                            }
                            info != null && info.upgradeStatus == VehicleUpgradeStatus.AVAILABLE -> {
                                dotDrawable?.setColor(ThemeTokens.ACCENT_LIGHT)
                                versionDot.visibility = View.VISIBLE
                                versionChevron.visibility = View.VISIBLE
                                val verPrefix = info.currentVersion.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
                                versionValueTv.text = "${verPrefix}有新版本"
                                versionValueTv.setTextColor(ThemeTokens.ACCENT_LIGHT)
                                versionValueTv.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                            }
                            info != null && info.upgradeStatus == VehicleUpgradeStatus.SCHEDULED -> {
                                dotDrawable?.setColor(ThemeTokens.ACCENT_LIGHT)
                                versionDot.visibility = View.VISIBLE
                                versionChevron.visibility = View.VISIBLE
                                val verPrefix = info.currentVersion.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
                                versionValueTv.text = "${verPrefix}已预约升级"
                                versionValueTv.setTextColor(ThemeTokens.ACCENT_LIGHT)
                                versionValueTv.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                            }
                            info != null && info.upgradeStatus == VehicleUpgradeStatus.UPGRADING -> {
                                dotDrawable?.setColor(ThemeTokens.ACCENT_LIGHT)
                                versionDot.visibility = View.VISIBLE
                                versionChevron.visibility = View.VISIBLE
                                versionValueTv.text = "升级中"
                                versionValueTv.setTextColor(ThemeTokens.ACCENT_LIGHT)
                                versionValueTv.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                            }
                            info != null && info.upgradeStatus == VehicleUpgradeStatus.PREPARING -> {
                                dotDrawable?.setColor(ThemeTokens.ACCENT_LIGHT)
                                versionDot.visibility = View.VISIBLE
                                versionChevron.visibility = View.VISIBLE
                                versionValueTv.text = "等待升级中"
                                versionValueTv.setTextColor(ThemeTokens.ACCENT_LIGHT)
                                versionValueTv.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                            }
                            info != null && (info.upgradeStatus == VehicleUpgradeStatus.RETRYABLE_FAILED ||
                                info.upgradeStatus == VehicleUpgradeStatus.UNFINISHED ||
                                info.upgradeStatus == VehicleUpgradeStatus.FAILED) -> {
                                dotDrawable?.setColor(ThemeTokens.DANGER)
                                versionDot.visibility = View.VISIBLE
                                versionChevron.visibility = View.VISIBLE
                                versionValueTv.text = if (info.upgradeStatus == VehicleUpgradeStatus.FAILED) "升级失败" else "升级未完成"
                                versionValueTv.setTextColor(ThemeTokens.DANGER)
                                versionValueTv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                            }
                            info != null -> {
                                versionDot.visibility = View.GONE
                                versionChevron.visibility = View.GONE
                                val ver = info.currentVersion.trim()
                                versionValueTv.text = if (ver.isNotBlank()) "$ver · 已是最新" else "已是最新"
                                versionValueTv.setTextColor(ThemeTokens.TEXT_PRIMARY)
                                versionValueTv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                            }
                            upgradeCheckFailed -> {
                                versionDot.visibility = View.GONE
                                versionChevron.visibility = View.VISIBLE
                                versionValueTv.text = "检查失败，轻触重试"
                                versionValueTv.setTextColor(ThemeTokens.TEXT_SUB)
                                versionValueTv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                            }
                            else -> {
                                versionDot.visibility = View.GONE
                                versionChevron.visibility = View.GONE
                                versionValueTv.text = "—"
                                versionValueTv.setTextColor(ThemeTokens.TEXT_SUB)
                            }
                        }
                    }

                    refreshVersionCurrentRow = ::refreshVersionRowUi

                    fun fetchVehicleVersion(openOrToastOnComplete: Boolean) {
                        if (upgradeChecking) return
                        val active = profileProvider()
                        if (active.accessToken.isBlank()) return
                        upgradeChecking = true
                        upgradeCheckFailed = false
                        refreshVersionCurrentRow?.invoke()
                        scope.launch {
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    httpClient.checkVehicleUpgrade(vehicle.vin, active.accessToken)
                                }
                            }
                            upgradeChecking = false
                            upgradeCheckedOnce = true
                            res.onSuccess { info ->
                                cachedUpgradeInfo = info
                                upgradeCheckFailed = false
                                refreshVersionCurrentRow?.invoke()
                                if (openOrToastOnComplete) {
                                    if (info.shouldOpenSheet) {
                                        onOpenVehicleUpgrade(info)
                                    } else {
                                        toast("当前车辆系统已是最新版本")
                                    }
                                }
                            }.onFailure { err ->
                                upgradeCheckFailed = true
                                refreshVersionCurrentRow?.invoke()
                                if (openOrToastOnComplete && err !is CherySessionExpiredException) {
                                    toast("检查车辆版本失败，请稍后重试")
                                }
                            }
                        }
                    }

                    versionRow.setOnClickListener {
                        if (upgradeChecking) return@setOnClickListener
                        val info = cachedUpgradeInfo
                        if (info != null) {
                            if (info.shouldOpenSheet) {
                                onOpenVehicleUpgrade(info)
                            } else {
                                toast("当前车辆系统已是最新版本")
                            }
                        } else {
                            fetchVehicleVersion(openOrToastOnComplete = true)
                        }
                    }

                    body.addView(versionRow, lp())
                    body.addView(AppComponents.createHairline(context, startInsetDp = 20))
                    refreshVersionRowUi()
                    if (!upgradeCheckedOnce && !upgradeChecking) {
                        fetchVehicleVersion(openOrToastOnComplete = false)
                    }
                }
            }

            // Section 2: 车辆状态 (live telemetry specs for active vehicle, hidden during edit mode)
            val liveSpecs = if (isCurrentActive && !isEditing) vehicleLiveSpecsProvider(vehicle.vin) else emptyList()
            if (liveSpecs.isNotEmpty()) {
                body.addView(AppComponents.createSectionHeader(context, "车辆状态"), lp())
                liveSpecs.forEachIndexed { idx, (label, value) ->
                    body.addView(buildBorderlessInfoRow(label, value), lp())
                    if (idx < liveSpecs.lastIndex) {
                        body.addView(AppComponents.createHairline(context, startInsetDp = 20))
                    }
                }
                body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            }

            // Section 3: 车辆标识 (always read-only masked rows; tap to copy)
            body.addView(AppComponents.createSectionHeader(context, "车辆标识"), lp())
            body.addView(
                buildDetailValueRow("车架号", SensitiveUiText.identifier(vehicle.vin), copyable = !isEditing, dimmed = isEditing),
                lp()
            )
            body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            body.addView(
                buildDetailValueRow("发动机号", SensitiveUiText.identifier(vehicle.engineNumber), copyable = !isEditing, dimmed = isEditing),
                lp()
            )
            body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            body.addView(
                buildDetailValueRow("ICCID", SensitiveUiText.identifier(vehicle.iccid), copyable = !isEditing, dimmed = isEditing),
                lp()
            )
            body.addView(AppComponents.createHairline(context, startInsetDp = 20))
            body.addView(
                buildDetailValueRow("SN 码", SensitiveUiText.identifier(vehicle.sn), copyable = !isEditing, dimmed = isEditing),
                lp()
            )
        }

        val refreshHook: () -> Unit = {
            if (!isEditing) {
                if (cachedUpgradeInfo?.shouldOpenSheet == true && !upgradeChecking) {
                    upgradeCheckedOnce = false
                }
                val updated = cachedVehicleSummary(targetVin) ?: currentVehicle
                if (updated != null) {
                    renderDetail(updated)
                }
            }
        }
        openVehicleInfoRefresh = refreshHook
        onPushOverlaySubpage(root) {
            if (openVehicleInfoRefresh === refreshHook) {
                openVehicleInfoRefresh = null
            }
        }

        val resolvedInitial = initialVehicle ?: cachedVehicleSummary(targetVin)
        if (resolvedInitial != null) {
            renderDetail(resolvedInitial)
        } else {
            body.addView(loadingHint("正在加载车辆信息..."))
            scope.launch {
                val active = profileProvider()
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        val list = httpClient.fetchVehicleList(active.accessToken)
                        val matched = list.firstOrNull { it.vin.equals(targetVin, ignoreCase = true) }
                            ?: throw IllegalStateException("未找到对应车辆档案")
                        val liveSim = runCatching {
                            httpClient.queryVehicleSimStatus(active.accessToken, matched.vin)
                        }.getOrNull()
                        if (!liveSim.isNullOrBlank()) matched.copy(simStatus = liveSim) else matched
                    }
                }
                res.onSuccess { fetched ->
                    if (active.accessToken != profileProvider().accessToken) return@onSuccess
                    renderDetail(fetched)
                }.onFailure { err ->
                    body.removeAllViews()
                    if (err is CherySessionExpiredException) return@onFailure
                    body.addView(errorBanner("加载车辆信息失败：${err.message.orEmpty().take(90)}"))
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
        AppSystemInsets.applyBottomNavPadding(scroll)
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

        formCard.addView(formFieldHeader("PIN 码", required = true), lp(top = dp(16)))
        val (pinBox, pinInput) = createPasswordToggleUnderlineInput("请设置 6 位数字 PIN 码", maxLength = 6)
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
                    toast("请输入 6 位数字 PIN 码")
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
    // Reusable Minimalist Dark Automotive UI Helpers
    // =========================================================================

    private fun buildBorderlessInfoRow(
        label: String,
        value: String,
        dimmed: Boolean = false
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            alpha = if (dimmed) 0.55f else 1f
            addView(
                text(label, 14.5f, ThemeTokens.TEXT_SUB).apply { minWidth = dp(84) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )
            addView(
                text(value, 15f, if (value == SensitiveUiText.MISSING || value == "未设置") ThemeTokens.TEXT_SUB else ThemeTokens.TEXT_PRIMARY).apply {
                    gravity = Gravity.END
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            )
        }
    }

    private fun buildInlineEditRow(label: String, inputView: View): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(6), dp(20), dp(6))
            addView(
                text(label, 14.5f, ThemeTokens.TEXT_SUB).apply { minWidth = dp(84) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )
            addView(
                inputView,
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
            )
        }
    }

    private fun buildDetailValueRow(
        label: String,
        maskedValue: String,
        copyable: Boolean,
        dimmed: Boolean = false
    ): LinearLayout {
        val canCopy = copyable && maskedValue != SensitiveUiText.MISSING
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            alpha = if (dimmed) 0.55f else 1f
            if (canCopy) {
                isClickable = true
                isFocusable = true
                contentDescription = "$label，$maskedValue，轻触复制"
                MotionSpec.attachPressPhysics(this, pressedScale = 0.985f, haptic = false)
                setOnClickListener {
                    AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(label, maskedValue))
                    toast("已复制${label}")
                }
            }
            addView(
                text(label, 14.5f, ThemeTokens.TEXT_SUB).apply { minWidth = dp(84) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            )
            addView(
                TextView(context).apply {
                    text = maskedValue
                    gravity = Gravity.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    setTextColor(if (maskedValue == SensitiveUiText.MISSING) ThemeTokens.TEXT_SUB else ThemeTokens.TEXT_BODY)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
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
                    subtitle = "添加车辆后即可在此查看车辆信息、修改车牌与管理车辆"
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
        val cleaned = formatAuthTime(initialValue).takeUnless { it == "--" }.orEmpty()
        return AppComponents.createDateTimePickerField(
            context = context,
            hint = hint,
            initialValue = cleaned,
            mode = DateTimeMode.DATE_TIME_MINUTE,
            onValueChanged = onChanged
        )
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
        AppSystemInsets.applyBottomNavPadding(inner)
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
