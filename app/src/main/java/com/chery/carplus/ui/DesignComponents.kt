package com.chery.carplus.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Modern Android Edge-to-Edge & System Insets utility (gesture navigation pill "小白条",
 * display cutouts, IME keyboard lift, and immersive dark bottom sheets).
 */
object AppSystemInsets {

    fun applyEdgeToEdge(window: Window, backgroundColor: Int = ThemeTokens.BG_CANVAS) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.setBackgroundColor(backgroundColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    fun applyBottomSheetEdgeToEdge(dialog: Dialog, sheetView: View) {
        val window = dialog.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val baseLeft = sheetView.paddingLeft
        val baseTop = sheetView.paddingTop
        val baseRight = sheetView.paddingRight
        val baseBottom = sheetView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(sheetView) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val extraBottom = max(sysBars.bottom, ime.bottom)
            v.setPadding(
                baseLeft + sysBars.left,
                baseTop,
                baseRight + sysBars.right,
                baseBottom + extraBottom
            )
            insets
        }
        sheetView.post { ViewCompat.requestApplyInsets(sheetView) }
    }
}

/**
 * Unified CarPlus minimalist dark-mode design tokens (per DESIGN_SYSTEM.md v2.2).
 */
object ThemeTokens {
    val BG_CANVAS: Int = Color.parseColor("#161719")
    val BG_SURFACE: Int = Color.parseColor("#1C1D21")
    val BG_POPOVER: Int = Color.parseColor("#202228")
    val DIVIDER: Int = Color.parseColor("#23252B")
    val BTN_SECONDARY: Int = Color.parseColor("#2A2C32")
    val ACCENT_BLUE: Int = Color.parseColor("#3E6AE1")
    val ACCENT_ACTIVE: Int = Color.parseColor("#3D9BFF")
    val ACCENT_LIGHT: Int = Color.parseColor("#3D9BFF")
    val TEXT_PRIMARY: Int = Color.parseColor("#FFFFFF")
    val TEXT_BODY: Int = Color.parseColor("#D1D1D6")
    val TEXT_SUB: Int = Color.parseColor("#8E9299")
    val DANGER: Int = Color.parseColor("#F87171")
}

enum class DateTimeMode {
    YEAR_MONTH,
    DATE,
    DATE_HOUR,
    DATE_TIME,
    DATE_TIME_MINUTE,
    TIME,
    TIME_SECONDS
}

enum class ButtonStyle {
    PRIMARY,
    SECONDARY,
    DANGER_TEXT
}

data class DropdownOption(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val danger: Boolean = false
)

/**
 * Unified iOS-inspired animated sliding pill switch (46dp x 26dp).
 * If [onToggleAttempt] is set, tapping delegates to it (e.g. for confirmation dialog)
 * rather than mutating [checked] directly.
 */
class ToggleSwitch(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val defaultWidthPx = (46f * density).roundToInt()
    private val defaultHeightPx = (26f * density).roundToInt()
    private val trackOffColor = Color.parseColor("#2C2F36")
    private val trackOnColor = ThemeTokens.ACCENT_BLUE
    private val argbEvaluator = ArgbEvaluator()

    private var _checked = false
    var checked: Boolean
        get() = _checked
        set(value) {
            setChecked(value, animate = false)
        }

    var onToggleAttempt: ((targetChecked: Boolean) -> Unit)? = null

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val trackRect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
            val next = !_checked
            val handler = onToggleAttempt
            if (handler != null) {
                handler(next)
            } else {
                setChecked(next, animate = true)
            }
        }
    }

    fun setChecked(value: Boolean, animate: Boolean = true) {
        _checked = value
        val target = if (value) 1f else 0f
        animator?.cancel()
        animator = null
        if (!animate) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 200L
            interpolator = MotionSpec.EASE_OUT_EXPO
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(defaultWidthPx, widthMeasureSpec)
        val h = resolveSize(defaultHeightPx, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f
        trackPaint.color = argbEvaluator.evaluate(progress, trackOffColor, trackOnColor) as Int
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val pad = 2.5f * density
        val thumbR = (h - pad * 2f) / 2f
        val minCx = pad + thumbR
        val maxCx = w - pad - thumbR
        val cx = minCx + (maxCx - minCx) * progress
        canvas.drawCircle(cx, h / 2f, thumbR, thumbPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

/**
 * Shared minimalist UI component builders used across Home, Settings, Security, and Sheets.
 */
object AppComponents {

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun dpF(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /**
     * Strictly uniform 56dp TopBar across ALL Level-2 / Level-3 full-screen subpages:
     * BG_CANVAS (#161719), 16dp horizontal padding, 40dp back hit target with 22dp Lucide arrow-left,
     * 18sp bold #FFFFFF title, and optional right action text or custom view.
     */
    fun createTopBar(
        context: Context,
        title: String,
        onBack: () -> Unit,
        rightText: String? = null,
        rightEnabled: Boolean = true,
        onRightClick: (() -> Unit)? = null,
        rightView: View? = null
    ): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeTokens.BG_CANVAS)
            setPadding(dp(context, 16f), 0, dp(context, 16f), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 56f)
            )
        }

        val backHit = FrameLayout(context).apply {
            val icon = AppIconView(context, AppIconType.BACK_ARROW, ThemeTokens.TEXT_PRIMARY)
            addView(icon, FrameLayout.LayoutParams(dp(context, 22f), dp(context, 22f), Gravity.CENTER))
            MotionSpec.attachPressPhysics(this, pressedScale = 0.88f)
            setOnClickListener { onBack() }
        }
        bar.addView(
            backHit,
            LinearLayout.LayoutParams(dp(context, 40f), dp(context, 40f)).apply {
                marginStart = -dp(context, 8f)
            }
        )

        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            tag = "top_bar_title"
        }
        bar.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 6f)
            }
        )

        if (!rightText.isNullOrBlank()) {
            val rightTv = TextView(context).apply {
                text = rightText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (rightEnabled) ThemeTokens.ACCENT_ACTIVE else Color.parseColor("#5C606A"))
                isEnabled = rightEnabled
                setPadding(dp(context, 10f), dp(context, 8f), dp(context, 4f), dp(context, 8f))
                tag = "top_bar_right_text"
                if (onRightClick != null) {
                    MotionSpec.attachPressPhysics(this, pressedScale = 0.92f)
                    setOnClickListener { if (isEnabled) onRightClick() }
                }
            }
            bar.addView(
                rightTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        if (rightView != null) {
            bar.addView(
                rightView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(context, 8f)
                }
            )
        }

        return bar
    }

    /**
     * Minimalist section header above borderless list rows.
     */
    fun createSectionHeader(context: Context, title: String): TextView {
        return TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(ThemeTokens.TEXT_SUB)
            setPadding(dp(context, 20f), dp(context, 18f), dp(context, 20f), dp(context, 6f))
        }
    }

    /**
     * Borderless minimalist underline text input that highlights to ACCENT_ACTIVE on focus.
     */
    fun createUnderlineInput(
        context: Context,
        hint: String,
        initialValue: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        maxLength: Int? = null
    ): Pair<LinearLayout, EditText> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val linePx = (0.8f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val underline = View(context).apply {
            setBackgroundColor(ThemeTokens.DIVIDER)
        }
        val edit = EditText(context).apply {
            setText(initialValue)
            this.hint = hint
            setHintTextColor(Color.parseColor("#5C606A"))
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(context, 10f), 0, dp(context, 10f))
            this.inputType = inputType
            isSingleLine = true
            if (maxLength != null && maxLength > 0) {
                filters = arrayOf(InputFilter.LengthFilter(maxLength))
            }
            setOnFocusChangeListener { _, hasFocus ->
                underline.setBackgroundColor(if (hasFocus) ThemeTokens.ACCENT_ACTIVE else ThemeTokens.DIVIDER)
            }
        }
        container.addView(
            edit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        container.addView(
            underline,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, linePx)
        )
        return container to edit
    }

    /**
     * Minimalist centered empty state with Lucide icon, title, and optional subtitle.
     */
    fun createEmptyState(
        context: Context,
        icon: AppIconType,
        title: String,
        subtitle: String? = null
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 28f), dp(context, 48f), dp(context, 28f), dp(context, 48f))

            addView(
                AppIconView(context, icon, Color.parseColor("#5C606A")),
                LinearLayout.LayoutParams(dp(context, 36f), dp(context, 36f))
            )
            addView(
                TextView(context).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(ThemeTokens.TEXT_BODY)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 14f) }
            )
            if (!subtitle.isNullOrBlank()) {
                addView(
                    TextView(context).apply {
                        text = subtitle
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(ThemeTokens.TEXT_SUB)
                        gravity = Gravity.CENTER
                        setLineSpacing(dpF(context, 3f), 1f)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(context, 6f) }
                )
            }
        }
    }

    /**
     * Borderless underline Date/Time selector row backed by our unified dark
     * [WheelDateTimePicker] bottom sheet. Returns Pair(rowView, valueProvider).
     */
    fun createDateTimePickerField(
        context: Context,
        hint: String,
        initialValue: String = "",
        mode: DateTimeMode = DateTimeMode.DATE_TIME,
        title: String? = null,
        onValueChanged: (String) -> Unit = {}
    ): Pair<LinearLayout, () -> String> {
        var currentValue = initialValue.trim()
        val wheelMode = when (mode) {
            DateTimeMode.YEAR_MONTH -> WheelDateTimeMode.YEAR_MONTH
            DateTimeMode.DATE -> WheelDateTimeMode.DATE
            DateTimeMode.DATE_HOUR -> WheelDateTimeMode.DATE_HOUR
            DateTimeMode.DATE_TIME -> WheelDateTimeMode.DATE_TIME_SECONDS
            DateTimeMode.DATE_TIME_MINUTE -> WheelDateTimeMode.DATE_TIME
            DateTimeMode.TIME -> WheelDateTimeMode.TIME_HM
            DateTimeMode.TIME_SECONDS -> WheelDateTimeMode.TIME_HMS
        }

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val valueView = TextView(context).apply {
            text = currentValue.ifBlank { hint }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (currentValue.isNotBlank()) ThemeTokens.TEXT_PRIMARY else Color.parseColor("#5C606A"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10f), 0, dp(context, 10f))
        }
        row.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            AppIconView(
                context,
                if (mode == DateTimeMode.TIME || mode == DateTimeMode.TIME_SECONDS) AppIconType.CLOCK else AppIconType.CALENDAR,
                ThemeTokens.TEXT_SUB
            ),
            LinearLayout.LayoutParams(dp(context, 18f), dp(context, 18f)).apply {
                marginStart = dp(context, 8f)
            }
        )

        val linePx = (0.8f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val underline = View(context).apply {
            setBackgroundColor(ThemeTokens.DIVIDER)
        }

        MotionSpec.attachPressPhysics(outer, pressedScale = 0.985f)
        outer.setOnClickListener {
            WheelDateTimePicker.show(
                context = context,
                mode = wheelMode,
                title = title,
                initialValue = currentValue
            ) { formatted ->
                currentValue = formatted
                valueView.text = formatted
                valueView.setTextColor(ThemeTokens.TEXT_PRIMARY)
                onValueChanged(formatted)
            }
        }

        outer.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        outer.addView(
            underline,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, linePx)
        )

        return outer to { currentValue }
    }

    /**
     * Borderless underline Date/Time Range selector row backed by [WheelDateTimePicker.showRange].
     */
    fun createDateTimeRangePickerField(
        context: Context,
        hint: String,
        initialStart: String = "",
        initialEnd: String = "",
        mode: WheelDateTimeMode = WheelDateTimeMode.DATE_TIME_RANGE,
        title: String? = null,
        onRangeChanged: (start: String, end: String) -> Unit = { _, _ -> }
    ): Pair<LinearLayout, () -> Pair<String, String>> {
        var curStart = initialStart.trim()
        var curEnd = initialEnd.trim()

        fun formatSummary(): String = if (curStart.isNotBlank() && curEnd.isNotBlank()) {
            "$curStart ~ $curEnd"
        } else {
            ""
        }

        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val valueView = TextView(context).apply {
            val summary = formatSummary()
            text = summary.ifBlank { hint }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(if (summary.isNotBlank()) ThemeTokens.TEXT_PRIMARY else Color.parseColor("#5C606A"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10f), 0, dp(context, 10f))
        }
        row.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            AppIconView(context, AppIconType.CALENDAR, ThemeTokens.TEXT_SUB),
            LinearLayout.LayoutParams(dp(context, 18f), dp(context, 18f)).apply { marginStart = dp(context, 8f) }
        )

        val linePx = (0.8f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val underline = View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) }

        MotionSpec.attachPressPhysics(outer, pressedScale = 0.985f)
        outer.setOnClickListener {
            WheelDateTimePicker.showRange(
                context = context,
                mode = mode,
                title = title,
                initialStart = curStart,
                initialEnd = curEnd
            ) { s, e ->
                curStart = s
                curEnd = e
                valueView.text = formatSummary()
                valueView.setTextColor(ThemeTokens.TEXT_PRIMARY)
                onRangeChanged(s, e)
            }
        }

        outer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(underline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, linePx))
        return outer to { curStart to curEnd }
    }

    /**
     * Unified borderless minimalist row matching Home, Settings, and Security.
     */
    fun createListRow(
        context: Context,
        icon: AppIconType?,
        title: String,
        subtitle: String? = null,
        trailingText: String? = null,
        trailingView: View? = null,
        showChevron: Boolean = true,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 56f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(context, 20f), dp(context, 12f), dp(context, 20f), dp(context, 12f))
        }

        if (icon != null) {
            val iconView = AppIconView(context, icon, ThemeTokens.TEXT_BODY)
            row.addView(
                iconView,
                LinearLayout.LayoutParams(dp(context, 22f), dp(context, 22f)).apply {
                    marginEnd = dp(context, 16f)
                }
            )
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            tag = "row_title"
        }
        textCol.addView(titleView)

        if (!subtitle.isNullOrBlank()) {
            val subView = TextView(context).apply {
                text = subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ThemeTokens.TEXT_SUB)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                tag = "row_subtitle"
            }
            textCol.addView(
                subView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 2f)
                }
            )
        }

        row.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        if (!trailingText.isNullOrBlank()) {
            val trailingTv = TextView(context).apply {
                text = trailingText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ThemeTokens.TEXT_SUB)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                tag = "row_trailing_text"
            }
            row.addView(
                trailingTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(context, 8f)
                    if (showChevron) marginEnd = dp(context, 4f)
                }
            )
        }

        if (trailingView != null) {
            val existingLp = trailingView.layoutParams
            val targetW = existingLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
            val targetH = existingLp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            row.addView(
                trailingView,
                LinearLayout.LayoutParams(targetW, targetH).apply {
                    marginStart = dp(context, 8f)
                }
            )
        }

        if (showChevron) {
            val chevron = AppIconView(
                context,
                AppIconType.CHEVRON_RIGHT,
                Color.parseColor("#5C606A")
            )
            row.addView(
                chevron,
                LinearLayout.LayoutParams(dp(context, 16f), dp(context, 16f)).apply {
                    marginStart = dp(context, 4f)
                }
            )
        }

        if (onClick != null) {
            MotionSpec.attachPressPhysics(row, pressedScale = 0.985f, haptic = false)
            row.setOnClickListener { onClick() }
        }

        return row
    }

    /**
     * Subtle 0.6dp hairline divider with configurable start inset.
     */
    fun createHairline(context: Context, startInsetDp: Int = 62): View {
        val hPx = (0.6f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        return View(context).apply {
            setBackgroundColor(ThemeTokens.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                hPx
            ).apply {
                marginStart = dp(context, startInsetDp.toFloat())
                marginEnd = dp(context, 20f)
            }
        }
    }

    /**
     * Unified frameless vehicle control icon button (zero box/border clutter).
     */
    fun createControlIconButton(
        context: Context,
        iconType: AppIconType,
        sizeDp: Int = 28,
        active: Boolean = false,
        label: String? = null,
        showCheckDot: Boolean = false,
        dimmed: Boolean = false
    ): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 4f), dp(context, 8f), dp(context, 4f), dp(context, 8f))
            alpha = if (dimmed) 0.42f else 1f
        }

        val iconBoxSize = dp(context, (sizeDp + 14).toFloat())
        val iconFrame = FrameLayout(context)

        val iconView = AppIconView(
            context,
            iconType,
            if (active) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_PRIMARY
        ).apply {
            tag = "control_icon"
        }
        iconFrame.addView(
            iconView,
            FrameLayout.LayoutParams(dp(context, sizeDp.toFloat()), dp(context, sizeDp.toFloat()), Gravity.CENTER)
        )

        if (showCheckDot) {
            val dot = View(context).apply {
                tag = "control_check_dot"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ThemeTokens.ACCENT_LIGHT)
                    setStroke(dp(context, 1.5f), ThemeTokens.BG_CANVAS)
                }
            }
            iconFrame.addView(
                dot,
                FrameLayout.LayoutParams(dp(context, 10f), dp(context, 10f), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(context, 2f)
                    marginEnd = dp(context, 2f)
                }
            )
        }

        root.addView(iconFrame, LinearLayout.LayoutParams(iconBoxSize, iconBoxSize))

        if (!label.isNullOrBlank()) {
            val labelTv = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(if (active) ThemeTokens.ACCENT_LIGHT else ThemeTokens.TEXT_BODY)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                tag = "control_label"
            }
            root.addView(
                labelTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 5f)
                }
            )
        }

        MotionSpec.attachPressPhysics(root, pressedScale = 0.88f)
        return root
    }

    /**
     * Unified anchored floating dropdown popover (scale 0.92 -> 1.0, 180ms EASE_OUT_EXPO).
     */
    fun showAnchoredDropdown(
        context: Context,
        anchor: View,
        options: List<DropdownOption>,
        widthDp: Int = 224,
        alignEnd: Boolean = false,
        onSelect: (DropdownOption) -> Unit
    ): PopupWindow {
        val popupWidthPx = dp(context, widthDp.toFloat())
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(context, 12f)
                setColor(ThemeTokens.BG_POPOVER)
                setStroke(dp(context, 0.8f).coerceAtLeast(1), Color.parseColor("#2F323B"))
            }
            elevation = dpF(context, 12f)
            setPadding(0, dp(context, 4f), 0, dp(context, 4f))
        }

        val popup = PopupWindow(
            panel,
            popupWidthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dpF(context, 12f)
        }

        options.forEachIndexed { idx, opt ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 14f), dp(context, 11f), dp(context, 14f), dp(context, 11f))
            }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(
                TextView(context).apply {
                    text = opt.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setTextColor(
                        when {
                            opt.danger -> ThemeTokens.DANGER
                            opt.selected -> ThemeTokens.TEXT_PRIMARY
                            else -> ThemeTokens.TEXT_BODY
                        }
                    )
                    setTypeface(null, if (opt.selected) Typeface.BOLD else Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
            if (!opt.subtitle.isNullOrBlank()) {
                col.addView(
                    TextView(context).apply {
                        text = opt.subtitle
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        setTextColor(ThemeTokens.TEXT_SUB)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(context, 2f) }
                )
            }
            item.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (opt.selected) {
                item.addView(
                    AppIconView(context, AppIconType.CHECK_CIRCLE, ThemeTokens.ACCENT_LIGHT),
                    LinearLayout.LayoutParams(dp(context, 17f), dp(context, 17f)).apply {
                        marginStart = dp(context, 8f)
                    }
                )
            }

            MotionSpec.attachPressPhysics(item, pressedScale = 0.97f)
            item.setOnClickListener {
                popup.dismiss()
                onSelect(opt)
            }
            panel.addView(item)

            if (idx < options.lastIndex) {
                val sep = View(context).apply {
                    setBackgroundColor(Color.parseColor("#2A2D36"))
                }
                panel.addView(
                    sep,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (0.6f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
                    ).apply {
                        marginStart = dp(context, 14f)
                        marginEnd = dp(context, 14f)
                    }
                )
            }
        }

        panel.pivotX = if (alignEnd) popupWidthPx.toFloat() else 0f
        panel.pivotY = 0f
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f
        panel.alpha = 0f

        val xOff = if (alignEnd) anchor.width - popupWidthPx else 0
        popup.showAsDropDown(anchor, xOff, dp(context, 6f))
        panel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .start()

        return popup
    }

    /**
     * Unified minimalist centered 2-action confirmation dialog.
     */
    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "确认",
        cancelText: String = "取消",
        dangerConfirm: Boolean = false,
        onConfirm: () -> Unit
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(context, 16f)
                setColor(ThemeTokens.BG_SURFACE)
                setStroke(dp(context, 0.8f).coerceAtLeast(1), Color.parseColor("#2A2D36"))
            }
            setPadding(dp(context, 22f), dp(context, 22f), dp(context, 22f), dp(context, 18f))
        }

        card.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(ThemeTokens.TEXT_PRIMARY)
                setTypeface(null, Typeface.BOLD)
            }
        )

        card.addView(
            TextView(context).apply {
                text = message
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ThemeTokens.TEXT_SUB)
                setLineSpacing(dpF(context, 3f), 1f)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 10f)
                bottomMargin = dp(context, 20f)
            }
        )

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cancelBtn = createActionButton(context, cancelText, ButtonStyle.SECONDARY) {
            dialog.dismiss()
        }
        val confirmBtn = TextView(context).apply {
            text = confirmText
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            minimumHeight = dp(context, 44f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(context, 11f)
                setColor(if (dangerConfirm) ThemeTokens.DANGER else ThemeTokens.ACCENT_BLUE)
            }
            MotionSpec.attachPressPhysics(this, pressedScale = 0.96f)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        btnRow.addView(
            cancelBtn,
            LinearLayout.LayoutParams(0, dp(context, 44f), 1f).apply { marginEnd = dp(context, 10f) }
        )
        btnRow.addView(
            confirmBtn,
            LinearLayout.LayoutParams(0, dp(context, 44f), 1f)
        )
        card.addView(btnRow)

        val wrapper = FrameLayout(context).apply {
            setPadding(dp(context, 24f), 0, dp(context, 24f), 0)
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        dialog.setContentView(wrapper)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.62f)
        }
        dialog.show()
        MotionSpec.animateModalEnter(card)
        return dialog
    }

    /**
     * Unified minimalist action button.
     */
    fun createActionButton(
        context: Context,
        label: String,
        style: ButtonStyle,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            when (style) {
                ButtonStyle.PRIMARY -> {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ThemeTokens.TEXT_PRIMARY)
                    minimumHeight = dp(context, 46f)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpF(context, 12f)
                        setColor(ThemeTokens.ACCENT_BLUE)
                    }
                }
                ButtonStyle.SECONDARY -> {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setTypeface(null, Typeface.NORMAL)
                    setTextColor(ThemeTokens.TEXT_PRIMARY)
                    minimumHeight = dp(context, 44f)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpF(context, 11f)
                        setColor(ThemeTokens.BTN_SECONDARY)
                    }
                }
                ButtonStyle.DANGER_TEXT -> {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ThemeTokens.DANGER)
                    minimumHeight = dp(context, 48f)
                    setBackgroundColor(Color.TRANSPARENT)
                }
            }
            MotionSpec.attachPressPhysics(this, pressedScale = 0.97f, haptic = false)
            setOnClickListener { onClick() }
        }
    }
}

/**
 * Flagship 6-digit vehicle security PIN bottom sheet with 6 individual rounded digit boxes,
 * animated dot pop-in, built-in dark 3x4 numeric keypad, auto-submit on 6th digit,
 * horizontal error shake, and stepped Old -> New -> Confirm change wizard.
 */
object SixDigitPinSheet {

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun dpF(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun showVerifyPinSheet(
        context: Context,
        title: String = "输入车控安全密码",
        subtitle: String = "请输入 6 位数字密码以继续",
        onCompleted: (pin: String, showError: (String) -> Unit, dismiss: () -> Unit) -> Unit
    ): Dialog {
        return createPinSheetInternal(
            context = context,
            initialTitle = title,
            initialSubtitle = subtitle,
            skipLinkText = null,
            onSkipClick = null,
            onPinSixDigits = { pin, ui ->
                onCompleted(pin, { err -> ui.showError(err) }, { ui.dismissAnimated() })
            }
        )
    }

    fun showChangePinWizardSheet(
        context: Context,
        requireOldPin: Boolean,
        onCompleted: (oldPin: String?, newPin: String, showError: (String) -> Unit, dismiss: () -> Unit) -> Unit
    ): Dialog {
        var step = if (requireOldPin) 0 else 1
        var capturedOldPin: String? = null
        var capturedNewPin: String? = null

        val initTitle = if (step == 0) "输入原车控密码" else "设置新车控密码"
        val initSub = if (step == 0) "请输入当前 6 位车控安全密码" else "请输入新的 6 位数字密码"

        return createPinSheetInternal(
            context = context,
            initialTitle = initTitle,
            initialSubtitle = initSub,
            skipLinkText = if (requireOldPin) "首次设置？跳过原密码" else null,
            onSkipClick = { ui ->
                step = 1
                capturedOldPin = null
                ui.transitionStep("设置新车控密码", "请输入新的 6 位数字密码", false)
            },
            onPinSixDigits = { pin, ui ->
                when (step) {
                    0 -> {
                        capturedOldPin = pin
                        step = 1
                        ui.transitionStep("设置新车控密码", "请输入新的 6 位数字密码", false)
                    }
                    1 -> {
                        capturedNewPin = pin
                        step = 2
                        ui.transitionStep("确认新车控密码", "请再次输入以确认 6 位新密码", false)
                    }
                    else -> {
                        if (pin != capturedNewPin) {
                            ui.showError("两次输入的密码不一致，请重新确认")
                        } else {
                            onCompleted(
                                capturedOldPin,
                                pin,
                                { err ->
                                    if (requireOldPin && (err.contains("原") || err.contains("错误"))) {
                                        step = 0
                                        capturedOldPin = null
                                        capturedNewPin = null
                                        ui.transitionStep("输入原车控密码", "请输入当前 6 位车控安全密码", true)
                                    }
                                    ui.showError(err)
                                },
                                { ui.dismissAnimated() }
                            )
                        }
                    }
                }
            }
        )
    }

    private class PinSheetController(
        val showError: (String) -> Unit,
        val transitionStep: (title: String, subtitle: String, showSkip: Boolean) -> Unit,
        val dismissAnimated: () -> Unit
    )

    private fun createPinSheetInternal(
        context: Context,
        initialTitle: String,
        initialSubtitle: String,
        skipLinkText: String?,
        onSkipClick: ((PinSheetController) -> Unit)?,
        onPinSixDigits: (pin: String, ui: PinSheetController) -> Unit
    ): Dialog {
        var outerRef: View? = null
        val dialog = object : Dialog(context) {
            override fun dismiss() {
                val sheet = outerRef
                if (sheet == null || MotionSpec.isBypassingSheetDismiss(this)) {
                    super.dismiss()
                } else {
                    MotionSpec.dismissSheetAnimated(sheet, this)
                }
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val r = dpF(context, 22f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(ThemeTokens.BG_SURFACE)
            }
            setPadding(dp(context, 20f), dp(context, 10f), dp(context, 20f), dp(context, 22f))
        }
        outerRef = outer

        // Top minimalist drag handle bar
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(context, 2f)
                setColor(Color.parseColor("#444854"))
            }
        }
        outer.addView(
            handle,
            LinearLayout.LayoutParams(dp(context, 38f), dp(context, 4f)).apply {
                bottomMargin = dp(context, 18f)
            }
        )

        val headerBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val titleTv = TextView(context).apply {
            text = initialTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            gravity = Gravity.CENTER
        }
        val subtitleTv = TextView(context).apply {
            text = initialSubtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ThemeTokens.TEXT_SUB)
            gravity = Gravity.CENTER
        }
        headerBlock.addView(titleTv)
        headerBlock.addView(
            subtitleTv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 6f) }
        )
        outer.addView(
            headerBlock,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 20f) }
        )

        // 6 individual rounded digit boxes
        val boxRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val boxes = ArrayList<FrameLayout>(6)
        val dots = ArrayList<View>(6)
        val digits = StringBuilder(6)
        var locked = false

        fun boxBg(focused: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, 10f)
            setColor(Color.parseColor("#1E2026"))
            val strokePx = dp(context, if (focused) 1.5f else 1f).coerceAtLeast(1)
            setStroke(strokePx, if (focused) ThemeTokens.ACCENT_ACTIVE else Color.parseColor("#2C2F38"))
        }

        for (i in 0 until 6) {
            val cell = FrameLayout(context).apply {
                background = boxBg(i == 0)
            }
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ThemeTokens.TEXT_PRIMARY)
                }
                scaleX = 0f
                scaleY = 0f
                alpha = 0f
            }
            cell.addView(dot, FrameLayout.LayoutParams(dp(context, 11f), dp(context, 11f), Gravity.CENTER))
            boxes.add(cell)
            dots.add(dot)
            boxRow.addView(
                cell,
                LinearLayout.LayoutParams(dp(context, 44f), dp(context, 52f)).apply {
                    if (i < 5) marginEnd = dp(context, 8f)
                }
            )
        }
        outer.addView(boxRow)

        val errorTv = TextView(context).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(ThemeTokens.DANGER)
            gravity = Gravity.CENTER
            minHeight = dp(context, 20f)
        }
        outer.addView(
            errorTv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 10f) }
        )

        var skipTv: TextView? = null
        if (!skipLinkText.isNullOrBlank()) {
            skipTv = TextView(context).apply {
                text = skipLinkText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(ThemeTokens.ACCENT_ACTIVE)
                gravity = Gravity.CENTER
                setPadding(dp(context, 12f), dp(context, 4f), dp(context, 12f), dp(context, 8f))
            }
            outer.addView(skipTv)
        }

        fun syncBoxes(animateIndex: Int = -1) {
            val len = digits.length
            for (i in 0 until 6) {
                boxes[i].background = boxBg(i == len && !locked)
                val dot = dots[i]
                if (i < len) {
                    if (i == animateIndex) {
                        dot.animate().cancel()
                        dot.scaleX = 0.2f
                        dot.scaleY = 0.2f
                        dot.alpha = 0f
                        dot.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(110L)
                            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                            .start()
                    } else {
                        dot.scaleX = 1f
                        dot.scaleY = 1f
                        dot.alpha = 1f
                    }
                } else {
                    dot.animate().cancel()
                    dot.scaleX = 0f
                    dot.scaleY = 0f
                    dot.alpha = 0f
                }
            }
        }

        lateinit var controller: PinSheetController
        controller = PinSheetController(
            showError = { msg ->
                locked = false
                errorTv.text = msg
                AppHaptics.perform(boxRow, HapticLevel.ERROR_REJECT)
                MotionSpec.shakeError(boxRow)
                digits.setLength(0)
                syncBoxes()
            },
            transitionStep = { newTitle, newSub, showSkip ->
                locked = false
                digits.setLength(0)
                errorTv.text = ""
                syncBoxes()
                skipTv?.visibility = if (showSkip) View.VISIBLE else View.GONE
                headerBlock.animate().cancel()
                headerBlock.alpha = 0f
                headerBlock.translationX = dpF(context, 18f)
                titleTv.text = newTitle
                subtitleTv.text = newSub
                headerBlock.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(180L)
                    .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                    .start()
            },
            dismissAnimated = {
                MotionSpec.dismissSheetAnimated(outer, dialog)
            }
        )

        skipTv?.setOnClickListener {
            AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
            onSkipClick?.invoke(controller)
        }

        // Sleek built-in dark 3x4 numeric keypad
        val keypad = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("CLEAR", "0", "DEL")
        )

        fun onDigitPressed(d: String) {
            if (locked || digits.length >= 6) return
            errorTv.text = ""
            val idx = digits.length
            digits.append(d)
            syncBoxes(animateIndex = idx)
            if (digits.length == 6) {
                locked = true
                val pin = digits.toString()
                boxRow.postDelayed({
                    if (dialog.isShowing) {
                        onPinSixDigits(pin, controller)
                    }
                }, 90L)
            }
        }

        rows.forEachIndexed { rIdx, keys ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            keys.forEachIndexed { cIdx, key ->
                val btn = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpF(context, 12f)
                        setColor(
                            if (key == "CLEAR" || key == "DEL") Color.TRANSPARENT
                            else Color.parseColor("#24262E")
                        )
                    }
                }
                when (key) {
                    "DEL" -> {
                        val icon = AppIconView(context, AppIconType.BACK_ARROW, ThemeTokens.TEXT_BODY)
                        btn.addView(icon, FrameLayout.LayoutParams(dp(context, 22f), dp(context, 22f), Gravity.CENTER))
                    }
                    "CLEAR" -> {
                        btn.addView(
                            TextView(context).apply {
                                text = "清空"
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                setTextColor(ThemeTokens.TEXT_SUB)
                                gravity = Gravity.CENTER
                            },
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                    else -> {
                        btn.addView(
                            TextView(context).apply {
                                text = key
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                                setTypeface(null, Typeface.NORMAL)
                                setTextColor(ThemeTokens.TEXT_PRIMARY)
                                gravity = Gravity.CENTER
                            },
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                }

                MotionSpec.attachPressPhysics(btn, pressedScale = 0.92f, haptic = false)
                btn.setOnClickListener {
                    AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                    when (key) {
                        "CLEAR" -> {
                            if (!locked && digits.isNotEmpty()) {
                                digits.setLength(0)
                                errorTv.text = ""
                                syncBoxes()
                            }
                        }
                        "DEL" -> {
                            if (!locked && digits.isNotEmpty()) {
                                digits.deleteCharAt(digits.length - 1)
                                syncBoxes()
                            }
                        }
                        else -> onDigitPressed(key)
                    }
                }

                row.addView(
                    btn,
                    LinearLayout.LayoutParams(0, dp(context, 52f), 1f).apply {
                        if (cIdx < 2) marginEnd = dp(context, 8f)
                    }
                )
            }
            keypad.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rIdx < rows.lastIndex) bottomMargin = dp(context, 8f)
                }
            )
        }

        outer.addView(
            keypad,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 10f) }
        )

        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.58f)
            setWindowAnimations(0)
        }
        AppSystemInsets.applyBottomSheetEdgeToEdge(dialog, outer)
        MotionSpec.attachSheetSwipeToDismiss(outer, null, dialog)
        dialog.show()
        MotionSpec.animateSheetEnter(outer)
        return dialog
    }
}
