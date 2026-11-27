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
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.profile.VehicleProfileStore
import com.chery.carplus.ui.MainActivity
import com.chery.carplus.ui.VehicleDisplayNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

enum class CarWidgetStyle(val layoutRes: Int) {
    BAR(R.layout.widget_car_bar),
    COMPACT(R.layout.widget_car_compact),
    MEDIUM(R.layout.widget_car_medium),
    LARGE(R.layout.widget_car_large)
}

data class CarWidgetSnapshot(
    val modelName: String = "艾瑞泽8",
    val plateNo: String = "",
    val fuelPercent: Int = 0,
    val hasFuelData: Boolean = false,
    val rangeKm: Int = -1,
    val odometerKm: Int = -1,
    val doorLocked: Boolean = true,
    val engineRunning: Boolean = false,
    val acOn: Boolean = false,
    val trunkOpen: Boolean = false,
    val cabinTempC: Int = Int.MIN_VALUE,
    val primaryStatus: String = "状态待同步",
    val updatedAtMs: Long = 0L,
    val transientHint: String = ""
)

object CarWidgetStateStore {
    private const val PREFS_NAME = "carplus_widget_state"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_PLATE_NO = "plate_no"
    private const val KEY_FUEL_PERCENT = "fuel_percent"
    private const val KEY_HAS_FUEL = "has_fuel"
    private const val KEY_RANGE_KM = "range_km"
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
            modelName = prefs.getString(KEY_MODEL_NAME, "艾瑞泽8").orEmpty().ifBlank { "艾瑞泽8" },
            plateNo = prefs.getString(KEY_PLATE_NO, "").orEmpty(),
            fuelPercent = prefs.getInt(KEY_FUEL_PERCENT, 0).coerceIn(0, 100),
            hasFuelData = prefs.getBoolean(KEY_HAS_FUEL, false),
            rangeKm = prefs.getInt(KEY_RANGE_KM, -1),
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
            .putString(KEY_MODEL_NAME, snapshot.modelName)
            .putString(KEY_PLATE_NO, snapshot.plateNo)
            .putInt(KEY_FUEL_PERCENT, snapshot.fuelPercent.coerceIn(0, 100))
            .putBoolean(KEY_HAS_FUEL, snapshot.hasFuelData)
            .putInt(KEY_RANGE_KM, snapshot.rangeKm)
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

    const val CMD_REFRESH = "refresh"
    const val CMD_TOGGLE_LOCK = "toggle_lock"
    const val CMD_TOGGLE_AC = "toggle_ac"
    const val CMD_FIND_CAR = "find_car"
    const val CMD_TOGGLE_TRUNK = "toggle_trunk"
    const val CMD_TOGGLE_ENGINE = "toggle_engine"

    private val COLOR_WHITE = Color.parseColor("#FFFFFF")
    private val COLOR_LABEL_IDLE = Color.parseColor("#B8BCC6")
    private val COLOR_ACTIVE = Color.parseColor("#3D9BFF")

    fun syncFromTelemetry(
        context: Context,
        modelName: String,
        plateNo: String,
        telemetry: VehicleTelemetry?,
        cabinTempC: Double? = null,
        primaryStatus: String? = null
    ) {
        val current = CarWidgetStateStore.load(context)
        val fuel = telemetry?.fuelPercent ?: telemetry?.electricitySurplus?.toDoubleOrNull()
        val resolvedStatus = primaryStatus ?: when {
            telemetry?.engineRunning == true -> "已启动"
            telemetry?.engineRunning == false -> "已驻车"
            telemetry?.doorLocked == true -> "已锁止"
            telemetry?.doorLocked == false -> "已解锁"
            else -> current.primaryStatus
        }
        val next = CarWidgetSnapshot(
            modelName = modelName.ifBlank { current.modelName },
            plateNo = plateNo.ifBlank { telemetry?.licensePlate.orEmpty().ifBlank { current.plateNo } },
            fuelPercent = fuel?.roundToInt()?.coerceIn(0, 100) ?: current.fuelPercent,
            hasFuelData = fuel != null || current.hasFuelData,
            rangeKm = telemetry?.remainingRangeKm?.takeIf { it > 0.0 }?.roundToInt() ?: current.rangeKm,
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
        val statusText = if (s.transientHint.isNotBlank()) {
            s.transientHint
        } else {
            when (style) {
                CarWidgetStyle.BAR, CarWidgetStyle.COMPACT -> {
                    val fuelPart = if (s.hasFuelData) "${s.fuelPercent}%" else "--%"
                    "${s.primaryStatus} · $fuelPart"
                }
                CarWidgetStyle.MEDIUM, CarWidgetStyle.LARGE -> {
                    val timePart = formatRelativeTime(s.updatedAtMs)
                    if (timePart.isNotBlank()) "${s.primaryStatus} · $timePart" else s.primaryStatus
                }
            }
        }
        rv.setTextViewText(R.id.widget_status_line, statusText)

        // Fuel Bar (COMPACT & MEDIUM)
        if (style == CarWidgetStyle.COMPACT || style == CarWidgetStyle.MEDIUM) {
            rv.setProgressBar(R.id.widget_fuel_bar, 100, if (s.hasFuelData) s.fuelPercent else 0, false)
        }

        // Plate badge & detailed fuel text (MEDIUM & LARGE)
        if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
            if (s.plateNo.isNotBlank()) {
                rv.setViewVisibility(R.id.widget_plate_badge, View.VISIBLE)
                rv.setTextViewText(R.id.widget_plate_badge, s.plateNo)
            } else {
                rv.setViewVisibility(R.id.widget_plate_badge, View.GONE)
            }
            val fuelStr = if (s.hasFuelData) "${s.fuelPercent}%" else "--%"
            val rangeStr = if (s.rangeKm > 0) "${s.rangeKm}km" else "--km"
            rv.setTextViewText(R.id.widget_fuel_text, "$fuelStr · $rangeStr")
        }

        // Large Dashboard extra telemetry cells
        if (style == CarWidgetStyle.LARGE) {
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

    private fun formatRelativeTime(updatedAtMs: Long): String {
        if (updatedAtMs <= 0L) return ""
        val diffSec = ((System.currentTimeMillis() - updatedAtMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 60L -> "刚刚更新"
            diffSec < 3600L -> "${diffSec / 60L} 分钟前"
            diffSec < 86_400L -> "${diffSec / 3600L} 小时前"
            else -> "${diffSec / 86_400L} 天前"
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

        val uiPrefs = appCtx.getSharedPreferences("carplus_ui_prefs", Context.MODE_PRIVATE)
        val requirePinEveryTime = uiPrefs.getBoolean("require_pin_every_time", false)
        val needsPin = cmd != CarWidgetManager.CMD_REFRESH
        if (needsPin && (requirePinEveryTime || profile.controlPin.length != 6)) {
            // Respect user's security policy: open App for 6-digit PIN sheet
            launchAppForAction(appCtx, cmd)
            return
        }

        val pendingResult = goAsync()
        val mainHandler = Handler(Looper.getMainLooper())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val client = CheryHttpClient(defaultDeviceId = store.defaultDeviceId())
                val snap = CarWidgetStateStore.load(appCtx)
                val cachedModel = VehicleDisplayNames.normalizeCachedName(
                    uiPrefs.getString("cached_model_name", snap.modelName).orEmpty(),
                    uiPrefs.getString("cached_plate_no", snap.plateNo).orEmpty()
                )
                val cachedPlate = uiPrefs.getString("cached_plate_no", snap.plateNo).orEmpty()

                if (cmd == CarWidgetManager.CMD_REFRESH) {
                    CarWidgetStateStore.setTransientHint(appCtx, "正在刷新车况...")
                    CarWidgetManager.updateAllWidgets(appCtx)
                    val telemetry = runCatching {
                        client.fetchVehicleStatus(
                            vin = profile.vin,
                            accessToken = profile.accessToken,
                            sdkToken = profile.sdkToken,
                            userId = profile.userId
                        )
                    }.getOrNull()
                    if (telemetry != null) {
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
                    return@launch
                }

                val (remoteCmd, actionLabel, params) = when (cmd) {
                    CarWidgetManager.CMD_TOGGLE_LOCK -> {
                        if (snap.doorLocked) Triple(RemoteCommand.DOOR_UNLOCK, "解锁", emptyMap())
                        else Triple(RemoteCommand.DOOR_LOCK, "锁车", emptyMap())
                    }
                    CarWidgetManager.CMD_TOGGLE_AC -> {
                        if (snap.acOn) Triple(RemoteCommand.AC_OFF, "关闭空调", emptyMap())
                        else Triple(RemoteCommand.AC_ON, "开启空调", mapOf("temperature" to "23.0"))
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
                    else -> return@launch
                }

                CarWidgetStateStore.setTransientHint(appCtx, "正在${actionLabel}...")
                CarWidgetManager.updateAllWidgets(appCtx)

                val result = runCatching {
                    client.sendRemoteCommand(
                        vin = profile.vin,
                        userId = profile.userId,
                        accessToken = profile.accessToken,
                        sdkToken = profile.sdkToken,
                        command = remoteCmd,
                        pin = profile.controlPin,
                        params = params
                    )
                }.getOrNull()

                if (result?.success == true) {
                    // Optimistic update immediately for crisp desktop responsiveness
                    val optimistic = when (remoteCmd) {
                        RemoteCommand.DOOR_UNLOCK -> snap.copy(doorLocked = false, transientHint = "已发送解锁")
                        RemoteCommand.DOOR_LOCK -> snap.copy(doorLocked = true, transientHint = "已发送锁车")
                        RemoteCommand.AC_ON -> snap.copy(acOn = true, transientHint = "已发送开空调")
                        RemoteCommand.AC_OFF -> snap.copy(acOn = false, transientHint = "已发送关空调")
                        RemoteCommand.TRUNK_UNLOCK -> snap.copy(trunkOpen = true, transientHint = "已发送开后备箱")
                        RemoteCommand.TAILGATE_CLOSE -> snap.copy(trunkOpen = false, transientHint = "已发送关后备箱")
                        RemoteCommand.ENGINE_START -> snap.copy(engineRunning = true, primaryStatus = "已启动", transientHint = "已发送启动")
                        RemoteCommand.ENGINE_STOP -> snap.copy(engineRunning = false, primaryStatus = "已驻车", transientHint = "已发送熄火")
                        else -> snap.copy(transientHint = "已发送${actionLabel}")
                    }
                    CarWidgetStateStore.save(appCtx, optimistic)
                    CarWidgetManager.updateAllWidgets(appCtx)

                    // Auto-sync real telemetry shortly after command
                    delay(1600L)
                    val refreshed = runCatching {
                        client.fetchVehicleStatus(
                            vin = profile.vin,
                            accessToken = profile.accessToken,
                            sdkToken = profile.sdkToken,
                            userId = profile.userId
                        )
                    }.getOrNull()
                    if (refreshed != null) {
                        CarWidgetManager.syncFromTelemetry(appCtx, cachedModel, cachedPlate, refreshed)
                    } else {
                        CarWidgetStateStore.setTransientHint(appCtx, "")
                        CarWidgetManager.updateAllWidgets(appCtx)
                    }
                } else {
                    CarWidgetStateStore.setTransientHint(appCtx, "")
                    CarWidgetManager.updateAllWidgets(appCtx)
                    val msg = result?.resultMessage?.takeIf { it.isNotBlank() } ?: "${actionLabel}失败，请打开应用重试"
                    mainHandler.post {
                        Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun launchAppForAction(context: Context, cmd: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CarWidgetManager.EXTRA_SHORTCUT_ACTION, cmd)
        }
        runCatching { context.startActivity(intent) }
    }
}
