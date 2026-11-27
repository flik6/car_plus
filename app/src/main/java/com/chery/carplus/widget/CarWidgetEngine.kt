package com.chery.carplus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.chery.carplus.R
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.RemoteCommand
import com.chery.carplus.cloud.VehiclePowertrain
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.profile.VehicleProfileStore
import com.chery.carplus.ui.MainActivity
import com.chery.carplus.ui.VehicleDisplayNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

enum class CarWidgetStyle(val layoutRes: Int) {
    BAR(R.layout.widget_car_bar),
    COMPACT(R.layout.widget_car_compact),
    MEDIUM(R.layout.widget_car_medium),
    LARGE(R.layout.widget_car_large)
}

data class CarWidgetSnapshot(
    val vin: String = "",
    val modelName: String = "CarPlus",
    val plateNo: String = "",
    val fuelPercent: Int = 0,
    val hasFuelData: Boolean = false,
    val batteryPercent: Int = 0,
    val hasBatteryData: Boolean = false,
    val powertrainCode: String = VehiclePowertrain.ICE.code,
    val isCharging: Boolean = false,
    val rangeKm: Int = -1,
    val electricRangeKm: Int = -1,
    val odometerKm: Int = -1,
    val doorLocked: Boolean = true,
    val engineRunning: Boolean = false,
    val acOn: Boolean = false,
    val trunkOpen: Boolean = false,
    val cabinTempC: Int = Int.MIN_VALUE,
    val primaryStatus: String = "状态待同步",
    val updatedAtMs: Long = 0L,
    val transientHint: String = ""
) {
    val powertrain: VehiclePowertrain
        get() = VehiclePowertrain.fromEngineTypeCode(powertrainCode)
            ?: VehiclePowertrain.resolve("", modelName, null)
}

object CarWidgetStateStore {
    private const val PREFS_NAME = "carplus_widget_state"
    private const val KEY_VIN = "vin"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_PLATE_NO = "plate_no"
    private const val KEY_FUEL_PERCENT = "fuel_percent"
    private const val KEY_HAS_FUEL = "has_fuel"
    private const val KEY_BATTERY_PERCENT = "battery_percent"
    private const val KEY_HAS_BATTERY = "has_battery"
    private const val KEY_POWERTRAIN = "powertrain_code"
    private const val KEY_IS_CHARGING = "is_charging"
    private const val KEY_RANGE_KM = "range_km"
    private const val KEY_ELECTRIC_RANGE_KM = "electric_range_km"
    private const val KEY_ODO_KM = "odo_km"
    private const val KEY_LOCKED = "door_locked"
    private const val KEY_ENGINE = "engine_running"
    private const val KEY_AC_ON = "ac_on"
    private const val KEY_TRUNK_OPEN = "trunk_open"
    private const val KEY_CABIN_TEMP = "cabin_temp"
    private const val KEY_PRIMARY_STATUS = "primary_status"
    private const val KEY_UPDATED_AT = "updated_at_ms"
    private const val KEY_TRANSIENT_HINT = "transient_hint"

    fun load(context: Context): CarWidgetSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CarWidgetSnapshot(
            vin = prefs.getString(KEY_VIN, "").orEmpty(),
            modelName = prefs.getString(KEY_MODEL_NAME, "CarPlus").orEmpty().ifBlank { "CarPlus" },
            plateNo = prefs.getString(KEY_PLATE_NO, "").orEmpty(),
            fuelPercent = prefs.getInt(KEY_FUEL_PERCENT, 0).coerceIn(0, 100),
            hasFuelData = prefs.getBoolean(KEY_HAS_FUEL, false),
            batteryPercent = prefs.getInt(KEY_BATTERY_PERCENT, 0).coerceIn(0, 100),
            hasBatteryData = prefs.getBoolean(KEY_HAS_BATTERY, false),
            powertrainCode = prefs.getString(KEY_POWERTRAIN, VehiclePowertrain.ICE.code).orEmpty(),
            isCharging = prefs.getBoolean(KEY_IS_CHARGING, false),
            rangeKm = prefs.getInt(KEY_RANGE_KM, -1),
            electricRangeKm = prefs.getInt(KEY_ELECTRIC_RANGE_KM, -1),
            odometerKm = prefs.getInt(KEY_ODO_KM, -1),
            doorLocked = prefs.getBoolean(KEY_LOCKED, true),
            engineRunning = prefs.getBoolean(KEY_ENGINE, false),
            acOn = prefs.getBoolean(KEY_AC_ON, false),
            trunkOpen = prefs.getBoolean(KEY_TRUNK_OPEN, false),
            cabinTempC = prefs.getInt(KEY_CABIN_TEMP, Int.MIN_VALUE),
            primaryStatus = prefs.getString(KEY_PRIMARY_STATUS, "状态待同步").orEmpty().ifBlank { "状态待同步" },
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT, 0L),
            transientHint = prefs.getString(KEY_TRANSIENT_HINT, "").orEmpty()
        )
    }

    fun save(context: Context, snapshot: CarWidgetSnapshot) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIN, snapshot.vin.trim().uppercase(Locale.US))
            .putString(KEY_MODEL_NAME, snapshot.modelName)
            .putString(KEY_PLATE_NO, snapshot.plateNo)
            .putInt(KEY_FUEL_PERCENT, snapshot.fuelPercent.coerceIn(0, 100))
            .putBoolean(KEY_HAS_FUEL, snapshot.hasFuelData)
            .putInt(KEY_BATTERY_PERCENT, snapshot.batteryPercent.coerceIn(0, 100))
            .putBoolean(KEY_HAS_BATTERY, snapshot.hasBatteryData)
            .putString(KEY_POWERTRAIN, snapshot.powertrainCode)
            .putBoolean(KEY_IS_CHARGING, snapshot.isCharging)
            .putInt(KEY_RANGE_KM, snapshot.rangeKm)
            .putInt(KEY_ELECTRIC_RANGE_KM, snapshot.electricRangeKm)
            .putInt(KEY_ODO_KM, snapshot.odometerKm)
            .putBoolean(KEY_LOCKED, snapshot.doorLocked)
            .putBoolean(KEY_ENGINE, snapshot.engineRunning)
            .putBoolean(KEY_AC_ON, snapshot.acOn)
            .putBoolean(KEY_TRUNK_OPEN, snapshot.trunkOpen)
            .putInt(KEY_CABIN_TEMP, snapshot.cabinTempC)
            .putString(KEY_PRIMARY_STATUS, snapshot.primaryStatus)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtMs)
            .putString(KEY_TRANSIENT_HINT, snapshot.transientHint)
            .apply()
    }

    fun setTransientHint(context: Context, hint: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRANSIENT_HINT, hint)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

object CarWidgetManager {

    const val ACTION_WIDGET_CMD = "com.chery.carplus.widget.ACTION_WIDGET_CMD"
    const val EXTRA_CMD = "widget_cmd"
    const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
    const val EXTRA_NEED_PIN_CACHE = "need_pin_cache"

    const val CMD_REFRESH = "refresh"
    const val CMD_TOGGLE_LOCK = "toggle_lock"
    const val CMD_TOGGLE_AC = "toggle_ac"
    const val CMD_FIND_CAR = "find_car"
    const val CMD_TOGGLE_TRUNK = "toggle_trunk"
    const val CMD_TOGGLE_ENGINE = "toggle_engine"

    private val COLOR_WHITE = Color.parseColor("#FFFFFF")
    private val COLOR_LABEL_IDLE = Color.parseColor("#B8BCC6")
    private val COLOR_ACTIVE = Color.parseColor("#3D9BFF")
    private val lastAutoSyncAttemptMs = AtomicLong(0L)
    private val bgSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Silently refreshes cloud vehicle telemetry when the system triggers periodic
     * widget `onUpdate` (every 30 min) and the cached snapshot is stale (>= 15 min).
     */
    fun triggerBackgroundSyncIfStale(
        context: Context,
        minStaleMs: Long = 15L * 60L * 1000L,
        pendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        val snap = CarWidgetStateStore.load(appCtx)
        if (snap.updatedAtMs > 0L && now - snap.updatedAtMs < minStaleMs) {
            pendingResult?.finish()
            return
        }

        val prevAttempt = lastAutoSyncAttemptMs.get()
        if (now - prevAttempt < 60_000L || !lastAutoSyncAttemptMs.compareAndSet(prevAttempt, now)) {
            pendingResult?.finish()
            return
        }

        val store = VehicleProfileStore(appCtx)
        val profile = store.getActiveProfile()
        if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
            pendingResult?.finish()
            return
        }

        bgSyncScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                    val client = CheryHttpClient(
                        defaultDeviceId = store.defaultDeviceId(),
                        connectTimeoutMs = 2_000,
                        readTimeoutMs = 2_500
                    )
                    val telemetry = runCatching {
                        client.fetchVehicleStatus(
                            vin = profile.vin,
                            accessToken = profile.accessToken,
                            sdkToken = profile.sdkToken,
                            userId = profile.userId
                        )
                    }.onFailure { err ->
                        if (err is com.chery.carplus.cloud.CherySessionExpiredException &&
                            store.getActiveProfile().accessToken == profile.accessToken
                        ) {
                            store.clearTokensPreserveOfflineKeys()
                        }
                    }.getOrNull() ?: return@withTimeoutOrNull

                    if (!store.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                        return@withTimeoutOrNull
                    }
                    val uiPrefs = appCtx.getSharedPreferences("car_plus_consumer_ui", Context.MODE_PRIVATE)
                    val cachedModel = VehicleDisplayNames.normalizeCachedName(
                        uiPrefs.getString("modelName", snap.modelName).orEmpty(),
                        uiPrefs.getString("plateNo", snap.plateNo).orEmpty()
                    )
                    val cachedPlate = uiPrefs.getString("plateNo", snap.plateNo).orEmpty()
                    syncFromTelemetry(
                        context = appCtx,
                        modelName = cachedModel,
                        plateNo = cachedPlate,
                        telemetry = telemetry
                    )
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    fun syncFromTelemetry(
        context: Context,
        modelName: String,
        plateNo: String,
        telemetry: VehicleTelemetry?,
        cabinTempC: Double? = null,
        primaryStatus: String? = null
    ) {
        val rawCurrent = CarWidgetStateStore.load(context)
        val targetVin = telemetry?.vin?.trim()?.uppercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: VehicleProfileStore(context).getActiveProfile().vin.trim().uppercase(Locale.US)
        val sameVehicle = targetVin.isBlank() ||
            rawCurrent.vin.isBlank() ||
            rawCurrent.vin.equals(targetVin, ignoreCase = true)
        val current = if (sameVehicle) rawCurrent else CarWidgetSnapshot(vin = targetVin)

        val uiPrefs = context.applicationContext.getSharedPreferences("car_plus_consumer_ui", Context.MODE_PRIVATE)
        val cachedEngineType = uiPrefs.getString("engineType", "").orEmpty()
        val resolvedModel = modelName.ifBlank { current.modelName }
        val powertrain = VehiclePowertrain.resolve(cachedEngineType, resolvedModel, telemetry)
        val fuel = telemetry?.fuelPercent
        val battery = telemetry?.batteryPercent
        val isCharging = powertrain.hasElectricDrive && telemetry?.isCharging == true
        val resolvedStatus = primaryStatus ?: when {
            isCharging -> "充电中"
            telemetry?.engineRunning == true -> if (powertrain == VehiclePowertrain.EV) "已上电" else "已启动"
            telemetry?.engineRunning == false -> "已驻车"
            telemetry?.doorLocked == true -> "已锁止"
            telemetry?.doorLocked == false -> "已解锁"
            else -> current.primaryStatus
        }
        val next = CarWidgetSnapshot(
            vin = targetVin.ifBlank { current.vin },
            modelName = resolvedModel,
            plateNo = plateNo.ifBlank { telemetry?.licensePlate.orEmpty().ifBlank { current.plateNo } },
            fuelPercent = fuel?.roundToInt()?.coerceIn(0, 100) ?: current.fuelPercent,
            hasFuelData = if (telemetry != null) fuel != null else current.hasFuelData,
            batteryPercent = battery?.roundToInt()?.coerceIn(0, 100) ?: current.batteryPercent,
            hasBatteryData = if (telemetry != null) battery != null else current.hasBatteryData,
            powertrainCode = powertrain.code,
            isCharging = isCharging,
            rangeKm = telemetry?.remainingRangeKm?.takeIf { it > 0.0 }?.roundToInt() ?: current.rangeKm,
            electricRangeKm = telemetry?.electricRangeKm?.roundToInt() ?: current.electricRangeKm,
            odometerKm = telemetry?.odometerKm?.takeIf { it > 0.0 }?.roundToInt() ?: current.odometerKm,
            doorLocked = telemetry?.doorLocked ?: current.doorLocked,
            engineRunning = telemetry?.engineRunning ?: current.engineRunning,
            acOn = telemetry?.acOn ?: current.acOn,
            trunkOpen = telemetry?.trunkOpen ?: current.trunkOpen,
            cabinTempC = cabinTempC?.roundToInt() ?: current.cabinTempC,
            primaryStatus = resolvedStatus,
            updatedAtMs = if (telemetry != null) System.currentTimeMillis() else current.updatedAtMs,
            transientHint = ""
        )
        CarWidgetStateStore.save(context, next)
        updateAllWidgets(context)
    }

    fun updateAllWidgets(context: Context) {
        val appContext = context.applicationContext
        val mgr = AppWidgetManager.getInstance(appContext) ?: return
        val snapshot = CarWidgetStateStore.load(appContext)
        updateProvider(appContext, mgr, CarWidgetBarProvider::class.java, CarWidgetStyle.BAR, snapshot)
        updateProvider(appContext, mgr, CarWidgetCompactProvider::class.java, CarWidgetStyle.COMPACT, snapshot)
        updateProvider(appContext, mgr, CarWidgetMediumProvider::class.java, CarWidgetStyle.MEDIUM, snapshot)
        updateProvider(appContext, mgr, CarWidgetLargeProvider::class.java, CarWidgetStyle.LARGE, snapshot)
    }

    fun updateWidgetsForStyle(
        context: Context,
        mgr: AppWidgetManager,
        widgetIds: IntArray,
        style: CarWidgetStyle
    ) {
        val snapshot = CarWidgetStateStore.load(context.applicationContext)
        for (id in widgetIds) {
            mgr.updateAppWidget(id, buildRemoteViews(context.applicationContext, style, snapshot))
        }
    }

    private fun updateProvider(
        context: Context,
        mgr: AppWidgetManager,
        providerClass: Class<*>,
        style: CarWidgetStyle,
        snapshot: CarWidgetSnapshot
    ) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, providerClass))
        if (ids.isEmpty()) return
        val views = buildRemoteViews(context, style, snapshot)
        for (id in ids) {
            mgr.updateAppWidget(id, views)
        }
    }

    private fun buildRemoteViews(
        context: Context,
        style: CarWidgetStyle,
        s: CarWidgetSnapshot
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, style.layoutRes)

        // Tap header or vehicle image to launch CarPlus Home
        val openAppPi = buildOpenAppPendingIntent(context)
        rv.setOnClickPendingIntent(R.id.widget_header_area, openAppPi)
        if (style != CarWidgetStyle.BAR) {
            rv.setOnClickPendingIntent(R.id.widget_car_image, openAppPi)
        }

        // Model Name
        rv.setTextViewText(R.id.widget_model_name, s.modelName)

        // Status Line (transient feedback takes priority while executing command)
        val primaryBarPercent = when (s.powertrain) {
            VehiclePowertrain.EV -> if (s.hasBatteryData) s.batteryPercent else -1
            VehiclePowertrain.PHEV -> when {
                s.hasBatteryData -> s.batteryPercent
                s.hasFuelData -> s.fuelPercent
                else -> -1
            }
            VehiclePowertrain.ICE -> if (s.hasFuelData) s.fuelPercent else -1
        }
        val statusText = if (s.transientHint.isNotBlank()) {
            s.transientHint
        } else {
            when (style) {
                CarWidgetStyle.BAR, CarWidgetStyle.COMPACT -> {
                    val energyPart = when (s.powertrain) {
                        VehiclePowertrain.EV -> if (s.hasBatteryData) "电 ${s.batteryPercent}%" else "--%"
                        VehiclePowertrain.PHEV -> when {
                            s.hasBatteryData && s.hasFuelData -> "电${s.batteryPercent}% 油${s.fuelPercent}%"
                            s.hasBatteryData -> "电 ${s.batteryPercent}%"
                            s.hasFuelData -> "油 ${s.fuelPercent}%"
                            else -> "--%"
                        }
                        VehiclePowertrain.ICE -> if (s.hasFuelData) "${s.fuelPercent}%" else "--%"
                    }
                    "${s.primaryStatus} · $energyPart"
                }
                CarWidgetStyle.MEDIUM, CarWidgetStyle.LARGE -> s.primaryStatus
            }
        }
        rv.setTextViewText(R.id.widget_status_line, statusText)

        // Exact sync timestamp placed right beside the Refresh button (never freezes on "刚刚更新")
        if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
            val timeLabel = if (s.transientHint.startsWith("正在刷新")) {
                "刷新中..."
            } else {
                formatSyncTimestamp(s.updatedAtMs)
            }
            rv.setTextViewText(R.id.widget_update_time, timeLabel)
        }

        // Fuel / Battery Bar (COMPACT & MEDIUM)
        if (style == CarWidgetStyle.COMPACT || style == CarWidgetStyle.MEDIUM) {
            rv.setProgressBar(R.id.widget_fuel_bar, 100, if (primaryBarPercent >= 0) primaryBarPercent else 0, false)
        }

        // Plate badge & detailed energy text (MEDIUM & LARGE)
        if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
            if (s.plateNo.isNotBlank()) {
                rv.setViewVisibility(R.id.widget_plate_badge, View.VISIBLE)
                rv.setTextViewText(R.id.widget_plate_badge, s.plateNo)
            } else {
                rv.setViewVisibility(R.id.widget_plate_badge, View.GONE)
            }
            val rangeStr = if (s.rangeKm > 0) "${s.rangeKm}km" else "--km"
            val energyDetail = when (s.powertrain) {
                VehiclePowertrain.EV -> {
                    val batStr = if (s.hasBatteryData) "电 ${s.batteryPercent}%" else "--%"
                    val evRange = if (s.electricRangeKm > 0) "${s.electricRangeKm}km" else rangeStr
                    "$batStr · $evRange"
                }
                VehiclePowertrain.PHEV -> {
                    val parts = mutableListOf<String>()
                    if (s.hasBatteryData) parts += "电 ${s.batteryPercent}%"
                    if (s.hasFuelData) parts += "油 ${s.fuelPercent}%"
                    if (parts.isEmpty()) parts += "--%"
                    parts += rangeStr
                    parts.joinToString(" · ")
                }
                VehiclePowertrain.ICE -> {
                    val fuelStr = if (s.hasFuelData) "${s.fuelPercent}%" else "--%"
                    "$fuelStr · $rangeStr"
                }
            }
            rv.setTextViewText(R.id.widget_fuel_text, energyDetail)
        }

        // Large Dashboard extra telemetry cells
        if (style == CarWidgetStyle.LARGE) {
            val energyLabel = when (s.powertrain) {
                VehiclePowertrain.EV -> "电量续航"
                VehiclePowertrain.PHEV -> "油电续航"
                VehiclePowertrain.ICE -> "油量续航"
            }
            rv.setTextViewText(R.id.widget_fuel_label, energyLabel)

            val climateStr = when {
                s.acOn && s.cabinTempC != Int.MIN_VALUE -> "开启 · ${s.cabinTempC}°C"
                s.acOn -> "已开启"
                s.cabinTempC != Int.MIN_VALUE -> "关闭 · ${s.cabinTempC}°C"
                else -> "已关闭"
            }
            rv.setTextViewText(R.id.widget_climate_text, climateStr)
            rv.setTextColor(R.id.widget_climate_text, if (s.acOn) COLOR_ACTIVE else COLOR_WHITE)

            val odoStr = if (s.odometerKm > 0) {
                "${String.format(Locale.US, "%,d", s.odometerKm)} km"
            } else {
                "-- km"
            }
            rv.setTextViewText(R.id.widget_odo_text, odoStr)
        }

        // Refresh button (COMPACT, MEDIUM, LARGE)
        if (style != CarWidgetStyle.BAR) {
            rv.setOnClickPendingIntent(R.id.widget_btn_refresh, buildActionPendingIntent(context, CMD_REFRESH, 100))
        }

        // Control 1: Lock / Unlock (ALL 4 styles)
        val unlocked = !s.doorLocked
        rv.setImageViewResource(
            R.id.widget_icon_lock,
            if (unlocked) R.drawable.ic_widget_lock_open else R.drawable.ic_widget_lock_closed
        )
        rv.setInt(R.id.widget_icon_lock, "setColorFilter", if (unlocked) COLOR_ACTIVE else COLOR_WHITE)
        rv.setInt(
            R.id.widget_btn_lock,
            "setBackgroundResource",
            if (unlocked) R.drawable.widget_btn_bg_active else R.drawable.widget_btn_bg
        )
        rv.setOnClickPendingIntent(R.id.widget_btn_lock, buildActionPendingIntent(context, CMD_TOGGLE_LOCK, 101))
        if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
            rv.setTextViewText(R.id.widget_label_lock, if (unlocked) "锁车" else "解锁")
            rv.setTextColor(R.id.widget_label_lock, if (unlocked) COLOR_ACTIVE else COLOR_LABEL_IDLE)
        }

        // Control 2: Climate AC (ALL 4 styles)
        rv.setInt(R.id.widget_icon_ac, "setColorFilter", if (s.acOn) COLOR_ACTIVE else COLOR_WHITE)
        rv.setInt(
            R.id.widget_btn_ac,
            "setBackgroundResource",
            if (s.acOn) R.drawable.widget_btn_bg_active else R.drawable.widget_btn_bg
        )
        rv.setOnClickPendingIntent(R.id.widget_btn_ac, buildActionPendingIntent(context, CMD_TOGGLE_AC, 102))
        if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
            rv.setTextViewText(R.id.widget_label_ac, if (s.acOn) "关空调" else "空调")
            rv.setTextColor(R.id.widget_label_ac, if (s.acOn) COLOR_ACTIVE else COLOR_LABEL_IDLE)
        }

        // Controls 3 & 4: Find Car + Trunk (BAR, MEDIUM, LARGE)
        if (style != CarWidgetStyle.COMPACT) {
            rv.setInt(R.id.widget_icon_flash, "setColorFilter", COLOR_WHITE)
            rv.setOnClickPendingIntent(R.id.widget_btn_flash, buildActionPendingIntent(context, CMD_FIND_CAR, 103))

            rv.setInt(R.id.widget_icon_trunk, "setColorFilter", if (s.trunkOpen) COLOR_ACTIVE else COLOR_WHITE)
            rv.setInt(
                R.id.widget_btn_trunk,
                "setBackgroundResource",
                if (s.trunkOpen) R.drawable.widget_btn_bg_active else R.drawable.widget_btn_bg
            )
            rv.setOnClickPendingIntent(R.id.widget_btn_trunk, buildActionPendingIntent(context, CMD_TOGGLE_TRUNK, 104))
            if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
                rv.setTextViewText(R.id.widget_label_trunk, if (s.trunkOpen) "关尾箱" else "后备箱")
                rv.setTextColor(R.id.widget_label_trunk, if (s.trunkOpen) COLOR_ACTIVE else COLOR_LABEL_IDLE)
            }
        }

        // Control 5: Engine Start/Stop (LARGE)
        if (style == CarWidgetStyle.LARGE) {
            rv.setInt(R.id.widget_icon_engine, "setColorFilter", if (s.engineRunning) COLOR_ACTIVE else COLOR_WHITE)
            rv.setInt(
                R.id.widget_btn_engine,
                "setBackgroundResource",
                if (s.engineRunning) R.drawable.widget_btn_bg_active else R.drawable.widget_btn_bg
            )
            rv.setOnClickPendingIntent(R.id.widget_btn_engine, buildActionPendingIntent(context, CMD_TOGGLE_ENGINE, 105))
            rv.setTextViewText(R.id.widget_label_engine, if (s.engineRunning) "熄火" else "启动")
            rv.setTextColor(R.id.widget_label_engine, if (s.engineRunning) COLOR_ACTIVE else COLOR_LABEL_IDLE)
        }

        return rv
    }

    private fun buildOpenAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, 90, intent, flags)
    }

    private fun buildActionPendingIntent(context: Context, cmd: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CarWidgetActionReceiver::class.java).apply {
            action = ACTION_WIDGET_CMD
            putExtra(EXTRA_CMD, cmd)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun formatSyncTimestamp(updatedAtMs: Long): String {
        if (updatedAtMs <= 0L) return "待同步"
        val nowCal = Calendar.getInstance()
        val updCal = Calendar.getInstance().apply { timeInMillis = updatedAtMs }
        val sameDay = nowCal.get(Calendar.YEAR) == updCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == updCal.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updatedAtMs))
            "$hhmm 更新"
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAtMs))
        }
    }
}

class CarWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CarWidgetManager.ACTION_WIDGET_CMD) return
        val cmd = intent.getStringExtra(CarWidgetManager.EXTRA_CMD).orEmpty()
        if (cmd.isBlank()) return

        val appCtx = context.applicationContext
        val store = VehicleProfileStore(appCtx)
        val profile = store.getActiveProfile()

        if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
            launchAppForAction(appCtx, cmd)
            return
        }

        val uiPrefs = appCtx.getSharedPreferences("car_plus_consumer_ui", Context.MODE_PRIVATE)
        val requirePinEveryTime = uiPrefs.getBoolean("require_pin_every_time", true)
        val savedPin = profile.controlPin.trim()
        val needsPin = cmd != CarWidgetManager.CMD_REFRESH
        if (needsPin && (requirePinEveryTime || savedPin.length != 6)) {
            val needPinCache = !requirePinEveryTime && savedPin.length != 6
            if (needPinCache) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        appCtx,
                        "本机尚未缓存 PIN 码，验证一次后小组件即可免密控车",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            launchAppForAction(appCtx, cmd, needPinCache = needPinCache)
            return
        }

        val pendingResult = goAsync()
        val mainHandler = Handler(Looper.getMainLooper())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishReceiverOnce() {
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult.finish() }
            }
        }
        val watchdog = Runnable { finishReceiverOnce() }
        mainHandler.postDelayed(watchdog, 8_800L)

        scope.launch {
            var commandSucceeded = false
            try {
                val completed = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                    val client = CheryHttpClient(
                        defaultDeviceId = store.defaultDeviceId(),
                        connectTimeoutMs = 2_000,
                        readTimeoutMs = 2_500
                    )
                    val snap = CarWidgetStateStore.load(appCtx)
                    val cachedModel = VehicleDisplayNames.normalizeCachedName(
                        uiPrefs.getString("modelName", snap.modelName).orEmpty(),
                        uiPrefs.getString("plateNo", snap.plateNo).orEmpty()
                    )
                    val cachedPlate = uiPrefs.getString("plateNo", snap.plateNo).orEmpty()
                    val airType = uiPrefs.getString("airType", "1").orEmpty().ifBlank { "1" }

                    if (cmd == CarWidgetManager.CMD_REFRESH) {
                        CarWidgetStateStore.setTransientHint(appCtx, "正在刷新车况...")
                        CarWidgetManager.updateAllWidgets(appCtx)
                        var refreshExpired = false
                        val telemetry = try {
                            client.fetchVehicleStatus(
                                vin = profile.vin,
                                accessToken = profile.accessToken,
                                sdkToken = profile.sdkToken,
                                userId = profile.userId
                            )
                        } catch (_: com.chery.carplus.cloud.CherySessionExpiredException) {
                            refreshExpired = true
                            null
                        } catch (_: Throwable) {
                            null
                        }
                        if (refreshExpired) {
                            if (store.getActiveProfile().accessToken == profile.accessToken) {
                                store.clearTokensPreserveOfflineKeys()
                            }
                            CarWidgetStateStore.setTransientHint(appCtx, "")
                            CarWidgetManager.updateAllWidgets(appCtx)
                            mainHandler.post {
                                Toast.makeText(appCtx, "登录已失效，请打开应用重新登录", Toast.LENGTH_SHORT).show()
                                launchAppForAction(appCtx, cmd, needPinCache = false)
                            }
                            return@withTimeoutOrNull
                        }
                        if (telemetry != null && store.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                            CarWidgetManager.syncFromTelemetry(
                                context = appCtx,
                                modelName = cachedModel,
                                plateNo = cachedPlate,
                                telemetry = telemetry
                            )
                        } else {
                            CarWidgetStateStore.setTransientHint(appCtx, "")
                            CarWidgetManager.updateAllWidgets(appCtx)
                            mainHandler.post {
                                Toast.makeText(appCtx, "刷新车况失败，请检查网络", Toast.LENGTH_SHORT).show()
                            }
                        }
                        return@withTimeoutOrNull
                    }

                    val (remoteCmd, actionLabel, params) = when (cmd) {
                        CarWidgetManager.CMD_TOGGLE_LOCK -> {
                            if (snap.doorLocked) Triple(RemoteCommand.DOOR_UNLOCK, "解锁", emptyMap())
                            else Triple(RemoteCommand.DOOR_LOCK, "锁车", emptyMap())
                        }
                        CarWidgetManager.CMD_TOGGLE_AC -> {
                            if (snap.acOn) Triple(RemoteCommand.AC_OFF, "关闭空调", mapOf("airType" to airType))
                            else Triple(RemoteCommand.AC_ON, "开启空调", mapOf("temperature" to "23", "airType" to airType))
                        }
                        CarWidgetManager.CMD_FIND_CAR -> Triple(RemoteCommand.FIND_CAR, "寻车", emptyMap())
                        CarWidgetManager.CMD_TOGGLE_TRUNK -> {
                            if (snap.trunkOpen) Triple(RemoteCommand.TAILGATE_CLOSE, "关闭后备箱", emptyMap())
                            else Triple(RemoteCommand.TRUNK_UNLOCK, "开启后备箱", emptyMap())
                        }
                        CarWidgetManager.CMD_TOGGLE_ENGINE -> {
                            if (snap.engineRunning) Triple(RemoteCommand.ENGINE_STOP, "熄火", emptyMap())
                            else Triple(RemoteCommand.ENGINE_START, "远程启动", emptyMap())
                        }
                        else -> return@withTimeoutOrNull
                    }

                    CarWidgetStateStore.setTransientHint(appCtx, "正在${actionLabel}...")
                    CarWidgetManager.updateAllWidgets(appCtx)

                    var sessionExpired = false
                    val result = try {
                        client.sendRemoteCommand(
                            vin = profile.vin,
                            userId = profile.userId,
                            accessToken = profile.accessToken,
                            sdkToken = profile.sdkToken,
                            command = remoteCmd,
                            pin = savedPin,
                            params = params
                        )
                    } catch (_: com.chery.carplus.cloud.CherySessionExpiredException) {
                        sessionExpired = true
                        null
                    } catch (_: Throwable) {
                        null
                    }

                    if (sessionExpired) {
                        if (store.getActiveProfile().accessToken == profile.accessToken) {
                            store.clearTokensPreserveOfflineKeys()
                        }
                        CarWidgetStateStore.setTransientHint(appCtx, "")
                        CarWidgetManager.updateAllWidgets(appCtx)
                        mainHandler.post {
                            Toast.makeText(appCtx, "登录已失效，请打开应用重新登录", Toast.LENGTH_SHORT).show()
                            launchAppForAction(appCtx, cmd, needPinCache = false)
                        }
                        return@withTimeoutOrNull
                    }

                    if (result?.isPinError == true) {
                        store.updateControlPin(profile.vin, "")
                        CarWidgetStateStore.setTransientHint(appCtx, "")
                        CarWidgetManager.updateAllWidgets(appCtx)
                        mainHandler.post {
                            Toast.makeText(appCtx, "本机缓存的 PIN 码已失效，请重新验证", Toast.LENGTH_SHORT).show()
                            launchAppForAction(appCtx, cmd, needPinCache = true)
                        }
                        return@withTimeoutOrNull
                    }

                    if (result?.success == true && store.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                        commandSucceeded = true
                        val optimistic = when (remoteCmd) {
                            RemoteCommand.DOOR_UNLOCK -> snap.copy(vin = profile.vin, doorLocked = false, transientHint = "已发送解锁")
                            RemoteCommand.DOOR_LOCK -> snap.copy(vin = profile.vin, doorLocked = true, transientHint = "已发送锁车")
                            RemoteCommand.AC_ON -> snap.copy(vin = profile.vin, acOn = true, transientHint = "已发送开空调")
                            RemoteCommand.AC_OFF -> snap.copy(vin = profile.vin, acOn = false, transientHint = "已发送关空调")
                            RemoteCommand.TRUNK_UNLOCK -> snap.copy(vin = profile.vin, trunkOpen = true, transientHint = "已发送开后备箱")
                            RemoteCommand.TAILGATE_CLOSE -> snap.copy(vin = profile.vin, trunkOpen = false, transientHint = "已发送关后备箱")
                            RemoteCommand.ENGINE_START -> snap.copy(vin = profile.vin, engineRunning = true, primaryStatus = "已启动", transientHint = "已发送启动")
                            RemoteCommand.ENGINE_STOP -> snap.copy(vin = profile.vin, engineRunning = false, primaryStatus = "已驻车", transientHint = "已发送熄火")
                            else -> snap.copy(vin = profile.vin, transientHint = "已发送${actionLabel}")
                        }
                        CarWidgetStateStore.save(appCtx, optimistic)
                        CarWidgetManager.updateAllWidgets(appCtx)

                        delay(1200L)
                        val refreshed = runCatching {
                            client.fetchVehicleStatus(
                                vin = profile.vin,
                                accessToken = profile.accessToken,
                                sdkToken = profile.sdkToken,
                                userId = profile.userId
                            )
                        }.getOrNull()
                        if (refreshed != null && store.getActiveProfile().vin.equals(profile.vin, ignoreCase = true)) {
                            CarWidgetManager.syncFromTelemetry(appCtx, cachedModel, cachedPlate, refreshed)
                        } else {
                            CarWidgetStateStore.setTransientHint(appCtx, "")
                            CarWidgetManager.updateAllWidgets(appCtx)
                        }
                    } else if (result?.success != true) {
                        CarWidgetStateStore.setTransientHint(appCtx, "")
                        CarWidgetManager.updateAllWidgets(appCtx)
                        val msg = result?.resultMessage?.takeIf { it.isNotBlank() } ?: "${actionLabel}失败，请打开应用重试"
                        mainHandler.post {
                            Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Command succeeded but active VIN changed mid-flight; clear any stale hint on matching snapshot.
                        if (CarWidgetStateStore.load(appCtx).vin.equals(profile.vin, ignoreCase = true)) {
                            CarWidgetStateStore.setTransientHint(appCtx, "")
                            CarWidgetManager.updateAllWidgets(appCtx)
                        }
                    }
                }
                if (completed == null) {
                    CarWidgetStateStore.setTransientHint(appCtx, "")
                    CarWidgetManager.updateAllWidgets(appCtx)
                    if (!commandSucceeded) {
                        mainHandler.post {
                            Toast.makeText(appCtx, "网络响应超时，请稍后重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } finally {
                mainHandler.removeCallbacks(watchdog)
                finishReceiverOnce()
            }
        }
    }

    private fun launchAppForAction(context: Context, cmd: String, needPinCache: Boolean = false) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CarWidgetManager.EXTRA_SHORTCUT_ACTION, cmd)
            putExtra(CarWidgetManager.EXTRA_NEED_PIN_CACHE, needPinCache)
        }
        runCatching { context.startActivity(intent) }
    }
}
