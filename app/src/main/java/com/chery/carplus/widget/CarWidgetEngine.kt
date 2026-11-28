package com.chery.carplus.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chery.carplus.R
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.RemoteCommand
import com.chery.carplus.cloud.VehiclePowertrain
import com.chery.carplus.cloud.VehicleTelemetry
import com.chery.carplus.profile.VehicleProfileStore
import com.chery.carplus.ui.MainActivity
import com.chery.carplus.ui.VehicleDisplayNames
import com.chery.carplus.ui.VehicleModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class CarWidgetStyle(val layoutRes: Int) {
    BAR(R.layout.widget_car_bar),
    COMPACT(R.layout.widget_car_compact),
    MEDIUM(R.layout.widget_car_medium),
    LARGE(R.layout.widget_car_large)
}

data class CarWidgetSnapshot(
    val vin: String = "",
    val modelKey: String = "arrizo8",
    val modelName: String = "Qiri+",
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
    val transientHint: String = "",
    val loggedIn: Boolean = false,
    val sessionExpired: Boolean = false
) {
    val powertrain: VehiclePowertrain
        get() = VehiclePowertrain.fromEngineTypeCode(powertrainCode)
            ?: VehiclePowertrain.resolve("", modelName, null)
}

object CarWidgetStateStore {
    private const val PREFS_NAME = "carplus_widget_state"
    private const val KEY_VIN = "vin"
    private const val KEY_MODEL_KEY = "model_key"
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
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_SESSION_EXPIRED = "session_expired"

    fun load(context: Context): CarWidgetSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val vin = prefs.getString(KEY_VIN, "").orEmpty()
        val modelName = prefs.getString(KEY_MODEL_NAME, "Qiri+").orEmpty()
            .let { if (it.isBlank() || it == "CarPlus") "Qiri+" else it }
        val savedModelKey = prefs.getString(KEY_MODEL_KEY, "").orEmpty()
        val resolvedModelKey = savedModelKey.ifBlank {
            CarWidgetManager.resolveWidgetModelKey(modelName = modelName, vin = vin)
        }
        val loggedIn = if (prefs.contains(KEY_LOGGED_IN)) {
            prefs.getBoolean(KEY_LOGGED_IN, false)
        } else {
            vin.isNotBlank()
        }
        return CarWidgetSnapshot(
            vin = vin,
            modelKey = resolvedModelKey,
            modelName = modelName,
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
            transientHint = prefs.getString(KEY_TRANSIENT_HINT, "").orEmpty(),
            loggedIn = loggedIn,
            sessionExpired = prefs.getBoolean(KEY_SESSION_EXPIRED, false)
        )
    }

    fun save(context: Context, snapshot: CarWidgetSnapshot) {
        val resolvedKey = snapshot.modelKey.trim().lowercase(Locale.US).ifBlank {
            CarWidgetManager.resolveWidgetModelKey(modelName = snapshot.modelName, vin = snapshot.vin)
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIN, snapshot.vin.trim().uppercase(Locale.US))
            .putString(KEY_MODEL_KEY, resolvedKey)
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
            .putBoolean(KEY_LOGGED_IN, snapshot.loggedIn)
            .putBoolean(KEY_SESSION_EXPIRED, snapshot.sessionExpired)
            .apply()
    }

    fun setTransientHint(context: Context, hint: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRANSIENT_HINT, hint)
            .apply()
    }

    fun markLoggedOut(context: Context, expired: Boolean = false) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean(KEY_LOGGED_IN, false)
            .putBoolean(KEY_SESSION_EXPIRED, expired)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean(KEY_LOGGED_IN, false)
            .putBoolean(KEY_SESSION_EXPIRED, false)
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
    private val COLOR_LABEL_IDLE = Color.parseColor("#8E9299")
    private val COLOR_ACTIVE = Color.parseColor("#3D9BFF")
    private val lastAutoSyncAttemptMs = AtomicLong(0L)
    private val bgSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val widgetExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarWidgetWorker").apply { isDaemon = true }
    }
    private val coalescedUpdateQueued = AtomicBoolean(false)

    private data class WidgetRenderAuthContext(
        val hasActiveToken: Boolean,
        val savedPin: String,
        val requirePinEveryTime: Boolean
    )

    private data class CachedModelBitmapEntry(
        val key: String,
        val lastModified: Long,
        val fileLength: Long,
        val bitmap: Bitmap
    )

    private val modelBitmapCacheLock = Any()
    private val widgetStateLock = Any()
    private var cachedModelBitmap: CachedModelBitmapEntry? = null

    private fun runOffMainSerialized(
        onComplete: (() -> Unit)? = null,
        block: () -> Unit
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            widgetExecutor.execute {
                try {
                    synchronized(widgetStateLock) {
                        block()
                    }
                } catch (_: Throwable) {
                    // Keep background widget worker resilient
                } finally {
                    runCatching { onComplete?.invoke() }
                }
            }
        } else {
            try {
                synchronized(widgetStateLock) {
                    block()
                }
            } finally {
                runCatching { onComplete?.invoke() }
            }
        }
    }

    internal fun isSessionStillValid(
        context: Context,
        expectedVin: String,
        expectedToken: String
    ): Boolean {
        val appCtx = context.applicationContext
        return synchronized(widgetStateLock) {
            isSessionStillValidLocked(appCtx, expectedVin, expectedToken)
        }
    }

    private fun isSessionStillValidLocked(
        appCtx: Context,
        expectedVin: String,
        expectedToken: String
    ): Boolean {
        if (expectedVin.isBlank() || expectedToken.isBlank()) return false
        val latest = runCatching { VehicleProfileStore(appCtx).getActiveProfile() }.getOrNull()
            ?: return false
        if (!latest.vin.equals(expectedVin, ignoreCase = true)) return false
        if (latest.accessToken.isBlank() || latest.accessToken != expectedToken) return false
        return !CarWidgetStateStore.load(appCtx).sessionExpired
    }

    internal fun setTransientHintAndUpdate(
        context: Context,
        hint: String,
        expectedVin: String? = null,
        expectedToken: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        runOffMainSerialized(onComplete = onComplete) {
            if (expectedVin != null && expectedToken != null &&
                !isSessionStillValidLocked(appCtx, expectedVin, expectedToken)
            ) {
                return@runOffMainSerialized
            }
            CarWidgetStateStore.setTransientHint(appCtx, hint)
            updateAllWidgetsNow(appCtx)
        }
    }

    internal fun saveOptimisticAndUpdate(
        context: Context,
        snapshot: CarWidgetSnapshot,
        expectedVin: String,
        expectedToken: String
    ): Boolean {
        val appCtx = context.applicationContext
        return synchronized(widgetStateLock) {
            if (!isSessionStillValidLocked(appCtx, expectedVin, expectedToken)) {
                return@synchronized false
            }
            CarWidgetStateStore.save(appCtx, snapshot)
            updateAllWidgetsNow(appCtx)
            true
        }
    }

    private fun loadRenderAuthContext(context: Context): WidgetRenderAuthContext {
        val appCtx = context.applicationContext
        val profile = runCatching { VehicleProfileStore(appCtx).getActiveProfile() }.getOrNull()
        val uiPrefs = appCtx.getSharedPreferences("car_plus_consumer_ui", Context.MODE_PRIVATE)
        return WidgetRenderAuthContext(
            hasActiveToken = profile != null && profile.accessToken.isNotBlank(),
            savedPin = profile?.controlPin?.trim().orEmpty(),
            requirePinEveryTime = uiPrefs.getBoolean("require_pin_every_time", true)
        )
    }

    fun resolveWidgetModelKey(
        modelName: String,
        vin: String = "",
        explicitKey: String = ""
    ): String {
        val explicit = explicitKey.trim().lowercase(Locale.US)
        if (explicit.isNotBlank()) return explicit
        val cleanName = modelName.trim()
        if (cleanName.isNotBlank() && !cleanName.equals("Qiri+", ignoreCase = true)) {
            val resolved = VehicleModelRepository.resolveModelKey(cleanName)
            if (resolved.isNotBlank()) return resolved
        }
        val cleanVin = vin.trim()
        if (cleanVin.isNotBlank()) {
            val resolved = VehicleModelRepository.resolveModelKey(cleanVin)
            if (resolved.isNotBlank()) return resolved
        }
        val lower = cleanName.lowercase(Locale.US)
        return when {
            lower.contains("瑞虎") || lower.contains("tiggo") -> "tiggo8"
            else -> "arrizo8"
        }
    }

    /**
     * Immediately switches all desktop widgets to the logged-out or session-expired state,
     * hiding all vehicle identity, telemetry, and remote controls.
     */
    fun markLoggedOut(
        context: Context,
        expired: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        runOffMainSerialized(onComplete = onComplete) {
            CarWidgetStateStore.markLoggedOut(appCtx, expired = expired)
            updateAllWidgetsNow(appCtx)
        }
    }

    /**
     * Immediately synchronizes the active vehicle identity & 3D-matched widget render
     * whenever the user logs in or switches vehicles in-app, clearing stale telemetry
     * from a previous VIN if [telemetry] is not yet available.
     */
    fun syncActiveVehicle(
        context: Context,
        vin: String,
        modelName: String,
        plateNo: String,
        modelKey: String = "",
        engineType: String = "",
        telemetry: VehicleTelemetry? = null,
        cabinTempC: Double? = null,
        primaryStatus: String? = null
    ) {
        val appCtx = context.applicationContext
        runOffMainSerialized {
            val rawCurrent = CarWidgetStateStore.load(appCtx)
            val normalizedVin = vin.trim().uppercase(Locale.US).ifBlank {
                telemetry?.vin?.trim()?.uppercase(Locale.US).orEmpty()
            }.ifBlank {
                VehicleProfileStore(appCtx).getActiveProfile().vin.trim().uppercase(Locale.US)
            }
            val sameVehicle = normalizedVin.isNotBlank() &&
                rawCurrent.vin.equals(normalizedVin, ignoreCase = true)
            val base = if (sameVehicle) rawCurrent else CarWidgetSnapshot(vin = normalizedVin)

            val uiPrefs = appCtx.getSharedPreferences("car_plus_consumer_ui", Context.MODE_PRIVATE)
            val effectiveEngineType = engineType.ifBlank { uiPrefs.getString("engineType", "").orEmpty() }
            val resolvedModelName = modelName.trim().ifBlank { base.modelName }
            val resolvedKey = resolveWidgetModelKey(
                modelName = resolvedModelName,
                vin = normalizedVin,
                explicitKey = modelKey
            )
            val powertrain = VehiclePowertrain.resolve(effectiveEngineType, resolvedModelName, telemetry)
            val resolvedPlate = plateNo.trim().ifBlank {
                telemetry?.licensePlate.orEmpty().trim().ifBlank { if (sameVehicle) base.plateNo else "" }
            }

            if (telemetry != null) {
                syncFromTelemetryNow(
                    context = appCtx,
                    modelName = resolvedModelName,
                    plateNo = resolvedPlate,
                    telemetry = telemetry,
                    cabinTempC = cabinTempC,
                    primaryStatus = primaryStatus,
                    modelKey = resolvedKey
                )
                return@runOffMainSerialized
            }

            val next = base.copy(
                vin = normalizedVin,
                modelKey = resolvedKey,
                modelName = resolvedModelName,
                plateNo = resolvedPlate,
                powertrainCode = powertrain.code,
                primaryStatus = primaryStatus ?: if (sameVehicle) base.primaryStatus else "状态待同步",
                transientHint = "",
                loggedIn = true,
                sessionExpired = false
            )
            CarWidgetStateStore.save(appCtx, next)
            updateAllWidgetsNow(appCtx)
        }
    }

    /**
     * Silently refreshes cloud vehicle telemetry when the system triggers periodic
     * widget `onUpdate` (every 30 min) and the cached snapshot is stale (>= 15 min).
     */
    fun triggerBackgroundSyncIfStale(
        context: Context,
        minStaleMs: Long = 15L * 60L * 1000L,
        pendingResult: BroadcastReceiver.PendingResult? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult?.finish() }
                runCatching { onComplete?.invoke() }
            }
        }
        var syncJob: kotlinx.coroutines.Job? = null
        val watchdog = Runnable {
            syncJob?.cancel()
            finishOnce()
        }
        if (pendingResult != null || onComplete != null) {
            mainHandler.postDelayed(watchdog, 7_000L)
        }

        syncJob = bgSyncScope.launch {
            try {
                val store = VehicleProfileStore(appCtx)
                val profile = store.getActiveProfile()
                if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
                    return@launch
                }

                val forceSync = minStaleMs <= 0L
                val now = System.currentTimeMillis()
                val snap = CarWidgetStateStore.load(appCtx)
                if (!forceSync && snap.loggedIn && snap.updatedAtMs > 0L && now - snap.updatedAtMs < minStaleMs) {
                    return@launch
                }

                if (forceSync) {
                    lastAutoSyncAttemptMs.set(now)
                } else {
                    val prevAttempt = lastAutoSyncAttemptMs.get()
                    if (now - prevAttempt < 60_000L || !lastAutoSyncAttemptMs.compareAndSet(prevAttempt, now)) {
                        return@launch
                    }
                }

                kotlinx.coroutines.withTimeoutOrNull(6_500L) {
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
                            markLoggedOut(appCtx, expired = true)
                        }
                    }.getOrNull() ?: return@withTimeoutOrNull

                    if (!isSessionStillValid(appCtx, profile.vin, profile.accessToken)) {
                        return@withTimeoutOrNull
                    }
                    val uiPrefs = appCtx.getSharedPreferences("car_plus_consumer_ui", Context.MODE_PRIVATE)
                    val cachedModel = VehicleDisplayNames.normalizeCachedName(
                        uiPrefs.getString("modelName", snap.modelName).orEmpty(),
                        uiPrefs.getString("plateNo", snap.plateNo).orEmpty()
                    )
                    val cachedPlate = uiPrefs.getString("plateNo", snap.plateNo).orEmpty()
                    synchronized(widgetStateLock) {
                        syncFromTelemetryNow(
                            context = appCtx,
                            modelName = cachedModel,
                            plateNo = cachedPlate,
                            telemetry = telemetry,
                            expectedVin = profile.vin,
                            expectedToken = profile.accessToken
                        )
                    }
                }
            } finally {
                mainHandler.removeCallbacks(watchdog)
                finishOnce()
            }
        }
    }

    fun syncFromTelemetry(
        context: Context,
        modelName: String,
        plateNo: String,
        telemetry: VehicleTelemetry?,
        cabinTempC: Double? = null,
        primaryStatus: String? = null,
        modelKey: String = "",
        expectedVin: String? = null,
        expectedToken: String? = null
    ) {
        val appCtx = context.applicationContext
        runOffMainSerialized {
            syncFromTelemetryNow(
                context = appCtx,
                modelName = modelName,
                plateNo = plateNo,
                telemetry = telemetry,
                cabinTempC = cabinTempC,
                primaryStatus = primaryStatus,
                modelKey = modelKey,
                expectedVin = expectedVin,
                expectedToken = expectedToken
            )
        }
    }

    private fun syncFromTelemetryNow(
        context: Context,
        modelName: String,
        plateNo: String,
        telemetry: VehicleTelemetry?,
        cabinTempC: Double? = null,
        primaryStatus: String? = null,
        modelKey: String = "",
        expectedVin: String? = null,
        expectedToken: String? = null
    ) {
        val appCtx = context.applicationContext
        if (expectedVin != null && expectedToken != null) {
            if (!isSessionStillValidLocked(appCtx, expectedVin, expectedToken)) return
        } else {
            val active = runCatching { VehicleProfileStore(appCtx).getActiveProfile() }.getOrNull()
            if (active == null || active.accessToken.isBlank()) return
        }
        val rawCurrent = CarWidgetStateStore.load(appCtx)
        val targetVin = telemetry?.vin?.trim()?.uppercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: VehicleProfileStore(appCtx).getActiveProfile().vin.trim().uppercase(Locale.US)
        val sameVehicle = targetVin.isBlank() ||
            rawCurrent.vin.isBlank() ||
            rawCurrent.vin.equals(targetVin, ignoreCase = true)
        val current = if (sameVehicle) rawCurrent else CarWidgetSnapshot(vin = targetVin)

        val uiPrefs = appCtx.getSharedPreferences("car_plus_consumer_ui", Context.MODE_PRIVATE)
        val cachedEngineType = uiPrefs.getString("engineType", "").orEmpty()
        val resolvedModel = modelName.ifBlank { current.modelName }
        val resolvedKey = resolveWidgetModelKey(
            modelName = resolvedModel,
            vin = targetVin.ifBlank { current.vin },
            explicitKey = modelKey
        )
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
            modelKey = resolvedKey,
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
            transientHint = "",
            loggedIn = true,
            sessionExpired = false
        )
        CarWidgetStateStore.save(appCtx, next)
        updateAllWidgetsNow(appCtx)
    }

    fun updateAllWidgets(
        context: Context,
        onComplete: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (onComplete != null) {
                widgetExecutor.execute {
                    try {
                        updateAllWidgetsNow(appContext)
                    } catch (_: Throwable) {
                        // Keep widget update resilient
                    } finally {
                        runCatching { onComplete.invoke() }
                    }
                }
            } else if (coalescedUpdateQueued.compareAndSet(false, true)) {
                widgetExecutor.execute {
                    coalescedUpdateQueued.set(false)
                    runCatching { updateAllWidgetsNow(appContext) }
                }
            }
            return
        }
        try {
            updateAllWidgetsNow(appContext)
        } finally {
            runCatching { onComplete?.invoke() }
        }
    }

    private fun updateAllWidgetsNow(context: Context) {
        synchronized(widgetStateLock) {
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext) ?: return
            val barIds = mgr.getAppWidgetIds(ComponentName(appContext, CarWidgetBarProvider::class.java))
            val compactIds = mgr.getAppWidgetIds(ComponentName(appContext, CarWidgetCompactProvider::class.java))
            val mediumIds = mgr.getAppWidgetIds(ComponentName(appContext, CarWidgetMediumProvider::class.java))
            val largeIds = mgr.getAppWidgetIds(ComponentName(appContext, CarWidgetLargeProvider::class.java))
            if (barIds.isEmpty() && compactIds.isEmpty() && mediumIds.isEmpty() && largeIds.isEmpty()) {
                return
            }
            val snapshot = CarWidgetStateStore.load(appContext)
            val authCtx = loadRenderAuthContext(appContext)
            updateProviderIds(appContext, mgr, barIds, CarWidgetStyle.BAR, snapshot, authCtx)
            updateProviderIds(appContext, mgr, compactIds, CarWidgetStyle.COMPACT, snapshot, authCtx)
            updateProviderIds(appContext, mgr, mediumIds, CarWidgetStyle.MEDIUM, snapshot, authCtx)
            updateProviderIds(appContext, mgr, largeIds, CarWidgetStyle.LARGE, snapshot, authCtx)
        }
    }

    fun handleProviderUpdate(
        context: Context,
        mgr: AppWidgetManager,
        widgetIds: IntArray,
        style: CarWidgetStyle,
        pendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        val appCtx = context.applicationContext
        val idsCopy = widgetIds.copyOf()
        val mainHandler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult?.finish() }
            }
        }
        val watchdog = Runnable { finishOnce() }
        if (pendingResult != null) {
            mainHandler.postDelayed(watchdog, 7_000L)
        }
        widgetExecutor.execute {
            try {
                if (idsCopy.isNotEmpty()) {
                    synchronized(widgetStateLock) {
                        val snapshot = CarWidgetStateStore.load(appCtx)
                        val authCtx = loadRenderAuthContext(appCtx)
                        updateProviderIds(appCtx, mgr, idsCopy, style, snapshot, authCtx)
                    }
                }
            } catch (_: Throwable) {
                // Keep provider update resilient
            }
            // Hand off the remaining budget to background stale-telemetry sync (or finish immediately if fresh)
            triggerBackgroundSyncIfStale(
                context = appCtx,
                pendingResult = null,
                onComplete = {
                    mainHandler.removeCallbacks(watchdog)
                    finishOnce()
                }
            )
        }
    }

    fun updateWidgetsForStyle(
        context: Context,
        mgr: AppWidgetManager,
        widgetIds: IntArray,
        style: CarWidgetStyle,
        pendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        if (widgetIds.isEmpty()) {
            runCatching { pendingResult?.finish() }
            return
        }
        val appCtx = context.applicationContext
        val idsCopy = widgetIds.copyOf()
        val mainHandler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult?.finish() }
            }
        }
        val watchdog = Runnable { finishOnce() }
        if (pendingResult != null) {
            mainHandler.postDelayed(watchdog, 7_000L)
        }
        runOffMainSerialized {
            try {
                val snapshot = CarWidgetStateStore.load(appCtx)
                val authCtx = loadRenderAuthContext(appCtx)
                updateProviderIds(appCtx, mgr, idsCopy, style, snapshot, authCtx)
            } finally {
                mainHandler.removeCallbacks(watchdog)
                finishOnce()
            }
        }
    }

    private fun updateProviderIds(
        context: Context,
        mgr: AppWidgetManager,
        ids: IntArray,
        style: CarWidgetStyle,
        snapshot: CarWidgetSnapshot,
        authCtx: WidgetRenderAuthContext
    ) {
        if (ids.isEmpty()) return
        val views = buildRemoteViews(context, style, snapshot, authCtx)
        for (id in ids) {
            mgr.updateAppWidget(id, views)
        }
    }

    private fun formatRangeText(s: CarWidgetSnapshot): String {
        val km = when (s.powertrain) {
            VehiclePowertrain.EV -> if (s.electricRangeKm > 0) s.electricRangeKm else s.rangeKm
            VehiclePowertrain.PHEV, VehiclePowertrain.ICE -> if (s.rangeKm > 0) s.rangeKm else s.electricRangeKm
        }
        return if (km > 0) "$km km" else "-- km"
    }

    private fun rangeLabelForPowertrain(powertrain: VehiclePowertrain): String = when (powertrain) {
        VehiclePowertrain.EV -> "纯电续航"
        VehiclePowertrain.PHEV -> "综合续航"
        VehiclePowertrain.ICE -> "剩余续航"
    }

    private fun loadOptionalCachedModelBitmap(context: Context, modelKey: String): Bitmap? {
        val cleanKey = modelKey.trim().lowercase(Locale.US)
        if (cleanKey.isBlank()) return null
        val file = File(File(context.applicationContext.filesDir, "widget_models"), "$cleanKey.png")
        val fileLen = if (file.exists()) file.length() else 0L
        if (fileLen <= 0L) return null
        val mtime = file.lastModified()
        synchronized(modelBitmapCacheLock) {
            val cached = cachedModelBitmap
            if (cached != null &&
                cached.key == cleanKey &&
                cached.lastModified == mtime &&
                cached.fileLength == fileLen &&
                !cached.bitmap.isRecycled
            ) {
                return cached.bitmap
            }
        }
        val decodedResult = runCatching {
            val maxDimPx = 480
            val maxBytes = 350 * 1024
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDim > 0 && maxDim / sample > maxDimPx) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
            val w = decoded.width
            val h = decoded.height
            val bytes = decoded.allocationByteCount
            if (w <= maxDimPx && h <= maxDimPx && bytes <= maxBytes) {
                decoded
            } else {
                val dimScale = if (maxOf(w, h) > maxDimPx) maxDimPx.toFloat() / maxOf(w, h).toFloat() else 1f
                val byteScale = if (bytes > maxBytes) sqrt(maxBytes.toDouble() / bytes.toDouble()).toFloat() else 1f
                val scale = minOf(dimScale, byteScale).coerceIn(0.1f, 1f)
                val targetW = (w * scale).roundToInt().coerceAtLeast(1)
                val targetH = (h * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            }
        }.getOrNull() ?: return null

        synchronized(modelBitmapCacheLock) {
            cachedModelBitmap = CachedModelBitmapEntry(cleanKey, mtime, fileLen, decodedResult)
        }
        return decodedResult
    }

    private fun bindVehicleImage(context: Context, rv: RemoteViews, modelKey: String) {
        val cached = loadOptionalCachedModelBitmap(context, modelKey)
        if (cached != null) {
            rv.setImageViewBitmap(R.id.widget_car_image, cached)
            return
        }
        val resId = when (modelKey.trim().lowercase(Locale.US)) {
            "tiggo8" -> R.drawable.widget_car_tiggo8
            else -> R.drawable.widget_car_arrizo8
        }
        rv.setImageViewResource(R.id.widget_car_image, resId)
    }

    private fun buildRemoteViews(
        context: Context,
        style: CarWidgetStyle,
        s: CarWidgetSnapshot,
        authCtx: WidgetRenderAuthContext
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, style.layoutRes)
        val openAppPi = buildOpenAppPendingIntent(context)

        // Clean logged-out / session-expired state across all 4 widget sizes
        if (!s.loggedIn || !authCtx.hasActiveToken) {
            rv.setViewVisibility(R.id.widget_content_container, View.GONE)
            rv.setViewVisibility(R.id.widget_logged_out_container, View.VISIBLE)
            val expired = s.sessionExpired
            val titleText = when (style) {
                CarWidgetStyle.BAR -> if (expired) "Qiri+ · 登录已失效" else "Qiri+ · 未登录"
                else -> if (expired) "登录已失效" else "未登录"
            }
            val subtitleText = when (style) {
                CarWidgetStyle.COMPACT -> if (expired) "轻触重新登录" else "轻触登录查看车况"
                CarWidgetStyle.BAR -> if (expired) "轻触重新登录恢复同步" else "轻触登录查看车况与远控"
                CarWidgetStyle.MEDIUM -> if (expired) "账号凭证已过期，请重新登录" else "登录账号同步实时车况与远控"
                CarWidgetStyle.LARGE -> if (expired) "账号凭证已过期，请重新登录 Qiri+" else "登录 Qiri+ 同步实时车况与远程控车"
            }
            val ctaText = if (expired) "重新登录" else "去登录"
            rv.setTextViewText(R.id.widget_logged_out_title, titleText)
            rv.setTextViewText(R.id.widget_logged_out_subtitle, subtitleText)
            rv.setTextViewText(R.id.widget_btn_login, ctaText)
            rv.setOnClickPendingIntent(R.id.widget_logged_out_container, openAppPi)
            rv.setOnClickPendingIntent(R.id.widget_btn_login, openAppPi)
            return rv
        }

        rv.setViewVisibility(R.id.widget_content_container, View.VISIBLE)
        rv.setViewVisibility(R.id.widget_logged_out_container, View.GONE)

        // Tap header or vehicle image to launch Qiri+ Home
        rv.setOnClickPendingIntent(R.id.widget_header_area, openAppPi)
        if (style != CarWidgetStyle.BAR) {
            bindVehicleImage(context, rv, s.modelKey)
            rv.setOnClickPendingIntent(R.id.widget_car_image, openAppPi)
        }

        // Model Name
        rv.setTextViewText(R.id.widget_model_name, s.modelName)

        // Range-only display (strictly zero % across all 4 widget styles)
        val rangeStr = formatRangeText(s)
        val statusText = if (s.transientHint.isNotBlank()) {
            s.transientHint
        } else {
            when (style) {
                CarWidgetStyle.BAR, CarWidgetStyle.COMPACT -> "${s.primaryStatus} · $rangeStr"
                CarWidgetStyle.MEDIUM, CarWidgetStyle.LARGE -> s.primaryStatus
            }
        }
        rv.setTextViewText(R.id.widget_status_line, statusText)

        // Exact sync timestamp beside the Refresh button (MEDIUM & LARGE)
        if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
            val timeLabel = if (s.transientHint.startsWith("正在刷新")) {
                "刷新中..."
            } else {
                formatSyncTimestamp(s.updatedAtMs)
            }
            rv.setTextViewText(R.id.widget_update_time, timeLabel)

            if (s.plateNo.isNotBlank()) {
                rv.setViewVisibility(R.id.widget_plate_badge, View.VISIBLE)
                rv.setTextViewText(R.id.widget_plate_badge, s.plateNo)
            } else {
                rv.setViewVisibility(R.id.widget_plate_badge, View.GONE)
            }

            rv.setTextViewText(R.id.widget_fuel_label, rangeLabelForPowertrain(s.powertrain))
            rv.setTextViewText(R.id.widget_fuel_text, rangeStr)
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

        // Android 10-15 BAL compliance: when PIN verification or PIN caching is required,
        // bind remote control buttons directly to PendingIntent.getActivity instead of a
        // BroadcastReceiver trampoline (which Android BAL blocks when backgrounded).
        val requirePinEveryTime = authCtx.requirePinEveryTime
        val savedPin = authCtx.savedPin
        val launchAppForControl = requirePinEveryTime || savedPin.length != 6
        val needPinCache = !requirePinEveryTime && savedPin.length != 6

        // Control 1: Lock / Unlock (frameless; circular halo only when active)
        val unlocked = !s.doorLocked
        rv.setImageViewResource(
            R.id.widget_icon_lock,
            if (unlocked) R.drawable.ic_widget_lock_open else R.drawable.ic_widget_lock_closed
        )
        rv.setInt(R.id.widget_icon_lock, "setColorFilter", if (unlocked) COLOR_ACTIVE else COLOR_WHITE)
        rv.setInt(
            R.id.widget_halo_lock,
            "setBackgroundResource",
            if (unlocked) R.drawable.widget_icon_halo_active else 0
        )
        rv.setOnClickPendingIntent(
            R.id.widget_btn_lock,
            buildControlPendingIntent(context, CMD_TOGGLE_LOCK, 101, launchAppForControl, needPinCache)
        )
        if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
            rv.setTextViewText(R.id.widget_label_lock, if (unlocked) "锁车" else "解锁")
            rv.setTextColor(R.id.widget_label_lock, if (unlocked) COLOR_ACTIVE else COLOR_LABEL_IDLE)
        }

        // Control 2: Climate AC (frameless; circular halo only when active)
        rv.setInt(R.id.widget_icon_ac, "setColorFilter", if (s.acOn) COLOR_ACTIVE else COLOR_WHITE)
        rv.setInt(
            R.id.widget_halo_ac,
            "setBackgroundResource",
            if (s.acOn) R.drawable.widget_icon_halo_active else 0
        )
        rv.setOnClickPendingIntent(
            R.id.widget_btn_ac,
            buildControlPendingIntent(context, CMD_TOGGLE_AC, 102, launchAppForControl, needPinCache)
        )
        if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
            rv.setTextViewText(R.id.widget_label_ac, if (s.acOn) "关空调" else "空调")
            rv.setTextColor(R.id.widget_label_ac, if (s.acOn) COLOR_ACTIVE else COLOR_LABEL_IDLE)
        }

        // Controls 3 & 4: Find Car + Trunk (BAR, MEDIUM, LARGE)
        if (style != CarWidgetStyle.COMPACT) {
            rv.setInt(R.id.widget_icon_flash, "setColorFilter", COLOR_WHITE)
            rv.setInt(R.id.widget_halo_flash, "setBackgroundResource", 0)
            rv.setOnClickPendingIntent(
                R.id.widget_btn_flash,
                buildControlPendingIntent(context, CMD_FIND_CAR, 103, launchAppForControl, needPinCache)
            )

            rv.setInt(R.id.widget_icon_trunk, "setColorFilter", if (s.trunkOpen) COLOR_ACTIVE else COLOR_WHITE)
            rv.setInt(
                R.id.widget_halo_trunk,
                "setBackgroundResource",
                if (s.trunkOpen) R.drawable.widget_icon_halo_active else 0
            )
            rv.setOnClickPendingIntent(
                R.id.widget_btn_trunk,
                buildControlPendingIntent(context, CMD_TOGGLE_TRUNK, 104, launchAppForControl, needPinCache)
            )
            if (style == CarWidgetStyle.MEDIUM || style == CarWidgetStyle.LARGE) {
                rv.setTextViewText(R.id.widget_label_trunk, if (s.trunkOpen) "关尾箱" else "后备箱")
                rv.setTextColor(R.id.widget_label_trunk, if (s.trunkOpen) COLOR_ACTIVE else COLOR_LABEL_IDLE)
            }
        }

        // Control 5: Engine Start/Stop (LARGE)
        if (style == CarWidgetStyle.LARGE) {
            rv.setInt(R.id.widget_icon_engine, "setColorFilter", if (s.engineRunning) COLOR_ACTIVE else COLOR_WHITE)
            rv.setInt(
                R.id.widget_halo_engine,
                "setBackgroundResource",
                if (s.engineRunning) R.drawable.widget_icon_halo_active else 0
            )
            rv.setOnClickPendingIntent(
                R.id.widget_btn_engine,
                buildControlPendingIntent(context, CMD_TOGGLE_ENGINE, 105, launchAppForControl, needPinCache)
            )
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

    private fun buildControlPendingIntent(
        context: Context,
        cmd: String,
        requestCode: Int,
        launchAppDirectly: Boolean,
        needPinCache: Boolean
    ): PendingIntent {
        return if (launchAppDirectly) {
            buildLaunchActionPendingIntent(context, cmd, requestCode + 200, needPinCache)
        } else {
            buildActionPendingIntent(context, cmd, requestCode)
        }
    }

    internal fun buildLaunchActionPendingIntent(
        context: Context,
        cmd: String,
        requestCode: Int,
        needPinCache: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "$ACTION_WIDGET_CMD.$cmd"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHORTCUT_ACTION, cmd)
            putExtra(EXTRA_NEED_PIN_CACHE, needPinCache)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    internal fun postActionPromptNotification(
        context: Context,
        title: String,
        message: String,
        cmd: String = "",
        needPinCache: Boolean = false,
        openAppOnly: Boolean = false
    ) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "widget_action_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(channelId)
            if (existing == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "小组件控车提示",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "小组件控车需要验证 PIN 或重新登录时的快捷跳转提示"
                    }
                )
            }
        }
        val contentPi = if (openAppOnly || cmd.isBlank()) {
            buildOpenAppPendingIntent(appCtx)
        } else {
            val reqCode = 400 + (cmd.hashCode() and 0xFF)
            buildLaunchActionPendingIntent(appCtx, cmd, reqCode, needPinCache)
        }
        val notification = NotificationCompat.Builder(appCtx, channelId)
            .setSmallIcon(R.drawable.ic_widget_lock_closed)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
        runCatching { nm.notify(4201, notification) }
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

    private fun runShortAsyncWidgetTask(task: (finish: () -> Unit) -> Unit) {
        val pendingResult = goAsync()
        val mainHandler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val watchdog = Runnable {
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult.finish() }
            }
        }
        fun finishOnce() {
            mainHandler.removeCallbacks(watchdog)
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult.finish() }
            }
        }
        mainHandler.postDelayed(watchdog, 7_000L)
        task(::finishOnce)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CarWidgetManager.ACTION_WIDGET_CMD) return
        val cmd = intent.getStringExtra(CarWidgetManager.EXTRA_CMD).orEmpty()
        if (cmd.isBlank()) return

        val appCtx = context.applicationContext
        val store = VehicleProfileStore(appCtx)
        val profile = store.getActiveProfile()

        if (profile.vin.isBlank() || profile.accessToken.isBlank()) {
            CarWidgetManager.postActionPromptNotification(
                context = appCtx,
                title = "请先登录 Qiri+",
                message = "轻触打开应用登录账号后使用远程控车",
                openAppOnly = true
            )
            runShortAsyncWidgetTask { finish ->
                CarWidgetManager.markLoggedOut(appCtx, expired = false, onComplete = finish)
            }
            return
        }

        val uiPrefs = appCtx.getSharedPreferences("car_plus_consumer_ui", Context.MODE_PRIVATE)
        val requirePinEveryTime = uiPrefs.getBoolean("require_pin_every_time", true)
        val savedPin = profile.controlPin.trim()
        val needsPin = cmd != CarWidgetManager.CMD_REFRESH
        if (needsPin && (requirePinEveryTime || savedPin.length != 6)) {
            val needPinCache = !requirePinEveryTime && savedPin.length != 6
            // Rebind widget buttons directly to PendingIntent.getActivity so next tap opens app without BAL block
            CarWidgetManager.postActionPromptNotification(
                context = appCtx,
                title = "需要验证 PIN 码",
                message = if (needPinCache) "本机尚未缓存 PIN 码，轻触验证一次后即可小组件免密控车" else "轻触打开 Qiri+ 完成 6 位 PIN 码验证",
                cmd = cmd,
                needPinCache = needPinCache
            )
            if (needPinCache) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        appCtx,
                        "本机尚未缓存 PIN 码，请点击通知或再次点击小组件验证",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            runShortAsyncWidgetTask { finish ->
                CarWidgetManager.setTransientHintAndUpdate(
                    context = appCtx,
                    hint = if (needPinCache) "请打开应用验证 PIN" else "轻触再次验证 PIN",
                    onComplete = finish
                )
            }
            return
        }

        val pendingResult = goAsync()
        val mainHandler = Handler(Looper.getMainLooper())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finished = AtomicBoolean(false)
        fun finishReceiverOnce() {
            if (finished.compareAndSet(false, true)) {
                runCatching { pendingResult.finish() }
            }
        }
        var actionJob: kotlinx.coroutines.Job? = null
        val watchdog = Runnable {
            actionJob?.cancel()
            finishReceiverOnce()
        }
        mainHandler.postDelayed(watchdog, 7_000L)

        actionJob = scope.launch {
            var commandSucceeded = false
            try {
                val completed = kotlinx.coroutines.withTimeoutOrNull(6_500L) {
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
                        CarWidgetManager.setTransientHintAndUpdate(
                            context = appCtx,
                            hint = "正在刷新车况...",
                            expectedVin = profile.vin,
                            expectedToken = profile.accessToken
                        )
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
                                CarWidgetManager.markLoggedOut(appCtx, expired = true)
                                CarWidgetManager.postActionPromptNotification(
                                    context = appCtx,
                                    title = "Qiri+ 登录已失效",
                                    message = "账号凭证已过期，轻触打开应用重新登录",
                                    openAppOnly = true
                                )
                                mainHandler.post {
                                    Toast.makeText(appCtx, "登录已失效，请打开应用重新登录", Toast.LENGTH_SHORT).show()
                                }
                            }
                            return@withTimeoutOrNull
                        }
                        if (telemetry != null &&
                            CarWidgetManager.isSessionStillValid(appCtx, profile.vin, profile.accessToken)
                        ) {
                            CarWidgetManager.syncFromTelemetry(
                                context = appCtx,
                                modelName = cachedModel,
                                plateNo = cachedPlate,
                                telemetry = telemetry,
                                expectedVin = profile.vin,
                                expectedToken = profile.accessToken
                            )
                        } else if (telemetry == null) {
                            CarWidgetManager.setTransientHintAndUpdate(
                                context = appCtx,
                                hint = "",
                                expectedVin = profile.vin,
                                expectedToken = profile.accessToken
                            )
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
                            else Triple(
                                RemoteCommand.AC_ON,
                                "开启空调",
                                mapOf(
                                    "temperature" to CheryHttpClient.formatAcTemperatureParam(24.0),
                                    "airType" to airType
                                )
                            )
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

                    CarWidgetManager.setTransientHintAndUpdate(
                        context = appCtx,
                        hint = "正在${actionLabel}...",
                        expectedVin = profile.vin,
                        expectedToken = profile.accessToken
                    )

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
                            CarWidgetManager.markLoggedOut(appCtx, expired = true)
                            CarWidgetManager.postActionPromptNotification(
                                context = appCtx,
                                title = "Qiri+ 登录已失效",
                                message = "账号凭证已过期，轻触打开应用重新登录",
                                openAppOnly = true
                            )
                            mainHandler.post {
                                Toast.makeText(appCtx, "登录已失效，请打开应用重新登录", Toast.LENGTH_SHORT).show()
                            }
                        }
                        return@withTimeoutOrNull
                    }

                    if (result?.isPinError == true) {
                        store.updateControlPin(profile.vin, "")
                        CarWidgetManager.setTransientHintAndUpdate(
                            context = appCtx,
                            hint = "PIN 已失效，请重验",
                            expectedVin = profile.vin,
                            expectedToken = profile.accessToken
                        )
                        CarWidgetManager.postActionPromptNotification(
                            context = appCtx,
                            title = "PIN 码验证失效",
                            message = "本机缓存的 PIN 码已失效，轻触打开应用重新验证",
                            cmd = cmd,
                            needPinCache = true
                        )
                        mainHandler.post {
                            Toast.makeText(appCtx, "本机缓存的 PIN 码已失效，请重新验证", Toast.LENGTH_SHORT).show()
                        }
                        return@withTimeoutOrNull
                    }

                    if (result?.success == true &&
                        CarWidgetManager.isSessionStillValid(appCtx, profile.vin, profile.accessToken)
                    ) {
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
                        if (!CarWidgetManager.saveOptimisticAndUpdate(appCtx, optimistic, profile.vin, profile.accessToken)) {
                            return@withTimeoutOrNull
                        }

                        delay(1200L)
                        var postRefreshExpired = false
                        val refreshed = try {
                            client.fetchVehicleStatus(
                                vin = profile.vin,
                                accessToken = profile.accessToken,
                                sdkToken = profile.sdkToken,
                                userId = profile.userId
                            )
                        } catch (_: com.chery.carplus.cloud.CherySessionExpiredException) {
                            postRefreshExpired = true
                            null
                        } catch (_: Throwable) {
                            null
                        }
                        if (postRefreshExpired) {
                            if (store.getActiveProfile().accessToken == profile.accessToken) {
                                store.clearTokensPreserveOfflineKeys()
                                CarWidgetManager.markLoggedOut(appCtx, expired = true)
                            }
                            return@withTimeoutOrNull
                        }
                        if (refreshed != null &&
                            CarWidgetManager.isSessionStillValid(appCtx, profile.vin, profile.accessToken)
                        ) {
                            CarWidgetManager.syncFromTelemetry(
                                context = appCtx,
                                modelName = cachedModel,
                                plateNo = cachedPlate,
                                telemetry = refreshed,
                                expectedVin = profile.vin,
                                expectedToken = profile.accessToken
                            )
                        } else {
                            CarWidgetManager.setTransientHintAndUpdate(
                                context = appCtx,
                                hint = "",
                                expectedVin = profile.vin,
                                expectedToken = profile.accessToken
                            )
                        }
                    } else if (result?.success != true) {
                        CarWidgetManager.setTransientHintAndUpdate(
                            context = appCtx,
                            hint = "",
                            expectedVin = profile.vin,
                            expectedToken = profile.accessToken
                        )
                        val msg = result?.resultMessage?.takeIf { it.isNotBlank() } ?: "${actionLabel}失败，请打开应用重试"
                        mainHandler.post {
                            Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        CarWidgetManager.setTransientHintAndUpdate(
                            context = appCtx,
                            hint = "",
                            expectedVin = profile.vin,
                            expectedToken = profile.accessToken
                        )
                    }
                }
                if (completed == null) {
                    CarWidgetManager.setTransientHintAndUpdate(
                        context = appCtx,
                        hint = "",
                        expectedVin = profile.vin,
                        expectedToken = profile.accessToken
                    )
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
}
