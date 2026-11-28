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
import com.chery.carplus.cloud.PoiSearchResult
import com.chery.carplus.cloud.TripDayGroup
import com.chery.carplus.cloud.TripRecordItem
import com.chery.carplus.cloud.VehicleAuthorizationItem
import com.chery.carplus.cloud.VehiclePowertrain
import com.chery.carplus.cloud.VehicleSummary
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.cloud.VehicleUpgradeInfo
import com.chery.carplus.cloud.VehicleUpgradeStatus
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
    private val onPinUpdated: (vin: String, newPin: String) -> Unit,
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
    private val activeMapWebViews = linkedSetOf<WebView>()
    private val geofenceAddressCache = mutableMapOf<String, String>()

    fun onHostPause() {
        activeMapWebViews.toList().forEach { wv ->
            runCatching {
                wv.evaluateJavascript("window.CarMap && window.CarMap.setPaused && window.CarMap.setPaused(true);", null)
                wv.onPause()
            }
        }
    }

    fun onHostResume() {
        activeMapWebViews.toList().forEach { wv ->
            runCatching {
                wv.onResume()
                wv.evaluateJavascript("window.CarMap && window.CarMap.setPaused && window.CarMap.setPaused(false);", null)
            }
        }
    }

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
        onVehicleBound: (vin: String, controlPin: String) -> Unit
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
        AppSystemInsets.applyBottomNavPadding(scroll)
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
            hint = "车辆序列号 (SN)",
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
                sn.isBlank() -> "请输入车辆序列号 (SN)"
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
                        AppHaptics.perform(submitBtn, HapticLevel.MEDIUM_CONFIRM)
                        toast("车辆绑定成功")
                        onVehicleBound(vin, pin)
                    } else {
                        errorTv.text = result.message.ifBlank { "绑定失败，请核对车架号、SN 与 ICCID" }
                        errorTv.visibility = View.VISIBLE
                        AppHaptics.perform(submitBtn, HapticLevel.ERROR_REJECT)
                        MotionSpec.shakeError(errorTv)
                    }
                }.onFailure { err ->
                    if (err is CherySessionExpiredException) return@onFailure
                    errorTv.text = friendlyErrorMessage(err, "绑定失败，请稍后重试")
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
        AppSystemInsets.applyBottomNavPadding(scroll)
        val contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        scroll.addView(contentHost, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        loadMessagesTab(contentHost, profile)
        return root
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
            val loadedCount = res.getOrNull()?.size ?: 0
            container.addView(primaryBtn("+ 新增电子围栏") {
                if (loadedCount >= MAX_GEOFENCE_COUNT) {
                    toast("最多可设置 $MAX_GEOFENCE_COUNT 个电子围栏，请先删除不需要的围栏")
                } else {
                    openGeofenceEditor(existing = null, currentFenceCount = loadedCount, onReload = onReload)
                }
            }, lp(bottom = dp(12), height = dp(46)))

            res.onSuccess { fences ->
                if (fences.isEmpty()) {
                    container.addView(emptyHint("暂未设置电子围栏"))
                } else {
                    fences.forEach { fence ->
                        container.addView(geofenceCard(fence, fences.size, profile, onReload), lp(bottom = dp(10)))
                    }
                }
            }.onFailure { err ->
                if (err !is CherySessionExpiredException) {
                    container.addView(errorHint("电子围栏查询失败：${err.message.orEmpty().take(90)}"))
                }
            }
        }
    }

    private fun geofenceCard(
        fence: GeofenceItem,
        totalFenceCount: Int,
        profile: VehicleProfile,
        onReload: () -> Unit
    ): LinearLayout = cardBox().apply {
        val title = fence.name.ifBlank { "围栏 #${fence.keyId}" }
        val fenceEnabled = fence.status == "1"
        isClickable = true
        isFocusable = true
        MotionSpec.attachPressPhysics(this, pressedScale = 0.985f)
        setOnClickListener { openGeofenceEditor(existing = fence, currentFenceCount = totalFenceCount, onReload = onReload) }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            text(title, 15f, TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        var togglingFence = false
        lateinit var toggleSwitch: ToggleSwitch
        toggleSwitch = ToggleSwitch(context).apply {
            setChecked(fenceEnabled, animate = false)
            onToggleAttempt = { targetChecked ->
                if (!togglingFence) {
                    togglingFence = true
                    toggleSwitch.isEnabled = false
                    toggleSwitch.alpha = 0.6f
                    setChecked(targetChecked, animate = true)
                    scope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.deleteOrToggleGeofence(
                                    keyId = fence.keyId,
                                    type = if (targetChecked) "1" else "0",
                                    accessToken = profile.accessToken
                                )
                            }
                        }
                        togglingFence = false
                        toggleSwitch.isEnabled = true
                        toggleSwitch.alpha = 1f
                        res.onSuccess { ok ->
                            if (ok) {
                                toast(if (targetChecked) "围栏已启用" else "围栏已停用")
                                onReload()
                            } else {
                                setChecked(!targetChecked, animate = true)
                                toast("围栏状态更新失败")
                            }
                        }.onFailure { err ->
                            setChecked(!targetChecked, animate = true)
                            if (err !is CherySessionExpiredException) {
                                toast(friendlyErrorMessage(err, "围栏状态更新失败"))
                            }
                        }
                    }
                }
            }
        }
        headerRow.addView(toggleSwitch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(headerRow)

        val alertLabel = if (fence.type.trim() == "0") "进入提醒" else "驶出提醒"
        val radiusLabel = fence.radiusMeters.takeIf { it.isNotBlank() }?.let { "半径 ${it}m" }
        val coordKey = if (fence.lat.isNotBlank() && fence.lon.isNotBlank()) "${fence.lat},${fence.lon}" else ""
        val cachedAddr = coordKey.takeIf { it.isNotBlank() }?.let { geofenceAddressCache[it] }.orEmpty()
        val detailTv = text(
            listOfNotNull(radiusLabel, alertLabel, cachedAddr.takeIf { it.isNotBlank() }).joinToString(" · "),
            12.5f,
            TEXT_SUB
        )
        addView(detailTv, lp(top = dp(4)))

        if (cachedAddr.isBlank() && coordKey.isNotBlank()) {
            val fLat = fence.lat.toDoubleOrNull()
            val fLon = fence.lon.toDoubleOrNull()
            if (fLat != null && fLon != null && (fLat != 0.0 || fLon != 0.0)) {
                scope.launch {
                    val resolved = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.reverseGeocodeApproxAddress(fLat, fLon)
                        }
                    }.getOrNull().orEmpty().trim()
                    if (resolved.isNotBlank()) {
                        geofenceAddressCache[coordKey] = resolved
                        detailTv.text = listOfNotNull(radiusLabel, alertLabel, resolved).joinToString(" · ")
                    }
                }
            }
        }

        var deletingFence = false
        lateinit var deleteBtn: TextView
        deleteBtn = AppComponents.createActionButton(
            context = context,
            label = "删除围栏",
            style = ButtonStyle.DANGER_TEXT
        ) {
            if (deletingFence) return@createActionButton
            AppComponents.showConfirmDialog(
                context = context,
                title = "删除电子围栏",
                message = "确认删除「$title」？删除后将不再触发该区域出入提醒。",
                confirmText = "删除",
                cancelText = "取消",
                dangerConfirm = true
            ) {
                if (deletingFence) return@showConfirmDialog
                deletingFence = true
                deleteBtn.isEnabled = false
                deleteBtn.alpha = 0.55f
                deleteBtn.text = "正在删除..."
                scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.deleteOrToggleGeofence(
                                keyId = fence.keyId,
                                type = "2",
                                accessToken = profile.accessToken
                            )
                        }
                    }
                    deletingFence = false
                    deleteBtn.isEnabled = true
                    deleteBtn.alpha = 1f
                    deleteBtn.text = "删除围栏"
                    res.onSuccess { ok ->
                        toast(if (ok) "围栏已删除" else "删除围栏失败")
                        if (ok) onReload()
                    }.onFailure { err ->
                        if (err !is CherySessionExpiredException) {
                            toast(friendlyErrorMessage(err, "删除围栏失败"))
                        }
                    }
                }
            }
        }
        addView(deleteBtn, lp(top = dp(8), height = dp(38)))
    }

    private fun openGeofenceEditor(
        existing: GeofenceItem?,
        currentFenceCount: Int = 0,
        onReload: () -> Unit
    ) {
        val hadActiveSheet = activeDialog?.isShowing == true
        activeImmediateDismiss?.invoke() ?: activeDialog?.dismiss()
        val pushOverlay = onOpenOverlaySubpage
        if (pushOverlay != null) {
            val (editorView, destroyCb) = buildGeofenceEditorSubpageView(
                existing = existing,
                currentFenceCount = currentFenceCount,
                onBack = { onPopOverlaySubpage?.invoke() },
                onSaved = {
                    onPopOverlaySubpage?.invoke()
                    if (hadActiveSheet) {
                        showGeofenceSheet()
                    } else {
                        onReload()
                    }
                }
            )
            pushOverlay(editorView, destroyCb)
            return
        }

        // Fallback full-screen dark modal Dialog when overlay stack callback is not wired
        if (!MotionSpec.isHostActivityAlive(context)) return
        secondaryDialog?.dismiss()
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val (editorView, destroyCb) = buildGeofenceEditorSubpageView(
            existing = existing,
            currentFenceCount = currentFenceCount,
            onBack = { dialog.dismiss() },
            onSaved = {
                dialog.dismiss()
                if (hadActiveSheet) {
                    showGeofenceSheet()
                } else {
                    onReload()
                }
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
        if (!MotionSpec.isHostActivityAlive(context)) {
            destroyCb()
            return
        }
        secondaryDialog = dialog
        runCatching { dialog.show() }.onFailure {
            destroyCb()
            if (secondaryDialog === dialog) secondaryDialog = null
        }
    }

    /**
     * Full-screen Visual Map Circle Geofence Editor (Level 3 subpage).
     * Panning the map moves the geofence center; pinch/zoom or bottom SeekBar adjusts radius (100m-10km) in real time.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun buildGeofenceEditorSubpageView(
        existing: GeofenceItem? = null,
        currentFenceCount: Int = 0,
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
            val preservedType = existing?.type?.trim()?.takeIf { it == "0" || it == "1" } ?: "1"
            val preservedShape = existing?.shape?.trim()?.takeIf { it == "1" || it == "2" } ?: "1"
            val wasDisabled = existing != null && existing.status.trim() == "0"
            saving = true
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        var deleteWarning: String? = null
                        val existingId = existing?.keyId.orEmpty().trim()
                        val savedOk = if (existing != null && existingId.isNotBlank() && currentFenceCount >= MAX_GEOFENCE_COUNT) {
                            // Server enforces a 5-fence cap (zyhl MapFenceListActivity:200); free slot first
                            val deletedOld = httpClient.deleteOrToggleGeofence(
                                keyId = existingId,
                                type = "2",
                                accessToken = profile.accessToken
                            )
                            if (!deletedOld) {
                                return@withContext false to "已达围栏上限且旧围栏移除失败，请稍后重试"
                            }
                            val createRes = runCatching {
                                httpClient.saveGeofence(
                                    vin = profile.vin,
                                    name = name,
                                    lat = latStr,
                                    lon = lonStr,
                                    radiusMeters = radiusStr,
                                    type = preservedType,
                                    shape = preservedShape,
                                    accessToken = profile.accessToken
                                )
                            }
                            createRes.exceptionOrNull()?.let { err ->
                                if (err is kotlinx.coroutines.CancellationException || err is CherySessionExpiredException) {
                                    throw err
                                }
                            }
                            val created = createRes.getOrDefault(false)
                            if (!created) {
                                // Best-effort rollback of original fence (preserving disabled state) on false or thrown error
                                val restoredName = existing.name.ifBlank { "安全围栏" }
                                val restoredOk = runCatching {
                                    httpClient.saveGeofence(
                                        vin = profile.vin,
                                        name = restoredName,
                                        lat = existing.lat,
                                        lon = existing.lon,
                                        radiusMeters = existing.radiusMeters.ifBlank { radiusStr },
                                        type = preservedType,
                                        shape = preservedShape,
                                        accessToken = profile.accessToken
                                    )
                                }.getOrDefault(false)
                                if (restoredOk && wasDisabled) {
                                    runCatching {
                                        val restoredList = httpClient.fetchGeofences(profile.vin, profile.accessToken)
                                        val restored = restoredList.lastOrNull { item ->
                                            item.keyId.isNotBlank() &&
                                                item.keyId != existingId &&
                                                item.name == restoredName
                                        }
                                        if (restored != null && restored.status != "0") {
                                            httpClient.deleteOrToggleGeofence(
                                                keyId = restored.keyId,
                                                type = "0",
                                                accessToken = profile.accessToken
                                            )
                                        }
                                    }
                                }
                                return@withContext false to "更新围栏失败，已尝试恢复原围栏"
                            }
                            true
                        } else {
                            val created = httpClient.saveGeofence(
                                vin = profile.vin,
                                name = name,
                                lat = latStr,
                                lon = lonStr,
                                radiusMeters = radiusStr,
                                type = preservedType,
                                shape = preservedShape,
                                accessToken = profile.accessToken
                            )
                            if (created && existing != null && existingId.isNotBlank()) {
                                val deletedOld = runCatching {
                                    httpClient.deleteOrToggleGeofence(
                                        keyId = existingId,
                                        type = "2",
                                        accessToken = profile.accessToken
                                    )
                                }.getOrDefault(false)
                                if (!deletedOld) {
                                    deleteWarning = "新围栏已保存，但旧围栏未能自动删除，请在列表中手动清理"
                                }
                            }
                            created
                        }

                        if (savedOk && wasDisabled) {
                            runCatching {
                                val updatedList = httpClient.fetchGeofences(profile.vin, profile.accessToken)
                                val newest = updatedList.lastOrNull { item ->
                                    item.keyId.isNotBlank() &&
                                        item.keyId != existingId &&
                                        item.name == name
                                }
                                if (newest != null && newest.status != "0") {
                                    httpClient.deleteOrToggleGeofence(
                                        keyId = newest.keyId,
                                        type = "0",
                                        accessToken = profile.accessToken
                                    )
                                }
                            }
                        }
                        savedOk to deleteWarning
                    }
                }
                saving = false
                if (mapDestroyed) return@launch
                res.onSuccess { (ok, msg) ->
                    if (ok) {
                        toast(msg ?: "电子围栏已保存")
                        onSaved()
                    } else {
                        toast(msg ?: "保存围栏失败，请重试")
                    }
                }.onFailure { err ->
                    if (err !is CherySessionExpiredException) {
                        toast(friendlyErrorMessage(err, "保存围栏失败，请重试"))
                    }
                }
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
        activeMapWebViews.add(wv)
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
        AppSystemInsets.applyBottomNavPadding(controlPanel)
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
                    activeMapWebViews.remove(webView)
                    webView.removeJavascriptInterface("CarMapBridge")
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
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
        var pickedPoiLat: Double? = null
        var pickedPoiLon: Double? = null
        var pickedPoiName = ""
        var pickedPoiAddress = ""
        var pickedBannerView: LinearLayout? = null
        var pickedTitleView: TextView? = null
        var pickedSubView: TextView? = null
        var searchDestPillView: View? = null

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

                    @JavascriptInterface
                    fun onMapTapped(lat: Double, lon: Double) {
                        post {
                            if (mapDestroyed) return@post
                            pickedPoiLat = lat
                            pickedPoiLon = lon
                            pickedPoiName = "地图选点"
                            pickedPoiAddress = ""
                            pickedTitleView?.text = "正在解析地址..."
                            pickedSubView?.text = ""
                            pickedSubView?.visibility = View.GONE
                            pickedBannerView?.visibility = View.VISIBLE
                            searchDestPillView?.visibility = View.GONE
                            scope.launch {
                                val resolved = runCatching {
                                    withContext(Dispatchers.IO) {
                                        httpClient.reverseGeocodeApproxAddress(lat, lon)
                                    }
                                }.getOrNull().orEmpty().trim()
                                if (mapDestroyed || pickedPoiLat != lat || pickedPoiLon != lon) return@launch
                                if (resolved.isNotBlank()) {
                                    pickedPoiName = resolved
                                    pickedPoiAddress = resolved
                                    pickedTitleView?.text = resolved
                                    pickedSubView?.visibility = View.GONE
                                } else {
                                    pickedTitleView?.text = "已选地图位置"
                                    pickedSubView?.text = "可点击右侧发送到车机导航"
                                    pickedSubView?.visibility = View.VISIBLE
                                }
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
        activeMapWebViews.add(wv)
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

        // Subtle destination search pill on map bottom-left (auto-hides when a map point is picked)
        val searchDestBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(232, 24, 26, 32), 18f, Color.argb(55, 255, 255, 255), 0.9f)
            setPadding(dp(12), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            elevation = dpF(4f)
            contentDescription = "搜索目的地发送到车"
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener {
                AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                showSendPoiToCarSheet(
                    initialName = pickedPoiName,
                    initialAddress = pickedPoiAddress,
                    initialLat = pickedPoiLat,
                    initialLon = pickedPoiLon
                ) { lat, lon, label ->
                    pickedPoiLat = lat
                    pickedPoiLon = lon
                    pickedPoiName = label
                    pickedPoiAddress = label
                    pickedTitleView?.text = label
                    pickedSubView?.visibility = View.GONE
                    pickedBannerView?.visibility = View.VISIBLE
                    visibility = View.GONE
                    runMapJs("window.CarMap && window.CarMap.setPickedDestination($lat, $lon, ${JSONObject.quote(label)});")
                }
            }
        }
        searchDestBtn.addView(
            AppIconView(context, AppIconType.CROSSHAIR, ThemeTokens.TEXT_PRIMARY),
            LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(5) }
        )
        searchDestBtn.addView(text("搜目的地", 13f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD))
        searchDestPillView = searchDestBtn
        mapContainer.addView(
            searchDestBtn,
            FrameLayout.LayoutParams(WRAP_CONTENT, dp(36), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(12)
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

        // Single Send-to-Car confirmation bar shown right under the map when a point is selected
        val pickedBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD_INNER, 12f, Color.argb(80, 16, 185, 129), 0.9f)
            setPadding(dp(14), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        val pickedTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showSendPoiToCarSheet(
                    initialName = pickedPoiName,
                    initialAddress = pickedPoiAddress,
                    initialLat = pickedPoiLat,
                    initialLon = pickedPoiLon
                ) { lat, lon, label ->
                    pickedPoiLat = lat
                    pickedPoiLon = lon
                    pickedPoiName = label
                    pickedPoiAddress = label
                    pickedTitleView?.text = label
                    pickedSubView?.visibility = View.GONE
                    runMapJs("window.CarMap && window.CarMap.setPickedDestination($lat, $lon, ${JSONObject.quote(label)});")
                }
            }
        }
        val pTitle = text("", 14f, TEXT_PRIMARY, Typeface.BOLD).apply { maxLines = 1 }
        val pSub = text("", 11.5f, TEXT_SUB).apply { maxLines = 1 }
        pickedTitleView = pTitle
        pickedSubView = pSub
        pickedTextCol.addView(pTitle)
        pickedTextCol.addView(pSub, lp(top = dp(2)))
        pickedBar.addView(pickedTextCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val quickSendPill = text("发送到车", 12.5f, Color.WHITE, Typeface.BOLD).apply {
            background = rounded(Color.parseColor("#10B981"), 9f)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.93f)
            setOnClickListener {
                val lat = pickedPoiLat ?: return@setOnClickListener
                val lon = pickedPoiLon ?: return@setOnClickListener
                val destName = pickedPoiName.ifBlank { "地图选点" }
                val destAddr = pickedPoiAddress.ifBlank { destName }
                val active = profileProvider()
                if (active.vin.isBlank() || active.accessToken.isBlank()) {
                    toast("当前暂无有效绑定车辆")
                    return@setOnClickListener
                }
                isEnabled = false
                alpha = 0.6f
                text = "发送中..."
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.sendPoiToVehicle(
                                vin = active.vin,
                                name = destName,
                                address = destAddr,
                                lat = String.format(Locale.US, "%.6f", lat),
                                lon = String.format(Locale.US, "%.6f", lon),
                                telephone = active.mobileNo,
                                accessToken = active.accessToken
                            )
                        }
                    }.getOrDefault(false)
                    isEnabled = true
                    alpha = 1f
                    text = "发送到车"
                    toast(if (ok) "已发送「$destName」到车机" else "发送失败，请重试")
                }
            }
        }
        pickedBar.addView(quickSendPill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })

        val clearPickBtn = AppIconView(context, AppIconType.CLOSE_ICON, TEXT_SUB).apply {
            setPadding(dp(8), dp(8), dp(6), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                pickedPoiLat = null
                pickedPoiLon = null
                pickedPoiName = ""
                pickedPoiAddress = ""
                pickedBar.visibility = View.GONE
                searchDestPillView?.visibility = View.VISIBLE
                runMapJs("window.CarMap && window.CarMap.clearPickedDestination();")
            }
        }
        pickedBar.addView(clearPickBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(4) })
        pickedBannerView = pickedBar
        root.addView(
            pickedBar,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(6)
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
            if (!mapDestroyed) {
                deviceLocationProvider()?.let { (uLat, uLon) ->
                    runMapJs("window.CarMap && window.CarMap.setUserLocation($uLat, $uLon);")
                }
            }
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
                    activeMapWebViews.remove(webView)
                    webView.removeJavascriptInterface("CarMapBridge")
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
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
                    Uri.parse("androidamap://navi?sourceApplication=QiriPlus&poiname=$encLabel&lat=$lat&lon=$lon&dev=0&style=2")
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
                    Uri.parse("qqmap://map/routeplan?type=walk&to=$encLabel&tocoord=$lat,$lon&referer=QiriPlus")
                ).setPackage("com.tencent.map")
                runCatching { context.startActivity(intent) }.onFailure { toast("无法拉起腾讯地图") }
            }
        }
        val (wgsLat, wgsLon) = gcj02ToWgs84(lat, lon)
        if (isPackageInstalled("com.google.android.apps.maps")) {
            options += NavOption("Google 地图", "打开地图定位并导航") {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:$wgsLat,$wgsLon?q=$wgsLat,$wgsLon($encLabel)")
                ).setPackage("com.google.android.apps.maps")
                runCatching { context.startActivity(intent) }.onFailure { toast("无法拉起 Google 地图") }
            }
        }
        options += NavOption("其他系统地图", "使用系统已安装的地图应用打开") {
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$wgsLat,$wgsLon?q=$wgsLat,$wgsLon($encLabel)"))
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
                if (err is CherySessionExpiredException) return@launch
                container.addView(errorHint(friendlyErrorMessage(err, "行程查询失败，请稍后重试")))
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
                        "行程轨迹记录未开启，可前往「安全性 → 行程轨迹记录」开启"
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
                toast("该行程暂无轨迹记录")
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
                "轨迹点数" to "${trip.points.size} 个"
            )
            pairs.forEachIndexed { idx, (k, v) ->
                box.addView(kvRow(k, v), lp(top = if (idx == 0) dp(8) else dp(2)))
            }
            val endPt = trip.points.lastOrNull()
            if (endPt != null && (endPt.lat != 0.0 || endPt.lon != 0.0)) {
                val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                btnRow.addView(secondaryBtn("关闭") { dialog.dismiss() }, weightLp(0, dp(44)))
                lateinit var sendEndBtn: TextView
                sendEndBtn = primaryBtn("发送终点到车") {
                    val active = profileProvider()
                    if (active.vin.isBlank() || active.accessToken.isBlank()) {
                        toast("当前暂无有效绑定车辆")
                        return@primaryBtn
                    }
                    val destName = trip.endAddress.ifBlank { "历史行程终点" }
                    sendEndBtn.isEnabled = false
                    sendEndBtn.alpha = 0.65f
                    sendEndBtn.text = "发送中..."
                    scope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.sendPoiToVehicle(
                                    vin = active.vin,
                                    name = destName,
                                    address = destName,
                                    lat = String.format(Locale.US, "%.6f", endPt.lat),
                                    lon = String.format(Locale.US, "%.6f", endPt.lon),
                                    telephone = active.mobileNo,
                                    accessToken = active.accessToken
                                )
                            }
                        }.getOrDefault(false)
                        if (!dialog.isShowing) return@launch
                        if (ok) {
                            dialog.dismiss()
                            toast("已发送「$destName」到车机导航")
                        } else {
                            sendEndBtn.isEnabled = true
                            sendEndBtn.alpha = 1f
                            sendEndBtn.text = "发送终点到车"
                            toast("发送到车失败，请稍后重试")
                        }
                    }
                }
                btnRow.addView(sendEndBtn, weightLp(dp(10), dp(44)))
                box.addView(btnRow, lp(top = dp(18)))
            } else {
                box.addView(primaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(18), height = dp(44)))
            }
        }
    }

    // =========================================================================
    // 3. 车辆升级 (OTA) 与账户安全/密码管理
    // =========================================================================

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
                pollJob?.cancel()
                pollJob = null
                host.removeAllViews()
                res.onSuccess { info: VehicleUpgradeInfo ->
                    val status = info.upgradeStatus
                    val card = cardBox()
                    card.addView(kvRow("当前版本", info.currentVersion.ifBlank { "—" }))
                    val statusText = when (status) {
                        VehicleUpgradeStatus.NO_UPDATE -> "已是最新版本"
                        VehicleUpgradeStatus.AVAILABLE -> "有新版本"
                        VehicleUpgradeStatus.SCHEDULED -> "已预约升级"
                        VehicleUpgradeStatus.PREPARING -> "等待升级启动中"
                        VehicleUpgradeStatus.UPGRADING -> "升级中"
                        VehicleUpgradeStatus.UNFINISHED -> "升级未完成"
                        VehicleUpgradeStatus.FAILED -> "升级失败"
                        VehicleUpgradeStatus.RETRYABLE_FAILED -> "升级未完成"
                    }
                    card.addView(kvRow("固件状态", statusText))
                    if (status == VehicleUpgradeStatus.SCHEDULED && info.upgradeStartTime.isNotBlank()) {
                        card.addView(kvRow("预约时间", info.upgradeStartTime))
                    }
                    if (info.upgradeTime.isNotBlank() && status != VehicleUpgradeStatus.NO_UPDATE) {
                        card.addView(kvRow("预计耗时", info.upgradeTime))
                    }

                    val notes = when (status) {
                        VehicleUpgradeStatus.UNFINISHED,
                        VehicleUpgradeStatus.FAILED,
                        VehicleUpgradeStatus.RETRYABLE_FAILED ->
                            info.descriptionText.ifBlank { info.releaseNote }
                        else -> info.releaseNote.ifBlank { info.descriptionText }
                    }
                    if (notes.isNotBlank()) {
                        val notesTitle = when (status) {
                            VehicleUpgradeStatus.UNFINISHED,
                            VehicleUpgradeStatus.FAILED,
                            VehicleUpgradeStatus.RETRYABLE_FAILED -> "状态说明"
                            else -> "更新说明"
                        }
                        card.addView(text(notesTitle, 12.5f, TEXT_SUB, Typeface.BOLD), lp(top = dp(10), bottom = dp(4)))
                        card.addView(
                            text(notes, 13f, TEXT_BODY).apply { setLineSpacing(0f, 1.45f) },
                            lp()
                        )
                    }
                    host.addView(card)

                    // Inline silent-polling linear progress indicator (shown while upgrading/preparing)
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
                                    val stillUpgrading = st.state == "2" || st.state == "3"
                                    val label = if (st.surplusTime.isNotBlank()) {
                                        "正在升级 · 剩余 ${st.surplusTime}"
                                    } else {
                                        "正在升级中..."
                                    }
                                    updateProgressUi(st.percentage, label)
                                    if (!stillUpgrading || st.percentage >= 100) {
                                        // Official protocol: state left 2/3 -> re-fetch 148 for terminal state
                                        onVehiclesChanged()
                                        retryAction()
                                        break
                                    }
                                }
                                delay(5_000L)
                            }
                        }
                    }

                    fun startPreparingPoll() {
                        pollJob?.cancel()
                        pollJob = scope.launch {
                            while (isActive && sheetActive) {
                                delay(5_000L)
                                if (!sheetActive) break
                                val next = runCatching {
                                    withContext(Dispatchers.IO) {
                                        httpClient.checkVehicleUpgrade(profile.vin, profile.accessToken)
                                    }
                                }.getOrNull()
                                if (!sheetActive) break
                                if (next != null && next.upgradeStatus != VehicleUpgradeStatus.PREPARING) {
                                    onVehiclesChanged()
                                    renderUpgradeResult(Result.success(next), retryAction)
                                    break
                                }
                            }
                        }
                    }

                    when {
                        info.canStartNow -> {
                            val ctaRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                            ctaRow.addView(secondaryBtn("取消") { dialog.dismiss() }, weightLp(0, dp(46)))
                            lateinit var upgradeBtn: TextView
                            fun executeStartUpgrade(
                                pin: String,
                                onPinError: ((String) -> Unit)? = null,
                                onDismissPin: (() -> Unit)? = null
                            ) {
                                upgradeBtn.isEnabled = false
                                upgradeBtn.alpha = 0.55f
                                upgradeBtn.text = "下发中..."
                                updateProgressUi(2, "正在向车辆下发升级指令...")
                                scope.launch {
                                    val outcome = runCatching {
                                        withContext(Dispatchers.IO) {
                                            httpClient.startVehicleUpgrade(
                                                vin = profile.vin,
                                                upgradeTaskId = info.upgradeTaskId,
                                                vehiclePin = pin,
                                                accessToken = profile.accessToken
                                            )
                                        }
                                    }
                                    if (!sheetActive) return@launch
                                    outcome.onSuccess { res ->
                                        if (res.success) {
                                            if (pin.length == 6 && pin.all { it.isDigit() }) {
                                                onPinUpdated(profile.vin, pin)
                                            }
                                            onDismissPin?.invoke()
                                            upgradeBtn.text = "升级中..."
                                            updateProgressUi(5, "升级指令已下发，正在同步车辆状态...")
                                            onVehiclesChanged()
                                            delay(1_500L)
                                            if (sheetActive) retryAction()
                                        } else if (res.isPinError) {
                                            progressBox.visibility = View.GONE
                                            upgradeBtn.isEnabled = true
                                            upgradeBtn.alpha = 1f
                                            upgradeBtn.text = "立即升级"
                                            if (onPinError != null) {
                                                onPinError(res.message.ifBlank { "车控安全密码校验失败，请重新输入" })
                                            } else {
                                                secondaryDialog = SixDigitPinSheet.showVerifyPinSheet(
                                                    context = context,
                                                    title = "验证安全密码",
                                                    subtitle = "请输入 6 位车控安全密码以开始车辆升级"
                                                ) { enteredPin, showErr, dismissPin ->
                                                    executeStartUpgrade(enteredPin, showErr, dismissPin)
                                                }
                                            }
                                        } else {
                                            onDismissPin?.invoke()
                                            progressBox.visibility = View.GONE
                                            upgradeBtn.isEnabled = true
                                            upgradeBtn.alpha = 1f
                                            upgradeBtn.text = "立即升级"
                                            toast(res.message.ifBlank { "触发车辆升级失败，请确认车辆已驻车熄火且电量充足" })
                                        }
                                    }.onFailure { err ->
                                        onDismissPin?.invoke()
                                        progressBox.visibility = View.GONE
                                        upgradeBtn.isEnabled = true
                                        upgradeBtn.alpha = 1f
                                        upgradeBtn.text = "立即升级"
                                        if (err !is CherySessionExpiredException) {
                                            toast(friendlyErrorMessage(err, "触发车辆升级失败，请稍后重试"))
                                        }
                                    }
                                }
                            }

                            upgradeBtn = primaryBtn("立即升级") {
                                val confirmMsg = if (status == VehicleUpgradeStatus.SCHEDULED) {
                                    "当前已预约 ${info.upgradeStartTime} 升级。确认取消预约并立即开始升级吗？升级期间请确保车辆处于驻车熄火状态且电量充足。"
                                } else {
                                    "升级期间请确保车辆处于驻车熄火状态且电量充足，过程中请勿操作车辆。"
                                }
                                AppComponents.showConfirmDialog(
                                    context = context,
                                    title = "确认开始车辆升级",
                                    message = confirmMsg,
                                    confirmText = "立即升级",
                                    cancelText = "取消"
                                ) {
                                    AppHaptics.perform(upgradeBtn, HapticLevel.MEDIUM_CONFIRM)
                                    val cachedPin = cachedPinProvider().ifBlank { profile.controlPin }.trim()
                                    val mustPromptPin = requirePinEveryTimeProvider() ||
                                        cachedPin.length != 6 || !cachedPin.all { it.isDigit() }
                                    if (mustPromptPin) {
                                        secondaryDialog = SixDigitPinSheet.showVerifyPinSheet(
                                            context = context,
                                            title = "验证安全密码",
                                            subtitle = "请输入 6 位车控安全密码以开始车辆升级"
                                        ) { enteredPin, showErr, dismissPin ->
                                            executeStartUpgrade(enteredPin, showErr, dismissPin)
                                        }
                                    } else {
                                        executeStartUpgrade(cachedPin)
                                    }
                                }
                            }
                            ctaRow.addView(upgradeBtn, weightLp(dp(10), dp(46)))
                            host.addView(ctaRow, lp(top = dp(16)))
                        }
                        status == VehicleUpgradeStatus.UPGRADING -> {
                            updateProgressUi(info.percentage.coerceAtLeast(1), "正在升级中...")
                            host.addView(secondaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(16), height = dp(46)))
                            if (info.upgradeTaskId.isNotBlank()) {
                                startSilentProgressPoll(info.upgradeTaskId)
                            }
                        }
                        status == VehicleUpgradeStatus.PREPARING -> {
                            updateProgressUi(5, "升级任务已下发，等待车辆开始安装...")
                            host.addView(secondaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(16), height = dp(46)))
                            startPreparingPoll()
                        }
                        info.canRetry -> {
                            val ctaRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                            ctaRow.addView(secondaryBtn("取消") { dialog.dismiss() }, weightLp(0, dp(46)))
                            lateinit var retryBtn: TextView
                            retryBtn = primaryBtn("重试升级") {
                                AppHaptics.perform(retryBtn, HapticLevel.MEDIUM_CONFIRM)
                                retryBtn.isEnabled = false
                                retryBtn.alpha = 0.55f
                                retryBtn.text = "提交中..."
                                scope.launch {
                                    val outcome = runCatching {
                                        withContext(Dispatchers.IO) {
                                            httpClient.retryVehicleUpgrade(
                                                vin = profile.vin,
                                                upgradeTaskId = info.upgradeTaskId,
                                                accessToken = profile.accessToken
                                            )
                                        }
                                    }
                                    if (!sheetActive) return@launch
                                    outcome.onSuccess { res ->
                                        if (res.success) {
                                            toast("已重新提交升级请求")
                                            onVehiclesChanged()
                                            retryAction()
                                        } else {
                                            retryBtn.isEnabled = true
                                            retryBtn.alpha = 1f
                                            retryBtn.text = "重试升级"
                                            toast(res.message.ifBlank { "重试升级失败，请稍后重试" })
                                        }
                                    }.onFailure { err ->
                                        retryBtn.isEnabled = true
                                        retryBtn.alpha = 1f
                                        retryBtn.text = "重试升级"
                                        if (err !is CherySessionExpiredException) {
                                            toast(friendlyErrorMessage(err, "重试升级失败，请稍后重试"))
                                        }
                                    }
                                }
                            }
                            ctaRow.addView(retryBtn, weightLp(dp(10), dp(46)))
                            host.addView(ctaRow, lp(top = dp(16)))
                        }
                        status == VehicleUpgradeStatus.UNFINISHED || status == VehicleUpgradeStatus.FAILED -> {
                            val ctaRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                            ctaRow.addView(secondaryBtn("关闭") { dialog.dismiss() }, weightLp(0, dp(46)))
                            ctaRow.addView(primaryBtn("重新检查") { retryAction() }, weightLp(dp(10), dp(46)))
                            host.addView(ctaRow, lp(top = dp(16)))
                        }
                        else -> {
                            host.addView(secondaryBtn("关闭") { dialog.dismiss() }, lp(top = dp(16), height = dp(46)))
                        }
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
            var submitting = false
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

            val errTv = text("", 12.5f, ThemeTokens.DANGER).apply { visibility = View.GONE }
            box.addView(errTv, lp(top = dp(10)))

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(btns, lp(top = dp(18)))
            btns.addView(secondaryBtn("取消") { if (!submitting) dialog.dismiss() }, weightLp(0, dp(44)))
            lateinit var saveBtn: TextView
            saveBtn = primaryBtn("保存") {
                if (submitting) return@primaryBtn
                val oldPwd = oldEt.text.toString()
                val newPwd = newEt.text.toString()
                val confirmPwd = confirmEt.text.toString()
                if (oldPwd.isBlank() || newPwd.length < 6) {
                    errTv.text = "请填写原密码及至少 6 位新密码"
                    errTv.visibility = View.VISIBLE
                    return@primaryBtn
                }
                if (newPwd != confirmPwd) {
                    errTv.text = "两次输入的新密码不一致"
                    errTv.visibility = View.VISIBLE
                    return@primaryBtn
                }
                submitting = true
                errTv.visibility = View.GONE
                saveBtn.isEnabled = false
                saveBtn.alpha = 0.6f
                saveBtn.text = "保存中..."
                scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.changeLoginPassword(oldPwd, newPwd, profile.accessToken)
                        }
                    }
                    if (!dialog.isShowing) return@launch
                    submitting = false
                    saveBtn.isEnabled = true
                    saveBtn.alpha = 1f
                    saveBtn.text = "保存"
                    res.onSuccess { ok ->
                        if (ok) {
                            dialog.dismiss()
                            toast("登录密码已更新")
                        } else {
                            errTv.text = "原密码校验失败，请核对后重试"
                            errTv.visibility = View.VISIBLE
                        }
                    }.onFailure { err ->
                        if (err is CherySessionExpiredException) {
                            dialog.dismiss()
                        } else {
                            errTv.text = friendlyErrorMessage(err, "修改登录密码失败，请稍后重试")
                            errTv.visibility = View.VISIBLE
                        }
                    }
                }
            }
            btns.addView(saveBtn, weightLp(dp(10), dp(44)))
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
        lateinit var sheet: Dialog
        sheet = SixDigitPinSheet.showChangePinWizardSheet(
            context = context,
            requireOldPin = true,
            allowSkipOldPin = false
        ) { oldPin, newPin, showError, dismiss ->
            val currentOldPin = oldPin.orEmpty().trim()
            if (currentOldPin.length != 6) {
                showError("请输入当前 6 位原 PIN 码")
                return@showChangePinWizardSheet
            }
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.changeVehicleControlPin(profile.vin, currentOldPin, newPin, profile.accessToken)
                    }
                }
                if (!sheet.isShowing) return@launch
                res.onSuccess { ok ->
                    if (ok) {
                        onPinUpdated(profile.vin, newPin)
                        dismiss()
                        toast("PIN 码已更新")
                    } else {
                        showError("原 PIN 码校验失败，请核对后重试")
                    }
                }.onFailure { err ->
                    if (err is CherySessionExpiredException) {
                        dismiss()
                    } else {
                        showError(friendlyErrorMessage(err, "PIN 码更新失败，请稍后重试"))
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
                text("校验车辆识别码与短信后重置 6 位 PIN 码", 12.5f, TEXT_SUB).apply {
                    setLineSpacing(0f, 1.35f)
                },
                lp(top = dp(4))
            )

            val backingVin = profile.vin.trim().uppercase(Locale.US)
            val backingSn = defaultSn.trim()
            val backingIccid = defaultIccid.trim()
            val backingMobile = profile.mobileNo.trim()

            val vinHint = if (backingVin.isNotBlank()) {
                "车架号 VIN（留空默认 ${SensitiveUiText.identifier(backingVin)}）"
            } else {
                "17 位车架号 VIN"
            }
            val snHint = if (backingSn.isNotBlank()) {
                "车辆序列号 SN（留空默认 ${SensitiveUiText.identifier(backingSn)}）"
            } else {
                "车辆序列号 (SN)"
            }
            val iccidHint = if (backingIccid.isNotBlank()) {
                "车载 SIM 卡 ICCID（留空默认 ${SensitiveUiText.identifier(backingIccid)}）"
            } else {
                "车载 SIM 卡 ICCID"
            }
            val mobileHint = if (backingMobile.isNotBlank()) {
                "账号手机号（留空默认 ${SensitiveUiText.maskPhone(backingMobile)}）"
            } else {
                "账号手机号"
            }

            val vinEt = modalInput(vinHint, InputType.TYPE_CLASS_TEXT)
            val snEt = modalInput(snHint, InputType.TYPE_CLASS_TEXT)
            val iccidEt = modalInput(iccidHint, InputType.TYPE_CLASS_TEXT)
            val mobileEt = modalInput(mobileHint, InputType.TYPE_CLASS_PHONE)

            val codeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val codeEt = modalInput("短信验证码", InputType.TYPE_CLASS_NUMBER)
            lateinit var sendBtn: TextView
            sendBtn = secondaryBtn("获取验证码") {
                if (smsJob?.isActive == true) return@secondaryBtn
                val m = mobileEt.text.toString().trim().ifBlank { backingMobile }
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
                val v = vinEt.text.toString().trim().uppercase(Locale.US).ifBlank { backingVin }
                val s = snEt.text.toString().trim().ifBlank { backingSn }
                val ic = iccidEt.text.toString().trim().ifBlank { backingIccid }
                val m = mobileEt.text.toString().trim().ifBlank { backingMobile }
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
                        onPinUpdated(v, np)
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
                text("车速超过阈值时推送提醒（60–120 km/h）", 13f, TEXT_SUB).apply {
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

            var submittingSpeed = false
            lateinit var saveSpeedBtn: TextView
            var disableSpeedBtn: TextView? = null

            fun submitSpeed(targetEnabled: Boolean, targetSpeed: Int) {
                if (submittingSpeed) return
                val desc = if (targetEnabled) "开启限速提醒（${targetSpeed} km/h）" else "关闭限速提醒"
                resolvePin(desc) { verifiedPin ->
                    if (submittingSpeed) return@resolvePin
                    submittingSpeed = true
                    saveSpeedBtn.isEnabled = false
                    saveSpeedBtn.alpha = 0.6f
                    disableSpeedBtn?.isEnabled = false
                    disableSpeedBtn?.alpha = 0.6f
                    scope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.setSpeedLimitReminder(
                                    vin = profile.vin,
                                    speedKmh = if (targetEnabled) targetSpeed else 0,
                                    pin = verifiedPin,
                                    accessToken = profile.accessToken
                                )
                            }
                        }
                        submittingSpeed = false
                        saveSpeedBtn.isEnabled = true
                        saveSpeedBtn.alpha = 1f
                        disableSpeedBtn?.isEnabled = true
                        disableSpeedBtn?.alpha = 1f
                        res.onSuccess { ok ->
                            if (ok) {
                                onUpdated(targetEnabled, if (targetEnabled) targetSpeed else 0)
                                if (dialog.isShowing) dialog.dismiss()
                                toast(if (targetEnabled) "已设置限速提醒：$targetSpeed km/h" else "已关闭限速提醒")
                            } else {
                                toast("限速提醒设置失败，请稍后重试")
                            }
                        }.onFailure { err ->
                            if (err is CherySessionExpiredException) {
                                if (dialog.isShowing) dialog.dismiss()
                            } else {
                                toast(friendlyErrorMessage(err, "限速提醒设置失败，请稍后重试"))
                            }
                        }
                    }
                }
            }

            saveSpeedBtn = primaryBtn("保存并开启限速提醒") {
                submitSpeed(true, selectedSpeed)
            }
            sheet.addView(saveSpeedBtn, lp(height = dp(48)))

            if (currentEnabled) {
                disableSpeedBtn = secondaryBtn("关闭限速提醒") {
                    submitSpeed(false, 0)
                }
                sheet.addView(disableSpeedBtn, lp(top = dp(10), height = dp(46)))
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
                telemetry?.remainingCharge?.let { formatRemainingMinutes(it) }?.let { formatted ->
                    detailParts += "剩余时间 $formatted"
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
            var savingSchedules = false

            lateinit var renderSchedules: () -> Unit

            fun persistSchedules(nextMaster: Boolean, nextList: List<ChargingScheduleItem>, actionDesc: String) {
                if (savingSchedules) return
                resolvePin(actionDesc) { verifiedPin ->
                    if (savingSchedules) return@resolvePin
                    savingSchedules = true
                    scope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.saveChargingSchedules(
                                    vin = profile.vin,
                                    masterEnabled = nextMaster,
                                    schedules = nextList,
                                    pin = verifiedPin,
                                    accessToken = profile.accessToken
                                )
                            }
                        }
                        savingSchedules = false
                        res.onSuccess { ok ->
                            if (ok) {
                                masterEnabled = nextMaster
                                currentSchedules = nextList
                                if (dialog.isShowing) renderSchedules()
                                toast("预约充电设置已下发")
                            } else {
                                if (dialog.isShowing) renderSchedules()
                                toast("预约充电同步失败，请稍后重试")
                            }
                        }.onFailure { err ->
                            if (err is CherySessionExpiredException) {
                                if (dialog.isShowing) dialog.dismiss()
                            } else {
                                if (dialog.isShowing) renderSchedules()
                                toast(friendlyErrorMessage(err, "预约充电同步失败，请稍后重试"))
                            }
                        }
                    }
                }
            }

            fun openEditScheduleDialog(existingIndex: Int?) {
                val existing = existingIndex?.let { currentSchedules.getOrNull(it) }
                var startTimeStr = existing?.startTime?.takeIf { it.contains(":") } ?: "23:00"
                var durationHours = (existing?.durationHours ?: 4).coerceIn(1, 24)
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
                        max = 23
                        progress = (durationHours - 1).coerceIn(0, 23)
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

                    if (existingIndex != null && existingIndex in currentSchedules.indices) {
                        box.addView(
                            AppComponents.createActionButton(context, "删除此计划", ButtonStyle.DANGER_TEXT) {
                                val updatedList = currentSchedules.toMutableList().apply { removeAt(existingIndex) }
                                mDialog.dismiss()
                                persistSchedules(updatedList.any { it.enabled }, updatedList, "删除预约充电计划")
                            },
                            lp(top = dp(8), height = dp(42))
                        )
                    }
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
                    scheduleHost.addView(text("暂无预约充电计划", 12.5f, TEXT_SUB).apply {
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

            fun loadSchedules() {
                scheduleHost.removeAllViews()
                scheduleHost.addView(text("正在读取预约充电计划...", 13f, TEXT_SUB).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(20), 0, dp(20))
                })
                scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.fetchChargingSchedules(profile.vin, profile.accessToken)
                        }
                    }
                    if (!dialog.isShowing) return@launch
                    res.onSuccess { summary ->
                        masterEnabled = summary.masterEnabled
                        currentSchedules = summary.items
                        renderSchedules()
                    }.onFailure { err ->
                        if (err is CherySessionExpiredException) {
                            dialog.dismiss()
                            return@onFailure
                        }
                        scheduleHost.removeAllViews()
                        scheduleHost.addView(errorHint(friendlyErrorMessage(err, "读取预约充电计划失败，请重试")))
                        scheduleHost.addView(secondaryBtn("重新加载") { loadSchedules() }, lp(top = dp(10), height = dp(44)))
                    }
                }
            }
            loadSchedules()
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
                text("出发前自动提前调节座舱温度", 13f, TEXT_SUB).apply {
                    setLineSpacing(0f, 1.4f)
                },
                lp(top = dp(6))
            )

            val host = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            sheet.addView(host, lp(top = dp(14)))

            fun render(enabled: Boolean, startTime: String) {
                host.removeAllViews()
                var isOn = enabled
                var savingTravel = false
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

                lateinit var saveBtn: TextView
                saveBtn = primaryBtn("保存预约出行") {
                    if (savingTravel) return@primaryBtn
                    val pickedTime = getTime().ifBlank { initialTime }
                    val desc = if (isOn) "开启预约出行（$pickedTime）" else "关闭预约出行"
                    resolvePin(desc) { verifiedPin ->
                        if (savingTravel) return@resolvePin
                        savingTravel = true
                        saveBtn.isEnabled = false
                        saveBtn.alpha = 0.6f
                        saveBtn.text = "同步中..."
                        scope.launch {
                            val res = runCatching {
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
                            }
                            savingTravel = false
                            saveBtn.isEnabled = true
                            saveBtn.alpha = 1f
                            saveBtn.text = "保存预约出行"
                            res.onSuccess { ok ->
                                if (ok) {
                                    if (dialog.isShowing) dialog.dismiss()
                                    toast(if (isOn) "预约出行已设置：$pickedTime" else "已关闭预约出行")
                                } else {
                                    toast("预约出行同步失败，请稍后重试")
                                }
                            }.onFailure { err ->
                                if (err is CherySessionExpiredException) {
                                    if (dialog.isShowing) dialog.dismiss()
                                } else {
                                    toast(friendlyErrorMessage(err, "预约出行同步失败，请稍后重试"))
                                }
                            }
                        }
                    }
                }
                host.addView(saveBtn, lp(height = dp(48)))
            }

            fun loadTravel() {
                host.removeAllViews()
                host.addView(text("正在读取预约出行配置...", 13f, TEXT_SUB).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(20), 0, dp(20))
                })
                scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.fetchTravelSchedules(profile.vin, profile.accessToken)
                        }
                    }
                    if (!dialog.isShowing) return@launch
                    res.onSuccess { summary ->
                        val first = summary.items.firstOrNull()
                        render(
                            enabled = summary.masterEnabled || (first?.enabled == true),
                            startTime = first?.startTime.orEmpty().ifBlank { "08:00" }
                        )
                    }.onFailure { err ->
                        if (err is CherySessionExpiredException) {
                            dialog.dismiss()
                            return@onFailure
                        }
                        host.removeAllViews()
                        host.addView(errorHint(friendlyErrorMessage(err, "读取预约出行配置失败，请重试")))
                        host.addView(secondaryBtn("重新加载") { loadTravel() }, lp(top = dp(10), height = dp(44)))
                    }
                }
            }
            loadTravel()
        }
    }

    /**
     * Send-POI-to-Car sheet (zyhl 943): supports keyword POI search, map-tapped point confirmation,
     * and Android Share / geo: incoming locations.
     */
    fun showSendPoiToCarSheet(
        initialName: String = "",
        initialAddress: String = "",
        initialLat: Double? = null,
        initialLon: Double? = null,
        initialQuery: String = "",
        onDestinationPreview: ((Double, Double, String) -> Unit)? = null
    ) {
        val activeProfile = profileProvider()
        var selectedLat: Double? = initialLat?.takeIf { it != 0.0 || (initialLon ?: 0.0) != 0.0 }
        var selectedLon: Double? = initialLon?.takeIf { selectedLat != null }
        var selectedAddress = initialAddress.trim()

        showSheet("发送目的地到车") { sheet, dialog ->
            sheet.addView(
                text("搜索或分享位置，一键推送至车机导航", 12.5f, TEXT_SUB).apply {
                    setLineSpacing(0f, 1.38f)
                },
                lp(top = dp(4))
            )

            // Search bar row
            val searchRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val searchEt = modalInput("搜索地点、大厦、车站或地址", InputType.TYPE_CLASS_TEXT).apply {
                if (initialQuery.isNotBlank()) setText(initialQuery)
            }
            lateinit var searchBtn: TextView
            val resultsHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val nameEt = modalInput("目的地名称（车机显示）", InputType.TYPE_CLASS_TEXT).apply {
                if (initialName.isNotBlank()) setText(initialName)
            }
            val initialSafeAddr = selectedAddress.takeIf {
                it.isNotBlank() && !RAW_COORD_REGEX.matches(it)
            }.orEmpty()
            val coordHintTv = text(
                if (selectedLat != null && selectedLon != null) {
                    if (initialSafeAddr.isNotBlank()) {
                        "已锁定位置：${initialSafeAddr.take(44)}"
                    } else {
                        "已锁定地图位置，可直接发送到车"
                    }
                } else {
                    "尚未选择位置：请先搜索地点或在地图点选"
                },
                12f,
                if (selectedLat != null) Color.parseColor("#10B981") else TEXT_SUB
            )

            fun applySelectedPoi(poi: PoiSearchResult) {
                selectedLat = poi.lat
                selectedLon = poi.lon
                selectedAddress = poi.address
                nameEt.setText(poi.name)
                val displayAddr = poi.address.trim().takeIf { it.isNotBlank() && !RAW_COORD_REGEX.matches(it) }
                coordHintTv.text = if (displayAddr != null) {
                    "已锁定：${displayAddr.take(44)}"
                } else {
                    "已锁定目的地：${poi.name.take(36)}"
                }
                coordHintTv.setTextColor(Color.parseColor("#10B981"))
                onDestinationPreview?.invoke(poi.lat, poi.lon, poi.name)
            }

            fun runPoiSearch(queryText: String) {
                val q = queryText.trim()
                if (q.isBlank()) {
                    toast("请输入搜索关键词")
                    return
                }
                searchBtn.isEnabled = false
                searchBtn.alpha = 0.6f
                searchBtn.text = "搜索中"
                resultsHost.removeAllViews()
                resultsHost.addView(text("正在检索地点...", 12.5f, TEXT_SUB).apply {
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                })
                scope.launch {
                    val t = telemetryProvider()
                    val list = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.searchPoi(q, t?.latitude, t?.longitude)
                        }
                    }.getOrDefault(emptyList())
                    if (!dialog.isShowing) return@launch
                    searchBtn.isEnabled = true
                    searchBtn.alpha = 1f
                    searchBtn.text = "搜索"
                    resultsHost.removeAllViews()
                    if (list.isEmpty()) {
                        resultsHost.addView(text("未找到匹配地点，可尝试更具体的城市+地名", 12.5f, TEXT_SUB).apply {
                            setPadding(dp(4), dp(8), dp(4), dp(8))
                        })
                        return@launch
                    }
                    list.take(5).forEach { poi ->
                        val itemCard = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            background = rounded(CARD_INNER, 10f)
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            isClickable = true
                            isFocusable = true
                            MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)
                            setOnClickListener {
                                applySelectedPoi(poi)
                                resultsHost.removeAllViews()
                                toast("已选中：${poi.name}")
                            }
                        }
                        itemCard.addView(text(poi.name, 13.5f, TEXT_PRIMARY, Typeface.BOLD).apply { maxLines = 1 })
                        itemCard.addView(text(poi.address, 11.5f, TEXT_SUB).apply { maxLines = 1 }, lp(top = dp(2)))
                        resultsHost.addView(itemCard, lp(top = dp(6)))
                    }
                    if (selectedLat == null && list.isNotEmpty()) {
                        applySelectedPoi(list.first())
                    }
                }
            }

            searchBtn = secondaryBtn("搜索") { runPoiSearch(searchEt.text.toString()) }
            searchRow.addView(searchEt, LinearLayout.LayoutParams(0, dp(44), 1f))
            searchRow.addView(searchBtn, LinearLayout.LayoutParams(dp(74), dp(44)).apply { marginStart = dp(8) })

            sheet.addView(searchRow, lp(top = dp(12)))
            sheet.addView(resultsHost, lp(top = dp(4)))

            sheet.addView(text("目的地名称", 12f, TEXT_SUB), lp(top = dp(12)))
            sheet.addView(nameEt, lp(top = dp(4), height = dp(44)))
            sheet.addView(coordHintTv, lp(top = dp(8), bottom = dp(16)))

            var sending = false
            lateinit var sendBtn: TextView
            sendBtn = primaryBtn("发送到车机导航") {
                if (sending) return@primaryBtn
                val lat = selectedLat
                val lon = selectedLon
                if (lat == null || lon == null) {
                    toast("请先搜索选择目的地或在地图点选位置")
                    return@primaryBtn
                }
                if (activeProfile.vin.isBlank() || activeProfile.accessToken.isBlank()) {
                    toast("当前暂无有效绑定车辆")
                    return@primaryBtn
                }
                val finalName = nameEt.text.toString().trim().ifBlank { "目的地" }
                val finalAddr = selectedAddress.ifBlank { finalName }
                sending = true
                sendBtn.isEnabled = false
                sendBtn.alpha = 0.65f
                sendBtn.text = "正在推送到车机..."
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.sendPoiToVehicle(
                                vin = activeProfile.vin,
                                name = finalName,
                                address = finalAddr,
                                lat = String.format(Locale.US, "%.6f", lat),
                                lon = String.format(Locale.US, "%.6f", lon),
                                telephone = activeProfile.mobileNo,
                                accessToken = activeProfile.accessToken
                            )
                        }
                    }.getOrDefault(false)
                    if (!dialog.isShowing) return@launch
                    if (ok) {
                        dialog.dismiss()
                        toast("已发送「$finalName」到车机导航")
                    } else {
                        sending = false
                        sendBtn.isEnabled = true
                        sendBtn.alpha = 1f
                        sendBtn.text = "发送到车机导航"
                        toast("发送到车失败，请稍后重试")
                    }
                }
            }
            sheet.addView(sendBtn, lp(height = dp(48)))

            if (initialQuery.isNotBlank() && selectedLat == null) {
                runPoiSearch(initialQuery)
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
        activeImmediateDismiss?.invoke() ?: activeDialog?.let { MotionSpec.dismissSheetImmediately(it) }
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
                MotionSpec.dismissSheetImmediately(this)
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

        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.78f).roundToInt()
        outer = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val parentMode = MeasureSpec.getMode(heightMeasureSpec)
                val parentSize = MeasureSpec.getSize(heightMeasureSpec)
                val effectiveMax = if (parentMode == MeasureSpec.UNSPECIFIED) maxHeight else minOf(parentSize, maxHeight)
                val mode = if (parentMode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(effectiveMax, mode))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = topRounded(CARD_MODAL, 22f)
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

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        build(body, dialog)

        AppSystemInsets.installBottomSheetContent(dialog, outer, dimAmount = 0.68f)
        MotionSpec.attachSheetSwipeToDismiss(outer, scroll, dialog) {
            dialog.restoreDepthIfNeeded()
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
        if (!MotionSpec.isHostActivityAlive(context)) return
        activeDialog = dialog
        activeImmediateDismiss = { dialog.dismissImmediately() }
        runCatching {
            dialog.show()
            onDepthRecede(true)
            MotionSpec.animateSheetEnter(outer)
        }.onFailure {
            if (activeDialog === dialog) {
                activeDialog = null
                activeImmediateDismiss = null
            }
        }
    }

    private fun showModalDialog(title: String, build: (LinearLayout, Dialog) -> Unit) {
        if (!MotionSpec.isHostActivityAlive(context)) return
        secondaryDialog?.dismiss()
        var modalCardRef: View? = null
        val dialog = object : Dialog(context, R.style.Theme_CarPlus_BottomSheetDialog) {
            override fun dismiss() {
                val target = modalCardRef
                if (!isShowing || target == null || MotionSpec.isBypassingSheetDismiss(this)) {
                    runCatching { super.dismiss() }
                } else {
                    MotionSpec.dismissModalAnimated(target, this)
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_MODAL, 16f, Color.rgb(52, 54, 62), 0.7f)
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        box.addView(text(title, 18f, TEXT_PRIMARY, Typeface.BOLD))
        build(box, dialog)
        scroll.addView(box, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        modalCardRef = scroll

        AppSystemInsets.installModalContent(dialog, scroll, dimAmount = 0.68f, horizontalMarginDp = 20f)
        dialog.setOnDismissListener { if (secondaryDialog === dialog) secondaryDialog = null }
        if (!MotionSpec.isHostActivityAlive(context)) return
        secondaryDialog = dialog
        runCatching {
            dialog.show()
            MotionSpec.animateModalEnter(scroll)
        }.onFailure {
            if (secondaryDialog === dialog) secondaryDialog = null
        }
    }

    private fun segmentedBar(
        labels: List<String>,
        initial: Int,
        onSelect: (Int) -> Unit
    ): FrameLayout = MotionSpec.createSlidingSegmentedBar(
        context = context,
        labels = labels,
        initial = initial,
        bgColor = ThemeTokens.SEGMENT_TRACK,
        pillColor = ThemeTokens.SEGMENT_PILL,
        activeTextColor = Color.WHITE,
        inactiveTextColor = TEXT_SUB,
        trackStrokeColor = ThemeTokens.SEGMENT_TRACK_BORDER,
        pillStrokeColor = ThemeTokens.SEGMENT_PILL_BORDER,
        onSelect = onSelect
    )

    private fun animateTabContent(container: View) {
        MotionSpec.animatePanelSwitch(container)
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
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = rounded(BTN_SECONDARY, 8f, ThemeTokens.BTN_SECONDARY_BORDER, 0.8f)
            setPadding(dp(10), 0, dp(10), 0)
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

    private fun friendlyErrorMessage(err: Throwable?, fallback: String): String {
        val raw = err?.message.orEmpty().trim()
        return when {
            raw.isBlank() -> fallback
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "网络连接超时，请稍后重试"
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("Failed to connect", ignoreCase = true) ||
                raw.contains("Socket", ignoreCase = true) ||
                raw.contains("SSL", ignoreCase = true) ||
                raw.contains("HTTP", ignoreCase = true) ||
                raw.startsWith("{") ||
                raw.contains("Exception") -> fallback
            else -> raw.take(60)
        }
    }

    private fun formatRemainingMinutes(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "0" || trimmed.equals("FFFF", ignoreCase = true) || trimmed == "65535") {
            return null
        }
        val mins = trimmed.toDoubleOrNull()?.roundToInt() ?: return trimmed
        if (mins <= 0 || mins > 24 * 60 * 7) return null
        val h = mins / 60
        val m = mins % 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分钟"
            h > 0 -> "${h}小时"
            else -> "${m}分钟"
        }
    }

    /**
     * Converts GCJ-02 (Amap/Chery cloud) coordinates back to WGS-84 for Google Maps and generic geo: apps.
     */
    private fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
        if (gcjLon !in 72.004..137.8347 || gcjLat !in 0.8293..55.8271) return gcjLat to gcjLon
        fun transformLat(x: Double, y: Double): Double {
            var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
            ret += (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            ret += (20.0 * kotlin.math.sin(y * Math.PI) + 40.0 * kotlin.math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
            ret += (160.0 * kotlin.math.sin(y / 12.0 * Math.PI) + 320.0 * kotlin.math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
            return ret
        }
        fun transformLon(x: Double, y: Double): Double {
            var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
            ret += (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            ret += (20.0 * kotlin.math.sin(x * Math.PI) + 40.0 * kotlin.math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
            ret += (150.0 * kotlin.math.sin(x / 12.0 * Math.PI) + 300.0 * kotlin.math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
            return ret
        }
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(gcjLon - 105.0, gcjLat - 35.0)
        var dLon = transformLon(gcjLon - 105.0, gcjLat - 35.0)
        val radLat = gcjLat / 180.0 * Math.PI
        var magic = kotlin.math.sin(radLat)
        magic = 1.0 - ee * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1.0 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * kotlin.math.cos(radLat) * Math.PI)
        return (gcjLat - dLat) to (gcjLon - dLon)
    }

    private fun String.withUnit(unit: String): String =
        if (this == "--" || endsWith(unit, ignoreCase = true)) this else "$this $unit"

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MAX_GEOFENCE_COUNT = 5
        private val RAW_COORD_REGEX = Regex("""^\s*-?\d+\.\d+\s*,\s*-?\d+\.\d+\s*$""")

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
