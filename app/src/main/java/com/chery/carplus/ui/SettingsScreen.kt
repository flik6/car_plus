package com.chery.carplus.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import com.chery.carplus.cloud.UpdateCheckResult
import com.chery.carplus.profile.VehicleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    private val onToggleTripTrack: (enabled: Boolean, onResult: (Boolean) -> Unit) -> Unit = { _, cb -> cb(false) },
    private val initialTripTrackEnabled: Boolean? = null
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

    private val accountPrefs: SharedPreferences =
        activity.getSharedPreferences(PREFS_ACCOUNT_PROFILE, Context.MODE_PRIVATE)

    init {
        if (initialTripTrackEnabled != null) {
            accountPrefs.edit().putBoolean(KEY_TRIP_TRACK_ENABLED, initialTripTrackEnabled).apply()
        }
    }

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
    private var progressWidthAnimator: ValueAnimator? = null

    // Live UI hooks on MAIN page
    private var mainAboutBadgeDot: View? = null
    private var mainAboutValueText: TextView? = null
    private var tripTrackBusy: Boolean = false
    private var activeConfirmDialog: Dialog? = null
    private var activeDropdown: PopupWindow? = null

    // Live UI hooks on ABOUT page
    private var aboutUpdateRow: LinearLayout? = null
    private var aboutUpdateDot: View? = null
    private var aboutUpdateValue: TextView? = null
    private var aboutUpdateChevron: View? = null
    private var aboutUpdateCard: LinearLayout? = null
    private var aboutStatusTitle: TextView? = null
    private var aboutStatusSub: TextView? = null
    private var aboutNotesView: TextView? = null
    private var aboutProgressBox: LinearLayout? = null
    private var aboutProgressFill: View? = null
    private var aboutProgressTrack: FrameLayout? = null
    private var aboutProgressLabel: TextView? = null
    private var aboutActionBtn: TextView? = null

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
        return host
    }

    fun handleBackPressed(): Boolean {
        dismissTransientOverlays()
        return when (currentSubPage) {
            SubPage.MAIN -> false
            SubPage.ACCOUNT_SECURITY,
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
        val displayTitle = account.nickname.ifBlank { account.username.ifBlank { "CarPlus 车主" } }
        identityCol.addView(
            text(displayTitle, 17.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            lp()
        )

        val subLine = buildString {
            val masked = maskMobile(account.mobile)
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
        val vehicleTrailing = listOfNotNull(
            modelNameProvider().takeIf { it.isNotBlank() },
            plateNoProvider().takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "查看与编辑" }
        body.addView(
            AppComponents.createListRow(
                context = activity,
                icon = AppIconType.CAR_CONTROLS,
                title = "车辆管理",
                trailingText = vehicleTrailing,
                showChevron = true,
                onClick = { onOpenVehicleSwitcher() }
            ),
            lp()
        )
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

        // 3. Group 2: 系统与隐私
        body.addView(AppComponents.createSectionHeader(activity, "系统与隐私"))
        body.addView(buildTripTrackToggleRow(), lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 58))

        // About CarPlus unified row with live version + update dot
        val (curName, _) = AppUpdater.getCurrentVersion(activity)
        val badgeDot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ThemeTokens.DANGER)
            }
            minimumWidth = dp(7)
            minimumHeight = dp(7)
            visibility = View.GONE
        }
        mainAboutBadgeDot = badgeDot

        val aboutRow = AppComponents.createListRow(
            context = activity,
            icon = AppIconType.INFO_CIRCLE,
            title = "关于 CarPlus",
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
    // Page 2: Pure Profile Editor ("个人资料")
    // =========================================================================

    private fun buildAccountSecurityView(): View {
        clearMainHooks()
        clearAboutHooks()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
        }

        var savedSnapshot = loadAccountProfile()
        var selectedGender = savedSnapshot.gender.ifBlank { "保密" }
        var avatarDirty = false
        var saveEnabled = false
        var saveColorAnim: ValueAnimator? = null
        var renderedSaveColor = Color.parseColor("#5A5E68")

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
                onBack = { navigateTo(SubPage.MAIN, direction = -1) },
                rightView = saveActionBtn
            )
        )

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(36))
        }

        // Inputs created before avatar callback so dirty check can read all fields
        val nicknameInput = buildBorderlessInput("请输入昵称", savedSnapshot.nickname, InputType.TYPE_CLASS_TEXT)
        val usernameInput = buildBorderlessInput("请输入用户名", savedSnapshot.username, InputType.TYPE_CLASS_TEXT)
        val mobileInput = buildBorderlessInput("请输入手机号", savedSnapshot.mobile, InputType.TYPE_CLASS_PHONE)
        val emailInput = buildBorderlessInput(
            "请输入邮箱地址",
            savedSnapshot.email,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        val regionInput = buildBorderlessInput("例如：上海", savedSnapshot.region, InputType.TYPE_CLASS_TEXT)

        fun currentDraft(): AccountProfileData = AccountProfileData(
            nickname = nicknameInput.text.toString().trim(),
            username = usernameInput.text.toString().trim(),
            mobile = mobileInput.text.toString().trim(),
            email = emailInput.text.toString().trim(),
            gender = selectedGender,
            region = regionInput.text.toString().trim()
        )

        fun updateSaveState() {
            val dirty = avatarDirty || currentDraft() != savedSnapshot
            if (dirty == saveEnabled) return
            saveEnabled = dirty
            saveActionBtn.isEnabled = dirty
            saveActionBtn.typeface = Typeface.create("sans-serif", if (dirty) Typeface.BOLD else Typeface.NORMAL)

            val targetColor = if (dirty) ThemeTokens.ACCENT_LIGHT else Color.parseColor("#5A5E68")
            saveColorAnim?.cancel()
            val startColor = renderedSaveColor
            if (startColor != targetColor) {
                saveColorAnim = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor).apply {
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
        listOf(nicknameInput, usernameInput, mobileInput, emailInput, regionInput).forEach {
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
        fun refreshAvatarSlot(displayName: String) {
            avatarImageSlot.removeAllViews()
            avatarImageSlot.addView(
                buildAvatarView(sizeDp = 78, displayName = displayName),
                FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER)
            )
        }
        refreshAvatarSlot(savedSnapshot.nickname.ifBlank { savedSnapshot.username })
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
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        saveScaledAvatar(pickedUri)
                    }
                    if (saved) {
                        val draft = currentDraft()
                        refreshAvatarSlot(draft.nickname.ifBlank { draft.username })
                        avatarDirty = true
                        updateSaveState()
                        toast("头像已更新")
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

        // Wire top-right Save action
        saveActionBtn.setOnClickListener {
            if (!saveEnabled) return@setOnClickListener
            AppHaptics.perform(saveActionBtn, HapticLevel.MEDIUM_CONFIRM)
            val updated = currentDraft()
            saveAccountProfile(updated)
            savedSnapshot = updated
            avatarDirty = false
            refreshAvatarSlot(updated.nickname.ifBlank { updated.username })
            updateSaveState()
            toast("个人资料已保存")
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
        body.addView(buildFormInputRow("地区", regionInput), lp())
        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    // =========================================================================
    // Page 3: About CarPlus ("关于 CarPlus")
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
                title = "关于 CarPlus",
                onBack = { navigateTo(SubPage.MAIN, direction = -1) }
            )
        )

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
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
            text("CarPlus", 20f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.02f
            },
            lp(top = dp(14))
        )

        brandSection.addView(
            text("v$curName", 13.5f, ThemeTokens.TEXT_SUB).apply {
                gravity = Gravity.CENTER
            },
            lp(top = dp(5))
        )

        brandSection.addView(
            text("离线 3D 车控 · 双模数字钥匙", 12.5f, ABOUT_SUBTITLE_MUTED).apply {
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
            if (isCheckingUpdate || isDownloadingUpdate) return@setOnClickListener
            val ready = downloadedApkFile?.let { it.exists() && it.length() > 0L } == true
            if (ready || latestCheckResult?.hasUpdate == true) {
                handleDownloadOrInstallClick()
            } else {
                triggerUpdateCheck(force = true)
            }
        }
        body.addView(updateRow, lp())

        // 3. Dynamic Update Card (collapsed when up to date; shown only for updates/downloads)
        val updateCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ThemeTokens.BG_SURFACE, 12f, ThemeTokens.DIVIDER, 0.7f)
            setPadding(dp(16), dp(15), dp(16), dp(16))
            visibility = View.GONE
        }

        val statusTitle = text("", 14.5f, ThemeTokens.TEXT_PRIMARY, Typeface.BOLD)
        val statusSub = text("", 12.5f, ThemeTokens.TEXT_SUB)
        val notesView = text("", 13f, ThemeTokens.TEXT_BODY).apply {
            setLineSpacing(dpF(3f), 1f)
            visibility = View.GONE
        }

        val progressBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val progressTrack = FrameLayout(activity).apply {
            background = rounded(ThemeTokens.BTN_SECONDARY, 2f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(2f))
                }
            }
        }
        val progressFill = View(activity).apply {
            background = rounded(ThemeTokens.ACCENT_BLUE, 2f)
        }
        progressTrack.addView(progressFill, FrameLayout.LayoutParams(0, MATCH_PARENT, Gravity.START))
        val progressLabel = text("准备下载...", 12f, ThemeTokens.TEXT_SUB)
        progressBox.addView(progressTrack, lp(height = dp(4)))
        progressBox.addView(progressLabel, lp(top = dp(7)))

        val actionBtn = AppComponents.createActionButton(
            context = activity,
            label = "下载并安装",
            style = ButtonStyle.PRIMARY
        ) {
            handleDownloadOrInstallClick()
        }.apply {
            visibility = View.GONE
        }

        updateCard.addView(statusTitle, lp())
        updateCard.addView(statusSub, lp(top = dp(4)))
        updateCard.addView(notesView, lp(top = dp(10)))
        updateCard.addView(progressBox, lp(top = dp(12)))
        updateCard.addView(actionBtn, lp(top = dp(14), height = dp(44)))

        body.addView(
            updateCard,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
                topMargin = dp(4)
                bottomMargin = dp(16)
            }
        )

        body.addView(AppComponents.createHairline(activity, startInsetDp = 20))

        // 4. Build Number Read-Only Row
        val buildRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
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
        aboutUpdateCard = updateCard
        aboutStatusTitle = statusTitle
        aboutStatusSub = statusSub
        aboutNotesView = notesView
        aboutProgressBox = progressBox
        aboutProgressTrack = progressTrack
        aboutProgressFill = progressFill
        aboutProgressLabel = progressLabel
        aboutActionBtn = actionBtn

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
            contentDescription = "CarPlus 品牌标识"
        }

        // Convert black-on-white foreground vector artwork into crisp silver-white on transparent
        // so the brand mark integrates seamlessly onto the dark automotive badge surface.
        val invertToWhiteAlpha = ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 245f,
                0f, 0f, 0f, 0f, 247f,
                0f, 0f, 0f, 0f, 250f,
                -0.333f, -0.333f, -0.334f, 1f, 0f
            )
        )
        val logoImage = ImageView(activity).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            colorFilter = ColorMatrixColorFilter(invertToWhiteAlpha)
        }
        badge.addView(logoImage, FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER))
        return badge
    }

    // =========================================================================
    // Auto Update Check, Download & Install Flow
    // =========================================================================

    private fun triggerUpdateCheck(force: Boolean) {
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
                latestCheckResult = res
                updateCheckError = null
            }.onFailure { err ->
                updateCheckError = err.message ?: "网络请求失败"
            }
            refreshMainUpdateBadge()
            renderAboutUpdateState()
        }
    }

    private fun handleDownloadOrInstallClick() {
        if (isDownloadingUpdate) return
        val existingApk = downloadedApkFile
        if (existingApk != null && existingApk.exists() && existingApk.length() > 0L) {
            val started = runCatching { AppUpdater.installApk(activity, existingApk) }.getOrDefault(false)
            if (!started) {
                toast("请允许 CarPlus 安装未知应用，授权后再次点击安装")
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
        renderAboutUpdateState()
        updateProgressBar(0L, result.assetSizeBytes)

        updateDownloadJob?.cancel()
        updateDownloadJob = scope.launch {
            val downloadOutcome = withContext(Dispatchers.IO) {
                runCatching {
                    AppUpdater.downloadApk(activity, result.downloadUrl) { downloaded, total ->
                        val effectiveTotal = if (total > 0L) total else result.assetSizeBytes
                        activity.runOnUiThread {
                            updateProgressBar(downloaded, effectiveTotal)
                        }
                    }
                }
            }
            isDownloadingUpdate = false
            downloadOutcome.onSuccess { apkFile ->
                downloadedApkFile = apkFile
                updateProgressBar(apkFile.length(), apkFile.length())
                renderAboutUpdateState()
                val started = runCatching { AppUpdater.installApk(activity, apkFile) }.getOrDefault(false)
                if (!started) {
                    toast("请允许 CarPlus 安装未知应用，授权后再次点击安装")
                }
            }.onFailure { err ->
                renderAboutUpdateState()
                toast("下载失败：${err.message ?: "请检查网络连接"}")
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
        val updateRow = aboutUpdateRow ?: return
        val updateDot = aboutUpdateDot ?: return
        val updateValue = aboutUpdateValue ?: return
        val updateChevron = aboutUpdateChevron ?: return
        val updateCard = aboutUpdateCard ?: return
        val titleTv = aboutStatusTitle ?: return
        val subTv = aboutStatusSub ?: return
        val notesTv = aboutNotesView ?: return
        val progBox = aboutProgressBox ?: return
        val actionBtn = aboutActionBtn ?: return

        val readyToInstall = downloadedApkFile?.let { it.exists() && it.length() > 0L } == true

        when {
            isDownloadingUpdate -> {
                updateRow.isEnabled = false
                updateDot.visibility = View.GONE
                updateChevron.visibility = View.GONE
                updateValue.text = "下载中"
                updateValue.setTextColor(ThemeTokens.ACCENT_LIGHT)
                updateValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

                updateCard.visibility = View.VISIBLE
                titleTv.text = "正在下载安装包"
                titleTv.setTextColor(ThemeTokens.TEXT_PRIMARY)
                subTv.text = "下载完成后将自动调起系统安装器"
                notesTv.visibility = View.GONE
                progBox.visibility = View.VISIBLE
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = "下载中..."
                actionBtn.isEnabled = false
                actionBtn.alpha = 0.5f
            }
            isCheckingUpdate -> {
                updateRow.isEnabled = false
                updateDot.visibility = View.GONE
                updateChevron.visibility = View.GONE
                updateValue.text = "检查中..."
                updateValue.setTextColor(ThemeTokens.TEXT_SUB)
                updateValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

                updateCard.visibility = View.GONE
            }
            updateCheckError != null -> {
                updateRow.isEnabled = true
                updateDot.visibility = View.GONE
                updateChevron.visibility = View.VISIBLE
                updateValue.text = "检查失败，轻触重试"
                updateValue.setTextColor(ThemeTokens.DANGER)
                updateValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

                updateCard.visibility = View.GONE
            }
            latestCheckResult != null -> {
                val res = latestCheckResult!!
                val sizeLabel = formatBytes(res.assetSizeBytes)
                val cleanNotes = sanitizeReleaseNotes(res.releaseNotes)
                updateRow.isEnabled = true

                if (res.hasUpdate || readyToInstall) {
                    updateDot.visibility = if (readyToInstall) View.GONE else View.VISIBLE
                    updateChevron.visibility = View.GONE
                    updateValue.text = if (readyToInstall) "等待安装" else "v${res.latestVersionName} 可更新"
                    updateValue.setTextColor(ThemeTokens.ACCENT_LIGHT)
                    updateValue.typeface = Typeface.create("sans-serif", Typeface.BOLD)

                    updateCard.visibility = View.VISIBLE
                    titleTv.text = if (readyToInstall) {
                        "安装包已就绪"
                    } else {
                        "新版本 v${res.latestVersionName}"
                    }
                    titleTv.setTextColor(ThemeTokens.TEXT_PRIMARY)
                    subTv.text = buildString {
                        append("安装包 $sizeLabel")
                        if (res.publishedAt.isNotBlank()) {
                            append(" · ")
                            append(res.publishedAt.take(10))
                        }
                    }
                    notesTv.text = cleanNotes
                    notesTv.visibility = if (res.hasUpdate && cleanNotes.isNotBlank()) View.VISIBLE else View.GONE
                    progBox.visibility = if (readyToInstall) View.VISIBLE else View.GONE
                    actionBtn.visibility = if (readyToInstall || res.downloadUrl.isNotBlank()) View.VISIBLE else View.GONE
                    actionBtn.text = if (readyToInstall) "立即安装" else "下载并安装"
                    actionBtn.isEnabled = true
                    actionBtn.alpha = 1f
                } else {
                    updateDot.visibility = View.GONE
                    updateChevron.visibility = View.GONE
                    updateValue.text = "已是最新版本"
                    updateValue.setTextColor(ThemeTokens.TEXT_SUB)
                    updateValue.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

                    updateCard.visibility = View.GONE
                }
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
        val track = aboutProgressTrack ?: return
        val fill = aboutProgressFill ?: return
        val label = aboutProgressLabel ?: return

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
                if (fallbackMobile.length >= 4) "车主 ${fallbackMobile.takeLast(4)}" else "CarPlus 车主"
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

    private fun saveScaledAvatar(uri: Uri): Boolean {
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
            val decoded = activity.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return false

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

            FileOutputStream(avatarFile()).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            scaled.recycle()
            true
        }.getOrDefault(false)
    }

    private fun buildAvatarView(sizeDp: Int, displayName: String): View {
        val file = avatarFile()
        val bitmap = if (file.exists() && file.length() > 0L) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        } else {
            null
        }

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

        if (bitmap != null) {
            val iv = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
            }
            frame.addView(iv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            val monogram = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "C"
            val tv = text(monogram, (sizeDp * 0.38f).coerceAtLeast(18f), ThemeTokens.TEXT_PRIMARY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            }
            frame.addView(tv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return frame
    }

    // =========================================================================
    // Shared Component Compositions (AppComponents + ToggleSwitch)
    // =========================================================================

    private fun buildTripTrackToggleRow(): View {
        val trailingBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val syncingLabel = text("同步中...", 12f, ThemeTokens.TEXT_SUB).apply {
            visibility = View.GONE
        }
        trailingBox.addView(
            syncingLabel,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) }
        )

        val tripSwitch = ToggleSwitch(activity).apply {
            setChecked(accountPrefs.getBoolean(KEY_TRIP_TRACK_ENABLED, true), animate = false)
        }
        tripSwitch.onToggleAttempt = { targetEnabled ->
            if (!tripTrackBusy) {
                val currentEnabled = accountPrefs.getBoolean(KEY_TRIP_TRACK_ENABLED, true)
                activeConfirmDialog?.dismiss()
                val titleText = if (targetEnabled) "开启行程轨迹记录" else "关闭行程轨迹记录"
                val messageText = if (targetEnabled) {
                    "开启后将自动记录车辆行驶路线与历史行程数据。"
                } else {
                    "关闭后将停止记录新的行驶轨迹，已有历史记录不受影响。"
                }
                activeConfirmDialog = AppComponents.showConfirmDialog(
                    context = activity,
                    title = titleText,
                    message = messageText,
                    confirmText = "确认",
                    cancelText = "取消",
                    dangerConfirm = !targetEnabled
                ) {
                    if (tripTrackBusy) return@showConfirmDialog
                    tripTrackBusy = true
                    syncingLabel.visibility = View.VISIBLE
                    tripSwitch.isEnabled = false
                    tripSwitch.alpha = 0.55f

                    onToggleTripTrack(targetEnabled) { success ->
                        activity.runOnUiThread {
                            tripTrackBusy = false
                            syncingLabel.visibility = View.GONE
                            tripSwitch.isEnabled = true
                            tripSwitch.alpha = 1f
                            if (success) {
                                accountPrefs.edit().putBoolean(KEY_TRIP_TRACK_ENABLED, targetEnabled).apply()
                                tripSwitch.setChecked(targetEnabled, animate = true)
                                toast(if (targetEnabled) "已开启行程轨迹记录" else "已关闭行程轨迹记录")
                            } else {
                                tripSwitch.setChecked(currentEnabled, animate = true)
                                toast("行程轨迹设置同步失败，请稍后重试")
                            }
                        }
                    }
                }
            }
        }
        trailingBox.addView(tripSwitch)

        return AppComponents.createListRow(
            context = activity,
            icon = AppIconType.LOCATION_ARROW,
            title = "行程轨迹记录",
            trailingView = trailingBox,
            showChevron = false,
            onClick = null
        )
    }

    private fun showLogoutConfirmDialog() {
        activeConfirmDialog?.dismiss()
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
        row.addView(input, LinearLayout.LayoutParams(0, dp(42), 1f))
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

    private fun maskMobile(mobile: String): String =
        if (mobile.length >= 7) mobile.take(3) + "****" + mobile.takeLast(4) else mobile

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
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = null
        activeDropdown?.dismiss()
        activeDropdown = null
    }

    private fun clearMainHooks() {
        dismissTransientOverlays()
        mainAboutBadgeDot = null
        mainAboutValueText = null
    }

    private fun clearAboutHooks() {
        progressWidthAnimator?.cancel()
        progressWidthAnimator = null
        aboutUpdateRow = null
        aboutUpdateDot = null
        aboutUpdateValue = null
        aboutUpdateChevron = null
        aboutUpdateCard = null
        aboutStatusTitle = null
        aboutStatusSub = null
        aboutNotesView = null
        aboutProgressBox = null
        aboutProgressFill = null
        aboutProgressTrack = null
        aboutProgressLabel = null
        aboutActionBtn = null
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val PREFS_ACCOUNT_PROFILE = "carplus_account_profile"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_USERNAME = "username"
        private const val KEY_MOBILE = "mobile"
        private const val KEY_EMAIL = "email"
        private const val KEY_GENDER = "gender"
        private const val KEY_REGION = "region"
        private const val KEY_TRIP_TRACK_ENABLED = "trip_track_enabled"
        private const val AVATAR_FILENAME = "user_avatar.jpg"

        private val ABOUT_BADGE_SURFACE = Color.parseColor("#1E2026")
        private val ABOUT_BADGE_BORDER = Color.parseColor("#2A2D35")
        private val ABOUT_SUBTITLE_MUTED = Color.parseColor("#6A6E78")
        private val HINT_COLOR = Color.rgb(92, 96, 106)

        private val RELEASE_META_LINE_REGEX = Regex(
            """^(?:(?:versionName|versionCode|commit|sha|sha256|tag|build|branch|ref|run_id|artifact)\s*=\s*\S+\s*)+$""",
            RegexOption.IGNORE_CASE
        )
        private val RELEASE_TITLE_ONLY_REGEX = Regex(
            """^CarPlus\s+v[0-9]+(?:\.[0-9]+)*(?:\s*\(Build\s+\d+\))?$""",
            RegexOption.IGNORE_CASE
        )
    }
}
