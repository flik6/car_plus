package com.chery.carplus.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.webkit.WebViewAssetLoader
import com.chery.carplus.R
import com.chery.carplus.cloud.AntiTheftStatus
import com.chery.carplus.cloud.ChargingScheduleItem
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.CherySessionExpiredException
import com.chery.carplus.cloud.GeofenceItem
import com.chery.carplus.cloud.InGeekCloudProvisioner
import com.chery.carplus.cloud.TripDayGroup
import com.chery.carplus.cloud.TripRecordItem
import com.chery.carplus.cloud.VehicleAuthorizationItem
import com.chery.carplus.cloud.VehiclePowertrain
import com.chery.carplus.cloud.VehicleSummary
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.cloud.VehicleUpgradeInfo
import com.chery.carplus.cloud.ZyhlMessageItem
import com.chery.carplus.profile.VehicleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Minimalist dark secondary bottom sheets wiring the complete zyhl
 * (/cheryT15/app/command.do) and InGeek (/DKtrans/business) extended APIs
 * while keeping the primary home screen minimal and uncluttered.
 */
internal class VehicleFeatureSheets(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val httpClient: CheryHttpClient,
    private val provisioner: InGeekCloudProvisioner,
    private val profileProvider: () -> VehicleProfile,
    private val telemetryProvider: () -> VehicleTelemetry?,
    private val cachedPinProvider: () -> String,
    private val onPinUpdated: (String) -> Unit,
    private val onRefreshTelemetry: () -> Unit,
    private val onTriggerCloudKeyProvision: () -> Unit,
    private val onScanNearbyBle: () -> Unit,
    private val onStatusMessage: (String) -> Unit,
    private val onLogout: () -> Unit,
    private val isBleConnected: () -> Boolean,
    private val isBleNearby: () -> Boolean,
    private val hasLocalKeyMaterial: (VehicleProfile) -> Boolean,
    private val lastPairingPinProvider: () -> String,
    private val modelNameProvider: () -> String,
    private val plateNoProvider: () -> String,
    private val onDepthRecede: (Boolean) -> Unit = {},
    private val tripTrackEnabledProvider: () -> Boolean? = { null },
    private val requirePinEveryTimeProvider: () -> Boolean = { false },
    private val onRequirePinEveryTimeChanged: (Boolean) -> Unit = {},
    private val onOpenOverlaySubpage: ((view: View, onDestroy: (() -> Unit)?) -> Unit)? = null,
    private val onPopOverlaySubpage: (() -> Unit)? = null,
    private val onOpenRemoteAuthManagement: (() -> Unit)? = null,
    private val liveDistanceLabelProvider: () -> String = { "" },
    private val liveAddressLabelProvider: () -> String = { "" },
    private val deviceLocationProvider: () -> Pair<Double, Double>? = { null },
    private val onVehiclesChanged: () -> Unit = {}
) {
    private var activeDialog: Dialog? = null
    private var activeImmediateDismiss: (() -> Unit)? = null
    private var secondaryDialog: Dialog? = null

    fun dismissAll() {
        runCatching { secondaryDialog?.dismiss() }
        secondaryDialog = null
        val immediate = activeImmediateDismiss
        activeImmediateDismiss = null
        if (immediate != null) {
            immediate()
        } else {
            runCatching { activeDialog?.dismiss() }
        }
        activeDialog = null
    }

    // =========================================================================
    // 1. Top-Right Message Icon: 纯净消息提醒 (963 / 216) & 独立安全/授权底部弹窗
    // =========================================================================

    /**
     * Full-screen Add / Bind Vehicle subpage (`zyhl` serviceType 930) for overlay navigation.
     * Follows DESIGN_SYSTEM.md: unified AppTopBar, #161719 canvas, borderless underline inputs, zero Emoji.
     */
    fun buildAddVehicleSubpageView(
        onBack: () -> Unit,
        onVehicleBound: () -> Unit
    ): View {
        val profile = profileProvider()
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
                onBack = onBack
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Required section
        content.addView(
            AppComponents.createSectionHeader(context, "必填信息").apply {
                setPadding(0, dp(8), 0, dp(4))
            },
            lp()
        )

        val (vinWrap, vinEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "车架号 VIN（17 位字母数字）",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
            maxLength = 17
        )
        val (snWrap, snEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "车辆 TBox SN 码",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        )
        val (iccidWrap, iccidEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "车辆 SIM 卡 ICCID",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        )
        val (pinWrap, pinEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "设置 6 位 PIN 码",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            maxLength = 6
        )
        val (confirmPinWrap, confirmPinEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "确认 6 位 PIN 码",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            maxLength = 6
        )

        content.addView(vinWrap, lp(top = dp(4)))
        content.addView(snWrap, lp(top = dp(8)))
        content.addView(iccidWrap, lp(top = dp(8)))
        content.addView(pinWrap, lp(top = dp(8)))
        content.addView(confirmPinWrap, lp(top = dp(8)))

        // Optional section
        content.addView(
            AppComponents.createSectionHeader(context, "补充信息（选填）").apply {
                setPadding(0, dp(22), 0, dp(4))
            },
            lp()
        )

        val (plateWrap, plateEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "车牌号（选填，如 浙A12345）",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        )
        val (ownerWrap, ownerEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "车主姓名（选填）",
            inputType = InputType.TYPE_CLASS_TEXT
        )
        val (purchaseDateField, getPurchaseDate) = AppComponents.createDateTimePickerField(
            context = context,
            hint = "购车日期（选填）",
            initialValue = "",
            mode = DateTimeMode.DATE
        )
        content.addView(plateWrap, lp(top = dp(4)))
        content.addView(ownerWrap, lp(top = dp(8)))
        content.addView(purchaseDateField, lp(top = dp(8)))

        var setAsDefault = true
        val defaultRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        val defaultCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        defaultCol.addView(text("设为默认车辆", 15f, TEXT_PRIMARY))
        defaultCol.addView(text("绑定后立即切换为首页主控车辆", 12f, TEXT_SUB), lp(top = dp(2)))
        defaultRow.addView(defaultCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val defaultSwitch = ToggleSwitch(context).apply {
            setChecked(true, animate = false)
            onToggleAttempt = { target ->
                setAsDefault = target
                setChecked(target, animate = true)
            }
        }
        defaultRow.addView(defaultSwitch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        content.addView(defaultRow, lp())

        val errorTv = text("", 13f, ERROR_RED).apply {
            visibility = View.GONE
            setLineSpacing(0f, 1.35f)
        }
        content.addView(errorTv, lp(top = dp(12)))

        var submitting = false
        lateinit var submitBtn: TextView
        submitBtn = AppComponents.createActionButton(
            context = context,
            label = "绑定车辆",
            style = ButtonStyle.PRIMARY
        ) {
            if (submitting) return@createActionButton
            val vin = vinEt.text.toString().trim().uppercase(Locale.US)
            val sn = snEt.text.toString().trim()
            val iccid = iccidEt.text.toString().trim()
            val pin = pinEt.text.toString().trim()
            val confirmPin = confirmPinEt.text.toString().trim()
            val plate = plateEt.text.toString().trim()
            val owner = ownerEt.text.toString().trim()
            val purchaseDate = getPurchaseDate().trim()

            val validationError = when {
                vin.length != 17 -> "请输入完整的 17 位车架号 (VIN)"
                sn.isBlank() -> "请输入车辆 TBox SN 码"
                iccid.isBlank() -> "请输入车辆 SIM 卡 ICCID"
                !Regex("^\\d{6}$").matches(pin) -> "PIN 码必须为 6 位数字"
                pin != confirmPin -> "两次输入的 PIN 码不一致"
                else -> null
            }
            if (validationError != null) {
                errorTv.text = validationError
                errorTv.visibility = View.VISIBLE
                AppHaptics.perform(submitBtn, HapticLevel.ERROR_REJECT)
                MotionSpec.shakeError(errorTv)
                return@createActionButton
            }

            errorTv.visibility = View.GONE
            submitting = true
            submitBtn.isEnabled = false
            submitBtn.alpha = 0.6f
            submitBtn.text = "正在绑定..."

            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.bindVehicle(
                            vin = vin,
                            sn = sn,
                            iccid = iccid,
                            controlPin = pin,
                            licenseNum = plate,
                            hostName = owner,
                            purchaseDate = purchaseDate,
                            isDefault = setAsDefault,
                            accessToken = profile.accessToken
                        )
                    }
                }
                submitting = false
                submitBtn.isEnabled = true
                submitBtn.alpha = 1f
                submitBtn.text = "绑定车辆"

                res.onSuccess { result ->
                    if (result.success) {
                        onPinUpdated(pin)
                        AppHaptics.perform(submitBtn, HapticLevel.MEDIUM_CONFIRM)
                        toast("车辆绑定成功")
                        onVehicleBound()
                    } else {
                        errorTv.text = result.message.ifBlank { "绑定失败，请核对车架号、SN 与 ICCID" }
                        errorTv.visibility = View.VISIBLE
                        AppHaptics.perform(submitBtn, HapticLevel.ERROR_REJECT)
                        MotionSpec.shakeError(errorTv)
                    }
                }.onFailure { err ->
                    errorTv.text = "绑定请求失败：${err.message.orEmpty().take(90)}"
                    errorTv.visibility = View.VISIBLE
                    AppHaptics.perform(submitBtn, HapticLevel.ERROR_REJECT)
                    MotionSpec.shakeError(errorTv)
                }
            }
        }
        content.addView(submitBtn, lp(top = dp(20), height = dp(48)))

        return root
    }

    /**
     * Full-screen Messages subpage (`Screen.MESSAGES`) for persistent-Home right-slide overlay.
     * Auto-loads on open with zero manual refresh button.
     */
    fun buildMessagesSubpageView(onBack: () -> Unit): View {
        val profile = profileProvider()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "消息",
                onBack = onBack
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        scroll.addView(contentHost, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        loadMessagesTab(contentHost, profile)
        return root
    }

    fun showMessageAndAlertCenterSheet(@Suppress("UNUSED_PARAMETER") initialTab: Int = 0) {
        val profile = profileProvider()
        showSheet("消息") { body, _ ->
            val contentHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(contentHost, lp(top = dp(10)))
            loadMessagesTab(contentHost, profile)
        }
    }

    fun showAntiTheftSheet() {
        val profile = profileProvider()
        showSheet("防盗与车辆体检") { body, _ ->
            val contentHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(contentHost, lp(top = dp(10)))
            loadAntiTheftAndMaintenanceTab(contentHost, profile)
        }
    }

    fun showGeofenceSheet() {
        val profile = profileProvider()
        showSheet("电子围栏") { body, _ ->
            val contentHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(contentHost, lp(top = dp(10)))
            fun reload() {
                contentHost.removeAllViews()
                loadGeofenceTab(contentHost, profile) { reload() }
            }
            reload()
        }
    }

    fun showRemoteAuthSheet() {
        val delegate = onOpenRemoteAuthManagement
        if (delegate != null) {
            delegate()
            return
        }
        val pushOverlay = onOpenOverlaySubpage
        if (pushOverlay != null) {
            pushOverlay(buildRemoteAuthSubpageView(onBack = { onPopOverlaySubpage?.invoke() }), null)
            return
        }
        val profile = profileProvider()
        showSheet("驾驶员") { body, _ ->
            val contentHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(contentHost, lp(top = dp(10)))
            fun reload() {
                contentHost.removeAllViews()
                loadRemoteAuthTab(contentHost, profile) { reload() }
            }
            reload()
        }
    }

    /**
     * Full-screen minimalist Vehicle Management subpage ("车辆管理") with borderless AppListRow items.
     */
    fun buildVehicleManagementSubpageView(
        onBack: () -> Unit,
        onVehiclesUpdated: () -> Unit = onVehiclesChanged
    ): View {
        val profile = profileProvider()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }

        val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var reloadVehicles: () -> Unit = {}

        fun openAddVehicle() {
            val pushOverlay = onOpenOverlaySubpage
            if (pushOverlay != null) {
                val addView = buildAddVehicleSubpageView(
                    onBack = { onPopOverlaySubpage?.invoke() },
                    onVehicleBound = {
                        onPopOverlaySubpage?.invoke()
                        onVehiclesUpdated()
                        reloadVehicles()
                    }
                )
                pushOverlay(addView, null)
            }
        }

        fun openEditVehicle(vehicle: VehicleSummary) {
            val pushOverlay = onOpenOverlaySubpage
            val editView = buildEditVehicleSubpageView(
                vehicle = vehicle,
                onBack = { onPopOverlaySubpage?.invoke() },
                onSaved = {
                    onPopOverlaySubpage?.invoke()
                    onVehiclesUpdated()
                    reloadVehicles()
                },
                onUnbound = {
                    onPopOverlaySubpage?.invoke()
                    onVehiclesUpdated()
                    reloadVehicles()
                }
            )
            if (pushOverlay != null) {
                pushOverlay(editView, null)
            } else {
                secondaryDialog?.dismiss()
                val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                val fallbackView = buildEditVehicleSubpageView(
                    vehicle = vehicle,
                    onBack = { dialog.dismiss() },
                    onSaved = {
                        dialog.dismiss()
                        onVehiclesUpdated()
                        reloadVehicles()
                    },
                    onUnbound = {
                        dialog.dismiss()
                        onVehiclesUpdated()
                        reloadVehicles()
                    }
                )
                dialog.setContentView(fallbackView)
                dialog.window?.apply {
                    statusBarColor = ThemeTokens.BG_CANVAS
                    navigationBarColor = ThemeTokens.BG_CANVAS
                    setLayout(MATCH_PARENT, MATCH_PARENT)
                }
                dialog.setOnDismissListener { if (secondaryDialog === dialog) secondaryDialog = null }
                secondaryDialog = dialog
                dialog.show()
            }
        }

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "车辆管理",
                onBack = onBack,
                rightText = "添加",
                rightEnabled = true,
                onRightClick = { openAddVehicle() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(listHost, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        reloadVehicles = {
            listHost.removeAllViews()
            listHost.addView(loadingHint("正在加载车辆列表..."))
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchVehicleList(profile.accessToken)
                    }
                }
                listHost.removeAllViews()
                res.onSuccess { vehicles ->
                    if (vehicles.isEmpty()) {
                        listHost.addView(
                            AppComponents.createEmptyState(
                                context = context,
                                icon = AppIconType.CAR_CONTROLS,
                                title = "暂无绑定车辆",
                                subtitle = "点击右上角「添加」绑定新车辆"
                            ),
                            lp(top = dp(48))
                        )
                    } else {
                        vehicles.forEachIndexed { idx, v ->
                            val baseName = v.seriesName.ifBlank { v.vehicleName }.ifBlank { "奇瑞座驾" }
                            val isDefault = v.selected == "1"
                            val sub = listOfNotNull(
                                v.plateNo.takeIf { it.isNotBlank() },
                                maskedVin(v.vin)
                            ).joinToString(" · ")
                            val row = AppComponents.createListRow(
                                context = context,
                                icon = AppIconType.CAR_CONTROLS,
                                title = baseName,
                                subtitle = sub.ifBlank { null },
                                trailingText = if (isDefault) "默认" else null,
                                showChevron = true,
                                onClick = { openEditVehicle(v) }
                            )
                            if (isDefault) {
                                (row.findViewWithTag<TextView>("row_trailing_text"))?.setTextColor(ThemeTokens.ACCENT_LIGHT)
                            }
                            listHost.addView(row, lp())
                            if (idx < vehicles.lastIndex) {
                                listHost.addView(AppComponents.createHairline(context, startInsetDp = 58))
                            }
                        }
                    }
                }.onFailure { err ->
                    listHost.addView(errorHint("车辆列表加载失败：${err.message.orEmpty().take(90)}"), lp(top = dp(12)))
                    listHost.addView(
                        secondaryBtn("轻触重试") { reloadVehicles() },
                        LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
                            marginStart = dp(20); marginEnd = dp(20); topMargin = dp(10)
                        }
                    )
                }
            }
        }
        reloadVehicles()
        return root
    }

    /**
     * Full-screen Edit Vehicle Level-3 subpage ("编辑车辆").
     * Dangerous action ("解绑此车辆") is hidden by default inside collapsible "更多操作"
     * and guarded by a strict 2-step confirmation (Confirm Dialog + VIN last-4 input).
     */
    fun buildEditVehicleSubpageView(
        vehicle: VehicleSummary,
        onBack: () -> Unit,
        onSaved: () -> Unit,
        onUnbound: () -> Unit
    ): View {
        val profile = profileProvider()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }

        val (plateWrap, plateEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "车牌号（如 浙A12345）",
            initialValue = vehicle.plateNo,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        )
        val (ownerWrap, ownerEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "车主姓名（选填）",
            initialValue = "",
            inputType = InputType.TYPE_CLASS_TEXT
        )
        val (purchaseDateField, getPurchaseDate) = AppComponents.createDateTimePickerField(
            context = context,
            hint = "购车日期（选填）",
            initialValue = "",
            mode = DateTimeMode.DATE
        )
        var setAsDefault = vehicle.selected == "1"
        val initiallyDefault = setAsDefault
        var saving = false

        fun saveVehicleChanges() {
            if (saving) return
            val plate = plateEt.text.toString().trim()
            val owner = ownerEt.text.toString().trim()
            val purchaseDate = getPurchaseDate().trim()
            if (plate.isBlank()) {
                toast("请填写车牌号")
                return
            }
            saving = true
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.saveVehicleInfo(
                            accessToken = profile.accessToken,
                            vin = vehicle.vin,
                            plateNo = plate,
                            hostName = owner,
                            purchaseDate = purchaseDate
                        )
                        if (setAsDefault && !initiallyDefault) {
                            httpClient.setDefaultVehicle(profile.accessToken, vehicle.vin)
                        }
                    }
                }
                saving = false
                res.onSuccess {
                    AppHaptics.perform(root, HapticLevel.MEDIUM_CONFIRM)
                    toast("车辆信息已保存")
                    onSaved()
                }.onFailure { err ->
                    AppHaptics.perform(root, HapticLevel.ERROR_REJECT)
                    toast("保存失败：${err.message.orEmpty().take(70)}")
                }
            }
        }

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "编辑车辆",
                onBack = onBack,
                rightText = "保存",
                rightEnabled = true,
                onRightClick = { saveVehicleChanges() }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val modelTitle = vehicle.seriesName.ifBlank { vehicle.vehicleName }.ifBlank { "奇瑞座驾" }
        content.addView(text(modelTitle, 18f, TEXT_PRIMARY, Typeface.BOLD))
        content.addView(text("车架号：${maskedVin(vehicle.vin)}", 13f, TEXT_SUB), lp(top = dp(4), bottom = dp(18)))

        content.addView(plateWrap, lp(top = dp(4)))
        content.addView(ownerWrap, lp(top = dp(10)))
        content.addView(purchaseDateField, lp(top = dp(10)))

        val defaultRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(16))
        }
        val defaultCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        defaultCol.addView(text("设为默认车辆", 15.5f, TEXT_PRIMARY))
        defaultCol.addView(text("作为首页默认显示与主控车辆", 12.5f, TEXT_SUB), lp(top = dp(2)))
        defaultRow.addView(defaultCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val defaultSwitch = ToggleSwitch(context).apply {
            setChecked(setAsDefault, animate = false)
            onToggleAttempt = { target ->
                setAsDefault = target
                setChecked(target, animate = true)
            }
        }
        defaultRow.addView(defaultSwitch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        content.addView(defaultRow, lp())

        val linePx = dpF(0.6f).roundToInt().coerceAtLeast(1)
        content.addView(
            View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, linePx).apply { topMargin = dp(8) }
        )

        // Collapsed danger zone: "更多操作" -> expands "解绑此车辆"
        var moreExpanded = false
        val chevron = AppIconView(context, AppIconType.CHEVRON_DOWN, Color.rgb(112, 116, 126))
        val dangerActionRow = text("解绑此车辆", 15f, ThemeTokens.DANGER, Typeface.BOLD).apply {
            setPadding(0, dp(14), 0, dp(14))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)
            setOnClickListener {
                // Step 1 Confirmation
                AppComponents.showConfirmDialog(
                    context = context,
                    title = "确认解绑车辆？",
                    message = "解绑后将移除该车辆的远程控车与数字钥匙权限，此操作不可撤销。",
                    confirmText = "继续解绑",
                    cancelText = "取消",
                    dangerConfirm = true
                ) {
                    // Step 2: VIN last-4 security verification modal
                    showUnbindVinConfirmModal(vehicle, profile, onUnbound)
                }
            }
        }

        val moreRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                moreExpanded = !moreExpanded
                chevron.animate().rotation(if (moreExpanded) 180f else 0f)
                    .setDuration(180L)
                    .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                    .start()
                dangerActionRow.visibility = if (moreExpanded) View.VISIBLE else View.GONE
                if (moreExpanded) {
                    dangerActionRow.alpha = 0f
                    dangerActionRow.animate().alpha(1f).setDuration(180L).start()
                }
            }
        }
        moreRow.addView(
            text("更多操作", 13.5f, Color.rgb(112, 116, 126)),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        moreRow.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)))
        content.addView(moreRow, lp())
        content.addView(dangerActionRow, lp())

        return root
    }

    private fun showUnbindVinConfirmModal(
        vehicle: VehicleSummary,
        profile: VehicleProfile,
        onUnbound: () -> Unit
    ) {
        val last4 = vehicle.vin.trim().uppercase(Locale.US).takeLast(4)
        showModalDialog("最终安全确认") { box, dialog ->
            box.addView(
                text(
                    "请输入车架号后 4 位（$last4）以确认解绑车辆，解绑后需重新验证方可绑定。",
                    13.5f,
                    TEXT_SUB
                ).apply { setLineSpacing(0f, 1.4f) },
                lp(top = dp(10))
            )

            val (vin4Wrap, vin4Et) = AppComponents.createUnderlineInput(
                context = context,
                hint = "输入车架号后 4 位 ($last4)",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
                maxLength = 4
            )
            box.addView(vin4Wrap, lp(top = dp(12)))

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(btns, lp(top = dp(20)))
            btns.addView(secondaryBtn("取消") { dialog.dismiss() }, weightLp(0, dp(44)))

            var confirmBtn: TextView? = null
            confirmBtn = text("确认解绑", 14.5f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = rounded(ThemeTokens.DANGER, 8f)
                alpha = 0.38f
                isEnabled = false
                isClickable = true
                isFocusable = true
                MotionSpec.attachPressPhysics(this, pressedScale = 0.96f)
                setOnClickListener {
                    val typed = vin4Et.text.toString().trim().uppercase(Locale.US)
                    if (typed != last4) {
                        AppHaptics.perform(this, HapticLevel.ERROR_REJECT)
                        MotionSpec.shakeError(vin4Wrap)
                        return@setOnClickListener
                    }
                    val pin = cachedPinProvider().ifBlank { profile.controlPin }
                    dialog.dismiss()
                    scope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.unbindVehicle(profile.accessToken, vehicle.vin, pin)
                            }
                        }.getOrDefault(false)
                        if (ok) {
                            AppHaptics.perform(box, HapticLevel.MEDIUM_CONFIRM)
                            toast("车辆已解绑")
                            onUnbound()
                        } else {
                            toast("解绑失败，请核对 PIN 码或稍后重试")
                        }
                    }
                }
            }
            vin4Et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val matched = s?.toString()?.trim()?.uppercase(Locale.US) == last4
                    confirmBtn?.isEnabled = matched
                    confirmBtn?.alpha = if (matched) 1f else 0.38f
                }
            })
            btns.addView(confirmBtn, weightLp(dp(10), dp(44)))
        }
    }

    /**
     * Full-screen minimalist Remote Authorization subpage ("远程授权").
     */
    fun buildRemoteAuthSubpageView(onBack: () -> Unit): View {
        val profile = profileProvider()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }

        val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var reloadList: () -> Unit = {}

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "驾驶员",
                onBack = onBack,
                rightText = "新增",
                rightEnabled = true,
                onRightClick = { showGrantRemoteAuthModal(profile) { reloadList() } }
            ),
            lp()
        )

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(listHost, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        reloadList = {
            listHost.removeAllViews()
            listHost.addView(loadingHint("正在加载远程授权..."))
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchRemoteAuthList(profile.vin, profile.accessToken)
                    }
                }
                listHost.removeAllViews()
                res.onSuccess { list ->
                    if (list.isEmpty()) {
                        listHost.addView(
                            AppComponents.createEmptyState(
                                context = context,
                                icon = AppIconType.KEY_ROUND,
                                title = "暂无远程授权",
                                subtitle = "点击右上角「新增」授权亲友远程控车"
                            ),
                            lp(top = dp(48))
                        )
                    } else {
                        list.forEachIndexed { idx, item ->
                            val title = item.receiverName.ifBlank { maskMobile(item.receiverMobile).ifBlank { "被授权人" } }
                            val range = formatAuthDateRange(item.startTime, item.endTime)
                            val (statusLabel, isActive) = formatAuthStatus(item.status)
                            val row = AppComponents.createListRow(
                                context = context,
                                icon = null,
                                title = title,
                                subtitle = range.ifBlank { item.authorityDesc.ifBlank { null } },
                                trailingText = statusLabel,
                                showChevron = true,
                                onClick = { showRemoteAuthActionModal(item, profile) { reloadList() } }
                            )
                            (row.findViewWithTag<TextView>("row_trailing_text"))?.setTextColor(
                                if (isActive) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_SUB
                            )
                            listHost.addView(row, lp())
                            if (idx < list.lastIndex) {
                                listHost.addView(AppComponents.createHairline(context, startInsetDp = 20))
                            }
                        }
                    }
                }.onFailure { err ->
                    listHost.addView(errorHint("远程授权加载失败：${err.message.orEmpty().take(90)}"), lp(top = dp(12)))
                    listHost.addView(
                        secondaryBtn("轻触重试") { reloadList() },
                        LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
                            marginStart = dp(20); marginEnd = dp(20); topMargin = dp(10)
                        }
                    )
                }
            }
        }
        reloadList()
        return root
    }

    private fun showRemoteAuthActionModal(
        item: VehicleAuthorizationItem,
        profile: VehicleProfile,
        onReload: () -> Unit
    ) {
        val name = item.receiverName.ifBlank { maskMobile(item.receiverMobile).ifBlank { "被授权人" } }
        showModalDialog("远程授权详情") { box, dialog ->
            val infoTv = text(
                listOf(
                    "被授权人：$name",
                    "生效时间：${formatAuthDateRange(item.startTime, item.endTime).ifBlank { "--" }}",
                    "授权权限：${item.authorityDesc.ifBlank { item.authority.ifBlank { "--" } }}"
                ).joinToString("\n"),
                13.5f,
                TEXT_BODY
            ).apply { setLineSpacing(0f, 1.5f) }
            box.addView(infoTv, lp(top = dp(12)))

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(btns, lp(top = dp(20)))
            btns.addView(secondaryBtn("关闭") { dialog.dismiss() }, weightLp(0, dp(44)))
            btns.addView(
                AppComponents.createActionButton(context, "撤销授权", ButtonStyle.DANGER_TEXT) {
                    val pin = cachedPinProvider().ifBlank { profile.controlPin }
                    if (pin.isBlank()) {
                        toast("请先在安全性中设置 6 位 PIN 码")
                        showPinManagementModal(profile)
                        return@createActionButton
                    }
                    AppComponents.showConfirmDialog(
                        context = context,
                        title = "撤销远程控车授权",
                        message = "确认撤销「$name」的远程控车权限？",
                        confirmText = "撤销",
                        cancelText = "取消",
                        dangerConfirm = true
                    ) {
                        dialog.dismiss()
                        scope.launch {
                            val ok = runCatching {
                                withContext(Dispatchers.IO) {
                                    httpClient.revokeRemoteAuth(profile.vin, item.keyId, pin, "0", profile.accessToken)
                                }
                            }.getOrDefault(false)
                            toast(if (ok) "远程授权已撤销" else "撤销远程授权失败")
                            if (ok) onReload()
                        }
                    }
                },
                weightLp(dp(10), dp(44))
            )
        }
    }

    private fun formatAuthDateRange(start: String, end: String): String {
        fun short(raw: String): String {
            val s = raw.trim()
            if (s.length >= 16) return s.substring(5, 16).replace('-', '/')
            return s
        }
        val a = short(start)
        val b = short(end)
        return when {
            a.isNotBlank() && b.isNotBlank() -> "$a – $b"
            a.isNotBlank() -> a
            b.isNotBlank() -> b
            else -> ""
        }
    }

    private fun formatAuthStatus(rawStatus: String): Pair<String, Boolean> {
        val s = rawStatus.trim()
        return when {
            s == "1" || s.contains("生效") || s.contains("有效") || s.equals("active", ignoreCase = true) -> "生效中" to true
            s == "0" || s.contains("过期") || s.contains("失效") || s.contains("撤销") -> "已过期" to false
            s.isBlank() -> "生效中" to true
            else -> s to false
        }
    }

    private fun loadMessagesTab(container: LinearLayout, profile: VehicleProfile) {
        container.addView(loadingHint("正在加载消息中心..."))
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchMessages(page = 1, pageSize = 20, accessToken = profile.accessToken)
                }
            }
            container.removeAllViews()
            result.onSuccess { items ->
                if (items.isEmpty()) {
                    container.addView(emptyHint("暂无消息提醒记录"))
                } else {
                    items.forEach { item ->
                        container.addView(messageCard(item) { openMessageDetail(item, profile) }, lp(bottom = dp(10)))
                    }
                }
            }.onFailure { err ->
                if (err is CherySessionExpiredException) return@onFailure
                container.addView(errorHint("消息加载失败：${err.message.orEmpty().take(90)}"))
                container.addView(secondaryBtn("轻触重试") { loadMessagesTab(container, profile) }, lp(top = dp(10), height = dp(42)))
            }
        }
    }

    private fun openMessageDetail(item: ZyhlMessageItem, profile: VehicleProfile) {
        showModalDialog(item.title.ifBlank { item.typeDesc.ifBlank { "消息详情" } }) { box, dialog ->
            val contentView = text(
                item.content.ifBlank { item.description.ifBlank { "正在获取消息正文..." } },
                14f,
                TEXT_BODY
            ).apply { setLineSpacing(0f, 1.5f) }
            if (item.messageTime.isNotBlank()) {
                box.addView(text(item.messageTime, 12f, TEXT_SUB), lp(top = dp(6)))
            }
            box.addView(contentView, lp(top = dp(12)))
            box.addView(primaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(20), height = dp(44)))

            if (item.functionId.isNotBlank() && item.type.isNotBlank() && item.type != "maintenance") {
                scope.launch {
                    val detail = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.fetchMessageDetail(item.functionId, item.type, profile.accessToken)
                        }
                    }.getOrNull()
                    if (detail != null) {
                        val fullText = detail.content.ifBlank { detail.description }
                        if (fullText.isNotBlank()) contentView.text = fullText
                    }
                }
            }
        }
    }

    private fun loadAntiTheftAndMaintenanceTab(container: LinearLayout, profile: VehicleProfile) {
        container.addView(loadingHint("正在查询防盗提醒与保养告警..."))
        scope.launch {
            val antiTheft = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchAntiTheftSettings(profile.vin, profile.accessToken)
                }
            }
            val reminders = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchMaintenanceReminders(profile.vin, 1, 15, profile.accessToken)
                }
            }
            container.removeAllViews()

            // Anti-theft & speed limit status card
            val statusCard = cardBox()
            statusCard.addView(text("防盗提醒与限速状态", 15f, TEXT_PRIMARY, Typeface.BOLD))
            antiTheft.onSuccess { st: AntiTheftStatus ->
                statusCard.addView(kvRow("防盗报警提醒", if (st.theftRemindEnabled) "已开启" else "未开启"), lp(top = dp(10)))
                statusCard.addView(kvRow("车速限制提醒", if (st.speedLimitEnabled) "已开启" else "未开启"))
                if (st.speedLimitKmh.isNotBlank() && st.speedLimitKmh != "0") {
                    statusCard.addView(kvRow("限速阈值", "${st.speedLimitKmh} km/h"))
                }
            }.onFailure { err ->
                statusCard.addView(text("查询失败：${err.message.orEmpty().take(80)}", 13f, ERROR_RED), lp(top = dp(8)))
            }
            container.addView(statusCard, lp(bottom = dp(12)))

            // Remote vehicle diagnosis trigger
            container.addView(primaryBtn("远程车辆体检诊断") {
                runVehicleDiagnosis(profile)
            }, lp(bottom = dp(14), height = dp(46)))

            // Maintenance & service reminders
            container.addView(text("保养与车辆提醒", 14f, TEXT_SUB, Typeface.BOLD), lp(bottom = dp(8)))
            reminders.onSuccess { list ->
                if (list.isEmpty()) {
                    container.addView(emptyHint("暂无保养或车辆告警提醒"))
                } else {
                    list.forEach { msg ->
                        container.addView(messageCard(msg) { openMessageDetail(msg, profile) }, lp(bottom = dp(8)))
                    }
                }
            }.onFailure { err ->
                container.addView(errorHint("提醒查询失败：${err.message.orEmpty().take(80)}"))
            }
        }
    }

    private fun runVehicleDiagnosis(profile: VehicleProfile) {
        showModalDialog("远程车辆体检诊断") { box, dialog ->
            val statusTv = text("正在向车辆下发远程体检请求...", 14f, TEXT_BODY).apply {
                setLineSpacing(0f, 1.45f)
            }
            box.addView(statusTv, lp(top = dp(12)))
            box.addView(primaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(20), height = dp(44)))

            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchVehicleDiagnosis(profile.vin, profile.accessToken)
                    }
                }
                res.onSuccess { json: JSONObject ->
                    if (json.length() == 0) {
                        statusTv.text = "体检完成：未检出异常故障项。"
                    } else {
                        val lines = mutableListOf<String>()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = json.optString(k)
                            if (v.isNotBlank() && v != "null") lines += "$k: $v"
                        }
                        statusTv.text = if (lines.isEmpty()) "体检完成，车辆状态正常。"
                        else lines.take(16).joinToString("\n")
                    }
                }.onFailure { err ->
                    statusTv.text = "远程体检失败：${err.message.orEmpty().take(120)}"
                }
            }
        }
    }

    private fun loadGeofenceTab(
        container: LinearLayout,
        profile: VehicleProfile,
        onReload: () -> Unit
    ) {
        container.addView(loadingHint("正在加载电子围栏列表..."))
        scope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchGeofences(profile.vin, profile.accessToken)
                }
            }
            container.removeAllViews()
            container.addView(primaryBtn("+ 新增电子围栏") {
                openGeofenceEditor(existing = null, onReload = onReload)
            }, lp(bottom = dp(12), height = dp(46)))

            res.onSuccess { fences ->
                if (fences.isEmpty()) {
                    container.addView(emptyHint("暂未设置电子围栏"))
                } else {
                    fences.forEach { fence ->
                        container.addView(geofenceCard(fence, profile, onReload), lp(bottom = dp(10)))
                    }
                }
            }.onFailure { err ->
                container.addView(errorHint("电子围栏查询失败：${err.message.orEmpty().take(90)}"))
            }
        }
    }

    private fun geofenceCard(
        fence: GeofenceItem,
        profile: VehicleProfile,
        onReload: () -> Unit
    ): LinearLayout = cardBox().apply {
        val title = fence.name.ifBlank { "围栏 #${fence.keyId}" }
        val isEnabled = fence.status == "1"
        isClickable = true
        isFocusable = true
        MotionSpec.attachPressPhysics(this, pressedScale = 0.985f)
        setOnClickListener { openGeofenceEditor(existing = fence, onReload = onReload) }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            text(title, 15f, TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val toggleSwitch = ToggleSwitch(context).apply {
            setChecked(isEnabled, animate = false)
            onToggleAttempt = { targetChecked ->
                setChecked(targetChecked, animate = true)
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.deleteOrToggleGeofence(
                                keyId = fence.keyId,
                                type = if (targetChecked) "1" else "0",
                                accessToken = profile.accessToken
                            )
                        }
                    }.getOrDefault(false)
                    if (ok) {
                        toast(if (targetChecked) "围栏已启用" else "围栏已停用")
                        onReload()
                    } else {
                        setChecked(!targetChecked, animate = true)
                        toast("围栏状态更新失败")
                    }
                }
            }
        }
        headerRow.addView(toggleSwitch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(headerRow)

        val detail = listOfNotNull(
            fence.radiusMeters.takeIf { it.isNotBlank() }?.let { "半径 ${it}m" },
            if (fence.lat.isNotBlank() && fence.lon.isNotBlank()) "${fence.lat}, ${fence.lon}" else null
        ).joinToString(" · ")
        if (detail.isNotBlank()) {
            addView(text(detail, 12.5f, TEXT_SUB), lp(top = dp(4)))
        }

        val deleteBtn = AppComponents.createActionButton(
            context = context,
            label = "删除围栏",
            style = ButtonStyle.DANGER_TEXT
        ) {
            AppComponents.showConfirmDialog(
                context = context,
                title = "删除电子围栏",
                message = "确认删除「$title」？删除后将不再触发该区域出入提醒。",
                confirmText = "删除",
                cancelText = "取消",
                dangerConfirm = true
            ) {
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.deleteOrToggleGeofence(
                                keyId = fence.keyId,
                                type = "2",
                                accessToken = profile.accessToken
                            )
                        }
                    }.getOrDefault(false)
                    toast(if (ok) "围栏已删除" else "删除围栏失败")
                    if (ok) onReload()
                }
            }
        }
        addView(deleteBtn, lp(top = dp(8), height = dp(38)))
    }

    private fun openGeofenceEditor(existing: GeofenceItem?, onReload: () -> Unit) {
        activeDialog?.dismiss()
        val pushOverlay = onOpenOverlaySubpage
        if (pushOverlay != null) {
            val (editorView, destroyCb) = buildGeofenceEditorSubpageView(
                existing = existing,
                onBack = { onPopOverlaySubpage?.invoke() },
                onSaved = {
                    onReload()
                    onPopOverlaySubpage?.invoke()
                }
            )
            pushOverlay(editorView, destroyCb)
            return
        }

        // Fallback full-screen dark modal Dialog when overlay stack callback is not wired
        secondaryDialog?.dismiss()
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val (editorView, destroyCb) = buildGeofenceEditorSubpageView(
            existing = existing,
            onBack = { dialog.dismiss() },
            onSaved = {
                onReload()
                dialog.dismiss()
            }
        )
        dialog.setContentView(editorView)
        dialog.window?.apply {
            statusBarColor = ThemeTokens.BG_CANVAS
            navigationBarColor = ThemeTokens.BG_CANVAS
            setLayout(MATCH_PARENT, MATCH_PARENT)
        }
        dialog.setOnDismissListener {
            destroyCb()
            if (secondaryDialog === dialog) secondaryDialog = null
        }
        secondaryDialog = dialog
        dialog.show()
    }

    /**
     * Full-screen Visual Map Circle Geofence Editor (Level 3 subpage).
     * Panning the map moves the geofence center; pinch/zoom or bottom SeekBar adjusts radius (100m-10km) in real time.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun buildGeofenceEditorSubpageView(
        existing: GeofenceItem? = null,
        onBack: () -> Unit,
        onSaved: () -> Unit
    ): Pair<View, () -> Unit> {
        val profile = profileProvider()
        val telemetry = telemetryProvider()
        val vehLat = telemetry?.latitude?.takeIf { it != 0.0 }
        val vehLon = telemetry?.longitude?.takeIf { it != 0.0 }

        var selectedLat = existing?.lat?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: vehLat ?: 30.2903
        var selectedLon = existing?.lon?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: vehLon ?: 120.1001
        var selectedRadiusMeters = (existing?.radiusMeters?.toDoubleOrNull()?.roundToInt() ?: 500).coerceIn(100, 10_000)

        var mapWebView: WebView? = null
        var mapReady = false
        var mapDestroyed = false
        var syncingSeekBarFromMap = false
        var saving = false

        fun formatRadius(meters: Int): String = if (meters >= 1000) {
            val km = meters / 1000.0
            if (meters % 1000 == 0) "${meters / 1000}km" else String.format(Locale.US, "%.1fkm", km)
        } else {
            "${meters}m"
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }

        val (nameWrap, nameEt) = AppComponents.createUnderlineInput(
            context = context,
            hint = "围栏名称（如：家 / 公司）",
            initialValue = existing?.name.orEmpty()
        )

        val rangeReadout = text("当前范围：半径 ${formatRadius(selectedRadiusMeters)}", 14f, TEXT_PRIMARY, Typeface.BOLD)

        fun saveCurrentGeofence() {
            if (saving) return
            val name = nameEt.text.toString().trim().ifBlank { "安全围栏" }
            val latStr = String.format(Locale.US, "%.6f", selectedLat)
            val lonStr = String.format(Locale.US, "%.6f", selectedLon)
            val radiusStr = selectedRadiusMeters.toString()
            saving = true
            scope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.saveGeofence(
                            vin = profile.vin,
                            name = name,
                            lat = latStr,
                            lon = lonStr,
                            radiusMeters = radiusStr,
                            accessToken = profile.accessToken
                        )
                    }
                }.getOrDefault(false)
                saving = false
                if (mapDestroyed) return@launch
                toast(if (ok) "电子围栏已保存" else "保存围栏失败，请重试")
                if (ok) onSaved()
            }
        }

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = if (existing == null) "新增电子围栏" else "编辑电子围栏",
                onBack = onBack,
                rightText = "保存",
                rightEnabled = true,
                onRightClick = { saveCurrentGeofence() }
            ),
            lp()
        )

        val radiusSeekBar = SeekBar(context).apply {
            max = 99 // 100m .. 10,000m (step 100m)
            progress = ((selectedRadiusMeters - 100) / 100).coerceIn(0, 99)
            progressTintList = ColorStateList.valueOf(ThemeTokens.ACCENT_LIGHT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(52, 56, 66))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || syncingSeekBarFromMap) return
                    val meters = (100 + progress * 100).coerceIn(100, 10_000)
                    selectedRadiusMeters = meters
                    rangeReadout.text = "当前范围：半径 ${formatRadius(meters)}"
                    if (mapReady && !mapDestroyed) {
                        mapWebView?.evaluateJavascript(
                            "window.CarMap && window.CarMap.setGeofenceRadius && window.CarMap.setGeofenceRadius($meters);",
                            null
                        )
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        val mapContainer = FrameLayout(context).apply {
            background = rounded(MAP_BG, 14f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(14f))
                }
            }
            clipToOutline = true
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val wv = object : WebView(context) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE -> parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(MAP_BG)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onGeofenceChanged(lat: Double, lon: Double, radiusMeters: Double) {
                        post {
                            if (mapDestroyed) return@post
                            if (lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)) {
                                selectedLat = lat
                                selectedLon = lon
                            }
                            if (radiusMeters.isFinite() && radiusMeters > 0.0) {
                                val meters = radiusMeters.roundToInt().coerceIn(100, 10_000)
                                selectedRadiusMeters = meters
                                rangeReadout.text = "当前范围：半径 ${formatRadius(meters)}"
                                syncingSeekBarFromMap = true
                                radiusSeekBar.progress = ((meters - 100) / 100).coerceIn(0, 99)
                                syncingSeekBarFromMap = false
                            }
                        }
                    }
                },
                "CarMapBridge"
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String?) {
                    if (mapDestroyed) return
                    mapReady = true
                    if (vehLat != null && vehLon != null) {
                        view.evaluateJavascript(
                            "window.CarMap && window.CarMap.setVehicleLocation($vehLat, $vehLon, ${JSONObject.quote("当前车辆位置")});",
                            null
                        )
                    }
                    view.evaluateJavascript(
                        "window.CarMap && window.CarMap.setGeofencePicker($selectedLat, $selectedLon, $selectedRadiusMeters);",
                        null
                    )
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/map/index.html")
        }
        mapWebView = wv
        mapContainer.addView(wv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(
            mapContainer,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(6)
                bottomMargin = dp(12)
            }
        )

        // Bottom sleek control panel (name underline input + live radius readout + SeekBar)
        val controlPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(22))
        }
        controlPanel.addView(nameWrap, lp(bottom = dp(14)))

        val rangeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rangeRow.addView(rangeReadout, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        rangeRow.addView(text("拖动地图或滑块调整", 12f, TEXT_SUB))
        controlPanel.addView(rangeRow, lp(bottom = dp(8)))
        controlPanel.addView(radiusSeekBar, lp())

        val scaleLabels = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        scaleLabels.addView(text("100m", 11f, TEXT_SUB), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        scaleLabels.addView(text("10km", 11f, TEXT_SUB))
        controlPanel.addView(scaleLabels, lp(top = dp(2)))

        root.addView(controlPanel, lp())

        val destroyCallback: () -> Unit = {
            if (!mapDestroyed) {
                mapDestroyed = true
                mapReady = false
                mapWebView?.let { webView ->
                    webView.stopLoading()
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                mapWebView = null
            }
        }
        return root to destroyCallback
    }

    // =========================================================================
    // 2. Menu Row 3 (地点): 全屏二级页面 (46% 固定地图 + 历史行程)
    // =========================================================================

    /**
     * Builds the full-screen Location & Trips subpage (`Screen.LOCATION`) for persistent-Home
     * overlay navigation (zero 3D reload). Returns `(rootView, destroyCallback)`.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun buildLocationSubpageView(
        onBack: () -> Unit,
        registerAddressUpdate: ((String) -> Unit) -> (() -> Unit) = { { } }
    ): Pair<View, () -> Unit> {
        val profile = profileProvider()
        var mapWebView: WebView? = null
        var mapReady = false
        var mapDestroyed = false
        var pendingMapJs: String? = null
        var clearTripSelection: () -> Unit = {}

        fun runMapJs(js: String) {
            if (mapDestroyed) return
            val wv = mapWebView ?: return
            if (mapReady) {
                wv.evaluateJavascript(js, null)
            } else {
                pendingMapJs = js
            }
        }

        fun plotTripOnMap(trip: TripRecordItem): Boolean {
            if (trip.points.isEmpty()) return false
            val ptsJson = JSONArray()
            trip.points.forEach { pt ->
                ptsJson.put(JSONObject().put("lat", pt.lat).put("lon", pt.lon))
            }
            val label = buildString {
                val timePart = listOf(trip.startTime, trip.endTime)
                    .filter { it.isNotBlank() }
                    .joinToString(" → ")
                append(timePart.ifBlank { trip.curDate.ifBlank { "行驶轨迹" } })
                if (trip.mileageKm.isNotBlank()) append(" · ${trip.mileageKm} km")
            }
            runMapJs("window.CarMap && window.CarMap.showTripRoute($ptsJson, ${JSONObject.quote(label)});")
            return true
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            isClickable = true
            isFocusable = true
        }

        val livePill = text("实时位置", 12.5f, Color.WHITE, Typeface.BOLD).apply {
            background = rounded(BTN_SECONDARY, 14f, Color.rgb(64, 68, 78), 0.7f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener {
                runMapJs("window.CarMap && window.CarMap.returnToLiveLocation && window.CarMap.returnToLiveLocation();")
                clearTripSelection()
                visibility = View.GONE
            }
        }

        root.addView(
            AppComponents.createTopBar(
                context = context,
                title = "地点",
                onBack = onBack,
                rightView = livePill
            ),
            lp()
        )

        // Top pinned 46% screen-height clean dark interactive map
        val screenH = context.resources.displayMetrics.heightPixels
        val mapHeight = (screenH * 0.46f).roundToInt().coerceIn(dp(280), dp(420))
        val mapContainer = FrameLayout(context).apply {
            background = rounded(MAP_BG, 14f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(14f))
                }
            }
            clipToOutline = true
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val wv = object : WebView(context) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(MAP_BG)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onRouteModeChanged(isViewingRoute: Boolean) {
                        post {
                            if (mapDestroyed) return@post
                            livePill.visibility = if (isViewingRoute) View.VISIBLE else View.GONE
                            if (!isViewingRoute) clearTripSelection()
                        }
                    }
                },
                "CarMapBridge"
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String?) {
                    if (mapDestroyed) return
                    mapReady = true
                    val t = telemetryProvider()
                    val vLat = t?.latitude
                    val vLon = t?.longitude
                    val hasV = vLat != null && vLon != null && (vLat != 0.0 || vLon != 0.0)
                    val userLoc = deviceLocationProvider()
                    val hasU = userLoc != null && (userLoc.first != 0.0 || userLoc.second != 0.0)
                    if (hasV || hasU) {
                        val vLatArg = if (hasV) "$vLat" else "null"
                        val vLonArg = if (hasV) "$vLon" else "null"
                        val uLatArg = if (hasU) "${userLoc!!.first}" else "null"
                        val uLonArg = if (hasU) "${userLoc!!.second}" else "null"
                        val badgeLabel = if (hasV) "车辆与当前位置" else "当前位置"
                        view.evaluateJavascript(
                            "window.CarMap && window.CarMap.setLocations($vLatArg, $vLonArg, ${JSONObject.quote(badgeLabel)}, $uLatArg, $uLonArg);",
                            null
                        )
                    }
                    pendingMapJs?.let { js ->
                        pendingMapJs = null
                        view.evaluateJavascript(js, null)
                    }
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/map/index.html")
        }
        mapWebView = wv
        mapContainer.addView(wv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Embedded Navigation pill inside the map overlay (bottom-right next to recenter control)
        val mapNavBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(232, 24, 26, 32), 18f, Color.argb(95, 61, 155, 255), 0.9f)
            setPadding(dp(12), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            elevation = dpF(4f)
            contentDescription = "导航到车辆位置"
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener {
                AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                val t = telemetryProvider()
                val lat = t?.latitude
                val lon = t?.longitude
                if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                    showNavigationAppPicker(lat, lon, liveAddressLabelProvider().ifBlank { "我的车辆" })
                } else {
                    toast("暂无可导航的车辆位置")
                }
            }
        }
        mapNavBtn.addView(
            AppIconView(context, AppIconType.LOCATION_ARROW, ThemeTokens.ACCENT_LIGHT),
            LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(5) }
        )
        mapNavBtn.addView(text("导航", 13f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD))
        mapContainer.addView(
            mapNavBtn,
            FrameLayout.LayoutParams(WRAP_CONTENT, dp(36), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(56)
                bottomMargin = dp(12)
            }
        )

        root.addView(
            mapContainer,
            LinearLayout.LayoutParams(MATCH_PARENT, mapHeight).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        )

        // Compact borderless address & distance summary directly beneath the map
        val addrBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val locationTelemetry = telemetryProvider()
        val hasVehicleLocation = locationTelemetry?.latitude != null &&
            locationTelemetry.longitude != null &&
            (locationTelemetry.latitude != 0.0 || locationTelemetry.longitude != 0.0)
        val initialStreet = liveAddressLabelProvider().trim()
        val distLabel = if (hasVehicleLocation) {
            liveDistanceLabelProvider().trim()
        } else {
            ""
        }
        val addressView = text("", 15f, TEXT_PRIMARY, Typeface.BOLD).apply { maxLines = 1 }
        val distanceView = text("", 12.5f, TEXT_SUB).apply { maxLines = 1 }

        fun applyLocationSummary(hasLoc: Boolean, street: String, dist: String) {
            when {
                !hasLoc -> {
                    addressView.text = "车辆位置暂不可用"
                    addressView.visibility = View.VISIBLE
                    distanceView.visibility = View.GONE
                }
                street.isNotBlank() -> {
                    addressView.text = street
                    addressView.visibility = View.VISIBLE
                    distanceView.text = dist
                    distanceView.visibility = if (dist.isNotBlank()) View.VISIBLE else View.GONE
                }
                dist.isNotBlank() -> {
                    addressView.text = dist
                    addressView.visibility = View.VISIBLE
                    distanceView.visibility = View.GONE
                }
                else -> {
                    addressView.visibility = View.GONE
                    distanceView.visibility = View.GONE
                }
            }
        }
        applyLocationSummary(hasVehicleLocation, initialStreet, distLabel)
        addrBar.addView(addressView)
        addrBar.addView(distanceView, lp(top = dp(2)))
        root.addView(addrBar, lp())

        val unregisterAddressUpdate = registerAddressUpdate { resolved ->
            val current = telemetryProvider()
            val lat = current?.latitude
            val lon = current?.longitude
            val hasLoc = lat != null && lon != null && (lat != 0.0 || lon != 0.0)
            applyLocationSummary(hasLoc, resolved.trim(), liveDistanceLabelProvider().trim())
        }

        root.addView(AppComponents.createHairline(context, startInsetDp = 20))
        root.addView(AppComponents.createSectionHeader(context, "历史行程"), lp())

        // Lower independent scrollable pane: Date pills + Historical Trip List (with clear empty state)
        val lowerScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(lowerScroll)
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(20))
        }
        lowerScroll.addView(listContainer, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(lowerScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        loadTripHistoryTab(
            container = listContainer,
            profile = profile,
            isTabActive = { !mapDestroyed },
            autoPlotFirst = false,
            onClearSelectionRegistrar = { clearFn -> clearTripSelection = clearFn },
            onPlotTrip = { trip ->
                val plotted = plotTripOnMap(trip)
                if (plotted) livePill.visibility = View.VISIBLE
                plotted
            }
        )

        val destroyCallback: () -> Unit = {
            unregisterAddressUpdate()
            if (!mapDestroyed) {
                mapDestroyed = true
                mapReady = false
                pendingMapJs = null
                mapWebView?.let { webView ->
                    webView.stopLoading()
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                mapWebView = null
            }
        }
        return root to destroyCallback
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun showNavigationAppPicker(lat: Double, lon: Double, label: String) {
        val encLabel = runCatching { URLEncoder.encode(label, "UTF-8") }.getOrDefault("我的车辆")
        data class NavOption(val title: String, val subtitle: String, val launch: () -> Unit)

        val options = mutableListOf<NavOption>()
        if (isPackageInstalled("com.autonavi.minimap")) {
            options += NavOption("高德地图", "步行 / 驾车导航前往车辆") {
                val primary = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("androidamap://navi?sourceApplication=CarPlus&poiname=$encLabel&lat=$lat&lon=$lon&dev=0&style=2")
                ).setPackage("com.autonavi.minimap")
                val fallback = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("amapuri://route/plan/?dlat=$lat&dlon=$lon&dname=$encLabel&dev=0&t=2")
                ).setPackage("com.autonavi.minimap")
                runCatching { context.startActivity(primary) }
                    .recoverCatching { context.startActivity(fallback) }
                    .onFailure { toast("无法拉起高德地图") }
            }
        }
        if (isPackageInstalled("com.baidu.BaiduMap")) {
            options += NavOption("百度地图", "导航前往车辆位置") {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("baidumap://map/direction?destination=name:$encLabel|latlng:$lat,$lon&coord_type=gcj02&mode=walking")
                ).setPackage("com.baidu.BaiduMap")
                runCatching { context.startActivity(intent) }.onFailure { toast("无法拉起百度地图") }
            }
        }
        if (isPackageInstalled("com.tencent.map")) {
            options += NavOption("腾讯地图", "导航前往车辆位置") {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("qqmap://map/routeplan?type=walk&to=$encLabel&tocoord=$lat,$lon&referer=CarPlus")
                ).setPackage("com.tencent.map")
                runCatching { context.startActivity(intent) }.onFailure { toast("无法拉起腾讯地图") }
            }
        }
        if (isPackageInstalled("com.google.android.apps.maps")) {
            options += NavOption("Google 地图", "打开地图定位并导航") {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:$lat,$lon?q=$lat,$lon($encLabel)")
                ).setPackage("com.google.android.apps.maps")
                runCatching { context.startActivity(intent) }.onFailure { toast("无法拉起 Google 地图") }
            }
        }
        options += NavOption("其他系统地图", "使用系统已安装的地图应用打开") {
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($encLabel)"))
            runCatching { context.startActivity(Intent.createChooser(geoIntent, "选择地图应用")) }
                .onFailure { toast("未找到可用的地图应用") }
        }

        showSheet("选择导航应用") { body, dialog ->
            options.forEachIndexed { idx, opt ->
                val row = AppComponents.createListRow(
                    context = context,
                    icon = AppIconType.MAP_PIN,
                    title = opt.title,
                    subtitle = opt.subtitle,
                    showChevron = true,
                    onClick = {
                        AppHaptics.perform(body, HapticLevel.LIGHT_TICK)
                        dialog.dismiss()
                        opt.launch()
                    }
                )
                body.addView(row, lp())
                if (idx < options.lastIndex) {
                    body.addView(AppComponents.createHairline(context, startInsetDp = 58))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showLocationAndTripsSheet(initialTab: Int = 0) {
        val profile = profileProvider()
        val startTab = initialTab.coerceIn(0, 1)
        var mapWebView: WebView? = null
        var mapReady = false
        var mapDestroyed = false
        var pendingMapJs: String? = null

        fun runMapJs(js: String) {
            if (mapDestroyed) return
            val wv = mapWebView ?: return
            if (mapReady) {
                wv.evaluateJavascript(js, null)
            } else {
                pendingMapJs = js
            }
        }

        fun centerMapOnVehicle() {
            val t = telemetryProvider()
            val lat = t?.latitude
            val lon = t?.longitude
            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                runMapJs("window.CarMap && window.CarMap.setVehicleLocation($lat, $lon, ${JSONObject.quote("当前车辆位置")});")
            }
        }

        fun plotTripOnMap(trip: TripRecordItem): Boolean {
            if (trip.points.isEmpty()) return false
            val ptsJson = JSONArray()
            trip.points.forEach { pt ->
                ptsJson.put(JSONObject().put("lat", pt.lat).put("lon", pt.lon))
            }
            val label = buildString {
                val timePart = listOf(trip.startTime, trip.endTime)
                    .filter { it.isNotBlank() }
                    .joinToString(" → ")
                append(timePart.ifBlank { trip.curDate.ifBlank { "行驶轨迹" } })
                if (trip.mileageKm.isNotBlank()) append(" · ${trip.mileageKm} km")
            }
            runMapJs("window.CarMap && window.CarMap.showTripRoute($ptsJson, ${JSONObject.quote(label)});")
            return true
        }

        activeDialog?.dismiss()
        var depthRestored = false
        lateinit var outer: LinearLayout
        val dialog = object : Dialog(context, R.style.Theme_CarPlus_BottomSheetDialog) {
            fun restoreDepthIfNeeded() {
                if (activeDialog === this && !depthRestored) {
                    depthRestored = true
                    onDepthRecede(false)
                }
            }

            fun dismissImmediately() {
                outer.animate().cancel()
                restoreDepthIfNeeded()
                runCatching { if (isShowing) super.dismiss() }
            }

            override fun dismiss() {
                if (!isShowing || MotionSpec.isBypassingSheetDismiss(this)) {
                    runCatching { super.dismiss() }
                    return
                }
                MotionSpec.dismissSheetAnimated(outer, this) {
                    restoreDepthIfNeeded()
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheetHeight = (context.resources.displayMetrics.heightPixels * 0.90f).roundToInt()
        val mapHeight = (sheetHeight * 0.38f).roundToInt().coerceIn(dp(248), dp(310))

        outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = topRounded(CARD_MODAL, 20f)
            setPadding(dp(20), dp(12), dp(20), dp(18))
        }
        val handle = View(context).apply { background = rounded(Color.rgb(72, 74, 82), 2f) }
        outer.addView(handle, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        outer.addView(header)
        header.addView(
            text("地点与行程", 20f, TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )

        // Pinned Top Map (~38% of 90% sheet height) — never scrolls off-screen
        val mapContainer = FrameLayout(context).apply {
            background = rounded(MAP_BG, 12f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(12f))
                }
            }
            clipToOutline = true
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val wv = object : WebView(context) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(MAP_BG)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String?) {
                    if (mapDestroyed) return
                    mapReady = true
                    val t = telemetryProvider()
                    val lat = t?.latitude
                    val lon = t?.longitude
                    if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                        view.evaluateJavascript(
                            "window.CarMap && window.CarMap.setVehicleLocation($lat, $lon, ${JSONObject.quote("当前车辆位置")});",
                            null
                        )
                    }
                    pendingMapJs?.let { js ->
                        pendingMapJs = null
                        view.evaluateJavascript(js, null)
                    }
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/map/index.html")
        }
        mapWebView = wv
        mapContainer.addView(wv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        outer.addView(mapContainer, lp(top = dp(10), bottom = dp(12), height = mapHeight))

        // Middle Pinned 2-Tab Sliding Segmented Bar
        val lowerScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(lowerScroll)
        val contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        lowerScroll.addView(contentHost, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        var currentTab = startTab
        var renderTab: (Int) -> Unit = {}

        val tabs = segmentedBar(listOf("实时位置", "历史行程"), startTab) { idx ->
            currentTab = idx
            renderTab(idx)
        }
        outer.addView(tabs, lp(bottom = dp(12)))
        outer.addView(lowerScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        renderTab = { tab ->
            lowerScroll.scrollTo(0, 0)
            contentHost.removeAllViews()
            when (tab) {
                0 -> {
                    centerMapOnVehicle()
                    renderLiveLocationTab(contentHost, dialog)
                }
                else -> loadTripHistoryTab(
                    container = contentHost,
                    profile = profile,
                    isTabActive = { !mapDestroyed && currentTab == 1 },
                    onPlotTrip = { plotTripOnMap(it) }
                )
            }
            animateTabContent(contentHost)
        }
        renderTab(currentTab)

        AppSystemInsets.installBottomSheetContent(dialog, outer, fixedSheetHeightPx = sheetHeight, dimAmount = 0.68f)
        MotionSpec.attachSheetSwipeToDismiss(outer, lowerScroll, dialog) {
            dialog.restoreDepthIfNeeded()
        }
        dialog.setOnDismissListener {
            mapDestroyed = true
            mapReady = false
            pendingMapJs = null
            mapWebView?.let { webView ->
                webView.stopLoading()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
            mapWebView = null
            if (activeDialog === dialog) {
                activeDialog = null
                activeImmediateDismiss = null
                if (!depthRestored) {
                    depthRestored = true
                    onDepthRecede(false)
                }
            }
        }
        activeDialog = dialog
        activeImmediateDismiss = { dialog.dismissImmediately() }
        dialog.show()
        onDepthRecede(true)
        MotionSpec.animateSheetEnter(outer)
    }

    private fun renderLiveLocationTab(
        container: LinearLayout,
        dialog: Dialog
    ) {
        val t = telemetryProvider()
        val hasCoords = t?.latitude != null && t.longitude != null && (t.latitude != 0.0 || t.longitude != 0.0)
        val onlineText = when (t?.online?.trim()?.lowercase(Locale.US)) {
            "1", "true", "online", "在线" -> "在线"
            "0", "false", "offline", "离线" -> "离线"
            else -> if (t != null) "在线" else "未获取"
        }
        val locationStatus = if (hasCoords) "$onlineText · 已定位" else "$onlineText · 暂无定位"

        val card = cardBox()
        val resolvedPlate = plateNoProvider().ifBlank { t?.licensePlate.orEmpty() }
        val rows = buildList {
            if (resolvedPlate.isNotBlank()) add("车牌号码" to resolvedPlate)
            add("车辆位置" to if (hasCoords) liveAddressLabelProvider().ifBlank { "已定位" } else "位置暂不可用")
            add("距离" to if (hasCoords) liveDistanceLabelProvider().ifBlank { "距离暂不可用" } else "距离暂不可用")
            add("车辆在线 / 定位状态" to locationStatus)
            t?.time?.takeIf { it.isNotBlank() }?.let { add("定位时间" to it) }
        }
        rows.forEachIndexed { idx, (k, v) ->
            card.addView(kvRow(k, v), lp(top = if (idx == 0) 0 else dp(2)))
        }
        container.addView(card)
    }

    private fun loadTripHistoryTab(
        container: LinearLayout,
        profile: VehicleProfile,
        isTabActive: () -> Boolean,
        autoPlotFirst: Boolean = true,
        onClearSelectionRegistrar: ((() -> Unit) -> Unit)? = null,
        onPlotTrip: (TripRecordItem) -> Boolean
    ) {
        container.addView(loadingHint("正在加载历史行程记录..."))
        scope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchTripHistoryDetailed(profile.vin, profile.accessToken)
                }
            }

            if (!isTabActive()) return@launch
            container.removeAllViews()

            res.onFailure { err ->
                container.addView(errorHint("行程查询失败：${err.message.orEmpty().take(90)}"))
                container.addView(secondaryBtn("轻触重试") {
                    container.removeAllViews()
                    loadTripHistoryTab(container, profile, isTabActive, autoPlotFirst, onClearSelectionRegistrar, onPlotTrip)
                }, lp(top = dp(10), height = dp(42)))
                return@launch
            }

            val historyResult = res.getOrThrow()
            val dayGroups = historyResult.dayGroups
                .filter { group ->
                    group.trips.isNotEmpty() && !(group.dateKey.trim().length <= 2 && group.dateKey.trim().all { it.isDigit() })
                }

            if (dayGroups.isEmpty()) {
                val code = historyResult.statusCode.trim()
                val isApiError = code.isNotBlank() && code != "0200" && code != "0204"
                if (isApiError) {
                    val msg = historyResult.statusMessage.ifBlank { "请检查登录状态或稍后重试" }
                    container.addView(errorHint("行程查询异常：$msg"))
                    container.addView(secondaryBtn("轻触重试") {
                        container.removeAllViews()
                        loadTripHistoryTab(container, profile, isTabActive, autoPlotFirst, onClearSelectionRegistrar, onPlotTrip)
                    }, lp(top = dp(10), height = dp(42)))
                } else {
                    val emptySub = if (tripTrackEnabledProvider() == false) {
                        "行程轨迹记录未开启，可前往「设置 → 行程轨迹记录」开启"
                    } else {
                        "行驶结束后将自动同步最近行程"
                    }
                    container.addView(
                        AppComponents.createEmptyState(
                            context = context,
                            icon = AppIconType.CLOCK,
                            title = "暂无历史行程",
                            subtitle = emptySub
                        ).apply {
                            setPadding(dp(20), dp(28), dp(20), dp(28))
                        }
                    )
                }
                return@launch
            }

            val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val hScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val pillsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            hScroll.addView(pillsRow)
            container.addView(hScroll, lp(bottom = dp(12)))

            val pillViews = mutableListOf<TextView>()
            fun selectDate(selectedIdx: Int, shouldAutoPlot: Boolean) {
                val group = dayGroups.getOrNull(selectedIdx) ?: return
                pillViews.forEachIndexed { idx, tv ->
                    val active = idx == selectedIdx
                    tv.background = rounded(if (active) PRIMARY_BLUE else BTN_SECONDARY, 16f)
                    tv.setTextColor(if (active) Color.WHITE else TEXT_BODY)
                }
                renderTripsForDay(listHost, group, shouldAutoPlot, onClearSelectionRegistrar, onPlotTrip)
            }

            dayGroups.forEachIndexed { idx, group ->
                val pillLabel = formatTripDatePill(group.dateLabel.ifBlank { group.dateKey })
                val pill = text(pillLabel, 12.5f, TEXT_BODY, Typeface.BOLD).apply {
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                        selectDate(idx, autoPlotFirst)
                    }
                    MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
                }
                pillViews += pill
                pillsRow.addView(pill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (idx > 0) marginStart = dp(8)
                })
            }
            container.addView(listHost, lp())
            selectDate(0, autoPlotFirst)
        }
    }

    private fun renderTripsForDay(
        listHost: LinearLayout,
        group: TripDayGroup,
        autoPlotFirst: Boolean,
        onClearSelectionRegistrar: ((() -> Unit) -> Unit)?,
        onPlotTrip: (TripRecordItem) -> Boolean
    ) {
        listHost.removeAllViews()
        val records = group.trips
        if (records.isEmpty()) {
            listHost.addView(emptyHint("该日期暂无行程记录"))
            return
        }

        val cardViews = mutableListOf<LinearLayout>()
        val clearAll: () -> Unit = {
            cardViews.forEach { card ->
                card.background = rounded(CARD_INNER, 12f)
            }
        }
        onClearSelectionRegistrar?.invoke(clearAll)

        fun highlightAndPlot(selectedIdx: Int) {
            val target = records.getOrNull(selectedIdx) ?: return
            if (target.points.isEmpty()) {
                toast("该行程暂无 GPS 轨迹点")
                return
            }
            onPlotTrip(target)
            cardViews.forEachIndexed { idx, card ->
                card.background = if (idx == selectedIdx) {
                    rounded(CARD_INNER, 12f, PRIMARY_BLUE, 1.5f)
                } else {
                    rounded(CARD_INNER, 12f)
                }
            }
        }

        records.forEachIndexed { idx, trip ->
            val card = tripCard(
                trip = trip,
                isSelected = false,
                onSelectRoute = { highlightAndPlot(idx) },
                onOpenDetail = { showTripDetailModal(trip) }
            )
            cardViews += card
            listHost.addView(card, lp(bottom = dp(10)))
        }

        if (autoPlotFirst) {
            val firstPlottableIdx = records.indexOfFirst { it.points.isNotEmpty() }
            if (firstPlottableIdx >= 0) {
                highlightAndPlot(firstPlottableIdx)
            }
        }
    }

    private fun formatTripDatePill(raw: String): String {
        val cleaned = raw.trim().substringBefore(' ')
        if (cleaned.isBlank()) return "最近行程"

        val parsed = parseTripDateCalendar(cleaned) ?: return cleaned
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        val py = parsed.get(Calendar.YEAR)
        val pm = parsed.get(Calendar.MONTH)
        val pd = parsed.get(Calendar.DAY_OF_MONTH)

        return when {
            py == today.get(Calendar.YEAR) && pm == today.get(Calendar.MONTH) && pd == today.get(Calendar.DAY_OF_MONTH) -> "今天"
            py == yesterday.get(Calendar.YEAR) && pm == yesterday.get(Calendar.MONTH) && pd == yesterday.get(Calendar.DAY_OF_MONTH) -> "昨天"
            else -> "${pm + 1}月${pd}日"
        }
    }

    private fun parseTripDateCalendar(dateToken: String): Calendar? {
        val patterns = listOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd", "yyyy.MM.dd")
        for (pattern in patterns) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            val date = runCatching { fmt.parse(dateToken) }.getOrNull()
            if (date != null) {
                return Calendar.getInstance().apply { time = date }
            }
        }
        val shortPatterns = listOf("MM-dd", "MM/dd", "M-d", "M/d")
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        for (pattern in shortPatterns) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            val date = runCatching { fmt.parse(dateToken) }.getOrNull()
            if (date != null) {
                return Calendar.getInstance().apply {
                    time = date
                    set(Calendar.YEAR, currentYear)
                }
            }
        }
        return null
    }

    private fun tripCard(
        trip: TripRecordItem,
        isSelected: Boolean = false,
        onSelectRoute: () -> Unit,
        onOpenDetail: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = if (isSelected) {
            rounded(CARD_INNER, 12f, PRIMARY_BLUE, 1.5f)
        } else {
            rounded(CARD_INNER, 12f)
        }
        setPadding(dp(15), dp(14), dp(15), dp(13))
        isClickable = true
        isFocusable = true
        setOnClickListener { onSelectRoute() }
        MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timeLine = listOf(trip.startTime, trip.endTime)
            .filter { it.isNotBlank() }
            .joinToString(" → ")
            .ifBlank { trip.curDate.ifBlank { "行程记录" } }
        topRow.addView(
            text(timeLine, 14f, TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        topRow.addView(text("详情 ›", 12.5f, ACCENT_BLUE, Typeface.BOLD).apply {
            setPadding(dp(10), dp(3), 0, dp(3))
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenDetail() }
            MotionSpec.attachPressPhysics(this, pressedScale = 0.92f)
        })
        addView(topRow)

        if (trip.startAddress.isNotBlank() || trip.endAddress.isNotBlank()) {
            val route = "${trip.startAddress.ifBlank { "起点" }} → ${trip.endAddress.ifBlank { "终点" }}"
            addView(text(route, 12.5f, TEXT_BODY).apply { setLineSpacing(0f, 1.3f) }, lp(top = dp(6)))
        }

        val durationPart = trip.durationOrDistance
            .takeIf { it.isNotBlank() && it != trip.mileageKm }
            ?.let { raw -> if (raw.any { it in "时分秒:" }) "用时 $raw" else "用时 ${raw}" }
        val metrics = listOfNotNull(
            trip.mileageKm.takeIf { it.isNotBlank() }?.let { "${it} km" },
            durationPart,
            trip.avgSpeed.takeIf { it.isNotBlank() }?.let { "均速 ${it} km/h" },
            if (trip.points.isNotEmpty()) "${trip.points.size} 轨迹点" else "无轨迹"
        ).joinToString(" · ")
        if (metrics.isNotBlank()) {
            addView(
                text(metrics, 12f, if (trip.points.isNotEmpty()) TEXT_SUB else Color.rgb(112, 116, 126)),
                lp(top = dp(6))
            )
        }
    }

    private fun showTripDetailModal(trip: TripRecordItem) {
        showModalDialog("行程与轨迹详情") { box, dialog ->
            val pairs = listOf(
                "出发时间" to trip.startTime.ifBlank { "--" },
                "结束时间" to trip.endTime.ifBlank { "--" },
                "行驶里程" to trip.mileageKm.ifBlank { "--" }.withUnit("km"),
                "行驶时长" to trip.durationOrDistance.ifBlank { "--" },
                "平均车速" to trip.avgSpeed.ifBlank { "--" }.withUnit("km/h"),
                "最高车速" to trip.maxSpeed.ifBlank { "--" }.withUnit("km/h"),
                "油耗 / 电耗" to trip.fuelConsumption.ifBlank { "--" },
                "起点位置" to trip.startAddress.ifBlank { "--" },
                "终点位置" to trip.endAddress.ifBlank { "--" },
                "GPS 轨迹点数" to "${trip.points.size} 个"
            )
            pairs.forEachIndexed { idx, (k, v) ->
                box.addView(kvRow(k, v), lp(top = if (idx == 0) dp(8) else dp(2)))
            }
            box.addView(primaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(18), height = dp(44)))
        }
    }

    // =========================================================================
    // 3. Menu Row 4 (安全性和驾驶员): 数字钥匙 / 远程授权管理 / 防盗 & 密码
    // =========================================================================

    fun showSecurityAndDriverSheet(initialTab: Int = 0) {
        val profile = profileProvider()
        showSheet("安全性和驾驶员") { body, dialog ->
            val contentHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            var currentTab = if (initialTab > 0) 1 else 0
            var renderTab: (Int) -> Unit = {}

            val tabs = segmentedBar(listOf("数字钥匙", "远程授权"), currentTab) { idx ->
                currentTab = idx
                renderTab(idx)
            }
            body.addView(tabs, lp(top = dp(10), bottom = dp(14)))
            body.addView(contentHost, lp())

            renderTab = { tab ->
                contentHost.removeAllViews()
                when (tab) {
                    0 -> renderDigitalKeySecurityTab(contentHost, profile, dialog)
                    else -> {
                        val delegate = onOpenRemoteAuthManagement
                        if (delegate != null) {
                            dialog.dismiss()
                            delegate()
                        } else {
                            loadRemoteAuthTab(contentHost, profile) { renderTab(1) }
                        }
                    }
                }
                animateTabContent(contentHost)
            }
            renderTab(currentTab)
        }
    }

    private fun renderDigitalKeySecurityTab(
        container: LinearLayout,
        profile: VehicleProfile,
        dialog: Dialog
    ) {
        val keyCard = cardBox()
        keyCard.addView(text("手机蓝牙数字钥匙", 15f, TEXT_PRIMARY, Typeface.BOLD))
        val bleReady = isBleConnected()
        val ready = hasLocalKeyMaterial(profile)
        val lines = mutableListOf<String>()
        lines += when {
            bleReady -> "通道状态：手机钥匙已连接（BLE 近场直连）"
            isBleNearby() -> "通道状态：发现附近车辆，正在建立蓝牙会话..."
            else -> "通道状态：手机钥匙已断开连接（当前远程网络）"
        }
        lines += if (ready) {
            "本地钥匙材料：已就绪（靠近车辆自动无感切换蓝牙）"
        } else {
            "本地钥匙材料：尚未同步，请点击下方一键拉取解封"
        }
        val pinHint = lastPairingPinProvider()
        if (pinHint.isNotBlank() && !bleReady) {
            lines += "最近动态配对码：$pinHint"
        }
        keyCard.addView(text(lines.joinToString("\n"), 13f, TEXT_SUB).apply {
            setLineSpacing(0f, 1.45f)
        }, lp(top = dp(6)))
        container.addView(keyCard)

        container.addView(primaryBtn(
            if (ready) "重新从云端拉取并更新数字钥匙" else "从云端一键拉取并解封新车钥匙"
        ) {
            dialog.dismiss()
            onTriggerCloudKeyProvision()
        }, lp(top = dp(14), height = dp(48)))

        container.addView(secondaryBtn("立即扫描并连接附近蓝牙钥匙") {
            dialog.dismiss()
            onScanNearbyBle()
            onStatusMessage("正在扫描附近车辆蓝牙...")
        }, lp(top = dp(10), height = dp(46)))

        // Quick security actions (OTA upgrade belongs in top-right account/system menu)
        container.addView(smallBtn("防盗与电子围栏") {
            dialog.dismiss()
            showAntiTheftSheet()
        }, lp(top = dp(14), height = dp(42)))

        val quickRow2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        container.addView(quickRow2, lp(top = dp(8)))
        quickRow2.addView(smallBtn("修改登录密码") {
            showChangeLoginPasswordModal(profile)
        }, weightLp(0, dp(42)))
        quickRow2.addView(smallBtn("修改/设置车控 PIN") {
            showPinManagementModal(profile)
        }, weightLp(dp(8), dp(42)))
    }

    private fun loadRemoteAuthTab(
        container: LinearLayout,
        profile: VehicleProfile,
        onReload: () -> Unit
    ) {
        container.addView(loadingHint("正在查询远程控车授权列表..."))
        scope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchRemoteAuthList(profile.vin, profile.accessToken)
                }
            }
            container.removeAllViews()

            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            container.addView(actions, lp(bottom = dp(12)))
            actions.addView(primaryBtn("+ 新增远程授权") {
                showGrantRemoteAuthModal(profile, onReload)
            }, weightLp(0, dp(44)))
            actions.addView(secondaryBtn("授权操作记录") {
                showAuthorizedOperateRecordsModal(profile)
            }, weightLp(dp(8), dp(44)))

            res.onSuccess { list ->
                if (list.isEmpty()) {
                    container.addView(emptyHint("当前暂无远程控车授权人"))
                } else {
                    list.forEach { item ->
                        container.addView(remoteAuthCard(item, profile, onReload), lp(bottom = dp(10)))
                    }
                }
            }.onFailure { err ->
                container.addView(errorHint("远程授权查询失败：${err.message.orEmpty().take(90)}"))
            }
        }
    }

    private fun remoteAuthCard(
        item: VehicleAuthorizationItem,
        profile: VehicleProfile,
        onReload: () -> Unit
    ): LinearLayout = cardBox().apply {
        val title = item.receiverName.ifBlank { item.receiverMobile.ifBlank { "授权人 #${item.keyId}" } }
        addView(text(title, 15f, TEXT_PRIMARY, Typeface.BOLD))
        val sub = listOfNotNull(
            item.authorityDesc.takeIf { it.isNotBlank() },
            item.status.takeIf { it.isNotBlank() }?.let { "状态:$it" }
        ).joinToString(" · ")
        if (sub.isNotBlank()) addView(text(sub, 12.5f, TEXT_SUB), lp(top = dp(4)))
        if (item.startTime.isNotBlank() || item.endTime.isNotBlank()) {
            addView(text("期限：${item.startTime} ~ ${item.endTime}", 12f, TEXT_SUB), lp(top = dp(3)))
        }

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        addView(row, lp(top = dp(10)))
        row.addView(smallBtn("查看详情") {
            showRemoteAuthDetailModal(item, profile)
        }, weightLp(0, dp(38)))
        row.addView(smallBtn("撤销授权", danger = true) {
            val pin = cachedPinProvider().ifBlank { profile.controlPin }
            if (pin.isBlank()) {
                toast("请先在安全性中设置 6 位 PIN 码")
                showPinManagementModal(profile)
                return@smallBtn
            }
            AppComponents.showConfirmDialog(
                context = context,
                title = "撤销远程控车授权",
                message = "确认撤销「$title」的远程控车权限？",
                confirmText = "撤销",
                cancelText = "取消",
                dangerConfirm = true
            ) {
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.revokeRemoteAuth(profile.vin, item.keyId, pin, "0", profile.accessToken)
                        }
                    }.getOrDefault(false)
                    toast(if (ok) "远程授权已撤销" else "撤销远程授权失败")
                    if (ok) onReload()
                }
            }
        }, weightLp(dp(8), dp(38)))
    }

    private fun showRemoteAuthDetailModal(item: VehicleAuthorizationItem, profile: VehicleProfile) {
        showModalDialog("远程授权详情") { box, dialog ->
            val tv = text("正在获取授权详情...", 13.5f, TEXT_BODY).apply { setLineSpacing(0f, 1.45f) }
            box.addView(tv, lp(top = dp(12)))
            box.addView(primaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(18), height = dp(44)))

            scope.launch {
                val detail = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchRemoteAuthDetail(item.keyId, profile.accessToken)
                    }
                }.getOrDefault(item)
                tv.text = listOf(
                    "被授权账号：${detail.receiverMobile.ifBlank { detail.receiverName }}",
                    "授权编号：${detail.keyId}",
                    "开始时间：${detail.startTime.ifBlank { "--" }}",
                    "结束时间：${detail.endTime.ifBlank { "--" }}",
                    "授权权限：${detail.authorityDesc.ifBlank { detail.authority.ifBlank { "--" } }}"
                ).joinToString("\n")
            }
        }
    }

    private fun showGrantRemoteAuthModal(profile: VehicleProfile, onGranted: () -> Unit) {
        val (defStart, defEnd) = defaultAuthWindow()
        showModalDialog("新增远程控车授权") { box, dialog ->
            val mobileEt = modalInput("被授权人手机号", InputType.TYPE_CLASS_PHONE)
            val (startField, getStartTime) = AppComponents.createDateTimePickerField(
                context = context,
                hint = "开始时间",
                initialValue = defStart,
                mode = DateTimeMode.DATE_TIME
            )
            val (endField, getEndTime) = AppComponents.createDateTimePickerField(
                context = context,
                hint = "结束时间",
                initialValue = defEnd,
                mode = DateTimeMode.DATE_TIME
            )
            val authEt = modalInput("权限码（默认 1,2,3）").apply { setText("1,2,3") }
            val pinEt = modalInput(
                "6 位 PIN 码",
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ).apply {
                val cached = cachedPinProvider().ifBlank { profile.controlPin }
                if (cached.isNotBlank()) setText(cached)
            }
            box.addView(mobileEt, lp(top = dp(12), height = dp(46)))
            box.addView(startField, lp(top = dp(10)))
            box.addView(endField, lp(top = dp(8)))
            box.addView(authEt, lp(top = dp(12), height = dp(46)))
            box.addView(pinEt, lp(top = dp(10), height = dp(46)))

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(btns, lp(top = dp(18)))
            btns.addView(secondaryBtn("取消") { dialog.dismiss() }, weightLp(0, dp(44)))
            btns.addView(primaryBtn("确认新增") {
                val mobile = mobileEt.text.toString().trim()
                val start = getStartTime().trim()
                val end = getEndTime().trim()
                val authority = authEt.text.toString().trim().ifBlank { "1,2,3" }
                val pin = pinEt.text.toString().trim()
                if (mobile.length < 11 || pin.isBlank()) {
                    toast("请填写正确手机号和车控 PIN")
                    return@primaryBtn
                }
                dialog.dismiss()
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.grantRemoteAuth(
                                vin = profile.vin,
                                receiverMobile = mobile,
                                startTime = start,
                                endTime = end,
                                pin = pin,
                                authority = authority,
                                accessToken = profile.accessToken
                            )
                        }
                    }.getOrDefault(false)
                    toast(if (ok) "远程控车授权已添加" else "新增远程授权失败")
                    if (ok) onGranted()
                }
            }, weightLp(dp(10), dp(44)))
        }
    }

    private fun showAuthorizedOperateRecordsModal(profile: VehicleProfile) {
        showModalDialog("被授权人操作记录") { box, dialog ->
            val tv = text("正在加载授权操作日志...", 13f, TEXT_BODY).apply { setLineSpacing(0f, 1.45f) }
            box.addView(tv, lp(top = dp(12)))
            box.addView(primaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(18), height = dp(44)))

            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchAuthorizedOperateRecords(profile.vin, "", 1, 15, profile.accessToken)
                    }
                }
                res.onSuccess { items ->
                    tv.text = if (items.isEmpty()) "暂无被授权人远程控车操作记录。"
                    else items.take(10).joinToString("\n\n") { it.toString().take(160) }
                }.onFailure { err ->
                    tv.text = "记录加载失败：${err.message.orEmpty().take(100)}"
                }
            }
        }
    }

    // =========================================================================
    // 4. Top-Right Menu: 账户安全、车辆升级 (OTA) 与修改密码
    // =========================================================================

    fun showAccountAndMenuSheet() {
        val profile = profileProvider()
        val modelName = modelNameProvider().ifBlank { "我的座驾" }
        val plate = plateNoProvider()

        showSheet("账户与车辆设置") { body, dialog ->
            val infoBox = cardBox()
            infoBox.addView(text(modelName, 17f, TEXT_PRIMARY, Typeface.BOLD))
            val meta = listOfNotNull(
                plate.takeIf { it.isNotBlank() },
                maskedVin(profile.vin),
                maskMobile(profile.mobileNo).takeIf { it.isNotBlank() }
            ).joinToString("  ·  ")
            infoBox.addView(text(meta, 13f, TEXT_SUB), lp(top = dp(6)))
            body.addView(infoBox, lp(top = dp(10)))

            body.addView(secondaryBtn("车辆升级 (OTA)") {
                dialog.dismiss()
                showVehicleUpgradeSheet()
            }, lp(top = dp(14), height = dp(46)))

            body.addView(secondaryBtn("修改登录密码") {
                showChangeLoginPasswordModal(profile)
            }, lp(top = dp(10), height = dp(46)))

            body.addView(secondaryBtn("车控安防密码 (PIN)") {
                showPinManagementModal(profile)
            }, lp(top = dp(10), height = dp(46)))

            body.addView(secondaryBtn("消息提醒") {
                dialog.dismiss()
                showMessageAndAlertCenterSheet()
            }, lp(top = dp(10), height = dp(46)))

            val logoutBtn = text("退出登录", 15f, ERROR_RED, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = rounded(BTN_SECONDARY, 6f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onLogout()
                }
                MotionSpec.attachPressPhysics(this, pressedScale = 0.96f)
            }
            body.addView(logoutBtn, lp(top = dp(16), height = dp(46)))
        }
    }

    fun showVehicleUpgradeSheet(prefetchedInfo: VehicleUpgradeInfo? = null) {
        val profile = profileProvider()
        var pollJob: Job? = null
        var sheetActive = true

        showSheet(
            title = "车辆升级",
            onDismiss = {
                sheetActive = false
                pollJob?.cancel()
                pollJob = null
            }
        ) { body, dialog ->
            val host = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(host, lp(top = dp(10)))

            fun renderUpgradeResult(res: Result<VehicleUpgradeInfo>, retryAction: () -> Unit) {
                host.removeAllViews()
                res.onSuccess { info: VehicleUpgradeInfo ->
                    val hasNewVersion = info.hasUpgrade || info.upgradeTaskId.isNotBlank()
                    val card = cardBox()
                    card.addView(kvRow("当前版本", info.currentVersion.ifBlank { "--" }))
                    val statusText = when {
                        hasNewVersion -> "发现新版本"
                        else -> "已是最新版本"
                    }
                    card.addView(kvRow("固件状态", statusText))
                    if (info.upgradeTime.isNotBlank()) {
                        card.addView(kvRow("预计耗时", info.upgradeTime))
                    }

                    val notes = info.releaseNote.ifBlank { info.descriptionText }
                    if (notes.isNotBlank()) {
                        card.addView(text("更新说明", 12.5f, TEXT_SUB, Typeface.BOLD), lp(top = dp(10), bottom = dp(4)))
                        val notesScroll = ScrollView(context).apply {
                            isVerticalScrollBarEnabled = true
                            overScrollMode = View.OVER_SCROLL_NEVER
                        }
                        notesScroll.addView(
                            text(notes, 13f, TEXT_BODY).apply { setLineSpacing(0f, 1.45f) },
                            ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        )
                        card.addView(notesScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(180)))
                    }
                    host.addView(card)

                    // Inline silent-polling linear progress indicator (shown while upgrading)
                    val progressBox = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                    }
                    val progressRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val progressLabel = text("正在升级...", 13f, TEXT_BODY)
                    val progressPct = text("0%", 13f, ACCENT_BLUE, Typeface.BOLD)
                    progressRow.addView(progressLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    progressRow.addView(progressPct)
                    progressBox.addView(progressRow, lp(bottom = dp(6)))

                    val track = FrameLayout(context).apply {
                        background = rounded(Color.rgb(42, 45, 52), 3f)
                    }
                    val fill = View(context).apply {
                        background = rounded(PRIMARY_BLUE, 3f)
                    }
                    track.addView(fill, FrameLayout.LayoutParams(0, MATCH_PARENT))
                    progressBox.addView(track, lp(height = dp(6)))
                    host.addView(progressBox, lp(top = dp(14)))

                    fun updateProgressUi(pct: Int, label: String) {
                        val clamped = pct.coerceIn(0, 100)
                        progressBox.visibility = View.VISIBLE
                        progressLabel.text = label
                        progressPct.text = "$clamped%"
                        track.post {
                            val targetW = ((track.width * clamped) / 100f).roundToInt().coerceAtLeast(if (clamped > 0) dp(6) else 0)
                            fill.layoutParams = FrameLayout.LayoutParams(targetW, MATCH_PARENT)
                        }
                    }

                    fun startSilentProgressPoll(taskId: String) {
                        pollJob?.cancel()
                        pollJob = scope.launch {
                            while (isActive && sheetActive) {
                                val st = runCatching {
                                    withContext(Dispatchers.IO) {
                                        httpClient.fetchVehicleUpgradeStatus(profile.vin, taskId, profile.accessToken)
                                    }
                                }.getOrNull()
                                if (!sheetActive) break
                                if (st != null) {
                                    val label = if (st.surplusTime.isNotBlank()) {
                                        "正在升级 · 剩余 ${st.surplusTime}"
                                    } else {
                                        "正在升级中..."
                                    }
                                    updateProgressUi(st.percentage, label)
                                    if (st.percentage >= 100) break
                                }
                                delay(3_000L)
                            }
                        }
                    }

                    // Strict CTA: New version -> "立即升级" + "取消"; Already up to date -> ONLY "取消"
                    if (hasNewVersion && info.upgradeTaskId.isNotBlank()) {
                        val ctaRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                        ctaRow.addView(secondaryBtn("取消") { dialog.dismiss() }, weightLp(0, dp(46)))
                        lateinit var upgradeBtn: TextView
                        upgradeBtn = primaryBtn("立即升级") {
                            AppComponents.showConfirmDialog(
                                context = context,
                                title = "确认开始车辆升级",
                                message = "升级期间请确保车辆处于驻车状态且电量充足，过程中请勿操作车辆。",
                                confirmText = "立即升级",
                                cancelText = "取消"
                            ) {
                                AppHaptics.perform(upgradeBtn, HapticLevel.MEDIUM_CONFIRM)
                                upgradeBtn.isEnabled = false
                                upgradeBtn.alpha = 0.55f
                                upgradeBtn.text = "升级中..."
                                updateProgressUi(1, "正在下发升级指令...")
                                scope.launch {
                                    val ok = runCatching {
                                        withContext(Dispatchers.IO) {
                                            httpClient.triggerVehicleUpgrade(profile.vin, info.upgradeTaskId, profile.accessToken)
                                        }
                                    }.getOrDefault(false)
                                    if (!sheetActive) return@launch
                                    if (ok) {
                                        startSilentProgressPoll(info.upgradeTaskId)
                                    } else {
                                        progressBox.visibility = View.GONE
                                        upgradeBtn.isEnabled = true
                                        upgradeBtn.alpha = 1f
                                        upgradeBtn.text = "立即升级"
                                        toast("触发车辆升级失败，请稍后重试")
                                    }
                                }
                            }
                        }
                        ctaRow.addView(upgradeBtn, weightLp(dp(10), dp(46)))
                        host.addView(ctaRow, lp(top = dp(16)))

                        if (info.percentage in 1..99) {
                            updateProgressUi(info.percentage, "正在升级中...")
                            startSilentProgressPoll(info.upgradeTaskId)
                        }
                    } else {
                        host.addView(secondaryBtn("取消") { dialog.dismiss() }, lp(top = dp(16), height = dp(46)))
                    }
                }.onFailure { err ->
                    host.addView(errorHint("检查升级失败：${err.message.orEmpty().take(100)}"))
                    val errRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    errRow.addView(secondaryBtn("取消") { dialog.dismiss() }, weightLp(0, dp(44)))
                    errRow.addView(primaryBtn("重试") { retryAction() }, weightLp(dp(10), dp(44)))
                    host.addView(errRow, lp(top = dp(14)))
                }
            }

            fun checkNow() {
                host.removeAllViews()
                host.addView(loadingHint("正在检查车辆固件版本..."))
                scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.checkVehicleUpgrade(profile.vin, profile.accessToken)
                        }
                    }
                    if (!sheetActive) return@launch
                    renderUpgradeResult(res, ::checkNow)
                }
            }

            if (prefetchedInfo != null) {
                renderUpgradeResult(Result.success(prefetchedInfo), ::checkNow)
            } else {
                checkNow()
            }
        }
    }

    private fun underlineInput(
        hintText: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): Pair<LinearLayout, EditText> {
        val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val et = EditText(context).apply {
            hint = hintText
            setHintTextColor(Color.rgb(112, 116, 126))
            setTextColor(TEXT_PRIMARY)
            textSize = 15f
            this.inputType = inputType
            setSingleLine(true)
            background = null
            setPadding(dp(2), dp(12), dp(2), dp(10))
        }
        val linePx = dpF(0.7f).roundToInt().coerceAtLeast(1)
        val underline = View(context).apply { setBackgroundColor(Color.rgb(42, 45, 54)) }
        wrapper.addView(et, lp())
        wrapper.addView(underline, LinearLayout.LayoutParams(MATCH_PARENT, linePx))
        return wrapper to et
    }

    fun showChangeLoginPasswordModal(profile: VehicleProfile) {
        showModalDialog("修改登录密码") { box, dialog ->
            val (oldWrap, oldEt) = underlineInput(
                "原密码",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            val (newWrap, newEt) = underlineInput(
                "新密码（至少 6 位）",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            val (confirmWrap, confirmEt) = underlineInput(
                "确认新密码",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            box.addView(oldWrap, lp(top = dp(14)))
            box.addView(newWrap, lp(top = dp(8)))
            box.addView(confirmWrap, lp(top = dp(8)))

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(btns, lp(top = dp(22)))
            btns.addView(secondaryBtn("取消") { dialog.dismiss() }, weightLp(0, dp(44)))
            btns.addView(primaryBtn("保存") {
                val oldPwd = oldEt.text.toString()
                val newPwd = newEt.text.toString()
                val confirmPwd = confirmEt.text.toString()
                if (oldPwd.isBlank() || newPwd.length < 6) {
                    toast("请填写原密码及至少 6 位新密码")
                    return@primaryBtn
                }
                if (newPwd != confirmPwd) {
                    toast("两次输入的新密码不一致")
                    return@primaryBtn
                }
                dialog.dismiss()
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.changeLoginPassword(oldPwd, newPwd, profile.accessToken)
                        }
                    }.getOrDefault(false)
                    toast(if (ok) "登录密码已更新" else "修改登录密码失败，请核对原密码")
                }
            }, weightLp(dp(10), dp(44)))
        }
    }

    fun showResetPasswordBySmsModal(defaultMobile: String = "") {
        showModalDialog("短信重置登录密码") { box, dialog ->
            var smsJob: kotlinx.coroutines.Job? = null
            var submitting = false
            val mobileEt = modalInput("手机号", InputType.TYPE_CLASS_PHONE).apply {
                if (defaultMobile.isNotBlank()) setText(defaultMobile)
            }
            val codeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val codeEt = modalInput("短信验证码", InputType.TYPE_CLASS_NUMBER)
            lateinit var sendBtn: TextView
            sendBtn = secondaryBtn("获取验证码") {
                if (smsJob?.isActive == true) return@secondaryBtn
                val m = mobileEt.text.toString().trim()
                if (m.length < 11) {
                    toast("请输入正确手机号")
                    return@secondaryBtn
                }
                sendBtn.isEnabled = false
                sendBtn.alpha = 0.55f
                sendBtn.text = "发送中..."
                scope.launch {
                    val sent = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.sendSmsCode(m, type = CheryHttpClient.SMS_TYPE_FORGET_PASSWORD)
                        }
                    }.getOrDefault(false)
                    if (!dialog.isShowing) return@launch
                    if (sent) {
                        toast("验证码已发送")
                        smsJob = scope.launch {
                            for (sec in 60 downTo 1) {
                                sendBtn.isEnabled = false
                                sendBtn.alpha = 0.55f
                                sendBtn.text = "${sec}s"
                                kotlinx.coroutines.delay(1_000L)
                            }
                            sendBtn.isEnabled = true
                            sendBtn.alpha = 1f
                            sendBtn.text = "获取验证码"
                        }
                    } else {
                        sendBtn.isEnabled = true
                        sendBtn.alpha = 1f
                        sendBtn.text = "获取验证码"
                        toast("验证码发送失败")
                    }
                }
            }
            codeRow.addView(codeEt, LinearLayout.LayoutParams(0, dp(46), 1f))
            codeRow.addView(sendBtn, LinearLayout.LayoutParams(dp(104), dp(46)).apply { marginStart = dp(8) })

            val newPwdEt = modalInput(
                "新登录密码（至少 6 位）",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            box.addView(mobileEt, lp(top = dp(12), height = dp(46)))
            box.addView(codeRow, lp(top = dp(10)))
            box.addView(newPwdEt, lp(top = dp(10), height = dp(46)))

            val errorText = text("", 12.5f, ThemeTokens.DANGER).apply {
                visibility = View.GONE
            }
            box.addView(errorText, lp(top = dp(10)))

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(btns, lp(top = dp(18)))
            btns.addView(secondaryBtn("取消") { if (!submitting) dialog.dismiss() }, weightLp(0, dp(44)))
            lateinit var resetBtn: TextView
            resetBtn = primaryBtn("重置密码") {
                if (submitting) return@primaryBtn
                val m = mobileEt.text.toString().trim()
                val c = codeEt.text.toString().trim()
                val p = newPwdEt.text.toString()
                if (m.length < 11 || c.isBlank() || p.length < 6) {
                    errorText.text = "请完整填写手机号、验证码与至少 6 位新密码"
                    errorText.visibility = View.VISIBLE
                    return@primaryBtn
                }
                submitting = true
                errorText.visibility = View.GONE
                resetBtn.isEnabled = false
                resetBtn.alpha = 0.65f
                resetBtn.text = "提交中..."
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.resetLoginPasswordBySms(m, c, p)
                        }
                    }.getOrDefault(false)
                    if (!dialog.isShowing) return@launch
                    if (ok) {
                        smsJob?.cancel()
                        dialog.dismiss()
                        toast("密码已重置，请使用新密码登录")
                    } else {
                        submitting = false
                        resetBtn.isEnabled = true
                        resetBtn.alpha = 1f
                        resetBtn.text = "重置密码"
                        errorText.text = "验证码校验或密码重置失败，请核对后重试"
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
            btns.addView(resetBtn, weightLp(dp(10), dp(44)))
            dialog.setOnDismissListener {
                smsJob?.cancel()
            }
        }
    }

    fun showPinManagementModal(profile: VehicleProfile) {
        secondaryDialog?.dismiss()
        val hasSavedPin = cachedPinProvider().isNotBlank() || profile.controlPin.isNotBlank()
        val sheet = SixDigitPinSheet.showChangePinWizardSheet(
            context = context,
            requireOldPin = hasSavedPin
        ) { oldPin, newPin, showError, dismiss ->
            scope.launch {
                if (!oldPin.isNullOrBlank()) {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.changeVehicleControlPin(profile.vin, oldPin, newPin, profile.accessToken)
                        }
                    }.getOrDefault(false)
                    if (ok) {
                        onPinUpdated(newPin)
                        dismiss()
                        toast("PIN 码已更新")
                    } else {
                        showError("原 PIN 码校验失败，请重试")
                    }
                } else {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.setVehicleControlPin(profile.vin, newPin, profile.accessToken)
                        }
                    }.getOrDefault(false)
                    if (ok) {
                        onPinUpdated(newPin)
                        dismiss()
                        toast("PIN 码已保存")
                    } else {
                        showError("云端 PIN 码设置失败，请重试")
                    }
                }
            }
        }
        sheet.setOnDismissListener {
            if (secondaryDialog === sheet) secondaryDialog = null
        }
        secondaryDialog = sheet
    }

    /**
     * Resets a forgotten 6-digit vehicle control PIN using VIN/SN/ICCID + SMS (zyhl 922 + 920/921 + 124).
     */
    fun showResetPinByVehicleSheet(
        profile: VehicleProfile,
        defaultSn: String = "",
        defaultIccid: String = ""
    ) {
        showModalDialog("忘记 PIN 码（重置）") { box, dialog ->
            var smsJob: Job? = null
            var submitting = false
            box.addView(
                text("核验车辆识别码（VIN / SN / ICCID）与手机短信后，可重新设置 6 位云端控车 PIN 码。", 12.5f, TEXT_SUB).apply {
                    setLineSpacing(0f, 1.35f)
                },
                lp(top = dp(4))
            )

            val vinEt = modalInput("17 位车架号 VIN", InputType.TYPE_CLASS_TEXT).apply {
                if (profile.vin.isNotBlank()) setText(profile.vin)
            }
            val snEt = modalInput("T-Box 序列号 SN", InputType.TYPE_CLASS_TEXT).apply {
                if (defaultSn.isNotBlank()) setText(defaultSn)
            }
            val iccidEt = modalInput("车载 SIM 卡 ICCID", InputType.TYPE_CLASS_TEXT).apply {
                if (defaultIccid.isNotBlank()) setText(defaultIccid)
            }
            val mobileEt = modalInput("账号手机号", InputType.TYPE_CLASS_PHONE).apply {
                if (profile.mobileNo.isNotBlank()) setText(profile.mobileNo)
            }

            val codeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val codeEt = modalInput("短信验证码", InputType.TYPE_CLASS_NUMBER)
            lateinit var sendBtn: TextView
            sendBtn = secondaryBtn("获取验证码") {
                if (smsJob?.isActive == true) return@secondaryBtn
                val m = mobileEt.text.toString().trim()
                if (m.length < 11) {
                    toast("请输入正确手机号")
                    return@secondaryBtn
                }
                sendBtn.isEnabled = false
                sendBtn.alpha = 0.55f
                sendBtn.text = "发送中..."
                scope.launch {
                    val sent = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.sendSmsCode(m, type = CheryHttpClient.SMS_TYPE_FORGET_PASSWORD)
                        }
                    }.getOrDefault(false)
                    if (!dialog.isShowing) return@launch
                    if (sent) {
                        toast("验证码已发送")
                        smsJob = scope.launch {
                            for (sec in 60 downTo 1) {
                                sendBtn.isEnabled = false
                                sendBtn.alpha = 0.55f
                                sendBtn.text = "${sec}s"
                                delay(1_000L)
                            }
                            sendBtn.isEnabled = true
                            sendBtn.alpha = 1f
                            sendBtn.text = "获取验证码"
                        }
                    } else {
                        sendBtn.isEnabled = true
                        sendBtn.alpha = 1f
                        sendBtn.text = "获取验证码"
                        toast("验证码发送失败")
                    }
                }
            }
            codeRow.addView(codeEt, LinearLayout.LayoutParams(0, dp(46), 1f))
            codeRow.addView(sendBtn, LinearLayout.LayoutParams(dp(104), dp(46)).apply { marginStart = dp(8) })

            val newPinEt = modalInput(
                "新 6 位数字 PIN 码",
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ).apply {
                filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            }

            box.addView(vinEt, lp(top = dp(12), height = dp(44)))
            box.addView(snEt, lp(top = dp(8), height = dp(44)))
            box.addView(iccidEt, lp(top = dp(8), height = dp(44)))
            box.addView(mobileEt, lp(top = dp(8), height = dp(44)))
            box.addView(codeRow, lp(top = dp(8)))
            box.addView(newPinEt, lp(top = dp(8), height = dp(44)))

            val errorText = text("", 12.5f, ThemeTokens.DANGER).apply { visibility = View.GONE }
            box.addView(errorText, lp(top = dp(8)))

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(btns, lp(top = dp(16)))
            btns.addView(secondaryBtn("取消") { if (!submitting) dialog.dismiss() }, weightLp(0, dp(44)))
            lateinit var submitBtn: TextView
            submitBtn = primaryBtn("验证并重置") {
                if (submitting) return@primaryBtn
                val v = vinEt.text.toString().trim().uppercase(Locale.US)
                val s = snEt.text.toString().trim()
                val ic = iccidEt.text.toString().trim()
                val m = mobileEt.text.toString().trim()
                val c = codeEt.text.toString().trim()
                val np = newPinEt.text.toString().trim()
                if (v.length != 17 || s.isBlank() || ic.isBlank() || m.length < 11 || c.isBlank() || np.length != 6) {
                    errorText.text = "请完整填写 17 位 VIN、SN、ICCID、手机号、验证码及 6 位新 PIN 码"
                    errorText.visibility = View.VISIBLE
                    return@primaryBtn
                }
                submitting = true
                errorText.visibility = View.GONE
                submitBtn.isEnabled = false
                submitBtn.alpha = 0.65f
                submitBtn.text = "提交中..."
                scope.launch {
                    val stepResult = runCatching {
                        withContext(Dispatchers.IO) {
                            val idOk = httpClient.verifyVehicleIdentityForReset(v, s, ic, profile.accessToken)
                            if (!idOk) return@withContext "车辆 VIN / SN / ICCID 校验未通过"
                            val ok = httpClient.resetVehicleControlPinByVehicleInfo(
                                vin = v,
                                sn = s,
                                iccid = ic,
                                telephone = m,
                                validateCode = c,
                                newPin = np,
                                accessToken = profile.accessToken
                            )
                            if (ok) "" else "短信验证码校验或 PIN 码重置失败，请核对后重试"
                        }
                    }.getOrElse { it.message.orEmpty().ifBlank { "重置请求失败，请检查网络" } }
                    if (!dialog.isShowing) return@launch
                    if (stepResult.isEmpty()) {
                        smsJob?.cancel()
                        onPinUpdated(np)
                        dialog.dismiss()
                        toast("PIN 码已重置")
                    } else {
                        submitting = false
                        submitBtn.isEnabled = true
                        submitBtn.alpha = 1f
                        submitBtn.text = "验证并重置"
                        errorText.text = stepResult
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
            btns.addView(submitBtn, weightLp(dp(10), dp(44)))
            dialog.setOnDismissListener { smsJob?.cancel() }
        }
    }

    /**
     * Speed-limit reminder sheet (zyhl 950 + 970 instruction 1203; 60–120 km/h or off).
     */
    fun showSpeedLimitSheet(
        profile: VehicleProfile,
        currentEnabled: Boolean,
        currentSpeedKmh: Int,
        resolvePin: (String, (String) -> Unit) -> Unit,
        onUpdated: (Boolean, Int) -> Unit
    ) {
        val minSpeed = 60
        val maxSpeed = 120
        var selectedSpeed = currentSpeedKmh.coerceIn(minSpeed, maxSpeed)
        showSheet("限速提醒") { sheet, dialog ->
            sheet.addView(
                text("开启后当车辆行驶速度超过设定阈值时，系统将主动推送超速提醒（60–120 km/h）。", 13f, TEXT_SUB).apply {
                    setLineSpacing(0f, 1.4f)
                },
                lp(top = dp(6))
            )

            val valueTv = text("$selectedSpeed km/h", 30f, TEXT_PRIMARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            }
            sheet.addView(valueTv, lp(top = dp(20), bottom = dp(10)))

            val seekBar = SeekBar(context).apply {
                max = maxSpeed - minSpeed
                progress = selectedSpeed - minSpeed
                progressTintList = ColorStateList.valueOf(ThemeTokens.ACCENT_LIGHT)
                thumbTintList = ColorStateList.valueOf(ThemeTokens.TEXT_PRIMARY)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        selectedSpeed = minSpeed + progress
                        valueTv.text = "$selectedSpeed km/h"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            }
            sheet.addView(seekBar, lp(top = dp(4), bottom = dp(4)))

            val boundsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            boundsRow.addView(text("60 km/h", 12f, TEXT_SUB), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            boundsRow.addView(text("120 km/h", 12f, TEXT_SUB).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            sheet.addView(boundsRow, lp(bottom = dp(18)))

            fun submitSpeed(targetEnabled: Boolean, targetSpeed: Int) {
                val desc = if (targetEnabled) "开启限速提醒（${targetSpeed} km/h）" else "关闭限速提醒"
                resolvePin(desc) { verifiedPin ->
                    scope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.setSpeedLimitReminder(
                                    vin = profile.vin,
                                    speedKmh = if (targetEnabled) targetSpeed else 0,
                                    pin = verifiedPin,
                                    accessToken = profile.accessToken
                                )
                            }
                        }.getOrDefault(false)
                        if (ok) {
                            onUpdated(targetEnabled, if (targetEnabled) targetSpeed else 0)
                            if (dialog.isShowing) dialog.dismiss()
                            toast(if (targetEnabled) "已设置限速提醒：$targetSpeed km/h" else "已关闭限速提醒")
                        } else {
                            toast("限速提醒设置失败，请稍后重试")
                        }
                    }
                }
            }

            sheet.addView(primaryBtn("保存并开启限速提醒") {
                submitSpeed(true, selectedSpeed)
            }, lp(height = dp(48)))

            if (currentEnabled) {
                sheet.addView(secondaryBtn("关闭限速提醒") {
                    submitSpeed(false, 0)
                }, lp(top = dp(10), height = dp(46)))
            }
        }
    }

    /**
     * EV / PHEV scheduled charging sheet (zyhl 974 + 970 instruction 1110).
     */
    fun showChargingScheduleSheet(
        profile: VehicleProfile,
        telemetry: VehicleTelemetry?,
        powertrain: VehiclePowertrain,
        resolvePin: (String, (String) -> Unit) -> Unit
    ) {
        showSheet("充电与预约充电") { sheet, dialog ->
            // Live energy / charging summary card
            val statusCard = cardBox().apply {
                val batStr = telemetry?.batteryPercent?.let { "${it.toInt()}%" } ?: "--%"
                val rangeStr = when {
                    telemetry?.electricRangeKm != null -> "纯电续航 ${telemetry.electricRangeKm!!.toInt()} km"
                    telemetry?.remainingRangeKm != null -> "剩余续航 ${telemetry.remainingRangeKm!!.toInt()} km"
                    else -> "续航待同步"
                }
                val stateLabel = when {
                    telemetry?.isCharging == true -> "正在充电"
                    telemetry?.isChargingGunConnected == true -> "已插充电枪（待机）"
                    else -> "未连接充电枪"
                }
                addView(text(stateLabel, 15.5f, if (telemetry?.isCharging == true) ThemeTokens.ACCENT_LIGHT else TEXT_PRIMARY, Typeface.BOLD))
                val detailParts = mutableListOf(powertrain.label, "电量 $batStr", rangeStr)
                telemetry?.remainingCharge?.takeIf { it.isNotBlank() && it != "0" }?.let {
                    detailParts += "剩余时间 $it"
                }
                addView(text(detailParts.joinToString(" · "), 12.5f, TEXT_SUB), lp(top = dp(6)))
            }
            sheet.addView(statusCard, lp(top = dp(8)))

            val scheduleHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            sheet.addView(scheduleHost, lp(top = dp(14)))
            scheduleHost.addView(text("正在读取预约充电计划...", 13f, TEXT_SUB).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(20))
            })

            var currentSchedules: List<ChargingScheduleItem> = emptyList()
            var masterEnabled = telemetry?.chargeAppintStatus == "1"

            lateinit var renderSchedules: () -> Unit

            fun persistSchedules(nextMaster: Boolean, nextList: List<ChargingScheduleItem>, actionDesc: String) {
                resolvePin(actionDesc) { verifiedPin ->
                    scope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.saveChargingSchedules(
                                    vin = profile.vin,
                                    masterEnabled = nextMaster,
                                    schedules = nextList,
                                    pin = verifiedPin,
                                    accessToken = profile.accessToken
                                )
                            }
                        }.getOrDefault(false)
                        if (ok) {
                            masterEnabled = nextMaster
                            currentSchedules = nextList
                            if (dialog.isShowing) renderSchedules()
                            toast("预约充电设置已下发")
                        } else {
                            toast("预约充电同步失败，请稍后重试")
                        }
                    }
                }
            }

            fun openEditScheduleDialog(existingIndex: Int?) {
                val existing = existingIndex?.let { currentSchedules.getOrNull(it) }
                var startTimeStr = existing?.startTime?.takeIf { it.contains(":") } ?: "23:00"
                var durationHours = existing?.durationHours ?: 4
                val selectedDays = (existing?.days ?: listOf(1, 2, 3, 4, 5, 6, 7)).toMutableSet()
                val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

                showModalDialog(if (existing == null) "新增预约充电" else "编辑预约充电") { box, mDialog ->
                    val (timeField, getTime) = AppComponents.createDateTimePickerField(
                        context = context,
                        hint = "选择开始时间",
                        initialValue = startTimeStr,
                        mode = DateTimeMode.TIME,
                        title = "充电开始时间"
                    )
                    box.addView(text("开始时间", 12.5f, TEXT_SUB), lp(top = dp(8)))
                    box.addView(timeField, lp(top = dp(4)))

                    val durLabel = text("充电时长：$durationHours 小时", 13.5f, TEXT_PRIMARY, Typeface.BOLD)
                    box.addView(durLabel, lp(top = dp(14)))
                    val durSeek = SeekBar(context).apply {
                        max = 11
                        progress = (durationHours - 1).coerceIn(0, 11)
                        progressTintList = ColorStateList.valueOf(ThemeTokens.ACCENT_LIGHT)
                        thumbTintList = ColorStateList.valueOf(ThemeTokens.TEXT_PRIMARY)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                                durationHours = progress + 1
                                durLabel.text = "充电时长：$durationHours 小时"
                            }
                            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                        })
                    }
                    box.addView(durSeek, lp(top = dp(6)))

                    box.addView(text("重复周期", 12.5f, TEXT_SUB), lp(top = dp(14)))
                    val daysRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    dayLabels.forEachIndexed { idx, lbl ->
                        val dayNum = idx + 1
                        val pill = text(lbl, 12.5f, TEXT_PRIMARY, Typeface.BOLD).apply {
                            gravity = Gravity.CENTER
                            isClickable = true
                            isFocusable = true
                            fun refreshPill() {
                                val active = dayNum in selectedDays
                                background = rounded(
                                    if (active) ThemeTokens.ACCENT_BLUE else ThemeTokens.BTN_SECONDARY,
                                    8f
                                )
                                setTextColor(if (active) ThemeTokens.TEXT_PRIMARY else TEXT_SUB)
                            }
                            refreshPill()
                            setOnClickListener {
                                if (dayNum in selectedDays && selectedDays.size > 1) {
                                    selectedDays.remove(dayNum)
                                } else {
                                    selectedDays.add(dayNum)
                                }
                                refreshPill()
                            }
                        }
                        daysRow.addView(pill, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                            if (idx > 0) marginStart = dp(4)
                        })
                    }
                    box.addView(daysRow, lp(top = dp(8)))

                    val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    box.addView(btns, lp(top = dp(18)))
                    btns.addView(secondaryBtn("取消") { mDialog.dismiss() }, weightLp(0, dp(44)))
                    btns.addView(primaryBtn("保存") {
                        val picked = getTime().ifBlank { startTimeStr }
                        val newItem = ChargingScheduleItem(
                            chargeId = existing?.chargeId.orEmpty(),
                            startTime = picked,
                            durationHours = durationHours,
                            days = selectedDays.sorted(),
                            enabled = true
                        )
                        val updatedList = currentSchedules.toMutableList()
                        if (existingIndex != null && existingIndex in updatedList.indices) {
                            updatedList[existingIndex] = newItem
                        } else {
                            updatedList.add(newItem)
                        }
                        mDialog.dismiss()
                        persistSchedules(true, updatedList, "保存预约充电计划")
                    }, weightLp(dp(10), dp(44)))
                }
            }

            renderSchedules = {
                scheduleHost.removeAllViews()
                val masterRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(10))
                }
                masterRow.addView(
                    text("预约充电总开关", 14.5f, TEXT_PRIMARY, Typeface.BOLD),
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                )
                val masterSwitch = ToggleSwitch(context).apply {
                    setChecked(masterEnabled, animate = false)
                    onToggleAttempt = { target ->
                        persistSchedules(target, currentSchedules, if (target) "开启预约充电" else "关闭预约充电")
                    }
                }
                masterRow.addView(masterSwitch)
                scheduleHost.addView(masterRow)

                if (currentSchedules.isEmpty()) {
                    scheduleHost.addView(text("暂无预约充电计划，点击下方新增低谷电定时计划", 12.5f, TEXT_SUB).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(14), 0, dp(14))
                    })
                } else {
                    val dayNames = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
                    currentSchedules.forEachIndexed { idx, item ->
                        val rowCard = cardBox().apply {
                            val top = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            top.addView(
                                text("${item.startTime.ifBlank { "--:--" }} · ${item.durationHours}小时", 15f, TEXT_PRIMARY, Typeface.BOLD),
                                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                            )
                            val sw = ToggleSwitch(context).apply {
                                setChecked(item.enabled, animate = false)
                                onToggleAttempt = { target ->
                                    val updated = currentSchedules.mapIndexed { i, it ->
                                        if (i == idx) it.copy(enabled = target) else it
                                    }
                                    persistSchedules(updated.any { it.enabled }, updated, "更新预约充电计划")
                                }
                            }
                            top.addView(sw)
                            addView(top)
                            val repeatText = if (item.days.size == 7) {
                                "每天"
                            } else if (item.days.isEmpty()) {
                                "单次"
                            } else {
                                item.days.mapNotNull { dayNames.getOrNull(it) }.joinToString("、")
                            }
                            addView(text(repeatText, 12.5f, TEXT_SUB), lp(top = dp(4)))
                            setOnClickListener { openEditScheduleDialog(idx) }
                        }
                        scheduleHost.addView(rowCard, lp(top = dp(8)))
                    }
                }

                scheduleHost.addView(primaryBtn("新增预约充电") {
                    openEditScheduleDialog(null)
                }, lp(top = dp(14), height = dp(46)))
            }

            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchChargingSchedules(profile.vin, profile.accessToken)
                    }
                }.getOrNull()
                if (!dialog.isShowing) return@launch
                if (res != null) {
                    masterEnabled = res.masterEnabled
                    currentSchedules = res.items
                }
                renderSchedules()
            }
        }
    }

    /**
     * Departure / travel pre-conditioning schedule sheet (zyhl 143 + 970 instruction 1111; ICE/PHEV/EV).
     */
    fun showTravelScheduleSheet(
        profile: VehicleProfile,
        airType: Int = 1,
        resolvePin: (String, (String) -> Unit) -> Unit
    ) {
        showSheet("预约出行") { sheet, dialog ->
            sheet.addView(
                text("设定出发时刻后，车辆将在出行前自动提前调节座舱温度（油/混/电车型均支持）。", 13f, TEXT_SUB).apply {
                    setLineSpacing(0f, 1.4f)
                },
                lp(top = dp(6))
            )

            val host = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            sheet.addView(host, lp(top = dp(14)))
            host.addView(text("正在读取预约出行配置...", 13f, TEXT_SUB).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(20))
            })

            fun render(enabled: Boolean, startTime: String) {
                host.removeAllViews()
                var isOn = enabled
                val initialTime = startTime.takeIf { it.contains(":") } ?: "08:00"
                val (timeField, getTime) = AppComponents.createDateTimePickerField(
                    context = context,
                    hint = "选择出发时间",
                    initialValue = initialTime,
                    mode = DateTimeMode.TIME,
                    title = "预约出发时间"
                )
                val swRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(12))
                }
                swRow.addView(text("启用预约出行", 15f, TEXT_PRIMARY, Typeface.BOLD), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val sw = ToggleSwitch(context).apply {
                    setChecked(isOn, animate = false)
                    onToggleAttempt = { target ->
                        isOn = target
                        setChecked(target, animate = true)
                    }
                }
                swRow.addView(sw)
                host.addView(swRow)
                host.addView(text("出发时间", 12.5f, TEXT_SUB))
                host.addView(timeField, lp(top = dp(6), bottom = dp(18)))

                host.addView(primaryBtn("保存预约出行") {
                    val pickedTime = getTime().ifBlank { initialTime }
                    val desc = if (isOn) "开启预约出行（$pickedTime）" else "关闭预约出行"
                    resolvePin(desc) { verifiedPin ->
                        scope.launch {
                            val ok = runCatching {
                                withContext(Dispatchers.IO) {
                                    httpClient.saveTravelSchedule(
                                        vin = profile.vin,
                                        enabled = isOn,
                                        startTimeHhMm = pickedTime,
                                        airType = airType,
                                        pin = verifiedPin,
                                        accessToken = profile.accessToken
                                    )
                                }
                            }.getOrDefault(false)
                            if (ok) {
                                if (dialog.isShowing) dialog.dismiss()
                                toast(if (isOn) "预约出行已设置：$pickedTime" else "已关闭预约出行")
                            } else {
                                toast("预约出行同步失败，请稍后重试")
                            }
                        }
                    }
                }, lp(height = dp(48)))
            }

            scope.launch {
                val summary = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchTravelSchedules(profile.vin, profile.accessToken)
                    }
                }.getOrNull()
                if (!dialog.isShowing) return@launch
                val first = summary?.items?.firstOrNull()
                render(
                    enabled = summary?.masterEnabled ?: (first?.enabled == true),
                    startTime = first?.startTime.orEmpty().ifBlank { "08:00" }
                )
            }
        }
    }

    // =========================================================================
    // Shared App Dark Sheet & Component Primitives
    // =========================================================================

    private fun showSheet(
        title: String,
        onDismiss: (() -> Unit)? = null,
        build: (LinearLayout, Dialog) -> Unit
    ) {
        activeDialog?.dismiss()
        var depthRestored = false
        lateinit var outer: LinearLayout
        val dialog = object : Dialog(context, R.style.Theme_CarPlus_BottomSheetDialog) {
            fun restoreDepthIfNeeded() {
                if (activeDialog === this && !depthRestored) {
                    depthRestored = true
                    onDepthRecede(false)
                }
            }

            fun dismissImmediately() {
                outer.animate().cancel()
                restoreDepthIfNeeded()
                runCatching { if (isShowing) super.dismiss() }
            }

            override fun dismiss() {
                if (!isShowing || MotionSpec.isBypassingSheetDismiss(this)) {
                    runCatching { super.dismiss() }
                    return
                }
                MotionSpec.dismissSheetAnimated(outer, this) {
                    restoreDepthIfNeeded()
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = topRounded(CARD_MODAL, 20f)
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        val handle = View(context).apply { background = rounded(Color.rgb(72, 74, 82), 2f) }
        outer.addView(handle, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        outer.addView(header)
        header.addView(text(title, 20f, TEXT_PRIMARY, Typeface.BOLD), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.78f).roundToInt()
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        build(body, dialog)

        AppSystemInsets.installBottomSheetContent(dialog, outer, dimAmount = 0.68f)
        MotionSpec.attachSheetSwipeToDismiss(outer, scroll, dialog) {
            dialog.restoreDepthIfNeeded()
        }
        var clampedToMax = false
        outer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val measuredHeight = bottom - top
            if (measuredHeight > maxHeight && !clampedToMax) {
                clampedToMax = true
                val lp = outer.layoutParams
                if (lp != null) {
                    lp.height = maxHeight
                    outer.layoutParams = lp
                }
            }
        }
        dialog.setOnDismissListener {
            onDismiss?.invoke()
            if (activeDialog === dialog) {
                activeDialog = null
                activeImmediateDismiss = null
                if (!depthRestored) {
                    depthRestored = true
                    onDepthRecede(false)
                }
            }
        }
        activeDialog = dialog
        activeImmediateDismiss = { dialog.dismissImmediately() }
        dialog.show()
        onDepthRecede(true)
        MotionSpec.animateSheetEnter(outer)
    }

    private fun showModalDialog(title: String, build: (LinearLayout, Dialog) -> Unit) {
        secondaryDialog?.dismiss()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_MODAL, 14f, Color.rgb(52, 54, 62), 0.7f)
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        box.addView(text(title, 18f, TEXT_PRIMARY, Typeface.BOLD))
        build(box, dialog)
        scroll.addView(box, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        dialog.setContentView(scroll)
        dialog.window?.apply {
            setWindowAnimations(0)
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.75f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout((context.resources.displayMetrics.widthPixels * 0.90f).roundToInt(), WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.setOnDismissListener { if (secondaryDialog === dialog) secondaryDialog = null }
        secondaryDialog = dialog
        dialog.show()
        MotionSpec.animateModalEnter(box)
    }

    private fun segmentedBar(
        labels: List<String>,
        initial: Int,
        onSelect: (Int) -> Unit
    ): FrameLayout = MotionSpec.createSlidingSegmentedBar(
        context = context,
        labels = labels,
        initial = initial,
        bgColor = INPUT_BG,
        pillColor = BTN_SECONDARY,
        activeTextColor = Color.WHITE,
        inactiveTextColor = TEXT_SUB,
        onSelect = onSelect
    )

    private fun animateTabContent(container: View) {
        container.animate().cancel()
        container.alpha = 0f
        container.translationY = dpF(8f)
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .start()
    }

    private fun messageCard(item: ZyhlMessageItem, onClick: () -> Unit): LinearLayout = cardBox().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)
        val head = item.title.ifBlank { item.typeDesc.ifBlank { "车辆提醒" } }
        addView(text(head, 14.5f, TEXT_PRIMARY, Typeface.BOLD))
        val preview = item.description.ifBlank { item.content }
        if (preview.isNotBlank() && preview != head) {
            addView(text(preview.take(100), 13f, TEXT_BODY).apply { setLineSpacing(0f, 1.35f) }, lp(top = dp(4)))
        }
        val meta = listOf(item.typeDesc, item.messageTime).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) addView(text(meta, 11.5f, TEXT_SUB), lp(top = dp(6)))
    }

    private fun cardBox(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD_INNER, 10f)
        setPadding(dp(14), dp(13), dp(14), dp(13))
    }

    private fun kvRow(key: String, value: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(text(key, 13.5f, TEXT_SUB), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(text(value, 13.5f, TEXT_PRIMARY, Typeface.BOLD))
    }

    private fun loadingHint(msg: String): TextView = text(msg, 13.5f, TEXT_SUB).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, dp(24))
    }

    private fun emptyHint(msg: String): TextView = text(msg, 13.5f, TEXT_SUB).apply {
        gravity = Gravity.CENTER
        background = rounded(CARD_INNER, 10f)
        setPadding(dp(16), dp(22), dp(16), dp(22))
    }

    private fun errorHint(msg: String): TextView = text(msg, 13f, ERROR_RED).apply {
        background = rounded(CARD_INNER, 10f)
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun primaryBtn(label: String, onClick: () -> Unit): TextView =
        AppComponents.createActionButton(context, label, ButtonStyle.PRIMARY, onClick)

    private fun secondaryBtn(label: String, onClick: () -> Unit): TextView =
        AppComponents.createActionButton(context, label, ButtonStyle.SECONDARY, onClick)

    private fun smallBtn(label: String, danger: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 13f, if (danger) ERROR_RED else TEXT_PRIMARY, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(BTN_SECONDARY, 6f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            MotionSpec.attachPressPhysics(this, pressedScale = 0.95f)
        }

    private fun modalInput(hintText: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(context).apply {
            hint = hintText
            setHintTextColor(Color.rgb(112, 116, 126))
            setTextColor(TEXT_PRIMARY)
            textSize = 14.5f
            this.inputType = inputType
            setSingleLine(true)
            background = rounded(INPUT_BG, 6f, Color.rgb(54, 56, 66), 0.7f)
            setPadding(dp(14), 0, dp(14), 0)
        }

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

    private fun topRounded(fillColor: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            val r = dpF(radiusDp)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

    private fun lp(
        top: Int = 0,
        bottom: Int = 0,
        height: Int = WRAP_CONTENT
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(MATCH_PARENT, height).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun weightLp(startMargin: Int, height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, height, 1f).apply { marginStart = startMargin }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun dpF(value: Float): Float = value * context.resources.displayMetrics.density

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun defaultAuthWindow(): Pair<String, String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val cal = Calendar.getInstance()
        val start = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, 7)
        val end = fmt.format(cal.time)
        return start to end
    }

    private fun maskedVin(vin: String): String {
        val v = vin.trim().uppercase(Locale.US)
        return if (v.length >= 10) v.take(3) + "***" + v.takeLast(4) else v.ifBlank { "--" }
    }

    private fun maskMobile(mobile: String): String =
        if (mobile.length >= 7) mobile.take(3) + "****" + mobile.takeLast(4) else mobile

    private fun String.withUnit(unit: String): String =
        if (this == "--" || endsWith(unit, ignoreCase = true)) this else "$this $unit"

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        private val BG_HOME = ThemeTokens.BG_CANVAS
        private val MAP_BG = Color.rgb(20, 22, 27)
        private val CARD_MODAL = ThemeTokens.BG_SURFACE
        private val CARD_INNER = Color.rgb(36, 37, 42)
        private val BTN_SECONDARY = ThemeTokens.BTN_SECONDARY
        private val INPUT_BG = Color.rgb(34, 35, 38)
        private val PRIMARY_BLUE = ThemeTokens.ACCENT_BLUE
        private val ACCENT_BLUE = ThemeTokens.ACCENT_LIGHT
        private val TEXT_PRIMARY = ThemeTokens.TEXT_PRIMARY
        private val TEXT_BODY = ThemeTokens.TEXT_BODY
        private val TEXT_SUB = ThemeTokens.TEXT_SUB
        private val ERROR_RED = ThemeTokens.DANGER
    }
}
