package com.chery.carplus.ui

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipDescription
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
import android.view.DragEvent
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chery.carplus.R
import com.chery.carplus.ble.CheryBleController
import com.chery.carplus.ble.ParsedTBoxResponse
import com.chery.carplus.ble.PassiveEntryConfig
import com.chery.carplus.ble.PassiveEntryPrerequisiteChecker
import com.chery.carplus.ble.PassiveEntryRuntime
import com.chery.carplus.ble.PassiveEntrySnapshot
import com.chery.carplus.ble.PassiveEntryStore
import com.chery.carplus.ble.PassiveEntryWakeManager
import com.chery.carplus.ble.PassiveLockSuppressionReason
import com.chery.carplus.ble.PrerequisiteId
import com.chery.carplus.ble.SessionSnapshot
import com.chery.carplus.cloud.CheryDeviceBindingRequiredException
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.CheryLoginResult
import com.chery.carplus.cloud.CherySessionExpiredException
import com.chery.carplus.cloud.InGeekCloudProvisioner
import com.chery.carplus.cloud.RemoteCommand
import com.chery.carplus.cloud.RemoteCommandResult
import com.chery.carplus.cloud.VehiclePowertrain
import com.chery.carplus.cloud.VehicleSummary
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.profile.VehicleProfileStore
import com.chery.carplus.widget.CarWidgetManager
import com.chery.carplus.widget.CarWidgetStateStore
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
 * Consumer-facing Qiri+ minimalist dark interface.
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

    private class DeckGestureLayout(
        context: Context,
        private val getDeckProgress: () -> Float,
        private val isTouchInsideScrollArea: (rawX: Float, rawY: Float) -> Boolean,
        private val isTouchBlockedForEditDrag: (rawX: Float, rawY: Float) -> Boolean,
        private val canScrollAreaPullDown: () -> Boolean,
        private val onDragStart: (direction: Int) -> Unit,
        private val onDragMove: (dy: Float, velocityY: Float, direction: Int) -> Unit,
        private val onDragEnd: (totalDy: Float, velocityY: Float, direction: Int) -> Unit
    ) : LinearLayout(context) {
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var velocityTracker: android.view.VelocityTracker? = null
        private var dragDirection = 0 // +1 = pull down to expand, -1 = pull up to collapse
        private var touchInScrollArea = false
        private var touchBlockedForEditDrag = false

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        private fun addMovementRaw(ev: MotionEvent) {
            val vt = velocityTracker ?: android.view.VelocityTracker.obtain().also { velocityTracker = it }
            val copy = MotionEvent.obtain(ev)
            copy.setLocation(ev.rawX, ev.rawY)
            vt.addMovement(copy)
            copy.recycle()
        }

        private fun computeVelocityY(): Float {
            val vt = velocityTracker ?: return 0f
            vt.computeCurrentVelocity(1000)
            return if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                vt.getYVelocity(activePointerId)
            } else {
                vt.yVelocity
            }
        }

        private fun activeRawCoords(ev: MotionEvent): Pair<Float, Float> {
            val idx = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                ev.findPointerIndex(activePointerId)
            } else {
                -1
            }
            val safeIdx = if (idx >= 0) idx else 0
            val rawOffsetX = ev.rawX - ev.x
            val rawOffsetY = ev.rawY - ev.y
            return (ev.getX(safeIdx) + rawOffsetX) to (ev.getY(safeIdx) + rawOffsetY)
        }

        private fun handlePointerUp(ev: MotionEvent) {
            val liftedIdx = ev.actionIndex
            val liftedId = ev.getPointerId(liftedIdx)
            if (liftedId == activePointerId && ev.pointerCount > 1) {
                val newIdx = if (liftedIdx == 0) 1 else 0
                val rawOffsetY = ev.rawY - ev.y
                val oldY = ev.getY(liftedIdx) + rawOffsetY
                val newY = ev.getY(newIdx) + rawOffsetY
                downY += (newY - oldY)
                val rawOffsetX = ev.rawX - ev.x
                downX += ((ev.getX(newIdx) + rawOffsetX) - (ev.getX(liftedIdx) + rawOffsetX))
                activePointerId = ev.getPointerId(newIdx)
            }
        }

        private fun tryStartDrag(rawX: Float, rawY: Float): Boolean {
            if (touchBlockedForEditDrag) return false
            val dx = rawX - downX
            val dy = rawY - downY
            val progress = getDeckProgress()
            val atScrollTop = !touchInScrollArea || canScrollAreaPullDown()
            if (!atScrollTop) return false

            if (progress < 0.995f && dy > touchSlop && dy > abs(dx) * 1.15f) {
                dragDirection = 1
                downY = rawY
                parent?.requestDisallowInterceptTouchEvent(true)
                onDragStart(1)
                return true
            }
            if (progress > 0.005f && dy < -touchSlop && abs(dy) > abs(dx) * 1.15f) {
                dragDirection = -1
                downY = rawY
                parent?.requestDisallowInterceptTouchEvent(true)
                onDragStart(-1)
                return true
            }
            return false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    recycleVelocityTracker()
                    activePointerId = ev.getPointerId(0)
                    downX = ev.rawX
                    downY = ev.rawY
                    dragDirection = 0
                    touchInScrollArea = isTouchInsideScrollArea(ev.rawX, ev.rawY)
                    touchBlockedForEditDrag = isTouchBlockedForEditDrag(ev.rawX, ev.rawY)
                    addMovementRaw(ev)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    handlePointerUp(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchBlockedForEditDrag) return false
                    addMovementRaw(ev)
                    val (rx, ry) = activeRawCoords(ev)
                    if (tryStartDrag(rx, ry)) {
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDirection = 0
                    touchInScrollArea = false
                    touchBlockedForEditDrag = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    recycleVelocityTracker()
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Claim ACTION_DOWN when no child consumed it (e.g., blank space in deck/pull strip)
                    // so subsequent ACTION_MOVE events can initiate expand/collapse drag.
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    handlePointerUp(ev)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    addMovementRaw(ev)
                    val (rx, ry) = activeRawCoords(ev)
                    if (dragDirection == 0 && !tryStartDrag(rx, ry)) {
                        return true
                    }
                    val vy = computeVelocityY()
                    onDragMove(ry - downY, vy, dragDirection)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    addMovementRaw(ev)
                    val vy = if (ev.actionMasked == MotionEvent.ACTION_UP) computeVelocityY() else 0f
                    val (_, ry) = activeRawCoords(ev)
                    val dir = dragDirection
                    dragDirection = 0
                    touchInScrollArea = false
                    touchBlockedForEditDrag = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    recycleVelocityTracker()
                    if (dir != 0) {
                        onDragEnd(ry - downY, vy, dir)
                    }
                    return true
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            recycleVelocityTracker()
            super.onDetachedFromWindow()
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
    private var postActionRefreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private var bleRssiPollJob: Job? = null
    private var geocodeJob: Job? = null
    private var pendingResumeBleRunnable: Runnable? = null
    private var pendingShortcutRunnable: Runnable? = null
    private var pendingEntrancePreviewStyle: String? = null
    private var pendingEntrancePreviewRunnable: Runnable? = null
    private var liveLocationListener: android.location.LocationListener? = null
    private var liveLocationCancelSignal: android.os.CancellationSignal? = null
    @Volatile
    private var latestLiveDeviceLocation: Location? = null
    private var lastLiveLocationRequestMs = 0L
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
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
    private val pendingExitDestroys = mutableListOf<Pair<View, () -> Unit>>()
    private var settingsScreen: SettingsScreen? = null
    private var pendingAvatarPickCallback: ((Uri) -> Unit)? = null
    private lateinit var avatarPickerLauncher: ActivityResultLauncher<String>
    private lateinit var passiveEntrySheets: PassiveEntrySheets
    private lateinit var passivePermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var companionAssocLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var securityPassiveRefreshCallback: (() -> Unit)? = null
    private val passiveRuntimeUiListener: (PassiveEntrySnapshot) -> Unit = {
        runOnUiThread {
            securityPassiveRefreshCallback?.invoke()
            if (::passiveEntrySheets.isInitialized) {
                passiveEntrySheets.refreshIfOpen()
            }
        }
    }

    // Login form views
    private var loginMobileLabel: TextView? = null
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
    private var homeLowerSheetView: DeckGestureLayout? = null
    private var lowerOverlayContainer: ScrollView? = null
    private var homeCollapsedHeroHeightPx: Int = 0
    private var homeExpandedHeroHeightPx: Int = 0
    private var lastSentExpandedVisibleRatio: Float = 1.0f
    private var homeHeaderView: View? = null
    private var carHeroContainerView: FrameLayout? = null
    private var homeMenuSectionView: View? = null
    private var carVisual: VehicleVisualView? = null
    private data class TpmsCornerViews(
        val pressureTv: TextView,
        val tempTv: TextView
    )

    private data class QuickActionDragPayload(
        val action: QuickActionId,
        val fromTopRow: Boolean,
        val sourceViewRef: java.lang.ref.WeakReference<View>
    )

    private var tpmsOverlayContainer: FrameLayout? = null
    private var tpmsFlCorner: TpmsCornerViews? = null
    private var tpmsFrCorner: TpmsCornerViews? = null
    private var tpmsRlCorner: TpmsCornerViews? = null
    private var tpmsRrCorner: TpmsCornerViews? = null
    private var quickActionRow: LinearLayout? = null
    private val quickActionIcons = mutableMapOf<QuickActionId, AppIconView>()
    private var quickDeckClipContainer: FrameLayout? = null
    private var quickDeckContainer: LinearLayout? = null
    private var quickDeckPullHintStrip: FrameLayout? = null
    private var quickDeckTargetHeightPx: Int = 0
    private var quickDeckEditHeader: LinearLayout? = null
    private var quickDeckEditTitle: TextView? = null
    private var quickDeckGridHost: LinearLayout? = null
    private val quickDeckRowViews = mutableListOf<LinearLayout>()
    private val quickDeckTileViews = mutableMapOf<QuickActionId, View>()
    private var deckExpandAnimator: ValueAnimator? = null
    private var deckHeightAnimator: ValueAnimator? = null
    private var deckExpandProgress = 0f
    private var deckDragStartProgress = 0f
    private var quickDeckExpanded = false
    private var quickDeckEditMode = false
    private var climateRowSubtitle: TextView? = null
    private var chargingMenuRow: View? = null
    private var chargingRowSubtitle: TextView? = null
    private var locationRowSubtitle: TextView? = null
    private var securityDotView: StatusDotView? = null
    private var securityRowSubtitle: TextView? = null
    private var homeFooterView: TextView? = null

    // Active sheet live views (updated if open)
    private var sheetClimateTempView: TextView? = null

    // Live state
    private var currentProfile: VehicleProfile? = null
    private var activeTelemetry: VehicleTelemetry? = null
    private var sessionVehiclePin = ""
    private var climateTemperatureCelsius = 24.0
    private var bleConnected = false
    private var bleNearby = false
    private data class PendingBleActionMeta(
        val label: String,
        val vin: String,
        val isPassiveAuto: Boolean
    )

    private var isActivityResumed = false
    private var pendingStartupServicesRunnable: Runnable? = null
    private var lastPairingPin = ""
    private val pendingBleActionMetaByKey = mutableMapOf<String, ArrayDeque<PendingBleActionMeta>>()
    private val lastPassiveAutoUnlockActiveByVin = mutableMapOf<String, Boolean>()
    private var foregroundLiveBleLocked: Boolean? = null
    private var foregroundLiveBleDoorsOpen: Boolean? = null
    private var foregroundLiveBleEngineRunning: Boolean? = null
    private var foregroundLiveBleUpdatedElapsedMs = 0L
    private var lastTelemetrySyncedElapsedMs = 0L
    private var foregroundSessionReadyElapsedMs = 0L
    private var foregroundBleDisconnectedAtElapsedMs = 0L
    private var foregroundSessionUnlockEvaluated = false
    private var foregroundConsecutiveNearRssiHits = 0
    private var pendingForegroundUnlockCheckRunnable: Runnable? = null
    private var energyShowsRange = false
    private var cloudTripTrackEnabled: Boolean? = null
    private var lastKnownAntiTheftEnabled: Boolean? = null
    private var topPairingDrawerView: View? = null
    private var latestTopStatusBarInsetPx: Int = 0
    private var lastGeocodedKey = ""
    private var lastGeocodedAddress: String? = null
    private var lastGeocodeSettled = false
    private var lastGeocodeFailedAtMs = 0L
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
            onOpenVehicleUpgrade = { prefetched -> featureSheets.showVehicleUpgradeSheet(prefetched) },
            onVehicleBound = { boundVin, boundPin, isDefault ->
                handleManagementVehicleBound(boundVin, boundPin, isDefault)
            }
        )
        featureSheets = VehicleFeatureSheets(
            context = this,
            scope = lifecycleScope,
            httpClient = httpClient,
            provisioner = provisioner,
            profileProvider = { profileStore.getActiveProfile() },
            telemetryProvider = { activeTelemetry },
            cachedPinProvider = { sessionVehiclePin },
            onPinUpdated = { targetVin, newPin ->
                val resolvedVin = targetVin.trim().uppercase(Locale.US).ifBlank {
                    profileStore.getActiveProfile().vin
                }
                if (resolvedVin.isNotBlank()) {
                    lifecycleScope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            profileStore.updateControlPin(resolvedVin, newPin)
                        }
                        val active = profileStore.getActiveProfile()
                        if (active.vin.equals(resolvedVin, ignoreCase = true)) {
                            sessionVehiclePin = newPin
                            currentProfile = updated ?: active
                        }
                        CarWidgetManager.updateAllWidgets(this@MainActivity)
                    }
                }
            },
            onRefreshTelemetry = { refreshTelemetry(silent = false) },
            onTriggerCloudKeyProvision = { launchCloudProvision(profileStore.getActiveProfile(), automatic = false) },
            onScanNearbyBle = {
                if (ensureBlePermissions()) {
                    bleController.triggerImmediatePairingOrConnect(
                        profileProvider = { profileStore.getActiveProfile() },
                        onRequestEnableBluetooth = { requestBluetoothEnable() }
                    )
                }
            },
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
            requirePinEveryTimeProvider = { uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, true) },
            onRequirePinEveryTimeChanged = { enabled ->
                uiPrefs.edit().putBoolean(KEY_REQUIRE_PIN_EVERY_TIME, enabled).apply()
                CarWidgetManager.updateAllWidgets(this)
                showHomeStatus(if (enabled) "已开启每次控车验证 PIN 码" else "已关闭每次控车验证 PIN 码")
            },
            onOpenOverlaySubpage = { view, onDestroy -> pushSubpageOverlay(view, onDestroy) },
            onPopOverlaySubpage = { popSubpageOverlay() },
            onOpenRemoteAuthManagement = { managementScreens.openDriversScreen() },
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

        passivePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            securityPassiveRefreshCallback?.invoke()
            if (::passiveEntrySheets.isInitialized) passiveEntrySheets.refreshIfOpen()
        }

        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasDeviceLocationPermission()) {
                startLiveDeviceLocationUpdates()
                if (currentScreen == Screen.HOME) {
                    val updatedLocSub = formatLocationSubtitle(activeTelemetry)
                    locationRowSubtitle?.apply {
                        text = updatedLocSub
                        visibility = if (updatedLocSub.isNotBlank()) View.VISIBLE else View.GONE
                    }
                }
                locationAddressObservers.toList().forEach { observer ->
                    observer(currentStreetLabel())
                }
            }
        }

        companionAssocLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val profile = profileStore.getActiveProfile()
            val mac = PassiveEntryPrerequisiteChecker.normalizeMac(profile.bleMac).orEmpty()
            if (result.resultCode == RESULT_OK ||
                (mac.isNotBlank() && PassiveEntryPrerequisiteChecker.isCompanionAssociated(this, mac))
            ) {
                if (profile.vin.isNotBlank() && mac.isNotBlank()) {
                    val cfg = PassiveEntryStore.load(this, profile.vin)
                    PassiveEntryStore.save(this, cfg.copy(cdmAssociatedMac = mac))
                    if (cfg.enabled) {
                        PassiveEntryWakeManager.enableForVehicle(this, profile)
                    }
                }
                Toast.makeText(this, "已完成系统设备关联", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "已取消设备关联，将继续使用后台省电监听", Toast.LENGTH_SHORT).show()
            }
            securityPassiveRefreshCallback?.invoke()
            if (::passiveEntrySheets.isInitialized) passiveEntrySheets.refreshIfOpen()
        }

        passiveEntrySheets = PassiveEntrySheets(
            context = this,
            profileProvider = { profileStore.getActiveProfile() },
            liveBleConnectedProvider = { bleConnected || bleController.isSessionReady() },
            liveBleRssiProvider = { bleController.lastProximityRssi },
            onRequestLiveRssi = { bleController.requestRemoteRssi() },
            onFixPrerequisite = { id, profile -> handleFixPassivePrerequisite(id, profile) },
            onProceedEnableAfterPrerequisites = { profile, onDone ->
                verifyPinAndEnablePassiveEntry(profile, onDone)
            },
            onConfigUpdated = { updatedCfg ->
                securityPassiveRefreshCallback?.invoke()
                val vinKey = updatedCfg.vin.trim().uppercase(Locale.US)
                val prevActive = lastPassiveAutoUnlockActiveByVin[vinKey] ?: false
                val nowActive = updatedCfg.enabled && updatedCfg.autoUnlockEnabled
                lastPassiveAutoUnlockActiveByVin[vinKey] = nowActive
                // P2-29: only clear MANUAL_LOCK suppression & trigger auto-unlock when auto-unlock transitions false -> true
                if (!prevActive && nowActive) {
                    PassiveEntryRuntime.clearLockSuppression(this, updatedCfg.vin)
                    triggerForegroundPassiveAutoUnlockIfReady(forceReevaluate = true)
                }
            }
        )

        PassiveEntryRuntime.onAutoActionExecuted = { actionKey, vin ->
            runOnUiThread {
                val activeVin = profileStore.getActiveProfile().vin
                if (activeVin.equals(vin, ignoreCase = true) && (actionKey == "unlock" || actionKey == "lock")) {
                    applyOptimisticAction(actionKey)
                }
                val label = if (actionKey == "unlock") "自动解锁" else "离车自动上锁"
                showHomeStatus("蓝牙无感已执行：$label")
                scheduleTelemetryRefresh()
            }
        }
        PassiveEntryRuntime.addListener(passiveRuntimeUiListener)

        AppSystemInsets.applyEdgeToEdge(window, BG_HOME)
        contentFrame = FrameLayout(this).apply {
            setBackgroundColor(BG_HOME)
            clipToPadding = false
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(contentFrame) { v, insets ->
            val sysBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            latestTopStatusBarInsetPx = sysBars.top
            AppSystemInsets.updateBottomNavInset(sysBars.bottom)
            // Keep bottom edge-to-edge behind Android gesture pill ("小白条"); only IME lifts contentFrame
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, ime.bottom)
            insets
        }
        setContentView(contentFrame)
        androidx.core.view.ViewCompat.requestApplyInsets(contentFrame)

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
                        if (quickDeckEditMode) {
                            exitQuickDeckEditMode(showSavedToast = false)
                        } else if (quickDeckExpanded || deckExpandProgress > 0.05f) {
                            animateDeckTo(false)
                        } else {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastBackExitPromptMs <= 2_000L) {
                                finish()
                            } else {
                                lastBackExitPromptMs = now
                                showHomeStatus("再按一次退出 Qiri+")
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

        captureIncomingSharedPoiIntent(intent)
        routeInitialScreen()
    }

    private var pendingShortcutAction: String? = null
    private var pendingShortcutRequiresPinCache: Boolean = false
    private var pendingSharedPoiIntent: Intent? = null

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (captureIncomingSharedPoiIntent(intent)) {
            consumePendingSharedPoiIfReady()
            return
        }
        val act = intent?.getStringExtra(CarWidgetManager.EXTRA_SHORTCUT_ACTION).orEmpty()
        if (act.isNotBlank()) {
            pendingShortcutAction = act
            pendingShortcutRequiresPinCache = intent?.getBooleanExtra(
                CarWidgetManager.EXTRA_NEED_PIN_CACHE,
                false
            ) == true
            routePendingShortcutActionNow()
        }
    }

    private fun captureIncomingSharedPoiIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val isSendText = intent.action == Intent.ACTION_SEND &&
            intent.type?.startsWith("text/") == true &&
            !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
        val isGeoView = intent.action == Intent.ACTION_VIEW &&
            intent.data?.scheme.equals("geo", ignoreCase = true)
        if (!isSendText && !isGeoView) return false
        pendingSharedPoiIntent = Intent(intent)
        return true
    }

    private fun consumePendingSharedPoiIfReady() {
        val shared = pendingSharedPoiIntent ?: return
        if (currentScreen == Screen.WELCOME ||
            currentScreen == Screen.LOGIN_FORM ||
            currentScreen == Screen.PRIVACY_CONSENT
        ) {
            return
        }
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank() || profile.accessToken.isBlank()) return
        pendingSharedPoiIntent = null

        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                parseSharedDestinationIntent(shared)
            }
            featureSheets.showSendPoiToCarSheet(
                initialName = parsed.name,
                initialAddress = parsed.address,
                initialLat = parsed.lat,
                initialLon = parsed.lon,
                initialQuery = parsed.searchQuery
            )
        }
    }

    private data class ParsedSharedPoi(
        val name: String = "",
        val address: String = "",
        val lat: Double? = null,
        val lon: Double? = null,
        val searchQuery: String = ""
    )

    private fun parseSharedDestinationIntent(intent: Intent): ParsedSharedPoi {
        fun isValidCoord(la: Double?, lo: Double?): Boolean =
            la != null && lo != null &&
                la in -90.0..90.0 && lo in -180.0..180.0 &&
                (la != 0.0 || lo != 0.0)

        // 1. Handle geo:lat,lon?q=... (Android geo: intents carry WGS-84 coordinates)
        val geoUri = intent.data
        if (intent.action == Intent.ACTION_VIEW && geoUri != null && geoUri.scheme.equals("geo", ignoreCase = true)) {
            val ssp = geoUri.schemeSpecificPart.orEmpty()
            val beforeQ = ssp.substringBefore("?").trim()
            val queryPart = ssp.substringAfter("?", "")
            val qParam = runCatching {
                queryPart.split("&")
                    .firstOrNull { it.startsWith("q=", ignoreCase = true) }
                    ?.substringAfter("=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    .orEmpty()
            }.getOrDefault("")
            val coords = beforeQ.split(",")
            var lat = coords.getOrNull(0)?.trim()?.toDoubleOrNull()?.takeIf { it != 0.0 }
            var lon = coords.getOrNull(1)?.trim()?.toDoubleOrNull()?.takeIf { it != 0.0 }
            var label = ""
            if (qParam.isNotBlank()) {
                val parenMatch = Regex("""(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)\s*\(([^)]+)\)""").find(qParam)
                if (parenMatch != null) {
                    lat = lat ?: parenMatch.groupValues[1].toDoubleOrNull()
                    lon = lon ?: parenMatch.groupValues[2].toDoubleOrNull()
                    label = parenMatch.groupValues[3].trim()
                } else {
                    val pairMatch = Regex("""(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""").find(qParam)
                    if (pairMatch != null) {
                        lat = lat ?: pairMatch.groupValues[1].toDoubleOrNull()
                        lon = lon ?: pairMatch.groupValues[2].toDoubleOrNull()
                    } else {
                        label = qParam.trim()
                    }
                }
            }
            if (isValidCoord(lat, lon)) {
                val (gcjLat, gcjLon) = wgs84ToGcj02(lat!!, lon!!)
                val resolvedName = label.ifBlank {
                    httpClient.reverseGeocodeApproxAddress(gcjLat, gcjLon).orEmpty().ifBlank { "共享位置" }
                }
                return ParsedSharedPoi(name = resolvedName, address = resolvedName, lat = gcjLat, lon = gcjLon)
            }
            if (label.isNotBlank()) {
                return ParsedSharedPoi(name = label, searchQuery = label)
            }
        }

        // 2. Handle ACTION_SEND plain text (Amap / Baidu / Google / OSM / WeChat / Meituan shared links or text)
        val rawText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
        val urlMatch = Regex("""https?://[^\s\u4e00-\u9fa5]+""").find(rawText)
        val resolvedUrl = urlMatch?.value?.let { httpClient.resolveSharedUrlRedirect(it) }.orEmpty()
        val combined = "$rawText\n$resolvedUrl"
        val combinedLower = combined.lowercase(Locale.US)

        fun extractParam(vararg keys: String): String {
            for (k in keys) {
                val m = Regex("""[?&]$k=([^&\s]+)""", RegexOption.IGNORE_CASE).find(combined)
                if (m != null) {
                    val decoded = runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(m.groupValues[1])
                    if (decoded.isNotBlank()) return decoded.trim()
                }
            }
            return ""
        }

        var lat = extractParam("lat", "dlat", "latitude").toDoubleOrNull()
        var lon = extractParam("lon", "lng", "dlon", "longitude").toDoubleOrNull()
        if (lat == null || lon == null) {
            val locParam = extractParam("location", "position", "c", "center", "to")
            val parts = locParam.split(",")
            if (parts.size >= 2) {
                val a = parts[0].trim().toDoubleOrNull()
                val b = parts[1].trim().toDoubleOrNull()
                if (a != null && b != null) {
                    if (a > 70.0 && b < 60.0) { // China lon,lat order (Amap)
                        lon = a
                        lat = b
                    } else if (a < 60.0 && b > 70.0) { // lat,lon order
                        lat = a
                        lon = b
                    }
                }
            }
        }
        if (lat == null || lon == null) {
            val atMatch = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(combined)
            if (atMatch != null) {
                lat = atMatch.groupValues[1].toDoubleOrNull()
                lon = atMatch.groupValues[2].toDoubleOrNull()
            }
        }

        val urlName = extractParam("name", "poiname", "title", "q", "word")
        val textTitle = rawText
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""【[^】]*】"""), "")
            .trim()
            .lines()
            .firstOrNull { it.trim().length >= 2 }
            ?.trim()
            .orEmpty()
        val bestName = firstNonBlank(urlName, subject, textTitle)

        if (isValidCoord(lat, lon)) {
            val isBaidu = combinedLower.contains("map.baidu.com") ||
                combinedLower.contains("j.map.baidu.com") ||
                combinedLower.contains("baidumap")
            val isWgs84Source = combinedLower.contains("google.com/maps") ||
                combinedLower.contains("maps.app.goo.gl") ||
                combinedLower.contains("openstreetmap.org") ||
                combinedLower.contains("osm.org")
            val (finalLat, finalLon) = when {
                isBaidu -> bd09ToGcj02(lat!!, lon!!)
                isWgs84Source -> wgs84ToGcj02(lat!!, lon!!)
                else -> lat!! to lon!!
            }
            val finalName = bestName.ifBlank {
                httpClient.reverseGeocodeApproxAddress(finalLat, finalLon).orEmpty().ifBlank { "共享位置" }
            }
            return ParsedSharedPoi(name = finalName, address = finalName, lat = finalLat, lon = finalLon)
        }
        return ParsedSharedPoi(
            name = bestName,
            searchQuery = bestName.ifBlank { rawText.take(40) }
        )
    }

    private fun bd09ToGcj02(bdLat: Double, bdLon: Double): Pair<Double, Double> {
        val xPi = PI * 3000.0 / 180.0
        val x = bdLon - 0.0065
        val y = bdLat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * xPi)
        val theta = atan2(y, x) - 0.000003 * cos(x * xPi)
        return (z * sin(theta)) to (z * cos(theta))
    }

    private fun routePendingShortcutActionNow() {
        if (pendingShortcutAction.isNullOrBlank()) return
        if (currentScreen == Screen.WELCOME ||
            currentScreen == Screen.LOGIN_FORM ||
            currentScreen == Screen.PRIVACY_CONSENT
        ) {
            return
        }
        appDialog?.dismiss()
        appDialog = null
        if (subpageOverlayStack.isNotEmpty()) {
            clearSubpageOverlayStack()
        }
        when (currentScreen) {
            Screen.SETTINGS -> returnFromSettingsToHome()
            Screen.SECURITY -> returnFromSecurityToHome()
            Screen.LOCATION -> returnFromLocationToHome()
            Screen.MESSAGES -> returnFromMessagesToHome()
            Screen.HOME -> Unit
            else -> showHomeScreen()
        }
        consumePendingShortcutActionIfReady()
        consumePendingSharedPoiIfReady()
    }

    private fun consumePendingShortcutActionIfReady() {
        val act = pendingShortcutAction ?: return
        if (currentScreen != Screen.HOME) return
        val forcePinCache = pendingShortcutRequiresPinCache
        pendingShortcutAction = null
        pendingShortcutRequiresPinCache = false
        pendingShortcutRunnable?.let(contentFrame::removeCallbacks)
        val task = Runnable {
            pendingShortcutRunnable = null
            if (isFinishing || isDestroyed || currentScreen != Screen.HOME) return@Runnable
            val profile = profileStore.getActiveProfile().also { currentProfile = it }
            if (act == CarWidgetManager.CMD_REFRESH) {
                refreshTelemetry(silent = false)
                return@Runnable
            }
            val widgetSnap = CarWidgetStateStore.load(this)
            val useSnap = widgetSnap.vin.isBlank() || widgetSnap.vin.equals(profile.vin, ignoreCase = true)
            val airExtra = mapOf("airType" to uiPrefs.getString(KEY_AIR_TYPE, "1").orEmpty().ifBlank { "1" })
            val (actionKey, label, temp, extra) = when (act) {
                CarWidgetManager.CMD_TOGGLE_LOCK -> {
                    val locked = activeTelemetry?.doorLocked ?: if (useSnap) widgetSnap.doorLocked else (carVisual?.vehicleLocked ?: true)
                    if (locked) Quadruple("unlock", "解锁", null, emptyMap()) else Quadruple("lock", "锁车", null, emptyMap())
                }
                CarWidgetManager.CMD_TOGGLE_AC -> {
                    val acOn = activeTelemetry?.acOn ?: if (useSnap) widgetSnap.acOn else false
                    if (acOn) Quadruple("ac_off", "关闭空调", null, airExtra) else Quadruple("ac_on", "开启空调", climateTemperatureCelsius, airExtra)
                }
                CarWidgetManager.CMD_FIND_CAR -> Quadruple("find_car", "闪灯鸣笛寻车", null, emptyMap())
                CarWidgetManager.CMD_TOGGLE_TRUNK -> {
                    val open = activeTelemetry?.trunkOpen ?: if (useSnap) widgetSnap.trunkOpen else false
                    if (open) Quadruple("tailgate_close", "关闭后备箱", null, emptyMap()) else Quadruple("trunk_open", "开启后备箱", null, emptyMap())
                }
                CarWidgetManager.CMD_TOGGLE_ENGINE -> {
                    val running = activeTelemetry?.engineRunning ?: if (useSnap) widgetSnap.engineRunning else false
                    if (running) Quadruple("engine_stop", "远程熄火", null, emptyMap()) else Quadruple("engine_start", "远程启动", null, emptyMap())
                }
                else -> Quadruple("", "", null, emptyMap())
            }
            if (actionKey.isBlank()) return@Runnable
            if (forcePinCache) {
                if (profile.vin.isNotBlank()) {
                    showVehiclePinSheet(profile, actionKey, label, temp, extra)
                }
            } else {
                carVisual?.triggerActionFeedback(actionKey)
                dispatchSmartVehicleCommand(actionKey, label, temp, extra)
            }
        }
        pendingShortcutRunnable = task
        contentFrame.postDelayed(task, 220L)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun routeInitialScreen() {
        pendingShortcutAction = intent?.getStringExtra(CarWidgetManager.EXTRA_SHORTCUT_ACTION)
            ?.takeIf { it.isNotBlank() }
        pendingShortcutRequiresPinCache = intent?.getBooleanExtra(
            CarWidgetManager.EXTRA_NEED_PIN_CACHE,
            false
        ) == true
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
            CarWidgetManager.markLoggedOut(this, expired = false)
            showWelcomeScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        val passiveWasRunning = PassiveEntryRuntime.isServiceRunning()
        PassiveEntryRuntime.setForegroundAppActive(this, true)
        val wasBg = wasBackgrounded
        wasBackgrounded = false
        if (::featureSheets.isInitialized) {
            featureSheets.onHostResume()
        }
        val homeVisible = currentScreen == Screen.HOME && subpageOverlayStack.isEmpty()
        if (homeVisible) {
            carVisual?.onHostResume()
        }
        pendingResumeBleRunnable?.let(mainHandler::removeCallbacks)
        pendingResumeBleRunnable = null
        pendingStartupServicesRunnable?.let(contentFrame::removeCallbacks)
        pendingStartupServicesRunnable = null

        // Defer non-rendering binder IPC (BLE scan, LocationManager, passive wake restore) until
        // after the current UI frame commits so cold launch & resume render smoothly without stutter.
        val startupTask = Runnable {
            pendingStartupServicesRunnable = null
            if (!isActivityResumed || isFinishing || isDestroyed) return@Runnable
            if (PassiveEntryStore.getActiveEnabledVin(this).isNotBlank() &&
                !PassiveEntryRuntime.currentSnapshot().wakeChannelActive
            ) {
                PassiveEntryWakeManager.restoreIfEnabled(this)
            }
            val nowHomeVisible = currentScreen == Screen.HOME && subpageOverlayStack.isEmpty()
            if (nowHomeVisible) {
                if (passiveWasRunning) {
                    // Allow PassiveEntryService to finish closing its GATT link so T-Box resumes advertising.
                    val task = Runnable {
                        pendingResumeBleRunnable = null
                        if ((currentScreen == Screen.HOME || currentScreen == Screen.SECURITY) &&
                            !wasBackgrounded &&
                            !isFinishing &&
                            !isDestroyed &&
                            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                        ) {
                            startProximityWhenPermitted()
                        }
                    }
                    pendingResumeBleRunnable = task
                    mainHandler.postDelayed(task, 600L)
                } else {
                    startProximityWhenPermitted()
                }
                startPeriodicHomeRefresh()
                startForegroundBleRssiPolling()
                startLiveDeviceLocationUpdates()
                if (hasAllBlePermissionsGranted()) {
                    maybeRequestDeviceLocationPermissionOnce()
                }
                refreshTelemetry(silent = true)
            } else if (currentScreen == Screen.LOCATION && subpageOverlayStack.isEmpty()) {
                startLiveDeviceLocationUpdates()
            } else if (currentScreen == Screen.SECURITY) {
                if (passiveWasRunning) {
                    val task = Runnable {
                        pendingResumeBleRunnable = null
                        if ((currentScreen == Screen.HOME || currentScreen == Screen.SECURITY) &&
                            !wasBackgrounded &&
                            !isFinishing &&
                            !isDestroyed &&
                            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                        ) {
                            startProximityWhenPermitted()
                        }
                    }
                    pendingResumeBleRunnable = task
                    mainHandler.postDelayed(task, 600L)
                } else {
                    startProximityWhenPermitted()
                }
                startForegroundBleRssiPolling()
            }
        }
        pendingStartupServicesRunnable = startupTask
        contentFrame.post(startupTask)

        if (homeVisible && wasBg && !quickDeckExpanded) {
            carVisual?.playEntranceAnimation()
        }
        securityPassiveRefreshCallback?.invoke()
        if (::passiveEntrySheets.isInitialized) {
            passiveEntrySheets.refreshIfOpen()
        }
        if (bleController.isSessionReady() && !foregroundSessionUnlockEvaluated) {
            scheduleForegroundPassiveAutoUnlockCheck(forceReevaluate = false)
        }
    }

    private fun cancelPendingEntrancePreview() {
        pendingEntrancePreviewRunnable?.let(mainHandler::removeCallbacks)
        pendingEntrancePreviewRunnable = null
    }

    override fun onPause() {
        isActivityResumed = false
        pendingStartupServicesRunnable?.let(contentFrame::removeCallbacks)
        pendingStartupServicesRunnable = null
        pendingResumeBleRunnable?.let(mainHandler::removeCallbacks)
        pendingResumeBleRunnable = null
        cancelPendingForegroundPassiveUnlockCheck()
        cancelPendingEntrancePreview()
        stopPeriodicHomeRefresh()
        stopForegroundBleRssiPolling()
        stopLiveDeviceLocationUpdates()
        carVisual?.onHostPause()
        if (::featureSheets.isInitialized) {
            featureSheets.onHostPause()
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        wasBackgrounded = true
        cancelPendingForegroundPassiveUnlockCheck()
        // Snapshot live foreground proximity state BEFORE tearing down GATT for background handoff.
        val activeVin = profileStore.getActiveProfile().vin.trim().uppercase(Locale.US)
        val enabledPassiveVin = PassiveEntryStore.getActiveEnabledVin(this).trim().uppercase(Locale.US)
        val samePassiveVehicle = enabledPassiveVin.isNotBlank() && activeVin == enabledPassiveVin
        val wasNear = samePassiveVehicle && (bleController.isSessionReady() || bleConnected)
        val lastRssi = if (samePassiveVehicle) bleController.lastProximityRssi else null
        val alreadyUnlocked = samePassiveVehicle && (
            foregroundLiveBleLocked == false || activeTelemetry?.doorLocked == false
        )
        bleController.stopAutoProximityWatch()
        if (enabledPassiveVin.isNotBlank()) {
            bleController.disconnect()
        }
        PassiveEntryRuntime.setForegroundAppActive(
            context = this,
            active = false,
            handoffFromNearSession = wasNear,
            handoffRssi = lastRssi,
            handoffVin = activeVin,
            handoffAlreadyUnlocked = alreadyUnlocked
        )
    }

    override fun onDestroy() {
        pendingResumeBleRunnable?.let(mainHandler::removeCallbacks)
        pendingResumeBleRunnable = null
        cancelPendingForegroundPassiveUnlockCheck()
        cancelPendingEntrancePreview()
        pendingEntrancePreviewStyle = null
        pendingShortcutRunnable?.let(contentFrame::removeCallbacks)
        pendingShortcutRunnable = null
        smsCountdownJob?.cancel()
        telemetryJob?.cancel()
        postActionRefreshJob?.cancel()
        periodicRefreshJob?.cancel()
        bleRssiPollJob?.cancel()
        stopLiveDeviceLocationUpdates()
        geocodeJob?.cancel()
        provisioningJob?.cancel()
        deckExpandAnimator?.cancel()
        deckHeightAnimator?.cancel()
        stopStatusAutoHide()
        dismissTopPairingPinDrawer(immediate = true)
        vehicleSwitcherPopup?.dismiss()
        vehicleSwitcherPopup = null
        dismissAppDialogImmediately()
        clearSubpageOverlayStack()
        settingsScreen?.dismissTransientOverlays()
        settingsScreen?.cancelBackgroundJobs()
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
        if (::passiveEntrySheets.isInitialized) passiveEntrySheets.dismiss()
        PassiveEntryRuntime.removeListener(passiveRuntimeUiListener)
        PassiveEntryRuntime.onAutoActionExecuted = null
        securityPassiveRefreshCallback = null
        bleController.close()
        super.onDestroy()
    }

    private fun startPeriodicHomeRefresh() {
        if (periodicRefreshJob?.isActive == true) return
        periodicRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000L)
                if (currentScreen == Screen.HOME) {
                    requestFreshDeviceLocationIfStale()
                    refreshTelemetry(silent = true)
                }
            }
        }
    }

    private fun stopPeriodicHomeRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    private fun startForegroundBleRssiPolling() {
        if (bleRssiPollJob?.isActive == true) return
        bleRssiPollJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000L)
                if (bleController.isSessionReady()) {
                    bleController.requestRemoteRssi()
                }
            }
        }
    }

    private fun stopForegroundBleRssiPolling() {
        bleRssiPollJob?.cancel()
        bleRssiPollJob = null
    }

    private fun resumeHomeForegroundLoops() {
        startPeriodicHomeRefresh()
        startForegroundBleRssiPolling()
        startLiveDeviceLocationUpdates()
        if (!PassiveEntryRuntime.isServiceRunning()) {
            startProximityWhenPermitted()
        }
        refreshTelemetry(silent = true)
    }

    private fun maybeRequestDeviceLocationPermissionOnce() {
        if (hasDeviceLocationPermission()) return
        if (!::locationPermissionLauncher.isInitialized) return
        if (uiPrefs.getBoolean(KEY_LOCATION_PERM_PROMPTED, false)) return
        uiPrefs.edit().putBoolean(KEY_LOCATION_PERM_PROMPTED, true).apply()
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun hasDeviceLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLiveDeviceLocationUpdates() {
        if (!hasDeviceLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (liveLocationListener == null) {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    onFreshDeviceLocationReceived(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            val candidateProviders = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.GPS_PROVIDER)
            }
            var registeredAny = false
            candidateProviders.forEach { provider ->
                val ok = runCatching {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 10_000L, 15f, listener, Looper.getMainLooper())
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
                if (ok) registeredAny = true
            }
            if (registeredAny) {
                liveLocationListener = listener
            }
        }
        requestFreshDeviceLocationIfStale()
    }

    private fun locationAgeMs(loc: Location): Long {
        val elapsedNanos = runCatching { loc.elapsedRealtimeNanos }.getOrDefault(0L)
        if (elapsedNanos > 0L) {
            return ((android.os.SystemClock.elapsedRealtimeNanos() - elapsedNanos) / 1_000_000L).coerceAtLeast(0L)
        }
        return (System.currentTimeMillis() - loc.time).coerceAtLeast(0L)
    }

    private fun isUsableDeviceLocation(loc: Location): Boolean {
        if (loc.latitude.isNaN() || loc.longitude.isNaN()) return false
        if (loc.latitude !in -90.0..90.0 || loc.longitude !in -180.0..180.0) return false
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return false
        return locationAgeMs(loc) <= MAX_DEVICE_LOCATION_AGE_MS
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun requestFreshDeviceLocationIfStale() {
        if (!hasDeviceLocationPermission()) return
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val existingAgeMs = latestLiveDeviceLocation?.let { locationAgeMs(it) } ?: Long.MAX_VALUE
        if (existingAgeMs < 15_000L || nowElapsed - lastLiveLocationRequestMs < 8_000L) return
        lastLiveLocationRequestMs = nowElapsed
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false) ->
                    LocationManager.FUSED_PROVIDER
                runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                    LocationManager.NETWORK_PROVIDER
                runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                    LocationManager.GPS_PROVIDER
                else -> null
            } ?: return
            liveLocationCancelSignal?.cancel()
            val signal = android.os.CancellationSignal()
            liveLocationCancelSignal = signal
            runCatching {
                lm.getCurrentLocation(provider, signal, mainExecutor) { loc ->
                    if (isDestroyed || isFinishing || signal.isCanceled) return@getCurrentLocation
                    if (loc != null) onFreshDeviceLocationReceived(loc)
                }
            }
        }
    }

    private fun stopLiveDeviceLocationUpdates() {
        liveLocationCancelSignal?.cancel()
        liveLocationCancelSignal = null
        val listener = liveLocationListener ?: return
        liveLocationListener = null
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { lm.removeUpdates(listener) }
    }

    private fun onFreshDeviceLocationReceived(location: Location) {
        if (!isUsableDeviceLocation(location)) return
        val prev = latestLiveDeviceLocation
        if (prev != null && isUsableDeviceLocation(prev)) {
            val ageDeltaMs = locationAgeMs(prev) - locationAgeMs(location)
            // If incoming fix is older, ignore; if within 60s, ignore much worse accuracy.
            if (ageDeltaMs < 0L) return
            if (ageDeltaMs < 60_000L && location.hasAccuracy() && prev.hasAccuracy() &&
                location.accuracy > prev.accuracy * 2f && location.accuracy > 120f
            ) {
                return
            }
        }
        latestLiveDeviceLocation = location
        if (currentScreen == Screen.HOME) {
            val updatedLocSub = formatLocationSubtitle(activeTelemetry)
            locationRowSubtitle?.apply {
                text = updatedLocSub
                visibility = if (updatedLocSub.isNotBlank()) View.VISIBLE else View.GONE
            }
        }
        locationAddressObservers.toList().forEach { observer ->
            observer(currentStreetLabel())
        }
    }

    private fun dismissAppDialogImmediately() {
        val syncDismiss = dismissAppDialogSync
        dismissAppDialogSync = null
        if (syncDismiss != null) {
            syncDismiss()
        } else {
            appDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        }
        appDialog = null
    }

    private fun trackAppDialog(dialog: android.app.Dialog, onDismissExtra: (() -> Unit)? = null): android.app.Dialog {
        dismissAppDialogSync = null
        appDialog = dialog
        dialog.setOnDismissListener {
            if (appDialog === dialog) {
                appDialog = null
                dismissAppDialogSync = null
            }
            onDismissExtra?.invoke()
        }
        return dialog
    }

    private fun configureSystemBars(color: Int) {
        AppSystemInsets.applyEdgeToEdge(window, color)
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
            // Mount Home directly at identity transform/alpha so 3D WebView is never clipped by ancestor layer animation.
            // Preserve spatial continuity by smoothly gliding out the departing auth screen overlay on top.
            val departingViews = (0 until contentFrame.childCount).map { contentFrame.getChildAt(it) }
            val canCrossFadeOut = departingViews.isNotEmpty() && direction > 0
            contentFrame.animate().cancel()
            contentFrame.scaleX = 1f
            contentFrame.scaleY = 1f
            contentFrame.alpha = 1f
            rootView.alpha = 1f
            rootView.translationX = 0f
            rootView.translationY = 0f
            if (canCrossFadeOut) {
                contentFrame.addView(rootView, 0, matchParent())
                val exitDx = -dpF(28f)
                departingViews.forEach { oldView ->
                    oldView.isClickable = false
                    oldView.isFocusable = false
                    oldView.animate().cancel()
                    oldView.animate()
                        .alpha(0f)
                        .translationX(exitDx)
                        .setDuration(240L)
                        .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                        .withEndAction { contentFrame.removeView(oldView) }
                        .start()
                }
            } else {
                contentFrame.removeAllViews()
                contentFrame.addView(rootView, matchParent())
            }
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
        dismissAppDialogImmediately()
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
        trackAppDialog(dialog)
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
        AppSystemInsets.applyBottomNavPadding(root)
        presentScreen(Screen.WELCOME, root)

        // Centered oversized monochrome Qiri+ vector emblem (3.333:1 natural ratio)
        val screenW = resources.displayMetrics.widthPixels
        val logoW = (screenW * 0.72f).roundToInt().coerceIn(dp(216), dp(280))
        val logoH = (logoW / 3.333f).roundToInt().coerceAtLeast(dp(64))
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.ic_qiri_adaptive_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Qiri+ Logo"
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
        dismissAppDialogImmediately()
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
        trackAppDialog(dialog)
        dialog.show()
        MotionSpec.animateModalEnter(sheet)
    }

    // =========================================================================
    // Screen 2B: Qiri+ Dark Login Form (Password & SMS + 0843 Device Binding)
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
        val mobileLabelView = text(if (startWithSms) "手机号" else "手机号 / 账号", 12.5f, ThemeTokens.TEXT_SUB)
        loginMobileLabel = mobileLabelView
        page.addView(mobileLabelView, lp(top = dp(28)))
        val (mobileBox, mobileEdit) = AppComponents.createUnderlineInput(
            context = this,
            hint = if (startWithSms) "请输入 11 位手机号" else "请输入手机号或账号",
            inputType = if (startWithSms) {
                InputType.TYPE_CLASS_PHONE
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            },
            maxLength = if (startWithSms) 11 else 64
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
        val changed = loginUsesSms != useSms
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
        if (changed) {
            val activeBlock = if (useSms) loginSmsBlock else loginPasswordBlock
            activeBlock?.let { MotionSpec.animatePanelSwitch(it, direction = if (useSms) 1 else -1) }
        }
        loginMobileLabel?.text = if (useSms) "手机号" else "手机号 / 账号"
        loginMobileInput?.apply {
            val currentText = text?.toString().orEmpty()
            hint = if (useSms) "请输入 11 位手机号" else "请输入手机号或账号"
            inputType = if (useSms) {
                InputType.TYPE_CLASS_PHONE
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            filters = arrayOf(android.text.InputFilter.LengthFilter(if (useSms) 11 else 64))
            val trimmed = if (useSms && currentText.length > 11) currentText.take(11) else currentText
            if (trimmed != currentText) {
                setText(trimmed)
            }
            setSelection(text?.length ?: 0)
        }
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
        if (loginUsesSms) {
            if (!isValidMobile(mobile)) {
                showLoginError("请输入正确的 11 位手机号")
                return
            }
        } else if (mobile.isBlank()) {
            showLoginError("请输入手机号或账号")
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

    private fun performLogin(
        mobile: String,
        secret: String,
        bySms: Boolean,
        overrideDeviceId: String = ""
    ) {
        if (loginBusy) return
        setLoginBusy(true, "正在登录...")
        hideLoginError()
        lifecycleScope.launch {
            try {
                val deviceId = overrideDeviceId.trim().ifBlank { profileStore.defaultDeviceId() }
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
                    profileStore.pruneProfilesNotMatchingAccount(userId, resolvedMobile)
                    profileStore.saveProfile(
                        profile = profile,
                        activate = true,
                        preserveExistingPinIfBlank = true,
                        preserveExistingBleKeysIfBlank = true
                    )
                }
                managementScreens.rememberVehicleSummaries(vehicles, saved.accessToken)
                if (primary != null) {
                    persistVehicleMetadata(primary)
                    val primaryName = vehicleModelName(primary)
                    CarWidgetManager.syncActiveVehicle(
                        context = this@MainActivity,
                        vin = saved.vin,
                        modelName = primaryName,
                        plateNo = primary.plateNo.trim(),
                        modelKey = VehicleModelRepository.resolveModelKey(primaryName),
                        engineType = primary.engineType.trim(),
                        telemetry = null
                    )
                } else {
                    clearVehicleMetadata()
                    CarWidgetStateStore.clear(this@MainActivity)
                    CarWidgetManager.updateAllWidgets(this@MainActivity)
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
        val boundDeviceId = binding.deviceId.trim().ifBlank { profileStore.defaultDeviceId() }
        val targetDisplay = SensitiveUiText.maskPhone(binding.telephone.ifBlank { mobile })
        val promptMsg = binding.message.orEmpty().ifBlank {
            if (binding.smsSent) {
                "检测到新设备登录，已向 $targetDisplay 发送换机绑定验证码，请输入刚刚收到的短信验证码。"
            } else {
                "检测到新设备登录，当前未自动发送换机绑定短信，请点击下方按钮重新发送验证码。"
            }
        }
        var resendCountdownJob: Job? = null
        showBottomSheet(
            title = "新设备短信验证",
            onDismiss = { resendCountdownJob?.cancel() }
        ) { sheet, dialog ->
            var verifying = false

            sheet.addView(text(promptMsg, 13f, TEXT_SECONDARY).apply {
                setLineSpacing(0f, 1.45f)
                layoutParams = lp(top = dp(8))
            })
            val codeInput = styledInput("请输入换机绑定短信验证码", InputType.TYPE_CLASS_NUMBER).apply {
                filters = arrayOf(android.text.InputFilter.LengthFilter(8))
                imeOptions = EditorInfo.IME_ACTION_DONE
                layoutParams = lp(top = dp(16), height = dp(50))
            }
            sheet.addView(codeInput)

            val sheetErrorView = text("", 12.5f, ThemeTokens.DANGER).apply {
                visibility = View.GONE
                layoutParams = lp(top = dp(10))
            }
            sheet.addView(sheetErrorView)

            fun showSheetError(msg: String) {
                sheetErrorView.text = msg
                sheetErrorView.visibility = View.VISIBLE
                android.animation.ObjectAnimator.ofFloat(
                    codeInput,
                    View.TRANSLATION_X,
                    0f, -12f, 12f, -8f, 8f, -4f, 4f, 0f
                ).setDuration(260L).start()
            }

            lateinit var resendBtn: TextView
            fun startResendCooldown() {
                resendCountdownJob?.cancel()
                resendCountdownJob = lifecycleScope.launch {
                    for (remaining in 60 downTo 1) {
                        resendBtn.isEnabled = false
                        resendBtn.alpha = 0.55f
                        resendBtn.text = "${remaining}s 后可重新发送换机绑定短信"
                        delay(1_000L)
                    }
                    resendBtn.isEnabled = true
                    resendBtn.alpha = 1f
                    resendBtn.text = "未收到？重新发送换机绑定短信"
                }
            }

            resendBtn = text("未收到？重新发送换机绑定短信", 13f, LINK_BLUE).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (verifying || resendCountdownJob?.isActive == true) return@setOnClickListener
                    isEnabled = false
                    alpha = 0.55f
                    text = "正在重新发送..."
                    sheetErrorView.visibility = View.GONE
                    lifecycleScope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.requestDeviceBindSmsDetailed(mobile, method)
                            }
                        }.getOrNull()
                        if (!dialog.isShowing) return@launch
                        if (res?.success == true) {
                            Toast.makeText(this@MainActivity, "换机绑定验证码已重新发送", Toast.LENGTH_SHORT).show()
                            startResendCooldown()
                        } else {
                            isEnabled = true
                            alpha = 1f
                            text = "未收到？重新发送换机绑定短信"
                            val errMsg = res?.message.orEmpty().ifBlank { "重新发送失败，请稍后重试" }
                            showSheetError(errMsg)
                        }
                    }
                }
            }
            sheet.addView(resendBtn, lp(top = dp(4)))
            if (binding.smsSent) {
                startResendCooldown()
            }

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sheet.addView(buttons, lp(top = dp(14)))
            val cancelBtn = secondaryActionButton("取消") {
                if (!verifying) dialog.dismiss()
            }
            lateinit var confirmBtn: TextView
            confirmBtn = primaryActionButton(if (bySms) "验证设备" else "验证并登录") {
                if (verifying) return@primaryActionButton
                val code = codeInput.text.toString().trim()
                if (code.isBlank()) {
                    showSheetError("请输入换机绑定短信验证码")
                    return@primaryActionButton
                }
                verifying = true
                sheetErrorView.visibility = View.GONE
                codeInput.isEnabled = false
                cancelBtn.isEnabled = false
                cancelBtn.alpha = 0.55f
                confirmBtn.isEnabled = false
                confirmBtn.alpha = 0.65f
                confirmBtn.text = "正在验证..."

                lifecycleScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.confirmDeviceBindSmsDetailed(
                                loginName = mobile,
                                validateCode = code,
                                loginMethod = method,
                                deviceId = boundDeviceId
                            )
                        }
                    }.getOrNull()
                    if (!dialog.isShowing) return@launch
                    if (result?.success == true) {
                        resendCountdownJob?.cancel()
                        dialog.dismiss()
                        if (bySms) {
                            // Matches official zyhl LoginActivity: after 108 succeeds, original 920 login SMS
                            // is already consumed; reset countdown and prompt user to request a fresh login SMS.
                            smsCountdownJob?.cancel()
                            smsCodeButton?.apply {
                                isEnabled = true
                                alpha = 1f
                                text = "获取验证码"
                            }
                            loginSmsInput?.setText("")
                            loginSmsInput?.requestFocus()
                            showLoginInfo("换机绑定成功！请重新点击“获取验证码”完成登录")
                            Toast.makeText(this@MainActivity, "更换设备成功，请重新获取验证码登录", Toast.LENGTH_LONG).show()
                        } else {
                            val password = loginPasswordInput?.text?.toString().orEmpty()
                            performLogin(mobile, password, bySms = false, overrideDeviceId = boundDeviceId)
                        }
                    } else {
                        verifying = false
                        codeInput.isEnabled = true
                        cancelBtn.isEnabled = true
                        cancelBtn.alpha = 1f
                        confirmBtn.isEnabled = true
                        confirmBtn.alpha = 1f
                        confirmBtn.text = if (bySms) "验证设备" else "验证并登录"
                        showSheetError(result?.message.orEmpty().ifBlank { "换机验证码校验失败，请核对后重试" })
                    }
                }
            }
            buttons.addView(cancelBtn, weightedLp(0, dp(46)))
            buttons.addView(confirmBtn, weightedLp(dp(10), dp(46)))

            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            codeInput.requestFocus()
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
            setTextColor(ThemeTokens.DANGER)
            text = message
            visibility = View.VISIBLE
        }
    }

    private fun showLoginInfo(message: String) {
        loginErrorView?.apply {
            setTextColor(ThemeTokens.ACCENT_LIGHT)
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
        clearSubpageOverlayStack()
        val existingHome = homeRootView
        if (existingHome != null && currentScreen == Screen.SETTINGS && existingHome.parent === contentFrame) {
            returnFromSettingsToHome()
            return
        }

        settingsScreen?.dismissTransientOverlays()
        settingsScreen?.cancelBackgroundJobs()
        settingsScreen = null
        settingsRootView = null
        cancelPendingEntrancePreview()
        pendingEntrancePreviewStyle = null
        deckExpandAnimator?.cancel()
        deckHeightAnimator?.cancel()
        quickDeckTargetHeightPx = 0
        homeCollapsedHeroHeightPx = 0
        homeExpandedHeroHeightPx = 0
        lastSentExpandedVisibleRatio = 1.0f
        homeLowerSheetView = null
        deckExpandProgress = 0f
        deckDragStartProgress = 0f
        quickDeckExpanded = false
        quickDeckEditMode = false
        quickDeckTileViews.clear()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        bleConnected = bleController.isSessionReady()
        bleNearby = bleController.isVehicleNearby
        activeTelemetry = null
        geocodeJob?.cancel()
        lastGeocodedKey = ""
        lastGeocodedAddress = null
        lastGeocodeSettled = false
        lastGeocodeFailedAtMs = 0L

        val profile = profileStore.getActiveProfile().also { currentProfile = it }
        sessionVehiclePin = profile.controlPin

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

        // 2. Stage Layout: FrameLayout holding full-height 3D Car Hero behind opaque Lower Sheet.
        //    When pulled down, the 3D hero viewport expands smoothly (zero WebView resize!) while
        //    the car rotates to top-down and enlarges, and the extra control deck unfolds strictly
        //    below the 4 quick buttons & above the pull bar and scrollable menu (温度/地点/安全性).
        val stageFrame = FrameLayout(this).apply {
            setBackgroundColor(BG_HOME)
            clipChildren = true
        }
        homeStageFrame = stageFrame
        homeShell.addView(stageFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val collapsedHeroH = dp(248)
        val expandedStageH = computeExpandedStageHeight()
        homeCollapsedHeroHeightPx = collapsedHeroH
        homeExpandedHeroHeightPx = expandedStageH

        val carHeroContainer = FrameLayout(this).apply {
            setBackgroundColor(BG_HOME)
        }
        carHeroContainerView = carHeroContainer
        carVisual = VehicleVisualView(this).apply {
            modelKey = uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty().ifBlank { "arrizo8" }
            entranceStyle = uiPrefs.getString(KEY_ENTRANCE_STYLE, DEFAULT_ENTRANCE_STYLE)
                .orEmpty()
                .ifBlank { DEFAULT_ENTRANCE_STYLE }
            vehicleLocked = true
            setCollapsedVisibleRatio(collapsedHeroH.toFloat() / expandedStageH.toFloat())
            isClickable = true
            isFocusable = true
            contentDescription = "3D 车辆视图，单击闪灯，双击展开或收起更多车控"
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

        // 3. Lower Sheet (opaque BG_HOME):
        //    - Pinned 4-button Quick Action Row
        //    - Red-boxed Expandable Control Deck (OUTSIDE ScrollView so scrolling NEVER pushes it under the 4 buttons!)
        //    - Pull Handle Pill ("小白条")
        //    - Scrollable Menu Rows (温度 / 充电? / 地点 / 安全性) + Footer
        val lowerScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(BG_HOME)
        }
        lowerOverlayContainer = lowerScroll

        val tempLocation = IntArray(2)
        fun isPointInsideView(view: View?, rawX: Float, rawY: Float): Boolean {
            if (view == null || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
            view.getLocationOnScreen(tempLocation)
            val l = tempLocation[0].toFloat()
            val t = tempLocation[1].toFloat()
            return rawX >= l && rawX <= l + view.width && rawY >= t && rawY <= t + view.height
        }

        val lowerSheet = DeckGestureLayout(
            context = this,
            getDeckProgress = { deckExpandProgress },
            isTouchInsideScrollArea = { rx, ry -> isPointInsideView(lowerScroll, rx, ry) },
            isTouchBlockedForEditDrag = { rx, ry ->
                quickDeckEditMode && (
                    isPointInsideView(quickActionRow, rx, ry) ||
                        isPointInsideView(quickDeckClipContainer, rx, ry)
                    )
            },
            canScrollAreaPullDown = { lowerScroll.scrollY == 0 },
            onDragStart = { dir ->
                cancelPendingEntrancePreview()
                deckExpandAnimator?.cancel()
                deckHeightAnimator?.cancel()
                deckDragStartProgress = deckExpandProgress
                if (dir > 0) {
                    updateTpmsBadges(activeTelemetry)
                    renderQuickDeckGrid(animateLabels = false)
                    quickDeckTargetHeightPx = measureQuickDeckTargetHeight()
                }
            },
            onDragMove = { dy, _, _ ->
                val deckH = quickDeckTargetHeightPx.takeIf { it > 0 } ?: dp(172)
                val heroDelta = (effectiveExpandedHeroHeightPx(deckH) - collapsedHeroH).coerceAtLeast(0)
                val rangePx = (heroDelta + deckH).toFloat().coerceAtLeast(dpF(180f))
                val p = (deckDragStartProgress + dy / rangePx).coerceIn(0f, 1f)
                applyDeckExpandProgress(p)
            },
            onDragEnd = { _, velocityY, dir ->
                val shouldExpand = when {
                    velocityY > 750f -> true
                    velocityY < -750f -> false
                    dir > 0 -> deckExpandProgress >= 0.32f
                    else -> deckExpandProgress > 0.68f
                }
                if (shouldExpand != quickDeckExpanded) {
                    AppHaptics.perform(homeStageFrame, HapticLevel.MEDIUM_CONFIRM)
                }
                animateDeckTo(shouldExpand)
            }
        ).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_HOME)
            clipChildren = true
            clipToPadding = true
        }
        homeLowerSheetView = lowerSheet

        // 3a. Pinned 4-button Quick Action Row (directly below 3D car)
        buildIconQuickActionRow(lowerSheet)

        // 3b. Expandable Control Deck sits strictly BELOW the 4 quick buttons and ABOVE pull handle & 温度
        buildExpandableQuickDeck(lowerSheet)

        // 3c. Subtle minimalist pull handle (between 4 quick buttons / expanded deck and 温度; tap or drag to toggle)
        val pullHintStrip = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "展开或收起更多车控按钮"
            setOnClickListener {
                AppHaptics.perform(homeStageFrame, HapticLevel.MEDIUM_CONFIRM)
                animateDeckTo(!quickDeckExpanded)
            }
        }
        val pullHintBar = View(this).apply {
            background = rounded(Color.argb(56, 255, 255, 255), 2f)
        }
        pullHintStrip.addView(pullHintBar, FrameLayout.LayoutParams(dp(36), dpF(3f).roundToInt(), Gravity.CENTER))
        quickDeckPullHintStrip = pullHintStrip
        lowerSheet.addView(pullHintStrip, lp(height = dp(20)))

        // 3d. Scrollable Menu Rows (温度 / 充电? / 地点 / 安全性) + Footer
        val lowerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_HOME)
            setPadding(0, 0, 0, dp(28))
        }
        AppSystemInsets.applyBottomNavPadding(lowerScroll)
        lowerScroll.addView(lowerContent, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        buildMenuRows(lowerContent)
        buildHomeFooter(lowerContent)

        lowerSheet.addView(
            lowerScroll,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        )

        stageFrame.addView(
            lowerSheet,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP).apply {
                topMargin = collapsedHeroH
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
        // During cold launch (onCreate), onResume() immediately starts BLE/Location/telemetry once
        // after the first frame commits; only kick off inline when navigating to Home while resumed.
        if (isActivityResumed) {
            if (!PassiveEntryRuntime.isServiceRunning()) {
                startProximityWhenPermitted()
            }
            startPeriodicHomeRefresh()
            startForegroundBleRssiPolling()
            startLiveDeviceLocationUpdates()
            if (hasAllBlePermissionsGranted()) {
                maybeRequestDeviceLocationPermissionOnce()
            }
            refreshTelemetry(silent = true)
        }
        syncCloudVehicleMeta()
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
            if (matched != null) {
                persistVehicleMetadata(matched)
                val resolvedName = vehicleModelName(matched)
                CarWidgetManager.syncActiveVehicle(
                    context = this@MainActivity,
                    vin = matched.vin.trim().uppercase(Locale.US),
                    modelName = resolvedName,
                    plateNo = matched.plateNo.trim(),
                    modelKey = VehicleModelRepository.resolveModelKey(resolvedName),
                    engineType = matched.engineType.trim(),
                    telemetry = activeTelemetry
                )
            }
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
                clearVehicleMetadata()
                provisioningJob?.cancel()
                provisioningActive = false
                bleController.resetTargetAndDisconnect()
                bleConnected = false
                bleNearby = false
                lastPairingPin = ""
                CarWidgetStateStore.clear(this@MainActivity)
                CarWidgetManager.updateAllWidgets(this@MainActivity)
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
                controlPin = existing?.controlPin.orEmpty()
            )
            val saved = withContext(Dispatchers.IO) {
                localByVin.keys.filter { it !in cloudVins }.forEach { profileStore.deleteProfile(it) }
                profileStore.saveProfile(updatedProfile, activate = true)
            }
            val displayName = vehicleModelName(chosen)
            persistVehicleMetadata(chosen)
            CarWidgetManager.syncActiveVehicle(
                context = this@MainActivity,
                vin = saved.vin,
                modelName = displayName,
                plateNo = chosen.plateNo.trim(),
                modelKey = VehicleModelRepository.resolveModelKey(displayName),
                engineType = chosen.engineType.trim(),
                telemetry = null
            )
            cloudTripTrackEnabled = chosen.isTracksOpen ?: cloudTripTrackEnabled
            currentProfile = saved
            sessionVehiclePin = saved.controlPin

            val wasEmpty = carVisual == null || !currentVin.equals(chosenVin, ignoreCase = true)
            if (wasEmpty) {
                provisioningJob?.cancel()
                provisioningActive = false
                telemetryJob?.cancel()
                postActionRefreshJob?.cancel()
                geocodeJob?.cancel()
                lastGeocodedKey = ""
                lastGeocodedAddress = null
                lastGeocodeFailedAtMs = 0L
                bleController.resetTargetAndDisconnect()
                bleConnected = false
                bleNearby = false
                lastPairingPin = ""
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
            onVehicleBound = { boundVin, boundPin -> handleVehicleBound(boundVin, boundPin) }
        )
        pushSubpageOverlay(addView)
    }

    private fun handleManagementVehicleBound(boundVin: String, boundPin: String, isDefault: Boolean) {
        val activeProfile = profileStore.getActiveProfile()
        if (isDefault || activeProfile.vin.isBlank()) {
            handleVehicleBound(boundVin = boundVin, boundPin = boundPin, shouldPopOverlay = false)
            return
        }
        val cleanVin = boundVin.trim().uppercase(Locale.US)
        val cleanPin = boundPin.trim()
        if (cleanVin.isNotBlank() && cleanPin.length == 6 && cleanPin.all { it.isDigit() }) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    profileStore.updateControlPin(cleanVin, cleanPin)
                }
            }
        }
        syncCloudVehicleMeta()
    }

    private fun handleVehicleBound(
        boundVin: String = "",
        boundPin: String = "",
        shouldPopOverlay: Boolean = true
    ) {
        if (shouldPopOverlay) {
            popSubpageOverlay()
        }
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

            val normalizedBoundVin = boundVin.trim().uppercase(Locale.US)
            val chosen = cloudVehicles.firstOrNull { normalizedBoundVin.isNotBlank() && it.vin.trim().uppercase(Locale.US) == normalizedBoundVin }
                ?: cloudVehicles.firstOrNull { it.vin.trim().uppercase(Locale.US) !in existingVins }
                ?: cloudVehicles.firstOrNull { it.selected == "1" }
                ?: cloudVehicles.firstOrNull()
            if (chosen == null) {
                showHomeStatus("车辆已绑定，正在同步列表...")
                return@launch
            }
            val newVin = chosen.vin.trim().uppercase(Locale.US)
            val pinForNewVehicle = if (normalizedBoundVin.isBlank() || newVin == normalizedBoundVin) {
                boundPin.trim()
            } else {
                ""
            }
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
                controlPin = pinForNewVehicle
            )
            val saved = withContext(Dispatchers.IO) {
                profileStore.saveProfile(newProfile, activate = true)
            }
            val displayName = vehicleModelName(chosen)
            persistVehicleMetadata(chosen)
            CarWidgetManager.syncActiveVehicle(
                context = this@MainActivity,
                vin = saved.vin,
                modelName = displayName,
                plateNo = chosen.plateNo.trim(),
                modelKey = VehicleModelRepository.resolveModelKey(displayName),
                engineType = chosen.engineType.trim(),
                telemetry = null
            )
            cloudTripTrackEnabled = chosen.isTracksOpen ?: cloudTripTrackEnabled
            currentProfile = saved
            sessionVehiclePin = saved.controlPin
            provisioningJob?.cancel()
            provisioningActive = false
            telemetryJob?.cancel()
            postActionRefreshJob?.cancel()
            geocodeJob?.cancel()
            lastGeocodedKey = ""
            lastGeocodedAddress = null
            lastGeocodeFailedAtMs = 0L
            bleController.resetTargetAndDisconnect()
            bleConnected = false
            bleNearby = false
            lastPairingPin = ""
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
        settingsScreen?.dismissTransientOverlays()
        settingsScreen?.cancelBackgroundJobs()
        settingsScreen = null
        if (overlay != null) {
            overlay.isClickable = false
            overlay.isFocusable = false
            MotionSpec.animateSubpageExit(overlay, underlying = null) {
                contentFrame.removeView(overlay)
            }
        }
        carVisual?.onHostResume()
        refreshHomeHeaderAndRows()
        resumeHomeForegroundLoops()

        val previewStyle = pendingEntrancePreviewStyle
        pendingEntrancePreviewStyle = null
        cancelPendingEntrancePreview()
        if (previewStyle != null && !quickDeckExpanded && deckExpandProgress <= 0.05f && subpageOverlayStack.isEmpty()) {
            val task = Runnable {
                pendingEntrancePreviewRunnable = null
                if (currentScreen == Screen.HOME &&
                    !wasBackgrounded &&
                    !isFinishing &&
                    !isDestroyed &&
                    !quickDeckExpanded &&
                    deckExpandProgress <= 0.05f &&
                    subpageOverlayStack.isEmpty()
                ) {
                    carVisual?.playEntranceAnimation(previewStyle)
                }
            }
            pendingEntrancePreviewRunnable = task
            mainHandler.postDelayed(task, if (overlay != null) 180L else 0L)
        }
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
        cancelPendingEntrancePreview()
        pendingEntrancePreviewStyle = null
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
            fetchCloudUserInfo = {
                val active = profileStore.getActiveProfile()
                if (active.accessToken.isBlank()) null
                else runCatching { httpClient.fetchUserInfo(active.accessToken) }.getOrNull()
            },
            updateCloudProfileField = { updateType, content ->
                val active = profileStore.getActiveProfile()
                if (active.accessToken.isBlank()) false
                else runCatching {
                    httpClient.updateUserProfileField(updateType, content, active.accessToken)
                }.getOrDefault(false)
            },
            onOpenDebugDiagnostics = { openDebugDiagnosticsScreen() },
            onEntranceStyleChanged = { newStyle ->
                carVisual?.entranceStyle = newStyle
                pendingEntrancePreviewStyle = newStyle
            },
            fetchRegionCities = {
                val active = profileStore.getActiveProfile()
                httpClient.fetchRegionCities(active.accessToken)
            }
        )
        settingsScreen = screen
        currentScreen = Screen.SETTINGS

        settingsRootView?.let { contentFrame.removeView(it) }
        val settingsView = screen.buildRootView()
        settingsRootView = settingsView
        contentFrame.addView(settingsView, matchParent())
        MotionSpec.animateSubpageEnter(settingsView, underlying = null)
    }

    private fun persistVehicleMetadata(vehicle: VehicleSummary) {
        uiPrefs.edit()
            .putString(KEY_MODEL_NAME, vehicleModelName(vehicle))
            .putString(KEY_PLATE_NO, vehicle.plateNo.trim())
            .putString(KEY_ENGINE_TYPE, vehicle.engineType.trim())
            .putString(KEY_AIR_TYPE, vehicle.airType.trim())
            .putString(KEY_SUNROOF_TYPE, vehicle.sunroofType.trim())
            .putString(KEY_TEMP_LO, vehicle.tempLo.trim())
            .putString(KEY_TEMP_HI, vehicle.tempHi.trim())
            .putString(KEY_OPEN_AIR, vehicle.openAir.trim())
            .putString(KEY_VEHICLE_SN, vehicle.sn.trim())
            .putString(KEY_VEHICLE_ICCID, vehicle.iccid.trim())
            .apply()
    }

    private fun clearVehicleMetadata() {
        uiPrefs.edit()
            .remove(KEY_MODEL_NAME)
            .remove(KEY_PLATE_NO)
            .remove(KEY_ENGINE_TYPE)
            .remove(KEY_AIR_TYPE)
            .remove(KEY_SUNROOF_TYPE)
            .remove(KEY_TEMP_LO)
            .remove(KEY_TEMP_HI)
            .remove(KEY_OPEN_AIR)
            .remove(KEY_VEHICLE_SN)
            .remove(KEY_VEHICLE_ICCID)
            .apply()
    }

    private fun sunroofExtraParams(): Map<String, String> {
        val raw = uiPrefs.getString(KEY_SUNROOF_TYPE, "").orEmpty().trim()
        return if (raw == "0" || raw == "1") mapOf("sunroofType" to raw) else emptyMap()
    }

    private fun activePowertrain(telemetry: VehicleTelemetry? = activeTelemetry): VehiclePowertrain {
        val engineType = uiPrefs.getString(KEY_ENGINE_TYPE, "").orEmpty()
        val modelName = uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty()
        return VehiclePowertrain.resolve(engineType, modelName, telemetry)
    }

    private fun resolveVehiclePinForAction(actionLabel: String, onPinReady: (String) -> Unit) {
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
            Toast.makeText(this, "当前暂无有效车辆信息", Toast.LENGTH_SHORT).show()
            return
        }
        val cachedPin = profile.controlPin.trim().ifBlank { sessionVehiclePin.trim() }
        val needPin = uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, true) || cachedPin.length != 6
        if (!needPin) {
            sessionVehiclePin = cachedPin
            onPinReady(cachedPin)
            return
        }
        dismissAppDialogImmediately()
        lateinit var dialog: android.app.Dialog
        dialog = SixDigitPinSheet.showVerifyPinSheet(
            context = this,
            title = "输入 PIN 码",
            subtitle = "请输入 6 位云端 PIN 码以执行「$actionLabel」"
        ) { pin, showError, dismiss ->
            lifecycleScope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.verifyVehiclePin(profile.vin, pin, profile.accessToken)
                    }
                }
                if (isFinishing || isDestroyed || !dialog.isShowing) return@launch
                res.onFailure { err ->
                    if (err is CherySessionExpiredException) {
                        dismiss()
                    } else {
                        showError("网络异常，PIN 码校验失败，请重试")
                    }
                    return@launch
                }
                if (res.getOrDefault(false)) {
                    sessionVehiclePin = pin
                    currentProfile = withContext(Dispatchers.IO) {
                        profileStore.updateControlPin(profile.vin, pin)
                    } ?: profileStore.getActiveProfile()
                    dismiss()
                    onPinReady(pin)
                } else {
                    showError("云端 PIN 码错误，请重试")
                }
            }
        }
        trackAppDialog(dialog)
    }

    private fun formatEnergyDisplay(telemetry: VehicleTelemetry?): String {
        val powertrain = activePowertrain(telemetry)
        val fuel = telemetry?.fuelPercent
        val battery = telemetry?.batteryPercent
        val range = telemetry?.remainingRangeKm
        val evRange = telemetry?.electricRangeKm
        return when (powertrain) {
            VehiclePowertrain.EV -> {
                if (!energyShowsRange) {
                    val prefix = if (telemetry?.isCharging == true) "充电中 " else "电 "
                    when {
                        battery != null -> "$prefix${battery.roundToInt()}%"
                        evRange != null -> "${evRange.roundToInt()} km"
                        range != null && range > 0 -> "${range.roundToInt()} km"
                        else -> "--%"
                    }
                } else {
                    val r = evRange ?: range
                    when {
                        r != null && r > 0 -> "${r.roundToInt()} km"
                        battery != null -> "电 ${battery.roundToInt()}%"
                        else -> "-- km"
                    }
                }
            }
            VehiclePowertrain.PHEV -> {
                if (!energyShowsRange) {
                    when {
                        battery != null && fuel != null -> "电 ${battery.roundToInt()}% · 油 ${fuel.roundToInt()}%"
                        battery != null -> "电 ${battery.roundToInt()}%"
                        fuel != null -> "油 ${fuel.roundToInt()}%"
                        else -> "--%"
                    }
                } else {
                    when {
                        evRange != null && range != null && range > 0 ->
                            "电 ${evRange.roundToInt()} · 综 ${range.roundToInt()} km"
                        range != null && range > 0 -> "${range.roundToInt()} km"
                        evRange != null -> "纯电 ${evRange.roundToInt()} km"
                        else -> "-- km"
                    }
                }
            }
            VehiclePowertrain.ICE -> {
                val energy = fuel ?: battery
                if (!energyShowsRange) {
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
        AppSystemInsets.applyBottomNavPadding(scroll)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Group 1: 控车安全
        content.addView(AppComponents.createSectionHeader(this, "控车安全"), lp())

        // Row 1: PIN 码 switch (default ON; controls both BLE & HTTP; toggling requires cloud PIN verification)
        val pinSwitch = ToggleSwitch(this).apply {
            checked = uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, true)
            onToggleAttempt = { targetChecked ->
                val profile = profileStore.getActiveProfile()
                if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
                    Toast.makeText(this@MainActivity, "当前暂无有效车辆信息", Toast.LENGTH_SHORT).show()
                } else {
                    val actionDesc = if (targetChecked) "开启" else "关闭"
                    dismissAppDialogImmediately()
                    lateinit var dialog: android.app.Dialog
                    dialog = SixDigitPinSheet.showVerifyPinSheet(
                        context = this@MainActivity,
                        title = "验证 PIN 码",
                        subtitle = "请输入 6 位云端 PIN 码以${actionDesc}每次控车验证"
                    ) { pin, showError, dismiss ->
                        lifecycleScope.launch {
                            val res = runCatching {
                                withContext(Dispatchers.IO) {
                                    httpClient.verifyVehiclePin(profile.vin, pin, profile.accessToken)
                                }
                            }
                            if (isFinishing || isDestroyed || !dialog.isShowing) return@launch
                            res.onFailure { err ->
                                if (err is CherySessionExpiredException) {
                                    dismiss()
                                } else {
                                    showError("网络异常，PIN 码校验失败，请重试")
                                }
                                return@launch
                            }
                            if (res.getOrDefault(false)) {
                                sessionVehiclePin = pin
                                currentProfile = withContext(Dispatchers.IO) {
                                    profileStore.updateControlPin(profile.vin, pin)
                                } ?: profileStore.getActiveProfile()
                                uiPrefs.edit().putBoolean(KEY_REQUIRE_PIN_EVERY_TIME, targetChecked).apply()
                                CarWidgetManager.updateAllWidgets(this@MainActivity)
                                setChecked(targetChecked, animate = true)
                                dismiss()
                                val msg = if (targetChecked) "已开启每次控车验证 PIN 码" else "已关闭每次控车验证 PIN 码"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            } else {
                                showError("云端 PIN 码错误，请重试")
                            }
                        }
                    }
                    trackAppDialog(dialog)
                }
            }
        }
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.LOCK_CLOSED,
                title = "PIN 码",
                subtitle = "控车时验证 6 位 PIN 码",
                trailingView = pinSwitch,
                showChevron = false,
                onClick = null
            ),
            lp()
        )
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Row 1b: 蓝牙无感 (prerequisite-gated before enable)
        val passiveSwitch = ToggleSwitch(this)
        val passiveRow = AppComponents.createListRow(
            context = this,
            icon = AppIconType.RADAR,
            title = "蓝牙无感",
            subtitle = "检查前置条件中...",
            trailingView = passiveSwitch,
            showChevron = false
        ) {
            val profile = profileStore.getActiveProfile()
            val cfg = PassiveEntryStore.load(this, profile.vin)
            if (cfg.enabled) {
                passiveEntrySheets.showPassiveEntryConfigSheet()
            } else {
                passiveEntrySheets.showPrerequisiteGateSheet(inspectOnly = false) {
                    securityPassiveRefreshCallback?.invoke()
                }
            }
        }
        val passiveSubtitleTv = (passiveRow as? LinearLayout)
            ?.getChildAt(1)
            ?.let { it as? LinearLayout }
            ?.getChildAt(1) as? TextView
        val passiveIconView = (passiveRow as? LinearLayout)?.getChildAt(0) as? AppIconView

        fun refreshPassiveEntrySecurityRow() {
            val profile = profileStore.getActiveProfile()
            val cfg = PassiveEntryStore.load(this, profile.vin)
            val vinKey = profile.vin.trim().uppercase(Locale.US)
            if (vinKey.isNotBlank()) {
                lastPassiveAutoUnlockActiveByVin[vinKey] = cfg.enabled && cfg.autoUnlockEnabled
            }
            val report = PassiveEntryPrerequisiteChecker.evaluate(this, profile)
            val snap = PassiveEntryRuntime.currentSnapshot()
            passiveSwitch.setChecked(cfg.enabled, animate = false)
            passiveIconView?.tintColor = if (cfg.enabled) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_PRIMARY
            passiveSubtitleTv?.text = when {
                cfg.enabled && !report.allRequiredSatisfied ->
                    "缺 ${report.missingRequiredCount} 项条件 · 点此修复"
                cfg.enabled -> {
                    val modeBits = buildList {
                        if (cfg.autoUnlockEnabled) add("解锁")
                        if (cfg.autoLockEnabled) add("上锁")
                    }.joinToString("/").ifBlank { "仅监听" }
                    val stateLabel = if (snap.state == com.chery.carplus.ble.PassiveState.PAUSED_FOREGROUND) {
                        if (bleConnected || bleController.isSessionReady()) "已连接" else "监听中"
                    } else {
                        snap.state.label
                    }
                    "$modeBits · $stateLabel"
                }
                report.allRequiredSatisfied ->
                    "靠近解锁 · 离车上锁"
                else ->
                    "需完成 ${report.missingRequiredCount} 项设置"
            }
        }
        securityPassiveRefreshCallback = { refreshPassiveEntrySecurityRow() }
        refreshPassiveEntrySecurityRow()

        passiveSwitch.onToggleAttempt = { targetEnabled ->
            val profile = profileStore.getActiveProfile()
            if (profile.vin.isBlank()) {
                passiveSwitch.setChecked(false, animate = true)
                Toast.makeText(this@MainActivity, "当前暂无有效车辆信息", Toast.LENGTH_SHORT).show()
            } else if (targetEnabled) {
                val report = PassiveEntryPrerequisiteChecker.evaluate(this@MainActivity, profile)
                if (!report.allRequiredSatisfied) {
                    passiveSwitch.setChecked(false, animate = true)
                    passiveEntrySheets.showPrerequisiteGateSheet(inspectOnly = false) {
                        refreshPassiveEntrySecurityRow()
                    }
                } else {
                    passiveSwitch.setChecked(false, animate = false)
                    verifyPinAndEnablePassiveEntry(profile) { enabled ->
                        passiveSwitch.setChecked(enabled, animate = true)
                        refreshPassiveEntrySecurityRow()
                    }
                }
            } else {
                dismissAppDialogImmediately()
                trackAppDialog(
                    AppComponents.showConfirmDialog(
                        context = this@MainActivity,
                        title = "关闭蓝牙无感",
                        message = "关闭后将停止靠近解锁与离车上锁。",
                        confirmText = "确认关闭",
                        cancelText = "取消",
                        dangerConfirm = true
                    ) {
                        val currentCfg = PassiveEntryStore.load(this@MainActivity, profile.vin)
                        PassiveEntryStore.save(this@MainActivity, currentCfg.copy(enabled = false))
                        val vinKey = profile.vin.trim().uppercase(Locale.US)
                        if (vinKey.isNotBlank()) {
                            lastPassiveAutoUnlockActiveByVin[vinKey] = false
                        }
                        PassiveEntryWakeManager.disable(this@MainActivity, profile.bleMac)
                        passiveSwitch.setChecked(false, animate = true)
                        refreshPassiveEntrySecurityRow()
                        Toast.makeText(this@MainActivity, "已关闭蓝牙无感", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        content.addView(passiveRow, lp())
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Row 2: 修改 PIN 码 (6-digit stepped wizard)
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.KEY_ROUND,
                title = "修改 PIN 码",
                showChevron = true
            ) { featureSheets.showPinManagementModal(profileStore.getActiveProfile()) },
            lp()
        )
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Row 2b: 忘记 PIN 码（车辆识别码 + 短信重置 922 + 124）
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.INFO_CIRCLE,
                title = "忘记 PIN 码",
                subtitle = "验证车辆信息重置",
                showChevron = true
            ) {
                featureSheets.showResetPinByVehicleSheet(
                    profile = profileStore.getActiveProfile(),
                    defaultSn = uiPrefs.getString(KEY_VEHICLE_SN, "").orEmpty(),
                    defaultIccid = uiPrefs.getString(KEY_VEHICLE_ICCID, "").orEmpty()
                )
            },
            lp()
        )
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Group 2: 车辆防护
        content.addView(AppComponents.createSectionHeader(this, "车辆防护"), lp())

        // Row 3: 防盗提醒 (dedicated standalone toggle row; vehicle diagnosis removed)
        val theftTrailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val theftSyncLabel = text("读取中...", 12f, TEXT_SECONDARY)
        theftTrailing.addView(
            theftSyncLabel,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) }
        )
        var theftBusy = true
        var theftEnabledState = false
        val theftSwitch = ToggleSwitch(this).apply {
            setChecked(false, animate = false)
            isEnabled = false
            alpha = 0.55f
        }

        lateinit var promptAntiTheftPin: (Boolean) -> Unit

        fun submitAntiTheftToggle(targetEnabled: Boolean, verifiedPin: String) {
            val profile = profileStore.getActiveProfile()
            if (profile.vin.isBlank() || profile.accessToken.isBlank()) return
            theftBusy = true
            theftSyncLabel.text = "同步中..."
            theftSyncLabel.visibility = View.VISIBLE
            theftSwitch.isEnabled = false
            theftSwitch.alpha = 0.55f
            lifecycleScope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        val payload = org.json.JSONObject().apply {
                            put("vin", profile.vin.trim().uppercase(Locale.US))
                            put("instruction", "1204")
                            put("paras", org.json.JSONObject().put("0002", if (targetEnabled) 1 else 0))
                            put("passwordType", "0")
                            if (verifiedPin.isNotBlank()) {
                                put("safePasswd", httpClient.hashPasswordOrPin(verifiedPin))
                            }
                        }
                        httpClient.executeZyhlService("970", payload, profile.accessToken)
                    }
                }
                theftBusy = false
                theftSyncLabel.visibility = View.GONE
                theftSwitch.isEnabled = true
                theftSwitch.alpha = 1f
                val env = res.getOrNull()
                val ok = env != null && httpClient.is970CommandAccepted(env)
                if (ok) {
                    theftEnabledState = targetEnabled
                    lastKnownAntiTheftEnabled = targetEnabled
                    theftSwitch.setChecked(targetEnabled, animate = true)
                    Toast.makeText(
                        this@MainActivity,
                        if (targetEnabled) "已开启防盗提醒" else "已关闭防盗提醒",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (env != null && httpClient.isPinRejectionEnvelope(env)) {
                    theftSwitch.setChecked(theftEnabledState, animate = true)
                    sessionVehiclePin = ""
                    if (profile.vin.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            profileStore.updateControlPin(profile.vin, "")
                        }?.let { currentProfile = it }
                        CarWidgetManager.updateAllWidgets(this@MainActivity)
                    }
                    Toast.makeText(this@MainActivity, "本机 PIN 码已失效，请重新验证", Toast.LENGTH_SHORT).show()
                    promptAntiTheftPin(targetEnabled)
                } else {
                    theftSwitch.setChecked(theftEnabledState, animate = true)
                    if (res.exceptionOrNull() !is CherySessionExpiredException) {
                        Toast.makeText(this@MainActivity, "防盗提醒设置同步失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        promptAntiTheftPin = { targetEnabled ->
            val profile = profileStore.getActiveProfile()
            dismissAppDialogImmediately()
            lateinit var dialog: android.app.Dialog
            dialog = SixDigitPinSheet.showVerifyPinSheet(
                context = this@MainActivity,
                title = "输入 PIN 码",
                subtitle = "请输入 6 位云端 PIN 码以${if (targetEnabled) "开启" else "关闭"}防盗提醒"
            ) { pin, showError, dismiss ->
                lifecycleScope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.IO) {
                            httpClient.verifyVehiclePin(profile.vin, pin, profile.accessToken)
                        }
                    }
                    if (isFinishing || isDestroyed || !dialog.isShowing) return@launch
                    res.onFailure { err ->
                        if (err is CherySessionExpiredException) {
                            dismiss()
                        } else {
                            showError("网络异常，PIN 码校验失败，请重试")
                        }
                        return@launch
                    }
                    if (res.getOrDefault(false)) {
                        sessionVehiclePin = pin
                        currentProfile = withContext(Dispatchers.IO) {
                            profileStore.updateControlPin(profile.vin, pin)
                        } ?: profileStore.getActiveProfile()
                        CarWidgetManager.updateAllWidgets(this@MainActivity)
                        dismiss()
                        submitAntiTheftToggle(targetEnabled, pin)
                    } else {
                        showError("云端 PIN 码错误，请重试")
                    }
                }
            }
            trackAppDialog(dialog)
        }

        theftSwitch.onToggleAttempt = { targetEnabled ->
            if (!theftBusy) {
                val profile = profileStore.getActiveProfile()
                val titleText = if (targetEnabled) "开启防盗提醒" else "关闭防盗提醒"
                val messageText = if (targetEnabled) {
                    "开启后当车辆发生异常移动或触发防盗告警时将及时推送提醒。"
                } else {
                    "关闭后将不再接收车辆防盗异常告警提醒，确定关闭吗？"
                }
                dismissAppDialogImmediately()
                trackAppDialog(
                    AppComponents.showConfirmDialog(
                        context = this@MainActivity,
                        title = titleText,
                        message = messageText,
                        confirmText = "确认",
                        cancelText = "取消",
                        dangerConfirm = !targetEnabled
                    ) {
                        val cachedPin = profile.controlPin.trim().ifBlank { sessionVehiclePin.trim() }
                        val needPin = uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, true) || cachedPin.length != 6
                        if (needPin) {
                            promptAntiTheftPin(targetEnabled)
                        } else {
                            submitAntiTheftToggle(targetEnabled, cachedPin)
                        }
                    }
                )
            }
        }
        theftTrailing.addView(theftSwitch)
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.SHIELD_CHECK,
                title = "防盗提醒",
                subtitle = "异常移动与防盗告警",
                trailingView = theftTrailing,
                showChevron = false,
                onClick = null
            ),
            lp()
        )
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Row 3b: 限速提醒 (zyhl 950 + 970 instruction 1203; 60–120 km/h)
        var speedLimitEnabledState = false
        var speedLimitKmhState = 100
        val speedLimitValueTv = text("读取中...", 13f, TEXT_SECONDARY)
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.CLOCK,
                title = "限速提醒",
                subtitle = "超速时推送提醒",
                trailingView = speedLimitValueTv,
                showChevron = true
            ) {
                featureSheets.showSpeedLimitSheet(
                    profile = profileStore.getActiveProfile(),
                    currentEnabled = speedLimitEnabledState,
                    currentSpeedKmh = speedLimitKmhState,
                    resolvePin = { desc, cb -> resolveVehiclePinForAction(desc, cb) }
                ) { enabled, speedKmh ->
                    speedLimitEnabledState = enabled
                    if (speedKmh > 0) speedLimitKmhState = speedKmh
                    speedLimitValueTv.text = if (enabled && speedKmh > 0) "$speedKmh km/h" else "已关闭"
                    speedLimitValueTv.setTextColor(if (enabled) ThemeTokens.ACCENT_LIGHT else TEXT_SECONDARY)
                }
            },
            lp()
        )
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Fetch live anti-theft & speed-limit remind state on Security screen open
        lifecycleScope.launch {
            val active = profileStore.getActiveProfile()
            val status = if (active.vin.isNotBlank() && active.accessToken.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchAntiTheftSettings(active.vin, active.accessToken)
                    }
                }.getOrNull()
            } else null
            theftBusy = false
            theftSyncLabel.visibility = View.GONE
            theftSwitch.isEnabled = true
            theftSwitch.alpha = 1f
            if (status != null) {
                theftEnabledState = status.theftRemindEnabled
                theftSwitch.setChecked(status.theftRemindEnabled, animate = false)
                speedLimitEnabledState = status.speedLimitEnabled
                val parsedSpeed = status.speedLimitKmh.trim().toIntOrNull() ?: 0
                if (parsedSpeed in 60..120) speedLimitKmhState = parsedSpeed
                speedLimitValueTv.text = if (status.speedLimitEnabled && parsedSpeed > 0) "$parsedSpeed km/h" else "已关闭"
                speedLimitValueTv.setTextColor(if (status.speedLimitEnabled) ThemeTokens.ACCENT_LIGHT else TEXT_SECONDARY)
            } else {
                speedLimitValueTv.text = "已关闭"
            }
        }

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
        content.addView(AppComponents.createHairline(this, startInsetDp = 58))

        // Group 3: 隐私与轨迹
        content.addView(AppComponents.createSectionHeader(this, "隐私与轨迹"), lp())

        // Row 5: 行程轨迹记录 (per-vehicle cloud trip track toggle with confirmation)
        val tripTrailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tripSyncLabel = text("同步中...", 12f, TEXT_SECONDARY).apply {
            visibility = View.GONE
        }
        tripTrailing.addView(
            tripSyncLabel,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) }
        )
        var tripBusy = false
        val tripSwitch = ToggleSwitch(this).apply {
            setChecked(cloudTripTrackEnabled ?: true, animate = false)
        }
        tripSwitch.onToggleAttempt = { targetEnabled ->
            if (!tripBusy) {
                val previousEnabled = cloudTripTrackEnabled ?: tripSwitch.checked
                val titleText = if (targetEnabled) "开启行程轨迹记录" else "关闭行程轨迹记录"
                val messageText = if (targetEnabled) {
                    "开启后将自动记录当前车辆的行驶路线与历史行程数据。"
                } else {
                    "关闭后将停止记录当前车辆新的行驶轨迹，已有历史记录不受影响。"
                }
                AppComponents.showConfirmDialog(
                    context = this@MainActivity,
                    title = titleText,
                    message = messageText,
                    confirmText = "确认",
                    cancelText = "取消",
                    dangerConfirm = !targetEnabled
                ) {
                    if (tripBusy) return@showConfirmDialog
                    val profile = profileStore.getActiveProfile()
                    if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
                        tripSwitch.setChecked(previousEnabled, animate = true)
                        Toast.makeText(this@MainActivity, "当前暂无有效车辆信息", Toast.LENGTH_SHORT).show()
                        return@showConfirmDialog
                    }
                    tripBusy = true
                    tripSyncLabel.visibility = View.VISIBLE
                    tripSwitch.isEnabled = false
                    tripSwitch.alpha = 0.55f
                    lifecycleScope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.setTripTrackSwitch(profile.vin, targetEnabled, profile.accessToken)
                            }
                        }.getOrDefault(false)
                        tripBusy = false
                        tripSyncLabel.visibility = View.GONE
                        tripSwitch.isEnabled = true
                        tripSwitch.alpha = 1f
                        if (ok) {
                            cloudTripTrackEnabled = targetEnabled
                            tripSwitch.setChecked(targetEnabled, animate = true)
                            val msg = if (targetEnabled) "已开启行程轨迹记录" else "已关闭行程轨迹记录"
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        } else {
                            tripSwitch.setChecked(previousEnabled, animate = true)
                            Toast.makeText(this@MainActivity, "行程轨迹设置同步失败，请稍后重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        tripTrailing.addView(tripSwitch)
        content.addView(
            AppComponents.createListRow(
                context = this,
                icon = AppIconType.LOCATION_ARROW,
                title = "行程轨迹记录",
                subtitle = "自动记录行驶路线",
                trailingView = tripTrailing,
                showChevron = false,
                onClick = null
            ),
            lp()
        )

        currentScreen = Screen.SECURITY
        securityOverlayView?.let { contentFrame.removeView(it) }
        securityOverlayView = root
        contentFrame.addView(root, matchParent())
        MotionSpec.animateSubpageEnter(root, underlying = null)
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
        maybeRequestDeviceLocationPermissionOnce()
        startLiveDeviceLocationUpdates()

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
        MotionSpec.animateSubpageEnter(locationView, underlying = null)
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
            overlay.isClickable = false
            overlay.isFocusable = false
            var finished = false
            lateinit var entry: Pair<View, () -> Unit>
            val finalizeExit = {
                if (!finished) {
                    finished = true
                    pendingExitDestroys.remove(entry)
                    destroyCb?.invoke()
                    contentFrame.removeView(overlay)
                }
            }
            entry = overlay to finalizeExit
            pendingExitDestroys.add(entry)
            MotionSpec.animateSubpageExit(overlay, underlying = null) { finalizeExit() }
        } else {
            destroyCb?.invoke()
        }
        carVisual?.onHostResume()
        refreshHomeHeaderAndRows()
        resumeHomeForegroundLoops()
    }

    private fun showMessagesSubpageScreen() {
        stopStatusAutoHide()
        cancelPendingEntrancePreview()
        stopLiveDeviceLocationUpdates()
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
        MotionSpec.animateSubpageEnter(messagesView, underlying = null)
    }

    private fun returnFromMessagesToHome() {
        currentScreen = Screen.HOME
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        val overlay = messagesOverlayView
        messagesOverlayView = null
        if (overlay != null) {
            overlay.isClickable = false
            overlay.isFocusable = false
            MotionSpec.animateSubpageExit(overlay, underlying = null) {
                contentFrame.removeView(overlay)
            }
        }
        carVisual?.onHostResume()
        refreshHomeHeaderAndRows()
        resumeHomeForegroundLoops()
    }

    private fun pushSubpageOverlay(view: View, onDestroy: (() -> Unit)? = null) {
        stopStatusAutoHide()
        cancelPendingEntrancePreview()
        configureSystemBars(BG_HOME)
        contentFrame.setBackgroundColor(BG_HOME)
        if (currentScreen == Screen.HOME) {
            carVisual?.onHostPause()
        }
        val previousTop = subpageOverlayStack.lastOrNull()?.first
        subpageOverlayStack.addLast(view to onDestroy)
        contentFrame.addView(view, matchParent())
        MotionSpec.animateSubpageEnter(view, underlying = previousTop)
    }

    private fun popSubpageOverlay() {
        val entry = subpageOverlayStack.removeLastOrNull() ?: return
        val (overlay, destroyCb) = entry
        val revealedTop = subpageOverlayStack.lastOrNull()?.first
        overlay.isClickable = false
        overlay.isFocusable = false
        var finished = false
        lateinit var exitEntry: Pair<View, () -> Unit>
        val finalizeExit = {
            if (!finished) {
                finished = true
                pendingExitDestroys.remove(exitEntry)
                destroyCb?.invoke()
                contentFrame.removeView(overlay)
            }
        }
        exitEntry = overlay to finalizeExit
        pendingExitDestroys.add(exitEntry)
        MotionSpec.animateSubpageExit(overlay, underlying = revealedTop) { finalizeExit() }

        if (subpageOverlayStack.isEmpty()) {
            when (currentScreen) {
                Screen.HOME -> {
                    carVisual?.onHostResume()
                    refreshHomeHeaderAndRows()
                    resumeHomeForegroundLoops()
                }
                Screen.SETTINGS -> {
                    settingsScreen?.refreshVehicleRow()
                }
                else -> Unit
            }
        }
    }

    private fun flushPendingExitDestroys() {
        val snapshot = pendingExitDestroys.toList()
        pendingExitDestroys.clear()
        snapshot.forEach { (view, finalizeExit) ->
            view.animate().cancel()
            finalizeExit()
        }
    }

    private fun clearSubpageOverlayStack() {
        flushPendingExitDestroys()
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
        securityPassiveRefreshCallback = null
        if (overlay != null) {
            overlay.isClickable = false
            overlay.isFocusable = false
            MotionSpec.animateSubpageExit(overlay, underlying = null) {
                contentFrame.removeView(overlay)
            }
        }
        carVisual?.onHostResume()
        refreshHomeHeaderAndRows()
        resumeHomeForegroundLoops()
    }

    private fun formatSecurityDigitalKeyStatus(): String {
        val profile = profileStore.getActiveProfile()
        val isBleReady = bleConnected || bleController.isSessionReady()
        val isConnecting = !isBleReady && (bleNearby || bleController.isVehicleNearby)
        val keyReady = hasCompleteKeyMaterial(profile)
        return when {
            isBleReady -> "手机钥匙已连接 · 靠近控车已就绪"
            isConnecting -> "已发现附近车辆 · 正在连接手机钥匙"
            keyReady -> "数字钥匙已就绪 · 靠近车辆自动连接"
            else -> "数字钥匙待同步 · 点击首页蓝牙图标可同步"
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
        ): TpmsCornerViews {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                this.gravity = if (alignEnd) Gravity.END else Gravity.START
                background = null
                clipChildren = false
                clipToPadding = false
            }
            val pressureTv = text("--", 18f, Color.parseColor("#F2F4F8"), Typeface.BOLD).apply {
                maxLines = 1
                isSingleLine = true
                ellipsize = null
                includeFontPadding = false
                fontFeatureSettings = "tnum"
                setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
            }
            val tempTv = text("", 12f, ThemeTokens.TEXT_SUB).apply {
                maxLines = 1
                isSingleLine = true
                ellipsize = null
                includeFontPadding = false
                fontFeatureSettings = "tnum"
                visibility = View.GONE
                setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
            }
            box.addView(pressureTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            box.addView(
                tempTv,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = dp(2)
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
            return TpmsCornerViews(pressureTv = pressureTv, tempTv = tempTv)
        }

        tpmsFlCorner = makeMinimalTpmsCorner(Gravity.TOP or Gravity.START, false, dp(20), dp(14), 0, 0)
        tpmsFrCorner = makeMinimalTpmsCorner(Gravity.TOP or Gravity.END, true, 0, dp(14), dp(20), 0)
        tpmsRlCorner = makeMinimalTpmsCorner(Gravity.BOTTOM or Gravity.START, false, dp(20), 0, 0, dp(14))
        tpmsRrCorner = makeMinimalTpmsCorner(Gravity.BOTTOM or Gravity.END, true, 0, 0, dp(20), dp(14))
        heroContainer.addView(
            overlay,
            FrameLayout.LayoutParams(
                MATCH_PARENT,
                homeCollapsedHeroHeightPx.takeIf { it > 0 } ?: dp(248),
                Gravity.TOP
            )
        )
    }

    private fun parseCornerTyrePressureBar(pressureRaw: String): Double? {
        val raw = pressureRaw.trim()
        if (raw.isBlank() || raw == "0" || raw.equals("FFFF", ignoreCase = true) || raw == "65535") return null
        val match = NUMBER_EXTRACT_REGEX.find(raw) ?: return null
        val num = match.value.toDoubleOrNull() ?: return null
        val unit = activeTelemetry?.tyreUnit.orEmpty().trim().lowercase(Locale.US)
        val rawLower = raw.lowercase(Locale.US)
        val bar = when {
            rawLower.contains("psi") || unit.contains("psi") -> {
                if (num <= 0.0 || num > 100.0) return null
                num * 0.0689476
            }
            rawLower.contains("kpa") || unit.contains("kpa") -> {
                if (num <= 0.0 || num > 500.0) return null
                num / 100.0
            }
            rawLower.contains("bar") || unit.contains("bar") -> {
                if (num <= 0.0 || num > 10.0) return null
                num
            }
            else -> {
                if (num <= 0.0 || num > 500.0) return null
                when {
                    num >= 100.0 -> num / 100.0
                    num >= 10.0 -> num / 10.0
                    else -> num
                }
            }
        }
        return if (bar > 0.0 && bar <= 6.0) bar else null
    }

    private fun formatCornerTyrePressure(pressureRaw: String): String {
        val bar = parseCornerTyrePressureBar(pressureRaw) ?: return "--"
        return String.format(Locale.US, "%.2f", bar)
    }

    private fun updateTpmsBadges(telemetry: VehicleTelemetry?) {
        val normalColor = Color.parseColor("#F2F4F8")
        val warnColor = Color.parseColor("#F59E0B")
        val dangerColor = Color.parseColor("#F87171")

        fun isValidTemp(s: String): Boolean {
            if (s.isBlank() || s == "255" || s == "65535" || s.equals("FFFF", ignoreCase = true)) return false
            val n = s.toDoubleOrNull() ?: return false
            return n in -40.0..150.0
        }

        fun applyCorner(
            corner: TpmsCornerViews?,
            pressureRaw: String,
            vararg tempKeys: String
        ) {
            if (corner == null) return
            val bar = parseCornerTyrePressureBar(pressureRaw)
            if (bar != null) {
                corner.pressureTv.text = String.format(Locale.US, "%.2f", bar)
                corner.pressureTv.setTextColor(
                    when {
                        bar < 1.7 || bar > 3.2 -> dangerColor
                        bar < 2.0 || bar > 2.9 -> warnColor
                        else -> normalColor
                    }
                )
            } else {
                corner.pressureTv.text = "--"
                corner.pressureTv.setTextColor(ThemeTokens.TEXT_SUB)
            }

            val tempRaw = tempKeys.firstNotNullOfOrNull { key ->
                telemetry?.conditionFields?.get(key)?.trim()?.takeIf(::isValidTemp)
                    ?: telemetry?.conditionFields?.get(key.uppercase(Locale.US))?.trim()?.takeIf(::isValidTemp)
            }
            val tempNum = tempRaw?.toDoubleOrNull()
            if (tempRaw != null && tempNum != null) {
                corner.tempTv.text = if (tempNum % 1.0 == 0.0) tempNum.toInt().toString() else tempRaw
                corner.tempTv.setTextColor(if (tempNum >= 65.0) warnColor else ThemeTokens.TEXT_SUB)
                corner.tempTv.visibility = View.VISIBLE
            } else {
                corner.tempTv.visibility = View.GONE
            }
        }

        applyCorner(tpmsFlCorner, telemetry?.lFrontTyre.orEmpty(), "lFrontTyreTemp", "flTyreTemp", "tyreTempFL", "leftFrontTyreTemp")
        applyCorner(tpmsFrCorner, telemetry?.rFrontTyre.orEmpty(), "rFrontTyreTemp", "frTyreTemp", "tyreTempFR", "rightFrontTyreTemp")
        applyCorner(tpmsRlCorner, telemetry?.lRearTyre.orEmpty(), "lRearTyreTemp", "rlTyreTemp", "tyreTempRL", "leftRearTyreTemp")
        applyCorner(tpmsRrCorner, telemetry?.rRearTyre.orEmpty(), "rRearTyreTemp", "rrTyreTemp", "tyreTempRR", "rightRearTyreTemp")
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
        return (screenH * 0.405f).roundToInt().coerceIn(dp(296), dp(364))
    }

    private fun measureQuickActionRowHeight(): Int {
        val row = quickActionRow ?: return dp(if (quickDeckEditMode) 88 else 68)
        val width = homeStageFrame?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return row.measuredHeight.coerceAtLeast(dp(if (quickDeckEditMode) 84 else 64))
    }

    private fun effectiveExpandedHeroHeightPx(deckTargetH: Int): Int {
        val collapsedHeroH = homeCollapsedHeroHeightPx.takeIf { it > 0 } ?: dp(248)
        val expandedHeroH = homeExpandedHeroHeightPx.takeIf { it > 0 } ?: computeExpandedStageHeight()
        val stageH = homeStageFrame?.height?.takeIf { it > 0 } ?: return expandedHeroH
        val quickRowH = measureQuickActionRowHeight()
        val pullBarH = quickDeckPullHintStrip?.height?.takeIf { it > 0 } ?: dp(20)
        val minMenuPeek = if (quickDeckEditMode) dp(12) else dp(52)
        val maxSafeHero = (stageH - quickRowH - deckTargetH - pullBarH - minMenuPeek)
            .coerceAtLeast(collapsedHeroH)
        return expandedHeroH.coerceAtMost(maxSafeHero)
    }

    private fun buildExpandableQuickDeck(parent: LinearLayout) {
        val clip = FrameLayout(this).apply {
            setBackgroundColor(BG_HOME)
            clipChildren = true
            clipToPadding = true
            visibility = View.GONE
        }
        quickDeckClipContainer = clip

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_HOME)
            visibility = View.GONE
            alpha = 0f
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        quickDeckContainer = container

        container.addView(
            View(this).apply { setBackgroundColor(ThemeTokens.DIVIDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, dpF(0.6f).roundToInt().coerceAtLeast(1)).apply {
                bottomMargin = dp(8)
            }
        )

        val editHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        quickDeckEditHeader = editHeader
        val editTitle = text("", 12f, TEXT_SECONDARY).apply {
            maxLines = 2
            setLineSpacing(0f, 1.2f)
        }
        quickDeckEditTitle = editTitle
        editHeader.addView(
            editTitle,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        )

        val doneBtn = text("完成", 12.5f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(PRIMARY_BLUE, 14f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener {
                exitQuickDeckEditMode(showSavedToast = true)
            }
        }
        editHeader.addView(doneBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        container.addView(editHeader, lp())

        val gridHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        quickDeckGridHost = gridHost
        container.addView(gridHost, lp())

        // Anchor to Gravity.TOP so collapsing genuinely folds/retracts the deck in place
        // rather than sliding/shoving upper control rows upward underneath the 4 quick buttons.
        clip.addView(
            container,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP)
        )
        parent.addView(clip, LinearLayout.LayoutParams(MATCH_PARENT, 0))
    }

    private fun measureQuickDeckTargetHeight(): Int {
        val deck = quickDeckContainer ?: return dp(172)
        val width = homeStageFrame?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val prevVis = deck.visibility
        deck.visibility = View.VISIBLE
        deck.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measured = deck.measuredHeight.coerceAtLeast(dp(84))
        deck.visibility = prevVis
        return measured
    }

    private fun syncCarExpandedVisibleRatio(heroH: Int) {
        val fullExpandedH = homeExpandedHeroHeightPx.takeIf { it > 0 } ?: computeExpandedStageHeight()
        val ratio = (heroH.toFloat() / fullExpandedH.toFloat()).coerceIn(0.40f, 1.0f)
        if (abs(ratio - lastSentExpandedVisibleRatio) > 0.002f) {
            lastSentExpandedVisibleRatio = ratio
            carVisual?.setExpandedVisibleRatio(ratio)
        }
    }

    private fun syncExpandedClipHeightAnimated() {
        val clip = quickDeckClipContainer ?: return
        val newTarget = measureQuickDeckTargetHeight()
        quickDeckTargetHeightPx = newTarget
        if (!quickDeckExpanded || deckExpandProgress < 0.95f) return
        val startH = clip.layoutParams?.height?.takeIf { it > 0 } ?: clip.height
        val targetHeroH = effectiveExpandedHeroHeightPx(newTarget)
        val sheetLp = homeLowerSheetView?.layoutParams as? FrameLayout.LayoutParams
        val startHeroH = sheetLp?.topMargin ?: targetHeroH
        if (startH == newTarget && startHeroH == targetHeroH) {
            syncCarExpandedVisibleRatio(targetHeroH)
            return
        }
        deckHeightAnimator?.cancel()
        deckHeightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = MotionSpec.EASE_OUT_EXPO
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val curDeckH = (startH + (newTarget - startH) * f).roundToInt()
                val curHeroH = (startHeroH + (targetHeroH - startHeroH) * f).roundToInt()
                syncCarExpandedVisibleRatio(curHeroH)
                clip.layoutParams?.let { lp ->
                    if (lp.height != curDeckH) {
                        lp.height = curDeckH
                        clip.layoutParams = lp
                    }
                }
                homeLowerSheetView?.let { sheet ->
                    val lp = sheet.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null && lp.topMargin != curHeroH) {
                        lp.topMargin = curHeroH
                        sheet.layoutParams = lp
                    }
                }
                tpmsOverlayContainer?.let { tpms ->
                    val lp = tpms.layoutParams
                    if (lp != null && lp.height != curHeroH) {
                        lp.height = curHeroH
                        tpms.layoutParams = lp
                    }
                }
            }
            start()
        }
    }

    private fun applyDeckExpandProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        deckExpandProgress = p

        val targetH = quickDeckTargetHeightPx.takeIf { it > 0 } ?: measureQuickDeckTargetHeight()
        quickDeckTargetHeightPx = targetH

        val collapsedHeroH = homeCollapsedHeroHeightPx.takeIf { it > 0 } ?: dp(248)
        val expandedHeroH = effectiveExpandedHeroHeightPx(targetH)
        val curHeroH = (collapsedHeroH + (expandedHeroH - collapsedHeroH) * p).roundToInt()

        // 1. Drive 3D camera in exact 1:1 lockstep (sync capped expanded viewport ratio before setting progress)
        syncCarExpandedVisibleRatio(expandedHeroH)
        carVisual?.setDeckProgress(p)
        carHeroContainerView?.translationY = 0f

        // 2. Shift lowerSheet topMargin cleanly so 3D hero visibly enlarges without resizing WebView
        homeLowerSheetView?.let { sheet ->
            val lp = sheet.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.topMargin != curHeroH) {
                lp.topMargin = curHeroH
                sheet.layoutParams = lp
            }
        }

        // 3. Genuine fold/retract of the red-boxed deck strictly below the 4 quick buttons & above pull bar / 温度
        val visible = p > 0.002f
        val curH = if (visible) (targetH * p).roundToInt() else 0

        quickDeckClipContainer?.let { clip ->
            clip.visibility = if (visible) View.VISIBLE else View.GONE
            val lp = clip.layoutParams
            if (lp != null && lp.height != curH) {
                lp.height = curH
                clip.layoutParams = lp
            }
        }

        quickDeckContainer?.let { deck ->
            deck.visibility = if (visible) View.VISIBLE else View.GONE
            val pivotW = deck.width.takeIf { it > 0 }
                ?: homeStageFrame?.width?.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            deck.pivotX = pivotW * 0.5f
            deck.pivotY = 0f
            deck.alpha = ((p - 0.08f) / 0.92f).coerceIn(0f, 1f)
            deck.scaleX = 0.95f + 0.05f * p
            deck.scaleY = 0.84f + 0.16f * p
            deck.translationY = 0f

            val rowCount = quickDeckRowViews.size.coerceAtLeast(1)
            quickDeckRowViews.forEachIndexed { rowIdx, row ->
                val rowStart = (rowIdx.toFloat() / rowCount) * 0.48f
                val rowEnd = (rowStart + 0.52f).coerceAtMost(1f)
                val rowP = ((p - rowStart) / (rowEnd - rowStart)).coerceIn(0f, 1f)
                val easedRow = rowP * rowP * (3f - 2f * rowP)
                row.alpha = easedRow
                val rowScale = 0.90f + 0.10f * easedRow
                row.scaleX = rowScale
                row.scaleY = rowScale
                row.translationY = 0f
            }
        }

        // 4. Corner TPMS badges frame the visible expanded hero bounds once car settles near top-down view
        tpmsOverlayContainer?.let { tpms ->
            val tpmsLp = tpms.layoutParams
            if (tpmsLp != null && tpmsLp.height != curHeroH) {
                tpmsLp.height = curHeroH
                tpms.layoutParams = tpmsLp
            }
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
        deckHeightAnimator?.cancel()
        quickDeckExpanded = targetExpanded
        if (targetExpanded) {
            updateTpmsBadges(activeTelemetry)
            renderQuickDeckGrid(animateLabels = false)
            quickDeckTargetHeightPx = measureQuickDeckTargetHeight()
        }

        val start = deckExpandProgress
        val end = if (targetExpanded) 1f else 0f
        if (abs(start - end) < 0.005f) {
            applyDeckExpandProgress(end)
            if (!targetExpanded) {
                quickDeckEditMode = false
                rebuildQuickActionRow()
                renderQuickDeckGrid(animateLabels = false)
            }
            return
        }

        val spanFraction = abs(end - start).coerceIn(0.25f, 1f)
        val totalDurationMs = (360f * spanFraction).roundToInt().coerceIn(200, 360).toLong()

        var cancelled = false
        deckExpandAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = totalDurationMs
            interpolator = MotionSpec.EASE_OUT_EXPO
            addUpdateListener { anim ->
                applyDeckExpandProgress(anim.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && !targetExpanded) {
                        quickDeckEditMode = false
                        rebuildQuickActionRow()
                        renderQuickDeckGrid(animateLabels = false)
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
            QuickActionId.SUNROOF -> isSkylightOpen(effectiveSkylightState(t))
            QuickActionId.SUNROOF_TILT -> isSkylightTilted(effectiveSkylightState(t))
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
            showHomeStatus("手机钥匙已连接")
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
        if (!ensureBlePermissions()) return
        bleController.triggerImmediatePairingOrConnect(
            profileProvider = { profileStore.getActiveProfile() },
            onRequestEnableBluetooth = { requestBluetoothEnable() }
        )
        showHomeStatus("正在高灵敏度扫描并连接附近蓝牙钥匙...")
    }

    private fun enterQuickDeckEditMode(source: View? = null) {
        if (quickDeckEditMode) return
        AppHaptics.perform(source ?: homeStageFrame, HapticLevel.LONG_PRESS_MODE)
        quickDeckEditMode = true
        rebuildQuickActionRow(animateLabels = true)
        if (quickDeckExpanded) {
            renderQuickDeckGrid(animateLabels = true)
            syncExpandedClipHeightAnimated()
        } else {
            animateDeckTo(true)
        }
    }

    private fun exitQuickDeckEditMode(showSavedToast: Boolean = false) {
        if (!quickDeckEditMode) return
        quickDeckEditMode = false
        rebuildQuickActionRow(animateLabels = false)
        renderQuickDeckGrid(animateLabels = false)
        syncExpandedClipHeightAnimated()
        if (showSavedToast) {
            showHomeStatus("首页快捷栏已保存")
        }
    }

    private fun extractDraggedQuickAction(event: DragEvent): QuickActionDragPayload? {
        (event.localState as? QuickActionDragPayload)?.let { return it }
        val text = event.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        if (!text.startsWith(DRAG_PREFIX_QUICK_ACTION)) return null
        val id = text.removePrefix(DRAG_PREFIX_QUICK_ACTION)
        val action = QuickActionId.fromId(id) ?: return null
        val fromTop = action in loadQuickActions()
        return QuickActionDragPayload(action, fromTop, java.lang.ref.WeakReference(null))
    }

    private fun startQuickActionDrag(tile: View, action: QuickActionId, fromTopRow: Boolean): Boolean {
        AppHaptics.perform(tile, HapticLevel.LIGHT_TICK)
        val clip = ClipData.newPlainText("quick_action", "$DRAG_PREFIX_QUICK_ACTION${action.id}")
        val payload = QuickActionDragPayload(
            action = action,
            fromTopRow = fromTopRow,
            sourceViewRef = java.lang.ref.WeakReference(tile)
        )
        val shadow = View.DragShadowBuilder(tile)
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tile.startDragAndDrop(clip, shadow, payload, 0)
        } else {
            @Suppress("DEPRECATION")
            tile.startDrag(clip, shadow, payload, 0)
        }
        if (started) {
            tile.alpha = 0.28f
        }
        return started
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachEditModeDragTouchListener(
        tile: View,
        action: QuickActionId,
        fromTopRow: Boolean
    ) {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragStarted = false
        tile.setOnTouchListener { v, ev ->
            if (!quickDeckEditMode) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    dragStarted = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .alpha(0.85f)
                        .setDuration(80L)
                        .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                        .start()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragStarted) {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (dx * dx + dy * dy > touchSlop * touchSlop) {
                            dragStarted = true
                            v.animate().cancel()
                            v.scaleX = 1f
                            v.scaleY = 1f
                            v.alpha = 1f
                            startQuickActionDrag(v, action, fromTopRow)
                            return@setOnTouchListener true
                        }
                    }
                    dragStarted
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(120L)
                        .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                        .start()
                    val consumed = dragStarted
                    dragStarted = false
                    consumed
                }
                else -> false
            }
        }
    }

    private fun circularEditHaloDrawable(highlighted: Boolean, empty: Boolean = false): GradientDrawable? =
        when {
            highlighted -> GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(46, 61, 155, 255))
            }
            empty -> GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(22, 255, 255, 255))
            }
            else -> null
        }

    private fun applyTopSlotDragHover(slotView: View, hovered: Boolean, empty: Boolean = false) {
        slotView.background = null
        slotView.findViewWithTag<View>("control_icon_frame")?.background =
            circularEditHaloDrawable(highlighted = hovered, empty = empty)
        slotView.findViewWithTag<AppIconView>("control_icon")?.tintColor =
            if (hovered) ACCENT_BLUE else if (empty) TEXT_SECONDARY else TEXT_PRIMARY
        slotView.findViewWithTag<TextView>("control_label")?.setTextColor(
            when {
                hovered -> ACCENT_BLUE
                empty -> TEXT_SECONDARY
                else -> ThemeTokens.TEXT_BODY
            }
        )
        slotView.animate().cancel()
        slotView.animate()
            .scaleX(if (hovered) 1.08f else 1f)
            .scaleY(if (hovered) 1.08f else 1f)
            .setDuration(100L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .start()
    }

    private fun applyQuickActionDropOnTopSlot(targetSlotIndex: Int, payload: QuickActionDragPayload, anchor: View) {
        val mutable = loadQuickActions().toMutableList()
        val dragged = payload.action
        val existingIdx = mutable.indexOf(dragged)

        if (existingIdx >= 0) {
            // Reordering within the top pinned bar
            val clampedTarget = targetSlotIndex.coerceIn(0, mutable.lastIndex)
            if (existingIdx == clampedTarget) return
            mutable.removeAt(existingIdx)
            mutable.add(clampedTarget.coerceIn(0, mutable.size), dragged)
        } else {
            // Dragged from bottom deck into top bar
            if (targetSlotIndex < mutable.size) {
                if (mutable.size < MAX_QUICK_ACTIONS) {
                    mutable.add(targetSlotIndex, dragged)
                } else {
                    mutable[targetSlotIndex] = dragged
                }
            } else if (mutable.size < MAX_QUICK_ACTIONS) {
                mutable.add(dragged)
            } else {
                mutable[mutable.lastIndex] = dragged
            }
        }

        saveQuickActions(mutable)
        AppHaptics.perform(anchor, HapticLevel.MEDIUM_CONFIRM)
        rebuildQuickActionRow()
        renderQuickDeckGrid(animateLabels = false)
        syncExpandedClipHeightAnimated()
    }

    private fun renderQuickDeckGrid(animateLabels: Boolean = false) {
        val gridHost = quickDeckGridHost ?: return
        val selectedList = loadQuickActions()
        val selectedSet = selectedList.toSet()

        val defaultEditHint = "拖动调整顺序或替换 (${selectedList.size}/$MAX_QUICK_ACTIONS)"
        quickDeckEditHeader?.visibility = if (quickDeckEditMode) View.VISIBLE else View.GONE
        quickDeckEditTitle?.apply {
            text = defaultEditHint
            setTextColor(TEXT_SECONDARY)
        }

        // In edit mode, allow dragging a pinned top button onto the bottom deck to unpin it (if > 1 pinned)
        quickDeckContainer?.let { deck ->
            deck.setBackgroundColor(BG_HOME)
            if (quickDeckEditMode) {
                deck.setOnDragListener { v, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> {
                            event.localState is QuickActionDragPayload ||
                                event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                        }
                        DragEvent.ACTION_DRAG_ENTERED -> {
                            val payload = extractDraggedQuickAction(event)
                            if (payload?.fromTopRow == true && loadQuickActions().size > 1) {
                                quickDeckEditTitle?.apply {
                                    text = "松手从快捷栏移除"
                                    setTextColor(ACCENT_BLUE)
                                }
                                AppHaptics.perform(v, HapticLevel.LIGHT_TICK)
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_EXITED -> {
                            quickDeckEditTitle?.apply {
                                text = defaultEditHint
                                setTextColor(TEXT_SECONDARY)
                            }
                            true
                        }
                        DragEvent.ACTION_DROP -> {
                            quickDeckEditTitle?.apply {
                                text = defaultEditHint
                                setTextColor(TEXT_SECONDARY)
                            }
                            val payload = extractDraggedQuickAction(event) ?: return@setOnDragListener false
                            if (!payload.fromTopRow) return@setOnDragListener false
                            val mutable = loadQuickActions().toMutableList()
                            if (mutable.size <= 1) {
                                showHomeStatus("至少保留 1 项快捷控制")
                                return@setOnDragListener true
                            }
                            if (mutable.remove(payload.action)) {
                                saveQuickActions(mutable)
                                AppHaptics.perform(v, HapticLevel.MEDIUM_CONFIRM)
                                rebuildQuickActionRow()
                                renderQuickDeckGrid(animateLabels = false)
                                syncExpandedClipHeightAnimated()
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> {
                            quickDeckEditTitle?.apply {
                                text = defaultEditHint
                                setTextColor(TEXT_SECONDARY)
                            }
                            (event.localState as? QuickActionDragPayload)?.sourceViewRef?.get()?.alpha = 1f
                            true
                        }
                        else -> true
                    }
                }
            } else {
                deck.setOnDragListener(null)
            }
        }

        gridHost.removeAllViews()
        quickDeckRowViews.clear()
        quickDeckTileViews.clear()

        // Always show unpinned actions in a strict 4-column grid matching the 4 top quick buttons
        val displayActions = QuickActionId.values().filter { it !in selectedSet }
        val columns = MAX_QUICK_ACTIONS
        val minTileHeight = dp(74)
        displayActions.chunked(columns).forEachIndexed { rowIdx, chunk ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                clipChildren = false
                clipToPadding = false
            }
            quickDeckRowViews.add(rowView)
            chunk.forEachIndexed { _, action ->
                val isStateActive = isQuickActionActive(action)
                val iconType = if (quickDeckEditMode) action.icon else resolveQuickActionIcon(action, isStateActive)
                val tileLabel = resolveQuickActionLabel(action, isStateActive, quickDeckEditMode)

                val btn = AppComponents.createControlIconButton(
                    context = this,
                    iconType = iconType,
                    sizeDp = 25,
                    active = !quickDeckEditMode && isStateActive,
                    label = tileLabel,
                    showCheckDot = false,
                    dimmed = false
                ).apply {
                    background = null
                    foreground = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        defaultFocusHighlightEnabled = false
                    }
                    minimumHeight = minTileHeight
                    isClickable = true
                    isFocusable = true
                    isLongClickable = !quickDeckEditMode
                    contentDescription = action.label
                    if (quickDeckEditMode) {
                        setOnLongClickListener(null)
                        attachEditModeDragTouchListener(this, action, fromTopRow = false)
                    } else {
                        MotionSpec.attachPressPhysics(this, pressedScale = 0.88f)
                    }
                    setOnClickListener {
                        if (quickDeckEditMode) {
                            val mutable = loadQuickActions().toMutableList()
                            if (mutable.size < MAX_QUICK_ACTIONS && action !in mutable) {
                                mutable.add(action)
                                saveQuickActions(mutable)
                                AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                                rebuildQuickActionRow()
                                renderQuickDeckGrid(animateLabels = false)
                                syncExpandedClipHeightAnimated()
                            } else {
                                showHomeStatus("已满 4 项，请拖动到上方目标位置替换")
                            }
                        } else {
                            triggerQuickAction(action)
                        }
                    }
                    if (!quickDeckEditMode) {
                        setOnLongClickListener {
                            enterQuickDeckEditMode(this)
                            true
                        }
                    }
                }

                val iconView = btn.findViewWithTag<AppIconView>("control_icon")
                if (action == QuickActionId.CLIMATE && isStateActive && !quickDeckEditMode) {
                    iconView?.spinEnabled = true
                }
                quickDeckTileViews[action] = btn
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

                rowView.addView(btn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            // Pad incomplete trailing row so all 4 columns align identically with the 4 top buttons
            val remainingSlots = columns - chunk.size
            for (s in 0 until remainingSlots) {
                rowView.addView(View(this), LinearLayout.LayoutParams(0, minTileHeight, 1f))
            }
            gridHost.addView(rowView, lp(top = if (rowIdx == 0) 0 else dp(4)))
        }
        if (quickDeckExpanded && !quickDeckEditMode) {
            quickDeckTargetHeightPx = measureQuickDeckTargetHeight()
        }
    }

    private fun updateQuickDeckTileStates() {
        if (quickDeckEditMode) return
        if (quickDeckTileViews.isEmpty()) {
            renderQuickDeckGrid(animateLabels = false)
            return
        }
        quickDeckTileViews.forEach { (action, tile) ->
            val active = isQuickActionActive(action)
            val iconType = resolveQuickActionIcon(action, active)
            val labelText = resolveQuickActionLabel(action, active, editMode = false)
            tile.findViewWithTag<AppIconView>("control_icon")?.apply {
                this.iconType = iconType
                MotionSpec.animateIconTint(this, if (active) ACCENT_BLUE else TEXT_PRIMARY)
                spinEnabled = (action == QuickActionId.CLIMATE && active)
            }
            tile.findViewWithTag<TextView>("control_label")?.apply {
                text = labelText
                setTextColor(if (active) ACCENT_BLUE else ThemeTokens.TEXT_BODY)
            }
        }
    }

    private fun loadQuickActions(): List<QuickActionId> {
        val raw = uiPrefs.getString(KEY_QUICK_ACTIONS, DEFAULT_QUICK_ACTIONS).orEmpty()
        val parsed = raw.split(",")
            .mapNotNull { QuickActionId.fromId(it) }
            .distinct()
            .take(MAX_QUICK_ACTIONS)
        return parsed.ifEmpty { QuickActionId.DEFAULT_LIST }
    }

    private fun saveQuickActions(actions: List<QuickActionId>) {
        val normalized = actions.distinct().take(MAX_QUICK_ACTIONS).ifEmpty { QuickActionId.DEFAULT_LIST }
        uiPrefs.edit()
            .putString(KEY_QUICK_ACTIONS, normalized.joinToString(",") { it.id })
            .apply()
    }

    private fun rebuildQuickActionRow(animateLabels: Boolean = false) {
        val row = quickActionRow ?: return
        row.removeAllViews()
        quickActionIcons.clear()

        val actions = loadQuickActions()
        row.clipChildren = false
        row.clipToPadding = false
        row.setPadding(dp(18), dp(10), dp(18), dp(4))

        val slotCount = if (quickDeckEditMode) MAX_QUICK_ACTIONS else actions.size
        val editMinSlotHeight = dp(74)
        for (slotIdx in 0 until slotCount) {
            val action = actions.getOrNull(slotIdx)
            if (action != null) {
                val btn = AppComponents.createControlIconButton(
                    context = this,
                    iconType = action.icon,
                    sizeDp = if (quickDeckEditMode) 25 else 26,
                    active = false,
                    label = if (quickDeckEditMode) action.label else null,
                    showCheckDot = quickDeckEditMode
                ).apply {
                    background = null
                    foreground = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        defaultFocusHighlightEnabled = false
                    }
                    isClickable = true
                    isFocusable = true
                    isLongClickable = !quickDeckEditMode
                    contentDescription = action.label
                    if (quickDeckEditMode) {
                        minimumHeight = editMinSlotHeight
                        setOnLongClickListener(null)
                        attachEditModeDragTouchListener(this, action, fromTopRow = true)
                        setOnDragListener { v, event ->
                            when (event.action) {
                                DragEvent.ACTION_DRAG_STARTED -> {
                                    event.localState is QuickActionDragPayload ||
                                        event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                                }
                                DragEvent.ACTION_DRAG_ENTERED -> {
                                    applyTopSlotDragHover(v, hovered = true, empty = false)
                                    AppHaptics.perform(v, HapticLevel.LIGHT_TICK)
                                    true
                                }
                                DragEvent.ACTION_DRAG_EXITED -> {
                                    applyTopSlotDragHover(v, hovered = false, empty = false)
                                    true
                                }
                                DragEvent.ACTION_DROP -> {
                                    applyTopSlotDragHover(v, hovered = false, empty = false)
                                    val payload = extractDraggedQuickAction(event) ?: return@setOnDragListener false
                                    applyQuickActionDropOnTopSlot(slotIdx, payload, v)
                                    true
                                }
                                DragEvent.ACTION_DRAG_ENDED -> {
                                    applyTopSlotDragHover(v, hovered = false, empty = false)
                                    (event.localState as? QuickActionDragPayload)?.sourceViewRef?.get()?.alpha = 1f
                                    true
                                }
                                else -> true
                            }
                        }
                    } else {
                        MotionSpec.attachPressPhysics(this, pressedScale = 0.88f)
                        setOnDragListener(null)
                    }
                    setOnClickListener {
                        if (quickDeckEditMode) {
                            showHomeStatus("拖动可调整顺序，或拖至下方移除")
                        } else {
                            triggerQuickAction(action)
                        }
                    }
                    if (!quickDeckEditMode) {
                        setOnLongClickListener {
                            enterQuickDeckEditMode(this)
                            true
                        }
                    }
                }
                btn.findViewWithTag<AppIconView>("control_icon")?.let { iconView ->
                    quickActionIcons[action] = iconView
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
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(
                        0,
                        if (quickDeckEditMode) WRAP_CONTENT else dp(54),
                        1f
                    )
                )
            } else {
                // Frameless circular empty drop slot in Edit Mode when fewer than 4 buttons are pinned
                val emptySlot = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = null
                    foreground = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        defaultFocusHighlightEnabled = false
                    }
                    clipChildren = false
                    clipToPadding = false
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    minimumHeight = editMinSlotHeight
                    contentDescription = "空快捷槽位，可拖入按钮"

                    val iconFrame = FrameLayout(this@MainActivity).apply {
                        tag = "control_icon_frame"
                        background = circularEditHaloDrawable(highlighted = false, empty = true)
                    }
                    val addIcon = AppIconView(this@MainActivity, AppIconType.ADD_ICON, TEXT_SECONDARY).apply {
                        tag = "control_icon"
                    }
                    iconFrame.addView(addIcon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                    addView(iconFrame, LinearLayout.LayoutParams(dp(39), dp(39)))

                    val hintTv = text("可拖入", 11.5f, TEXT_SECONDARY).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                        tag = "control_label"
                    }
                    addView(
                        hintTv,
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                            topMargin = dp(5)
                        }
                    )

                    setOnDragListener { v, event ->
                        when (event.action) {
                            DragEvent.ACTION_DRAG_STARTED -> {
                                event.localState is QuickActionDragPayload ||
                                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                            }
                            DragEvent.ACTION_DRAG_ENTERED -> {
                                applyTopSlotDragHover(v, hovered = true, empty = true)
                                AppHaptics.perform(v, HapticLevel.LIGHT_TICK)
                                true
                            }
                            DragEvent.ACTION_DRAG_EXITED -> {
                                applyTopSlotDragHover(v, hovered = false, empty = true)
                                true
                            }
                            DragEvent.ACTION_DROP -> {
                                applyTopSlotDragHover(v, hovered = false, empty = true)
                                val payload = extractDraggedQuickAction(event) ?: return@setOnDragListener false
                                applyQuickActionDropOnTopSlot(slotIdx, payload, v)
                                true
                            }
                            DragEvent.ACTION_DRAG_ENDED -> {
                                applyTopSlotDragHover(v, hovered = false, empty = true)
                                (event.localState as? QuickActionDragPayload)?.sourceViewRef?.get()?.alpha = 1f
                                true
                            }
                            else -> true
                        }
                    }
                }
                row.addView(emptySlot, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }
        if (!quickDeckEditMode) {
            updateQuickActionIconStates()
        }
    }

    private fun triggerQuickAction(action: QuickActionId) {
        AppHaptics.perform(quickActionRow ?: quickDeckGridHost, HapticLevel.MEDIUM_CONFIRM)
        when (action) {
            QuickActionId.LOCK -> {
                val currentlyLocked = activeTelemetry?.doorLocked ?: (carVisual?.vehicleLocked ?: true)
                if (currentlyLocked) {
                    carVisual?.triggerActionFeedback("unlock")
                    dispatchSmartVehicleCommand("unlock", "车门解锁")
                } else {
                    carVisual?.triggerActionFeedback("lock")
                    dispatchSmartVehicleCommand("lock", "车门锁止")
                }
            }
            QuickActionId.CLIMATE -> {
                val airExtra = mapOf("airType" to uiPrefs.getString(KEY_AIR_TYPE, "1").orEmpty().ifBlank { "1" })
                if (activeTelemetry?.acOn == true) {
                    carVisual?.triggerActionFeedback("ac_off")
                    dispatchSmartVehicleCommand("ac_off", "关闭空调", extraParams = airExtra)
                } else {
                    carVisual?.climateTemp = climateTemperatureCelsius
                    carVisual?.triggerActionFeedback("ac_on")
                    dispatchSmartVehicleCommand(
                        "ac_on",
                        "开启空调",
                        temperatureCelsius = climateTemperatureCelsius,
                        extraParams = airExtra
                    )
                }
            }
            QuickActionId.FLASH -> {
                carVisual?.triggerActionFeedback("find_car")
                dispatchSmartVehicleCommand("find_car", "闪灯鸣笛寻车")
            }
            QuickActionId.TRUNK -> {
                if (activeTelemetry?.trunkOpen == true) {
                    carVisual?.triggerActionFeedback("tailgate_close")
                    dispatchSmartVehicleCommand("tailgate_close", "关闭后备箱")
                } else {
                    carVisual?.triggerActionFeedback("trunk_open")
                    dispatchSmartVehicleCommand("trunk_open", "开启后备箱")
                }
            }
            QuickActionId.ENGINE -> {
                if (activeTelemetry?.engineRunning == true) {
                    carVisual?.triggerActionFeedback("engine_stop")
                    dispatchSmartVehicleCommand("engine_stop", "远程熄火")
                } else {
                    carVisual?.triggerActionFeedback("engine_start")
                    dispatchSmartVehicleCommand("engine_start", "远程启动引擎")
                }
            }
            QuickActionId.WINDOW -> {
                if (areWindowsOpen(activeTelemetry)) {
                    carVisual?.triggerActionFeedback("window_close")
                    dispatchSmartVehicleCommand("window_close", "关闭车窗")
                } else {
                    carVisual?.triggerActionFeedback("window_open")
                    dispatchSmartVehicleCommand("window_open", "打开车窗")
                }
            }
            QuickActionId.WINDOW_VENT -> {
                carVisual?.windowsMode = "vent"
                carVisual?.triggerActionFeedback("window_ventilate")
                dispatchSmartVehicleCommand("window_ventilate", "车窗透气")
            }
            QuickActionId.SUNROOF -> {
                val sunExtra = sunroofExtraParams()
                if (isSkylightOpen(effectiveSkylightState(activeTelemetry))) {
                    carVisual?.triggerActionFeedback("skylight_close")
                    dispatchSmartVehicleCommand("skylight_close", "关闭天窗", extraParams = sunExtra)
                } else {
                    carVisual?.triggerActionFeedback("skylight_open")
                    dispatchSmartVehicleCommand("skylight_open", "打开天窗", extraParams = sunExtra)
                }
            }
            QuickActionId.SUNROOF_TILT -> {
                carVisual?.skylightMode = "tilt"
                carVisual?.triggerActionFeedback("skylight_tilt")
                dispatchSmartVehicleCommand("skylight_tilt", "天窗翘起", extraParams = sunroofExtraParams())
            }
        }
    }

    private fun updateQuickActionIconStates() {
        if (quickDeckEditMode) return
        val telemetry = activeTelemetry
        val effectiveLocked = telemetry?.doorLocked ?: (carVisual?.vehicleLocked ?: true)
        val acActive = telemetry?.acOn == true
        val trunkIsOpen = telemetry?.trunkOpen == true
        val engineRunningNow = telemetry?.engineRunning == true
        val windowsOpenNow = areWindowsOpen(telemetry)
        val sunroofOpenNow = isSkylightOpen(effectiveSkylightState(telemetry))
        val sunroofTiltedNow = isSkylightTilted(effectiveSkylightState(telemetry))

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

        // Row 1b: 充电与预约充电 (strictly visible ONLY for EV & PHEV vehicles)
        val chargeRow = createMenuRowWithRefs(
            icon = AppIconType.BOLT_FLASH,
            title = "充电",
            initialSubtitle = "未插充电枪 · 预约充电",
            withStatusDot = false
        ) {
            featureSheets.showChargingScheduleSheet(
                profile = profileStore.getActiveProfile(),
                telemetry = activeTelemetry,
                powertrain = activePowertrain(),
                resolvePin = { desc, cb -> resolveVehiclePinForAction(desc, cb) }
            )
        }
        chargingMenuRow = chargeRow.first.apply {
            visibility = if (activePowertrain().hasElectricDrive) View.VISIBLE else View.GONE
        }
        chargingRowSubtitle = chargeRow.second
        list.addView(chargeRow.first)

        // Row 2: 地点 (全屏实时位置与历史行程子页；街道信息未获取前不展示占位文案)
        val locRow = createMenuRowWithRefs(
            icon = AppIconType.LOCATION_ARROW,
            title = "地点",
            initialSubtitle = "",
            withStatusDot = false
        ) { showLocationSubpageScreen() }
        locationRowSubtitle = locRow.second?.apply { visibility = View.GONE }
        list.addView(locRow.first)

        // Row 3: 安全性 (手机钥匙实时状态 + 车控密码开关 / 修改密码 / 防盗体检 / 电子围栏)
        val securityRow = createMenuRowWithRefs(
            icon = AppIconType.SHIELD_CHECK,
            title = "安全性",
            initialSubtitle = "手机钥匙未连接 · 远程网络控制",
            withStatusDot = true
        ) { showSecuritySubpageScreen() }
        securityRowSubtitle = securityRow.second
        securityDotView = securityRow.third
        list.addView(securityRow.first)

        // Row 4: 驾驶员 (直达远程授权管理)
        list.addView(
            createMenuRow(
                icon = AppIconType.KEY_ROUND,
                title = "驾驶员",
                subtitle = null,
                withStatusDot = false
            ) { showDriversSubpageScreen() }
        )

        // Row 5: 车辆信息 (基础车况信息 / 车辆版本)
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
            setPadding(dp(22), dp(22), dp(22), dp(12))
        }

        // Unified centered footer metric: defaults to license plate; single-tap cycles vehicle info
        plateBadgeView = null
        homeFooterView = text("", 12.5f, FOOTER_TEXT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.4f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.96f, haptic = false)
            setOnClickListener { handleHomeFooterTap() }
        }
        footerBox.addView(homeFooterView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        page.addView(footerBox, lp())
    }

    private fun formatHomeFooterMetric(telemetry: VehicleTelemetry?, index: Int): String {
        val powertrain = activePowertrain(telemetry)
        return when (index % 4) {
            0 -> {
                val plateNo = firstNonBlank(
                    telemetry?.licensePlate.orEmpty(),
                    uiPrefs.getString(KEY_PLATE_NO, "").orEmpty()
                )
                if (plateNo.isNotBlank()) plateNo else "未设置车牌"
            }
            1 -> {
                val odo = telemetry?.odometerKm?.takeIf { it > 0.0 }
                if (odo != null) "总里程 ${String.format(Locale.US, "%,d", odo.roundToInt())} km" else "总里程 -- km"
            }
            2 -> {
                val range = telemetry?.remainingRangeKm?.takeIf { it > 0.0 }
                val evRange = telemetry?.electricRangeKm
                when (powertrain) {
                    VehiclePowertrain.EV -> {
                        val r = evRange ?: range
                        if (r != null) "剩余续航 ${r.roundToInt()} km" else "剩余续航 -- km"
                    }
                    VehiclePowertrain.PHEV -> when {
                        evRange != null && range != null ->
                            "纯电 ${evRange.roundToInt()} km · 综合 ${range.roundToInt()} km"
                        range != null -> "综合续航 ${range.roundToInt()} km"
                        evRange != null -> "纯电续航 ${evRange.roundToInt()} km"
                        else -> "剩余续航 -- km"
                    }
                    VehiclePowertrain.ICE ->
                        if (range != null) "剩余续航 ${range.roundToInt()} km" else "剩余续航 -- km"
                }
            }
            else -> when (powertrain) {
                VehiclePowertrain.EV -> {
                    val bat = telemetry?.batteryPercent
                    val avgElec = telemetry?.averageElectricity?.trim()?.takeIf { it.isNotBlank() && it != "0" && it != "255" }
                    val base = if (bat != null) "剩余电量 ${bat.roundToInt()}%" else "剩余电量 --%"
                    if (avgElec != null) "$base · 电耗 ${avgElec} kWh" else base
                }
                VehiclePowertrain.PHEV -> {
                    val bat = telemetry?.batteryPercent
                    val fuel = telemetry?.fuelPercent
                    val parts = mutableListOf<String>()
                    if (bat != null) parts += "电量 ${bat.roundToInt()}%"
                    if (fuel != null) parts += "油量 ${fuel.roundToInt()}%"
                    if (parts.isEmpty()) "油电状态 --%" else parts.joinToString(" · ")
                }
                VehiclePowertrain.ICE -> {
                    val fuel = telemetry?.fuelPercent
                    val avgFuel = telemetry?.averageFuel?.trim()?.takeIf { it.isNotBlank() && it != "0" && it != "255" }
                    val base = if (fuel != null) "剩余油量 ${fuel.roundToInt()}%" else "剩余油量 --%"
                    if (avgFuel != null) "$base · 油耗 ${avgFuel} L" else base
                }
            }
        }
    }

    private fun handleHomeFooterTap() {
        AppHaptics.perform(homeFooterView, HapticLevel.LIGHT_TICK)
        footerMetricIndex = (footerMetricIndex + 1) % 4
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
                        .alpha(1f)
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
        val signalDesc = bleController.lastProximityRssi?.let { rssi ->
            when {
                rssi >= -65 -> "（信号强）"
                rssi >= -80 -> "（信号良好）"
                else -> "（信号较弱）"
            }
        }.orEmpty()
        val bleChannelText = when {
            isBleReady -> "蓝牙已连接$signalDesc"
            bleNearby || bleController.isVehicleNearby -> "附近已发现车辆"
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
                "蓝牙连接状态" to bleChannelText,
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

        val powertrain = activePowertrain(telemetry)
        val energy = when (powertrain) {
            VehiclePowertrain.EV -> telemetry?.batteryPercent ?: telemetry?.fuelPercent
            VehiclePowertrain.PHEV -> telemetry?.batteryPercent ?: telemetry?.fuelPercent
            VehiclePowertrain.ICE -> telemetry?.fuelPercent ?: telemetry?.batteryPercent
        }
        batteryPillView?.apply {
            levelPercent = energy?.roundToInt() ?: 0
            isChargingOrActive = (powertrain.hasElectricDrive && telemetry?.isCharging == true) ||
                telemetry?.engineRunning == true
        }
        energyTextView?.text = formatEnergyDisplay(telemetry)

        chargingMenuRow?.visibility = if (powertrain.hasElectricDrive) View.VISIBLE else View.GONE
        if (powertrain.hasElectricDrive) {
            val chargeStateText = when {
                telemetry?.isCharging == true -> {
                    val rem = formatRemainingChargeLabel(telemetry.remainingCharge)
                    if (rem != null) "充电中 · 剩余 $rem" else "充电中"
                }
                telemetry?.isChargingGunConnected == true -> "已插充电枪"
                else -> "未插充电枪"
            }
            val apptText = if (telemetry?.chargeAppintStatus == "1") "预约充电已开启" else "预约充电"
            chargingRowSubtitle?.text = "$chargeStateText · $apptText"
        }

        val locked = telemetry?.doorLocked
        val primaryMotionState = when {
            powertrain.hasElectricDrive && telemetry?.isCharging == true -> "充电中"
            telemetry?.engineRunning == true -> if (powertrain == VehiclePowertrain.EV) "已上电" else "已启动"
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

        val skylightRaw = effectiveSkylightState(telemetry)
        val skylightIsOpen = isSkylightOpen(skylightRaw)
        val skylightIsTilted = isSkylightTilted(skylightRaw)
        val resolvedSkylightMode = when {
            skylightIsTilted -> "tilt"
            skylightIsOpen -> "open"
            else -> "closed"
        }
        val resolvedWindowsMode = when {
            areWindowsOpen(telemetry) -> "open"
            else -> "closed"
        }

        carVisual?.apply {
            modelKey = modelName.ifBlank { "arrizo8" }
            vehicleLocked = effectiveLocked
            climateOn = acActive
            climateTemp = climateTemperatureCelsius
            trunkOpen = trunkIsOpen
            skylightOpen = skylightIsOpen
            skylightMode = resolvedSkylightMode
            windowsMode = resolvedWindowsMode
            engineRunning = telemetry?.engineRunning == true
        }

        updateQuickActionIconStates()
        updateTpmsBadges(telemetry)
        if (quickDeckExpanded && !quickDeckEditMode) {
            updateQuickDeckTileStates()
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

        val locSub = formatLocationSubtitle(telemetry)
        locationRowSubtitle?.apply {
            text = locSub
            visibility = if (locSub.isNotBlank()) View.VISIBLE else View.GONE
        }
        updateHomeFooter(telemetry)
        updateSecurityRow()
        CarWidgetManager.syncFromTelemetry(
            context = this,
            modelName = modelName,
            plateNo = plateNo,
            telemetry = telemetry,
            cabinTempC = cabinTemp,
            primaryStatus = primaryMotionState,
            modelKey = VehicleModelRepository.resolveModelKey(modelName)
        )
        consumePendingShortcutActionIfReady()
        consumePendingSharedPoiIfReady()
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
                securityRowSubtitle?.text = "手机钥匙已连接 · 蓝牙直连"
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
                securityRowSubtitle?.text = "手机钥匙未连接 · 远程网络控制"
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
            val address = if (key == lastGeocodedKey && !lastGeocodedAddress.isNullOrBlank()) {
                lastGeocodedAddress!!
            } else {
                val backoffOk = SystemClock.elapsedRealtime() - lastGeocodeFailedAtMs >= 30_000L
                if (key != lastGeocodedKey && backoffOk) {
                    triggerHomeReverseGeocode(lat, lon, key)
                }
                null
            }
            val distance = formatDistanceToVehicle(lat, lon)
            return listOfNotNull(address, distance).joinToString(" · ")
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
            if (isActive && key == lastGeocodedKey) {
                if (!addr.isNullOrBlank()) {
                    lastGeocodedAddress = addr
                    lastGeocodeFailedAtMs = 0L
                } else {
                    // Allow retry after 30s backoff instead of sticking blank forever.
                    lastGeocodedKey = ""
                    lastGeocodeFailedAtMs = SystemClock.elapsedRealtime()
                }
                lastGeocodeSettled = true
                locationAddressObservers.toList().forEach { observer ->
                    observer(currentStreetLabel())
                }
                if (currentScreen == Screen.HOME) {
                    val updatedLocSub = formatLocationSubtitle(activeTelemetry)
                    locationRowSubtitle?.apply {
                        text = updatedLocSub
                        visibility = if (updatedLocSub.isNotBlank()) View.VISIBLE else View.GONE
                    }
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

    @android.annotation.SuppressLint("MissingPermission")
    private fun getBestDeviceGcj02Location(): Pair<Double, Double>? {
        if (!hasDeviceLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        var best: Location? = latestLiveDeviceLocation?.takeIf { isUsableDeviceLocation(it) }
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (!isUsableDeviceLocation(loc)) continue
            val currentBest = best
            if (currentBest == null) {
                best = loc
                continue
            }
            val locAge = locationAgeMs(loc)
            val bestAge = locationAgeMs(currentBest)
            val ageDiff = bestAge - locAge // positive => loc is fresher
            val significantlyMoreAccurate = loc.hasAccuracy() && currentBest.hasAccuracy() &&
                loc.accuracy + 30f < currentBest.accuracy
            if (ageDiff > 60_000L || (kotlin.math.abs(ageDiff) <= 60_000L && significantlyMoreAccurate)) {
                best = loc
            } else if (ageDiff > 0L && (!loc.hasAccuracy() || !currentBest.hasAccuracy() || loc.accuracy <= currentBest.accuracy * 2f)) {
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
        val a = (sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
            sin(dLon / 2.0) * sin(dLon / 2.0)).coerceIn(0.0, 1.0)
        return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }

    // =========================================================================
    // Bottom Sheets for the 4 Menu Rows & Top-Right Account Menu
    // =========================================================================

    private fun showQuickActionsCustomizeSheet() {
        val draft = loadQuickActions().toMutableList()
        showBottomSheet("自定义快捷控制") { sheet, dialog ->
            sheet.addView(text("支持 1 至 4 项首页快捷控制，点击上方已选图标可移除", 12.5f, TEXT_SECONDARY).apply {
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
                countLabel.text = "已选 ${draft.size}/$MAX_QUICK_ACTIONS"
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
                allActions.chunked(MAX_QUICK_ACTIONS).forEachIndexed { rowIdx, chunk ->
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
                                    if (draft.size >= MAX_QUICK_ACTIONS) {
                                        Toast.makeText(this@MainActivity, "最多选择 4 项快捷控制，请先移除一项", Toast.LENGTH_SHORT).show()
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
                    for (pad in 0 until (MAX_QUICK_ACTIONS - chunk.size)) {
                        rowView.addView(View(this), weightedLp(dp(8), dp(76)))
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
                DropdownOption(
                    id = item.vin,
                    title = item.displayName,
                    subtitle = item.plateNo.trim().ifBlank { "未设置车牌" },
                    selected = item.vin.equals(activeVin, ignoreCase = true)
                )
            } + DropdownOption(
                id = "__MANAGE_VEHICLES__",
                title = "车辆管理",
                subtitle = "",
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
                val initialSignature = initialItems.map { Triple(it.vin, it.displayName, it.plateNo.trim()) }
                val mergedSignature = mergedList.map { Triple(it.vin, it.displayName, it.plateNo.trim()) }
                if (mergedSignature != initialSignature) {
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
                    val sub = item.plateNo.trim().ifBlank { "未设置车牌" }
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
            controlPin = existing?.controlPin.orEmpty()
        )

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                profileStore.saveProfile(updatedProfile, activate = true)
            }
            if (cloud != null) {
                persistVehicleMetadata(cloud)
            } else {
                clearVehicleMetadata()
                uiPrefs.edit()
                    .putString(KEY_MODEL_NAME, item.displayName)
                    .putString(KEY_PLATE_NO, item.plateNo)
                    .apply()
            }

            currentProfile = saved
            sessionVehiclePin = saved.controlPin
            activeTelemetry = null
            provisioningJob?.cancel()
            provisioningActive = false
            telemetryJob?.cancel()
            postActionRefreshJob?.cancel()
            geocodeJob?.cancel()
            lastGeocodedKey = ""
            lastGeocodedAddress = null
            lastGeocodeFailedAtMs = 0L
            bleController.resetTargetAndDisconnect()
            bleConnected = false
            bleNearby = false
            lastPairingPin = ""

            val resolvedName = cloud?.let { vehicleModelName(it) }
                ?.takeIf { it.isNotBlank() }
                ?: item.displayName.ifBlank { "我的座驾" }
            val resolvedPlate = cloud?.plateNo?.trim().orEmpty().ifBlank { item.plateNo.trim() }
            val resolvedModelKey = VehicleModelRepository.resolveModelKey(resolvedName)
            CarWidgetManager.syncActiveVehicle(
                context = this@MainActivity,
                vin = saved.vin,
                modelName = resolvedName,
                plateNo = resolvedPlate,
                modelKey = resolvedModelKey,
                engineType = cloud?.engineType?.trim().orEmpty(),
                telemetry = null
            )

            carVisual?.modelKey = resolvedName.ifBlank { "arrizo8" }
            dialog?.dismiss()
            homeHeaderView?.let { hdr ->
                MotionSpec.animatePanelSwitch(hdr, direction = 0)
            }
            refreshHomeHeaderAndRows()
            startProximityWhenPermitted()
            syncCloudVehicleMeta()
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
        val skylightRaw = effectiveSkylightState(telemetry)
        val skylightOpenNow = isSkylightOpen(skylightRaw)
        val skylightTiltNow = isSkylightTilted(skylightRaw)
        val engineRunningNow = telemetry?.engineRunning == true

        showBottomSheet("控制") { sheet, dialog ->
            val statusSummary = listOf(
                "门锁 ${if (locked) "已锁" else "已解锁"}",
                "四门 ${if (doorsOpenNow) "未关" else "已关"}",
                "后备箱 ${if (trunkOpenNow) "已开" else "已关"}",
                "天窗 ${formatSkylightLabel(skylightRaw)}",
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
            val sunExtra = sunroofExtraParams()
            sheet.addView(controlTileRow(
                ControlTileSpec(AppIconType.SUNROOF_OPEN, "打开天窗", active = skylightOpenNow && !skylightTiltNow) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("skylight_open", "打开天窗", extraParams = sunExtra)
                },
                ControlTileSpec(AppIconType.SUNROOF_TILT, "天窗翘起", active = skylightTiltNow) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("skylight_tilt", "天窗翘起", extraParams = sunExtra)
                },
                ControlTileSpec(AppIconType.SUNROOF_CLOSE, "关闭天窗", active = !skylightOpenNow && telemetry != null) {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("skylight_close", "关闭天窗", extraParams = sunExtra)
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
        val t = activeTelemetry
        val cabin = cabinTemperature(t)
        val acActive = t?.acOn == true
        val minTemp = uiPrefs.getString(KEY_TEMP_LO, "").orEmpty().toDoubleOrNull()?.coerceIn(14.0, 20.0) ?: 16.0
        val maxTemp = uiPrefs.getString(KEY_TEMP_HI, "").orEmpty().toDoubleOrNull()?.coerceIn(26.0, 34.0) ?: 32.0
        climateTemperatureCelsius = climateTemperatureCelsius.coerceIn(minTemp, maxTemp)

        var selectedDuration = 10
        val initialFrontDefrost = t?.fWinHeatingState == "1"
        val initialRearDefrost = t?.rWinHeatingState == "1"
        val initialAirPm = t?.airPurification == "1"
        val initialDriverHeat = t?.dSeatHeatingState == "1" || t?.dSeatHeatingState == "2"
        val initialPassHeat = t?.pSeatHeatingState == "1" || t?.pSeatHeatingState == "2"
        val initialDriverVent = !initialDriverHeat && t?.dSeatVentilateState == "1"
        val initialPassVent = !initialPassHeat && t?.pSeatVentilateState == "1"
        var frontDefrost = initialFrontDefrost
        var rearDefrost = initialRearDefrost
        var airPm = initialAirPm
        var driverHeat = initialDriverHeat
        var passHeat = initialPassHeat
        var driverVent = initialDriverVent
        var passVent = initialPassVent
        val airTypeInt = uiPrefs.getString(KEY_AIR_TYPE, "1").orEmpty().toIntOrNull()?.coerceIn(0, 1) ?: 1

        showBottomSheet("空调") { sheet, dialog ->
            // Airy, borderless Hero Temperature Stage directly on sheet canvas
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            statusRow.addView(
                StatusDotView(this).apply {
                    state = if (acActive) StatusDotView.State.CONNECTED else StatusDotView.State.IDLE
                },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
            )
            val sub = buildString {
                append(if (acActive) "运行中" else "已关闭")
                if (cabin != null) append(" · 车内 ${cabin.roundToInt()}°C")
            }
            statusRow.addView(text(sub, 12.5f, ThemeTokens.TEXT_SUB))
            sheet.addView(statusRow, lp(top = dp(6), bottom = dp(16)))

            fun climateStepButton(symbol: String, onTap: () -> Unit): TextView =
                text(symbol, 20f, TEXT_PRIMARY, Typeface.NORMAL).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = rounded(
                        fillColor = Color.argb(30, 255, 255, 255),
                        radiusDp = 21f,
                        strokeColor = Color.argb(38, 255, 255, 255),
                        strokeWidthDp = 0.8f
                    )
                    isClickable = true
                    isFocusable = true
                    MotionSpec.attachPressPhysics(this, pressedScale = 0.90f)
                    setOnClickListener { onTap() }
                }

            val stepper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val minus = climateStepButton("−") {
                AppHaptics.perform(sheetClimateTempView, HapticLevel.LIGHT_TICK)
                climateTemperatureCelsius = (climateTemperatureCelsius - 0.5).coerceAtLeast(minTemp)
                sheetClimateTempView?.text = formatTemp(climateTemperatureCelsius)
            }
            sheetClimateTempView = text(formatTemp(climateTemperatureCelsius), 40f, TEXT_PRIMARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                fontFeatureSettings = "tnum"
                minWidth = dp(160)
                setPadding(dp(14), 0, dp(14), 0)
            }
            val plus = climateStepButton("+") {
                AppHaptics.perform(sheetClimateTempView, HapticLevel.LIGHT_TICK)
                climateTemperatureCelsius = (climateTemperatureCelsius + 0.5).coerceAtMost(maxTemp)
                sheetClimateTempView?.text = formatTemp(climateTemperatureCelsius)
            }
            stepper.addView(minus, LinearLayout.LayoutParams(dp(42), dp(42)))
            stepper.addView(sheetClimateTempView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            stepper.addView(plus, LinearLayout.LayoutParams(dp(42), dp(42)))
            sheet.addView(stepper, lp(bottom = dp(18)))

            // Slim, low-profile inline runtime selector (5 / 10 / 15 min)
            val durations = listOf(5, 10, 15)
            val durSegBar = MotionSpec.createSlidingSegmentedBar(
                context = this,
                labels = durations.map { "${it} 分钟" },
                initial = durations.indexOf(selectedDuration).coerceAtLeast(0),
                bgColor = Color.argb(24, 255, 255, 255),
                pillColor = Color.argb(48, 255, 255, 255),
                activeTextColor = ThemeTokens.TEXT_PRIMARY,
                inactiveTextColor = ThemeTokens.TEXT_SUB,
                trackStrokeColor = Color.TRANSPARENT,
                pillStrokeColor = Color.argb(34, 255, 255, 255)
            ) { idx ->
                selectedDuration = durations.getOrElse(idx) { 10 }
            }
            // Trim tab vertical padding slightly for a sleek ~36dp bar without clipping row/pill height
            (durSegBar.getChildAt(1) as? LinearLayout)?.let { tabRow ->
                for (i in 0 until tabRow.childCount) {
                    (tabRow.getChildAt(i) as? TextView)?.setPadding(dp(6), dp(6), dp(6), dp(6))
                }
            }
            sheet.addView(durSegBar, lp(bottom = dp(18)))

            // Smart primary action: single full-width "开启空调" when OFF; "更新设定" + "关闭空调" when ON
            val startBtn = primaryActionButton(if (acActive) "更新设定" else "开启空调") {
                val extra = buildMap<String, String> {
                    put("airType", airTypeInt.toString())
                    put("controlTime", selectedDuration.toString())
                    if (airPm) { put("airPM", "1") } else if (initialAirPm) { put("airPM", "0") }
                    if (frontDefrost) { put("frontDefrost", "1") } else if (initialFrontDefrost) { put("frontDefrost", "0") }
                    if (rearDefrost) { put("rearDefrost", "1") } else if (initialRearDefrost) { put("rearDefrost", "0") }
                    if (driverHeat) { put("driverHeating", "1") } else if (initialDriverHeat) { put("driverHeating", "0") }
                    if (passHeat) { put("passengerHeating", "1") } else if (initialPassHeat) { put("passengerHeating", "0") }
                    if (driverVent) { put("driverVent", "1") } else if (initialDriverVent) { put("driverVent", "0") }
                    if (passVent) { put("passengerVent", "1") } else if (initialPassVent) { put("passengerVent", "0") }
                }
                dialog.dismiss()
                dispatchSmartVehicleCommand(
                    "ac_on",
                    "开启空调 (${formatTemp(climateTemperatureCelsius)})",
                    temperatureCelsius = climateTemperatureCelsius,
                    extraParams = extra
                )
            }
            if (acActive) {
                val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val offBtn = secondaryActionButton("关闭空调") {
                    dialog.dismiss()
                    dispatchSmartVehicleCommand("ac_off", "关闭空调", extraParams = mapOf("airType" to airTypeInt.toString()))
                }
                actionRow.addView(startBtn, LinearLayout.LayoutParams(0, dp(46), 1.2f))
                actionRow.addView(offBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
                sheet.addView(actionRow, lp(bottom = dp(16)))
            } else {
                sheet.addView(startBtn, lp(height = dp(46), bottom = dp(16)))
            }

            // Subtle hairline divider before clean collapsible comfort & schedule rows
            sheet.addView(View(this).apply {
                setBackgroundColor(ThemeTokens.DIVIDER)
            }, LinearLayout.LayoutParams(MATCH_PARENT, dpF(0.6f).roundToInt().coerceAtLeast(1)))

            // Collapsible "舒适选项" row (collapsed by default to keep sheet calm & uncluttered)
            var comfortExpanded = false
            val comfortSummaryTv = text("", 12.5f, ThemeTokens.TEXT_SUB).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            fun updateComfortSummary() {
                val activeLabels = buildList {
                    if (frontDefrost) add("除霜")
                    if (rearDefrost) add("后挡加热")
                    if (airPm) add("净化")
                    if (driverHeat || passHeat) add("座椅加热")
                    if (driverVent || passVent) add("座椅通风")
                }
                comfortSummaryTv.text = when {
                    activeLabels.isEmpty() -> "未选"
                    activeLabels.size <= 2 -> activeLabels.joinToString(" · ")
                    else -> "已选 ${activeLabels.size} 项"
                }
                comfortSummaryTv.setTextColor(if (activeLabels.isNotEmpty()) ACCENT_BLUE else ThemeTokens.TEXT_SUB)
            }
            updateComfortSummary()

            val comfortChevron = AppIconView(this, AppIconType.CHEVRON_DOWN, Color.parseColor("#6B707D"))
            val comfortPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(2), 0, dp(10))
            }

            val comfortHeaderRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                isFocusable = true
                MotionSpec.attachPressPhysics(this, pressedScale = 0.985f, haptic = false)
                setOnClickListener {
                    AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                    comfortExpanded = !comfortExpanded
                    comfortPanel.visibility = if (comfortExpanded) View.VISIBLE else View.GONE
                    comfortChevron.animate()
                        .rotation(if (comfortExpanded) 180f else 0f)
                        .setDuration(200L)
                        .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                        .start()
                }
            }
            comfortHeaderRow.addView(
                AppIconView(this, AppIconType.FAN_BLADE, ThemeTokens.TEXT_SUB),
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
            )
            comfortHeaderRow.addView(
                text("舒适选项", 14.5f, TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            comfortHeaderRow.addView(
                comfortSummaryTv,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) }
            )
            comfortHeaderRow.addView(comfortChevron, LinearLayout.LayoutParams(dp(15), dp(15)))
            sheet.addView(comfortHeaderRow, lp())

            fun styleComfortChip(tv: TextView, active: Boolean) {
                tv.background = rounded(
                    if (active) TILE_ACTIVE_BG else Color.argb(24, 255, 255, 255),
                    10f,
                    if (active) ACCENT_BLUE else Color.argb(30, 255, 255, 255),
                    0.8f
                )
                tv.setTextColor(if (active) TEXT_PRIMARY else TEXT_BODY)
            }

            lateinit var driverHeatChip: TextView
            lateinit var driverVentChip: TextView
            lateinit var passHeatChip: TextView
            lateinit var passVentChip: TextView

            fun makeChip(label: String, initial: Boolean, onTap: (TextView) -> Unit): TextView =
                text(label, 12.5f, TEXT_PRIMARY).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(4), 0)
                    isClickable = true
                    isFocusable = true
                    styleComfortChip(this, initial)
                    MotionSpec.attachPressPhysics(this, pressedScale = 0.96f, haptic = false)
                    setOnClickListener {
                        AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                        onTap(this)
                        updateComfortSummary()
                    }
                }

            val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val frontDefrostChip = makeChip("前挡除霜", frontDefrost) { tv ->
                frontDefrost = !frontDefrost
                styleComfortChip(tv, frontDefrost)
            }
            val rearDefrostChip = makeChip("后挡加热", rearDefrost) { tv ->
                rearDefrost = !rearDefrost
                styleComfortChip(tv, rearDefrost)
            }
            val airPmChip = makeChip("空气净化", airPm) { tv ->
                airPm = !airPm
                styleComfortChip(tv, airPm)
            }
            rowA.addView(frontDefrostChip, LinearLayout.LayoutParams(0, dp(34), 1f))
            rowA.addView(rearDefrostChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(8) })
            rowA.addView(airPmChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(8) })
            comfortPanel.addView(rowA, lp(bottom = dp(8)))

            val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            driverHeatChip = makeChip("主驾加热", driverHeat) { tv ->
                driverHeat = !driverHeat
                if (driverHeat) {
                    driverVent = false
                    styleComfortChip(driverVentChip, false)
                }
                styleComfortChip(tv, driverHeat)
            }
            passHeatChip = makeChip("副驾加热", passHeat) { tv ->
                passHeat = !passHeat
                if (passHeat) {
                    passVent = false
                    styleComfortChip(passVentChip, false)
                }
                styleComfortChip(tv, passHeat)
            }
            driverVentChip = makeChip("主驾通风", driverVent) { tv ->
                driverVent = !driverVent
                if (driverVent) {
                    driverHeat = false
                    styleComfortChip(driverHeatChip, false)
                }
                styleComfortChip(tv, driverVent)
            }
            passVentChip = makeChip("副驾通风", passVent) { tv ->
                passVent = !passVent
                if (passVent) {
                    passHeat = false
                    styleComfortChip(passHeatChip, false)
                }
                styleComfortChip(tv, passVent)
            }
            rowB.addView(driverHeatChip, LinearLayout.LayoutParams(0, dp(34), 1f))
            rowB.addView(passHeatChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(6) })
            rowB.addView(driverVentChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(6) })
            rowB.addView(passVentChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(6) })
            comfortPanel.addView(rowB, lp(bottom = dp(10)))

            val extrasRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(4), dp(2), dp(2))
            }
            val autoAirInitial = uiPrefs.getString(KEY_OPEN_AIR, "0") == "1"
            extrasRow.addView(text("座舱自动换气", 13.5f, TEXT_BODY), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val autoAirSwitch = ToggleSwitch(this).apply {
                setChecked(autoAirInitial, animate = false)
                onToggleAttempt = { target ->
                    val profile = profileStore.getActiveProfile()
                    lifecycleScope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.setAutoAirVentilation(profile.vin, target, profile.accessToken)
                            }
                        }.getOrDefault(false)
                        if (ok) {
                            uiPrefs.edit().putString(KEY_OPEN_AIR, if (target) "1" else "0").apply()
                            setChecked(target, animate = true)
                            Toast.makeText(this@MainActivity, if (target) "已开启座舱自动换气" else "已关闭座舱自动换气", Toast.LENGTH_SHORT).show()
                        } else {
                            setChecked(!target, animate = true)
                            Toast.makeText(this@MainActivity, "当前车型暂不支持或同步失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            extrasRow.addView(autoAirSwitch)
            comfortPanel.addView(extrasRow)
            sheet.addView(comfortPanel, lp())

            // Aligned bottom hairline + "预约出行" row
            sheet.addView(View(this).apply {
                setBackgroundColor(ThemeTokens.DIVIDER)
            }, LinearLayout.LayoutParams(MATCH_PARENT, dpF(0.6f).roundToInt().coerceAtLeast(1)))

            val scheduleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(2))
                isClickable = true
                isFocusable = true
                MotionSpec.attachPressPhysics(this, pressedScale = 0.985f, haptic = false)
                setOnClickListener {
                    dialog.dismiss()
                    featureSheets.showTravelScheduleSheet(
                        profile = profileStore.getActiveProfile(),
                        airType = airTypeInt,
                        resolvePin = { desc, cb -> resolveVehiclePinForAction(desc, cb) }
                    )
                }
            }
            scheduleRow.addView(
                AppIconView(this, AppIconType.CLOCK, ThemeTokens.TEXT_SUB),
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
            )
            scheduleRow.addView(
                text("预约出行", 14.5f, TEXT_PRIMARY),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            scheduleRow.addView(
                AppIconView(this, AppIconType.CHEVRON_RIGHT, Color.parseColor("#6B707D")),
                LinearLayout.LayoutParams(dp(15), dp(15))
            )
            sheet.addView(scheduleRow, lp())
        }
    }

    private fun refreshSecuritySheetIfOpen() {
        securityPassiveRefreshCallback?.invoke()
        if (::passiveEntrySheets.isInitialized) passiveEntrySheets.refreshIfOpen()
    }

    private fun showPinConfigSheet(profile: VehicleProfile) {
        showInputSheet(
            title = "远程控车 PIN 码",
            message = "请输入 6 位数字 PIN 码，仅保存在本机用于远程网络控车校验。",
            hint = "6 位 PIN 码",
            numeric = true,
            confirmText = "保存"
        ) { pin ->
            if (!PIN_REGEX.matches(pin)) {
                Toast.makeText(this, "请输入 6 位数字 PIN 码", Toast.LENGTH_SHORT).show()
                return@showInputSheet
            }
            sessionVehiclePin = pin
            lifecycleScope.launch {
                val updated = withContext(Dispatchers.IO) {
                    profileStore.updateControlPin(profile.vin, pin)
                }
                if (updated != null && profileStore.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                    currentProfile = updated
                }
                CarWidgetManager.updateAllWidgets(this@MainActivity)
            }
            showHomeStatus("PIN 码已保存")
        }
    }

    // =========================================================================
    // Smart Dual-Mode Command Router (BLE Nearby -> HTTP Fallback)
    // =========================================================================

    private fun enqueuePendingBleActionMeta(actionKey: String, meta: PendingBleActionMeta) {
        val queue = pendingBleActionMetaByKey.getOrPut(actionKey) { ArrayDeque() }
        while (queue.size >= 8) {
            queue.removeFirst()
        }
        queue.addLast(meta)
    }

    private fun popLastPendingBleActionMeta(actionKey: String) {
        val queue = pendingBleActionMetaByKey[actionKey] ?: return
        if (queue.isNotEmpty()) queue.removeLast()
        if (queue.isEmpty()) pendingBleActionMetaByKey.remove(actionKey)
    }

    private fun consumePendingBleActionMeta(actionKey: String): PendingBleActionMeta? {
        val queue = pendingBleActionMetaByKey[actionKey] ?: return null
        val meta = queue.removeFirstOrNull()
        if (queue.isEmpty()) pendingBleActionMetaByKey.remove(actionKey)
        return meta
    }

    private fun executeBleActionImmediate(
        actionKey: String,
        label: String,
        temperatureCelsius: Double?
    ): Boolean {
        val activeVin = profileStore.getActiveProfile().vin
        enqueuePendingBleActionMeta(
            actionKey,
            PendingBleActionMeta(label = label, vin = activeVin, isPassiveAuto = false)
        )
        val accepted = if (temperatureCelsius != null) {
            bleController.sendVehicleAction(actionKey, temperatureCelsius, 10)
        } else {
            bleController.sendVehicleAction(actionKey)
        }
        if (accepted) {
            // P2-31: wait for T-Box 0x04 confirmation in onActionResult() before committing optimistic lock/widget/cooldown state.
            showHomeStatus(
                if (bleController.isSessionReady()) {
                    "蓝牙直连指令已下发：$label..."
                } else {
                    "蓝牙钥匙连接中，已排队：$label"
                }
            )
        } else {
            popLastPendingBleActionMeta(actionKey)
        }
        return accepted
    }

    private fun dispatchSmartVehicleCommand(
        actionKey: String,
        label: String,
        temperatureCelsius: Double? = null,
        extraParams: Map<String, String> = emptyMap()
    ) {
        val profile = profileStore.getActiveProfile().also { currentProfile = it }
        if (profile.vin.isBlank()) {
            showHomeStatus("尚未同步绑定车辆")
            return
        }

        val requirePinEveryTime = uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, true)
        val hasAdvancedHttpParams = extraParams.keys.any {
            it in setOf("airPM", "frontDefrost", "rearDefrost", "driverHeating", "passengerHeating", "driverVent", "passengerVent", "controlTime")
        }
        val bleRoute = !hasAdvancedHttpParams && (
            bleController.isSessionReady() ||
                bleController.isVehicleNearby ||
                bleNearby
            )

        if (bleRoute) {
            if (!requirePinEveryTime) {
                if (executeBleActionImmediate(actionKey, label, temperatureCelsius)) {
                    return
                }
            } else {
                // BLE direct control also enforces the 6-digit PIN gate when switch is ON,
                // with offline fallback against the locally cached PIN when BLE session is live.
                dismissAppDialogImmediately()
                lateinit var dialog: android.app.Dialog
                dialog = SixDigitPinSheet.showVerifyPinSheet(
                    context = this,
                    title = "输入 PIN 码",
                    subtitle = "请输入 6 位 PIN 码以执行「$label」"
                ) { pin, showError, dismiss ->
                    lifecycleScope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.IO) {
                                httpClient.verifyVehiclePin(profile.vin, pin, profile.accessToken)
                            }
                        }
                        if (isFinishing || isDestroyed || !dialog.isShowing) return@launch
                        val err = res.exceptionOrNull()
                        if (err is CherySessionExpiredException) {
                            dismiss()
                            return@launch
                        }
                        val cachedLocalPin = profile.controlPin.trim().ifBlank { sessionVehiclePin.trim() }
                        val offlineBleMatch = err is java.io.IOException &&
                            bleController.isSessionReady() &&
                            cachedLocalPin.length == 6 &&
                            pin == cachedLocalPin
                        val verified = res.getOrDefault(false) || offlineBleMatch
                        if (verified) {
                            sessionVehiclePin = pin
                            val latestProfile = if (!offlineBleMatch) {
                                withContext(Dispatchers.IO) {
                                    profileStore.updateControlPin(profile.vin, pin)
                                } ?: profileStore.getActiveProfile()
                            } else {
                                profile
                            }
                            currentProfile = latestProfile
                            if (!offlineBleMatch) {
                                CarWidgetManager.updateAllWidgets(this@MainActivity)
                            }
                            dismiss()
                            if (!executeBleActionImmediate(actionKey, label, temperatureCelsius)) {
                                sendHttpVehicleCommand(latestProfile, actionKey, label, temperatureCelsius, pin, extraParams)
                            }
                        } else if (err is java.io.IOException) {
                            showError(
                                if (bleController.isSessionReady() && cachedLocalPin.length == 6) {
                                    "离线 PIN 码不匹配，请重试"
                                } else {
                                    "当前网络不可用，无法在线校验 PIN 码"
                                }
                            )
                        } else {
                            showError("云端 PIN 码错误，请重试")
                        }
                    }
                }
                trackAppDialog(dialog)
                return
            }
        }

        val cachedPin = profile.controlPin.trim().ifBlank { sessionVehiclePin.trim() }
        if (requirePinEveryTime || cachedPin.length != 6) {
            showVehiclePinSheet(profile, actionKey, label, temperatureCelsius, extraParams)
        } else {
            sessionVehiclePin = cachedPin
            sendHttpVehicleCommand(profile, actionKey, label, temperatureCelsius, cachedPin, extraParams)
        }
    }

    private fun showVehiclePinSheet(
        profile: VehicleProfile,
        actionKey: String,
        label: String,
        temperatureCelsius: Double?,
        extraParams: Map<String, String> = emptyMap()
    ) {
        dismissAppDialogImmediately()
        lateinit var dialog: android.app.Dialog
        dialog = SixDigitPinSheet.showVerifyPinSheet(
            context = this,
            title = "输入 PIN 码",
            subtitle = "请输入 6 位数字 PIN 码以执行「$label」"
        ) { pin, showError, dismiss ->
            val command = remoteCommandFor(actionKey)
            if (command == null) {
                showError("暂不支持该远程控制动作")
                return@showVerifyPinSheet
            }
            val params = buildMap {
                putAll(extraParams)
                if (temperatureCelsius != null) {
                    val formatted = CheryHttpClient.formatAcTemperatureParam(temperatureCelsius)
                    put("temperature", formatted)
                    put("airTemperature", formatted)
                }
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
                            params = params,
                            verifyPinFirst = true
                        )
                    }
                }
                if (isFinishing || isDestroyed || !dialog.isShowing) return@launch
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
                    // Only clear cached PIN if the rejected input matches what was cached.
                    if (sessionVehiclePin == pin || profile.controlPin == pin) {
                        if (profileStore.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                            sessionVehiclePin = ""
                        }
                        if (profile.vin.isNotBlank()) {
                            val cleared = withContext(Dispatchers.IO) {
                                profileStore.updateControlPin(profile.vin, "")
                            }
                            if (cleared != null && profileStore.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                                currentProfile = cleared
                            }
                            CarWidgetManager.updateAllWidgets(this@MainActivity)
                        }
                    }
                    val pinErrMsg = result.resultMessage.ifBlank { "PIN 码错误，请重新输入" }.take(40)
                    showError(pinErrMsg)
                } else if (!result.pinVerified) {
                    // Network or transient server error before service 125 confirmed the PIN: do NOT persist unverified PIN.
                    showError(result.error.orEmpty().ifBlank { "网络异常，PIN 码校验未完成，请重试" }.take(60))
                } else {
                    // Service 125 confirmed this PIN is valid; persist it atomically even if 970 rejected for vehicle state.
                    val isStillActive = profileStore.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)
                    if (isStillActive) sessionVehiclePin = pin
                    val updatedProfile = if (profile.vin.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            profileStore.updateControlPin(profile.vin, pin)
                        } ?: profileStore.getActiveProfile()
                    } else {
                        profile
                    }
                    if (isStillActive) currentProfile = updatedProfile
                    CarWidgetManager.updateAllWidgets(this@MainActivity)
                    dismiss()
                    handleRemoteResult(result, updatedProfile, actionKey, label, temperatureCelsius, pin, extraParams)
                }
            }
        }
        trackAppDialog(dialog)
    }

    private fun sendHttpVehicleCommand(
        profile: VehicleProfile,
        actionKey: String,
        label: String,
        temperatureCelsius: Double?,
        pin: String,
        extraParams: Map<String, String> = emptyMap()
    ) {
        val command = remoteCommandFor(actionKey) ?: run {
            showHomeStatus("暂不支持该远程控制动作")
            return
        }
        val params = buildMap {
            putAll(extraParams)
            if (temperatureCelsius != null) {
                val formatted = CheryHttpClient.formatAcTemperatureParam(temperatureCelsius)
                put("temperature", formatted)
                put("airTemperature", formatted)
            }
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
                handleRemoteResult(result, profile, actionKey, label, temperatureCelsius, pin, extraParams)
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
        usedPin: String,
        extraParams: Map<String, String> = emptyMap()
    ) {
        val isStillActive = profileStore.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)
        if (result.success) {
            if (usedPin.length == 6) {
                if (isStillActive) sessionVehiclePin = usedPin
                if (profile.controlPin != usedPin && profile.vin.isNotBlank()) {
                    lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            profileStore.updateControlPin(profile.vin, usedPin)
                        }
                        if (saved != null && profileStore.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                            currentProfile = saved
                        }
                        CarWidgetManager.updateAllWidgets(this@MainActivity)
                    }
                }
            }
            if (isStillActive) {
                if (actionKey == "lock") {
                    recordManualLockOrUnlockForPassive(profile.vin, locked = true)
                } else if (actionKey == "unlock") {
                    recordManualLockOrUnlockForPassive(profile.vin, locked = false)
                }
                applyOptimisticAction(actionKey, extraParams)
                showHomeStatus("已完成：$label")
                scheduleTelemetryRefresh()
            }
        } else if (result.isPinError) {
            if (isStillActive) sessionVehiclePin = ""
            if (profile.vin.isNotBlank()) {
                lifecycleScope.launch {
                    val cleared = withContext(Dispatchers.IO) {
                        profileStore.updateControlPin(profile.vin, "")
                    } ?: profileStore.getActiveProfile()
                    if (profileStore.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                        currentProfile = cleared
                        CarWidgetManager.updateAllWidgets(this@MainActivity)
                        showHomeStatus("本机 PIN 码已失效，请重新验证")
                        showVehiclePinSheet(cleared, actionKey, label, temperatureCelsius, extraParams)
                    }
                }
            } else if (isStillActive) {
                showHomeStatus("本机 PIN 码已失效，请重新验证")
                showVehiclePinSheet(profile.copy(controlPin = ""), actionKey, label, temperatureCelsius, extraParams)
            }
        } else {
            AppHaptics.perform(contentFrame, HapticLevel.ERROR_REJECT)
            val msg = result.error ?: result.resultMessage.ifBlank { "云端未受理指令" }
            showHomeStatus("控制失败：${msg.take(90)}")
        }
    }

    private fun applyOptimisticAction(
        actionKey: String,
        extraParams: Map<String, String> = emptyMap()
    ) {
        // Cancel any pre-action telemetry request so its stale response cannot revert optimistic UI.
        telemetryJob?.cancel()
        val current = activeTelemetry
        if (current != null) {
            activeTelemetry = when (actionKey) {
                "lock" -> current.copy(doorLocked = true, doorLockState = "0", doorLock = "0")
                "unlock" -> current.copy(doorLocked = false, doorLockState = "1", doorLock = "1")
                "ac_on" -> current.copy(
                    acOn = true,
                    acState = "1",
                    airState = "1",
                    airPurification = extraParams["airPM"] ?: current.airPurification,
                    fWinHeatingState = extraParams["frontDefrost"] ?: current.fWinHeatingState,
                    rWinHeatingState = extraParams["rearDefrost"] ?: current.rWinHeatingState,
                    dSeatHeatingState = extraParams["driverHeating"] ?: current.dSeatHeatingState,
                    pSeatHeatingState = extraParams["passengerHeating"] ?: current.pSeatHeatingState,
                    dSeatVentilateState = extraParams["driverVent"] ?: current.dSeatVentilateState,
                    pSeatVentilateState = extraParams["passengerVent"] ?: current.pSeatVentilateState
                )
                "ac_off" -> current.copy(
                    acOn = false,
                    acState = "0",
                    airState = "0",
                    airPurification = "0",
                    fWinHeatingState = "0",
                    rWinHeatingState = "0",
                    dSeatHeatingState = "0",
                    pSeatHeatingState = "0",
                    dSeatVentilateState = "0",
                    pSeatVentilateState = "0"
                )
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

    private fun refreshTelemetry(silent: Boolean, forceRestart: Boolean = false) {
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank() || profile.accessToken.isBlank()) return
        if (forceRestart) {
            telemetryJob?.cancel()
        } else if (telemetryJob?.isActive == true) {
            return
        }
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
            if (!isActive || !profileStore.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                return@launch
            }
            result.onSuccess {
                activeTelemetry = it
                lastTelemetrySyncedElapsedMs = SystemClock.elapsedRealtime()
                syncPassiveEntrySafetyHint(profile.vin, it)
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
        postActionRefreshJob?.cancel()
        postActionRefreshJob = lifecycleScope.launch {
            delay(2_200L)
            refreshTelemetry(silent = true, forceRestart = true)
            telemetryJob?.join()
            delay(4_500L)
            refreshTelemetry(silent = true, forceRestart = true)
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
                        profileStore = null
                    )
                }.getOrNull()
                if (!isActive) return@launch
                if (result?.success == true) {
                    val provisioned = result.profile ?: profile
                    // Guard against writing into a logged-out or switched account.
                    val latestActive = profileStore.getActiveProfile()
                    val stillLoggedIn = latestActive.accessToken.isNotBlank() &&
                        latestActive.vin.isNotBlank()
                    if (!stillLoggedIn) return@launch
                    val stillActive = latestActive.vin.equals(provisioned.vin, ignoreCase = true)
                    val merged = withContext(Dispatchers.IO) {
                        profileStore.updateKeyMaterial(
                            vin = provisioned.vin,
                            bleName = provisioned.bleName,
                            bleMac = provisioned.bleMac,
                            rootKeyHex = provisioned.rootKeyHex,
                            infoHex = provisioned.infoHex,
                            edfHex = provisioned.edfHex,
                            validFrom = provisioned.validFrom,
                            validTo = provisioned.validTo,
                            secAHex = provisioned.secAHex,
                            secBHex = provisioned.secBHex,
                            keyRole = provisioned.keyRole
                        )
                    }
                    if (stillActive && merged != null) {
                        currentProfile = merged
                        sessionVehiclePin = merged.controlPin
                        showHomeStatus("手机数字钥匙已同步")
                        startProximityWhenPermitted()
                    }
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
        clearActiveSessionAndUiState(preserveOfflineKeys = false)
        SettingsScreen.clearCachedAccountProfile(this)
        CarWidgetManager.markLoggedOut(this, expired = false)
        showWelcomeScreen()
    }

    /**
     * Global handler for zyhl resultCode `0406` (session/token expired):
     * debounced across concurrent requests, clears expired session tokens & overlays
     * while preserving offline BLE key material, shows a clear prompt, and redirects to login.
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
            CarWidgetManager.markLoggedOut(this, expired = true)
            return
        }
        handlingSessionExpired = true
        clearActiveSessionAndUiState(preserveOfflineKeys = true)
        CarWidgetManager.markLoggedOut(this, expired = true)
        Toast.makeText(
            this,
            reason.ifBlank { "登录已失效，请重新登录" },
            Toast.LENGTH_LONG
        ).show()
        showLoginFormScreen(startWithSms = false)
        mainHandler.post { handlingSessionExpired = false }
    }

    private fun clearActiveSessionAndUiState(preserveOfflineKeys: Boolean = false) {
        pendingResumeBleRunnable?.let(mainHandler::removeCallbacks)
        pendingResumeBleRunnable = null
        cancelPendingEntrancePreview()
        pendingEntrancePreviewStyle = null
        pendingShortcutRunnable?.let(contentFrame::removeCallbacks)
        pendingShortcutRunnable = null
        pendingShortcutAction = null
        pendingShortcutRequiresPinCache = false
        pendingSharedPoiIntent = null
        deckExpandAnimator?.cancel()
        deckHeightAnimator?.cancel()
        provisioningJob?.cancel()
        provisioningActive = false
        telemetryJob?.cancel()
        postActionRefreshJob?.cancel()
        stopPeriodicHomeRefresh()
        stopForegroundBleRssiPolling()
        stopLiveDeviceLocationUpdates()
        latestLiveDeviceLocation = null
        geocodeJob?.cancel()
        smsCountdownJob?.cancel()
        stopStatusAutoHide()
        PassiveEntryWakeManager.disable(this)
        if (!preserveOfflineKeys) {
            PassiveEntryStore.clearAll(this)
        }
        bleController.resetTargetAndDisconnect()
        httpClient.clearSession()
        if (preserveOfflineKeys) {
            profileStore.clearTokensPreserveOfflineKeys()
        } else {
            profileStore.clearAllProfiles()
        }
        // Keep privacy consent accepted when session expires or user logs out
        clearVehicleMetadata()
        vehicleSwitcherPopup?.dismiss()
        vehicleSwitcherPopup = null
        dismissTopPairingPinDrawer(immediate = true)
        dismissAppDialogImmediately()
        if (::featureSheets.isInitialized) featureSheets.dismissAll()
        if (::managementScreens.isInitialized) managementScreens.dismissAll()
        if (::passiveEntrySheets.isInitialized) passiveEntrySheets.dismiss()
        settingsScreen?.dismissTransientOverlays()
        settingsScreen?.cancelBackgroundJobs()
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
        securityPassiveRefreshCallback = null
        locationOverlayView = null
        messagesOverlayView = null
        settingsScreen = null
        carVisual = null
        currentProfile = null
        activeTelemetry = null
        cloudTripTrackEnabled = null
        lastKnownAntiTheftEnabled = null
        lastGeocodedKey = ""
        lastGeocodedAddress = null
        lastGeocodeSettled = false
        lastGeocodeFailedAtMs = 0L
        locationAddressObservers.clear()
        quickDeckTileViews.clear()
        homeStageFrame = null
        homeLowerSheetView = null
        lowerOverlayContainer = null
        carHeroContainerView = null
        quickDeckClipContainer = null
        quickDeckContainer = null
        quickDeckPullHintStrip = null
        tpmsOverlayContainer = null
        tpmsFlCorner = null
        tpmsFrCorner = null
        tpmsRlCorner = null
        tpmsRrCorner = null
        lastSentExpandedVisibleRatio = 1.0f
        sessionVehiclePin = ""
        bleConnected = false
        bleNearby = false
        lastPairingPin = ""
    }

    private fun activateVehicleFromSummary(
        summary: VehicleSummary,
        fallbackAccount: VehicleProfile = profileStore.getActiveProfile()
    ) {
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
        activateSelectedVehicle(item, fallbackAccount, null)
    }

    private fun handleVehicleUnbound(unboundVin: String) {
        val activeProfile = profileStore.getActiveProfile()
        if (PassiveEntryStore.getActiveEnabledVin(this).equals(unboundVin, ignoreCase = true)) {
            val cfg = PassiveEntryStore.load(this, unboundVin)
            PassiveEntryStore.save(this, cfg.copy(enabled = false))
            PassiveEntryWakeManager.disable(this, activeProfile.bleMac)
        }
        profileStore.deleteProfile(unboundVin)
        if (!activeProfile.vin.equals(unboundVin, ignoreCase = true)) {
            return
        }
        val remaining = profileStore.listProfiles().firstOrNull { it.vin.isNotBlank() }
        provisioningJob?.cancel()
        provisioningActive = false
        telemetryJob?.cancel()
        postActionRefreshJob?.cancel()
        geocodeJob?.cancel()
        lastGeocodedKey = ""
        lastGeocodedAddress = null
        lastGeocodeFailedAtMs = 0L
        bleController.resetTargetAndDisconnect()
        bleConnected = false
        bleNearby = false
        lastPairingPin = ""
        if (remaining != null) {
            val switched = profileStore.setActiveVin(remaining.vin) ?: remaining
            currentProfile = switched
            sessionVehiclePin = switched.controlPin
            clearVehicleMetadata()
            val fallbackName = remaining.bleName.ifBlank { "我的座驾" }
            uiPrefs.edit()
                .putString(KEY_MODEL_NAME, fallbackName)
                .apply()
            activeTelemetry = null
            CarWidgetManager.syncActiveVehicle(
                context = this,
                vin = switched.vin,
                modelName = fallbackName,
                plateNo = "",
                modelKey = VehicleModelRepository.resolveModelKey(fallbackName),
                telemetry = null
            )
            refreshHomeHeaderAndRows()
            startProximityWhenPermitted()
            syncCloudVehicleMeta()
            refreshTelemetry(silent = true)
            return
        }
        sessionVehiclePin = ""
        if (activeProfile.accessToken.isNotBlank()) {
            lifecycleScope.launch {
                val nextCloud = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.fetchVehicleList(activeProfile.accessToken)
                    }
                }.getOrNull()?.firstOrNull { it.vin.isNotBlank() }
                if (nextCloud != null) {
                    activateVehicleFromSummary(nextCloud, fallbackAccount = activeProfile)
                } else {
                    withContext(Dispatchers.IO) {
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
                    clearVehicleMetadata()
                    CarWidgetStateStore.clear(this@MainActivity)
                    CarWidgetManager.updateAllWidgets(this@MainActivity)
                    activeTelemetry = null
                    currentProfile = profileStore.getActiveProfile()
                    clearSubpageOverlayStack()
                    homeRootView = null
                    showHomeScreen()
                }
            }
        } else {
            clearVehicleMetadata()
            CarWidgetStateStore.clear(this)
            CarWidgetManager.updateAllWidgets(this)
            activeTelemetry = null
            currentProfile = profileStore.getActiveProfile()
            clearSubpageOverlayStack()
            homeRootView = null
            showHomeScreen()
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
            showHomeStatus("手机钥匙已连接")
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
        bleController.triggerImmediatePairingOrConnect(
            profileProvider = { profileStore.getActiveProfile() },
            onRequestEnableBluetooth = { requestBluetoothEnable() }
        )
        showHomeStatus(
            if (bleNearby || bleController.isVehicleNearby) {
                "正在连接附近车辆蓝牙..."
            } else {
                "正在高灵敏度扫描并连接车辆蓝牙..."
            }
        )
    }

    private fun startProximityWhenPermitted() {
        if (currentScreen != Screen.HOME && currentScreen != Screen.SECURITY) return
        if (!ensureBlePermissions()) return
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank()) return
        bleController.startAutoProximityWatch(
            profileProvider = { profileStore.getActiveProfile() }
        ) { requestBluetoothEnable() }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun requestBluetoothEnable() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return
        if (adapter.isEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            ensureBlePermissions()
            return
        }
        runCatching {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.onFailure {
            showHomeStatus("请开启系统蓝牙以使用手机钥匙")
        }
    }

    private fun hasAllBlePermissionsGranted(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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
            if (currentScreen == Screen.HOME) {
                startProximityWhenPermitted()
                maybeRequestDeviceLocationPermissionOnce()
            }
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, stateName: String) {
        runOnUiThread {
            if (!connected) {
                handleForegroundBleDisconnected()
            }
        }
    }

    override fun onChannelModeChanged(
        isBleConnected: Boolean,
        rssi: Int?,
        statusText: String
    ) {
        runOnUiThread {
            val wasConnected = bleConnected
            bleConnected = isBleConnected
            bleNearby = !isBleConnected && (
                bleController.isVehicleNearby ||
                    statusText.contains("发现附近车辆") ||
                    statusText.contains("发现本车蓝牙") ||
                    statusText.contains("正在自动切换") ||
                    statusText.contains("蓝牙数字钥匙正在连接") ||
                    statusText.contains("正在握手")
                )
            if (!isBleConnected && wasConnected) {
                handleForegroundBleDisconnected()
            }
            updateSecurityRow()
            if (isBleConnected) {
                lastPairingPin = ""
                dismissTopPairingPinDrawer()
                showHomeStatus("手机钥匙已连接")
            }
            PassiveEntryRuntime.reportForegroundBleRssi(
                rssi = rssi,
                sessionReady = isBleConnected,
                vin = profileStore.getActiveProfile().vin
            )
            securityPassiveRefreshCallback?.invoke()
        }
    }

    override fun onRssiUpdated(rssi: Int) {
        runOnUiThread {
            updateSecurityRow()
            val ready = bleConnected || bleController.isSessionReady()
            PassiveEntryRuntime.reportForegroundBleRssi(
                rssi = rssi,
                sessionReady = ready,
                vin = profileStore.getActiveProfile().vin
            )
            if (ready) {
                evaluateForegroundRssiUnlockRecovery(rssi)
            }
            if (::passiveEntrySheets.isInitialized) {
                passiveEntrySheets.refreshIfOpen()
            }
        }
    }

    override fun onPairingPinAvailable(pin: String, address: String) {
        runOnUiThread {
            lastPairingPin = pin
            refreshSecuritySheetIfOpen()
        }
    }

    override fun onManualPairingRequired(pin: String, deviceName: String, address: String) {
        runOnUiThread {
            lastPairingPin = pin
            refreshSecuritySheetIfOpen()
            showTopPairingPinDrawer(pin = pin)
        }
    }

    override fun onBondCompleted(success: Boolean, address: String) {
        runOnUiThread {
            dismissTopPairingPinDrawer()
            if (success) {
                showHomeStatus("蓝牙配对已完成，正在建立安全连接...")
            }
            securityPassiveRefreshCallback?.invoke()
            if (::passiveEntrySheets.isInitialized) passiveEntrySheets.refreshIfOpen()
        }
    }

    private fun syncPassiveEntrySafetyHint(vin: String, telemetry: VehicleTelemetry) {
        if (vin.isBlank()) return
        val doorVals = listOf(
            telemetry.frontLeftDoor,
            telemetry.frontRightDoor,
            telemetry.backLeftDoor,
            telemetry.backRightDoor
        ).map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }
        val anyDoorOpen = doorVals.any { it == "1" || it == "open" || it == "true" } || telemetry.trunkOpen == true
        val allDoorsClosed = if (anyDoorOpen) {
            false
        } else if (doorVals.isNotEmpty() && doorVals.all { it == "0" || it == "closed" || it == "close" || it == "false" }) {
            true
        } else {
            null
        }
        val engineStopped = telemetry.engineRunning?.let { !it }
        PassiveEntryStore.updateVehicleSafetyHint(
            context = this,
            vin = vin,
            doorsClosed = allDoorsClosed,
            engineStopped = engineStopped,
            locked = telemetry.doorLocked
        )
    }

    private fun handleFixPassivePrerequisite(id: PrerequisiteId, profile: VehicleProfile) {
        when (id) {
            PrerequisiteId.KEY_MATERIAL -> {
                if (profile.userId.isBlank() || profile.mobileNo.isBlank()) {
                    Toast.makeText(this, "账号信息不完整，请重新登录后同步数字钥匙", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "正在从云端同步蓝牙数字钥匙...", Toast.LENGTH_SHORT).show()
                    launchCloudProvision(profile, automatic = false)
                }
            }
            PrerequisiteId.BLUETOOTH_ON -> {
                requestBluetoothEnable()
            }
            PrerequisiteId.BLE_PERMISSIONS -> {
                val missing = PassiveEntryPrerequisiteChecker.missingBlePermissions(this)
                if (missing.isEmpty()) {
                    passiveEntrySheets.refreshIfOpen()
                    return
                }
                // On Android 10-11, ACCESS_BACKGROUND_LOCATION must be requested after FINE_LOCATION.
                val foregroundFirst = missing.filter { it != Manifest.permission.ACCESS_BACKGROUND_LOCATION }
                val toAsk = if (foregroundFirst.isNotEmpty()) foregroundFirst else missing
                passivePermissionLauncher.launch(toAsk.toTypedArray())
            }
            PrerequisiteId.DEVICE_BONDED -> {
                if (!ensureBlePermissions()) {
                    Toast.makeText(this, "请先授予蓝牙权限", Toast.LENGTH_SHORT).show()
                    return
                }
                Toast.makeText(this, "正在高灵敏度扫描车辆，请在车旁完成首次蓝牙配对", Toast.LENGTH_LONG).show()
                bleController.triggerImmediatePairingOrConnect(
                    profileProvider = { profileStore.getActiveProfile() },
                    onRequestEnableBluetooth = { requestBluetoothEnable() }
                )
            }
            PrerequisiteId.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    passivePermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    runCatching {
                        startActivity(PassiveEntryPrerequisiteChecker.buildAppNotificationSettingsIntent(this))
                    }
                }
            }
            PrerequisiteId.BATTERY_UNRESTRICTED -> {
                runCatching {
                    startActivity(PassiveEntryPrerequisiteChecker.buildIgnoreBatteryOptimizationIntent(this))
                }.onFailure {
                    Toast.makeText(this, "请在系统应用信息中将电池策略设为「无限制」", Toast.LENGTH_LONG).show()
                }
            }
            PrerequisiteId.BACKGROUND_WAKE_CHANNEL -> {
                PassiveEntryPrerequisiteChecker.requestCompanionAssociation(
                    activity = this,
                    bleMac = profile.bleMac,
                    bleName = profile.bleName,
                    onIntentSenderReady = { sender ->
                        runCatching {
                            companionAssocLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }.onFailure {
                            Toast.makeText(this, "拉起系统设备关联失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAlreadyAssociated = {
                        val mac = PassiveEntryPrerequisiteChecker.normalizeMac(profile.bleMac).orEmpty()
                        if (profile.vin.isNotBlank() && mac.isNotBlank()) {
                            val cfg = PassiveEntryStore.load(this, profile.vin)
                            PassiveEntryStore.save(this, cfg.copy(cdmAssociatedMac = mac))
                        }
                        Toast.makeText(this, "已完成系统设备关联", Toast.LENGTH_SHORT).show()
                        passiveEntrySheets.refreshIfOpen()
                        securityPassiveRefreshCallback?.invoke()
                    },
                    onError = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun verifyPinAndEnablePassiveEntry(
        profile: VehicleProfile,
        onCompleted: (Boolean) -> Unit
    ) {
        val report = PassiveEntryPrerequisiteChecker.evaluate(this, profile)
        if (!report.allRequiredSatisfied) {
            Toast.makeText(this, report.summaryText, Toast.LENGTH_SHORT).show()
            onCompleted(false)
            return
        }
        if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
            Toast.makeText(this, "当前暂无有效车辆信息", Toast.LENGTH_SHORT).show()
            onCompleted(false)
            return
        }
        dismissAppDialogImmediately()
        lateinit var dialog: android.app.Dialog
        dialog = SixDigitPinSheet.showVerifyPinSheet(
            context = this,
            title = "验证 PIN 码",
            subtitle = "验证 PIN 码以开启蓝牙无感"
        ) { pin, showError, dismiss ->
            lifecycleScope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        httpClient.verifyVehiclePin(profile.vin, pin, profile.accessToken)
                    }
                }
                if (isFinishing || isDestroyed || !dialog.isShowing) return@launch
                res.onFailure { err ->
                    if (err is CherySessionExpiredException) {
                        dismiss()
                    } else {
                        showError("网络异常，PIN 码校验失败，请重试")
                    }
                    return@launch
                }
                if (!res.getOrDefault(false)) {
                    showError("云端 PIN 码错误，请重试")
                    return@launch
                }
                sessionVehiclePin = pin
                val savedProfile = withContext(Dispatchers.IO) {
                    profileStore.updateControlPin(profile.vin, pin)
                } ?: profileStore.getActiveProfile()
                currentProfile = savedProfile
                dismiss()
                val wakeResult = PassiveEntryWakeManager.enableForVehicle(this@MainActivity, savedProfile)
                if (wakeResult.ok) {
                    val prevCfg = PassiveEntryStore.load(this@MainActivity, savedProfile.vin)
                    val updated = prevCfg.copy(
                        enabled = true,
                        cdmAssociatedMac = if (wakeResult.companionPresenceActive) {
                            PassiveEntryPrerequisiteChecker.normalizeMac(savedProfile.bleMac).orEmpty()
                        } else {
                            prevCfg.cdmAssociatedMac
                        }
                    )
                    PassiveEntryStore.save(this@MainActivity, updated)
                    val vinKey = savedProfile.vin.trim().uppercase(Locale.US)
                    if (vinKey.isNotBlank()) {
                        lastPassiveAutoUnlockActiveByVin[vinKey] = updated.enabled && updated.autoUnlockEnabled
                    }
                    PassiveEntryRuntime.clearLockSuppression(this@MainActivity, savedProfile.vin)
                    PassiveEntryRuntime.setForegroundAppActive(this@MainActivity, true)
                    Toast.makeText(this@MainActivity, "已开启蓝牙无感（${wakeResult.channelLabel}）", Toast.LENGTH_SHORT).show()
                    triggerForegroundPassiveAutoUnlockIfReady(forceReevaluate = true)
                    onCompleted(true)
                } else {
                    Toast.makeText(this@MainActivity, "后台唤醒注册失败：${wakeResult.message}", Toast.LENGTH_LONG).show()
                    onCompleted(false)
                }
            }
        }
        trackAppDialog(dialog)
    }

    override fun onAuthenticated(sessionSnapshot: SessionSnapshot) {
        runOnUiThread {
            val nowElapsed = SystemClock.elapsedRealtime()
            bleConnected = true
            bleNearby = false
            foregroundSessionReadyElapsedMs = nowElapsed
            foregroundSessionUnlockEvaluated = false
            foregroundConsecutiveNearRssiHits = 0
            val activeVin = profileStore.getActiveProfile().vin
            if (activeVin.isNotBlank() &&
                foregroundBleDisconnectedAtElapsedMs > 0L &&
                (nowElapsed - foregroundBleDisconnectedAtElapsedMs) >= FOREGROUND_DEPARTURE_RESET_MS
            ) {
                PassiveEntryRuntime.clearLockSuppression(this, activeVin)
            }
            foregroundBleDisconnectedAtElapsedMs = 0L
            dismissTopPairingPinDrawer()
            updateSecurityRow()
            bleController.requestRemoteRssi()
            scheduleForegroundPassiveAutoUnlockCheck(forceReevaluate = false)
        }
    }

    private fun cancelPendingForegroundPassiveUnlockCheck() {
        pendingForegroundUnlockCheckRunnable?.let(mainHandler::removeCallbacks)
        pendingForegroundUnlockCheckRunnable = null
    }

    private fun handleForegroundBleDisconnected() {
        cancelPendingForegroundPassiveUnlockCheck()
        foregroundSessionUnlockEvaluated = false
        foregroundConsecutiveNearRssiHits = 0
        foregroundLiveBleLocked = null
        foregroundLiveBleDoorsOpen = null
        foregroundLiveBleEngineRunning = null
        foregroundLiveBleUpdatedElapsedMs = 0L
        if (foregroundBleDisconnectedAtElapsedMs == 0L) {
            foregroundBleDisconnectedAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun recordManualLockOrUnlockForPassive(vin: String, locked: Boolean) {
        if (vin.isBlank()) return
        cancelPendingForegroundPassiveUnlockCheck()
        foregroundSessionUnlockEvaluated = true
        foregroundConsecutiveNearRssiHits = 0
        foregroundLiveBleLocked = locked
        foregroundLiveBleUpdatedElapsedMs = SystemClock.elapsedRealtime()
        PassiveEntryStore.updateVehicleLockHint(this, vin, locked = locked)
        if (locked) {
            PassiveEntryRuntime.recordLockSuppression(this, vin, PassiveLockSuppressionReason.MANUAL_LOCK)
            PassiveEntryRuntime.appendLog("手动锁车已触发，启用临时防误解保护")
        } else {
            PassiveEntryRuntime.clearLockSuppression(this, vin)
        }
    }

    private fun triggerForegroundPassiveAutoUnlockIfReady(forceReevaluate: Boolean) {
        if (!bleController.isSessionReady()) return
        scheduleForegroundPassiveAutoUnlockCheck(forceReevaluate = forceReevaluate)
    }

    private fun scheduleForegroundPassiveAutoUnlockCheck(forceReevaluate: Boolean) {
        cancelPendingForegroundPassiveUnlockCheck()
        if (!bleController.isSessionReady()) return
        if (!forceReevaluate && foregroundSessionUnlockEvaluated) return
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank()) return
        val cfg = PassiveEntryStore.load(this, profile.vin)
        if (!cfg.enabled || !cfg.autoUnlockEnabled) return
        if (!PassiveEntryStore.getActiveEnabledVin(this).equals(profile.vin, ignoreCase = true)) return

        val hasSessionLiveLock = foregroundLiveBleLocked != null &&
            foregroundLiveBleUpdatedElapsedMs >= (foregroundSessionReadyElapsedMs - 2_000L)
        if (hasSessionLiveLock || forceReevaluate) {
            evaluateForegroundPassiveAutoUnlock(
                triggerSource = if (hasSessionLiveLock) "蓝牙实时车况" else "无感开关开启",
                forceReevaluate = forceReevaluate
            )
            return
        }
        val task = Runnable {
            pendingForegroundUnlockCheckRunnable = null
            evaluateForegroundPassiveAutoUnlock(
                triggerSource = "蓝牙连接完成",
                forceReevaluate = false
            )
        }
        pendingForegroundUnlockCheckRunnable = task
        mainHandler.postDelayed(task, FOREGROUND_CONNECT_STATUS_SETTLE_MS)
    }

    private fun evaluateForegroundPassiveAutoUnlock(
        triggerSource: String,
        forceReevaluate: Boolean = false
    ) {
        if (!forceReevaluate && foregroundSessionUnlockEvaluated) return
        if (!bleController.isSessionReady()) return
        if (isFinishing || isDestroyed) return
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank()) return
        val cfg = PassiveEntryStore.load(this, profile.vin)
        if (!cfg.enabled || !cfg.autoUnlockEnabled) return
        if (!PassiveEntryStore.getActiveEnabledVin(this).equals(profile.vin, ignoreCase = true)) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val suppression = PassiveEntryRuntime.activeLockSuppressionReason(this, profile.vin, nowElapsed)
        if (suppression != PassiveLockSuppressionReason.NONE) {
            foregroundSessionUnlockEvaluated = true
            PassiveEntryRuntime.appendLog(
                "前台蓝牙已连接（$triggerSource），处于${suppression.label}保护期，跳过自动解锁"
            )
            return
        }

        val liveFresh = foregroundLiveBleUpdatedElapsedMs > 0L &&
            (nowElapsed - foregroundLiveBleUpdatedElapsedMs) in 0L..FOREGROUND_LIVE_BLE_TTL_MS
        val telemetryFresh = lastTelemetrySyncedElapsedMs > 0L &&
            (nowElapsed - lastTelemetrySyncedElapsedMs) in 0L..FOREGROUND_UNLOCK_HINT_TTL_MS
        val hint = PassiveEntryStore.getVehicleSafetyHint(this, profile.vin)
        val hintFresh = hint.isFresh(maxAgeMs = FOREGROUND_UNLOCK_HINT_TTL_MS)

        val engineRunning = when {
            liveFresh && foregroundLiveBleEngineRunning != null -> foregroundLiveBleEngineRunning == true
            telemetryFresh && activeTelemetry?.engineRunning != null -> activeTelemetry?.engineRunning == true
            hintFresh && hint.engineStopped != null -> hint.engineStopped == false
            else -> false
        }
        if (engineRunning) {
            foregroundSessionUnlockEvaluated = true
            PassiveEntryRuntime.appendLog("前台蓝牙已连接（$triggerSource），车况显示处于启动状态，跳过自动解锁")
            return
        }

        val resolvedLocked: Boolean? = when {
            liveFresh && foregroundLiveBleLocked != null -> foregroundLiveBleLocked
            telemetryFresh && activeTelemetry?.doorLocked != null -> activeTelemetry?.doorLocked
            hintFresh && hint.locked != null -> hint.locked
            else -> null
        }
        if (resolvedLocked == false) {
            foregroundSessionUnlockEvaluated = true
            PassiveEntryStore.updateVehicleLockHint(this, profile.vin, locked = false)
            PassiveEntryRuntime.appendLog("前台蓝牙已连接（$triggerSource），车辆已处于解锁状态，跳过自动解锁")
            return
        }

        if (PassiveEntryRuntime.isAutoUnlockInCooldown(profile.vin, nowElapsed)) {
            foregroundSessionUnlockEvaluated = true
            PassiveEntryRuntime.appendLog("前台蓝牙已连接（$triggerSource），处于防抖冷却期内，跳过重复解锁")
            return
        }

        enqueuePendingBleActionMeta(
            "unlock",
            PendingBleActionMeta(label = "连接自动解锁", vin = profile.vin, isPassiveAuto = true)
        )
        val accepted = bleController.sendVehicleAction("unlock")
        if (accepted) {
            // Mark evaluated while awaiting T-Box 0x04 confirmation in onActionResult();
            // if T-Box rejects or times out, onActionResult() resets foregroundSessionUnlockEvaluated.
            foregroundSessionUnlockEvaluated = true
            PassiveEntryRuntime.appendLog("前台蓝牙已发起$triggerSource 自动解锁，等待车端确认")
        } else {
            popLastPendingBleActionMeta("unlock")
            PassiveEntryRuntime.appendLog("前台蓝牙连接自动解锁指令未能发送")
        }
    }

    private fun evaluateForegroundRssiUnlockRecovery(rssi: Int) {
        val profile = profileStore.getActiveProfile()
        if (profile.vin.isBlank()) return
        val cfg = PassiveEntryStore.load(this, profile.vin)
        if (!cfg.enabled || !cfg.autoUnlockEnabled) return
        if (!PassiveEntryStore.getActiveEnabledVin(this).equals(profile.vin, ignoreCase = true)) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val suppression = PassiveEntryRuntime.activeLockSuppressionReason(this, profile.vin, nowElapsed)
        if (suppression != PassiveLockSuppressionReason.WALK_AWAY_LOCK) {
            foregroundConsecutiveNearRssiHits = 0
            return
        }
        if (PassiveEntryRuntime.isAutoUnlockInCooldown(profile.vin, nowElapsed) || rssi < cfg.effectiveUnlockRssi) {
            foregroundConsecutiveNearRssiHits = 0
            return
        }
        foregroundConsecutiveNearRssiHits++
        if (foregroundConsecutiveNearRssiHits >= 2) {
            foregroundConsecutiveNearRssiHits = 0
            PassiveEntryRuntime.clearLockSuppression(this, profile.vin)
            evaluateForegroundPassiveAutoUnlock(triggerSource = "离车后再次靠近", forceReevaluate = true)
        }
    }

    private fun openDebugDiagnosticsScreen() {
        val screen = DebugDiagnosticsScreen(
            context = this,
            scope = lifecycleScope,
            bleController = bleController,
            profileProvider = { profileStore.getActiveProfile() },
            modelNameProvider = { uiPrefs.getString(KEY_MODEL_NAME, "").orEmpty() },
            plateNoProvider = { uiPrefs.getString(KEY_PLATE_NO, "").orEmpty() },
            telemetryProvider = { activeTelemetry },
            lastPairingPinProvider = { lastPairingPin },
            requirePinEveryTimeProvider = { uiPrefs.getBoolean(KEY_REQUIRE_PIN_EVERY_TIME, true) },
            antiTheftEnabledProvider = { lastKnownAntiTheftEnabled },
            onRequestBlePermissions = { ensureBlePermissions() },
            onPreviewPairingDrawer = { pin, _, _ ->
                showTopPairingPinDrawer(pin = pin)
            },
            onBack = { popSubpageOverlay() },
            vehicleCountProvider = {
                profileStore.listProfiles().count { it.vin.isNotBlank() }
            },
            onRefreshNow = {
                refreshTelemetry(silent = false, forceRestart = true)
            },
            onFetchVehicles = {
                syncCloudVehicleMeta()
                showHomeStatus("已触发云端车辆列表同步")
            },
            onReload3dModel = {
                carVisual?.reloadCurrentModel()
                showHomeStatus("已触发 3D 车模重载")
            },
            onReconnectBle = {
                onChannelBadgeTapped()
            },
            onReadBleStatus = {
                val rssiSent = bleController.requestRemoteRssi()
                refreshTelemetry(silent = false, forceRestart = true)
                showHomeStatus(if (rssiSent) "已请求蓝牙信号与实时车况" else "蓝牙未连接，已触发云端车况读取")
            },
            onOpenPassiveEntry = {
                passiveEntrySheets.showPassiveEntryConfigSheet()
            },
            onDisconnectBle = {
                bleController.resetTargetAndDisconnect()
                bleConnected = false
                bleNearby = false
                updateSecurityRow()
                showHomeStatus("已断开蓝牙连接")
            },
            onTriggerCarControl = { actionKey ->
                handleDebugCarControl(actionKey)
            },
            onValidateToken = { onResult ->
                validateCurrentSessionToken(onResult)
            },
            onSyncConfig = {
                syncCloudVehicleMeta()
                refreshTelemetry(silent = true, forceRestart = true)
                showHomeStatus("已同步配置与车况")
            }
        )
        val (view, onDestroy) = screen.buildView()
        pushSubpageOverlay(view, onDestroy)
    }

    private fun handleDebugCarControl(actionKey: String) {
        val airExtra = mapOf("airType" to uiPrefs.getString(KEY_AIR_TYPE, "1").orEmpty().ifBlank { "1" })
        when (actionKey.trim().lowercase(Locale.US)) {
            "flash" -> {
                carVisual?.triggerActionFeedback("find_car")
                dispatchSmartVehicleCommand("find_car", "闪灯鸣笛寻车")
            }
            "lock" -> {
                carVisual?.triggerActionFeedback("lock")
                dispatchSmartVehicleCommand("lock", "车门锁止")
            }
            "unlock" -> {
                carVisual?.triggerActionFeedback("unlock")
                dispatchSmartVehicleCommand("unlock", "车门解锁")
            }
            "ac_toggle" -> {
                if (activeTelemetry?.acOn == true) {
                    carVisual?.triggerActionFeedback("ac_off")
                    dispatchSmartVehicleCommand("ac_off", "关闭空调", extraParams = airExtra)
                } else {
                    carVisual?.climateTemp = climateTemperatureCelsius
                    carVisual?.triggerActionFeedback("ac_on")
                    dispatchSmartVehicleCommand(
                        "ac_on",
                        "开启空调",
                        temperatureCelsius = climateTemperatureCelsius,
                        extraParams = airExtra
                    )
                }
            }
            "trunk" -> {
                if (activeTelemetry?.trunkOpen == true) {
                    carVisual?.triggerActionFeedback("tailgate_close")
                    dispatchSmartVehicleCommand("tailgate_close", "关闭后备箱")
                } else {
                    carVisual?.triggerActionFeedback("trunk_open")
                    dispatchSmartVehicleCommand("trunk_open", "开启后备箱")
                }
            }
            "engine_toggle" -> {
                if (activeTelemetry?.engineRunning == true) {
                    carVisual?.triggerActionFeedback("engine_stop")
                    dispatchSmartVehicleCommand("engine_stop", "远程熄火")
                } else {
                    carVisual?.triggerActionFeedback("engine_start")
                    dispatchSmartVehicleCommand("engine_start", "远程启动引擎")
                }
            }
        }
    }

    private fun validateCurrentSessionToken(onResult: (ok: Boolean, summary: String) -> Unit) {
        val active = profileStore.getActiveProfile()
        if (active.accessToken.isBlank()) {
            onResult(false, "未检测到云端令牌")
            return
        }
        lifecycleScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.fetchUserInfo(active.accessToken)
                }
            }
            res.onSuccess { info ->
                val masked = SensitiveUiText.maskPhone(info.telephone.ifBlank { active.mobileNo }, "—")
                onResult(true, "云端令牌有效（$masked）")
            }.onFailure { err ->
                onResult(false, "令牌校验失败：${err.message.orEmpty().take(48)}")
            }
        }
    }

    /**
     * Slides a compact non-blocking pairing PIN drawer smoothly down from the TOP of the screen
     * so it remains clearly visible above centered or bottom-anchored Android system
     * Bluetooth pairing dialogs while the user manually types the 6-digit PIN.
     */
    private fun showTopPairingPinDrawer(pin: String) {
        val decor = window.decorView as? FrameLayout ?: return
        dismissTopPairingPinDrawer(immediate = true)

        val topSafePx = latestTopStatusBarInsetPx.coerceAtLeast(dp(28))
        val cleanPin = pin.trim()

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(16f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1D21"))
                cornerRadii = floatArrayOf(
                    0f, 0f,
                    0f, 0f,
                    dpF(22f), dpF(22f),
                    dpF(22f), dpF(22f)
                )
                setStroke(dp(1), Color.parseColor("#2E333E"))
            }
            setPadding(dp(20), topSafePx + dp(10), dp(20), dp(16))
            isClickable = true
            isFocusable = false
        }

        // Top Row: Bluetooth icon + concise title + Close button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            AppIconView(this@MainActivity, AppIconType.BLUETOOTH_ICON, Color.parseColor("#60A5FA")),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
        )
        headerRow.addView(
            text("蓝牙配对码", 15f, TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        val closeBtn = text("收起", 13f, TEXT_SECONDARY, Typeface.BOLD).apply {
            setPadding(dp(10), dp(6), dp(4), dp(6))
            isClickable = true
            setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                dismissTopPairingPinDrawer()
            }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        drawer.addView(headerRow, lp())

        // Middle Hero: 6 equal-spaced digit cells for instant glanceability
        if (cleanPin.length == 6) {
            val cellsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            cleanPin.forEachIndexed { idx, ch ->
                val cell = TextView(this).apply {
                    text = ch.toString()
                    setTextColor(TEXT_PRIMARY)
                    textSize = 24f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = GradientDrawable().apply {
                        cornerRadius = dpF(12f)
                        setColor(Color.parseColor("#121317"))
                        setStroke(dp(1), Color.parseColor("#2D323E"))
                    }
                }
                val cellLp = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    if (idx < 5) {
                        marginEnd = dp(8)
                    }
                }
                cellsRow.addView(cell, cellLp)
            }
            drawer.addView(cellsRow, lp(top = dp(14)))
        } else {
            val fallbackTv = text(cleanPin.toCharArray().joinToString(" "), 26f, TEXT_PRIMARY, Typeface.BOLD).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.1f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dpF(12f)
                    setColor(Color.parseColor("#121317"))
                    setStroke(dp(1), Color.parseColor("#2D323E"))
                }
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            drawer.addView(fallbackTv, lp(top = dp(14)))
        }

        // Concise manual input hint
        drawer.addView(
            text("请在系统配对框中手动输入上方配对码", 12.5f, TEXT_SECONDARY).apply {
                gravity = Gravity.CENTER
            },
            lp(top = dp(12))
        )

        topPairingDrawerView = drawer
        drawer.visibility = View.INVISIBLE
        decor.addView(
            drawer,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP)
        )

        // Slide smoothly down from above the top edge without a pre-measure 1-frame flash
        drawer.alpha = 0.92f
        drawer.post {
            if (topPairingDrawerView !== drawer || !drawer.isAttachedToWindow) return@post
            val h = drawer.height.takeIf { it > 0 }?.toFloat() ?: dpF(168f)
            drawer.translationY = -h
            drawer.visibility = View.VISIBLE
            drawer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(380L)
                .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                .start()
        }
    }

    private fun dismissTopPairingPinDrawer(immediate: Boolean = false) {
        val view = topPairingDrawerView ?: return
        topPairingDrawerView = null
        val parent = view.parent as? ViewGroup ?: return
        view.animate().cancel()
        if (immediate) {
            parent.removeView(view)
            return
        }
        val h = view.height.takeIf { it > 0 }?.toFloat() ?: dpF(210f)
        view.animate()
            .translationY(-h)
            .alpha(0f)
            .setDuration(260L)
            .setInterpolator(MotionSpec.EASE_STANDARD)
            .withEndAction {
                (view.parent as? ViewGroup)?.removeView(view)
            }
            .start()
    }

    override fun onVehicleResponse(parsedResponse: ParsedTBoxResponse) {
        runOnUiThread {
            var bleStateChanged = false
            parsedResponse.doorLocked?.let {
                foregroundLiveBleLocked = it
                bleStateChanged = true
            }
            parsedResponse.doorsOpen?.let {
                foregroundLiveBleDoorsOpen = it
                bleStateChanged = true
            }
            parsedResponse.engineRunning?.let {
                foregroundLiveBleEngineRunning = it
                bleStateChanged = true
            }
            if (bleStateChanged) {
                foregroundLiveBleUpdatedElapsedMs = SystemClock.elapsedRealtime()
                val activeVin = profileStore.getActiveProfile().vin
                if (activeVin.isNotBlank()) {
                    val prev = PassiveEntryStore.getVehicleSafetyHint(this, activeVin).takeIf { it.isFresh() }
                    PassiveEntryStore.updateVehicleSafetyHint(
                        context = this,
                        vin = activeVin,
                        doorsClosed = foregroundLiveBleDoorsOpen?.let { !it } ?: prev?.doorsClosed,
                        engineStopped = foregroundLiveBleEngineRunning?.let { !it } ?: prev?.engineStopped,
                        locked = foregroundLiveBleLocked ?: prev?.locked
                    )
                }
            }
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
            parsedResponse.trunkOpen?.let { carVisual?.trunkOpen = it }
            parsedResponse.acOn?.let { carVisual?.climateOn = it }
            parsedResponse.engineRunning?.let { carVisual?.engineRunning = it }
            refreshHomeHeaderAndRows()
            if (parsedResponse.doorLocked != null &&
                pendingForegroundUnlockCheckRunnable != null &&
                bleController.isSessionReady()
            ) {
                cancelPendingForegroundPassiveUnlockCheck()
                evaluateForegroundPassiveAutoUnlock(triggerSource = "蓝牙实时车况")
            }
        }
    }

    override fun onActionDispatched(actionKey: String, serial: Int) {
        // Wait for onActionResult (T-Box 0x04 confirmation) before committing UI/optimistic state.
    }

    override fun onActionResult(
        actionKey: String,
        serial: Int,
        success: Boolean,
        parsedResponse: ParsedTBoxResponse?,
        errorMessage: String?
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val activeVin = profileStore.getActiveProfile().vin
            val meta = consumePendingBleActionMeta(actionKey)
            val targetVin = meta?.vin?.takeIf { it.isNotBlank() } ?: activeVin
            val wasPassive = actionKey == "unlock" && meta?.isPassiveAuto == true
            val label = meta?.label?.takeIf { it.isNotBlank() } ?: actionKey

            if (success) {
                if (wasPassive) {
                    foregroundSessionUnlockEvaluated = true
                    foregroundLiveBleLocked = false
                    foregroundLiveBleUpdatedElapsedMs = SystemClock.elapsedRealtime()
                    PassiveEntryRuntime.reportForegroundAutoUnlock(
                        context = this,
                        vin = targetVin,
                        label = "连接自动解锁"
                    )
                    if (activeVin.equals(targetVin, ignoreCase = true)) {
                        applyOptimisticAction("unlock")
                    }
                    showHomeStatus("蓝牙无感已自动解锁")
                    securityPassiveRefreshCallback?.invoke()
                    if (::passiveEntrySheets.isInitialized) passiveEntrySheets.refreshIfOpen()
                } else {
                    if (actionKey == "lock") {
                        recordManualLockOrUnlockForPassive(targetVin, locked = true)
                    } else if (actionKey == "unlock") {
                        recordManualLockOrUnlockForPassive(targetVin, locked = false)
                    }
                    if (activeVin.equals(targetVin, ignoreCase = true)) {
                        applyOptimisticAction(actionKey)
                    }
                    showHomeStatus("蓝牙直连已完成：$label")
                }
                scheduleTelemetryRefresh()
            } else {
                if (wasPassive) {
                    foregroundSessionUnlockEvaluated = false
                    PassiveEntryRuntime.appendLog("前台蓝牙无感解锁未确认：${errorMessage ?: "车端未响应"}")
                }
                AppHaptics.perform(contentFrame, HapticLevel.ERROR_REJECT)
                val reason = errorMessage?.trim().orEmpty().ifBlank { "车端未确认执行" }.take(60)
                showHomeStatus("蓝牙控制失败：$reason")
            }
        }
    }

    override fun onError(message: String, throwable: Throwable?) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val isPairingOrBondErr = message.contains("配对", ignoreCase = true) ||
                message.contains("动态", ignoreCase = true) ||
                message.contains("绑定", ignoreCase = true)
            val friendly = when {
                isPairingOrBondErr -> message.take(80)
                message.contains("GATT", ignoreCase = true) ||
                    message.contains("BLE 握手", ignoreCase = true) ||
                    message.contains("AES-CMAC", ignoreCase = true) ->
                    "蓝牙钥匙连接中断，已切换远程网络"
                message.contains("BLE", ignoreCase = true) ->
                    "蓝牙扫描异常，请确认手机蓝牙已开启"
                else -> message.take(60)
            }
            if (currentScreen == Screen.HOME && subpageOverlayStack.isEmpty()) {
                showHomeStatus(friendly)
            } else if (isPairingOrBondErr || currentScreen == Screen.SECURITY) {
                Toast.makeText(this, friendly, Toast.LENGTH_SHORT).show()
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
        if (isFinishing || isDestroyed) return
        if (currentScreen != Screen.HOME || subpageOverlayStack.isNotEmpty() || transientToastBanner == null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
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
        onDismiss: (() -> Unit)? = null,
        buildContent: (LinearLayout, android.app.Dialog) -> Unit
    ) {
        dismissAppDialogImmediately()
        sheetClimateTempView = null

        var bypassImmediate = false
        var sheetRootView: View? = null
        val dialog = object : android.app.Dialog(this@MainActivity, com.chery.carplus.R.style.Theme_CarPlus_BottomSheetDialog) {
            override fun dismiss() {
                val target = sheetRootView
                if (!isShowing || target == null || bypassImmediate || MotionSpec.isBypassingSheetDismiss(this)) {
                    runCatching { super.dismiss() }
                    return
                }
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

        AppSystemInsets.installBottomSheetContent(dialog, outer, dimAmount = 0.68f)
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
            }
            onDismiss?.invoke()
        }
        dismissAppDialogSync = {
            bypassImmediate = true
            outer.animate().cancel()
            MotionSpec.dismissSheetImmediately(dialog)
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

    private fun effectiveSkylightState(telemetry: VehicleTelemetry?): String =
        telemetry?.skylightState.orEmpty().trim()
            .ifBlank { telemetry?.windowStates?.get("sunroof").orEmpty().trim() }

    private fun isSkylightOpen(state: String?): Boolean {
        val v = state.orEmpty().trim().lowercase(Locale.US)
        if (v.isBlank()) return false
        // In DataResp910 ANGLE_SKYLIGHT: "0", "1" (0% closed), "255"/"0xff"/"ffff"/"65535" are closed/invalid
        if (v in setOf("0", "1", "255", "0xff", "ffff", "0xffff", "65535", "closed", "close", "false", "off")) return false
        return true
    }

    private fun formatSkylightLabel(state: String?): String {
        val v = state.orEmpty().trim().lowercase(Locale.US)
        return when {
            v.isBlank() || v in setOf("255", "0xff", "ffff", "0xffff", "65535") -> "--"
            v in setOf("0", "1", "closed", "close", "false", "off") -> "已关闭"
            v in setOf("12", "tilt", "cock") -> "已翘起"
            else -> "已开启"
        }
    }

    private fun formatRemainingChargeLabel(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "0" || trimmed.equals("FFFF", ignoreCase = true) || trimmed == "65535") {
            return null
        }
        val mins = trimmed.toIntOrNull() ?: return trimmed
        if (mins <= 0 || mins > 24 * 60 * 7) return null
        val h = mins / 60
        val m = mins % 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分钟"
            h > 0 -> "${h}小时"
            else -> "${m}分钟"
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

    private fun maskMobile(mobile: String): String = SensitiveUiText.maskPhone(mobile)

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
        private const val KEY_ENGINE_TYPE = "engineType"
        private const val KEY_AIR_TYPE = "airType"
        private const val KEY_SUNROOF_TYPE = "sunroofType"
        private const val KEY_TEMP_LO = "tempLo"
        private const val KEY_TEMP_HI = "tempHi"
        private const val KEY_OPEN_AIR = "openAir"
        private const val KEY_VEHICLE_SN = "vehicleSn"
        private const val KEY_VEHICLE_ICCID = "vehicleIccid"
        private const val KEY_QUICK_ACTIONS = "quick_actions"
        private const val DEFAULT_QUICK_ACTIONS = "LOCK,CLIMATE,FLASH,TRUNK"
        private const val MAX_QUICK_ACTIONS = 4
        private const val DRAG_PREFIX_QUICK_ACTION = "quick_action:"
        private const val KEY_REQUIRE_PIN_EVERY_TIME = "require_pin_every_time"
        private const val KEY_LOCATION_PERM_PROMPTED = "location_perm_prompted"
        private const val KEY_ENTRANCE_STYLE = "car3d_entrance_style"
        private const val DEFAULT_ENTRANCE_STYLE = "auto"
        private const val MAX_DEVICE_LOCATION_AGE_MS = 15L * 60L * 1000L
        private const val FOREGROUND_DEPARTURE_RESET_MS = 30_000L
        private const val FOREGROUND_CONNECT_STATUS_SETTLE_MS = 600L
        private const val FOREGROUND_LIVE_BLE_TTL_MS = 60_000L
        private const val FOREGROUND_UNLOCK_HINT_TTL_MS = 120_000L

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
