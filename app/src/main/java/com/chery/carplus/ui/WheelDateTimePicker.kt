package com.chery.carplus.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.TextView
import com.chery.carplus.R
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Supported modes for [WheelDateTimePicker], covering year-month, date,
 * date+hour, date+hour+minute, date+time+seconds, time-only, and ranges.
 */
enum class WheelDateTimeMode {
    YEAR_MONTH,
    DATE,
    DATE_HOUR,
    DATE_TIME,
    DATE_TIME_SECONDS,
    TIME_HM,
    TIME_HMS,
    DATE_RANGE,
    DATE_TIME_RANGE
}

/**
 * Custom smooth vertical wheel column styled for CarPlus Dark Automotive UI
 * (DESIGN_SYSTEM.md v2.6): pure Canvas rendering with progressive center-focus
 * typography, momentum fling, snap-to-row physics, tap-to-scroll, and LIGHT_TICK haptics.
 */
internal class WheelColumnView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val itemHeightPx = (44f * density).roundToInt().coerceAtLeast(1)
    private val visibleCount = 5
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    private var items: List<String> = emptyList()
    private var scrollOffsetPx = 0f
    private var selectedIndex = 0
    private var lastReportedIndex = -1

    private var downY = 0f
    private var lastTouchY = 0f
    private var dragging = false

    var onSelectedChanged: ((index: Int, label: String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val boldTypeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val normalTypeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun setItems(newItems: List<String>, initialIndex: Int = 0, notify: Boolean = false) {
        items = newItems
        val clamped = if (newItems.isEmpty()) 0 else initialIndex.coerceIn(0, newItems.lastIndex)
        scroller.abortAnimation()
        selectedIndex = clamped
        lastReportedIndex = clamped
        scrollOffsetPx = clamped * itemHeightPx.toFloat()
        invalidate()
        if (notify && newItems.isNotEmpty()) {
            onSelectedChanged?.invoke(clamped, newItems[clamped])
        }
    }

    fun getSelectedIndex(): Int = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    fun setSelectedIndex(index: Int, animate: Boolean = false) {
        if (items.isEmpty()) return
        val targetIdx = index.coerceIn(0, items.lastIndex)
        val targetOffset = targetIdx * itemHeightPx.toFloat()
        scroller.abortAnimation()
        if (!animate) {
            scrollOffsetPx = targetOffset
            selectedIndex = targetIdx
            lastReportedIndex = targetIdx
            invalidate()
        } else {
            val startY = scrollOffsetPx.roundToInt()
            val dy = targetOffset.roundToInt() - startY
            scroller.startScroll(0, startY, 0, dy, 220)
            postInvalidateOnAnimation()
        }
    }

    private fun maxScrollPx(): Float =
        ((items.size - 1).coerceAtLeast(0) * itemHeightPx).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = itemHeightPx * visibleCount
        val w = resolveSize((72f * density).roundToInt(), widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val cx = width / 2f
        val cy = height / 2f
        val exactIndex = scrollOffsetPx / itemHeightPx.toFloat()
        val centerIdx = exactIndex.roundToInt().coerceIn(0, items.lastIndex)

        val startIdx = (centerIdx - 3).coerceAtLeast(0)
        val endIdx = (centerIdx + 3).coerceAtMost(items.lastIndex)

        for (i in startIdx..endIdx) {
            val distItems = i - exactIndex
            val absDist = abs(distItems)
            if (absDist > 2.6f) continue

            val y = cy + distItems * itemHeightPx
            val focus = (1f - (absDist / 2.3f)).coerceIn(0f, 1f)

            val sizeSp = 13.5f + 3.5f * focus * focus
            paint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sizeSp,
                resources.displayMetrics
            )
            paint.typeface = if (absDist < 0.42f) boldTypeface else normalTypeface
            paint.color = blendColor(focus)

            val fm = paint.fontMetrics
            val baseline = y - (fm.ascent + fm.descent) / 2f
            canvas.drawText(items[i], cx, baseline, paint)
        }
    }

    private fun blendColor(focus: Float): Int {
        // Interpolate from subtle muted edge (#5A5E68, low alpha) to crisp pure white (#FFFFFF)
        val alpha = (56 + (199f * focus * focus)).roundToInt().coerceIn(48, 255)
        val r = (122 + (133f * focus)).roundToInt().coerceIn(0, 255)
        val g = (126 + (129f * focus)).roundToInt().coerceIn(0, 255)
        val b = (136 + (119f * focus)).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, r, g, b)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.abortAnimation()
                downY = event.y
                lastTouchY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastTouchY - event.y
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    val overLimit = itemHeightPx * 0.45f
                    scrollOffsetPx = (scrollOffsetPx + dy).coerceIn(-overLimit, maxScrollPx() + overLimit)
                    updateCurrentSelection(fromUser = true)
                    invalidate()
                }
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging) {
                    // Tap above/below center smoothly steps to tapped row
                    val deltaRows = ((event.y - height / 2f) / itemHeightPx.toFloat()).roundToInt()
                    if (deltaRows != 0) {
                        val target = (getSelectedIndex() + deltaRows).coerceIn(0, items.lastIndex)
                        setSelectedIndex(target, animate = true)
                        notifyIfChanged(target, haptic = true)
                    }
                } else {
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val vy = velocityTracker?.yVelocity ?: 0f
                    if (abs(vy) >= minFlingVelocity) {
                        scroller.fling(
                            0,
                            scrollOffsetPx.roundToInt(),
                            0,
                            (-vy * 0.85f).roundToInt(),
                            0,
                            0,
                            0,
                            maxScrollPx().roundToInt(),
                            0,
                            (itemHeightPx * 0.35f).roundToInt()
                        )
                        // Align fling destination directly onto an item center
                        val finalIdx = (scroller.finalY.toFloat() / itemHeightPx.toFloat())
                            .roundToInt()
                            .coerceIn(0, items.lastIndex)
                        val targetY = finalIdx * itemHeightPx
                        val curY = scrollOffsetPx.roundToInt()
                        val distancePx = abs(targetY - curY)
                        val durationMs = ((distancePx.toFloat() / abs(vy).coerceAtLeast(1f)) * 1800f)
                            .roundToInt()
                            .coerceIn(220, 650)
                        scroller.abortAnimation()
                        scroller.startScroll(0, curY, 0, targetY - curY, durationMs)
                        postInvalidateOnAnimation()
                    } else {
                        snapToNearest()
                    }
                }
                recycleVelocityTracker()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                snapToNearest()
                recycleVelocityTracker()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffsetPx = scroller.currY.toFloat()
            updateCurrentSelection(fromUser = true)
            postInvalidateOnAnimation()
        } else if (!dragging && items.isNotEmpty()) {
            val snapped = (scrollOffsetPx / itemHeightPx.toFloat()).roundToInt().coerceIn(0, items.lastIndex)
            val targetPx = snapped * itemHeightPx.toFloat()
            if (abs(scrollOffsetPx - targetPx) > 0.5f) {
                snapToNearest()
            }
        }
    }

    private fun snapToNearest() {
        if (items.isEmpty()) return
        val clampedIdx = (scrollOffsetPx / itemHeightPx.toFloat()).roundToInt().coerceIn(0, items.lastIndex)
        val targetPx = clampedIdx * itemHeightPx
        val curPx = scrollOffsetPx.roundToInt()
        if (curPx != targetPx) {
            scroller.startScroll(0, curPx, 0, targetPx - curPx, 180)
            postInvalidateOnAnimation()
        } else {
            scrollOffsetPx = targetPx.toFloat()
            invalidate()
        }
        notifyIfChanged(clampedIdx, haptic = false)
    }

    private fun updateCurrentSelection(fromUser: Boolean) {
        if (items.isEmpty()) return
        val idx = (scrollOffsetPx / itemHeightPx.toFloat()).roundToInt().coerceIn(0, items.lastIndex)
        selectedIndex = idx
        notifyIfChanged(idx, haptic = fromUser)
    }

    private fun notifyIfChanged(index: Int, haptic: Boolean) {
        if (index == lastReportedIndex || index !in items.indices) return
        lastReportedIndex = index
        if (haptic) {
            AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
        }
        onSelectedChanged?.invoke(index, items[index])
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        scroller.abortAnimation()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }
}

/**
 * Unified dark automotive bottom-sheet wheel Date / Time / Range picker
 * matching DESIGN_SYSTEM.md v2.6.
 */
object WheelDateTimePicker {

    private data class DateTimeParts(
        var year: Int,
        var month: Int, // 1..12
        var day: Int,   // 1..31
        var hour: Int,  // 0..23
        var minute: Int, // 0..59
        var second: Int  // 0..59
    ) {
        fun copyParts(): DateTimeParts = DateTimeParts(year, month, day, hour, minute, second)

        fun clampDay() {
            val maxDay = daysInMonth(year, month)
            if (day > maxDay) day = maxDay
            if (day < 1) day = 1
        }

        fun toMillis(): Long = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun format(mode: WheelDateTimeMode): String = when (mode) {
            WheelDateTimeMode.YEAR_MONTH -> String.format(Locale.US, "%04d-%02d", year, month)
            WheelDateTimeMode.DATE,
            WheelDateTimeMode.DATE_RANGE -> String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
            WheelDateTimeMode.DATE_HOUR -> String.format(Locale.US, "%04d-%02d-%02d %02d", year, month, day, hour)
            WheelDateTimeMode.DATE_TIME,
            WheelDateTimeMode.DATE_TIME_RANGE -> String.format(
                Locale.US,
                "%04d-%02d-%02d %02d:%02d",
                year, month, day, hour, minute
            )
            WheelDateTimeMode.DATE_TIME_SECONDS -> String.format(
                Locale.US,
                "%04d-%02d-%02d %02d:%02d:%02d",
                year, month, day, hour, minute, second
            )
            WheelDateTimeMode.TIME_HM -> String.format(Locale.US, "%02d:%02d", hour, minute)
            WheelDateTimeMode.TIME_HMS -> String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second)
        }
    }

    fun show(
        context: Context,
        mode: WheelDateTimeMode,
        title: String? = null,
        initialValue: String = "",
        minYear: Int = 1990,
        maxYear: Int = Calendar.getInstance().get(Calendar.YEAR) + 10,
        onConfirm: (formatted: String) -> Unit
    ): Dialog {
        require(mode != WheelDateTimeMode.DATE_RANGE && mode != WheelDateTimeMode.DATE_TIME_RANGE) {
            "Use showRange() for DATE_RANGE / DATE_TIME_RANGE modes"
        }
        val initial = parseDateTime(initialValue, mode).apply {
            year = year.coerceIn(minYear, maxYear)
            clampDay()
        }
        val resolvedTitle = title ?: defaultTitle(mode)
        return buildAndShowDialog(
            context = context,
            mode = mode,
            title = resolvedTitle,
            startParts = initial,
            endParts = null,
            minYear = minYear,
            maxYear = maxYear,
            onConfirmSingle = onConfirm,
            onConfirmRange = null
        )
    }

    fun showRange(
        context: Context,
        mode: WheelDateTimeMode = WheelDateTimeMode.DATE_TIME_RANGE,
        title: String? = null,
        initialStart: String = "",
        initialEnd: String = "",
        minYear: Int = 1990,
        maxYear: Int = Calendar.getInstance().get(Calendar.YEAR) + 10,
        onConfirm: (startFormatted: String, endFormatted: String) -> Unit
    ): Dialog {
        val effectiveMode = when (mode) {
            WheelDateTimeMode.DATE, WheelDateTimeMode.DATE_RANGE -> WheelDateTimeMode.DATE_RANGE
            else -> WheelDateTimeMode.DATE_TIME_RANGE
        }
        val start = parseDateTime(initialStart, effectiveMode).apply {
            year = year.coerceIn(minYear, maxYear)
            clampDay()
        }
        val end = if (initialEnd.isNotBlank()) {
            parseDateTime(initialEnd, effectiveMode)
        } else {
            start.copyParts().apply {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = start.toMillis()
                    add(Calendar.DAY_OF_MONTH, 1)
                }
                year = cal.get(Calendar.YEAR)
                month = cal.get(Calendar.MONTH) + 1
                day = cal.get(Calendar.DAY_OF_MONTH)
                hour = cal.get(Calendar.HOUR_OF_DAY)
                minute = cal.get(Calendar.MINUTE)
                second = 0
            }
        }.apply {
            year = year.coerceIn(minYear, maxYear)
            clampDay()
        }
        val resolvedTitle = title ?: defaultTitle(effectiveMode)
        return buildAndShowDialog(
            context = context,
            mode = effectiveMode,
            title = resolvedTitle,
            startParts = start,
            endParts = end,
            minYear = minYear,
            maxYear = maxYear,
            onConfirmSingle = null,
            onConfirmRange = onConfirm
        )
    }

    private fun buildAndShowDialog(
        context: Context,
        mode: WheelDateTimeMode,
        title: String,
        startParts: DateTimeParts,
        endParts: DateTimeParts?,
        minYear: Int,
        maxYear: Int,
        onConfirmSingle: ((String) -> Unit)?,
        onConfirmRange: ((String, String) -> Unit)?
    ): Dialog {
        val isRange = endParts != null
        var editingEnd = false
        fun activeParts(): DateTimeParts = if (isRange && editingEnd) endParts!! else startParts

        val dialog = Dialog(context, R.style.Theme_CarPlus_BottomSheetDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ThemeTokens.BG_SURFACE)
                val r = dpF(context, 20f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(0, dp(context, 10f), 0, dp(context, 24f))
        }

        // Top subtle drag handle
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(context, 2f)
                setColor(Color.parseColor("#484A52"))
            }
        }
        outer.addView(
            handle,
            LinearLayout.LayoutParams(dp(context, 38f), dp(context, 4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 8f)
            }
        )

        // Header: Cancel | Title | Confirm
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16f), dp(context, 6f), dp(context, 16f), dp(context, 10f))
        }
        val cancelBtn = TextView(context).apply {
            text = "取消"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ThemeTokens.TEXT_SUB)
            setPadding(dp(context, 6f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
            setOnClickListener {
                MotionSpec.dismissSheetAnimated(outer, dialog)
            }
        }
        val titleTv = TextView(context).apply {
            text = title
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val confirmBtn = TextView(context).apply {
            text = "确定"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.ACCENT_LIGHT)
            setPadding(dp(context, 10f), dp(context, 8f), dp(context, 6f), dp(context, 8f))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.94f)
        }
        header.addView(cancelBtn)
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(confirmBtn)
        outer.addView(header)

        // Optional Range Start / End Switcher Pills
        var startTabBox: LinearLayout? = null
        var endTabBox: LinearLayout? = null
        var startValTv: TextView? = null
        var endValTv: TextView? = null

        fun refreshRangeTabs() {
            if (!isRange) return
            startValTv?.text = startParts.format(mode)
            endValTv?.text = endParts!!.format(mode)
            styleRangeTab(context, startTabBox, selected = !editingEnd)
            styleRangeTab(context, endTabBox, selected = editingEnd)
        }

        if (isRange) {
            val rangeRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 16f), dp(context, 4f), dp(context, 16f), dp(context, 12f))
            }
            val (sBox, sVal) = createRangeTabView(context, "开始时间", startParts.format(mode))
            val (eBox, eVal) = createRangeTabView(context, "结束时间", endParts!!.format(mode))
            startTabBox = sBox
            endTabBox = eBox
            startValTv = sVal
            endValTv = eVal

            rangeRow.addView(sBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rangeRow.addView(
                TextView(context).apply {
                    text = "至"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(ThemeTokens.TEXT_SUB)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(context, 28f), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            rangeRow.addView(eBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            outer.addView(rangeRow)
        } else {
            outer.addView(
                View(context).apply { setBackgroundColor(ThemeTokens.DIVIDER) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (0.6f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
                )
            )
        }

        // Wheel Stage with subtle center highlight band
        val wheelStage = FrameLayout(context).apply {
            setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f))
        }

        val highlightBand = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(context, 10f)
                setColor(Color.parseColor("#24272F"))
                setStroke(
                    (0.7f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1),
                    Color.parseColor("#323642")
                )
            }
        }
        wheelStage.addView(
            highlightBand,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 44f),
                Gravity.CENTER_VERTICAL
            ).apply {
                marginStart = dp(context, 4f)
                marginEnd = dp(context, 4f)
            }
        )

        val columnsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val showYear = mode in setOf(
            WheelDateTimeMode.YEAR_MONTH,
            WheelDateTimeMode.DATE,
            WheelDateTimeMode.DATE_HOUR,
            WheelDateTimeMode.DATE_TIME,
            WheelDateTimeMode.DATE_TIME_SECONDS,
            WheelDateTimeMode.DATE_RANGE,
            WheelDateTimeMode.DATE_TIME_RANGE
        )
        val showMonth = showYear
        val showDay = mode in setOf(
            WheelDateTimeMode.DATE,
            WheelDateTimeMode.DATE_HOUR,
            WheelDateTimeMode.DATE_TIME,
            WheelDateTimeMode.DATE_TIME_SECONDS,
            WheelDateTimeMode.DATE_RANGE,
            WheelDateTimeMode.DATE_TIME_RANGE
        )
        val showHour = mode in setOf(
            WheelDateTimeMode.DATE_HOUR,
            WheelDateTimeMode.DATE_TIME,
            WheelDateTimeMode.DATE_TIME_SECONDS,
            WheelDateTimeMode.TIME_HM,
            WheelDateTimeMode.TIME_HMS,
            WheelDateTimeMode.DATE_TIME_RANGE
        )
        val showMinute = mode in setOf(
            WheelDateTimeMode.DATE_TIME,
            WheelDateTimeMode.DATE_TIME_SECONDS,
            WheelDateTimeMode.TIME_HM,
            WheelDateTimeMode.TIME_HMS,
            WheelDateTimeMode.DATE_TIME_RANGE
        )
        val showSecond = mode in setOf(
            WheelDateTimeMode.DATE_TIME_SECONDS,
            WheelDateTimeMode.TIME_HMS
        )

        val years = (minYear..maxYear).toList()
        val yearLabels = years.map { "${it}年" }
        val monthLabels = (1..12).map { String.format(Locale.US, "%02d月", it) }
        val hourLabels = (0..23).map { String.format(Locale.US, "%02d时", it) }
        val minuteLabels = (0..59).map { String.format(Locale.US, "%02d分", it) }
        val secondLabels = (0..59).map { String.format(Locale.US, "%02d秒", it) }

        var yearCol: WheelColumnView? = null
        var monthCol: WheelColumnView? = null
        var dayCol: WheelColumnView? = null
        var hourCol: WheelColumnView? = null
        var minuteCol: WheelColumnView? = null
        var secondCol: WheelColumnView? = null

        fun refreshDayColumn(parts: DateTimeParts) {
            val col = dayCol ?: return
            val maxD = daysInMonth(parts.year, parts.month)
            if (parts.day > maxD) parts.day = maxD
            val labels = (1..maxD).map { String.format(Locale.US, "%02d日", it) }
            col.setItems(labels, (parts.day - 1).coerceIn(0, maxD - 1), notify = false)
        }

        fun syncWheelsFromActive(animate: Boolean) {
            val p = activeParts()
            yearCol?.setSelectedIndex((p.year - minYear).coerceIn(0, years.lastIndex), animate)
            monthCol?.setSelectedIndex((p.month - 1).coerceIn(0, 11), animate)
            refreshDayColumn(p)
            hourCol?.setSelectedIndex(p.hour.coerceIn(0, 23), animate)
            minuteCol?.setSelectedIndex(p.minute.coerceIn(0, 59), animate)
            secondCol?.setSelectedIndex(p.second.coerceIn(0, 59), animate)
            refreshRangeTabs()
        }

        if (showYear) {
            yearCol = WheelColumnView(context).apply {
                setItems(yearLabels, (activeParts().year - minYear).coerceIn(0, years.lastIndex))
                onSelectedChanged = { idx, _ ->
                    val p = activeParts()
                    p.year = years[idx]
                    refreshDayColumn(p)
                    refreshRangeTabs()
                }
            }
            columnsRow.addView(yearCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f))
        }
        if (showMonth) {
            monthCol = WheelColumnView(context).apply {
                setItems(monthLabels, (activeParts().month - 1).coerceIn(0, 11))
                onSelectedChanged = { idx, _ ->
                    val p = activeParts()
                    p.month = idx + 1
                    refreshDayColumn(p)
                    refreshRangeTabs()
                }
            }
            columnsRow.addView(monthCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (showDay) {
            dayCol = WheelColumnView(context).apply {
                val p = activeParts()
                val maxD = daysInMonth(p.year, p.month)
                setItems((1..maxD).map { String.format(Locale.US, "%02d日", it) }, (p.day - 1).coerceIn(0, maxD - 1))
                onSelectedChanged = { idx, _ ->
                    activeParts().day = idx + 1
                    refreshRangeTabs()
                }
            }
            columnsRow.addView(dayCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (showHour) {
            hourCol = WheelColumnView(context).apply {
                setItems(hourLabels, activeParts().hour.coerceIn(0, 23))
                onSelectedChanged = { idx, _ ->
                    activeParts().hour = idx
                    refreshRangeTabs()
                }
            }
            columnsRow.addView(hourCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f))
        }
        if (showMinute) {
            minuteCol = WheelColumnView(context).apply {
                setItems(minuteLabels, activeParts().minute.coerceIn(0, 59))
                onSelectedChanged = { idx, _ ->
                    activeParts().minute = idx
                    refreshRangeTabs()
                }
            }
            columnsRow.addView(minuteCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f))
        }
        if (showSecond) {
            secondCol = WheelColumnView(context).apply {
                setItems(secondLabels, activeParts().second.coerceIn(0, 59))
                onSelectedChanged = { idx, _ ->
                    activeParts().second = idx
                    refreshRangeTabs()
                }
            }
            columnsRow.addView(secondCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f))
        }

        wheelStage.addView(
            columnsRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        outer.addView(wheelStage)

        if (isRange) {
            startTabBox?.setOnClickListener {
                if (editingEnd) {
                    AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                    editingEnd = false
                    syncWheelsFromActive(animate = true)
                }
            }
            endTabBox?.setOnClickListener {
                if (!editingEnd) {
                    AppHaptics.perform(it, HapticLevel.LIGHT_TICK)
                    editingEnd = true
                    syncWheelsFromActive(animate = true)
                }
            }
            refreshRangeTabs()
        }

        confirmBtn.setOnClickListener {
            if (isRange) {
                val sMs = startParts.toMillis()
                val eMs = endParts!!.toMillis()
                if (eMs < sMs) {
                    AppHaptics.perform(outer, HapticLevel.ERROR_REJECT)
                    Toast.makeText(context, "结束时间不能早于开始时间", Toast.LENGTH_SHORT).show()
                    if (!editingEnd) {
                        editingEnd = true
                        syncWheelsFromActive(animate = true)
                    }
                    return@setOnClickListener
                }
                AppHaptics.perform(confirmBtn, HapticLevel.LIGHT_TICK)
                val sFmt = startParts.format(mode)
                val eFmt = endParts.format(mode)
                MotionSpec.dismissSheetAnimated(outer, dialog) {
                    onConfirmRange?.invoke(sFmt, eFmt)
                }
            } else {
                AppHaptics.perform(confirmBtn, HapticLevel.LIGHT_TICK)
                val formatted = startParts.format(mode)
                MotionSpec.dismissSheetAnimated(outer, dialog) {
                    onConfirmSingle?.invoke(formatted)
                }
            }
        }

        AppSystemInsets.installBottomSheetContent(dialog, outer, dimAmount = 0.62f)
        dialog.show()
        MotionSpec.animateSheetEnter(outer)
        return dialog
    }

    private fun createRangeTabView(
        context: Context,
        label: String,
        initialValue: String
    ): Pair<LinearLayout, TextView> {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 10f), dp(context, 9f), dp(context, 10f), dp(context, 9f))
            isClickable = true
            isFocusable = true
            MotionSpec.attachPressPhysics(this, pressedScale = 0.97f, haptic = false)
        }
        box.addView(
            TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(ThemeTokens.TEXT_SUB)
                gravity = Gravity.CENTER
            }
        )
        val valTv = TextView(context).apply {
            text = initialValue
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT_PRIMARY)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        box.addView(
            valTv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 3f) }
        )
        return box to valTv
    }

    private fun styleRangeTab(context: Context, box: LinearLayout?, selected: Boolean) {
        if (box == null) return
        box.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, 10f)
            setColor(if (selected) Color.parseColor("#1B2840") else Color.parseColor("#23252C"))
            setStroke(
                dp(context, if (selected) 1.1f else 0.7f).coerceAtLeast(1),
                if (selected) ThemeTokens.ACCENT_LIGHT else ThemeTokens.DIVIDER
            )
        }
    }

    private fun defaultTitle(mode: WheelDateTimeMode): String = when (mode) {
        WheelDateTimeMode.YEAR_MONTH -> "选择年月"
        WheelDateTimeMode.DATE -> "选择日期"
        WheelDateTimeMode.DATE_HOUR -> "选择日期与小时"
        WheelDateTimeMode.DATE_TIME -> "选择日期时间"
        WheelDateTimeMode.DATE_TIME_SECONDS -> "选择精确时间"
        WheelDateTimeMode.TIME_HM,
        WheelDateTimeMode.TIME_HMS -> "选择时间"
        WheelDateTimeMode.DATE_RANGE -> "选择日期区间"
        WheelDateTimeMode.DATE_TIME_RANGE -> "选择时间区间"
    }

    private fun parseDateTime(raw: String, mode: WheelDateTimeMode): DateTimeParts {
        val cal = Calendar.getInstance()
        val parts = DateTimeParts(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            second = if (mode == WheelDateTimeMode.DATE_TIME_SECONDS || mode == WheelDateTimeMode.TIME_HMS) {
                cal.get(Calendar.SECOND)
            } else {
                0
            }
        )
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return parts

        runCatching {
            val tokens = trimmed.split(" ", "T")
            val isTimeOnly = mode == WheelDateTimeMode.TIME_HM || mode == WheelDateTimeMode.TIME_HMS
            if (!isTimeOnly && tokens.isNotEmpty() && tokens[0].contains("-")) {
                val d = tokens[0].split("-")
                if (d.isNotEmpty()) parts.year = d[0].toIntOrNull() ?: parts.year
                if (d.size >= 2) parts.month = (d[1].toIntOrNull() ?: parts.month).coerceIn(1, 12)
                if (d.size >= 3) parts.day = (d[2].toIntOrNull() ?: parts.day).coerceIn(1, 31)
            }
            val timeToken = when {
                isTimeOnly -> tokens[0]
                tokens.size >= 2 -> tokens[1]
                else -> ""
            }
            if (timeToken.isNotBlank()) {
                if (timeToken.contains(":")) {
                    val t = timeToken.split(":")
                    if (t.isNotEmpty()) parts.hour = (t[0].toIntOrNull() ?: parts.hour).coerceIn(0, 23)
                    if (t.size >= 2) parts.minute = (t[1].toIntOrNull() ?: parts.minute).coerceIn(0, 59)
                    if (t.size >= 3) parts.second = (t[2].toIntOrNull() ?: parts.second).coerceIn(0, 59)
                } else {
                    timeToken.toIntOrNull()?.let { parts.hour = it.coerceIn(0, 23) }
                }
            }
        }
        parts.clampDay()
        return parts
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun dpF(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density
}
