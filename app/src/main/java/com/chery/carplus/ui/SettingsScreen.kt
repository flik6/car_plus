package com.chery.carplus.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import com.chery.carplus.R
import com.chery.carplus.cloud.AppUpdater
import com.chery.carplus.cloud.CheryHttpClient
import com.chery.carplus.cloud.CheryUserInfo
import com.chery.carplus.cloud.InstallApkResult
import com.chery.carplus.cloud.UpdateCheckResult
import com.chery.carplus.profile.VehicleProfile
import com.chery.carplus.profile.VehicleProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen minimalist dark Settings experience unified onto [DesignComponents]
 * & [ThemeTokens] (DESIGN_SYSTEM.md v2.1).
 */
class SettingsScreen(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val profileProvider: () -> VehicleProfile,
    private val modelNameProvider: () -> String,
    private val plateNoProvider: () -> String,
    private val onBackToHome: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    private val onOpenVehicleSwitcher: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER")
    private val onOpenVehicleUpgrade: () -> Unit = {},
    private val onOpenChangePassword: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    private val onOpenPinManagement: () -> Unit = {},
    private val onPickAvatarImage: (onPicked: (Uri) -> Unit) -> Unit,
    private val onLogout: () -> Unit,
    private val fetchCloudUserInfo: (suspend () -> CheryUserInfo?)? = null,
    private val updateCloudProfileField: (suspend (updateType: String, content: String) -> Boolean)? = null,
    private val onOpenDebugDiagnostics: (() -> Unit)? = null,
    private val onEntranceStyleChanged: ((String) -> Unit)? = null,
    private val fetchRegionCities: (suspend () -> CheryHttpClient.RegionDirectory)? = null
) {

    private enum class SubPage {
        MAIN,
        ACCOUNT_SECURITY,
        ABOUT
    }

    private data class AccountProfileData(
        val nickname: String,
        val username: String,
        val mobile: String,
        val email: String,
        val gender: String,
        val region: String
    )

    private data class EntranceStyleOption(
        val id: String,
        val title: String,
        val subtitle: String
    )

    private val accountPrefs: SharedPreferences =
        activity.getSharedPreferences(PREFS_ACCOUNT_PROFILE, Context.MODE_PRIVATE)
    private val uiPrefs: SharedPreferences =
        activity.getSharedPreferences(PREFS_CONSUMER_UI, Context.MODE_PRIVATE)

    private var currentSubPage: SubPage = SubPage.MAIN
    private var pageHost: FrameLayout? = null

    // Shared update state so MAIN version indicator & ABOUT page stay in sync
    private var latestCheckResult: UpdateCheckResult? = null
    private var updateCheckError: String? = null
    private var isCheckingUpdate: Boolean = false
    private var isDownloadingUpdate: Boolean = false
    private var downloadedApkFile: File? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var profileSyncJob: Job? = null
    private var avatarSaveJob: Job? = null
    private var progressWidthAnimator: ValueAnimator? = null
    private var profileSaveColorAnim: ValueAnimator? = null
    private var cachedRegionDirectory: CheryHttpClient.RegionDirectory? = null

    // Live UI hooks on MAIN page
    private var mainVehicleTrailingTv: TextView? = null
    private var mainEntranceStyleValueText: TextView? = null
    private var mainAboutBadgeDot: View? = null
    private var mainAboutValueText: TextView? = null
    private var activeConfirmDialog: Dialog? = null
    private var entranceStyleSheetDialog: Dialog? = null
    private var regionSheetDialog: Dialog? = null
    private var activeDropdown: PopupWindow? = null

    // Live UI hooks on ABOUT page & Update Bottom Sheet
    private var aboutUpdateRow: LinearLayout? = null
    private var aboutUpdateDot: View? = null
    private var aboutUpdateValue: TextView? = null
    private var aboutUpdateChevron: View? = null
    private var updateSheetDialog: Dialog? = null
    private var sheetStatusTitle: TextView? = null
    private var sheetStatusSub: TextView? = null
    private var sheetNotesView: TextView? = null
    private var sheetProgressBox: LinearLayout? = null
    private var sheetProgressFill: View? = null
    private var sheetProgressTrack: FrameLayout? = null
    private var sheetProgressLabel: TextView? = null
    private var sheetActionBtn: TextView? = null
    private var lastDownloadedBytes: Long = 0L
    private var lastTotalBytes: Long = 0L

    fun buildRootView(): View {
        currentSubPage = SubPage.MAIN
        val host = FrameLayout(activity).apply {
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isClickable = true
            isFocusable = true
        }
        pageHost = host
        renderSubPage(SubPage.MAIN, direction = 0, animate = false)
        triggerUpdateCheck(force = false)
        syncOfficialCloudProfileIfPossible()
        return host
    }

    private fun formatVehicleRowTrailing(): String {
        val hasBoundVehicle = profileProvider().vin.isNotBlank() || modelNameProvider().isNotBlank()
        if (!hasBoundVehicle) return "未绑定车辆"
        return plateNoProvider().trim().ifBlank { "未设置车牌" }
    }

    fun refreshVehicleRow() {
        mainVehicleTrailingTv?.text = formatVehicleRowTrailing()
    }

    fun cancelBackgroundJobs() {
        updateCheckJob?.cancel()
        updateCheckJob = null
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        profileSyncJob?.cancel()
        profileSyncJob = null
        avatarSaveJob?.cancel()
        avatarSaveJob = null
        progressWidthAnimator?.cancel()
        progressWidthAnimator = null
        isCheckingUpdate = false
        isDownloadingUpdate = false
        runCatching { pendingAvatarFile().delete() }
    }

    private fun syncOfficialCloudProfileIfPossible() {
        val fetcher = fetchCloudUserInfo ?: return
        profileSyncJob?.cancel()
        profileSyncJob = scope.launch {
            val userInfo = withContext(Dispatchers.IO) {
                runCatching { fetcher() }.getOrNull()
            } ?: return@launch

            val changed = withContext(Dispatchers.IO) {
                applyCloudUserInfoToCache(userInfo)
            }
            if (changed && currentSubPage == SubPage.MAIN) {
                renderSubPage(SubPage.MAIN, direction = 0, animate = false)
            }
        }
    }

    private fun applyCloudUserInfoToCache(userInfo: CheryUserInfo): Boolean {
        val rootObj = runCatching { JSONObject(userInfo.rawJson) }.getOrNull()
        val data = rootObj?.optJSONObject("data") ?: rootObj
        val cloudNickname = firstNonBlank(
            userInfo.nickname,
            data?.optString("nickname").orEmpty(),
            data?.optString("nickName").orEmpty()
        )
        val cloudAccount = firstNonBlank(
            userInfo.account,
            data?.optString("account").orEmpty(),
            userInfo.userId
        )
        val cloudMobile = firstNonBlank(
            userInfo.telephone,
            data?.optString("telephone").orEmpty()
        )
        val cloudEmail = data?.optString("mail").orEmpty().trim()
        val cloudCity = data?.optString("city").orEmpty().trim()
        val rawSex = data?.optString("sex").orEmpty().trim()
        val cloudGender = when (rawSex.lowercase(Locale.US)) {
            "1", "m", "male", "男" -> "男"
            "2", "f", "female", "女" -> "女"
            "0", "保密", "未知" -> "保密"
            else -> ""
        }
        val rawIcon = firstNonBlank(
            data?.optString("icon").orEmpty(),
            data?.optString("headImg").orEmpty(),
            data?.optString("headUrl").orEmpty(),
            data?.optString("avatar").orEmpty()
        )
        val resolvedAvatarUrl = resolveCloudAvatarUrl(rawIcon)

        val editor = accountPrefs.edit()
        var changed = false

        val lastSyncedUserId = accountPrefs.getString(KEY_SYNCED_USER_ID, "").orEmpty()
        val currentUserId = userInfo.userId.ifBlank { cloudMobile }
        val switchedAccount = currentUserId.isNotBlank() && lastSyncedUserId.isNotBlank() && lastSyncedUserId != currentUserId
        if (switchedAccount) {
            cachedAvatarEntry = null
            runCatching { avatarFile().delete() }
            runCatching { pendingAvatarFile().delete() }
            runCatching { cloudAvatarFile().delete() }
            editor.remove(KEY_NICKNAME)
                .remove(KEY_USERNAME)
                .remove(KEY_MOBILE)
                .remove(KEY_EMAIL)
                .remove(KEY_REGION)
                .remove(KEY_GENDER)
                .remove(KEY_AVATAR_URL)
            changed = true
        }
        if (currentUserId.isNotBlank()) {
            editor.putString(KEY_SYNCED_USER_ID, currentUserId)
        }

        if (cloudNickname.isNotBlank() && (switchedAccount || cloudNickname != accountPrefs.getString(KEY_NICKNAME, ""))) {
            editor.putString(KEY_NICKNAME, cloudNickname)
            changed = true
        }
        if (cloudAccount.isNotBlank() && (switchedAccount || cloudAccount != accountPrefs.getString(KEY_USERNAME, ""))) {
            editor.putString(KEY_USERNAME, cloudAccount)
            changed = true
        }
        if (cloudMobile.isNotBlank() && (switchedAccount || cloudMobile != accountPrefs.getString(KEY_MOBILE, ""))) {
            editor.putString(KEY_MOBILE, cloudMobile)
            changed = true
        }
        if (cloudEmail.isNotBlank() && (switchedAccount || cloudEmail != accountPrefs.getString(KEY_EMAIL, ""))) {
            editor.putString(KEY_EMAIL, cloudEmail)
            changed = true
        }
        if (cloudCity.isNotBlank() && (switchedAccount || cloudCity != accountPrefs.getString(KEY_REGION, ""))) {
            editor.putString(KEY_REGION, cloudCity)
            changed = true
        }
        if (cloudGender.isNotBlank() && (switchedAccount || cloudGender != accountPrefs.getString(KEY_GENDER, ""))) {
            editor.putString(KEY_GENDER, cloudGender)
            changed = true
        }

        if (resolvedAvatarUrl.isNotBlank()) {
            val prevUrl = if (switchedAccount) "" else accountPrefs.getString(KEY_AVATAR_URL, "").orEmpty()
            val cFile = cloudAvatarFile()
            if (resolvedAvatarUrl != prevUrl || !cFile.exists() || cFile.length() == 0L) {
                if (downloadCloudAvatar(resolvedAvatarUrl, cFile)) {
                    cachedAvatarEntry = null
                    editor.putString(KEY_AVATAR_URL, resolvedAvatarUrl)
                    changed = true
                }
            }
        }
        editor.apply()
        return changed
    }

    private fun resolveCloudAvatarUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return ""
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            return "https://" + trimmed.substring(7)
        }
        val cleanPath = trimmed.removePrefix("/")
        return "https://cloudrivechery.mychery.com/$cleanPath"
    }

    private fun downloadCloudAvatar(urlStr: String, target: File): Boolean {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return false
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) return false
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
                val side = min(decoded.width, decoded.height)
                val x = (decoded.width - side) / 2
                val y = (decoded.height - side) / 2
                val cropped = Bitmap.createBitmap(decoded, x, y, side, side)
                if (cropped !== decoded) decoded.recycle()
                val targetSize = min(side, 512)
                val scaled = if (side > targetSize) {
                    Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true).also {
                        if (it !== cropped) cropped.recycle()
                    }
                } else {
                    cropped
                }
                FileOutputStream(target).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
                scaled.recycle()
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }.orEmpty()

    fun handleBackPressed(): Boolean {
        dismissTransientOverlays()
        return when (currentSubPage) {
            SubPage.MAIN -> false
            SubPage.ACCOUNT_SECURITY -> {
                pendingAvatarFile().delete()
                navigateTo(SubPage.MAIN, direction = -1)
                true
            }
            SubPage.ABOUT -> {
                navigateTo(SubPage.MAIN, direction = -1)
                true
            }
        }
    }

    private fun navigateTo(target: SubPage, direction: Int) {
        if (currentSubPage == target) return
        dismissTransientOverlays()
        currentSubPage = target
        renderSubPage(target, direction = direction, animate = true)
    }

    private fun renderSubPage(target: SubPage, direction: Int, animate: Boolean) {
        val host = pageHost ?: return
        val content = when (target) {
            SubPage.MAIN -> buildMainSettingsView()
            SubPage.ACCOUNT_SECURITY -> buildAccountSecurityView()
            SubPage.ABOUT -> buildAboutView()
        }
        if (animate && host.childCount > 0) {
            MotionSpec.transitionContainer(host, content, direction)
        } else {
            host.removeAllViews()
            host.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    // =========================================================================
    // Page 1: Main Settings ("设置") — Unified AppComponents Rows
    // =========================================================================

    private fun buildMainSettingsView(): View {
        clearAboutHooks()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
        }

        root.addView(AppComponents.createTopBar(activity, "设置", onBack = { onBackToHome() }))

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(scroll)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(32))
        }

        // 1. Borderless Integrated Hero Profile Row
        val account = loadAccountProfile()
        val profileRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { navigateTo(SubPage.ACCOUNT_SECURITY, direction = 1) }
        }
        MotionSpec.attachPressPhysics(profileRow, pressedScale = 0.985f)

        val avatarView = buildAvatarView(
            sizeDp = 52,
            displayName = account.nickname.ifBlank { account.username }
        )
        profileRow.addView(avatarView, LinearLayout.LayoutParams(dp(52), dp(52)))

        val identityCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val displayTitle = account.nickname.ifBlank {
            SensitiveUiText.maskIfPhone(account.username).ifBlank { "Qiri+ 车主" }
        }
        identityCol.addView(
            text(displayTitle, 17.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            lp()
        )

        val subLine = buildString {
            val masked = SensitiveUiText.maskPhone(account.mobile)
            if (masked.isNotBlank()) append(masked)
            if (account.region.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(account.region)
            }
        }.ifBlank { "轻触完善个人资料" }

        identityCol.addView(
            text(subLine, 13f, ThemeTokens.TEXT_SUB).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            lp(top = dp(4))
        )
        profileRow.addView(
            identityCol,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
                marginEnd = dp(10)
            }
        )

        profileRow.addView(
            text("编辑", 13.5f, ThemeTokens.ACCENT_LIGHT, Typeface.BOLD),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(4) }
        )
        profileRow.addView(
            AppIconView(activity, AppIconType.CHEVRON_RIGHT, Color.parseColor("#5C606A")),
            LinearLayout.LayoutParams(dp(16), dp(16))
        )
        body.addView(profileRow, lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        // 2. Group 0: 我的车辆管理 (900/935/932/933/934/931/930)
        body.addView(AppComponents.createSectionHeader(activity, "车辆管理"))
        val vehicleRow = AppComponents.createListRow(
            context = activity,
            icon = AppIconType.CAR_CONTROLS,
            title = "车辆管理",
            trailingText = formatVehicleRowTrailing(),
            showChevron = true,
            onClick = { onOpenVehicleSwitcher() }
        )
        mainVehicleTrailingTv = vehicleRow.findViewWithTag("row_trailing_text")
        body.addView(vehicleRow, lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 58))

        // 3. Group 1: 账号与安全 (Login password only; vehicle PIN lives in 安全性)
        body.addView(AppComponents.createSectionHeader(activity, "账号与安全"))
        body.addView(
            AppComponents.createListRow(
                context = activity,
                icon = AppIconType.LOCK_CLOSED,
                title = "修改登录密码",
                onClick = { onOpenChangePassword() }
            ),
            lp()
        )

        // 3. Group 2: 通用 (vehicle-scoped trip track toggle lives in 安全性)
        body.addView(AppComponents.createSectionHeader(activity, "通用"))

        // 3D vehicle entrance choreography selector
        val entranceRow = AppComponents.createListRow(
            context = activity,
            icon = AppIconType.SWAP_CAR,
            title = "出场动画",
            trailingText = entranceStyleLabel(loadEntranceStyle()),
            showChevron = true,
            onClick = { showEntranceStyleSheet() }
        )
        mainEntranceStyleValueText = entranceRow.findViewWithTag("row_trailing_text")
        body.addView(entranceRow, lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 58))

        // About Qiri+ unified row with live version + update dot
        val (curName, _) = AppUpdater.getCurrentVersion(activity)
        val dotSize = dp(7)
        val badgeDot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ThemeTokens.DANGER)
                setSize(dotSize, dotSize)
            }
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
            visibility = View.GONE
        }
        mainAboutBadgeDot = badgeDot

        val aboutRow = AppComponents.createListRow(
            context = activity,
            icon = AppIconType.INFO_CIRCLE,
            title = "关于 Qiri+",
            trailingText = "v$curName",
            trailingView = badgeDot,
            showChevron = true,
            onClick = { navigateTo(SubPage.ABOUT, direction = 1) }
        )
        mainAboutValueText = aboutRow.findViewWithTag("row_trailing_text")
        body.addView(aboutRow, lp())

        // Spacer to push logout cleanly toward bottom
        body.addView(View(activity), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 4. Clean Borderless Bottom Logout Action with Confirm Dialog
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))
        val logoutAction = AppComponents.createActionButton(
            context = activity,
            label = "退出登录",
            style = ButtonStyle.DANGER_TEXT
        ) {
            showLogoutConfirmDialog()
        }
        body.addView(logoutAction, lp(top = dp(4), height = dp(54)))

        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        refreshMainUpdateBadge()
        return root
    }

    // =========================================================================
    // 3D Entrance Animation Preference Sheet
    // =========================================================================

    private fun loadEntranceStyle(): String {
        val raw = uiPrefs.getString(KEY_ENTRANCE_STYLE, DEFAULT_ENTRANCE_STYLE).orEmpty().trim()
        return if (ENTRANCE_STYLE_OPTIONS.any { it.id == raw }) raw else DEFAULT_ENTRANCE_STYLE
    }

    private fun saveEntranceStyle(styleId: String) {
        val normalized = if (ENTRANCE_STYLE_OPTIONS.any { it.id == styleId }) styleId else DEFAULT_ENTRANCE_STYLE
        uiPrefs.edit().putString(KEY_ENTRANCE_STYLE, normalized).apply()
    }

    private fun entranceStyleLabel(styleId: String): String =
        ENTRANCE_STYLE_OPTIONS.firstOrNull { it.id == styleId }?.title ?: "随机轮换"

    private fun showEntranceStyleSheet() {
        entranceStyleSheetDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        entranceStyleSheetDialog = null

        val currentStyle = loadEntranceStyle()
        var sheetRef: View? = null
        val dialog = object : Dialog(activity, R.style.Theme_CarPlus_BottomSheetDialog) {
            override fun dismiss() {
                val target = sheetRef
                if (!isShowing || target == null || MotionSpec.isBypassingSheetDismiss(this)) {
                    runCatching { super.dismiss() }
                    return
                }
                MotionSpec.dismissSheetAnimated(target, this)
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ThemeTokens.BG_SURFACE)
                cornerRadii = floatArrayOf(dpF(20f), dpF(20f), dpF(20f), dpF(20f), 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(12), dp(20), dp(22))
        }
        sheetRef = sheet

        val handle = View(activity).apply {
            background = rounded(Color.parseColor("#383C46"), 2.5f)
        }
        sheet.addView(
            handle,
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        )

        sheet.addView(text("出场动画", 18f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD), lp())
        sheet.addView(
            text("选择进入首页时 3D 车辆的入场方式", 13f, ThemeTokens.TEXT_SUB),
            lp(top = dp(4), bottom = dp(14))
        )

        val listCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        ENTRANCE_STYLE_OPTIONS.forEachIndexed { idx, opt ->
            val selected = opt.id == currentStyle
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(58)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = if (selected) {
                    rounded(
                        fillColor = Color.parseColor("#242832"),
                        radiusDp = 12f,
                        strokeColor = Color.argb(96, 61, 155, 255),
                        strokeWidthDp = 0.8f
                    )
                } else {
                    null
                }
                isClickable = true
                isFocusable = true
            }
            MotionSpec.attachPressPhysics(row, pressedScale = 0.985f, haptic = false)

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            textCol.addView(
                text(
                    opt.title,
                    15.5f,
                    if (selected) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_PRIMARY,
                    if (selected) Typeface.BOLD else Typeface.NORMAL
                ),
                lp()
            )
            textCol.addView(
                text(opt.subtitle, 12.5f, ThemeTokens.TEXT_SUB).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                lp(top = dp(3))
            )
            row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            if (selected) {
                row.addView(
                    AppIconView(activity, AppIconType.CHECK, ThemeTokens.ACCENT_LIGHT),
                    LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) }
                )
            }

            row.setOnClickListener {
                AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                saveEntranceStyle(opt.id)
                mainEntranceStyleValueText?.text = opt.title
                onEntranceStyleChanged?.invoke(opt.id)
                dialog.dismiss()
            }

            listCol.addView(row, lp(bottom = if (idx < ENTRANCE_STYLE_OPTIONS.lastIndex) dp(4) else 0))
            if (!selected && idx < ENTRANCE_STYLE_OPTIONS.lastIndex && ENTRANCE_STYLE_OPTIONS[idx + 1].id != currentStyle) {
                listCol.addView(AppComponents.createHairline(activity, startInsetDp = 14), lp(bottom = dp(4)))
            }
        }

        val listScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listCol, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        sheet.addView(listScroll, lp())

        AppSystemInsets.installBottomSheetContent(dialog, sheet, dimAmount = 0.55f)
        MotionSpec.attachSheetSwipeToDismiss(sheet, listScroll, dialog)
        dialog.setOnDismissListener {
            if (entranceStyleSheetDialog === dialog) {
                entranceStyleSheetDialog = null
            }
        }
        entranceStyleSheetDialog = dialog
        if (MotionSpec.isHostActivityAlive(activity)) {
            runCatching {
                dialog.show()
                MotionSpec.animateSheetEnter(sheet)
            }
        }
    }

    // =========================================================================
    // Page 2: Pure Profile Editor ("个人资料")
    // =========================================================================

    private fun buildAccountSecurityView(): View {
        clearMainHooks()
        clearAboutHooks()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
        }

        // Clean any leftover staged avatar from an abandoned edit session
        runCatching { pendingAvatarFile().delete() }
        var savedSnapshot = loadAccountProfile()
        var selectedGender = savedSnapshot.gender.ifBlank { "保密" }
        var selectedRegionName = savedSnapshot.region
        var selectedRegionCode = ""
        var avatarDirty = false
        var isSavingProfile = false
        var saveEnabled = false
        var renderedSaveColor = Color.parseColor("#5A5E68")
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                profileSaveColorAnim?.cancel()
                profileSaveColorAnim = null
            }
        })

        val saveActionBtn = text("保存", 15.5f, renderedSaveColor, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(4), dp(8))
            isEnabled = false
            isClickable = true
            isFocusable = true
        }
        MotionSpec.attachPressPhysics(saveActionBtn, pressedScale = 0.92f)

        root.addView(
            AppComponents.createTopBar(
                context = activity,
                title = "个人资料",
                onBack = {
                    runCatching { pendingAvatarFile().delete() }
                    navigateTo(SubPage.MAIN, direction = -1)
                },
                rightView = saveActionBtn
            )
        )

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(scroll)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(36))
        }

        // Inputs created before avatar callback so dirty check can read all fields.
        // Username and mobile are cloud account identifiers (read-only).
        val nicknameInput = buildBorderlessInput("请输入昵称", savedSnapshot.nickname, InputType.TYPE_CLASS_TEXT)
        val usernameInput = buildBorderlessInput("未设置", SensitiveUiText.maskIfPhone(savedSnapshot.username), InputType.TYPE_CLASS_TEXT).apply {
            isEnabled = false
            isFocusable = false
            setTextColor(ThemeTokens.TEXT_SUB)
        }
        val mobileInput = buildBorderlessInput("未绑定", SensitiveUiText.maskPhone(savedSnapshot.mobile), InputType.TYPE_CLASS_PHONE).apply {
            isEnabled = false
            isFocusable = false
            setTextColor(ThemeTokens.TEXT_SUB)
        }
        val emailInput = buildBorderlessInput(
            "请输入邮箱地址",
            savedSnapshot.email,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )

        fun currentDraft(): AccountProfileData = AccountProfileData(
            nickname = nicknameInput.text.toString().trim(),
            username = savedSnapshot.username,
            mobile = savedSnapshot.mobile,
            email = emailInput.text.toString().trim(),
            gender = selectedGender,
            region = selectedRegionName.trim()
        )

        fun updateSaveState() {
            val dirty = !isSavingProfile && (avatarDirty || currentDraft() != savedSnapshot)
            if (dirty == saveEnabled) return
            saveEnabled = dirty
            saveActionBtn.isEnabled = dirty
            saveActionBtn.typeface = Typeface.create("sans-serif", if (dirty) Typeface.BOLD else Typeface.NORMAL)

            val targetColor = if (dirty) ThemeTokens.ACCENT_LIGHT else Color.parseColor("#5A5E68")
            profileSaveColorAnim?.cancel()
            val startColor = renderedSaveColor
            if (startColor != targetColor) {
                profileSaveColorAnim = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor).apply {
                    duration = 160L
                    interpolator = MotionSpec.EASE_OUT_EXPO
                    addUpdateListener { anim ->
                        val c = anim.animatedValue as Int
                        renderedSaveColor = c
                        saveActionBtn.setTextColor(c)
                    }
                    start()
                }
            }
        }

        val dirtyWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateSaveState()
            }
        }
        listOf(nicknameInput, emailInput).forEach {
            it.addTextChangedListener(dirtyWatcher)
        }

        // 1. Centered 78dp Avatar with Camera Edit Badge
        val avatarHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(22))
        }

        val avatarFrame = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
        }
        val avatarImageSlot = FrameLayout(activity)
        fun refreshAvatarSlot(displayName: String, preferPending: Boolean = avatarDirty) {
            avatarImageSlot.removeAllViews()
            avatarImageSlot.addView(
                buildAvatarView(sizeDp = 78, displayName = displayName, preferPending = preferPending),
                FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER)
            )
        }
        refreshAvatarSlot(savedSnapshot.nickname.ifBlank { savedSnapshot.username }, preferPending = false)
        avatarFrame.addView(avatarImageSlot, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))

        val editBadge = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ThemeTokens.ACCENT_BLUE)
                setStroke(dp(2), ThemeTokens.BG_CANVAS)
            }
        }
        editBadge.addView(
            AppIconView(activity, AppIconType.ADD_ICON, ThemeTokens.TEXT_PRIMARY),
            FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
        )
        avatarFrame.addView(
            editBadge,
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END)
        )

        MotionSpec.attachPressPhysics(avatarFrame, pressedScale = 0.94f)
        avatarFrame.setOnClickListener {
            onPickAvatarImage { pickedUri ->
                avatarSaveJob?.cancel()
                avatarSaveJob = scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        saveScaledAvatar(pickedUri, pendingAvatarFile())
                    }
                    if (saved) {
                        avatarDirty = true
                        val draft = currentDraft()
                        refreshAvatarSlot(draft.nickname.ifBlank { draft.username }, preferPending = true)
                        updateSaveState()
                        toast("头像已选择，点击右上角保存生效")
                    } else {
                        toast("头像读取失败，请重试")
                    }
                }
            }
        }

        avatarHeader.addView(avatarFrame, LinearLayout.LayoutParams(dp(82), dp(82)))
        avatarHeader.addView(
            text("轻触更换头像", 13f, ThemeTokens.TEXT_SUB).apply {
                gravity = Gravity.CENTER
                setOnClickListener { avatarFrame.performClick() }
            },
            lp(top = dp(10))
        )
        body.addView(avatarHeader, lp())

        // Wire top-right Save action (persists locally and syncs changed fields via zyhl 113)
        saveActionBtn.setOnClickListener {
            if (!saveEnabled || isSavingProfile) return@setOnClickListener
            AppHaptics.perform(saveActionBtn, HapticLevel.MEDIUM_CONFIRM)
            val prev = savedSnapshot
            val updated = currentDraft()

            fun commitPendingAvatarIfAny() {
                val staged = pendingAvatarFile()
                if (avatarDirty && staged.exists() && staged.length() > 0L) {
                    val target = avatarFile()
                    if (target.exists()) target.delete()
                    if (!staged.renameTo(target)) {
                        runCatching { staged.copyTo(target, overwrite = true) }
                        runCatching { staged.delete() }
                    }
                    cachedAvatarEntry = null
                }
            }

            val updater = updateCloudProfileField
            if (updated.nickname != prev.nickname && updated.nickname.isBlank()) {
                toast("昵称不能为空")
                return@setOnClickListener
            }
            if (updated.email != prev.email) {
                if (updated.email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(updated.email).matches()) {
                    toast("请输入正确的邮箱地址")
                    return@setOnClickListener
                }
            }
            val cloudUpdates = mutableListOf<Triple<String, String, String>>()
            if (updated.nickname != prev.nickname && updated.nickname.isNotBlank()) {
                cloudUpdates += Triple("0", updated.nickname, "昵称")
            }
            if (updated.gender != prev.gender) {
                val sexCode = when (updated.gender) {
                    "男" -> "1"
                    "女" -> "2"
                    else -> "0"
                }
                cloudUpdates += Triple("1", sexCode, "性别")
            }
            if (updated.email != prev.email) {
                cloudUpdates += Triple("2", updated.email, "邮箱")
            }
            if (updated.region != prev.region && selectedRegionCode.isNotBlank()) {
                cloudUpdates += Triple("3", selectedRegionCode, "地区")
            }
            val hadLocalAvatarChange = avatarDirty
            if (updater == null || cloudUpdates.isEmpty()) {
                commitPendingAvatarIfAny()
                saveAccountProfile(updated)
                savedSnapshot = updated
                avatarDirty = false
                refreshAvatarSlot(updated.nickname.ifBlank { updated.username }, preferPending = false)
                updateSaveState()
                toast(if (hadLocalAvatarChange && updated == prev) "头像已保存在本机" else "个人资料已保存")
                return@setOnClickListener
            }

            isSavingProfile = true
            saveActionBtn.text = "保存中"
            updateSaveState()
            scope.launch {
                val outcomes = withContext(Dispatchers.IO) {
                    cloudUpdates.map { (type, value, label) ->
                        Triple(type, label, runCatching { updater(type, value) }.getOrDefault(false))
                    }
                }
                isSavingProfile = false
                saveActionBtn.text = "保存"

                val succeededTypes = outcomes.filter { it.third }.map { it.first }.toSet()
                val failedLabels = outcomes.filter { !it.third }.map { it.second }

                // Reconcile every field that actually succeeded on cloud so local & cloud never diverge
                var reconciled = savedSnapshot
                if ("0" in succeededTypes) reconciled = reconciled.copy(nickname = updated.nickname)
                if ("1" in succeededTypes) reconciled = reconciled.copy(gender = updated.gender)
                if ("2" in succeededTypes) reconciled = reconciled.copy(email = updated.email)
                if ("3" in succeededTypes) {
                    reconciled = reconciled.copy(region = updated.region)
                    selectedRegionCode = ""
                }
                if (hadLocalAvatarChange) {
                    commitPendingAvatarIfAny()
                    avatarDirty = false
                }
                if (reconciled != savedSnapshot) {
                    saveAccountProfile(reconciled)
                    savedSnapshot = reconciled
                }
                refreshAvatarSlot(reconciled.nickname.ifBlank { reconciled.username }, preferPending = false)
                updateSaveState()

                when {
                    failedLabels.isEmpty() -> {
                        toast(
                            if (hadLocalAvatarChange) "个人资料已同步至云端（头像仅保存在本机）"
                            else "个人资料已同步至云端"
                        )
                    }
                    succeededTypes.isNotEmpty() -> {
                        toast("部分资料同步失败（${failedLabels.joinToString("、")}），其余修改已保存")
                    }
                    else -> {
                        toast(
                            if (hadLocalAvatarChange) "头像已保存在本机，其余资料云端同步失败"
                            else "云端同步失败，请稍后重试"
                        )
                    }
                }
            }
        }

        // 2. Borderless Form Rows with Hairline Dividers
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))
        body.addView(buildFormInputRow("昵称", nicknameInput), lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))
        body.addView(buildFormInputRow("用户名", usernameInput), lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))
        body.addView(buildFormInputRow("手机号", mobileInput), lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))
        body.addView(buildFormInputRow("邮箱", emailInput), lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        // Gender row using unified AppComponents.showAnchoredDropdown
        val genderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        genderRow.addView(
            text("性别", 14.5f, ThemeTokens.TEXT_SUB),
            LinearLayout.LayoutParams(dp(72), WRAP_CONTENT)
        )

        val genderAnchor = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isClickable = true
            isFocusable = true
        }
        val genderValueTv = text(selectedGender, 15.5f, ThemeTokens.TEXT_PRIMARY)
        genderAnchor.addView(genderValueTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        genderAnchor.addView(
            AppIconView(activity, AppIconType.CHEVRON_DOWN, ThemeTokens.TEXT_SUB),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) }
        )
        MotionSpec.attachPressPhysics(genderAnchor, pressedScale = 0.98f)
        genderAnchor.setOnClickListener { anchorView ->
            activeDropdown?.dismiss()
            val options = listOf("男", "女", "保密").map { label ->
                DropdownOption(id = label, title = label, selected = label == selectedGender)
            }
            activeDropdown = AppComponents.showAnchoredDropdown(
                context = activity,
                anchor = anchorView,
                options = options,
                widthDp = 168
            ) { picked ->
                selectedGender = picked.id
                genderValueTv.text = picked.title
                updateSaveState()
            }
        }
        genderRow.addView(genderAnchor, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(genderRow, lp())

        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        // Region row using official 138 province/city directory selector (submits cityCode / provinceCode)
        val regionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        regionRow.addView(
            text("地区", 14.5f, ThemeTokens.TEXT_SUB),
            LinearLayout.LayoutParams(dp(72), WRAP_CONTENT)
        )
        val regionAnchor = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isClickable = true
            isFocusable = true
        }
        val regionValueTv = text(
            selectedRegionName.ifBlank { "请选择地区" },
            15.5f,
            if (selectedRegionName.isBlank()) HINT_COLOR else ThemeTokens.TEXT_PRIMARY
        )
        regionAnchor.addView(regionValueTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        regionAnchor.addView(
            AppIconView(activity, AppIconType.CHEVRON_DOWN, ThemeTokens.TEXT_SUB),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) }
        )
        MotionSpec.attachPressPhysics(regionAnchor, pressedScale = 0.98f)
        regionAnchor.setOnClickListener {
            showRegionPickerSheet(currentRegionName = selectedRegionName) { pickedName, pickedCode ->
                selectedRegionName = pickedName
                selectedRegionCode = pickedCode
                regionValueTv.text = pickedName
                regionValueTv.setTextColor(ThemeTokens.TEXT_PRIMARY)
                updateSaveState()
            }
        }
        regionRow.addView(regionAnchor, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(regionRow, lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun showRegionPickerSheet(
        currentRegionName: String,
        onSelected: (displayName: String, regionCode: String) -> Unit
    ) {
        if (!MotionSpec.isHostActivityAlive(activity)) return
        regionSheetDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        regionSheetDialog = null

        var sheetRef: View? = null
        val dialog = object : Dialog(activity, R.style.Theme_CarPlus_BottomSheetDialog) {
            override fun dismiss() {
                val target = sheetRef
                if (!isShowing || target == null || MotionSpec.isBypassingSheetDismiss(this)) {
                    runCatching { super.dismiss() }
                } else {
                    MotionSpec.dismissSheetAnimated(target, this)
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.72f).roundToInt()
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ThemeTokens.BG_SURFACE)
                val r = dpF(22f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(10), dp(20), dp(22))
        }
        sheetRef = sheet

        sheet.addView(
            View(activity).apply {
                background = rounded(Color.parseColor("#484A52"), 2f)
            },
            LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        )

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = text("选择地区", 17f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD)
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val closeBtn = text("取消", 14.5f, ThemeTokens.TEXT_SUB).apply {
            setPadding(dp(10), dp(6), dp(4), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn)
        sheet.addView(headerRow, lp(bottom = dp(10)))

        val searchInput = EditText(activity).apply {
            hint = "搜索省份或城市"
            setHintTextColor(HINT_COLOR)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setSingleLine(true)
            background = rounded(Color.parseColor("#171920"), 12f, Color.parseColor("#2A2E38"), 0.8f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        sheet.addView(searchInput, lp(bottom = dp(10)))

        val listCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val listScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listCol, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        sheet.addView(listScroll, LinearLayout.LayoutParams(MATCH_PARENT, (maxHeight - dp(150)).coerceAtLeast(dp(220))))

        var selectedProvince: CheryHttpClient.RegionProvince? = null
        var activeDirectory: CheryHttpClient.RegionDirectory? = cachedRegionDirectory

        fun buildRegionRow(label: String, subtitle: String = "", selected: Boolean = false, onClick: () -> Unit): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                if (selected) {
                    background = rounded(Color.argb(36, 94, 158, 255), 10f)
                }
                isClickable = true
                isFocusable = true
                MotionSpec.attachPressPhysics(this, pressedScale = 0.985f)
                addView(
                    text(label, 15f, if (selected) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_PRIMARY, if (selected) Typeface.BOLD else Typeface.NORMAL),
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                )
                if (subtitle.isNotBlank()) {
                    addView(text(subtitle, 12.5f, ThemeTokens.TEXT_SUB))
                }
                setOnClickListener { onClick() }
            }
        }

        fun pickRegion(name: String, code: String) {
            onSelected(name, code)
            dialog.dismiss()
        }

        lateinit var loadDirectory: () -> Unit

        fun renderEmptyOrError(message: String, isError: Boolean) {
            listCol.removeAllViews()
            listCol.addView(
                text(message, 13.5f, if (isError) ThemeTokens.DANGER else ThemeTokens.TEXT_SUB).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(12))
                },
                lp()
            )
            listCol.addView(
                AppComponents.createActionButton(activity, "重新加载", ButtonStyle.SECONDARY) {
                    loadDirectory()
                },
                lp(height = dp(44))
            )
        }

        fun renderDirectory() {
            listCol.removeAllViews()
            val dir = activeDirectory
            if (dir == null) {
                listCol.addView(
                    text("正在加载省市目录...", 14f, ThemeTokens.TEXT_SUB).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(36), 0, dp(36))
                    },
                    lp()
                )
                return
            }
            if (dir.hotCities.isEmpty() && dir.provinces.isEmpty()) {
                renderEmptyOrError("暂未获取到省市目录，请重试", isError = false)
                return
            }

            val query = searchInput.text.toString().trim()
            if (query.isNotBlank()) {
                val matches = mutableListOf<Pair<String, String>>()
                val seenCodes = HashSet<String>()
                dir.provinces.forEach { prov ->
                    if (prov.cities.isEmpty()) {
                        if (prov.provinceName.contains(query, ignoreCase = true) && seenCodes.add(prov.provinceCode)) {
                            matches += prov.provinceName to prov.provinceCode
                        }
                    } else {
                        prov.cities.forEach { city ->
                            if ((city.cityName.contains(query, ignoreCase = true) || prov.provinceName.contains(query, ignoreCase = true)) &&
                                seenCodes.add(city.cityCode)
                            ) {
                                matches += city.cityName to city.cityCode
                            }
                        }
                    }
                }
                if (matches.isEmpty()) {
                    listCol.addView(
                        text("未找到匹配的地区", 14f, ThemeTokens.TEXT_SUB).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(32), 0, dp(32))
                        },
                        lp()
                    )
                } else {
                    matches.take(60).forEachIndexed { idx, (name, code) ->
                        listCol.addView(buildRegionRow(name, selected = name == currentRegionName) { pickRegion(name, code) }, lp())
                        if (idx < matches.lastIndex) listCol.addView(AppComponents.createHairline(activity, startInsetDp = 12))
                    }
                }
                return
            }

            val prov = selectedProvince
            if (prov != null) {
                titleTv.text = prov.provinceName
                listCol.addView(
                    buildRegionRow("← 返回全部省份", subtitle = "", selected = false) {
                        selectedProvince = null
                        titleTv.text = "选择地区"
                        renderDirectory()
                    },
                    lp(bottom = dp(6))
                )
                listCol.addView(AppComponents.createHairline(activity, startInsetDp = 12))
                prov.cities.forEachIndexed { idx, city ->
                    listCol.addView(
                        buildRegionRow(city.cityName, selected = city.cityName == currentRegionName) {
                            pickRegion(city.cityName, city.cityCode)
                        },
                        lp()
                    )
                    if (idx < prov.cities.lastIndex) listCol.addView(AppComponents.createHairline(activity, startInsetDp = 12))
                }
                return
            }

            titleTv.text = "选择地区"
            if (dir.hotCities.isNotEmpty()) {
                listCol.addView(text("热门城市", 12.5f, ThemeTokens.TEXT_SUB, Typeface.BOLD), lp(top = dp(4), bottom = dp(8)))
                val chunked = dir.hotCities.chunked(3)
                chunked.forEach { rowCities ->
                    val chipRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    rowCities.forEachIndexed { idx, city ->
                        val selected = city.cityName == currentRegionName
                        val chip = text(
                            city.cityName,
                            13.5f,
                            if (selected) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_PRIMARY,
                            if (selected) Typeface.BOLD else Typeface.NORMAL
                        ).apply {
                            gravity = Gravity.CENTER
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            background = rounded(
                                if (selected) Color.argb(40, 94, 158, 255) else Color.parseColor("#1A1D24"),
                                10f,
                                if (selected) ThemeTokens.ACCENT_LIGHT else Color.parseColor("#2A2E38"),
                                0.8f
                            )
                            setPadding(dp(8), dp(9), dp(8), dp(9))
                            isClickable = true
                            isFocusable = true
                            MotionSpec.attachPressPhysics(this, pressedScale = 0.96f)
                            setOnClickListener { pickRegion(city.cityName, city.cityCode) }
                        }
                        chipRow.addView(
                            chip,
                            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                                if (idx < 2) marginEnd = dp(8)
                            }
                        )
                    }
                    repeat(3 - rowCities.size) {
                        chipRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                    }
                    listCol.addView(chipRow, lp(bottom = dp(8)))
                }
            }

            if (dir.provinces.isNotEmpty()) {
                listCol.addView(text("全部省市", 12.5f, ThemeTokens.TEXT_SUB, Typeface.BOLD), lp(top = dp(8), bottom = dp(4)))
                dir.provinces.forEachIndexed { idx, p ->
                    val sub = if (p.cities.isEmpty()) "" else "${p.cities.size} 个城市 ›"
                    listCol.addView(
                        buildRegionRow(p.provinceName, subtitle = sub, selected = p.provinceName == currentRegionName) {
                            if (p.cities.isEmpty()) {
                                pickRegion(p.provinceName, p.provinceCode)
                            } else {
                                selectedProvince = p
                                listScroll.scrollTo(0, 0)
                                renderDirectory()
                            }
                        },
                        lp()
                    )
                    if (idx < dir.provinces.lastIndex) listCol.addView(AppComponents.createHairline(activity, startInsetDp = 12))
                }
            }
        }

        loadDirectory = {
            activeDirectory = null
            renderDirectory()
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        val customFetcher = fetchRegionCities
                        if (customFetcher != null) {
                            customFetcher()
                        } else {
                            val token = profileProvider().accessToken
                            val client = CheryHttpClient(
                                defaultDeviceId = VehicleProfileStore(activity).defaultDeviceId()
                            )
                            client.fetchRegionCities(token)
                        }
                    }
                }
                if (!dialog.isShowing) return@launch
                res.onSuccess { dir ->
                    if (dir.hotCities.isNotEmpty() || dir.provinces.isNotEmpty()) {
                        cachedRegionDirectory = dir
                        activeDirectory = dir
                        renderDirectory()
                    } else {
                        activeDirectory = dir
                        renderEmptyOrError("暂未获取到省市目录，请重试", isError = false)
                    }
                }.onFailure { err ->
                    renderEmptyOrError("地区列表加载失败：${err.message.orEmpty().take(60)}", isError = true)
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderDirectory()
            }
        })

        AppSystemInsets.installBottomSheetContent(dialog, sheet, dimAmount = 0.58f)
        MotionSpec.attachSheetSwipeToDismiss(sheet, listScroll, dialog)
        dialog.setOnDismissListener {
            if (regionSheetDialog === dialog) {
                regionSheetDialog = null
            }
        }
        regionSheetDialog = dialog
        runCatching {
            dialog.show()
            MotionSpec.animateSheetEnter(sheet)
        }
        if (activeDirectory != null) {
            renderDirectory()
        } else {
            loadDirectory()
        }
    }

    // =========================================================================
    // Page 3: About Qiri+ ("关于 Qiri+")
    // =========================================================================

    private fun buildAboutView(): View {
        clearMainHooks()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
        }

        root.addView(
            AppComponents.createTopBar(
                context = activity,
                title = "关于 Qiri+",
                onBack = { navigateTo(SubPage.MAIN, direction = -1) }
            )
        )

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        AppSystemInsets.applyBottomNavPadding(scroll)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(36))
        }

        val (curName, curCode) = AppUpdater.getCurrentVersion(activity)

        // 1. Centered Minimalist Brand Hero (dark-surface badge + clean version text)
        val brandSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(34), dp(24), dp(32))
        }

        brandSection.addView(buildAboutBrandBadge(), LinearLayout.LayoutParams(dp(68), dp(68)))

        brandSection.addView(
            text("Qiri+", 20f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.02f
            },
            lp(top = dp(14))
        )

        var debugTapCount = 0
        var debugTapWindowStart = 0L
        fun handleVersionDebugTap(triggerView: View) {
            if (onOpenDebugDiagnostics == null) return
            val now = System.currentTimeMillis()
            if (now - debugTapWindowStart > 2_500L) {
                debugTapCount = 1
                debugTapWindowStart = now
            } else {
                debugTapCount++
            }
            val remaining = 5 - debugTapCount
            when {
                remaining <= 0 -> {
                    debugTapCount = 0
                    debugTapWindowStart = 0L
                    AppHaptics.perform(triggerView, HapticLevel.MEDIUM_CONFIRM)
                    onOpenDebugDiagnostics.invoke()
                }
                remaining <= 2 -> {
                    AppHaptics.perform(triggerView, HapticLevel.LIGHT_TICK)
                    toast("再连续点击 $remaining 次进入调试面板")
                }
            }
        }

        brandSection.addView(
            text("v$curName", 13.5f, ThemeTokens.TEXT_SUB).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(4), dp(16), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { handleVersionDebugTap(it) }
            },
            lp(top = dp(3))
        )

        brandSection.addView(
            text("Qiri+，奇瑞家", 12.5f, ABOUT_SUBTITLE_MUTED).apply {
                gravity = Gravity.CENTER
            },
            lp(top = dp(6))
        )
        body.addView(brandSection, lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        // 2. Borderless Software Update Row (matches Settings & Vehicle Info rows)
        val updateRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            isFocusable = true
        }
        MotionSpec.attachPressPhysics(updateRow, pressedScale = 0.985f, haptic = false)
        updateRow.addView(
            text("软件更新", 15.5f, ThemeTokens.TEXT_PRIMARY),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )

        val updateDot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ThemeTokens.ACCENT_LIGHT)
            }
            visibility = View.GONE
        }
        updateRow.addView(
            updateDot,
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) }
        )

        val updateValue = text("检查中...", 13.5f, ThemeTokens.TEXT_SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        updateRow.addView(
            updateValue,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )

        val updateChevron = AppIconView(activity, AppIconType.CHEVRON_RIGHT, HINT_COLOR).apply {
            visibility = View.GONE
        }
        updateRow.addView(
            updateChevron,
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) }
        )

        updateRow.setOnClickListener {
            if (isCheckingUpdate) return@setOnClickListener
            val ready = downloadedApkFile?.let { it.exists() && it.length() > 0L } == true
            val cached = latestCheckResult
            when {
                isDownloadingUpdate || ready || cached?.hasUpdate == true -> {
                    showAppUpdateSheet()
                }
                cached != null && !cached.hasUpdate -> {
                    toast("当前已是最新版本 v$curName")
                }
                else -> {
                    triggerUpdateCheck(force = true, notifyUserOnResult = true)
                }
            }
        }
        body.addView(updateRow, lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        // 3. Build Number Row (rapid 5-tap unlocks Debug Diagnostics)
        val buildRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { handleVersionDebugTap(it) }
        }
        MotionSpec.attachPressPhysics(buildRow, pressedScale = 0.99f, haptic = false)
        buildRow.addView(
            text("构建版本", 15.5f, ThemeTokens.TEXT_PRIMARY),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        buildRow.addView(
            text("Build $curCode", 13.5f, ThemeTokens.TEXT_SUB),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )
        body.addView(buildRow, lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        aboutUpdateRow = updateRow
        aboutUpdateDot = updateDot
        aboutUpdateValue = updateValue
        aboutUpdateChevron = updateChevron

        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        renderAboutUpdateState()
        if (latestCheckResult == null && !isCheckingUpdate) {
            triggerUpdateCheck(force = true)
        }
        return root
    }

    private fun buildAboutBrandBadge(): FrameLayout {
        val badge = FrameLayout(activity).apply {
            background = rounded(ABOUT_BADGE_SURFACE, 18f, ABOUT_BADGE_BORDER, 0.8f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(18f))
                }
            }
            contentDescription = "Qiri+ 品牌标识"
        }

        // Crisp pure-white Qiri+ vector mark directly on the dark automotive badge surface.
        val logoImage = ImageView(activity).apply {
            setImageResource(R.drawable.ic_qiri_adaptive_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        badge.addView(logoImage, FrameLayout.LayoutParams(dp(56), dp(18), Gravity.CENTER))
        return badge
    }

    // =========================================================================
    // Auto Update Check, Download & Install Flow
    // =========================================================================

    private fun triggerUpdateCheck(force: Boolean, notifyUserOnResult: Boolean = false) {
        if (isCheckingUpdate) return
        if (!force && latestCheckResult != null) {
            refreshMainUpdateBadge()
            renderAboutUpdateState()
            return
        }
        isCheckingUpdate = true
        updateCheckError = null
        renderAboutUpdateState()

        updateCheckJob?.cancel()
        updateCheckJob = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { AppUpdater.checkForUpdate(activity) }
            }
            isCheckingUpdate = false
            outcome.onSuccess { res ->
                val versionChanged = latestCheckResult?.latestVersionCode != res.latestVersionCode ||
                    latestCheckResult?.latestVersionName != res.latestVersionName
                if (versionChanged) {
                    downloadedApkFile = null
                }
                latestCheckResult = res
                updateCheckError = null
                refreshMainUpdateBadge()
                renderAboutUpdateState()
                if (notifyUserOnResult) {
                    if (res.hasUpdate) {
                        showAppUpdateSheet()
                    } else {
                        val (curName, _) = AppUpdater.getCurrentVersion(activity)
                        toast("当前已是最新版本 v$curName")
                    }
                }
            }.onFailure { err ->
                val msg = err.message.orEmpty().trim()
                val friendly = if (msg.isBlank() || msg.contains("Exception") || msg.contains("HTTP", ignoreCase = true) || msg.contains("Unable to resolve", ignoreCase = true) || msg.contains("timeout", ignoreCase = true)) {
                    "网络异常，请稍后重试"
                } else {
                    msg.take(50)
                }
                updateCheckError = friendly
                refreshMainUpdateBadge()
                renderAboutUpdateState()
                if (notifyUserOnResult) {
                    toast("检查更新失败：$friendly")
                }
            }
        }
    }

    private fun showAppUpdateSheet() {
        updateSheetDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        updateSheetDialog = null
        var sheetRef: View? = null
        val dialog = object : Dialog(activity, R.style.Theme_CarPlus_BottomSheetDialog) {
            override fun dismiss() {
                val target = sheetRef
                if (!isShowing || target == null || MotionSpec.isBypassingSheetDismiss(this)) {
                    runCatching { super.dismiss() }
                    return
                }
                MotionSpec.dismissSheetAnimated(target, this)
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ThemeTokens.BG_SURFACE)
                cornerRadii = floatArrayOf(dpF(20f), dpF(20f), dpF(20f), dpF(20f), 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        sheetRef = sheet

        // Top drag handle
        val handle = View(activity).apply {
            background = rounded(Color.parseColor("#383C46"), 2.5f)
        }
        sheet.addView(
            handle,
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        )

        val statusTitle = text("软件更新", 18f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD)
        val statusSub = text("", 13f, ThemeTokens.TEXT_SUB)
        val notesView = text("", 13.5f, ThemeTokens.TEXT_BODY).apply {
            setLineSpacing(dpF(3.5f), 1f)
            visibility = View.GONE
        }

        val progressBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val progressTrack = FrameLayout(activity).apply {
            background = rounded(ThemeTokens.BTN_SECONDARY, 2.5f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(2.5f))
                }
            }
        }
        val progressFill = View(activity).apply {
            background = rounded(ThemeTokens.ACCENT_BLUE, 2.5f)
        }
        progressTrack.addView(progressFill, FrameLayout.LayoutParams(0, MATCH_PARENT, Gravity.START))
        val progressLabel = text("准备下载...", 12.5f, ThemeTokens.TEXT_SUB)
        progressBox.addView(progressTrack, lp(height = dp(5)))
        progressBox.addView(progressLabel, lp(top = dp(8)))

        val ctaRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val cancelBtn = AppComponents.createActionButton(activity, "暂不更新", ButtonStyle.SECONDARY) {
            dialog.dismiss()
        }
        val actionBtn = AppComponents.createActionButton(activity, "下载并安装", ButtonStyle.PRIMARY) {
            handleDownloadOrInstallClick()
        }
        ctaRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(46), 1f))
        ctaRow.addView(
            actionBtn,
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) }
        )

        val notesMaxH = dp(220)
        val notesScroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val parentMode = MeasureSpec.getMode(heightMeasureSpec)
                val parentSize = MeasureSpec.getSize(heightMeasureSpec)
                val effectiveMax = if (parentMode == MeasureSpec.UNSPECIFIED) notesMaxH else minOf(parentSize, notesMaxH)
                val mode = if (parentMode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(effectiveMax, mode))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(notesView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        sheet.addView(statusTitle, lp())
        sheet.addView(statusSub, lp(top = dp(5)))
        sheet.addView(notesScroll, lp(top = dp(14)))
        sheet.addView(progressBox, lp(top = dp(16)))
        sheet.addView(ctaRow, lp(top = dp(20)))

        sheetStatusTitle = statusTitle
        sheetStatusSub = statusSub
        sheetNotesView = notesView
        sheetProgressBox = progressBox
        sheetProgressTrack = progressTrack
        sheetProgressFill = progressFill
        sheetProgressLabel = progressLabel
        sheetActionBtn = actionBtn

        AppSystemInsets.installBottomSheetContent(dialog, sheet, dimAmount = 0.55f)
        MotionSpec.attachSheetSwipeToDismiss(sheet, notesScroll, dialog)
        dialog.setOnDismissListener {
            if (updateSheetDialog === dialog) {
                updateSheetDialog = null
                clearUpdateSheetHooks()
            }
        }
        updateSheetDialog = dialog
        renderAboutUpdateState()
        if (isDownloadingUpdate || downloadedApkFile?.let { it.exists() && it.length() > 0L } == true) {
            updateProgressBar(lastDownloadedBytes, lastTotalBytes)
        }
        if (MotionSpec.isHostActivityAlive(activity)) {
            runCatching {
                dialog.show()
                MotionSpec.animateSheetEnter(sheet)
            }
        }
    }

    private fun handleDownloadOrInstallClick() {
        if (isDownloadingUpdate) return
        val existingApk = downloadedApkFile
        if (existingApk != null && existingApk.exists() && existingApk.length() > 0L) {
            when (val res = AppUpdater.installApkResult(activity, existingApk)) {
                is InstallApkResult.Launched -> Unit
                is InstallApkResult.NeedsPermission ->
                    toast("请允许 Qiri+ 安装未知应用，授权后再次点击安装")
                is InstallApkResult.Failed -> toast(res.message)
            }
            return
        }

        val result = latestCheckResult ?: return
        if (result.downloadUrl.isBlank()) {
            toast("未找到可下载的安装包")
            return
        }

        isDownloadingUpdate = true
        downloadedApkFile = null
        lastDownloadedBytes = 0L
        lastTotalBytes = result.assetSizeBytes
        renderAboutUpdateState()
        updateProgressBar(0L, result.assetSizeBytes)

        val versionTag = "${result.latestVersionName}_${result.latestVersionCode}"
        updateDownloadJob?.cancel()
        updateDownloadJob = scope.launch {
            val downloadOutcome = withContext(Dispatchers.IO) {
                runCatching {
                    AppUpdater.downloadApk(
                        context = activity,
                        url = result.downloadUrl,
                        versionTag = versionTag,
                        expectedSha256 = result.expectedSha256,
                        isCancelled = { !isDownloadingUpdate }
                    ) { downloaded, total ->
                        val effectiveTotal = if (total > 0L) total else result.assetSizeBytes
                        activity.runOnUiThread {
                            if (!MotionSpec.isHostActivityAlive(activity)) return@runOnUiThread
                            lastDownloadedBytes = downloaded
                            lastTotalBytes = effectiveTotal
                            updateProgressBar(downloaded, effectiveTotal)
                        }
                    }
                }
            }
            isDownloadingUpdate = false
            downloadOutcome.onSuccess { apkFile ->
                downloadedApkFile = apkFile
                lastDownloadedBytes = apkFile.length()
                lastTotalBytes = apkFile.length()
                updateProgressBar(apkFile.length(), apkFile.length())
                renderAboutUpdateState()
                when (val res = AppUpdater.installApkResult(activity, apkFile)) {
                    is InstallApkResult.Launched -> Unit
                    is InstallApkResult.NeedsPermission ->
                        toast("请允许 Qiri+ 安装未知应用，授权后再次点击安装")
                    is InstallApkResult.Failed -> toast(res.message)
                }
            }.onFailure { err ->
                if (err is java.util.concurrent.CancellationException) return@onFailure
                renderAboutUpdateState()
                val msg = err.message.orEmpty().trim()
                val friendly = if (msg.isBlank() || msg.contains("Exception") || msg.contains("HTTP", ignoreCase = true) || msg.contains("Socket", ignoreCase = true) || msg.contains("SSL", ignoreCase = true)) {
                    "网络连接异常，请稍后重试"
                } else {
                    msg.take(50)
                }
                toast("下载失败：$friendly")
            }
        }
    }

    private fun refreshMainUpdateBadge() {
        val res = latestCheckResult
        val dot = mainAboutBadgeDot
        val valTv = mainAboutValueText
        val (curName, _) = AppUpdater.getCurrentVersion(activity)
        if (res != null && res.hasUpdate) {
            dot?.visibility = View.VISIBLE
            valTv?.text = "v${res.latestVersionName} 可更新"
            valTv?.setTextColor(ThemeTokens.ACCENT_LIGHT)
        } else {
            dot?.visibility = View.GONE
            valTv?.text = "v$curName"
            valTv?.setTextColor(ThemeTokens.TEXT_SUB)
        }
    }

    private fun renderAboutUpdateState() {
        val readyToInstall = downloadedApkFile?.let { it.exists() && it.length() > 0L } == true

        // 1. Update the clean single row on the About page
        val updateRow = aboutUpdateRow
        val updateDot = aboutUpdateDot
        val updateValue = aboutUpdateValue
        val updateChevron = aboutUpdateChevron
        if (updateRow != null && updateDot != null && updateValue != null && updateChevron != null) {
            when {
                isDownloadingUpdate -> {
                    updateRow.isEnabled = true
                    updateDot.visibility = View.GONE
                    updateChevron.visibility = View.VISIBLE
                    updateValue.text = "下载中..."
                    updateValue.setTextColor(ThemeTokens.ACCENT_LIGHT)
                    updateValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                isCheckingUpdate -> {
                    updateRow.isEnabled = false
                    updateDot.visibility = View.GONE
                    updateChevron.visibility = View.GONE
                    updateValue.text = "检查中..."
                    updateValue.setTextColor(ThemeTokens.TEXT_SUB)
                    updateValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                updateCheckError != null -> {
                    updateRow.isEnabled = true
                    updateDot.visibility = View.GONE
                    updateChevron.visibility = View.VISIBLE
                    updateValue.text = "检查失败，轻触重试"
                    updateValue.setTextColor(ThemeTokens.DANGER)
                    updateValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                latestCheckResult != null -> {
                    val res = latestCheckResult!!
                    updateRow.isEnabled = true
                    if (res.hasUpdate || readyToInstall) {
                        updateDot.visibility = if (readyToInstall) View.GONE else View.VISIBLE
                        updateChevron.visibility = View.VISIBLE
                        updateValue.text = if (readyToInstall) "等待安装" else "v${res.latestVersionName} 可更新"
                        updateValue.setTextColor(ThemeTokens.ACCENT_LIGHT)
                        updateValue.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    } else {
                        updateDot.visibility = View.GONE
                        updateChevron.visibility = View.GONE
                        updateValue.text = "已是最新版本"
                        updateValue.setTextColor(ThemeTokens.TEXT_SUB)
                        updateValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    }
                }
            }
        }

        // 2. Sync open update bottom sheet if visible
        val titleTv = sheetStatusTitle ?: return
        val subTv = sheetStatusSub ?: return
        val notesTv = sheetNotesView ?: return
        val progBox = sheetProgressBox ?: return
        val actionBtn = sheetActionBtn ?: return
        val res = latestCheckResult

        when {
            isDownloadingUpdate -> {
                titleTv.text = "正在下载安装包"
                subTv.text = "下载完成后将自动调起系统安装器"
                notesTv.visibility = View.GONE
                progBox.visibility = View.VISIBLE
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = "下载中..."
                actionBtn.isEnabled = false
                actionBtn.alpha = 0.5f
            }
            res != null -> {
                val sizeLabel = formatBytes(res.assetSizeBytes)
                val cleanNotes = sanitizeReleaseNotes(res.releaseNotes)
                titleTv.text = if (readyToInstall) {
                    "安装包已就绪"
                } else {
                    "发现新版本 v${res.latestVersionName}"
                }
                subTv.text = buildString {
                    append("安装包 $sizeLabel")
                    if (res.publishedAt.isNotBlank()) {
                        append(" · ")
                        append(res.publishedAt.take(10))
                    }
                }
                notesTv.text = cleanNotes
                notesTv.visibility = if (cleanNotes.isNotBlank()) View.VISIBLE else View.GONE
                progBox.visibility = if (readyToInstall) View.VISIBLE else View.GONE
                actionBtn.visibility = if (readyToInstall || res.downloadUrl.isNotBlank()) View.VISIBLE else View.GONE
                actionBtn.text = if (readyToInstall) "立即安装" else "下载并安装"
                actionBtn.isEnabled = true
                actionBtn.alpha = 1f
            }
        }
    }

    private fun sanitizeReleaseNotes(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    line != "最新版本安装包" &&
                    !RELEASE_META_LINE_REGEX.matches(line) &&
                    !RELEASE_TITLE_ONLY_REGEX.matches(line)
            }
            .toList()
        return lines.joinToString("\n").trim()
    }

    private fun updateProgressBar(downloaded: Long, total: Long) {
        val track = sheetProgressTrack ?: return
        val fill = sheetProgressFill ?: return
        val label = sheetProgressLabel ?: return

        val percent = if (total > 0L) {
            ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
        } else {
            0
        }

        label.text = if (total > 0L) {
            "$percent%  ·  ${formatBytes(downloaded)} / ${formatBytes(total)}"
        } else {
            "已下载 ${formatBytes(downloaded)}"
        }

        track.post {
            val w = track.width
            if (w <= 0) return@post
            val targetW = if (total > 0L) {
                (w * (percent / 100f)).roundToInt().coerceIn(0, w)
            } else {
                (w * 0.25f).roundToInt()
            }
            val lp = fill.layoutParams as FrameLayout.LayoutParams
            val startW = lp.width.coerceIn(0, w)
            if (startW == targetW) return@post

            progressWidthAnimator?.cancel()
            progressWidthAnimator = ValueAnimator.ofInt(startW, targetW).apply {
                duration = 140L
                interpolator = MotionSpec.EASE_OUT_EXPO
                addUpdateListener { anim ->
                    val currentLp = fill.layoutParams as FrameLayout.LayoutParams
                    currentLp.width = anim.animatedValue as Int
                    fill.layoutParams = currentLp
                }
                start()
            }
        }
    }

    // =========================================================================
    // Local Account Profile & Avatar Persistence
    // =========================================================================

    private fun loadAccountProfile(): AccountProfileData {
        val activeProfile = runCatching { profileProvider() }.getOrNull()
        val fallbackMobile = activeProfile?.mobileNo.orEmpty()
        val fallbackUserId = activeProfile?.userId.orEmpty()

        val savedNickname = accountPrefs.getString(KEY_NICKNAME, "").orEmpty()
        val savedUsername = accountPrefs.getString(KEY_USERNAME, "").orEmpty()
        val savedMobile = accountPrefs.getString(KEY_MOBILE, "").orEmpty()
        val savedEmail = accountPrefs.getString(KEY_EMAIL, "").orEmpty()
        val savedGender = accountPrefs.getString(KEY_GENDER, "").orEmpty()
        val savedRegion = accountPrefs.getString(KEY_REGION, "").orEmpty()

        return AccountProfileData(
            nickname = savedNickname.ifBlank {
                if (fallbackMobile.length >= 4) "车主 ${fallbackMobile.takeLast(4)}" else "Qiri+ 车主"
            },
            username = savedUsername.ifBlank { fallbackUserId },
            mobile = savedMobile.ifBlank { fallbackMobile },
            email = savedEmail,
            gender = savedGender.ifBlank { "保密" },
            region = savedRegion
        )
    }

    private fun saveAccountProfile(data: AccountProfileData) {
        accountPrefs.edit()
            .putString(KEY_NICKNAME, data.nickname)
            .putString(KEY_USERNAME, data.username)
            .putString(KEY_MOBILE, data.mobile)
            .putString(KEY_EMAIL, data.email)
            .putString(KEY_GENDER, data.gender)
            .putString(KEY_REGION, data.region)
            .apply()
    }

    private fun avatarFile(): File = File(activity.filesDir, AVATAR_FILENAME)

    private fun pendingAvatarFile(): File = File(activity.filesDir, PENDING_AVATAR_FILENAME)

    private fun cloudAvatarFile(): File = File(activity.filesDir, CLOUD_AVATAR_FILENAME)

    @SuppressLint("ExifInterface")
    private fun readExifRotationDegrees(uri: Uri): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0f
        return runCatching {
            activity.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
    }

    private fun saveScaledAvatar(uri: Uri, targetFile: File = avatarFile()): Boolean {
        return runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            activity.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return false

            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > 1024 || boundsOpts.outHeight / sampleSize > 1024) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawDecoded = activity.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return false

            val rotation = readExifRotationDegrees(uri)
            val decoded = if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(rawDecoded, 0, 0, rawDecoded.width, rawDecoded.height, matrix, true).also {
                    if (it !== rawDecoded) rawDecoded.recycle()
                }
            } else {
                rawDecoded
            }

            val side = min(decoded.width, decoded.height)
            val x = (decoded.width - side) / 2
            val y = (decoded.height - side) / 2
            val cropped = Bitmap.createBitmap(decoded, x, y, side, side)
            if (cropped !== decoded) decoded.recycle()

            val targetSize = min(side, 512)
            val scaled = if (side > targetSize) {
                Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true).also {
                    if (it !== cropped) cropped.recycle()
                }
            } else {
                cropped
            }

            FileOutputStream(targetFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            scaled.recycle()
            true
        }.getOrDefault(false)
    }

    private fun buildAvatarView(sizeDp: Int, displayName: String, preferPending: Boolean = false): View {
        val frame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ThemeTokens.ACCENT_BLUE)
                setStroke(dpF(1f).roundToInt().coerceAtLeast(1), Color.argb(42, 255, 255, 255))
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }

        fun renderMonogram() {
            val monogram = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "Q"
            val tv = text(monogram, (sizeDp * 0.38f).coerceAtLeast(18f), ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            }
            frame.removeAllViews()
            frame.addView(tv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        fun renderBitmap(bmp: Bitmap) {
            val iv = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            }
            frame.removeAllViews()
            frame.addView(iv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        val stagedFile = pendingAvatarFile()
        val localFile = avatarFile()
        val cloudFile = cloudAvatarFile()
        val memBmp = cachedAvatarEntry?.bitmap?.takeIf { !it.isRecycled }
        if (!preferPending && memBmp != null) {
            renderBitmap(memBmp)
        } else {
            renderMonogram()
        }

        scope.launch {
            val decodedEntry = withContext(Dispatchers.IO) {
                val resolvedFile = when {
                    preferPending && stagedFile.exists() && stagedFile.length() > 0L -> stagedFile
                    localFile.exists() && localFile.length() > 0L -> localFile
                    cloudFile.exists() && cloudFile.length() > 0L -> cloudFile
                    else -> null
                } ?: return@withContext null
                val key = "${resolvedFile.absolutePath}:${resolvedFile.lastModified()}:${resolvedFile.length()}"
                val cached = cachedAvatarEntry
                if (cached != null && cached.key == key && !cached.bitmap.isRecycled) {
                    return@withContext cached
                }
                val bmp = runCatching { BitmapFactory.decodeFile(resolvedFile.absolutePath) }.getOrNull()
                    ?: return@withContext null
                val entry = CachedAvatarEntry(key, bmp)
                if (!preferPending) {
                    cachedAvatarEntry = entry
                }
                entry
            }
            if (!MotionSpec.isHostActivityAlive(activity)) return@launch
            val bmp = decodedEntry?.bitmap
            if (bmp != null && !bmp.isRecycled) {
                if (bmp !== memBmp || preferPending) {
                    renderBitmap(bmp)
                }
            } else if (memBmp != null && !preferPending) {
                cachedAvatarEntry = null
                renderMonogram()
            }
        }
        return frame
    }

    // =========================================================================
    // Shared Component Compositions (AppComponents)
    // =========================================================================

    private fun showLogoutConfirmDialog() {
        activeConfirmDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        activeConfirmDialog = AppComponents.showConfirmDialog(
            context = activity,
            title = "退出登录",
            message = "退出后将清除本地账号登录态，下次使用需要重新登录。",
            confirmText = "退出登录",
            cancelText = "取消",
            dangerConfirm = true
        ) {
            onLogout()
        }
    }

    private fun buildFormInputRow(label: String, input: EditText): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        row.addView(
            text(label, 14.5f, ThemeTokens.TEXT_SUB),
            LinearLayout.LayoutParams(dp(72), WRAP_CONTENT)
        )
        input.minHeight = dp(42)
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun buildBorderlessInput(hintText: String, initialValue: String, inputType: Int): EditText =
        EditText(activity).apply {
            hint = hintText
            setText(initialValue)
            setHintTextColor(HINT_COLOR)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            this.inputType = inputType
            setSingleLine(true)
            background = null
            setPadding(0, 0, 0, 0)
        }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(activity).apply {
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

    private fun lp(
        top: Int = 0,
        bottom: Int = 0,
        height: Int = WRAP_CONTENT
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(MATCH_PARENT, height).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private fun dpF(value: Float): Float = value * activity.resources.displayMetrics.density

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "--"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    internal fun dismissTransientOverlays() {
        profileSaveColorAnim?.cancel()
        profileSaveColorAnim = null
        activeConfirmDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        activeConfirmDialog = null
        entranceStyleSheetDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        entranceStyleSheetDialog = null
        regionSheetDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        regionSheetDialog = null
        updateSheetDialog?.let { MotionSpec.dismissSheetImmediately(it) }
        updateSheetDialog = null
        activeDropdown?.dismiss()
        activeDropdown = null
    }

    private fun clearMainHooks() {
        dismissTransientOverlays()
        mainVehicleTrailingTv = null
        mainEntranceStyleValueText = null
        mainAboutBadgeDot = null
        mainAboutValueText = null
    }

    private fun clearUpdateSheetHooks() {
        progressWidthAnimator?.cancel()
        progressWidthAnimator = null
        sheetStatusTitle = null
        sheetStatusSub = null
        sheetNotesView = null
        sheetProgressBox = null
        sheetProgressFill = null
        sheetProgressTrack = null
        sheetProgressLabel = null
        sheetActionBtn = null
    }

    private fun clearAboutHooks() {
        clearUpdateSheetHooks()
        aboutUpdateRow = null
        aboutUpdateDot = null
        aboutUpdateValue = null
        aboutUpdateChevron = null
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val PREFS_ACCOUNT_PROFILE = "carplus_account_profile"
        private const val PREFS_CONSUMER_UI = "car_plus_consumer_ui"
        private const val KEY_ENTRANCE_STYLE = "car3d_entrance_style"
        private const val DEFAULT_ENTRANCE_STYLE = "auto"
        private val ENTRANCE_STYLE_OPTIONS = listOf(
            EntranceStyleOption("auto", "随机轮换", "每次进入自动切换，不连续重复"),
            EntranceStyleOption("right_drive", "右侧驶入", "从右侧弧线驶入展台并平缓停稳"),
            EntranceStyleOption("left_drive", "左侧驶入", "从左侧弧线驶入展台并平缓停稳"),
            EntranceStyleOption("reverse_park", "倒车入库", "前方减速挂倒挡，优雅倒车入位"),
            EntranceStyleOption("bay_exit", "车位驶出", "点亮前灯从车位转向驶出居中展示")
        )
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_USERNAME = "username"
        private const val KEY_MOBILE = "mobile"
        private const val KEY_EMAIL = "email"
        private const val KEY_GENDER = "gender"
        private const val KEY_REGION = "region"
        private const val KEY_AVATAR_URL = "cloud_avatar_url"
        private const val KEY_SYNCED_USER_ID = "synced_user_id"
        private const val AVATAR_FILENAME = "user_avatar.jpg"
        private const val PENDING_AVATAR_FILENAME = "user_avatar_pending.jpg"
        private const val CLOUD_AVATAR_FILENAME = "cloud_avatar.jpg"

        private data class CachedAvatarEntry(
            val key: String,
            val bitmap: Bitmap
        )

        @Volatile
        private var cachedAvatarEntry: CachedAvatarEntry? = null

        fun clearCachedAccountProfile(context: Context) {
            cachedAvatarEntry = null
            context.getSharedPreferences(PREFS_ACCOUNT_PROFILE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            runCatching { File(context.filesDir, AVATAR_FILENAME).delete() }
            runCatching { File(context.filesDir, PENDING_AVATAR_FILENAME).delete() }
            runCatching { File(context.filesDir, CLOUD_AVATAR_FILENAME).delete() }
        }

        private val ABOUT_BADGE_SURFACE = Color.parseColor("#1E2026")
        private val ABOUT_BADGE_BORDER = Color.parseColor("#2A2D35")
        private val ABOUT_SUBTITLE_MUTED = Color.parseColor("#6A6E78")
        private val HINT_COLOR = Color.rgb(92, 96, 106)

        private val RELEASE_META_LINE_REGEX = Regex(
            """^(?:(?:versionName|versionCode|commit|sha|sha256|tag|build|branch|ref|run_id|artifact)\s*=\s*\S+\s*)+$""",
            RegexOption.IGNORE_CASE
        )
        private val RELEASE_TITLE_ONLY_REGEX = Regex(
            """^(?:Qiri\+|CarPlus)\s+v[0-9]+(?:\.[0-9]+)*(?:\s*\(Build\s+\d+\))?$""",
            RegexOption.IGNORE_CASE
        )
    }
}
