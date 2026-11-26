package com.chery.carplus.ui

import android.Manifest
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chery.carplus.R
import com.chery.carplus.ble.CheryBleController
import com.chery.carplus.ble.ParsedTBoxResponse
import com.chery.carplus.ble.SessionSnapshot
import com.chery.carplus.cloud.CheryDeviceBindingRequiredException
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.CheryLoginResult
import com.chery.carplus.cloud.CherySessionExpiredException
import com.chery.carplus.cloud.InGeekCloudProvisioner
import com.chery.carplus.cloud.RemoteCommand
import com.chery.carplus.cloud.RemoteCommandResult
import com.chery.carplus.cloud.VehicleSummary
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.profile.VehicleProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Consumer-facing CarPlus minimalist dark interface.
 *
 * Flow:
 * 1. First-launch User Agreement & Privacy Policy modal (persisted once accepted).
 * 2. Minimalist Welcome screen ("登录" / "创建账户") -> Login Form (Password & SMS + 0843 binding).
 * 3. Vehicle Home screen:
 *    - Header (vehicle name + chevron, battery/fuel pill + %, parking/lock status, refresh & menu icons)
 *    - 3/4 isometric Pearl White SUV visual
 *    - 4 minimalist icon-only quick actions (Lock/Unlock, Climate, Find Car Flash, Trunk/Tailgate)
 *    - 4 clean vertical menu rows: 控制 / 温度 / 地点 / 安全性和驾驶员
 *      (deliberately omitting 召唤, 设置预约, 充电统计).
 */
class MainActivity : AppCompatActivity(), CheryBleController.Listener {

    private enum class Screen {
        PRIVACY_CONSENT,
        WELCOME,
        LOGIN_FORM,
        HOME,
        SETTINGS,
        SECURITY,
        LOCATION,
        MESSAGES
    }

    enum class QuickActionId(
        val id: String,
        val label: String,
        val icon: AppIconType
    ) {
        LOCK("LOCK", "锁车/解锁", AppIconType.LOCK_CLOSED),
        CLIMATE("CLIMATE", "空调", AppIconType.FAN_BLADE),
        FLASH("FLASH", "寻车", AppIconType.BOLT_FLASH),
        TRUNK("TRUNK", "后备箱", AppIconType.TRUNK_OPEN),
        ENGINE("ENGINE", "启停", AppIconType.ENGINE_POWER),
        WINDOW("WINDOW", "车窗", AppIconType.WINDOW_OPEN),
        WINDOW_VENT("WINDOW_VENT", "车窗透气", AppIconType.WINDOW_VENT),
        SUNROOF("SUNROOF", "天窗", AppIconType.SUNROOF_OPEN),
        SUNROOF_TILT("SUNROOF_TILT", "天窗翘起", AppIconType.SUNROOF_TILT);

        companion object {
            val DEFAULT_LIST: List<QuickActionId> = listOf(LOCK, CLIMATE, FLASH, TRUNK)

            fun fromId(raw: String): QuickActionId? {
                return when (raw.trim().uppercase(Locale.US)) {
                    "TAILGATE_OPEN", "TAILGATE_CLOSE" -> TRUNK
                    "WINDOW_OPEN", "WINDOW_CLOSE" -> WINDOW
                    "SUNROOF_OPEN", "SUNROOF_CLOSE", "SUNROOF" -> SUNROOF
                    else -> values().firstOrNull { it.id == raw.trim().uppercase(Locale.US) }
                }
            }
        }
    }

    private class PullDownInterceptScrollView(
        context: Context,
        private val canStartPullDown: () -> Boolean,
        private val onDragStart: () -> Unit,
        private val onDragMove: (dy: Float, velocityY: Float) -> Unit,
        private val onDragEnd: (totalDy: Float, velocityY: Float) -> Unit
    ) : ScrollView(context) {
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        private var lastTimeMs = 0L
        private var velocityY = 0f
        private var draggingDeck = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!canStartPullDown()) return super.onInterceptTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    lastY = ev.rawY
                    lastTimeMs = SystemClock.uptimeMillis()
                    velocityY = 0f
                    draggingDeck = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (scrollY == 0 && dy > touchSlop && dy > abs(dx) * 1.15f) {
                        draggingDeck = true
                        downY = ev.rawY
                        lastY = ev.rawY
                        lastTimeMs = SystemClock.uptimeMillis()
                        onDragStart()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingDeck = false
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!draggingDeck) {
                if (canStartPullDown() && scrollY == 0 && ev.actionMasked == MotionEvent.ACTION_MOVE) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dy > touchSlop && dy > abs(dx) * 1.15f) {
                        draggingDeck = true
                        downY = ev.rawY
                        lastY = ev.rawY
                        lastTimeMs = SystemClock.uptimeMillis()
                        onDragStart()
                    }
                }
                if (!draggingDeck) return super.onTouchEvent(ev)
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastTimeMs).coerceAtLeast(1L)
                    velocityY = (ev.rawY - lastY) * 1000f / dt
                    lastY = ev.rawY
                    lastTimeMs = now
                    onDragMove(ev.rawY - downY, velocityY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingDeck = false
                    onDragEnd(ev.rawY - downY, velocityY)
                    return true
                }
            }
            return true
        }
    }

    private lateinit var profileStore: VehicleProfileStore
    private lateinit var httpClient: CheryHttpClient
    private lateinit var provisioner: InGeekCloudProvisioner
    private lateinit var bleController: CheryBleController
    private lateinit var featureSheets: VehicleFeatureSheets
    private lateinit var managementScreens: VehicleManagementScreens
    private lateinit var contentFrame: FrameLayout
    private lateinit var uiPrefs: SharedPreferences

    private var currentScreen = Screen.PRIVACY_CONSENT
    private var hasRenderedScreen = false
    private var handlingSessionExpired = false
    private var loginUsesSms = false
    private var loginBusy = false
    private var blePermissionsRequested = false
    private var provisioningJob: Job? = null
    private var provisioningActive = false
    private var lastManualBleTapMs = 0L
    private var wasBackgrounded = false
    private var smsCountdownJob: Job? = null
    private var telemetryJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private var geocodeJob: Job? = null
    private var statusHideRunnable: Runnable? = null
    private var appDialog: android.app.Dialog? = null
    private var vehicleSwitcherPopup: PopupWindow? = null
    private var dismissAppDialogSync: (() -> Unit)? = null
    private var homeRootView: View? = null
    private var settingsRootView: View? = null
    private var securityOverlayView: View? = null
    private var securitySubpageStatusText: TextView? = null
    private var locationOverlayView: View? = null
    private var locationOverlayDestroy: (() -> Unit)? = null
    private val locationAddressObservers = linkedSetOf<(String) -> Unit>()
    private var messagesOverlayView: View? = null
    private val subpageOverlayStack = ArrayDeque<Pair<View, (() -> Unit)?>>()
    private var settingsScreen: SettingsScreen? = null
    private var pendingAvatarPickCallback: ((Uri) -> Unit)? = null
    private lateinit var avatarPickerLauncher: ActivityResultLauncher<String>

    // Login form views
    private var loginMobileInput: EditText? = null
    private var loginPasswordInput: EditText? = null
    private var loginSmsInput: EditText? = null
    private var loginPasswordBlock: LinearLayout? = null
    private var loginSmsBlock: LinearLayout? = null
    private var passwordTab: TextView? = null
    private var smsTab: TextView? = null
    private var loginButton: TextView? = null
    private var loginErrorView: TextView? = null
    private var smsCodeButton: TextView? = null

    // Home views
    private var vehicleNameView: TextView? = null
    private var batteryPillView: BatteryPillView? = null
    private var energyTextView: TextView? = null
    private var plateBadgeView: TextView? = null
    private var parkingStatusView: TextView? = null
    private var channelBadgeIcon: AppIconView? = null
    private var transientToastBanner: TextView? = null
    private var homeStageFrame: FrameLayout? = null
    private var lowerOverlayContainer: PullDownInterceptScrollView? = null
    private var homeHeaderView: View? = null
    private var carHeroContainerView: FrameLayout? = null
    private var homeMenuSectionView: View? = null
    private var carVisual: VehicleVisualView? = null
    private var tpmsOverlayContainer: FrameLayout? = null
    private var tpmsFlPressure: TextView? = null
    private var tpmsFlTemp: TextView? = null
    private var tpmsFrPressure: TextView? = null
    private var tpmsFrTemp: TextView? = null
    private var tpmsRlPressure: TextView? = null
    private var tpmsRlTemp: TextView? = null
    private var tpmsRrPressure: TextView? = null
    private var tpmsRrTemp: TextView? = null
    private var quickActionRow: LinearLayout? = null
    private val quickActionIcons = mutableMapOf<QuickActionId, AppIconView>()
    private var quickDeckContainer: LinearLayout? = null
    private var quickDeckEditHeader: LinearLayout? = null
    private var quickDeckEditTitle: TextView? = null
    private var quickDeckGridHost: LinearLayout? = null
    private val quickDeckRowViews = mutableListOf<LinearLayout>()
    private var deckExpandAnimator: ValueAnimator? = null
    private var deckExpandProgress = 0f
    private var quickDeckExpanded = false
    private var quickDeckEditMode = false
    private var climateRowSubtitle: TextView? = null
    private var locationRowSubtitle: TextView? = null
    private var securityDotView: StatusDotView? = null
    private var securityRowSubtitle: TextView? = null
    private var homeFooterView: TextView? = null

    // Active sheet live views (updated if open)
    private var sheetClimateTempView: TextView? = null
    private var sheetKeyStatusView: TextView? = null
    private var sheetKeyActionButton: TextView? = null

    // Live state
    private var currentProfile: VehicleProfile? = null
    private var activeTelemetry: VehicleTelemetry? = null
    private var sessionVehiclePin = ""
    private var climateTemperatureCelsius = 24.0
    private var bleConnected = false
    private var bleNearby = false
    private var lastPairingPin = ""
    private var lastBleActionLabel = ""
    private var energyShowsRange = false
    private var cloudTripTrackEnabled: Boolean? = null
    private var lastGeocodedKey = ""
    private var lastGeocodedAddress: String? = null
    private var lastGeocodeSettled = false
    private var lastBackExitPromptMs = 0L
    private var lastCarTapTelemetryAtMs = 0L
    private var footerMetricIndex = 0
    private var footerTapCount = 0
    private var footerTapWindowStartMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        profileStore = VehicleProfileStore(this)
        uiPrefs = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        val deviceId = profileStore.defaultDeviceId()
        httpClient = CheryHttpClient(defaultDeviceId = deviceId).apply {
            onSessionExpired = { reason ->
                runOnUiThread { handleSessionExpired(reason) }
            }
        }
        provisioner = InGeekCloudProvisioner(this)
        bleController = CheryBleController(this).also { it.listener = this }
        managementScreens = VehicleManagementScreens(
            context = this,
            scope = lifecycleScope,
            httpClient = httpClient,
            profileProvider = { profileStore.getActiveProfile() },
            onPushOverlaySubpage = { view, onDestroy -> pushSubpageOverlay(view, onDestroy) },
            onPopOverlaySubpage = { popSubpageOverlay() },
            onSelectVehicleSummary = { summary -> activateVehicleFromSummary(summary) },
            onVehicleInfoSaved = { vin, plateNo, _, _ ->
                val active = profileStore.getActiveProfile()
                if (active.vin.equals(vin, ignoreCase = true)) {
                    uiPrefs.edit().putString(KEY_PLATE_NO, plateNo).apply()
                    activeTelemetry = activeTelemetry?.copy(licensePlate = plateNo)
                    refreshHomeHeaderAndRows()
                }
            },
            onVehicleUnbound = { unboundVin -> handleVehicleUnbound(unboundVin) },
            onStatusMessage = { showHomeStatus(it) },
            vehicleLiveSpecsProvider = { vin -> buildActiveVehicleLiveSpecs(vin) },
            onOpenVehicleUpgrade = { featureSheets.showVehicleUpgradeSheet() }
        )
        featureSheets = VehicleFeatureSheets(
            context = this,
            scope = lifecycleScope,
            httpClient = httpClient,
            provisioner = provisioner,
            profileProvider = { profileStore.getActiveProfile() },
            telemetryProvider = { activeTelemetry },
            cachedPinProvider = { sessionVehiclePin },
            onPinUpdated = { newPin ->
                sessionVehiclePin = newPin
                val p = profileStore.getActiveProfile()
                if (p.vin.isNotBlank()) {
                    currentProfile = profileStore.saveProfile(p.copy(controlPin = newPin), activate = true)
                }
            },
            onRefreshTelemetry = { refreshTelemetry(silent = false) },
            onTriggerCloudKeyProvision = { launchCloudProvision(profileStore.getActiveProfile(), automatic = false) },
            onScanNearbyBle = { startProximityWhenPermitted() },
            onStatusMessage = { showHomeStatus(it) },
            onLogout = { logoutToWelcome() },
            isBleConnected = { bleConnected || bleController.isSessionReady() },
            isBleNearby = { bleNearby || bleController.isVehicleNearby },
            hasLocalKeyMaterial = { hasCompleteKeyMaterial(it) },
            lastPairingPinProvider = { lastPairingPin },
            modelNameProvider = { uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty() },
            plateNoProvider = { uiPrefs.getString(KEY_PLATE_NO, "").orEmpty() },
            onDepthRecede = { receded -> applySheetDepthRecede(receded) },
            tripTrackEnabledProvider = { cloudTripTrackEnabled },
            requirePinEveryTimeProvider = { uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, false) },
            onRequirePinEveryTimeChanged = { enabled ->
                uiPrefs.edit().putBoolean(KEY_REQUIRE_PIN_EVERY_TIME, enabled).apply()
                showHomeStatus(if (enabled) "已开启每次控车验证密码" else "已关闭每次控车验证密码")
            },
            onOpenOverlaySubpage = { view, onDestroy -> pushSubpageOverlay(view, onDestroy) },
            onPopOverlaySubpage = { popSubpageOverlay() },
            onOpenRemoteAuthManagement = { featureSheets.showRemoteAuthSheet() },
            liveDistanceLabelProvider = {
                val lat = activeTelemetry?.latitude
                val lon = activeTelemetry?.longitude
                if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                    formatDistanceToVehicle(lat, lon) ?: "距离暂不可用"
                } else {
                    "距离暂不可用"
                }
            },
            liveAddressLabelProvider = { currentStreetLabel() },
            deviceLocationProvider = { getBestDeviceGcj02Location() },
            onVehiclesChanged = { handleVehiclesChanged() }
        )

        avatarPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            val cb = pendingAvatarPickCallback
            pendingAvatarPickCallback = null
            if (uri != null) {
                cb?.invoke(uri)
            }
        }

        contentFrame = FrameLayout(this).apply {
            setBackgroundColor(BG_DARK)
            clipToPadding = false
        }
        setContentView(contentFrame)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (subpageOverlayStack.isNotEmpty()) {
                    popSubpageOverlay()
                    return
                }
                when (currentScreen) {
                    Screen.LOGIN_FORM -> showWelcomeScreen()
                    Screen.SETTINGS -> {
                        if (settingsScreen?.handleBackPressed() != true) {
                            showHomeScreen()
                        }
                    }
                    Screen.SECURITY -> returnFromSecurityToHome()
                    Screen.LOCATION -> returnFromLocationToHome()
                    Screen.MESSAGES -> returnFromMessagesToHome()
                    Screen.HOME -> {
                        if (quickDeckExpanded || deckExpandProgress > 0.05f) {
                            animateDeckTo(false)
                        } else {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastBackExitPromptMs <= 2_000L) {
                                finish()
                            } else {
                                lastBackExitPromptMs = now
                                showHomeStatus("再按一次退出 CarPlus")
                            }
                        }
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        routeInitialScreen()
    }

    private fun routeInitialScreen() {
        val privacyAccepted = uiPrefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)
        if (!privacyAccepted) {
            showPrivacyConsentScreen()
            return
        }
        val active = profileStore.getActiveProfile()
        if (active.accessToken.isNotBlank()) {
            currentProfile = active
            sessionVehiclePin = active.controlPin
            showHomeScreen()
        } else {
            showWelcomeScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        carVisual?.onHostResume()
        if (currentScreen == Screen.HOME) {
            if (wasBackgrounded && !quickDeckExpanded) {
                carVisual?.playEntranceAnimation()
            }
            wasBackgrounded = false
            startProximityWhenPermitted()
            startPeriodicHomeRefresh()
            refreshTelemetry(silent = true)
        }
    }

    override fun onPause() {
        stopPeriodicHomeRefresh()
        carVisual?.onHostPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        wasBackgrounded = true
    }

    override fun onDestroy() {
        smsCountdownJob?.cancel()
        telemetryJob?.cancel()
        periodicRefreshJob?.cancel()
        geocodeJob?.cancel()
        provisioningJob?.cancel()
        deckExpandAnimator?.cancel()
        stopStatusAutoHide()
        vehicleSwitcherPopup?.dismiss()
        vehicleSwitcherPopup = null
        dismissAppDialogImmediately()
        clearSubpageOverlayStack()
        homeRootView = null
        settingsRootView = null
        securityOverlayView = null
        securitySubpageStatusText = null
        locationOverlayDestroy?.invoke()
        locationOverlayDestroy = null
        locationOverlayView = null
        messagesOverlayView = null
        settingsScreen = null
        if (::featureSheets.isInitialized) featureSheets.dismissAll()
        if (::managementScreens.isInitialized) managementScreens.dismissAll()
        bleController.close()
        super.onDestroy()
    }

    private fun startPeriodicHomeRefresh() {
        if (periodicRefreshJob?.isActive == true) return
        periodicRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000L)
                if (currentScreen == Screen.HOME) {
                    refreshTelemetry(silent = true)
                }
            }
        }
    }

    private fun stopPeriodicHomeRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    private fun dismissAppDialogImmediately() {
        val syncDismiss = dismissAppDialogSync
        dismissAppDialogSync = null
        if (syncDismiss != null) {
            syncDismiss()
        } else {
            appDialog?.dismiss()
        }
        appDialog = null
    }

    private fun configureSystemBars(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
    }

    private fun applySheetDepthRecede(receded: Boolean) {
        // Avoid HWUI WebView DrawGLFunctor clip mismatch when Home 3D WebView is mounted
        if (currentScreen == Screen.HOME && receded) return
        if (!receded && contentFrame.scaleX == 1f && contentFrame.scaleY == 1f && contentFrame.alpha == 1f) return
        MotionSpec.animateDepthRecede(contentFrame, receded)
    }

    private fun presentScreen(target: Screen, rootView: View) {
        val direction = if (!hasRenderedScreen) {
            0
        } else {
            target.ordinal.compareTo(currentScreen.ordinal)
        }
        hasRenderedScreen = true
        currentScreen = target
        if (target == Screen.HOME) {
            // Mount Home directly at identity transform/alpha so 3D WebView is never clipped by ancestor layer animation
            contentFrame.animate().cancel()
            contentFrame.scaleX = 1f
            contentFrame.scaleY = 1f
            contentFrame.alpha = 1f
            contentFrame.removeAllViews()
            rootView.alpha = 1f
            rootView.translationX = 0f
            rootView.translationY = 0f
            contentFrame.addView(rootView, matchParent())
        } else {
            MotionSpec.transitionContainer(contentFrame, rootView, direction)
        }
    }

    // =========================================================================
    // Screen 1: First-Launch User Agreement & Privacy Policy Modal
    // =========================================================================

    private fun showPrivacyConsentScreen() {
        stopStatusAutoHide()
        configureSystemBars(BG_BLACK)
        contentFrame.setBackgroundColor(BG_BLACK)

        val root = FrameLayout(this).apply {
            setBackgroundColor(BG_BLACK)
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        presentScreen(Screen.PRIVACY_CONSENT, root)

        val cardScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_MODAL, 14f, Color.rgb(42, 42, 46), 0.5f)
            setPadding(dp(26), dp(28), dp(26), dp(26))
        }
        cardScroll.addView(card, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(
            cardScroll,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER)
        )

        card.addView(text("用户协议与隐私政策", 20f, TEXT_PRIMARY, Typeface.BOLD).apply {
            letterSpacing = 0.01f
        })

        card.addView(
            text(
                "您的隐私对我们来说至关重要。我们的隐私政策旨在提升透明度，以易于阅读和浏览的形式，帮助您了解我们的隐私保护措施。请您仔细阅读以下链接内容，了解我们如何收集、使用、分享和保护您的信息，以便为您提供流畅的车辆控制与数字钥匙使用体验。",
                14f,
                TEXT_BODY
            ).apply {
                setLineSpacing(0f, 1.45f)
                layoutParams = lp(top = dp(18))
            }
        )

        val linksBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(linksBox, lp(top = dp(16)))

        linksBox.addView(policyLink("隐私声明") {
            showPolicyInfoDialog(
                "隐私声明",
                "本应用仅在获得您明确授权后，通过加密链路访问您的车控账户会话与车辆状态数据，并在本机安全存储数字钥匙材料以支持近场蓝牙直连控车。我们不会向无关第三方出售或共享您的个人与车辆数据。"
            )
        })
        linksBox.addView(policyLink("应用程序接入的第三方SDK/API") {
            showPolicyInfoDialog(
                "应用程序接入的第三方SDK/API",
                "本应用使用标准 Android 蓝牙 GATT API 与授权车辆云端通信接口完成账号登录、车况查询、远程控车及数字钥匙签发解封，不集成第三方广告或数据追踪 SDK。"
            )
        }, lp(top = dp(4)))
        linksBox.addView(policyLink("使用条款") {
            showPolicyInfoDialog(
                "使用条款",
                "请在确保车辆处于安全环境及授权使用范围内执行远程控制或蓝牙数字钥匙操作。点击“同意并继续”即表示您已阅读并同意遵守上述隐私与使用条款。"
            )
        }, lp(top = dp(4)))

        card.addView(
            text(
                "在确认完整阅读上述内容后，如您接受上述条款，请点击“同意并继续”按钮；如您不接受上述条款，请点击“不同意并退出”按钮。",
                14f,
                TEXT_BODY
            ).apply {
                setLineSpacing(0f, 1.45f)
                layoutParams = lp(top = dp(18))
            }
        )

        val agreeBtn = primaryActionButton("同意并继续") {
            uiPrefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply()
            routeInitialScreen()
        }
        card.addView(agreeBtn, lp(top = dp(24), height = dp(48)))

        val declineBtn = secondaryActionButton("不同意并退出") {
            finishAffinity()
        }
        card.addView(declineBtn, lp(top = dp(14), height = dp(48)))
    }

    private fun policyLink(label: String, onClick: () -> Unit): TextView =
        text(label, 14f, LINK_BLUE).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(3), 0, dp(3))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun showPolicyInfoDialog(title: String, body: String) {
        appDialog?.dismiss()
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_MODAL, 14f, Color.rgb(46, 46, 52), 0.5f)
            setPadding(dp(24), dp(24), dp(24), dp(22))
        }
        container.addView(text(title, 18f, TEXT_PRIMARY, Typeface.BOLD))
        container.addView(text(body, 14f, TEXT_BODY).apply {
            setLineSpacing(0f, 1.5f)
            layoutParams = lp(top = dp(14))
        })
        container.addView(primaryActionButton("我知道了") { dialog.dismiss() }, lp(top = dp(22), height = dp(44)))

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.75f)
            setLayout((resources.displayMetrics.widthPixels * 0.86f).roundToInt(), WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.setOnDismissListener { if (appDialog === dialog) appDialog = null }
        appDialog = dialog
        dialog.show()
        MotionSpec.animateModalEnter(container)
    }

    // =========================================================================
    // Screen 2A: Post-Consent Minimalist Welcome Screen
    // =========================================================================

    private fun showWelcomeScreen() {
        stopStatusAutoHide()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)

        val root = FrameLayout(this).apply {
            setBackgroundColor(BG_HOME)
        }
        presentScreen(Screen.WELCOME, root)

        // Centered oversized monochrome CHERY vector emblem only (no CarPlus text)
        val screenW = resources.displayMetrics.widthPixels
        val logoW = (screenW * 0.68f).roundToInt().coerceIn(dp(200), dp(264))
        val logoH = (logoW / 3.213f).roundToInt().coerceAtLeast(dp(64))
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.ic_chery_adaptive_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "CHERY Logo"
        }
        root.addView(
            logoView,
            FrameLayout.LayoutParams(logoW, logoH, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.31f).roundToInt()
            }
        )

        val bottomStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(42))
        }
        root.addView(
            bottomStack,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
        )

        val loginCta = AppComponents.createActionButton(this, "登录", ButtonStyle.PRIMARY) {
            showLoginFormScreen(startWithSms = false)
        }
        bottomStack.addView(loginCta, lp(height = dp(48)))

        val createAccountBtn = text("创建账户", 14.5f, ThemeTokens.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.96f, haptic = false)
            setOnClickListener { showCreateAccountDialog() }
        }
        bottomStack.addView(createAccountBtn, lp(top = dp(12)))
    }

    private fun showCreateAccountDialog() {
        appDialog?.dismiss()
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_MODAL, 16f, Color.rgb(46, 46, 52), 0.5f)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        sheet.addView(text("创建账户", 19f, TEXT_PRIMARY, Typeface.BOLD))
        sheet.addView(
            text(
                "请使用已在官方车控平台完成实名与车辆绑定的手机号登录。支持账号密码或短信验证码快捷登录，登录后将自动同步您的车辆与蓝牙数字钥匙。",
                14f,
                TEXT_BODY
            ).apply {
                setLineSpacing(0f, 1.48f)
                layoutParams = lp(top = dp(12))
            }
        )
        sheet.addView(
            primaryActionButton("使用手机号短信登录") {
                dialog.dismiss()
                showLoginFormScreen(startWithSms = true)
            },
            lp(top = dp(22), height = dp(46))
        )
        sheet.addView(
            secondaryActionButton("返回") { dialog.dismiss() },
            lp(top = dp(12), height = dp(46))
        )

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.72f)
            setLayout((resources.displayMetrics.widthPixels * 0.88f).roundToInt(), WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.setOnDismissListener { if (appDialog === dialog) appDialog = null }
        appDialog = dialog
        dialog.show()
        MotionSpec.animateModalEnter(sheet)
    }

    // =========================================================================
    // Screen 2B: CarPlus Dark Login Form (Password & SMS + 0843 Device Binding)
    // =========================================================================

    private fun showLoginFormScreen(startWithSms: Boolean) {
        stopStatusAutoHide()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_HOME)
        }
        root.addView(
            AppComponents.createTopBar(
                context = this,
                title = "",
                onBack = { showWelcomeScreen() }
            ),
            lp()
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(BG_HOME)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(36))
        }
        scroll.addView(page, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        presentScreen(Screen.LOGIN_FORM, root)

        page.addView(text("登录", 26f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD))
        page.addView(
            text("登录后自动同步车辆与数字钥匙", 13f, ThemeTokens.TEXT_SUB),
            lp(top = dp(6))
        )

        // Unified sliding segmented switcher matching DESIGN_SYSTEM.md v2.6
        passwordTab = null
        smsTab = null
        val segmented = MotionSpec.createSlidingSegmentedBar(
            context = this,
            labels = listOf("密码登录", "验证码登录"),
            initial = if (startWithSms) 1 else 0,
            bgColor = ThemeTokens.BG_SURFACE,
            pillColor = ThemeTokens.BTN_SECONDARY,
            activeTextColor = ThemeTokens.TEXT_PRIMARY,
            inactiveTextColor = ThemeTokens.TEXT_SUB
        ) { idx ->
            setLoginMode(idx == 1)
        }
        page.addView(segmented, lp(top = dp(26)))

        // Borderless underline inputs matching Design System v2.6
        page.addView(text("手机号", 12.5f, ThemeTokens.TEXT_SUB), lp(top = dp(28)))
        val (mobileBox, mobileEdit) = AppComponents.createUnderlineInput(
            context = this,
            hint = "请输入 11 位手机号",
            inputType = InputType.TYPE_CLASS_PHONE,
            maxLength = 11
        )
        mobileEdit.imeOptions = EditorInfo.IME_ACTION_NEXT
        loginMobileInput = mobileEdit
        page.addView(mobileBox, lp(top = dp(2)))

        loginPasswordBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("密码", 12.5f, ThemeTokens.TEXT_SUB), lp(top = dp(22)))
            val (pwdBox, pwdEdit) = AppComponents.createUnderlineInput(
                context = this@MainActivity,
                hint = "请输入登录密码",
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            )
            pwdEdit.transformationMethod = PasswordTransformationMethod.getInstance()
            pwdEdit.imeOptions = EditorInfo.IME_ACTION_DONE
            loginPasswordInput = pwdEdit
            addView(pwdBox, lp(top = dp(2)))
        }
        page.addView(loginPasswordBlock, lp())

        loginSmsBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(text("短信验证码", 12.5f, ThemeTokens.TEXT_SUB), lp(top = dp(22)))
            val smsUnderline = View(this@MainActivity).apply {
                setBackgroundColor(ThemeTokens.DIVIDER)
            }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val smsEdit = EditText(this@MainActivity).apply {
                hint = "请输入验证码"
                setHintTextColor(Color.parseColor("#5C606A"))
                setTextColor(ThemeTokens.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, dp(10), 0, dp(10))
                inputType = InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                imeOptions = EditorInfo.IME_ACTION_DONE
                setOnFocusChangeListener { _, hasFocus ->
                    smsUnderline.setBackgroundColor(
                        if (hasFocus) ThemeTokens.ACCENT_ACTIVE else ThemeTokens.DIVIDER
                    )
                }
            }
            loginSmsInput = smsEdit
            row.addView(smsEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            smsCodeButton = text("获取验证码", 13.5f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(12), dp(8), dp(2), dp(8))
                isClickable = true
                isFocusable = true
                MotionSpec.attachPressPhysics(this, pressedScale = 0.94f, haptic = false)
                setOnClickListener { sendLoginSmsCode() }
            }
            row.addView(smsCodeButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(row, lp(top = dp(2)))
            addView(smsUnderline, lp(height = dpF(0.8f).roundToInt().coerceAtLeast(1)))
        }
        page.addView(loginSmsBlock, lp())

        loginErrorView = text("", 13f, ThemeTokens.DANGER).apply {
            visibility = View.GONE
            layoutParams = lp(top = dp(14))
        }
        page.addView(loginErrorView)

        loginButton = AppComponents.createActionButton(this, "登录", ButtonStyle.PRIMARY) {
            submitLogin()
        }
        page.addView(loginButton, lp(top = dp(28), height = dp(48)))

        val forgotBtn = text("忘记密码？短信重置登录密码", 13.5f, ThemeTokens.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(0, dp(10), 0, dp(8))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.96f, haptic = false)
            setOnClickListener {
                featureSheets.showResetPasswordBySmsModal(
                    loginMobileInput?.text?.toString()?.trim().orEmpty()
                )
            }
        }
        page.addView(forgotBtn, lp(top = dp(10)))

        setLoginMode(startWithSms)
    }

    private fun setLoginMode(useSms: Boolean) {
        loginUsesSms = useSms
        passwordTab?.apply {
            setTextColor(if (!useSms) TEXT_PRIMARY else TEXT_SECONDARY)
            typeface = Typeface.create("sans-serif", if (!useSms) Typeface.BOLD else Typeface.NORMAL)
            background = if (!useSms) rounded(BTN_SECONDARY, 6f) else null
        }
        smsTab?.apply {
            setTextColor(if (useSms) TEXT_PRIMARY else TEXT_SECONDARY)
            typeface = Typeface.create("sans-serif", if (useSms) Typeface.BOLD else Typeface.NORMAL)
            background = if (useSms) rounded(BTN_SECONDARY, 6f) else null
        }
        loginPasswordBlock?.visibility = if (useSms) View.GONE else View.VISIBLE
        loginSmsBlock?.visibility = if (useSms) View.VISIBLE else View.GONE
        hideLoginError()
    }

    private fun sendLoginSmsCode() {
        val mobile = loginMobileInput?.text?.toString()?.trim().orEmpty()
        if (!isValidMobile(mobile)) {
            showLoginError("请输入正确的 11 位手机号")
            return
        }
        if (smsCountdownJob?.isActive == true) return
        smsCodeButton?.apply {
            isEnabled = false
            alpha = 0.55f
            text = "发送中..."
        }
        lifecycleScope.launch {
            val sent = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.sendSmsCode(mobile, type = CheryHttpClient.SMS_TYPE_LOGIN)
                }
            }.getOrDefault(false)
            if (sent) {
                Toast.makeText(this@MainActivity, "验证码已发送", Toast.LENGTH_SHORT).show()
                startSmsCountdown()
            } else {
                smsCodeButton?.apply {
                    isEnabled = true
                    alpha = 1f
                    text = "获取验证码"
                }
                showLoginError("验证码发送失败，请稍后重试")
            }
        }
    }

    private fun startSmsCountdown() {
        smsCountdownJob?.cancel()
        smsCountdownJob = lifecycleScope.launch {
            for (remaining in 60 downTo 1) {
                smsCodeButton?.apply {
                    isEnabled = false
                    alpha = 0.55f
                    text = "${remaining}s"
                }
                delay(1_000L)
            }
            smsCodeButton?.apply {
                isEnabled = true
                alpha = 1f
                text = "获取验证码"
            }
        }
    }

    private fun submitLogin() {
        if (loginBusy) return
        val mobile = loginMobileInput?.text?.toString()?.trim().orEmpty()
        if (!isValidMobile(mobile)) {
            showLoginError("请输入正确的 11 位手机号")
            return
        }
        val secret = if (loginUsesSms) {
            loginSmsInput?.text?.toString()?.trim().orEmpty()
        } else {
            loginPasswordInput?.text?.toString().orEmpty()
        }
        if (secret.isBlank()) {
            showLoginError(if (loginUsesSms) "请输入短信验证码" else "请输入登录密码")
            return
        }
        performLogin(mobile, secret, loginUsesSms)
    }

    private fun performLogin(mobile: String, secret: String, bySms: Boolean) {
        if (loginBusy) return
        setLoginBusy(true, "正在登录...")
        hideLoginError()
        lifecycleScope.launch {
            try {
                val deviceId = profileStore.defaultDeviceId()
                val login = withContext(Dispatchers.IO) {
                    if (bySms) {
                        httpClient.loginWithSms(
                            mobileNo = mobile,
                            smsCode = secret,
                            deviceId = deviceId
                        )
                    } else {
                        httpClient.loginWithPassword(
                            mobileNo = mobile,
                            password = secret,
                            deviceId = deviceId
                        )
                    }
                }
                completeLogin(login, mobile, deviceId)
            } catch (binding: CheryDeviceBindingRequiredException) {
                setLoginBusy(false)
                showLoginError("新设备需要完成短信验证")
                showDeviceBindingSheet(mobile, bySms, binding)
            } catch (error: Throwable) {
                setLoginBusy(false)
                showLoginError(loginErrorMessage(error))
            }
        }
    }

    private fun completeLogin(login: CheryLoginResult, mobile: String, deviceId: String) {
        lifecycleScope.launch {
            try {
                val user = login.userInfo ?: withContext(Dispatchers.IO) {
                    httpClient.fetchUserInfo(login.accessToken)
                }
                val vehicles = login.vehicles.ifEmpty {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchVehicleList(login.accessToken)
                    }
                }
                val primary = vehicles.firstOrNull { it.selected == "1" && it.vin.isNotBlank() }
                    ?: vehicles.firstOrNull { it.vin.isNotBlank() }
                val userId = firstNonBlank(user.userId, login.accountId)
                if (userId.isBlank()) throw IllegalStateException("账号缺少用户标识")
                val resolvedMobile = firstNonBlank(user.telephone, mobile)

                val profile = VehicleProfile(
                    vin = primary?.vin?.trim()?.uppercase(Locale.US).orEmpty(),
                    userId = userId,
                    mobileNo = resolvedMobile,
                    bleName = primary?.bleName.orEmpty(),
                    bleMac = primary?.bleMac.orEmpty(),
                    deviceId = deviceId,
                    accountType = primary?.accountType ?: 0,
                    rootKeyHex = "",
                    infoHex = "",
                    edfHex = "",
                    accessToken = login.accessToken,
                    sdkToken = login.sdkToken
                )
                val saved = withContext(Dispatchers.IO) {
                    profileStore.saveProfile(profile, activate = true)
                }
                managementScreens.rememberVehicleSummaries(vehicles, saved.accessToken)
                if (primary != null) {
                    uiPrefs.edit()
                        .putString(KEY_MODEL_NAME, vehicleModelName(primary))
                        .putString(KEY_PLATE_NO, primary.plateNo)
                        .apply()
                } else {
                    uiPrefs.edit()
                        .remove(KEY_MODEL_NAME)
                        .remove(KEY_PLATE_NO)
                        .apply()
                }
                cloudTripTrackEnabled = primary?.isTracksOpen ?: user.isTracksOpen
                currentProfile = saved
                sessionVehiclePin = saved.controlPin
                smsCountdownJob?.cancel()
                setLoginBusy(false)
                showHomeScreen()
                if (saved.vin.isNotBlank()) {
                    launchCloudProvision(saved, automatic = true)
                }
            } catch (error: Throwable) {
                setLoginBusy(false)
                showLoginError("车辆信息同步失败：${error.message ?: "请稍后重试"}")
            }
        }
    }

    private fun showDeviceBindingSheet(
        mobile: String,
        bySms: Boolean,
        binding: CheryDeviceBindingRequiredException
    ) {
        val method = if (bySms) {
            CheryHttpClient.LOGIN_METHOD_SMS
        } else {
            CheryHttpClient.LOGIN_METHOD_PASSWORD
        }
        val promptMsg = binding.message.orEmpty().ifBlank {
            "检测到新设备登录，已向 ${binding.telephone.ifBlank { maskMobile(mobile) }} 发送换机绑定验证码，请输入刚刚收到的短信验证码。"
        }
        showBottomSheet("新设备短信验证") { sheet, dialog ->
            sheet.addView(text(promptMsg, 13f, TEXT_SECONDARY).apply {
                setLineSpacing(0f, 1.45f)
                layoutParams = lp(top = dp(8))
            })
            val codeInput = styledInput("请输入换机绑定短信验证码", InputType.TYPE_CLASS_NUMBER).apply {
                imeOptions = EditorInfo.IME_ACTION_DONE
                layoutParams = lp(top = dp(16), height = dp(50))
            }
            sheet.addView(codeInput)

            val resendBtn = text("未收到？重新发送换机绑定短信", 13f, LINK_BLUE).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    isEnabled = false
                    alpha = 0.55f
                    text = "正在重新发送..."
                    lifecycleScope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.requestDeviceBindSmsDetailed(mobile, method)
                            }
                        }.getOrNull()
                        isEnabled = true
                        alpha = 1f
                        text = "未收到？重新发送换机绑定短信"
                        Toast.makeText(
                            this@MainActivity,
                            if (res?.success == true) "换机绑定验证码已重新发送"
                            else res?.message.orEmpty().ifBlank { "重新发送失败，请稍后重试" },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            sheet.addView(resendBtn, lp(top = dp(4)))

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sheet.addView(buttons, lp(top = dp(14)))
            buttons.addView(secondaryActionButton("取消") { dialog.dismiss() }, weightedLp(0, dp(46)))
            buttons.addView(primaryActionButton("验证并登录") {
                val code = codeInput.text.toString().trim()
                if (code.isBlank()) {
                    Toast.makeText(this, "请输入短信验证码", Toast.LENGTH_SHORT).show()
                    return@primaryActionButton
                }
                dialog.dismiss()
                setLoginBusy(true, "正在绑定新设备...")
                lifecycleScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.confirmDeviceBindSmsDetailed(
                                loginName = mobile,
                                validateCode = code,
                                loginMethod = method,
                                deviceId = profileStore.defaultDeviceId()
                            )
                        }
                    }.getOrNull()
                    if (result?.success == true) {
                        loginBusy = false
                        if (bySms) {
                            // 108 succeeded; try logging in with original login SMS or prompt fresh 920 code
                            val originalSms = loginSmsInput?.text?.toString()?.trim().orEmpty()
                            val attemptCode = originalSms.ifBlank { code }
                            performLoginAfterDeviceBindSms(mobile, attemptCode)
                        } else {
                            val password = loginPasswordInput?.text?.toString().orEmpty()
                            performLogin(mobile, password, bySms = false)
                        }
                    } else {
                        setLoginBusy(false)
                        showLoginError(result?.message.orEmpty().ifBlank { "新设备验证失败，请核对验证码后重试" })
                    }
                }
            }, weightedLp(dp(10), dp(46)))

            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            codeInput.requestFocus()
        }
    }

    private fun performLoginAfterDeviceBindSms(mobile: String, smsCode: String) {
        if (loginBusy) return
        setLoginBusy(true, "设备已绑定，正在登录...")
        hideLoginError()
        lifecycleScope.launch {
            try {
                val deviceId = profileStore.defaultDeviceId()
                val login = withContext(Dispatchers.IO) {
                    httpClient.loginWithSms(
                        mobileNo = mobile,
                        smsCode = smsCode,
                        deviceId = deviceId
                    )
                }
                completeLogin(login, mobile, deviceId)
            } catch (error: Throwable) {
                setLoginBusy(false)
                smsCountdownJob?.cancel()
                smsCodeButton?.apply {
                    isEnabled = true
                    alpha = 1f
                    text = "获取验证码"
                }
                loginSmsInput?.setText("")
                showLoginError("新设备已绑定成功！请重新点击“获取验证码”并登录（${error.message.orEmpty().take(60)}）")
            }
        }
    }

    private fun setLoginBusy(busy: Boolean, label: String = "登录") {
        loginBusy = busy
        loginButton?.apply {
            isEnabled = !busy
            alpha = if (busy) 0.65f else 1f
            text = label
        }
        loginMobileInput?.isEnabled = !busy
        loginPasswordInput?.isEnabled = !busy
        loginSmsInput?.isEnabled = !busy
        passwordTab?.isEnabled = !busy
        smsTab?.isEnabled = !busy
        smsCodeButton?.isEnabled = !busy && smsCountdownJob?.isActive != true
    }

    private fun showLoginError(message: String) {
        loginErrorView?.apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    private fun hideLoginError() {
        loginErrorView?.visibility = View.GONE
    }

    private fun loginErrorMessage(error: Throwable): String {
        val raw = error.message.orEmpty().trim()
        return when {
            raw.isBlank() -> "登录失败，请检查网络后重试"
            raw.contains("0843") -> "新设备需要完成短信验证，请输入换机绑定验证码"
            else -> raw.take(160)
        }
    }

    // =========================================================================
    // Screen 3: Post-Login Vehicle Home Screen
    // =========================================================================

    private fun showHomeScreen() {
        val existingHome = homeRootView
        if (existingHome != null && currentScreen == Screen.SETTINGS && existingHome.parent === contentFrame) {
            returnFromSettingsToHome()
            return
        }

        settingsScreen = null
        settingsRootView = null
        deckExpandAnimator?.cancel()
        deckExpandProgress = 0f
        quickDeckExpanded = false
        quickDeckEditMode = false
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        bleConnected = bleController.isSessionReady()
        bleNearby = bleController.isVehicleNearby
        activeTelemetry = null
        geocodeJob?.cancel()
        lastGeocodedKey = ""
        lastGeocodedAddress = null
        lastGeocodeSettled = false

        val profile = profileStore.getActiveProfile().also { currentProfile = it }
        if (profile.controlPin.isNotBlank()) sessionVehiclePin = profile.controlPin

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(BG_HOME)
        }
        homeRootView = rootFrame
        presentScreen(Screen.HOME, rootFrame)

        val homeShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_HOME)
            setPadding(0, dp(12), 0, 0)
        }
        rootFrame.addView(homeShell, matchParent())

        // 1. Top Header (Left: Vehicle Name / Fuel Bar + % + Plate / Status + BLE/Cloud badge; Right: Message + Settings)
        buildHomeHeader(homeShell)

        val hasBoundVehicle = profile.vin.isNotBlank() || profileStore.listProfiles().any { it.vin.isNotBlank() }
        if (!hasBoundVehicle) {
            vehicleNameView?.text = "未绑定车辆"
            batteryPillView?.visibility = View.GONE
            energyTextView?.visibility = View.GONE
            plateBadgeView?.visibility = View.GONE
            parkingStatusView?.text = "请先绑定车辆"
            carVisual = null
            buildEmptyGarageOnboarding(homeShell)

            transientToastBanner = text("", 13f, TEXT_PRIMARY).apply {
                gravity = Gravity.CENTER
                visibility = View.GONE
                background = rounded(Color.argb(235, 34, 37, 43), 20f, Color.rgb(60, 64, 74), 0.7f)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                elevation = dp(8).toFloat()
            }
            rootFrame.addView(
                transientToastBanner,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(26)
                    marginStart = dp(24)
                    marginEnd = dp(24)
                }
            )
            return
        }

        // 2. Stage Frame: Fixed-height 3D WebView (ZERO resize!) + Expanded Control Deck + Overlapping Collapsible Lower Sheet
        val stageFrame = FrameLayout(this).apply {
            setBackgroundColor(BG_HOME)
            clipChildren = true
        }
        homeStageFrame = stageFrame
        homeShell.addView(stageFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val expandedStageH = computeExpandedStageHeight()
        val collapsedTop = dp(248)

        val carHeroContainer = FrameLayout(this).apply {
            setBackgroundColor(BG_HOME)
        }
        carHeroContainerView = carHeroContainer
        carVisual = VehicleVisualView(this).apply {
            modelKey = uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty().ifBlank { "arrizo8" }
            vehicleLocked = true
            setCollapsedVisibleRatio(collapsedTop.toFloat() / expandedStageH.toFloat())
            isClickable = true
            isFocusable = true
            contentDescription = "3D 车辆视图，单击闪灯，双击切换顶部控制视图"
            setOnClickListener {
                AppHaptics.perform(this, HapticLevel.MEDIUM_CONFIRM)
                flashLights()
            }
            onDoubleTapListener = {
                AppHaptics.perform(this, HapticLevel.MEDIUM_CONFIRM)
                animateDeckTo(!(quickDeckExpanded || deckExpandProgress >= 0.5f))
            }
        }
        carHeroContainer.addView(carVisual, matchParent())
        buildTpmsOverlay(carHeroContainer)
        stageFrame.addView(
            carHeroContainer,
            FrameLayout.LayoutParams(MATCH_PARENT, expandedStageH, Gravity.TOP)
        )

        // 3. Expanded 12-action Control Deck (lives directly below fixed-height car stage; NO bottom handle bar!)
        buildExpandableQuickDeck(stageFrame, expandedStageH)

        // 4. Collapsible Lower Overlay (Quick Row + subtle pull hint + 3 Menu Rows + Cycling Footer)
        val dragRangePx = dpF(165f)
        val expandThresholdPx = dpF(68f)
        val lowerScroll = PullDownInterceptScrollView(
            context = this,
            canStartPullDown = { !quickDeckExpanded },
            onDragStart = {
                deckExpandAnimator?.cancel()
                updateTpmsBadges(activeTelemetry)
                renderQuickDeckGrid(animateLabels = false)
            },
            onDragMove = { dy, _ ->
                val p = (dy / dragRangePx).coerceIn(0f, 1f)
                applyDeckExpandProgress(p)
            },
            onDragEnd = { totalDy, velocityY ->
                val shouldExpand = totalDy > expandThresholdPx || velocityY > 900f
                if (shouldExpand) {
                    AppHaptics.perform(homeStageFrame, HapticLevel.MEDIUM_CONFIRM)
                }
                animateDeckTo(shouldExpand)
            }
        ).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(BG_HOME)
        }
        lowerOverlayContainer = lowerScroll

        val lowerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_HOME)
            setPadding(0, 0, 0, dp(28))
        }
        lowerScroll.addView(lowerContent, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        buildIconQuickActionRow(lowerContent)
        // Subtle minimalist pull-down affordance bar (tappable or draggable; disappears cleanly on expand)
        val pullHintStrip = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "展开快捷控制面板"
            setOnClickListener {
                AppHaptics.perform(homeStageFrame, HapticLevel.MEDIUM_CONFIRM)
                animateDeckTo(true)
            }
        }
        val pullHintBar = View(this).apply {
            background = rounded(Color.argb(56, 255, 255, 255), 2f)
        }
        pullHintStrip.addView(pullHintBar, FrameLayout.LayoutParams(dp(36), dpF(3f).roundToInt(), Gravity.CENTER))
        lowerContent.addView(pullHintStrip, lp(height = dp(18)))

        buildMenuRows(lowerContent)
        buildHomeFooter(lowerContent)

        stageFrame.addView(
            lowerScroll,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                topMargin = collapsedTop
            }
        )

        // Floating transient feedback pill (hidden when idle so Screen 3 stays clean)
        transientToastBanner = text("", 13f, TEXT_PRIMARY).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = rounded(Color.argb(235, 34, 37, 43), 20f, Color.rgb(60, 64, 74), 0.7f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            elevation = dp(8).toFloat()
        }
        rootFrame.addView(
            transientToastBanner,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(26)
                marginStart = dp(24)
                marginEnd = dp(24)
            }
        )

        refreshHomeHeaderAndRows()
        startProximityWhenPermitted()
        startPeriodicHomeRefresh()
        syncCloudVehicleMeta()
        refreshTelemetry(silent = true)
    }

    private fun syncCloudVehicleMeta() {
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank() || profile.accessToken.isBlank()) return
        lifecycleScope.launch {
            val vehicles = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchVehicleList(profile.accessToken)
                }
            }.getOrNull() ?: return@launch
            val current = profileStore.getActiveProfile()
            if (current.accessToken != profile.accessToken ||
                !current.vin.equals(profile.vin, ignoreCase = true)) return@launch
            managementScreens.rememberVehicleSummaries(vehicles, profile.accessToken)
            managementScreens.refreshOpenVehicleInfo()
            val matched = vehicles.firstOrNull { it.vin.equals(profile.vin, ignoreCase = true) }
                ?: vehicles.firstOrNull()
            matched?.isTracksOpen?.let { cloudTripTrackEnabled = it }
        }
    }

    private fun buildEmptyGarageOnboarding(parent: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(64))
        }
        val carIcon = AppIconView(this, AppIconType.CAR_CONTROLS, TEXT_SECONDARY)
        box.addView(carIcon, LinearLayout.LayoutParams(dp(48), dp(48)))
        box.addView(
            text("暂无绑定车辆", 19f, TEXT_PRIMARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                layoutParams = lp(top = dp(18))
            }
        )
        box.addView(
            text("绑定车辆后即可开启远程车控、实时定位与数字钥匙", 13.5f, TEXT_SECONDARY).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.45f)
                layoutParams = lp(top = dp(8))
            }
        )
        val bindBtn = AppComponents.createActionButton(this, "绑定车辆", ButtonStyle.PRIMARY) {
            openAddVehicleSubpage()
        }
        box.addView(
            bindBtn,
            LinearLayout.LayoutParams(dp(200), dp(46)).apply {
                topMargin = dp(26)
            }
        )
        parent.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun openVehicleManagementSubpage() {
        managementScreens.openMyVehiclesScreen()
    }

    private fun handleVehiclesChanged() {
        val activeProfile = profileStore.getActiveProfile()
        if (activeProfile.accessToken.isBlank()) return
        lifecycleScope.launch {
            val cloudVehicles = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchVehicleList(activeProfile.accessToken)
                }
            }.getOrNull()?.filter { it.vin.isNotBlank() } ?: return@launch

            if (cloudVehicles.isEmpty()) {
                withContext(Dispatchers.IO) {
                    profileStore.clearAllProfiles()
                    profileStore.saveProfile(
                        VehicleProfile(
                            vin = "",
                            userId = activeProfile.userId,
                            mobileNo = activeProfile.mobileNo,
                            bleName = "",
                            bleMac = "",
                            deviceId = activeProfile.deviceId.ifBlank { profileStore.defaultDeviceId() },
                            accountType = 0,
                            rootKeyHex = "",
                            infoHex = "",
                            edfHex = "",
                            accessToken = activeProfile.accessToken,
                            sdkToken = activeProfile.sdkToken,
                            controlPin = ""
                        ),
                        activate = true
                    )
                }
                uiPrefs.edit()
                    .remove(KEY_MODEL_NAME)
                    .remove(KEY_PLATE_NO)
                    .apply()
                bleController.stopAutoProximityWatch()
                bleController.disconnect()
                bleConnected = false
                bleNearby = false
                currentProfile = profileStore.getActiveProfile()
                activeTelemetry = null
                clearSubpageOverlayStack()
                homeRootView = null
                showHomeScreen()
                return@launch
            }

            val cloudVins = cloudVehicles.map { it.vin.trim().uppercase(Locale.US) }.toSet()
            val localByVin = profileStore.listProfiles()
                .filter { it.vin.isNotBlank() }
                .associateBy { it.vin.trim().uppercase(Locale.US) }
            val currentVin = activeProfile.vin.trim().uppercase(Locale.US)
            val chosen = cloudVehicles.firstOrNull { it.selected == "1" }
                ?: cloudVehicles.firstOrNull { it.vin.trim().uppercase(Locale.US) == currentVin }
                ?: cloudVehicles.first()
            val chosenVin = chosen.vin.trim().uppercase(Locale.US)
            val existing = localByVin[chosenVin]

            val updatedProfile = VehicleProfile(
                vin = chosenVin,
                userId = firstNonBlank(existing?.userId.orEmpty(), activeProfile.userId),
                mobileNo = firstNonBlank(existing?.mobileNo.orEmpty(), activeProfile.mobileNo),
                bleName = firstNonBlank(chosen.bleName, existing?.bleName.orEmpty()),
                bleMac = firstNonBlank(chosen.bleMac, existing?.bleMac.orEmpty()),
                deviceId = firstNonBlank(existing?.deviceId.orEmpty(), activeProfile.deviceId, profileStore.defaultDeviceId()),
                accountType = chosen.accountType,
                rootKeyHex = existing?.rootKeyHex.orEmpty(),
                infoHex = existing?.infoHex.orEmpty(),
                edfHex = existing?.edfHex.orEmpty(),
                validFrom = existing?.validFrom.orEmpty(),
                validTo = existing?.validTo.orEmpty(),
                secAHex = existing?.secAHex.orEmpty(),
                secBHex = existing?.secBHex.orEmpty(),
                keyRole = existing?.keyRole ?: 1,
                accessToken = activeProfile.accessToken,
                sdkToken = activeProfile.sdkToken,
                controlPin = firstNonBlank(existing?.controlPin.orEmpty(), sessionVehiclePin, activeProfile.controlPin)
            )
            val saved = withContext(Dispatchers.IO) {
                localByVin.keys.filter { it !in cloudVins }.forEach { profileStore.deleteProfile(it) }
                profileStore.saveProfile(updatedProfile, activate = true)
            }
            val displayName = vehicleModelName(chosen)
            uiPrefs.edit()
                .putString(KEY_MODEL_NAME, displayName)
                .putString(KEY_PLATE_NO, chosen.plateNo.trim())
                .apply()
            cloudTripTrackEnabled = chosen.isTracksOpen ?: cloudTripTrackEnabled
            currentProfile = saved
            sessionVehiclePin = saved.controlPin

            val wasEmpty = carVisual == null || !currentVin.equals(chosenVin, ignoreCase = true)
            if (wasEmpty) {
                activeTelemetry = null
                homeRootView = null
                clearSubpageOverlayStack()
                showHomeScreen()
                if (!hasCompleteKeyMaterial(saved)) {
                    launchCloudProvision(saved, automatic = true)
                }
            } else {
                refreshHomeHeaderAndRows()
                refreshTelemetry(silent = true)
            }
        }
    }

    private fun openAddVehicleSubpage() {
        val addView = featureSheets.buildAddVehicleSubpageView(
            onBack = { popSubpageOverlay() },
            onVehicleBound = { handleVehicleBound() }
        )
        pushSubpageOverlay(addView)
    }

    private fun handleVehicleBound() {
        popSubpageOverlay()
        val activeProfile = profileStore.getActiveProfile()
        if (activeProfile.accessToken.isBlank()) return
        lifecycleScope.launch {
            val existingVins = profileStore.listProfiles()
                .map { it.vin.trim().uppercase(Locale.US) }
                .filter { it.isNotBlank() }
                .toSet()
            val cloudVehicles = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchVehicleList(activeProfile.accessToken)
                }
            }.getOrNull().orEmpty().filter { it.vin.isNotBlank() }

            val chosen = cloudVehicles.firstOrNull { it.vin.trim().uppercase(Locale.US) !in existingVins }
                ?: cloudVehicles.firstOrNull { it.selected == "1" }
                ?: cloudVehicles.firstOrNull()
            if (chosen == null) {
                showHomeStatus("车辆已绑定，正在同步列表...")
                return@launch
            }
            val newVin = chosen.vin.trim().uppercase(Locale.US)
            val newProfile = VehicleProfile(
                vin = newVin,
                userId = activeProfile.userId,
                mobileNo = activeProfile.mobileNo,
                bleName = chosen.bleName,
                bleMac = chosen.bleMac,
                deviceId = activeProfile.deviceId.ifBlank { profileStore.defaultDeviceId() },
                accountType = chosen.accountType,
                rootKeyHex = "",
                infoHex = "",
                edfHex = "",
                accessToken = activeProfile.accessToken,
                sdkToken = activeProfile.sdkToken,
                controlPin = sessionVehiclePin.ifBlank { activeProfile.controlPin }
            )
            val saved = withContext(Dispatchers.IO) {
                profileStore.saveProfile(newProfile, activate = true)
            }
            val displayName = vehicleModelName(chosen)
            uiPrefs.edit()
                .putString(KEY_MODEL_NAME, displayName)
                .putString(KEY_PLATE_NO, chosen.plateNo.trim())
                .apply()
            cloudTripTrackEnabled = chosen.isTracksOpen ?: cloudTripTrackEnabled
            currentProfile = saved
            sessionVehiclePin = saved.controlPin
            activeTelemetry = null
            homeRootView = null
            showHomeScreen()
            launchCloudProvision(saved, automatic = true)
            showHomeStatus("已绑定并切换至 $displayName")
        }
    }

    private fun returnFromSettingsToHome() {
        currentScreen = Screen.HOME
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        val overlay = settingsRootView
        settingsRootView = null
        settingsScreen = null
        if (overlay != null) {
            val endX = contentFrame.width.takeIf { it > 0 }?.toFloat()
                ?: resources.displayMetrics.widthPixels.toFloat()
            overlay.animate().cancel()
            overlay.animate()
                .translationX(endX)
                .setDuration(220L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .withEndAction {
                    contentFrame.removeView(overlay)
                }
                .start()
        }
        carVisual?.onHostResume()
        refreshHomeHeaderAndRows()
        startProximityWhenPermitted()
    }

    private fun buildHomeHeader(page: LinearLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(20), dp(4), dp(16), dp(4))
        }
        homeHeaderView = header
        page.addView(header)

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        header.addView(leftCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Row 1: Vehicle Title + downward chevron (vehicle switcher) | Interactive Bluetooth scan button on the right
        val titleLineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        leftCol.addView(titleLineRow)

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "切换车辆"
            MotionSpec.attachPressPhysics(this, pressedScale = 0.96f)
            setOnClickListener { showVehicleSwitcherPopover(this) }
        }
        titleLineRow.addView(titleRow)

        val initialCachedModel = VehicleDisplayNames.normalizeCachedName(
            uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty(),
            uiPrefs.getString(KEY_PLATE_NO, "").orEmpty()
        )
        vehicleNameView = text(
            initialCachedModel,
            24f,
            TEXT_PRIMARY,
            Typeface.BOLD
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(200)
        }
        titleRow.addView(vehicleNameView)

        val chevronDown = AppIconView(this, AppIconType.CHEVRON_DOWN, TEXT_PRIMARY)
        titleRow.addView(chevronDown, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            marginStart = dp(5)
            topMargin = dp(2)
        })

        // Interactive Bluetooth scan/connect button placed directly to the right of the vehicle title
        val channelBadgeWrap = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "蓝牙连接"
            MotionSpec.attachPressPhysics(this, pressedScale = 0.88f, haptic = false)
            setOnClickListener { triggerManualBleScanAndConnect(this) }
        }
        channelBadgeIcon = AppIconView(this, AppIconType.BLUETOOTH_ICON, TEXT_SECONDARY).apply {
            contentDescription = "蓝牙通道未连接，轻触扫描连接"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        channelBadgeWrap.addView(
            channelBadgeIcon,
            FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        )
        titleLineRow.addView(channelBadgeWrap, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            marginStart = dp(4)
        })

        // Row 2: Pure minimal energy bar + tappable % <-> km toggle (license plate moved to quiet Home footer)
        val energyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        leftCol.addView(energyRow, lp(top = dp(7)))

        val energyToggleBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "切换油量百分比与剩余续航"
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener { toggleEnergyDisplayMode() }
        }
        batteryPillView = BatteryPillView(this)
        energyToggleBox.addView(batteryPillView, LinearLayout.LayoutParams(dp(48), dp(7)))

        energyTextView = text("--%", 13.5f, Color.rgb(214, 218, 226), Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        }
        energyToggleBox.addView(energyTextView)
        energyRow.addView(energyToggleBox)

        // Row 3: one honest primary motion state (parked / started / lock only as a fallback).
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        leftCol.addView(statusRow, lp(top = dp(3)))

        parkingStatusView = text("状态待同步", 13f, TEXT_SECONDARY)
        statusRow.addView(parkingStatusView)

        // Right icons: Message + Settings Menu
        val rightIcons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(rightIcons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        val refreshIcon = AppIconView(this, AppIconType.MESSAGE_BUBBLE, TEXT_PRIMARY).apply {
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            isFocusable = true
            contentDescription = "消息"
            MotionSpec.attachPressPhysics(this, pressedScale = 0.88f)
            setOnClickListener { showMessagesSubpageScreen() }
        }
        rightIcons.addView(refreshIcon, LinearLayout.LayoutParams(dp(38), dp(38)))

        val menuIcon = AppIconView(this, AppIconType.MENU_HAMBURGER, TEXT_PRIMARY).apply {
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            isFocusable = true
            contentDescription = "设置与账户"
            MotionSpec.attachPressPhysics(this, pressedScale = 0.88f)
            setOnClickListener { showSettingsScreen() }
        }
        rightIcons.addView(menuIcon, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            marginStart = dp(8)
        })
    }

    private fun showSettingsScreen() {
        stopStatusAutoHide()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        carVisual?.onHostPause()

        val screen = SettingsScreen(
            activity = this,
            scope = lifecycleScope,
            profileProvider = { profileStore.getActiveProfile() },
            modelNameProvider = { uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty() },
            plateNoProvider = { uiPrefs.getString(KEY_PLATE_NO, "").orEmpty() },
            onBackToHome = { showHomeScreen() },
            onOpenVehicleSwitcher = { managementScreens.openMyVehiclesScreen() },
            onOpenVehicleUpgrade = { featureSheets.showVehicleUpgradeSheet() },
            onOpenChangePassword = { featureSheets.showChangeLoginPasswordModal(profileStore.getActiveProfile()) },
            onOpenPinManagement = { featureSheets.showPinManagementModal(profileStore.getActiveProfile()) },
            onPickAvatarImage = { onPicked ->
                pendingAvatarPickCallback = onPicked
                avatarPickerLauncher.launch("image/*")
            },
            onLogout = { logoutToWelcome() },
            onToggleTripTrack = { enabled, onResult ->
                val profile = profileStore.getActiveProfile()
                if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
                    onResult(false)
                } else {
                    lifecycleScope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.setTripTrackSwitch(profile.vin, enabled, profile.accessToken)
                            }
                        }.getOrDefault(false)
                        if (ok) cloudTripTrackEnabled = enabled
                        onResult(ok)
                    }
                }
            },
            initialTripTrackEnabled = cloudTripTrackEnabled
        )
        settingsScreen = screen
        currentScreen = Screen.SETTINGS

        settingsRootView?.let { contentFrame.removeView(it) }
        val settingsView = screen.buildRootView()
        settingsRootView = settingsView
        contentFrame.addView(settingsView, matchParent())

        val startX = contentFrame.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        settingsView.translationX = startX
        settingsView.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .start()
    }

    private fun formatEnergyDisplay(telemetry: VehicleTelemetry?): String {
        val energy = telemetry?.fuelPercent ?: telemetry?.electricitySurplus?.toDoubleOrNull()
        val range = telemetry?.remainingRangeKm
        return if (!energyShowsRange) {
            when {
                energy != null -> "${energy.roundToInt()}%"
                range != null && range > 0 -> "${range.roundToInt()} km"
                else -> "--%"
            }
        } else {
            when {
                range != null && range > 0 -> "${range.roundToInt()} km"
                energy != null -> "${energy.roundToInt()}%"
                else -> "-- km"
            }
        }
    }

    private fun toggleEnergyDisplayMode() {
        AppHaptics.perform(energyTextView, HapticLevel.LIGHT_TICK)
        energyShowsRange = !energyShowsRange
        val nextText = formatEnergyDisplay(activeTelemetry)
        energyTextView?.let { tv ->
            tv.animate().cancel()
            tv.animate()
                .alpha(0f)
                .setDuration(70L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .withEndAction {
                    tv.text = nextText
                    tv.animate()
                        .alpha(1f)
                        .setDuration(140L)
                        .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                        .start()
                }
                .start()
        }
    }

    private fun showSecuritySubpageScreen() {
        stopStatusAutoHide()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        carVisual?.onHostPause()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_HOME)
            isClickable = true
            isFocusable = true
        }

        // Unified AppTopBar (56dp fixed height, #161719, Lucide BACK_ARROW)
        root.addView(
            AppComponents.createTopBar(
                context = this,
                title = "安全性",
                onBack = { returnFromSecurityToHome() }
            ),
            lp()
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Row 1: 车控安全密码 switch (turn OFF requires confirmation per DESIGN_SYSTEM v2.3)
        val pinSwitch = ToggleSwitch(this).apply {
            checked = uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, false)
            onToggleAttempt = { targetChecked ->
                if (targetChecked) {
                    uiPrefs.edit().putBoolean(KEY_REQUIRE_PIN_EVERY_TIME, true).apply()
                    setChecked(true, animate = true)
                    showHomeStatus("已开启每次控车验证密码")
                } else {
                    AppComponents.showConfirmDialog(
                        context = this@MainActivity,
                        title = "关闭控车密码验证",
                        message = "关闭后发起远程控车将自动校验已保存密码，无需每次手动输入。",
                        confirmText = "确认关闭"
                    ) {
                        uiPrefs.edit().putBoolean(KEY_REQUIRE_PIN_EVERY_TIME, false).apply()
                        setChecked(false, animate = true)
                        showHomeStatus("已关闭每次控车验证密码")
                    }
                }
            }
        }
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.LOCK_CLOSED,
                title = "车控安全密码",
                subtitle = "开启后每次远程控车均需输入 6 位密码",
                trailingView = pinSwitch,
                showChevron = false,
                onClick = null
            ),
            lp()
        )
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Row 2: 修改车控安全密码 (6-digit stepped wizard)
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.KEY_ROUND,
                title = "修改车控安全密码",
                showChevron = true
            ) { featureSheets.showPinManagementModal(profileStore.getActiveProfile()) },
            lp()
        )
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Row 3: 防盗与车辆体检
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.SHIELD_CHECK,
                title = "防盗与车辆体检",
                showChevron = true
            ) { featureSheets.showAntiTheftSheet() },
            lp()
        )
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Row 4: 电子围栏
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.MAP_PIN,
                title = "电子围栏",
                showChevron = true
            ) { featureSheets.showGeofenceSheet() },
            lp()
        )

        currentScreen = Screen.SECURITY
        securityOverlayView?.let { contentFrame.removeView(it) }
        securityOverlayView = root
        contentFrame.addView(root, matchParent())

        val startX = contentFrame.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        root.translationX = startX
        root.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .start()
    }

    private fun showDriversSubpageScreen() {
        managementScreens.openDriversScreen()
    }

    private fun buildActiveVehicleLiveSpecs(vin: String): List<Pair<String, String>> {
        val profile = profileStore.getActiveProfile()
        if (!profile.vin.equals(vin, ignoreCase = true)) return emptyList()
        val t = activeTelemetry
        val odoStr = t?.odometerKm?.takeIf { it > 0.0 }?.let {
            "${String.format(Locale.US, "%,d", it.roundToInt())} km"
        } ?: t?.odometer?.takeIf { it.isNotBlank() }?.let { "$it km" } ?: "--"
        val tyreStr = formatTyreSummary(t).let { if (it == "--") "--" else "$it bar" }
        val voltStr = t?.batteryVoltage?.let { String.format(Locale.US, "%.2f V", it) } ?: "--"
        val bleNameStr = profile.bleName.ifBlank { "--" }
        return listOf(
            "总里程" to odoStr,
            "四轮胎压" to tyreStr,
            "蓄电池电压" to voltStr,
            "蓝牙钥匙名称" to bleNameStr
        )
    }

    private fun showVehicleInfoSubpageScreen() {
        val profile = profileStore.getActiveProfile()
        managementScreens.openVehicleInfoScreen(
            initialVehicle = managementScreens.cachedVehicleSummary(profile.vin),
            vinFallback = profile.vin
        )
    }

    private fun showLocationSubpageScreen() {
        stopStatusAutoHide()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        carVisual?.onHostPause()

        locationOverlayDestroy?.invoke()
        locationOverlayView?.let { contentFrame.removeView(it) }

        val (locationView, destroyCb) = featureSheets.buildLocationSubpageView(
            onBack = { returnFromLocationToHome() },
            registerAddressUpdate = { observer ->
                locationAddressObservers += observer
                val unregister: () -> Unit = { locationAddressObservers -= observer }
                unregister
            }
        )
        currentScreen = Screen.LOCATION
        locationOverlayView = locationView
        locationOverlayDestroy = destroyCb
        contentFrame.addView(locationView, matchParent())

        val startX = contentFrame.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        locationView.translationX = startX
        locationView.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .start()
    }

    private fun returnFromLocationToHome() {
        currentScreen = Screen.HOME
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        val overlay = locationOverlayView
        val destroyCb = locationOverlayDestroy
        locationOverlayView = null
        locationOverlayDestroy = null
        if (overlay != null) {
            val endX = contentFrame.width.takeIf { it > 0 }?.toFloat()
                ?: resources.displayMetrics.widthPixels.toFloat()
            overlay.animate().cancel()
            overlay.animate()
                .translationX(endX)
                .setDuration(220L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .withEndAction {
                    destroyCb?.invoke()
                    contentFrame.removeView(overlay)
                }
                .start()
        } else {
            destroyCb?.invoke()
        }
        carVisual?.onHostResume()
        refreshHomeHeaderAndRows()
        startProximityWhenPermitted()
    }

    private fun showMessagesSubpageScreen() {
        stopStatusAutoHide()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        carVisual?.onHostPause()

        messagesOverlayView?.let { contentFrame.removeView(it) }
        val messagesView = featureSheets.buildMessagesSubpageView {
            returnFromMessagesToHome()
        }
        currentScreen = Screen.MESSAGES
        messagesOverlayView = messagesView
        contentFrame.addView(messagesView, matchParent())

        val startX = contentFrame.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        messagesView.translationX = startX
        messagesView.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .start()
    }

    private fun returnFromMessagesToHome() {
        currentScreen = Screen.HOME
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        val overlay = messagesOverlayView
        messagesOverlayView = null
        if (overlay != null) {
            val endX = contentFrame.width.takeIf { it > 0 }?.toFloat()
                ?: resources.displayMetrics.widthPixels.toFloat()
            overlay.animate().cancel()
            overlay.animate()
                .translationX(endX)
                .setDuration(220L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .withEndAction {
                    contentFrame.removeView(overlay)
                }
                .start()
        }
        carVisual?.onHostResume()
        refreshHomeHeaderAndRows()
        startProximityWhenPermitted()
    }

    private fun pushSubpageOverlay(view: View, onDestroy: (() -> Unit)? = null) {
        stopStatusAutoHide()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        if (currentScreen == Screen.HOME) {
            carVisual?.onHostPause()
        }
        val previousTop = subpageOverlayStack.lastOrNull()?.first
        subpageOverlayStack.addLast(view to onDestroy)
        contentFrame.addView(view, matchParent())
        val startX = contentFrame.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        view.translationX = startX
        view.alpha = 0.96f
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(280L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .start()

        // Spatial-continuity background depth recede for underlying subpage
        previousTop?.let { bg ->
            bg.animate().cancel()
            bg.animate()
                .translationX(-dpF(34f))
                .scaleX(0.965f)
                .scaleY(0.965f)
                .alpha(0.76f)
                .setDuration(280L)
                .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                .start()
        }
    }

    private fun popSubpageOverlay() {
        val entry = subpageOverlayStack.removeLastOrNull() ?: return
        val (overlay, destroyCb) = entry
        val revealedTop = subpageOverlayStack.lastOrNull()?.first
        val endX = contentFrame.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        overlay.animate().cancel()
        overlay.animate()
            .translationX(endX)
            .setDuration(240L)
            .setInterpolator(MotionSpec.EASE_STANDARD)
            .withEndAction {
                destroyCb?.invoke()
                contentFrame.removeView(overlay)
            }
            .start()

        revealedTop?.let { bg ->
            bg.animate().cancel()
            bg.animate()
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(240L)
                .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                .start()
        }

        if (subpageOverlayStack.isEmpty() && currentScreen == Screen.HOME) {
            carVisual?.onHostResume()
            refreshHomeHeaderAndRows()
        }
    }

    private fun clearSubpageOverlayStack() {
        while (subpageOverlayStack.isNotEmpty()) {
            val (overlay, destroyCb) = subpageOverlayStack.removeLast()
            overlay.animate().cancel()
            destroyCb?.invoke()
            contentFrame.removeView(overlay)
        }
    }

    private fun returnFromSecurityToHome() {
        currentScreen = Screen.HOME
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        val overlay = securityOverlayView
        securityOverlayView = null
        securitySubpageStatusText = null
        if (overlay != null) {
            val endX = contentFrame.width.takeIf { it > 0 }?.toFloat()
                ?: resources.displayMetrics.widthPixels.toFloat()
            overlay.animate().cancel()
            overlay.animate()
                .translationX(endX)
                .setDuration(220L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .withEndAction {
                    contentFrame.removeView(overlay)
                }
                .start()
        }
        carVisual?.onHostResume()
        refreshHomeHeaderAndRows()
        startProximityWhenPermitted()
    }

    private fun formatSecurityDigitalKeyStatus(): String {
        val profile = profileStore.getActiveProfile()
        val isBleReady = bleConnected || bleController.isSessionReady()
        val isConnecting = !isBleReady && (bleNearby || bleController.isVehicleNearby)
        val keyReady = hasCompleteKeyMaterial(profile)
        return when {
            isBleReady -> {
                val rssi = bleController.lastProximityRssi?.let { " · $it dBm" }.orEmpty()
                "BLE 近场直连中$rssi · 无感控车已就绪"
            }
            isConnecting -> "附近车辆已发现 · 正在自动建立加密蓝牙会话"
            keyReady -> "本地数字钥匙已就绪 · 靠近车辆自动连接"
            else -> "数字钥匙待同步 · 点击首页蓝牙图标可自动同步"
        }
    }

    private fun buildTpmsOverlay(heroContainer: FrameLayout) {
        val overlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
        }
        tpmsOverlayContainer = overlay

        fun makeMinimalTpmsCorner(
            gravity: Int,
            alignEnd: Boolean,
            startM: Int,
            topM: Int,
            endM: Int,
            bottomM: Int
        ): Pair<TextView, TextView> {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                this.gravity = if (alignEnd) Gravity.END else Gravity.START
                minimumWidth = dp(64)
                clipChildren = false
                clipToPadding = false
            }
            val pressureTv = text("--", 17f, Color.rgb(242, 244, 248), Typeface.BOLD).apply {
                maxLines = 1
                isSingleLine = true
                ellipsize = null
                fontFeatureSettings = "tnum"
                setPadding(dp(2), 0, dp(2), 0)
                setShadowLayer(4f, 0f, 1f, Color.argb(190, 0, 0, 0))
            }
            val accentTick = View(this).apply {
                background = rounded(Color.argb(110, 255, 255, 255), 1f)
            }
            val tempTv = text("", 11f, TEXT_SECONDARY).apply {
                maxLines = 1
                isSingleLine = true
                ellipsize = null
                visibility = View.GONE
                setShadowLayer(4f, 0f, 1f, Color.argb(190, 0, 0, 0))
            }
            box.addView(pressureTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            box.addView(
                accentTick,
                LinearLayout.LayoutParams(dp(12), dpF(1.5f).roundToInt().coerceAtLeast(1)).apply {
                    topMargin = dp(3)
                }
            )
            box.addView(
                tempTv,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = dp(3)
                }
            )
            overlay.addView(
                box,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, gravity).apply {
                    marginStart = startM
                    topMargin = topM
                    marginEnd = endM
                    bottomMargin = bottomM
                }
            )
            return pressureTv to tempTv
        }

        val fl = makeMinimalTpmsCorner(Gravity.TOP or Gravity.START, false, dp(22), dp(22), 0, 0)
        tpmsFlPressure = fl.first
        tpmsFlTemp = fl.second
        val fr = makeMinimalTpmsCorner(Gravity.TOP or Gravity.END, true, 0, dp(22), dp(22), 0)
        tpmsFrPressure = fr.first
        tpmsFrTemp = fr.second
        val rl = makeMinimalTpmsCorner(Gravity.BOTTOM or Gravity.START, false, dp(22), 0, 0, dp(22))
        tpmsRlPressure = rl.first
        tpmsRlTemp = rl.second
        val rr = makeMinimalTpmsCorner(Gravity.BOTTOM or Gravity.END, true, 0, 0, dp(22), dp(22))
        tpmsRrPressure = rr.first
        tpmsRrTemp = rr.second
        heroContainer.addView(overlay, matchParent())
    }

    private fun formatCornerTyrePressure(pressureRaw: String): String {
        val raw = pressureRaw.trim()
        if (raw.isBlank() || raw == "0" || raw == "255") return "--"
        val match = NUMBER_EXTRACT_REGEX.find(raw) ?: return "--"
        val num = match.value.toDoubleOrNull() ?: return "--"
        if (num <= 0.0 || num >= 255.0) return "--"
        val bar = when {
            num >= 100.0 -> num / 100.0
            num >= 10.0 -> num / 10.0
            else -> num
        }
        return String.format(Locale.US, "%.2f", bar)
    }

    private fun updateTpmsBadges(telemetry: VehicleTelemetry?) {
        fun applyCorner(
            pressureTv: TextView?,
            tempTv: TextView?,
            pressureRaw: String,
            vararg tempKeys: String
        ) {
            pressureTv?.text = formatCornerTyrePressure(pressureRaw)

            val temp = tempKeys.firstNotNullOfOrNull { key ->
                telemetry?.conditionFields?.get(key)?.trim()?.takeIf { it.isNotBlank() && it != "255" }
                    ?: telemetry?.conditionFields?.get(key.uppercase(Locale.US))?.trim()?.takeIf { it.isNotBlank() && it != "255" }
            }
            if (temp != null) {
                tempTv?.text = "${temp}°C"
                tempTv?.visibility = View.VISIBLE
            } else {
                tempTv?.visibility = View.GONE
            }
        }

        applyCorner(tpmsFlPressure, tpmsFlTemp, telemetry?.lFrontTyre.orEmpty(), "lFrontTyreTemp", "flTyreTemp", "tyreTempFL", "leftFrontTyreTemp")
        applyCorner(tpmsFrPressure, tpmsFrTemp, telemetry?.rFrontTyre.orEmpty(), "rFrontTyreTemp", "frTyreTemp", "tyreTempFR", "rightFrontTyreTemp")
        applyCorner(tpmsRlPressure, tpmsRlTemp, telemetry?.lRearTyre.orEmpty(), "lRearTyreTemp", "rlTyreTemp", "tyreTempRL", "leftRearTyreTemp")
        applyCorner(tpmsRrPressure, tpmsRrTemp, telemetry?.rRearTyre.orEmpty(), "rRearTyreTemp", "rrTyreTemp", "tyreTempRR", "rightRearTyreTemp")
    }

    private fun buildIconQuickActionRow(page: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        quickActionRow = row
        page.addView(row)
        rebuildQuickActionRow()
    }

    private fun computeExpandedStageHeight(): Int {
        val screenH = resources.displayMetrics.heightPixels
        return (screenH * 0.44f).roundToInt().coerceIn(dp(300), dp(400))
    }

    private fun buildExpandableQuickDeck(stageFrame: FrameLayout, expandedStageH: Int) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_HOME)
            visibility = View.GONE
            alpha = 0f
            setPadding(dp(18), dp(8), dp(18), dp(20))
        }
        quickDeckContainer = container

        val editHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(2), dp(4), dp(12))
        }
        quickDeckEditHeader = editHeader
        val editTitle = text("", 12.5f, TEXT_SECONDARY, Typeface.BOLD)
        quickDeckEditTitle = editTitle
        editHeader.addView(editTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val doneBtn = text("完成", 12.5f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(PRIMARY_BLUE, 14f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener {
                quickDeckEditMode = false
                rebuildQuickActionRow()
                renderQuickDeckGrid(animateLabels = false)
                showHomeStatus("首页快捷栏已保存")
            }
        }
        editHeader.addView(doneBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        container.addView(editHeader, lp())

        val gridHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        quickDeckGridHost = gridHost
        container.addView(gridHost, lp())

        stageFrame.addView(
            container,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                topMargin = expandedStageH
            }
        )
    }

    private fun applyDeckExpandProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        deckExpandProgress = p

        // Drive 3D camera in exact 1:1 frame lockstep with the sliding sheet (zero secondary lag)
        carVisual?.setDeckProgress(p)
        carHeroContainerView?.translationY = 0f

        val stageH = homeStageFrame?.height?.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels - dp(120))
        val slideDistance = (stageH - dp(248)).coerceAtLeast(dp(360)).toFloat()

        // Pure solid physical drawer slide: lower sheet stays opaque (alpha = 1f) and glides smoothly down/up
        lowerOverlayContainer?.let { lower ->
            lower.visibility = if (p >= 0.998f) View.GONE else View.VISIBLE
            lower.translationY = slideDistance * p
            lower.scaleX = 1f
            lower.scaleY = 1f
            lower.alpha = 1f
        }

        // Control deck sits cleanly behind the opaque lower sheet (alpha = 1f, zero cross-fade ghosting)
        // and rises subtly into place as the lower sheet glides out of the way
        quickDeckContainer?.let { deck ->
            deck.visibility = if (p <= 0.002f) View.GONE else View.VISIBLE
            deck.alpha = 1f
            deck.translationY = (1f - p) * dpF(28f)

            quickDeckRowViews.forEachIndexed { rowIdx, row ->
                row.alpha = 1f
                row.translationY = (1f - p) * dpF(rowIdx * 8f)
                row.scaleX = 1f
                row.scaleY = 1f
            }
        }

        // Corner TPMS badges appear cleanly once the car settles near top-down view
        tpmsOverlayContainer?.let { tpms ->
            tpms.visibility = if (p <= 0.55f) View.GONE else View.VISIBLE
            val tpmsP = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val inv = 1f - tpmsP
            val eased = 1f - inv * inv * inv
            tpms.alpha = eased
            val tpmsScale = 0.94f + 0.06f * eased
            tpms.scaleX = tpmsScale
            tpms.scaleY = tpmsScale
        }
    }

    private fun animateDeckTo(targetExpanded: Boolean) {
        deckExpandAnimator?.cancel()
        quickDeckExpanded = targetExpanded
        if (targetExpanded) {
            updateTpmsBadges(activeTelemetry)
            renderQuickDeckGrid(animateLabels = false)
        } else {
            quickDeckEditMode = false
        }

        val start = deckExpandProgress
        val end = if (targetExpanded) 1f else 0f
        if (abs(start - end) < 0.005f) {
            applyDeckExpandProgress(end)
            if (!targetExpanded) rebuildQuickActionRow()
            return
        }

        val spanFraction = abs(end - start).coerceIn(0.25f, 1f)
        val totalDurationMs = (420f * spanFraction).roundToInt().coerceIn(220, 420).toLong()

        deckExpandAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = totalDurationMs
            interpolator = MotionSpec.EASE_OUT_EXPO
            addUpdateListener { anim ->
                applyDeckExpandProgress(anim.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!targetExpanded) {
                        rebuildQuickActionRow()
                    }
                }
            })
            start()
        }
    }

    private fun areWindowsOpen(t: VehicleTelemetry?): Boolean {
        if (t == null) return false
        return isDoorOpenToken(t.frontLeftWindowState.ifBlank { t.windowStates["frontLeft"].orEmpty() }) ||
            isDoorOpenToken(t.frontRightWindowState.ifBlank { t.windowStates["frontRight"].orEmpty() }) ||
            isDoorOpenToken(t.backLeftWindowState.ifBlank { t.windowStates["backLeft"].orEmpty() }) ||
            isDoorOpenToken(t.backRightWindowState.ifBlank { t.windowStates["backRight"].orEmpty() })
    }

    private fun isSkylightTilted(skylightState: String?): Boolean =
        skylightState.orEmpty().trim().lowercase(Locale.US) in setOf("12", "tilt", "cock")

    private fun isQuickActionActive(action: QuickActionId): Boolean {
        val t = activeTelemetry
        return when (action) {
            QuickActionId.LOCK -> !(t?.doorLocked ?: (carVisual?.vehicleLocked ?: true))
            QuickActionId.CLIMATE -> t?.acOn == true
            QuickActionId.TRUNK -> t?.trunkOpen == true
            QuickActionId.ENGINE -> t?.engineRunning == true
            QuickActionId.WINDOW -> areWindowsOpen(t)
            QuickActionId.SUNROOF -> isSkylightOpen(t?.skylightState)
            QuickActionId.SUNROOF_TILT -> isSkylightTilted(t?.skylightState)
            else -> false
        }
    }

    private fun resolveQuickActionIcon(action: QuickActionId, active: Boolean): AppIconType = when (action) {
        QuickActionId.LOCK -> if (active) AppIconType.LOCK_OPEN else AppIconType.LOCK_CLOSED
        QuickActionId.TRUNK -> if (active) AppIconType.TAILGATE_CLOSE else AppIconType.TRUNK_OPEN
        QuickActionId.ENGINE -> if (active) AppIconType.ENGINE_STOP else AppIconType.ENGINE_POWER
        QuickActionId.WINDOW -> if (active) AppIconType.WINDOW_CLOSE else AppIconType.WINDOW_OPEN
        QuickActionId.SUNROOF -> if (active) AppIconType.SUNROOF_CLOSE else AppIconType.SUNROOF_OPEN
        else -> action.icon
    }

    private fun resolveQuickActionLabel(action: QuickActionId, active: Boolean, editMode: Boolean): String {
        if (editMode) return action.label
        return when (action) {
            QuickActionId.LOCK -> if (active) "锁车" else "解锁"
            QuickActionId.CLIMATE -> if (active) "关空调" else "空调"
            QuickActionId.TRUNK -> if (active) "关后备箱" else "开后备箱"
            QuickActionId.ENGINE -> if (active) "熄火" else "启动"
            QuickActionId.WINDOW -> if (active) "关车窗" else "开车窗"
            QuickActionId.SUNROOF -> if (active) "关天窗" else "开天窗"
            else -> action.label
        }
    }

    private fun onChannelBadgeTapped() {
        val profile = profileStore.getActiveProfile()
        if (bleConnected || bleController.isSessionReady()) {
            val rssi = bleController.lastProximityRssi?.let { " · $it dBm" }.orEmpty()
            showHomeStatus("蓝牙数字钥匙已直连$rssi")
            return
        }
        if (profile.vin.isBlank()) {
            showHomeStatus("尚未绑定车辆")
            return
        }
        if (!hasCompleteKeyMaterial(profile)) {
            showHomeStatus("正在同步数字钥匙并尝试蓝牙连接...")
            launchCloudProvision(profile, automatic = false)
            return
        }
        bleController.stopAutoProximityWatch()
        startProximityWhenPermitted()
        showHomeStatus("正在扫描并连接附近蓝牙钥匙...")
    }

    private fun renderQuickDeckGrid(animateLabels: Boolean = false) {
        val gridHost = quickDeckGridHost ?: return
        val selectedList = loadQuickActions()
        val selectedSet = selectedList.toSet()

        quickDeckEditHeader?.visibility = if (quickDeckEditMode) View.VISIBLE else View.GONE
        quickDeckEditTitle?.text = "自定义快捷栏 (已选 ${selectedList.size}/6)"

        gridHost.removeAllViews()
        quickDeckRowViews.clear()
        val tileHeight = dp(78)
        QuickActionId.values().toList().chunked(3).forEachIndexed { rowIdx, chunk ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            quickDeckRowViews.add(rowView)
            chunk.forEachIndexed { colIdx, action ->
                val isPinned = action in selectedSet
                val isStateActive = isQuickActionActive(action)
                val iconType = if (quickDeckEditMode) action.icon else resolveQuickActionIcon(action, isStateActive)
                val tileLabel = resolveQuickActionLabel(action, isStateActive, quickDeckEditMode)

                val btn = AppComponents.createControlIconButton(
                    context = this,
                    iconType = iconType,
                    sizeDp = 28,
                    active = !quickDeckEditMode && isStateActive,
                    label = tileLabel,
                    showCheckDot = quickDeckEditMode && isPinned,
                    dimmed = quickDeckEditMode && !isPinned
                ).apply {
                    isClickable = true
                    isFocusable = true
                    isLongClickable = true
                    contentDescription = action.label
                    MotionSpec.attachPressPhysics(this, pressedScale = 0.88f)
                    setOnClickListener {
                        if (quickDeckEditMode) {
                            val mutable = loadQuickActions().toMutableList()
                            if (action in mutable) {
                                if (mutable.size <= 1) {
                                    Toast.makeText(this@MainActivity, "至少保留 1 项快捷控制", Toast.LENGTH_SHORT).show()
                                } else {
                                    mutable.remove(action)
                                    saveQuickActions(mutable)
                                    renderQuickDeckGrid(animateLabels = false)
                                }
                            } else {
                                if (mutable.size >= 6) {
                                    Toast.makeText(this@MainActivity, "最多选择 6 项快捷控制，请先取消一项", Toast.LENGTH_SHORT).show()
                                } else {
                                    mutable.add(action)
                                    saveQuickActions(mutable)
                                    renderQuickDeckGrid(animateLabels = false)
                                }
                            }
                        } else {
                            triggerQuickAction(action)
                        }
                    }
                    setOnLongClickListener {
                        if (!quickDeckEditMode) {
                            AppHaptics.perform(this, HapticLevel.LONG_PRESS_MODE)
                            quickDeckEditMode = true
                            renderQuickDeckGrid(animateLabels = true)
                        }
                        true
                    }
                }

                val iconView = btn.findViewWithTag<AppIconView>("control_icon")
                if (action == QuickActionId.CLIMATE && isStateActive && !quickDeckEditMode) {
                    iconView?.spinEnabled = true
                }
                if (quickDeckEditMode && animateLabels) {
                    btn.findViewWithTag<TextView>("control_label")?.apply {
                        alpha = 0f
                        animate()
                            .alpha(1f)
                            .setDuration(180L)
                            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                            .start()
                    }
                }

                rowView.addView(btn, weightedLp(if (colIdx == 0) 0 else dp(8), tileHeight))
            }
            gridHost.addView(rowView, lp(top = if (rowIdx == 0) 0 else dp(10)))
        }
    }

    private fun loadQuickActions(): List<QuickActionId> {
        val raw = uiPrefs.getString(KEY_QUICK_ACTIONS, DEFAULT_QUICK_ACTIONS).orEmpty()
        val parsed = raw.split(",")
            .mapNotNull { QuickActionId.fromId(it) }
            .distinct()
            .take(6)
        return parsed.ifEmpty { QuickActionId.DEFAULT_LIST }
    }

    private fun saveQuickActions(actions: List<QuickActionId>) {
        val normalized = actions.distinct().take(6).ifEmpty { QuickActionId.DEFAULT_LIST }
        uiPrefs.edit()
            .putString(KEY_QUICK_ACTIONS, normalized.joinToString(",") { it.id })
            .apply()
    }

    private fun rebuildQuickActionRow() {
        val row = quickActionRow ?: return
        row.removeAllViews()
        quickActionIcons.clear()

        val actions = loadQuickActions()
        val iconSizeDp = if (actions.size >= 5) 24 else 26
        val hPad = if (actions.size >= 5) dp(12) else dp(18)
        row.setPadding(hPad, dp(10), hPad, dp(4))

        actions.forEach { action ->
            val btn = AppComponents.createControlIconButton(
                context = this,
                iconType = action.icon,
                sizeDp = iconSizeDp,
                active = false,
                label = null
            ).apply {
                isClickable = true
                isFocusable = true
                contentDescription = action.label
                MotionSpec.attachPressPhysics(this, pressedScale = 0.88f)
                setOnClickListener { triggerQuickAction(action) }
            }
            btn.findViewWithTag<AppIconView>("control_icon")?.let { iconView ->
                quickActionIcons[action] = iconView
            }
            row.addView(btn, LinearLayout.LayoutParams(0, dp(54), 1f))
        }
        updateQuickActionIconStates()
    }

    private fun triggerQuickAction(action: QuickActionId) {
        AppHaptics.perform(quickActionRow ?: quickDeckGridHost, HapticLevel.MEDIUM_CONFIRM)
        when (action) {
            QuickActionId.LOCK -> {
                val currentlyLocked = activeTelemetry?.doorLocked ?: (carVisual?.vehicleLocked ?: true)
                if (currentlyLocked) {
                    dispatchSmartVehicleCommand("unlock", "车门解锁")
                } else {
                    dispatchSmartVehicleCommand("lock", "车门锁止")
                }
            }
            QuickActionId.CLIMATE -> {
                if (activeTelemetry?.acOn == true) {
                    dispatchSmartVehicleCommand("ac_off", "关闭空调")
                } else {
                    dispatchSmartVehicleCommand(
                        "ac_on",
                        "开启空调",
                        temperatureCelsius = climateTemperatureCelsius
                    )
                }
            }
            QuickActionId.FLASH -> {
                carVisual?.flashLights()
                dispatchSmartVehicleCommand("find_car", "闪灯鸣笛寻车")
            }
            QuickActionId.TRUNK -> {
                if (activeTelemetry?.trunkOpen == true) {
                    dispatchSmartVehicleCommand("tailgate_close", "关闭后备箱")
                } else {
                    dispatchSmartVehicleCommand("trunk_open", "开启后备箱")
                }
            }
            QuickActionId.ENGINE -> {
                if (activeTelemetry?.engineRunning == true) {
                    dispatchSmartVehicleCommand("engine_stop", "远程熄火")
                } else {
                    dispatchSmartVehicleCommand("engine_start", "远程启动引擎")
                }
            }
            QuickActionId.WINDOW -> {
                if (areWindowsOpen(activeTelemetry)) {
                    dispatchSmartVehicleCommand("window_close", "关闭车窗")
                } else {
                    dispatchSmartVehicleCommand("window_open", "打开车窗")
                }
            }
            QuickActionId.WINDOW_VENT -> {
                dispatchSmartVehicleCommand("window_ventilate", "车窗透气")
            }
            QuickActionId.SUNROOF -> {
                if (isSkylightOpen(activeTelemetry?.skylightState)) {
                    dispatchSmartVehicleCommand("skylight_close", "关闭天窗")
                } else {
                    dispatchSmartVehicleCommand("skylight_open", "打开天窗")
                }
            }
            QuickActionId.SUNROOF_TILT -> {
                dispatchSmartVehicleCommand("skylight_tilt", "天窗翘起")
            }
        }
    }

    private fun updateQuickActionIconStates() {
        val telemetry = activeTelemetry
        val effectiveLocked = telemetry?.doorLocked ?: (carVisual?.vehicleLocked ?: true)
        val acActive = telemetry?.acOn == true
        val trunkIsOpen = telemetry?.trunkOpen == true
        val engineRunningNow = telemetry?.engineRunning == true
        val windowsOpenNow = areWindowsOpen(telemetry)
        val sunroofOpenNow = isSkylightOpen(telemetry?.skylightState)
        val sunroofTiltedNow = isSkylightTilted(telemetry?.skylightState)

        quickActionIcons[QuickActionId.LOCK]?.apply {
            iconType = if (effectiveLocked) AppIconType.LOCK_CLOSED else AppIconType.LOCK_OPEN
            MotionSpec.animateIconTint(this, if (effectiveLocked) TEXT_PRIMARY else ACCENT_BLUE)
        }
        quickActionIcons[QuickActionId.CLIMATE]?.apply {
            MotionSpec.animateIconTint(this, if (acActive) ACCENT_BLUE else TEXT_PRIMARY)
            spinEnabled = acActive
        }
        quickActionIcons[QuickActionId.TRUNK]?.apply {
            iconType = if (trunkIsOpen) AppIconType.TAILGATE_CLOSE else AppIconType.TRUNK_OPEN
            MotionSpec.animateIconTint(this, if (trunkIsOpen) ACCENT_BLUE else TEXT_PRIMARY)
        }
        quickActionIcons[QuickActionId.ENGINE]?.apply {
            iconType = if (engineRunningNow) AppIconType.ENGINE_STOP else AppIconType.ENGINE_POWER
            MotionSpec.animateIconTint(this, if (engineRunningNow) ACCENT_BLUE else TEXT_PRIMARY)
        }
        quickActionIcons[QuickActionId.WINDOW]?.apply {
            iconType = if (windowsOpenNow) AppIconType.WINDOW_CLOSE else AppIconType.WINDOW_OPEN
            MotionSpec.animateIconTint(this, if (windowsOpenNow) ACCENT_BLUE else TEXT_PRIMARY)
        }
        quickActionIcons[QuickActionId.SUNROOF]?.apply {
            iconType = if (sunroofOpenNow) AppIconType.SUNROOF_CLOSE else AppIconType.SUNROOF_OPEN
            MotionSpec.animateIconTint(this, if (sunroofOpenNow) ACCENT_BLUE else TEXT_PRIMARY)
        }
        quickActionIcons[QuickActionId.SUNROOF_TILT]?.apply {
            MotionSpec.animateIconTint(this, if (sunroofTiltedNow) ACCENT_BLUE else TEXT_PRIMARY)
        }
    }

    private fun wrapQuickIcon(
        iconView: AppIconView,
        iconSizeDp: Int,
        desc: String,
        onClick: () -> Unit
    ): FrameLayout = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        contentDescription = desc
        addView(iconView, FrameLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp), Gravity.CENTER))
        MotionSpec.attachPressPhysics(this, pressedScale = 0.88f)
        setOnClickListener { onClick() }
    }

    private fun buildMenuRows(page: LinearLayout) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        homeMenuSectionView = list
        page.addView(list)

        // Row 1: 温度
        val tempRow = createMenuRowWithRefs(
            icon = AppIconType.FAN_BLADE,
            title = "温度",
            initialSubtitle = "空调已关闭",
            withStatusDot = false
        ) { showClimateSheet() }
        climateRowSubtitle = tempRow.second
        list.addView(tempRow.first)

        // Row 2: 地点 (全屏实时位置与历史行程子页)
        val locRow = createMenuRowWithRefs(
            icon = AppIconType.LOCATION_ARROW,
            title = "地点",
            initialSubtitle = "位置信息待同步",
            withStatusDot = false
        ) { showLocationSubpageScreen() }
        locationRowSubtitle = locRow.second
        list.addView(locRow.first)

        // Row 3: 安全性 (车控密码开关 / 修改密码 / 防盗体检 / 电子围栏)
        list.addView(
            createMenuRow(
                icon = AppIconType.SHIELD_CHECK,
                title = "安全性",
                subtitle = null,
                withStatusDot = false
            ) { showSecuritySubpageScreen() }
        )

        // Row 4: 驾驶员 (直达远程授权管理)
        val driversRow = createMenuRowWithRefs(
            icon = AppIconType.KEY_ROUND,
            title = "驾驶员",
            initialSubtitle = "手机钥匙已断开连接 · 远程网络控制",
            withStatusDot = true
        ) { showDriversSubpageScreen() }
        securityRowSubtitle = driversRow.second
        securityDotView = driversRow.third
        list.addView(driversRow.first)

        // Row 5: 车辆信息 (基础车况信息 / 车辆升级 OTA)
        list.addView(
            createMenuRow(
                icon = AppIconType.CAR_CONTROLS,
                title = "车辆信息",
                subtitle = null,
                withStatusDot = false
            ) { showVehicleInfoSubpageScreen() }
        )
    }

    private fun buildHomeFooter(page: LinearLayout) {
        val footerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(10))
        }

        // Clean Tesla-style centered plate signature above the odometer footer (keeps top energy row spotless)
        plateBadgeView = text("", 12.5f, FOOTER_TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.96f, haptic = false)
            setOnClickListener {
                val curPlate = firstNonBlank(
                    activeTelemetry?.licensePlate.orEmpty(),
                    uiPrefs.getString(KEY_PLATE_NO, "").orEmpty()
                )
                val active = profileStore.getActiveProfile()
                managementScreens.openVehicleInfoScreen(
                    initialVehicle = managementScreens.cachedVehicleSummary(active.vin),
                    vinFallback = active.vin,
                    startInEditMode = curPlate.isBlank()
                )
            }
        }
        footerBox.addView(plateBadgeView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        homeFooterView = text("", 12.5f, FOOTER_TEXT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.4f)
            setPadding(dp(12), dp(2), dp(12), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { handleHomeFooterTap() }
        }
        footerBox.addView(homeFooterView, lp())
        page.addView(footerBox, lp())
    }

    private fun formatHomeFooterMetric(telemetry: VehicleTelemetry?, index: Int): String = when (index % 3) {
        0 -> {
            val odo = telemetry?.odometerKm?.takeIf { it > 0.0 }
            if (odo != null) "总里程 ${String.format(Locale.US, "%,d", odo.roundToInt())} km" else "总里程 -- km"
        }
        1 -> {
            val fuel = telemetry?.fuelPercent ?: telemetry?.electricitySurplus?.toDoubleOrNull()
            if (fuel != null) "剩余油量 ${fuel.roundToInt()}%" else "剩余油量 --%"
        }
        else -> {
            val range = telemetry?.remainingRangeKm?.takeIf { it > 0.0 }
            if (range != null) "剩余续航 ${range.roundToInt()} km" else "剩余续航 -- km"
        }
    }

    private fun handleHomeFooterTap() {
        val now = System.currentTimeMillis()
        if (footerTapCount == 0 || now - footerTapWindowStartMs > 2_500L) {
            footerTapWindowStartMs = now
            footerTapCount = 1
        } else {
            footerTapCount++
        }
        if (footerTapCount >= 5) {
            footerTapCount = 0
            footerTapWindowStartMs = 0L
            AppHaptics.perform(homeFooterView, HapticLevel.LONG_PRESS_MODE)
            showDetailedVehicleTelemetrySheet()
            return
        }

        AppHaptics.perform(homeFooterView, HapticLevel.LIGHT_TICK)
        footerMetricIndex = (footerMetricIndex + 1) % 3
        val nextText = formatHomeFooterMetric(activeTelemetry, footerMetricIndex)
        homeFooterView?.let { tv ->
            tv.animate().cancel()
            tv.animate()
                .alpha(0f)
                .setDuration(70L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .withEndAction {
                    tv.text = nextText
                    tv.animate()
                        .alpha(if (quickDeckExpanded) 0f else 1f)
                        .setDuration(140L)
                        .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                        .start()
                }
                .start()
        }
    }

    private fun showDetailedVehicleTelemetrySheet() {
        val t = activeTelemetry
        val profile = profileStore.getActiveProfile()

        fun formatDoorDetail(): String {
            if (t == null) return "--"
            val openLabels = mutableListOf<String>()
            if (isDoorOpenToken(t.frontLeftDoor.ifBlank { t.doorStates["frontLeft"].orEmpty() })) openLabels += "左前"
            if (isDoorOpenToken(t.frontRightDoor.ifBlank { t.doorStates["frontRight"].orEmpty() })) openLabels += "右前"
            if (isDoorOpenToken(t.backLeftDoor.ifBlank { t.doorStates["backLeft"].orEmpty() })) openLabels += "左后"
            if (isDoorOpenToken(t.backRightDoor.ifBlank { t.doorStates["backRight"].orEmpty() })) openLabels += "右后"
            return if (openLabels.isEmpty()) "全部关闭" else "${openLabels.joinToString("/")} 未关"
        }

        fun formatWindowDetail(): String {
            if (t == null) return "--"
            val openLabels = mutableListOf<String>()
            if (isDoorOpenToken(t.frontLeftWindowState.ifBlank { t.windowStates["frontLeft"].orEmpty() })) openLabels += "左前"
            if (isDoorOpenToken(t.frontRightWindowState.ifBlank { t.windowStates["frontRight"].orEmpty() })) openLabels += "右前"
            if (isDoorOpenToken(t.backLeftWindowState.ifBlank { t.windowStates["backLeft"].orEmpty() })) openLabels += "左后"
            if (isDoorOpenToken(t.backRightWindowState.ifBlank { t.windowStates["backRight"].orEmpty() })) openLabels += "右后"
            return if (openLabels.isEmpty()) "全部关闭" else "${openLabels.joinToString("/")} 未关"
        }

        fun formatTyreCell(pressureRaw: String, vararg tempKeys: String): String {
            val p = formatCornerTyrePressure(pressureRaw).takeIf { it != "--" }
            val temp = tempKeys.firstNotNullOfOrNull { key ->
                t?.conditionFields?.get(key)?.trim()?.takeIf { it.isNotBlank() && it != "255" }
                    ?: t?.conditionFields?.get(key.uppercase(Locale.US))?.trim()?.takeIf { it.isNotBlank() && it != "255" }
            }
            return when {
                p != null && temp != null -> "$p bar · ${temp}°C"
                p != null -> "$p bar"
                temp != null -> "${temp}°C"
                else -> "--"
            }
        }

        val outsideTemp = listOf("outsideTemp", "outCarTemp", "ambientTemp", "extTemp", "outdoorTemp", "envTemp")
            .firstNotNullOfOrNull { key ->
                t?.conditionFields?.get(key)?.toDoubleOrNull()
                    ?: t?.conditionFields?.get(key.uppercase(Locale.US))?.toDoubleOrNull()
            }
        val cabinTemp = cabinTemperature(t)
        val tempSummary = when {
            cabinTemp != null && outsideTemp != null ->
                "车内 ${cabinTemp.roundToInt()}°C / 车外 ${outsideTemp.roundToInt()}°C"
            cabinTemp != null -> "车内 ${cabinTemp.roundToInt()}°C"
            outsideTemp != null -> "车外 ${outsideTemp.roundToInt()}°C"
            else -> "--"
        }

        val isBleReady = bleConnected || bleController.isSessionReady()
        val rssiText = bleController.lastProximityRssi?.let { " ($it dBm)" }.orEmpty()
        val bleChannelText = when {
            isBleReady -> "BLE 已连接$rssiText"
            bleNearby || bleController.isVehicleNearby -> "附近已发现$rssiText"
            else -> "未连接（远程网络）"
        }
        val keyValidityText = when {
            !hasCompleteKeyMaterial(profile) -> "本地未同步"
            profile.validFrom.isNotBlank() && profile.validTo.isNotBlank() ->
                "${profile.validFrom.take(10)} ~ ${profile.validTo.take(10)}"
            else -> "本地已就绪"
        }
        val onlineText = when {
            t == null -> "--"
            t.online == "1" || t.online.equals("true", ignoreCase = true) -> "在线"
            t.online == "0" || t.online.equals("false", ignoreCase = true) -> "休眠 / 离线"
            t.online.isNotBlank() -> t.online
            else -> "--"
        }
        val hoodText = when {
            t == null || t.hood.isBlank() -> "--"
            isDoorOpenToken(t.hood) -> "未关闭"
            else -> "已关闭"
        }

        val sections = listOf(
            "能源与里程" to listOf(
                "总里程" to (t?.odometerKm?.takeIf { it > 0.0 }?.let { "${it.roundToInt()} km" }
                    ?: t?.odometer?.takeIf { it.isNotBlank() }?.let { "$it km" } ?: "--"),
                "剩余续航" to (t?.remainingRangeKm?.takeIf { it > 0.0 }?.let { "${it.roundToInt()} km" }
                    ?: t?.mileageSurplus?.takeIf { it.isNotBlank() }?.let { "$it km" } ?: "--"),
                "剩余油量" to (t?.fuelPercent?.let { "${it.roundToInt()}%" }
                    ?: t?.oilSurplus?.takeIf { it.isNotBlank() }?.let { "$it%" } ?: "--"),
                "蓄电池电压" to (t?.batteryVoltage?.let { String.format(Locale.US, "%.2f V", it) } ?: "--"),
                "车内 / 车外温度" to tempSummary
            ),
            "四轮胎压与胎温" to listOf(
                "左前轮" to formatTyreCell(t?.lFrontTyre.orEmpty(), "lFrontTyreTemp", "flTyreTemp", "tyreTempFL", "leftFrontTyreTemp"),
                "右前轮" to formatTyreCell(t?.rFrontTyre.orEmpty(), "rFrontTyreTemp", "frTyreTemp", "tyreTempFR", "rightFrontTyreTemp"),
                "左后轮" to formatTyreCell(t?.lRearTyre.orEmpty(), "lRearTyreTemp", "rlTyreTemp", "tyreTempRL", "leftRearTyreTemp"),
                "右后轮" to formatTyreCell(t?.rRearTyre.orEmpty(), "rRearTyreTemp", "rrTyreTemp", "tyreTempRR", "rightRearTyreTemp")
            ),
            "车身与门窗状态" to listOf(
                "门锁状态" to when (t?.doorLocked) {
                    true -> "已锁止"
                    false -> "已解锁"
                    null -> "--"
                },
                "四门状态" to formatDoorDetail(),
                "引擎盖" to hoodText,
                "后备箱 / 尾门" to when (t?.trunkOpen) {
                    true -> "已开启"
                    false -> "已关闭"
                    null -> "--"
                },
                "天窗状态" to formatSkylightLabel(t?.skylightState),
                "车窗状态" to formatWindowDetail(),
                "发动机状态" to when (t?.engineRunning) {
                    true -> "运行中"
                    false -> "已熄火"
                    null -> "--"
                },
                "空调状态" to when (t?.acOn) {
                    true -> "运行中"
                    false -> "已关闭"
                    null -> "--"
                }
            ),
            "连接与数字钥匙" to listOf(
                "云端在线状态" to onlineText,
                "BLE 通道与信号" to bleChannelText,
                "数字钥匙有效期" to keyValidityText,
                "车辆识别码 (VIN)" to maskedVin(profile.vin),
                "最近同步时间" to (t?.time?.takeIf { it.isNotBlank() } ?: "--")
            )
        )

        showBottomSheet("车辆详细指标") { sheet, dialog ->
            sections.forEachIndexed { sIdx, (sectionTitle, rows) ->
                sheet.addView(sectionLabel(sectionTitle), lp(top = if (sIdx == 0) dp(8) else dp(16), bottom = dp(8)))
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(TILE_BG, 12f)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                rows.forEachIndexed { rIdx, (k, v) ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(7), 0, dp(7))
                    }
                    row.addView(text(k, 13f, TEXT_SECONDARY), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(text(v, 13.5f, TEXT_PRIMARY, Typeface.BOLD).apply {
                        gravity = Gravity.END
                    })
                    card.addView(row)
                    if (rIdx < rows.lastIndex) {
                        val divider = View(this).apply { setBackgroundColor(Color.argb(38, 255, 255, 255)) }
                        card.addView(divider, lp(height = 1))
                    }
                }
                sheet.addView(card, lp())
            }
        }
    }

    private fun createMenuRow(
        icon: AppIconType,
        title: String,
        subtitle: String?,
        withStatusDot: Boolean,
        onClick: () -> Unit
    ): LinearLayout = createMenuRowWithRefs(icon, title, subtitle, withStatusDot, onClick).first

    private fun createMenuRowWithRefs(
        icon: AppIconType,
        title: String,
        initialSubtitle: String?,
        withStatusDot: Boolean,
        onClick: () -> Unit
    ): Triple<LinearLayout, TextView?, StatusDotView?> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(70)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.98f)
            setOnClickListener { onClick() }
        }

        val leftIcon = AppIconView(this, icon, Color.rgb(232, 236, 242))
        row.addView(leftIcon, LinearLayout.LayoutParams(dp(23), dp(23)))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dp(20)
            marginEnd = dp(12)
        })

        textCol.addView(text(title, 16.5f, TEXT_PRIMARY, Typeface.BOLD))

        var subtitleTv: TextView? = null
        var dotView: StatusDotView? = null
        if (initialSubtitle != null) {
            val subRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textCol.addView(subRow, lp(top = dp(4)))
            if (withStatusDot) {
                dotView = StatusDotView(this)
                subRow.addView(dotView, LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    marginEnd = dp(6)
                })
            }
            subtitleTv = text(initialSubtitle, 13f, TEXT_SECONDARY)
            subRow.addView(subtitleTv)
        }

        val chevron = AppIconView(this, AppIconType.CHEVRON_RIGHT, Color.rgb(112, 116, 126))
        row.addView(chevron, LinearLayout.LayoutParams(dp(15), dp(15)))

        return Triple(row, subtitleTv, dotView)
    }

    // =========================================================================
    // Home Screen State Synchronization (Includes doorLock & BLE 0x64/0x10 Fixes)
    // =========================================================================

    private fun refreshHomeHeaderAndRows() {
        if (currentScreen != Screen.HOME) return
        val telemetry = activeTelemetry
        val plateNo = firstNonBlank(
            telemetry?.licensePlate.orEmpty(),
            uiPrefs.getString(KEY_PLATE_NO, "").orEmpty()
        )
        val modelName = VehicleDisplayNames.normalizeCachedName(
            uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty(),
            plateNo
        )
        vehicleNameView?.text = modelName
        val hasBound = currentProfile?.vin?.isNotBlank() == true || profileStore.getActiveProfile().vin.isNotBlank()
        plateBadgeView?.apply {
            background = null
            if (plateNo.isNotBlank()) {
                text = plateNo
                setTextColor(ThemeTokens.TEXT_SUB)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                visibility = View.VISIBLE
            } else if (hasBound) {
                text = "设置车牌"
                setTextColor(ThemeTokens.ACCENT_LIGHT)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        val energy = telemetry?.fuelPercent ?: telemetry?.electricitySurplus?.toDoubleOrNull()
        val range = telemetry?.remainingRangeKm
        batteryPillView?.apply {
            levelPercent = energy?.roundToInt() ?: 0
            isChargingOrActive = telemetry?.engineRunning == true
        }
        energyTextView?.text = formatEnergyDisplay(telemetry)

        val locked = telemetry?.doorLocked
        val primaryMotionState = when {
            telemetry?.engineRunning == true -> "已启动"
            telemetry?.engineRunning == false -> "已驻车"
            locked == true -> "已锁止"
            locked == false -> "已解锁"
            else -> "状态待同步"
        }
        parkingStatusView?.text = primaryMotionState
        parkingStatusView?.contentDescription = "车辆主状态：$primaryMotionState"

        val effectiveLocked = locked ?: true
        val physicalDoorsOpen = arePhysicalDoorsOpen(telemetry)
        val acActive = telemetry?.acOn == true
        val trunkIsOpen = telemetry?.trunkOpen == true

        carVisual?.apply {
            modelKey = modelName.ifBlank { "arrizo8" }
            vehicleLocked = effectiveLocked
            doorsOpen = physicalDoorsOpen
            climateOn = acActive
            trunkOpen = trunkIsOpen
            skylightOpen = isSkylightOpen(telemetry?.skylightState)
            engineRunning = telemetry?.engineRunning == true
        }

        updateQuickActionIconStates()
        updateTpmsBadges(telemetry)
        if (quickDeckExpanded && !quickDeckEditMode) {
            renderQuickDeckGrid()
        }

        val cabinTemp = cabinTemperature(telemetry)
        climateRowSubtitle?.text = when {
            acActive && cabinTemp != null ->
                "空调已开启 · ${formatTemp(climateTemperatureCelsius)} · 车内 ${cabinTemp.roundToInt()}°C"
            acActive ->
                "空调已开启 · ${formatTemp(climateTemperatureCelsius)}"
            cabinTemp != null ->
                "空调已关闭 · 车内 ${cabinTemp.roundToInt()}°C"
            else -> "空调已关闭"
        }

        locationRowSubtitle?.text = formatLocationSubtitle(telemetry)
        updateHomeFooter(telemetry)
        updateSecurityRow()
    }

    private fun updateHomeFooter(telemetry: VehicleTelemetry?) {
        homeFooterView?.text = formatHomeFooterMetric(telemetry, footerMetricIndex)
    }

    private fun updateSecurityRow() {
        securitySubpageStatusText?.text = formatSecurityDigitalKeyStatus()
        if (currentScreen != Screen.HOME) return
        val isBleReady = bleConnected || bleController.isSessionReady()
        val isConnecting = !isBleReady && (bleNearby || bleController.isVehicleNearby)
        when {
            isBleReady -> {
                securityDotView?.state = StatusDotView.State.CONNECTED
                val rssi = bleController.lastProximityRssi?.let { " · $it dBm" }.orEmpty()
                securityRowSubtitle?.text = "手机钥匙已连接 (BLE 直连)$rssi"
                channelBadgeIcon?.apply {
                    iconType = AppIconType.BLUETOOTH_ICON
                    contentDescription = "蓝牙通道已连接"
                    MotionSpec.animateIconTint(this, ACCENT_BLUE)
                }
            }
            isConnecting -> {
                securityDotView?.state = StatusDotView.State.CONNECTING
                securityRowSubtitle?.text = "发现附近车辆 · 正在连接手机钥匙..."
                channelBadgeIcon?.apply {
                    iconType = AppIconType.BLUETOOTH_ICON
                    contentDescription = "蓝牙通道正在连接，当前使用远程网络"
                    MotionSpec.animateIconTint(this, TEXT_SECONDARY)
                }
            }
            else -> {
                securityDotView?.state = StatusDotView.State.DISCONNECTED
                securityRowSubtitle?.text = "手机钥匙已断开连接 · 远程网络控制"
                channelBadgeIcon?.apply {
                    iconType = AppIconType.BLUETOOTH_ICON
                    contentDescription = "蓝牙通道未连接，当前使用远程网络"
                    MotionSpec.animateIconTint(this, TEXT_SECONDARY)
                }
            }
        }
        refreshSecuritySheetIfOpen()
    }

    private fun formatLocationSubtitle(telemetry: VehicleTelemetry?): String {
        val lat = telemetry?.latitude
        val lon = telemetry?.longitude
        if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
            val key = streetLookupKey(lat, lon)
            val address = when {
                key == lastGeocodedKey && !lastGeocodedAddress.isNullOrBlank() -> lastGeocodedAddress!!
                key == lastGeocodedKey && lastGeocodeSettled -> "街道信息暂不可用"
                else -> {
                    triggerHomeReverseGeocode(lat, lon, key)
                    "街道信息待获取"
                }
            }
            val distance = formatDistanceToVehicle(lat, lon) ?: "距离暂不可用"
            return "$address · $distance"
        }
        return "车辆位置暂不可用"
    }

    private fun streetLookupKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%s:%.3f,%.3f", currentProfile?.vin.orEmpty(), lat, lon)

    private fun currentStreetLabel(): String {
        val lat = activeTelemetry?.latitude ?: return ""
        val lon = activeTelemetry?.longitude ?: return ""
        if (lat == 0.0 && lon == 0.0) return ""
        return lastGeocodedAddress.orEmpty().takeIf { streetLookupKey(lat, lon) == lastGeocodedKey }.orEmpty()
    }

    private fun triggerHomeReverseGeocode(lat: Double, lon: Double, key: String) {
        if (key == lastGeocodedKey) return
        lastGeocodedKey = key
        lastGeocodedAddress = null
        lastGeocodeSettled = false
        geocodeJob?.cancel()
        geocodeJob = lifecycleScope.launch {
            val addr = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.reverseGeocodeApproxAddress(lat, lon)
                }
            }.getOrNull()
            if (isActive && key == lastGeocodedKey && !addr.isNullOrBlank()) {
                lastGeocodedAddress = addr
            }
            if (isActive && key == lastGeocodedKey) {
                lastGeocodeSettled = true
                locationAddressObservers.toList().forEach { observer ->
                    observer(currentStreetLabel())
                }
                if (currentScreen == Screen.HOME) {
                    locationRowSubtitle?.text = formatLocationSubtitle(activeTelemetry)
                }
            }
        }
    }

    private fun formatDistanceToVehicle(vehicleGcjLat: Double, vehicleGcjLon: Double): String? {
        val phoneGcj = getBestDeviceGcj02Location() ?: return null
        val distM = haversineDistanceMeters(phoneGcj.first, phoneGcj.second, vehicleGcjLat, vehicleGcjLon)
        if (distM.isNaN() || distM.isInfinite() || distM < 0.0) return null
        return when {
            distM < 1000.0 -> "距 ${distM.roundToInt()} 米"
            else -> String.format(Locale.US, "距 %.1f 公里", distM / 1000.0)
        }
    }

    private fun getBestDeviceGcj02Location(): Pair<Double, Double>? {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best.time) {
                best = loc
            }
        }
        val chosen = best ?: return null
        return wgs84ToGcj02(chosen.latitude, chosen.longitude)
    }

    private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) {
            return lat to lon
        }
        val a = 6378245.0
        val ee = 0.00669342162296594323
        val x = lon - 105.0
        val y = lat - 35.0
        var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        dLat += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        dLat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        dLat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        dLon += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        dLon += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        dLon += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1.0 - ee * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1.0 - ee)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }

    // =========================================================================
    // Bottom Sheets for the 4 Menu Rows & Top-Right Account Menu
    // =========================================================================

    private fun showQuickActionsCustomizeSheet() {
        val draft = loadQuickActions().toMutableList()
        showBottomSheet("自定义快捷控制") { sheet, dialog ->
            sheet.addView(text("支持 1 至 6 项首页快捷控制，点击上方已选图标可移除", 12.5f, TEXT_SECONDARY).apply {
                layoutParams = lp(top = dp(6), bottom = dp(14))
            })

            val countLabel = text("", 13f, TEXT_SECONDARY, Typeface.BOLD)
            sheet.addView(countLabel, lp(bottom = dp(8)))

            val previewCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(TILE_BG, 12f, Color.rgb(54, 56, 64), 0.7f)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                minimumHeight = dp(64)
            }
            sheet.addView(previewCard, lp(bottom = dp(18)))

            sheet.addView(sectionLabel("全部快捷操作（点击添加或取消）"), lp(bottom = dp(10)))
            val gridHost = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            sheet.addView(gridHost, lp(bottom = dp(18)))

            var renderState: () -> Unit = {}
            renderState = {
                countLabel.text = "已选 ${draft.size}/6"
                previewCard.removeAllViews()
                draft.forEach { action ->
                    val itemBox = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        isClickable = true
                        isFocusable = true
                        contentDescription = "移除 ${action.label}"
                        setOnClickListener {
                            if (draft.size <= 1) {
                                Toast.makeText(this@MainActivity, "至少保留 1 项快捷控制", Toast.LENGTH_SHORT).show()
                            } else {
                                draft.remove(action)
                                renderState()
                            }
                        }
                    }
                    val icon = AppIconView(this, action.icon, TEXT_PRIMARY)
                    itemBox.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
                    val removeHint = text("移除", 10f, TEXT_SECONDARY).apply {
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                            topMargin = dp(4)
                        }
                    }
                    itemBox.addView(removeHint)
                    previewCard.addView(itemBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }

                gridHost.removeAllViews()
                val allActions = QuickActionId.values().toList()
                allActions.chunked(4).forEachIndexed { rowIdx, chunk ->
                    val rowView = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    chunk.forEachIndexed { colIdx, action ->
                        val selected = action in draft
                        val tile = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            background = if (selected) {
                                rounded(TILE_ACTIVE_BG, 12f, ACCENT_BLUE, 1f)
                            } else {
                                rounded(TILE_BG, 12f)
                            }
                            setPadding(dp(4), dp(12), dp(4), dp(10))
                            isClickable = true
                            isFocusable = true
                            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
                            setOnClickListener {
                                if (selected) {
                                    if (draft.size <= 1) {
                                        Toast.makeText(this@MainActivity, "至少保留 1 项快捷控制", Toast.LENGTH_SHORT).show()
                                    } else {
                                        draft.remove(action)
                                        renderState()
                                    }
                                } else {
                                    if (draft.size >= 6) {
                                        Toast.makeText(this@MainActivity, "最多选择 6 项快捷控制，请先移除一项", Toast.LENGTH_SHORT).show()
                                    } else {
                                        draft.add(action)
                                        renderState()
                                    }
                                }
                            }
                        }
                        val tileIcon = AppIconView(
                            this,
                            action.icon,
                            if (selected) ACCENT_BLUE else TEXT_PRIMARY
                        )
                        tile.addView(tileIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
                        val tileLabel = text(
                            action.label,
                            11.5f,
                            if (selected) TEXT_PRIMARY else TEXT_BODY,
                            if (selected) Typeface.BOLD else Typeface.NORMAL
                        ).apply {
                            gravity = Gravity.CENTER
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                                topMargin = dp(7)
                            }
                        }
                        tile.addView(tileLabel)
                        rowView.addView(tile, weightedLp(if (colIdx == 0) 0 else dp(8), dp(76)))
                    }
                    gridHost.addView(rowView, lp(top = if (rowIdx == 0) 0 else dp(8)))
                }
            }
            renderState()

            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btnRow.addView(secondaryActionButton("恢复默认") {
                draft.clear()
                draft.addAll(QuickActionId.DEFAULT_LIST)
                renderState()
            }, weightedLp(0, dp(46)))
            btnRow.addView(primaryActionButton("保存") {
                saveQuickActions(draft)
                rebuildQuickActionRow()
                dialog.dismiss()
                showHomeStatus("快捷控制已更新")
            }, weightedLp(dp(10), dp(46)))
            sheet.addView(btnRow, lp(top = dp(4)))
        }
    }

    private data class SwitchableVehicleItem(
        val vin: String,
        val displayName: String,
        val plateNo: String,
        val localProfile: VehicleProfile?,
        val cloudSummary: VehicleSummary?
    )

    private fun showVehicleSwitcherPopover(anchor: View) {
        vehicleSwitcherPopup?.dismiss()
        val activeProfile = profileStore.getActiveProfile()
        val activeVin = activeProfile.vin.trim().uppercase(Locale.US)
        val cachedModelName = uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty()
        val cachedPlateNo = uiPrefs.getString(KEY_PLATE_NO, "").orEmpty()

        fun buildOptions(items: List<SwitchableVehicleItem>): List<DropdownOption> =
            items.map { item ->
                val sub = listOfNotNull(
                    item.plateNo.takeIf { it.isNotBlank() },
                    maskedVin(item.vin)
                ).joinToString(" · ")
                DropdownOption(
                    id = item.vin,
                    title = item.displayName,
                    subtitle = sub,
                    selected = item.vin.equals(activeVin, ignoreCase = true)
                )
            } + DropdownOption(
                id = "__MANAGE_VEHICLES__",
                title = "车辆管理",
                subtitle = "查看座驾档案与编辑信息",
                selected = false
            )

        fun presentDropdown(items: List<SwitchableVehicleItem>) {
            vehicleSwitcherPopup?.dismiss()
            val itemByVin = items.associateBy { it.vin }
            val popup = AppComponents.showAnchoredDropdown(
                context = this,
                anchor = anchor,
                options = buildOptions(items),
                widthDp = 236
            ) { chosen ->
                when (chosen.id) {
                    "__MANAGE_VEHICLES__" -> openVehicleManagementSubpage()
                    else -> itemByVin[chosen.id]?.let { selectedItem ->
                        activateSelectedVehicle(selectedItem, activeProfile, null)
                    }
                }
            }
            vehicleSwitcherPopup = popup
            popup.setOnDismissListener {
                if (vehicleSwitcherPopup === popup) vehicleSwitcherPopup = null
            }
        }

        val localProfiles = profileStore.listProfiles().filter { it.vin.isNotBlank() }
        val initialItems = localProfiles.map { p ->
            val vinKey = p.vin.trim().uppercase(Locale.US)
            val isCurrent = vinKey.equals(activeVin, ignoreCase = true)
            SwitchableVehicleItem(
                vin = vinKey,
                displayName = if (isCurrent && cachedModelName.isNotBlank()) cachedModelName else p.bleName.ifBlank { "我的座驾" },
                plateNo = if (isCurrent) cachedPlateNo else "",
                localProfile = p,
                cloudSummary = null
            )
        }
        presentDropdown(initialItems)

        if (activeProfile.accessToken.isNotBlank()) {
            lifecycleScope.launch {
                val cloudVehicles = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchVehicleList(activeProfile.accessToken)
                    }
                }.getOrNull() ?: return@launch
                if (vehicleSwitcherPopup?.isShowing != true) return@launch

                val localByVin = profileStore.listProfiles()
                    .filter { it.vin.isNotBlank() }
                    .associateBy { it.vin.trim().uppercase(Locale.US) }
                val merged = LinkedHashMap<String, SwitchableVehicleItem>()
                cloudVehicles.filter { it.vin.isNotBlank() }.forEach { v ->
                    val vinKey = v.vin.trim().uppercase(Locale.US)
                    merged[vinKey] = SwitchableVehicleItem(
                        vin = vinKey,
                        displayName = vehicleModelName(v),
                        plateNo = v.plateNo.trim(),
                        localProfile = localByVin[vinKey],
                        cloudSummary = v
                    )
                }
                localByVin.forEach { (vinKey, p) ->
                    if (!merged.containsKey(vinKey)) {
                        val isCurrent = vinKey.equals(activeVin, ignoreCase = true)
                        merged[vinKey] = SwitchableVehicleItem(
                            vin = vinKey,
                            displayName = if (isCurrent && cachedModelName.isNotBlank()) cachedModelName else p.bleName.ifBlank { "我的座驾" },
                            plateNo = if (isCurrent) cachedPlateNo else "",
                            localProfile = p,
                            cloudSummary = null
                        )
                    }
                }
                val mergedList = merged.values.toList()
                if (mergedList.size != initialItems.size || mergedList.any { it.plateNo.isNotBlank() && cachedPlateNo.isBlank() }) {
                    presentDropdown(mergedList)
                }
            }
        }
    }

    private fun showVehicleSwitcherSheet() {
        val activeProfile = profileStore.getActiveProfile()
        val activeVin = activeProfile.vin.trim().uppercase(Locale.US)
        val cachedModelName = uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty()
        val cachedPlateNo = uiPrefs.getString(KEY_PLATE_NO, "").orEmpty()

        showBottomSheet("切换车辆") { sheet, dialog ->
            val statusHint = text("正在同步云端绑定车辆...", 12.5f, TEXT_SECONDARY).apply {
                layoutParams = lp(top = dp(6), bottom = dp(12))
            }
            sheet.addView(statusHint)

            val listHost = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            sheet.addView(listHost, lp())

            fun renderVehicles(items: List<SwitchableVehicleItem>) {
                listHost.removeAllViews()
                if (items.isEmpty()) {
                    listHost.addView(text("暂未发现已绑定车辆", 14f, TEXT_SECONDARY).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(24), 0, dp(24))
                    })
                    return
                }
                items.forEachIndexed { idx, item ->
                    val isActive = item.vin.equals(activeVin, ignoreCase = true)
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = if (isActive) {
                            rounded(TILE_ACTIVE_BG, 12f, ACCENT_BLUE, 1f)
                        } else {
                            rounded(TILE_BG, 12f)
                        }
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        isClickable = true
                        isFocusable = true
                        MotionSpec.attachPressPhysics(this, pressedScale = 0.97f)
                        setOnClickListener {
                            activateSelectedVehicle(item, activeProfile, dialog)
                        }
                    }

                    val textCol = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    textCol.addView(text(item.displayName, 16f, TEXT_PRIMARY, Typeface.BOLD))
                    val sub = listOfNotNull(
                        item.plateNo.takeIf { it.isNotBlank() },
                        maskedVin(item.vin)
                    ).joinToString(" · ")
                    textCol.addView(text(sub, 12.5f, TEXT_SECONDARY).apply {
                        layoutParams = lp(top = dp(4))
                    })
                    card.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

                    if (isActive) {
                        val checkIcon = AppIconView(this, AppIconType.CHECK_CIRCLE, ACCENT_BLUE)
                        card.addView(checkIcon, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                            marginStart = dp(12)
                        })
                    }
                    listHost.addView(card, lp(top = if (idx == 0) 0 else dp(10)))
                }
            }

            val localProfiles = profileStore.listProfiles().filter { it.vin.isNotBlank() }
            val initialItems = localProfiles.map { p ->
                val vinKey = p.vin.trim().uppercase(Locale.US)
                val isCurrent = vinKey.equals(activeVin, ignoreCase = true)
                SwitchableVehicleItem(
                    vin = vinKey,
                    displayName = if (isCurrent && cachedModelName.isNotBlank()) cachedModelName else p.bleName.ifBlank { "我的座驾" },
                    plateNo = if (isCurrent) cachedPlateNo else "",
                    localProfile = p,
                    cloudSummary = null
                )
            }
            renderVehicles(initialItems)

            if (activeProfile.accessToken.isBlank()) {
                statusHint.text = "已加载本机车辆档案（${initialItems.size} 辆）"
                return@showBottomSheet
            }

            lifecycleScope.launch {
                val cloudResult = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchVehicleList(activeProfile.accessToken)
                    }
                }
                if (!dialog.isShowing) return@launch

                cloudResult.onSuccess { cloudVehicles ->
                    val localByVin = profileStore.listProfiles()
                        .filter { it.vin.isNotBlank() }
                        .associateBy { it.vin.trim().uppercase(Locale.US) }
                    val merged = LinkedHashMap<String, SwitchableVehicleItem>()

                    cloudVehicles.filter { it.vin.isNotBlank() }.forEach { v ->
                        val vinKey = v.vin.trim().uppercase(Locale.US)
                        merged[vinKey] = SwitchableVehicleItem(
                            vin = vinKey,
                            displayName = vehicleModelName(v),
                            plateNo = v.plateNo.trim(),
                            localProfile = localByVin[vinKey],
                            cloudSummary = v
                        )
                    }
                    localByVin.forEach { (vinKey, p) ->
                        if (!merged.containsKey(vinKey)) {
                            val isCurrent = vinKey.equals(activeVin, ignoreCase = true)
                            merged[vinKey] = SwitchableVehicleItem(
                                vin = vinKey,
                                displayName = if (isCurrent && cachedModelName.isNotBlank()) cachedModelName else p.bleName.ifBlank { "我的座驾" },
                                plateNo = if (isCurrent) cachedPlateNo else "",
                                localProfile = p,
                                cloudSummary = null
                            )
                        }
                    }

                    val list = merged.values.toList()
                    statusHint.text = "共 ${list.size} 辆已绑定车辆，点击切换"
                    renderVehicles(list)
                }.onFailure {
                    statusHint.text = "已显示本机车辆（云端列表同步失败）"
                }
            }
        }
    }

    private fun activateSelectedVehicle(
        item: SwitchableVehicleItem,
        activeProfile: VehicleProfile,
        dialog: android.app.Dialog?
    ) {
        val existing = item.localProfile
        val cloud = item.cloudSummary
        val updatedProfile = VehicleProfile(
            vin = item.vin,
            userId = firstNonBlank(existing?.userId.orEmpty(), activeProfile.userId),
            mobileNo = firstNonBlank(existing?.mobileNo.orEmpty(), activeProfile.mobileNo),
            bleName = firstNonBlank(cloud?.bleName.orEmpty(), existing?.bleName.orEmpty()),
            bleMac = firstNonBlank(cloud?.bleMac.orEmpty(), existing?.bleMac.orEmpty()),
            deviceId = firstNonBlank(existing?.deviceId.orEmpty(), activeProfile.deviceId, profileStore.defaultDeviceId()),
            accountType = cloud?.accountType ?: existing?.accountType ?: activeProfile.accountType,
            rootKeyHex = existing?.rootKeyHex.orEmpty(),
            infoHex = existing?.infoHex.orEmpty(),
            edfHex = existing?.edfHex.orEmpty(),
            validFrom = existing?.validFrom.orEmpty(),
            validTo = existing?.validTo.orEmpty(),
            secAHex = existing?.secAHex.orEmpty(),
            secBHex = existing?.secBHex.orEmpty(),
            keyRole = existing?.keyRole ?: 1,
            accessToken = firstNonBlank(activeProfile.accessToken, existing?.accessToken.orEmpty()),
            sdkToken = firstNonBlank(activeProfile.sdkToken, existing?.sdkToken.orEmpty()),
            controlPin = firstNonBlank(existing?.controlPin.orEmpty(), sessionVehiclePin, activeProfile.controlPin)
        )

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                profileStore.saveProfile(updatedProfile, activate = true)
            }
            uiPrefs.edit()
                .putString(KEY_MODEL_NAME, item.displayName)
                .putString(KEY_PLATE_NO, item.plateNo)
                .apply()

            currentProfile = saved
            sessionVehiclePin = saved.controlPin
            activeTelemetry = null
            telemetryJob?.cancel()
            bleController.stopAutoProximityWatch()
            bleController.disconnect()
            bleConnected = false
            bleNearby = false
            lastPairingPin = ""

            carVisual?.modelKey = item.displayName.ifBlank { "arrizo8" }
            dialog?.dismiss()
            refreshHomeHeaderAndRows()
            startProximityWhenPermitted()
            refreshTelemetry(silent = false)
            if (!hasCompleteKeyMaterial(saved)) {
                launchCloudProvision(saved, automatic = true)
            }
            showHomeStatus("已切换至 ${item.displayName}")
        }
    }

    private fun showControlsSheet() {
        val telemetry = activeTelemetry
        val locked = telemetry?.doorLocked ?: true
        val doorsOpenNow = arePhysicalDoorsOpen(telemetry)
        val trunkOpenNow = telemetry?.trunkOpen == true
        val skylightOpenNow = isSkylightOpen(telemetry?.skylightState)
        val skylightTiltNow = telemetry?.skylightState.orEmpty().trim().lowercase(Locale.US) in setOf("12", "tilt")
        val engineRunningNow = telemetry?.engineRunning == true

        showBottomSheet("控制") { sheet, dialog ->
            val statusSummary = listOf(
                "门锁 ${if (locked) "已锁" else "已解锁"}",
                "四门 ${if (doorsOpenNow) "未关" else "已关"}",
                "后备箱 ${if (trunkOpenNow) "已开" else "已关"}",
                "天窗 ${formatSkylightLabel(telemetry?.skylightState)}",
                "引擎 ${if (engineRunningNow) "运行" else "熄火"}"
            ).joinToString(" · ")
            sheet.addView(text(statusSummary, 12.5f, TEXT_SECONDARY).apply {
                setLineSpacing(0f, 1.35f)
                layoutParams = lp(top = dp(6), bottom = dp(14))
            })

            sheet.addView(sectionLabel("车门锁"))
            sheet.addView(controlTileRow(
                ControlTileSpec(AppIconType.LOCK_OPEN, "解锁车门", active = !locked) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("unlock", "车门解锁")
                },
                ControlTileSpec(AppIconType.LOCK_CLOSED, "锁止车门", active = locked) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("lock", "车门锁止")
                }
            ), lp(top = dp(8)))

            sheet.addView(sectionLabel("车窗"), lp(top = dp(16)))
            sheet.addView(controlTileRow(
                ControlTileSpec(AppIconType.WINDOW_OPEN, "车窗全开") {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("window_open", "车窗全开")
                },
                ControlTileSpec(AppIconType.WINDOW_VENT, "留缝透气") {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("window_ventilate", "车窗透气")
                },
                ControlTileSpec(AppIconType.WINDOW_CLOSE, "车窗全关") {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("window_close", "车窗全关")
                }
            ), lp(top = dp(8)))

            sheet.addView(sectionLabel("天窗"), lp(top = dp(16)))
            sheet.addView(controlTileRow(
                ControlTileSpec(AppIconType.SUNROOF_OPEN, "打开天窗", active = skylightOpenNow && !skylightTiltNow) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("skylight_open", "打开天窗")
                },
                ControlTileSpec(AppIconType.SUNROOF_TILT, "天窗翘起", active = skylightTiltNow) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("skylight_tilt", "天窗翘起")
                },
                ControlTileSpec(AppIconType.SUNROOF_CLOSE, "关闭天窗", active = !skylightOpenNow && telemetry != null) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("skylight_close", "关闭天窗")
                }
            ), lp(top = dp(8)))

            sheet.addView(sectionLabel("后备箱与尾门"), lp(top = dp(16)))
            sheet.addView(controlTileRow(
                ControlTileSpec(AppIconType.LOCK_OPEN, "解锁后备箱") {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("trunk_open", "开启后备箱")
                },
                ControlTileSpec(AppIconType.TRUNK_OPEN, "打开尾门", active = trunkOpenNow) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("tailgate_open", "打开尾门")
                },
                ControlTileSpec(AppIconType.TAILGATE_CLOSE, "关闭尾门", active = telemetry?.trunkOpen == false) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("tailgate_close", "关闭尾门")
                }
            ), lp(top = dp(8)))

            sheet.addView(sectionLabel("寻车与动力"), lp(top = dp(16)))
            sheet.addView(controlTileRow(
                ControlTileSpec(AppIconType.HORN_SOUND, "闪灯鸣笛") {
                    dialog.dismiss()
                    carVisual?.flashLights()
                    dispatchSmartVehicleCommand("find_car", "闪灯鸣笛寻车")
                },
                ControlTileSpec(AppIconType.ENGINE_POWER, "远程启动", active = engineRunningNow) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("engine_start", "远程启动引擎")
                },
                ControlTileSpec(AppIconType.ENGINE_STOP, "远程熄火", active = telemetry?.engineRunning == false) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("engine_stop", "远程熄火")
                }
            ), lp(top = dp(8)))
        }
    }

    private fun showClimateSheet() {
        val cabin = cabinTemperature(activeTelemetry)
        val acActive = activeTelemetry?.acOn == true

        showBottomSheet("温度") { sheet, dialog ->
            val sub = buildString {
                append(if (acActive) "空调运行中" else "空调已关闭")
                if (cabin != null) append("  ·  车内 ${cabin.roundToInt()}°C")
            }
            sheet.addView(text(sub, 13f, TEXT_SECONDARY).apply {
                layoutParams = lp(top = dp(6))
            })

            val stepper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            sheet.addView(stepper, lp(top = dp(24), bottom = dp(24)))

            val minus = sheetCircleButton("−") {
                AppHaptics.perform(sheetClimateTempView, HapticLevel.LIGHT_TICK)
                climateTemperatureCelsius = (climateTemperatureCelsius - 0.5).coerceAtLeast(16.0)
                sheetClimateTempView?.text = formatTemp(climateTemperatureCelsius)
            }
            sheetClimateTempView = text(formatTemp(climateTemperatureCelsius), 34f, TEXT_PRIMARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(26), 0, dp(26), 0)
            }
            val plus = sheetCircleButton("+") {
                AppHaptics.perform(sheetClimateTempView, HapticLevel.LIGHT_TICK)
                climateTemperatureCelsius = (climateTemperatureCelsius + 0.5).coerceAtMost(32.0)
                sheetClimateTempView?.text = formatTemp(climateTemperatureCelsius)
            }
            stepper.addView(minus, LinearLayout.LayoutParams(dp(48), dp(48)))
            stepper.addView(sheetClimateTempView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            stepper.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))

            sheet.addView(primaryActionButton("开启空调") {
                dialog.dismiss()
                dispatchSmartVehicleCommand(
                    "ac_on",
                    "开启空调 (${formatTemp(climateTemperatureCelsius)})",
                    temperatureCelsius = climateTemperatureCelsius
                )
            }, lp(height = dp(48)))

            sheet.addView(secondaryActionButton("关闭空调") {
                dialog.dismiss()
                dispatchSmartVehicleCommand("ac_off", "关闭空调")
            }, lp(top = dp(12), height = dp(48)))
        }
    }

    private fun refreshSecuritySheetIfOpen() {
        val statusTv = sheetKeyStatusView ?: return
        val profile = profileStore.getActiveProfile()
        val ready = hasCompleteKeyMaterial(profile)
        val bleReady = bleConnected || bleController.isSessionReady()
        val lines = mutableListOf<String>()
        lines += when {
            bleReady -> "通道状态：手机钥匙已连接（BLE 近场直连）"
            bleNearby || bleController.isVehicleNearby -> "通道状态：发现附近车辆，正在建立蓝牙会话..."
            else -> "通道状态：手机钥匙已断开连接（当前使用远程网络）"
        }
        lines += if (ready) {
            "本地钥匙材料：已就绪（靠近车辆自动无感连接）"
        } else {
            "本地钥匙材料：尚未同步，请点击下方一键拉取"
        }
        if (lastPairingPin.isNotBlank() && !bleReady) {
            lines += "最近动态配对码：$lastPairingPin"
        }
        statusTv.text = lines.joinToString("\n")

        sheetKeyActionButton?.apply {
            isEnabled = !provisioningActive
            alpha = if (provisioningActive) 0.6f else 1f
            text = when {
                provisioningActive -> "正在从云端拉取并解封..."
                ready -> "重新从云端拉取并更新钥匙"
                else -> "从云端一键拉取并解封新车钥匙"
            }
        }
    }

    private fun showPinConfigSheet(profile: VehicleProfile) {
        showInputSheet(
            title = "远程控车安全密码",
            message = "请输入 6 位数字车控安全密码，仅保存在本机用于远程网络控车校验。",
            hint = "6 位安全密码",
            numeric = true,
            confirmText = "保存"
        ) { pin ->
            if (!PIN_REGEX.matches(pin)) {
                Toast.makeText(this, "请输入 6 位数字密码", Toast.LENGTH_SHORT).show()
                return@showInputSheet
            }
            sessionVehiclePin = pin
            currentProfile = profileStore.saveProfile(profile.copy(controlPin = pin), activate = true)
            showHomeStatus("安全密码已保存")
        }
    }

    // =========================================================================
    // Smart Dual-Mode Command Router (BLE Nearby -> HTTP Fallback)
    // =========================================================================

    private fun dispatchSmartVehicleCommand(
        actionKey: String,
        label: String,
        temperatureCelsius: Double? = null
    ) {
        val profile = profileStore.getActiveProfile().also { currentProfile = it }
        if (profile.vin.isBlank()) {
            showHomeStatus("尚未同步绑定车辆")
            return
        }

        val bleRoute = bleController.isSessionReady() ||
            bleController.isVehicleNearby ||
            bleNearby
        if (bleRoute) {
            lastBleActionLabel = label
            val accepted = if (temperatureCelsius != null) {
                bleController.sendVehicleAction(actionKey, temperatureCelsius, 10)
            } else {
                bleController.sendVehicleAction(actionKey)
            }
            if (accepted) {
                applyOptimisticAction(actionKey)
                showHomeStatus(
                    if (bleController.isSessionReady()) {
                        "蓝牙直连已发送：$label"
                    } else {
                        "蓝牙钥匙连接中，已排队：$label"
                    }
                )
                scheduleTelemetryRefresh()
                return
            }
        }

        val requirePinEveryTime = uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, false)
        val cachedPin = sessionVehiclePin.ifBlank { profile.controlPin }
        if (requirePinEveryTime || cachedPin.isBlank()) {
            showVehiclePinSheet(profile, actionKey, label, temperatureCelsius)
        } else {
            sessionVehiclePin = cachedPin
            sendHttpVehicleCommand(profile, actionKey, label, temperatureCelsius, cachedPin)
        }
    }

    private fun showVehiclePinSheet(
        profile: VehicleProfile,
        actionKey: String,
        label: String,
        temperatureCelsius: Double?
    ) {
        appDialog?.dismiss()
        val dialog = SixDigitPinSheet.showVerifyPinSheet(
            context = this,
            title = "输入车控安全密码",
            subtitle = "请输入 6 位数字密码以执行「$label」"
        ) { pin, showError, dismiss ->
            val command = remoteCommandFor(actionKey)
            if (command == null) {
                showError("暂不支持该远程控制动作")
                return@showVerifyPinSheet
            }
            val params = if (temperatureCelsius != null) {
                val rounded = temperatureCelsius.roundToInt().toString()
                mapOf("temperature" to rounded, "airTemperature" to rounded)
            } else {
                emptyMap()
            }
            lifecycleScope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.sendRemoteCommand(
                            vin = profile.vin,
                            userId = profile.userId,
                            accessToken = profile.accessToken,
                            sdkToken = profile.sdkToken,
                            command = command,
                            pin = pin,
                            params = params
                        )
                    }
                }
                res.onFailure { err ->
                    if (err is CherySessionExpiredException) {
                        dismiss()
                    } else {
                        showError(err.message.orEmpty().ifBlank { "指令发送失败" }.take(60))
                    }
                    return@launch
                }
                val result = res.getOrThrow()
                if (result.isPinError) {
                    sessionVehiclePin = ""
                    val cleared = profile.copy(controlPin = "")
                    currentProfile = cleared
                    if (profile.vin.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            profileStore.saveProfile(cleared, activate = true)
                        }
                    }
                    showError("车控安全密码错误，请重新输入")
                } else {
                    dismiss()
                    handleRemoteResult(result, profile, actionKey, label, temperatureCelsius, pin)
                }
            }
        }
        appDialog = dialog
    }

    private fun sendHttpVehicleCommand(
        profile: VehicleProfile,
        actionKey: String,
        label: String,
        temperatureCelsius: Double?,
        pin: String
    ) {
        val command = remoteCommandFor(actionKey) ?: run {
            showHomeStatus("暂不支持该远程控制动作")
            return
        }
        val params = if (temperatureCelsius != null) {
            val rounded = temperatureCelsius.roundToInt().toString()
            mapOf("temperature" to rounded, "airTemperature" to rounded)
        } else {
            emptyMap()
        }
        showHomeStatus("正在下发：$label...")
        lifecycleScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.sendRemoteCommand(
                        vin = profile.vin,
                        userId = profile.userId,
                        accessToken = profile.accessToken,
                        sdkToken = profile.sdkToken,
                        command = command,
                        pin = pin,
                        params = params
                    )
                }
            }
            res.onSuccess { result ->
                handleRemoteResult(result, profile, actionKey, label, temperatureCelsius, pin)
            }.onFailure { err ->
                if (err !is CherySessionExpiredException) {
                    showHomeStatus("控车失败：${err.message.orEmpty().take(80)}")
                }
            }
        }
    }

    private fun handleRemoteResult(
        result: RemoteCommandResult,
        profile: VehicleProfile,
        actionKey: String,
        label: String,
        temperatureCelsius: Double?,
        usedPin: String
    ) {
        if (result.success) {
            if (usedPin.isNotBlank()) {
                sessionVehiclePin = usedPin
                if (profile.controlPin != usedPin && profile.vin.isNotBlank()) {
                    lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            profileStore.saveProfile(profile.copy(controlPin = usedPin), activate = true)
                        }
                        currentProfile = saved
                    }
                }
            }
            applyOptimisticAction(actionKey)
            showHomeStatus("已完成：$label")
            scheduleTelemetryRefresh()
        } else if (result.isPinError) {
            sessionVehiclePin = ""
            val cleared = profile.copy(controlPin = "")
            currentProfile = cleared
            if (profile.vin.isNotBlank()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        profileStore.saveProfile(cleared, activate = true)
                    }
                }
            }
            showHomeStatus("车控安全密码错误，请重新输入")
            showVehiclePinSheet(cleared, actionKey, label, temperatureCelsius)
        } else {
            AppHaptics.perform(contentFrame, HapticLevel.ERROR_REJECT)
            val msg = result.error ?: result.resultMessage.ifBlank { "云端未受理指令" }
            showHomeStatus("控制失败：${msg.take(90)}")
        }
    }

    private fun applyOptimisticAction(actionKey: String) {
        val current = activeTelemetry
        if (current != null) {
            activeTelemetry = when (actionKey) {
                "lock" -> current.copy(doorLocked = true, doorLockState = "0", doorLock = "0")
                "unlock" -> current.copy(doorLocked = false, doorLockState = "1", doorLock = "1")
                "ac_on" -> current.copy(acOn = true, acState = "1", airState = "1")
                "ac_off" -> current.copy(acOn = false, acState = "0", airState = "0")
                "tailgate_open", "trunk_open" -> current.copy(trunkOpen = true, trunkState = "1", tailgateState = "1")
                "tailgate_close", "trunk_close" -> current.copy(trunkOpen = false, trunkState = "0", tailgateState = "0")
                "skylight_open" -> current.copy(skylightState = "open")
                "skylight_tilt" -> current.copy(skylightState = "tilt")
                "skylight_close" -> current.copy(skylightState = "closed")
                "window_open", "window_ventilate" -> current.copy(
                    frontLeftWindowState = "open",
                    frontRightWindowState = "open",
                    backLeftWindowState = "open",
                    backRightWindowState = "open",
                    windowStates = current.windowStates + mapOf(
                        "frontLeft" to "open",
                        "frontRight" to "open",
                        "backLeft" to "open",
                        "backRight" to "open"
                    )
                )
                "window_close" -> current.copy(
                    frontLeftWindowState = "closed",
                    frontRightWindowState = "closed",
                    backLeftWindowState = "closed",
                    backRightWindowState = "closed",
                    windowStates = current.windowStates + mapOf(
                        "frontLeft" to "closed",
                        "frontRight" to "closed",
                        "backLeft" to "closed",
                        "backRight" to "closed"
                    )
                )
                "engine_start" -> current.copy(engineRunning = true, engineState = "1")
                "engine_stop" -> current.copy(engineRunning = false, engineState = "0")
                else -> current
            }
        } else {
            when (actionKey) {
                "lock" -> carVisual?.vehicleLocked = true
                "unlock" -> carVisual?.vehicleLocked = false
                "ac_on" -> carVisual?.climateOn = true
                "ac_off" -> carVisual?.climateOn = false
            }
        }
        refreshHomeHeaderAndRows()
    }

    private fun remoteCommandFor(actionKey: String): RemoteCommand? = when (actionKey) {
        "lock" -> RemoteCommand.DOOR_LOCK
        "unlock" -> RemoteCommand.DOOR_UNLOCK
        "ac_on" -> RemoteCommand.AC_ON
        "ac_off" -> RemoteCommand.AC_OFF
        "find_car" -> RemoteCommand.FIND_CAR
        "window_ventilate" -> RemoteCommand.WINDOW_VENTILATE
        "window_open" -> RemoteCommand.WINDOW_OPEN
        "window_close" -> RemoteCommand.WINDOW_CLOSE
        "skylight_open" -> RemoteCommand.SKYLIGHT_OPEN
        "skylight_tilt" -> RemoteCommand.SKYLIGHT_TILT
        "skylight_close" -> RemoteCommand.SKYLIGHT_CLOSE
        "engine_start" -> RemoteCommand.ENGINE_START
        "engine_stop" -> RemoteCommand.ENGINE_STOP
        "tailgate_open" -> RemoteCommand.TAILGATE_OPEN
        "tailgate_close" -> RemoteCommand.TAILGATE_CLOSE
        "trunk_open" -> RemoteCommand.TRUNK_UNLOCK
        else -> null
    }

    private fun refreshTelemetry(silent: Boolean) {
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank() || profile.accessToken.isBlank()) return
        if (telemetryJob?.isActive == true) return
        if (!silent) showHomeStatus("正在同步车况...")
        telemetryJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchVehicleStatus(
                        vin = profile.vin,
                        accessToken = profile.accessToken,
                        sdkToken = profile.sdkToken,
                        userId = profile.userId
                    )
                }
            }
            result.onSuccess {
                activeTelemetry = it
                val resolvedPlate = firstNonBlank(
                    it.licensePlate,
                    it.conditionFields["licensePlate"].orEmpty(),
                    it.conditionFields["licensenum"].orEmpty(),
                    it.conditionFields["licenseNum"].orEmpty(),
                    it.conditionFields["plateNo"].orEmpty()
                )
                if (resolvedPlate.isNotBlank()) {
                    uiPrefs.edit().putString(KEY_PLATE_NO, resolvedPlate).apply()
                }
                refreshHomeHeaderAndRows()
                if (!silent) showHomeStatus("车况已更新")
            }.onFailure {
                if (!silent) showHomeStatus("车况同步失败：${it.message.orEmpty().take(80)}")
            }
        }
    }

    private fun scheduleTelemetryRefresh() {
        lifecycleScope.launch {
            delay(1_500L)
            refreshTelemetry(silent = true)
        }
    }

    private fun launchCloudProvision(profile: VehicleProfile, automatic: Boolean) {
        if (provisioningActive) return
        if (profile.userId.isBlank() || profile.mobileNo.isBlank()) {
            if (!automatic) showHomeStatus("账号信息不完整，无法拉取数字钥匙")
            return
        }
        provisioningActive = true
        refreshSecuritySheetIfOpen()
        provisioningJob = lifecycleScope.launch {
            try {
                if (!automatic) showHomeStatus("正在从云端拉取并解封数字钥匙...")
                val result = runCatching {
                    provisioner.provisionVehicleKeyAsync(
                        userId = profile.userId,
                        mobileNo = profile.mobileNo,
                        vin = profile.vin,
                        bleName = profile.bleName,
                        bleMac = profile.bleMac,
                        deviceId = profile.deviceId,
                        accountType = profile.accountType,
                        profileStore = profileStore
                    )
                }.getOrNull()
                if (!isActive) return@launch
                if (result?.success == true) {
                    val provisioned = result.profile ?: profile
                    val merged = withContext(Dispatchers.IO) {
                        profileStore.saveProfile(
                            provisioned.copy(
                                userId = profile.userId,
                                mobileNo = profile.mobileNo,
                                deviceId = profile.deviceId,
                                accountType = profile.accountType,
                                accessToken = profile.accessToken,
                                sdkToken = profile.sdkToken,
                                controlPin = profile.controlPin
                            ),
                            activate = true
                        )
                    }
                    currentProfile = merged
                    showHomeStatus("手机数字钥匙已同步")
                    startProximityWhenPermitted()
                } else if (!automatic) {
                    val reason = result?.error ?: result?.message ?: "云端未返回有效钥匙材料"
                    showHomeStatus("钥匙同步失败：${reason.take(90)}")
                }
            } finally {
                if (isActive) {
                    provisioningActive = false
                    refreshSecuritySheetIfOpen()
                }
            }
        }
    }

    private fun logoutToWelcome() {
        clearActiveSessionAndUiState()
        showWelcomeScreen()
    }

    /**
     * Global handler for zyhl resultCode `0406` (session/token expired):
     * debounced across concurrent requests, clears expired session tokens & overlays,
     * shows a clear prompt, and redirects directly to the login form.
     */
    private fun handleSessionExpired(reason: String) {
        if (handlingSessionExpired) return
        val alreadyAtAuthScreen = (
            currentScreen == Screen.PRIVACY_CONSENT ||
                currentScreen == Screen.WELCOME ||
                currentScreen == Screen.LOGIN_FORM
            ) && subpageOverlayStack.isEmpty()
        if (alreadyAtAuthScreen) {
            httpClient.clearSession()
            return
        }
        handlingSessionExpired = true
        clearActiveSessionAndUiState()
        Toast.makeText(
            this,
            reason.ifBlank { "登录已失效，请重新登录" },
            Toast.LENGTH_LONG
        ).show()
        showLoginFormScreen(startWithSms = false)
        mainHandler.post { handlingSessionExpired = false }
    }

    private fun clearActiveSessionAndUiState() {
        provisioningJob?.cancel()
        provisioningActive = false
        telemetryJob?.cancel()
        stopPeriodicHomeRefresh()
        geocodeJob?.cancel()
        smsCountdownJob?.cancel()
        stopStatusAutoHide()
        bleController.stopAutoProximityWatch()
        bleController.disconnect()
        httpClient.clearSession()
        profileStore.clearAllProfiles()
        // Keep privacy consent accepted when session expires or user logs out
        uiPrefs.edit()
            .remove(KEY_MODEL_NAME)
            .remove(KEY_PLATE_NO)
            .apply()
        vehicleSwitcherPopup?.dismiss()
        vehicleSwitcherPopup = null
        dismissAppDialogImmediately()
        if (::featureSheets.isInitialized) featureSheets.dismissAll()
        if (::managementScreens.isInitialized) managementScreens.dismissAll()
        settingsScreen?.dismissTransientOverlays()
        clearSubpageOverlayStack()
        settingsRootView?.let { contentFrame.removeView(it) }
        securityOverlayView?.let { contentFrame.removeView(it) }
        locationOverlayDestroy?.invoke()
        locationOverlayDestroy = null
        locationOverlayView?.let { contentFrame.removeView(it) }
        messagesOverlayView?.let { contentFrame.removeView(it) }
        homeRootView = null
        settingsRootView = null
        securityOverlayView = null
        securitySubpageStatusText = null
        locationOverlayView = null
        messagesOverlayView = null
        settingsScreen = null
        currentProfile = null
        activeTelemetry = null
        sessionVehiclePin = ""
        bleConnected = false
        bleNearby = false
        lastPairingPin = ""
    }

    private fun activateVehicleFromSummary(summary: VehicleSummary) {
        val activeProfile = profileStore.getActiveProfile()
        val vinKey = summary.vin.trim().uppercase(Locale.US)
        if (vinKey.isBlank()) return
        val localMatch = profileStore.listProfiles()
            .firstOrNull { it.vin.equals(vinKey, ignoreCase = true) }
        val item = SwitchableVehicleItem(
            vin = vinKey,
            displayName = vehicleModelName(summary),
            plateNo = summary.plateNo.trim(),
            localProfile = localMatch,
            cloudSummary = summary
        )
        activateSelectedVehicle(item, activeProfile, null)
    }

    private fun handleVehicleUnbound(unboundVin: String) {
        val activeProfile = profileStore.getActiveProfile()
        profileStore.deleteProfile(unboundVin)
        if (!activeProfile.vin.equals(unboundVin, ignoreCase = true)) {
            return
        }
        val remaining = profileStore.listProfiles().firstOrNull { it.vin.isNotBlank() }
        if (remaining != null) {
            currentProfile = profileStore.setActiveVin(remaining.vin) ?: remaining
            uiPrefs.edit()
                .putString(KEY_MODEL_NAME, remaining.bleName.ifBlank { "我的座驾" })
                .remove(KEY_PLATE_NO)
                .apply()
            activeTelemetry = null
            refreshHomeHeaderAndRows()
            refreshTelemetry(silent = true)
            return
        }
        if (activeProfile.accessToken.isNotBlank()) {
            lifecycleScope.launch {
                val nextCloud = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchVehicleList(activeProfile.accessToken)
                    }
                }.getOrNull()?.firstOrNull { it.vin.isNotBlank() }
                if (nextCloud != null) {
                    activateVehicleFromSummary(nextCloud)
                } else {
                    uiPrefs.edit().remove(KEY_MODEL_NAME).remove(KEY_PLATE_NO).apply()
                    activeTelemetry = null
                    refreshHomeHeaderAndRows()
                }
            }
        }
    }

    // =========================================================================
    // BLE Proximity & Live Response Callbacks
    // =========================================================================

    private fun triggerManualBleScanAndConnect(triggerView: View) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastManualBleTapMs < 3000L) return
        lastManualBleTapMs = now

        AppHaptics.perform(triggerView, HapticLevel.LIGHT_TICK)
        if (bleConnected || bleController.isSessionReady()) {
            val rssi = bleController.lastProximityRssi?.let { " ($it dBm)" }.orEmpty()
            showHomeStatus("手机钥匙已连接 (BLE 直连)$rssi")
            return
        }
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank()) {
            showHomeStatus("请先绑定车辆")
            return
        }
        if (!ensureBlePermissions()) return

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            showHomeStatus("当前设备不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            requestBluetoothEnable()
            return
        }

        channelBadgeIcon?.animate()?.cancel()
        channelBadgeIcon?.apply {
            alpha = 0.45f
            animate().alpha(1f).setDuration(380L).start()
        }
        bleController.startAutoProximityWatch(
            profileProvider = { profileStore.getActiveProfile() }
        ) { requestBluetoothEnable() }
        showHomeStatus(
            if (bleNearby || bleController.isVehicleNearby) {
                "正在连接附近车辆蓝牙..."
            } else {
                "正在扫描并连接车辆蓝牙..."
            }
        )
    }

    private fun startProximityWhenPermitted() {
        if (currentScreen != Screen.HOME) return
        if (!ensureBlePermissions()) return
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank()) return
        bleController.startAutoProximityWatch(
            profileProvider = { profileStore.getActiveProfile() }
        ) { requestBluetoothEnable() }
    }

    private fun requestBluetoothEnable() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return
        if (adapter.isEnabled) return
        runCatching {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.onFailure {
            showHomeStatus("请开启系统蓝牙以使用手机钥匙")
        }
    }

    private fun ensureBlePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        if (!blePermissionsRequested) {
            blePermissionsRequested = true
            requestPermissions(missing.toTypedArray(), REQUEST_BLE_PERMISSIONS)
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLE_PERMISSIONS) return
        blePermissionsRequested = false
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (currentScreen == Screen.HOME) startProximityWhenPermitted()
        }
    }

    override fun onChannelModeChanged(
        isBleConnected: Boolean,
        rssi: Int?,
        statusText: String
    ) {
        runOnUiThread {
            bleConnected = isBleConnected
            bleNearby = !isBleConnected && (
                statusText.contains("发现附近车辆") || statusText.contains("正在握手")
            )
            updateSecurityRow()
            if (isBleConnected) {
                lastPairingPin = ""
                showHomeStatus("手机钥匙已连接 (BLE 直连)")
            }
        }
    }

    override fun onPairingPinAvailable(pin: String, address: String) {
        runOnUiThread {
            lastPairingPin = pin
            refreshSecuritySheetIfOpen()
            showHomeStatus("蓝牙配对码：$pin (已自动填入)")
        }
    }

    override fun onAuthenticated(sessionSnapshot: SessionSnapshot) {
        runOnUiThread {
            bleConnected = true
            bleNearby = false
            updateSecurityRow()
        }
    }

    override fun onVehicleResponse(parsedResponse: ParsedTBoxResponse) {
        runOnUiThread {
            val existing = activeTelemetry
            if (existing != null) {
                activeTelemetry = existing.copy(
                    doorLocked = parsedResponse.doorLocked ?: existing.doorLocked,
                    doorLockState = when (parsedResponse.doorLocked) {
                        true -> "0"
                        false -> "1"
                        null -> existing.doorLockState
                    },
                    doorLock = when (parsedResponse.doorLocked) {
                        true -> "0"
                        false -> "1"
                        null -> existing.doorLock
                    },
                    trunkOpen = parsedResponse.trunkOpen ?: existing.trunkOpen,
                    acOn = parsedResponse.acOn ?: existing.acOn,
                    engineRunning = parsedResponse.engineRunning ?: existing.engineRunning
                )
            }
            parsedResponse.doorLocked?.let { carVisual?.vehicleLocked = it }
            parsedResponse.doorsOpen?.let { carVisual?.doorsOpen = it }
            parsedResponse.trunkOpen?.let { carVisual?.trunkOpen = it }
            parsedResponse.acOn?.let { carVisual?.climateOn = it }
            parsedResponse.engineRunning?.let { carVisual?.engineRunning = it }
            refreshHomeHeaderAndRows()
        }
    }

    override fun onActionDispatched(actionKey: String, serial: Int) {
        runOnUiThread {
            if (lastBleActionLabel.isNotBlank()) {
                showHomeStatus("蓝牙直连已发送：$lastBleActionLabel")
            }
        }
    }

    override fun onError(message: String, throwable: Throwable?) {
        runOnUiThread {
            if (currentScreen == Screen.HOME) {
                showHomeStatus(message.take(100))
            }
        }
    }

    // =========================================================================
    // Transient Toast Pill & Reusable Dark App Bottom Sheets / Components
    // =========================================================================

    private fun showEditLicensePlateSheet() {
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank()) return
        val currentPlate = uiPrefs.getString(KEY_PLATE_NO, "").orEmpty()
        showInputSheet(
            title = "设置车牌号",
            message = "修改后将同步保存至云端并更新主页展示。",
            hint = currentPlate.ifBlank { "例如：皖B12345" },
            numeric = false,
            confirmText = "保存"
        ) { plate ->
            val clean = plate.trim().uppercase(Locale.US)
            if (clean.isBlank()) return@showInputSheet
            lifecycleScope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.updateVehicleLicensePlate(profile.vin, clean, profile.accessToken)
                    }
                }.getOrDefault(false)
                uiPrefs.edit().putString(KEY_PLATE_NO, clean).apply()
                refreshHomeHeaderAndRows()
                showHomeStatus(if (ok) "车牌号已同步更新：$clean" else "已本地保存车牌号：$clean")
            }
        }
    }

    private fun showHomeStatus(message: String) {
        if (currentScreen != Screen.HOME) return
        stopStatusAutoHide()
        transientToastBanner?.let { banner ->
            banner.text = message
            MotionSpec.animateToastShow(banner)
        }
        val hide = Runnable {
            transientToastBanner?.let { MotionSpec.animateToastHide(it) }
        }
        statusHideRunnable = hide
        mainHandler.postDelayed(hide, 3_600L)
    }

    private fun stopStatusAutoHide() {
        statusHideRunnable?.let { mainHandler.removeCallbacks(it) }
        statusHideRunnable = null
    }

    private fun showBottomSheet(
        title: String,
        buildContent: (LinearLayout, android.app.Dialog) -> Unit
    ) {
        appDialog?.dismiss()
        sheetClimateTempView = null
        sheetKeyStatusView = null
        sheetKeyActionButton = null

        var exitAnimStarted = false
        var sheetRootView: View? = null
        val dialog = object : android.app.Dialog(this@MainActivity) {
            override fun dismiss() {
                val target = sheetRootView
                if (!isShowing || target == null || exitAnimStarted || MotionSpec.isBypassingSheetDismiss(this)) {
                    super.dismiss()
                    return
                }
                exitAnimStarted = true
                MotionSpec.dismissSheetAnimated(target, this) {
                    if (appDialog === this) {
                        applySheetDepthRecede(false)
                    }
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = topRounded(CARD_MODAL, 20f)
            setPadding(dp(22), dp(12), dp(22), dp(26))
        }
        sheetRootView = outer

        // Top drag handle bar
        val handle = View(this).apply {
            background = rounded(Color.rgb(72, 74, 82), 2f)
        }
        outer.addView(handle, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        // Sheet header row (swipe down handle bar or tap outside to dismiss)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        outer.addView(header)
        header.addView(text(title, 20f, TEXT_PRIMARY, Typeface.BOLD), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(body, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(scroll, lp(top = dp(4)))

        buildContent(body, dialog)

        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setWindowAnimations(0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.68f)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        MotionSpec.attachSheetSwipeToDismiss(outer, scroll, dialog) {
            if (appDialog === dialog) {
                applySheetDepthRecede(false)
            }
        }
        dialog.setOnDismissListener {
            if (appDialog === dialog) {
                applySheetDepthRecede(false)
                appDialog = null
                dismissAppDialogSync = null
                sheetClimateTempView = null
                sheetKeyStatusView = null
                sheetKeyActionButton = null
            }
        }
        dismissAppDialogSync = {
            exitAnimStarted = true
            if (dialog.isShowing) dialog.dismiss()
        }
        appDialog = dialog
        dialog.show()
        MotionSpec.animateSheetEnter(outer)
        applySheetDepthRecede(true)
    }

    private fun showInputSheet(
        title: String,
        message: String,
        hint: String,
        numeric: Boolean,
        confirmText: String,
        onSubmit: (String) -> Unit
    ) {
        showBottomSheet(title) { sheet, dialog ->
            sheet.addView(text(message, 13f, TEXT_SECONDARY).apply {
                setLineSpacing(0f, 1.45f)
                layoutParams = lp(top = dp(8))
            })
            val input = styledInput(
                hint,
                if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
            ).apply {
                imeOptions = EditorInfo.IME_ACTION_DONE
                layoutParams = lp(top = dp(16), height = dp(50))
            }
            sheet.addView(input)

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sheet.addView(buttons, lp(top = dp(20)))
            buttons.addView(secondaryActionButton("取消") { dialog.dismiss() }, weightedLp(0, dp(46)))
            buttons.addView(primaryActionButton(confirmText) {
                val value = input.text.toString().trim()
                dialog.dismiss()
                onSubmit(value)
            }, weightedLp(dp(10), dp(46)))

            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            input.requestFocus()
        }
    }

    private data class ControlTileSpec(
        val icon: AppIconType,
        val label: String,
        val active: Boolean = false,
        val onClick: () -> Unit
    )

    private fun controlTileRow(vararg tiles: ControlTileSpec): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tiles.forEachIndexed { idx, spec ->
                addView(buildControlTile(spec), weightedLp(if (idx == 0) 0 else dp(10), dp(76)))
            }
            MotionSpec.staggerChildren(this, stepMs = 18L, maxDelayMs = 90L)
        }

    private fun buildControlTile(spec: ControlTileSpec): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (spec.active) {
                rounded(TILE_ACTIVE_BG, 12f, ACCENT_BLUE, 1f)
            } else {
                rounded(TILE_BG, 12f)
            }
            setPadding(dp(8), dp(12), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener { spec.onClick() }

            val iconView = AppIconView(
                this@MainActivity,
                spec.icon,
                if (spec.active) ACCENT_BLUE else TEXT_PRIMARY
            )
            addView(iconView, LinearLayout.LayoutParams(dp(24), dp(24)))

            val labelView = text(
                spec.label,
                12.5f,
                if (spec.active) TEXT_PRIMARY else TEXT_BODY,
                if (spec.active) Typeface.BOLD else Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            }
            addView(labelView)
        }

    private fun sheetCircleButton(label: String, onClick: () -> Unit): TextView =
        text(label, 24f, TEXT_PRIMARY, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(BTN_SECONDARY, 24f)
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.90f)
            setOnClickListener { onClick() }
        }

    private fun primaryActionButton(label: String, onClick: () -> Unit): TextView =
        AppComponents.createActionButton(this, label, ButtonStyle.PRIMARY, onClick)

    private fun secondaryActionButton(label: String, onClick: () -> Unit): TextView =
        AppComponents.createActionButton(this, label, ButtonStyle.SECONDARY, onClick)

    private fun sectionLabel(title: String): TextView =
        text(title, 12.5f, TEXT_SECONDARY, Typeface.BOLD)

    private fun fieldLabel(label: String): TextView =
        text(label, 13f, TEXT_SECONDARY)

    private fun styledInput(hintText: String, inputType: Int): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.rgb(112, 116, 126))
        setTextColor(TEXT_PRIMARY)
        textSize = 15f
        this.inputType = inputType
        setSingleLine(true)
        background = rounded(INPUT_BG, 6f, Color.rgb(52, 54, 62), 0.7f)
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(this).apply {
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

    private fun matchParent() = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    private fun lp(
        top: Int = 0,
        bottom: Int = 0,
        left: Int = 0,
        right: Int = 0,
        height: Int = WRAP_CONTENT
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(MATCH_PARENT, height).apply {
        topMargin = top
        bottomMargin = bottom
        marginStart = left
        marginEnd = right
    }

    private fun weightedLp(startMargin: Int, height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, height, 1f).apply {
            marginStart = startMargin
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    // =========================================================================
    // Telemetry Normalization & Formatting Helpers
    // =========================================================================

    private fun arePhysicalDoorsOpen(telemetry: VehicleTelemetry?): Boolean {
        if (telemetry == null) return false
        val directDoors = listOf(
            telemetry.frontLeftDoor,
            telemetry.frontRightDoor,
            telemetry.backLeftDoor,
            telemetry.backRightDoor
        )
        if (directDoors.any { isDoorOpenToken(it) }) return true
        val doorKeys = setOf("frontLeft", "frontRight", "backLeft", "backRight")
        return telemetry.doorStates.entries.any { (k, v) -> k in doorKeys && isDoorOpenToken(v) }
    }

    private fun isDoorOpenToken(raw: String): Boolean {
        val v = raw.trim().lowercase(Locale.US)
        return v == "1" || v == "open" || v == "opened" || v == "true"
    }

    private fun isSkylightOpen(state: String?): Boolean {
        val v = state.orEmpty().trim().lowercase(Locale.US)
        if (v.isBlank()) return false
        // In DataResp910 ANGLE_SKYLIGHT: "0", "1" (0% closed), "255"/"0xff" are closed/invalid
        if (v in setOf("0", "1", "255", "0xff", "closed", "close", "false", "off")) return false
        return true
    }

    private fun formatSkylightLabel(state: String?): String {
        val v = state.orEmpty().trim().lowercase(Locale.US)
        return when {
            v.isBlank() || v == "255" || v == "0xff" -> "--"
            v in setOf("0", "1", "closed", "close", "false", "off") -> "已关闭"
            v in setOf("12", "tilt") -> "已翘起"
            else -> "已开启"
        }
    }

    private fun formatTyreSummary(telemetry: VehicleTelemetry?): String {
        if (telemetry == null) return "--"
        val values = listOf(
            telemetry.lFrontTyre,
            telemetry.rFrontTyre,
            telemetry.lRearTyre,
            telemetry.rRearTyre
        ).map { formatCornerTyrePressure(it) }.filter { it != "--" }
        return if (values.isEmpty()) "--" else values.joinToString(" / ")
    }

    private fun cabinTemperature(telemetry: VehicleTelemetry?): Double? {
        if (telemetry == null) return null
        val keys = listOf("insideTemp", "inCarTemp", "cabinTemp", "temperature", "temp", "innerTemp")
        keys.forEach { key ->
            telemetry.conditionFields[key]?.toDoubleOrNull()?.let { return it }
            telemetry.conditionFields[key.uppercase(Locale.US)]?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun formatTemp(celsius: Double): String =
        String.format(Locale.US, "%.1f°C", celsius)

    private fun maskedVin(vin: String): String = SensitiveUiText.identifier(vin)

    private fun maskMobile(mobile: String): String = when {
        mobile.length >= 7 -> mobile.take(3) + "****" + mobile.takeLast(4)
        else -> mobile
    }

    private fun isValidMobile(mobile: String): Boolean = MOBILE_REGEX.matches(mobile)

    private fun vehicleModelName(vehicle: VehicleSummary): String =
        VehicleDisplayNames.resolve(vehicle)

    private fun hasCompleteKeyMaterial(profile: VehicleProfile): Boolean = runCatching {
        profile.rootKeyBytes().size == 16 &&
            profile.infoBytes().size == 147 &&
            profile.edfBytes().size in 68..74
    }.getOrDefault(false)

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val REQUEST_BLE_PERMISSIONS = 4102
        private const val UI_PREFS = "car_plus_consumer_ui"
        private const val KEY_PRIVACY_ACCEPTED = "privacyAccepted"
        private const val KEY_MODEL_NAME = "modelName"
        private const val KEY_PLATE_NO = "plateNo"
        private const val KEY_QUICK_ACTIONS = "quick_actions"
        private const val DEFAULT_QUICK_ACTIONS = "LOCK,CLIMATE,FLASH,TRUNK"
        private const val KEY_REQUIRE_PIN_EVERY_TIME = "require_pin_every_time"

        private val MOBILE_REGEX = Regex("^1\\d{10}$")
        private val PIN_REGEX = Regex("^\\d{6}$")
        private val NUMBER_EXTRACT_REGEX = Regex("[0-9]+(?:\\.[0-9]+)?")

        // Unified App Dark Palette aligned with ThemeTokens (DESIGN_SYSTEM.md)
        private val BG_BLACK = Color.rgb(0, 0, 0)
        private val BG_DARK = Color.rgb(17, 18, 20)
        private val BG_HOME = ThemeTokens.BG_CANVAS
        private val CARD_MODAL = ThemeTokens.BG_SURFACE
        private val TILE_BG = Color.rgb(36, 37, 42)
        private val TILE_ACTIVE_BG = Color.rgb(34, 46, 70)
        private val BTN_SECONDARY = ThemeTokens.BTN_SECONDARY
        private val INPUT_BG = Color.rgb(34, 35, 38)
        private val PRIMARY_BLUE = ThemeTokens.ACCENT_BLUE
        private val ACCENT_BLUE = ThemeTokens.ACCENT_LIGHT
        private val LINK_BLUE = ThemeTokens.ACCENT_LIGHT
        private val TEXT_PRIMARY = ThemeTokens.TEXT_PRIMARY
        private val TEXT_BODY = ThemeTokens.TEXT_BODY
        private val TEXT_SECONDARY = ThemeTokens.TEXT_SUB
        private val FOOTER_TEXT = Color.rgb(114, 118, 128)
        private val ERROR_RED = ThemeTokens.DANGER

        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
