package com.chery.carplus.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tiered haptic feedback specification (per DESIGN_SYSTEM.md v2.3).
 */
enum class HapticLevel {
    NONE,
    LIGHT_TICK,
    MEDIUM_CONFIRM,
    LONG_PRESS_MODE,
    ERROR_REJECT
}

/**
 * Centralized API-safe tactile feedback dispatcher.
 */
object AppHaptics {
    fun perform(view: View?, level: HapticLevel) {
        val target = view ?: return
        when (level) {
            HapticLevel.NONE -> return
            HapticLevel.LIGHT_TICK -> {
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    HapticFeedbackConstants.CLOCK_TICK
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
                target.performHapticFeedback(code)
            }
            HapticLevel.MEDIUM_CONFIRM -> {
                target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            HapticLevel.LONG_PRESS_MODE -> {
                target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            HapticLevel.ERROR_REJECT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    target.performHapticFeedback(HapticFeedbackConstants.REJECT)
                } else {
                    target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        }
    }
}

/**
 * Shared automotive-inspired motion choreography primitives for CarPlus UI.
 *
 * Principles:
 * 1. Physical Realism: subtle press depression, springy release, damped arrival.
 * 2. Hierarchical Guidance: Z-axis depth recession when sheets open.
 * 3. Immediate Response: sub-100ms touch feedback + haptic confirmation.
 * 4. Restrained Rhythm & Spatial Continuity: directional transitions and sliding pills.
 */
object MotionSpec {

    val EASE_OUT_EXPO: Interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    val EASE_STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val EASE_SPRING_RELEASE: Interpolator = PathInterpolator(0.22f, 1.12f, 0.36f, 1f)

    private val activeTintAnimators = WeakHashMap<AppIconView, ValueAnimator>()
    private val activeShakeAnimators = WeakHashMap<View, ValueAnimator>()
    private val dismissingDialogs = WeakHashMap<Dialog, Boolean>()
    private val bypassDismissDialogs = WeakHashMap<Dialog, Boolean>()

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    private fun dpInt(context: Context, value: Float): Int =
        dp(context, value).roundToInt()

    /**
     * Attaches immediate press physics (scale + subtle opacity + optional haptic)
     * and springy release while returning false so click/long-click listeners stay intact.
     * Default haptic is false so navigation rows / back arrows stay restrained.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachPressPhysics(
        view: View,
        pressedScale: Float = 0.94f,
        haptic: Boolean = false
    ) {
        var restAlpha = 1f
        var pressed = false
        var animatingPress = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!v.isEnabled) return@setOnTouchListener false
                    if (!animatingPress) {
                        restAlpha = v.alpha
                    }
                    pressed = true
                    animatingPress = true
                    v.animate().cancel()
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .alpha((restAlpha * 0.86f).coerceIn(0.15f, 1f))
                        .setDuration(90L)
                        .setInterpolator(EASE_OUT_EXPO)
                        .setListener(null)
                        .start()
                    if (haptic) {
                        AppHaptics.perform(v, HapticLevel.LIGHT_TICK)
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!pressed) return@setOnTouchListener false
                    pressed = false
                    v.animate().cancel()
                    val targetAlpha = if (v.isEnabled) restAlpha else v.alpha
                    var releaseCancelled = false
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(targetAlpha)
                        .setDuration(160L)
                        .setInterpolator(EASE_SPRING_RELEASE)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationCancel(animation: Animator) {
                                releaseCancelled = true
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                if (!releaseCancelled && !pressed) {
                                    animatingPress = false
                                }
                                v.animate().setListener(null)
                            }
                        })
                        .start()
                }
            }
            false
        }
    }

    /**
     * Spatial-continuity container transition along a shared horizontal (or vertical) axis:
     * - direction > 0: forward shared-axis push (incoming +22% width & 0.985->1 scale; outgoing -12% & 0.98 scale)
     * - direction < 0: exact reverse mirror (incoming -14% width & 0.98->1 scale; outgoing +22% right slide)
     * - direction == 0: subtle +14dp vertical glide + fade
     * Smoothly transitions previous child(ren) out before removing them.
     */
    fun transitionContainer(
        container: FrameLayout,
        newContent: View,
        direction: Int = 0
    ) {
        val ctx = container.context
        (newContent.parent as? ViewGroup)?.removeView(newContent)

        val outgoing = ArrayList<View>(container.childCount)
        for (i in 0 until container.childCount) {
            outgoing.add(container.getChildAt(i))
        }

        val params = newContent.layoutParams ?: FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val widthPx = container.width.takeIf { it > 0 }?.toFloat()
            ?: ctx.resources.displayMetrics.widthPixels.toFloat()
        val enterShiftX = when {
            direction > 0 -> (widthPx * 0.22f).coerceAtLeast(dp(ctx, 72f))
            direction < 0 -> -(widthPx * 0.14f).coerceAtLeast(dp(ctx, 44f))
            else -> 0f
        }
        val enterScale = when {
            direction > 0 -> 0.985f
            direction < 0 -> 0.98f
            else -> 1f
        }

        newContent.animate().cancel()
        newContent.alpha = 0f
        newContent.translationX = enterShiftX
        newContent.translationY = if (direction == 0) dp(ctx, 14f) else 0f
        newContent.scaleX = enterScale
        newContent.scaleY = enterScale

        container.addView(newContent, params)

        newContent.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(if (direction > 0) 260L else if (direction < 0) 240L else 220L)
            .setInterpolator(EASE_OUT_EXPO)
            .start()

        val exitShiftX = when {
            direction > 0 -> -(widthPx * 0.12f).coerceAtLeast(dp(ctx, 36f))
            direction < 0 -> (widthPx * 0.22f).coerceAtLeast(dp(ctx, 72f))
            else -> 0f
        }
        val exitScale = if (direction > 0) 0.98f else 1f

        for (old in outgoing) {
            old.isClickable = false
            old.isFocusable = false
            old.animate().cancel()
            old.animate()
                .alpha(0f)
                .translationX(exitShiftX)
                .scaleX(exitScale)
                .scaleY(exitScale)
                .setDuration(200L)
                .setInterpolator(EASE_STANDARD)
                .withEndAction {
                    container.removeView(old)
                }
                .start()
        }
    }

    /**
     * Full-screen overlay subpage push (Level-2 / Level-3 navigation):
     * slides [incoming] in along the shared horizontal axis while gently receding [underlying]
     * with parallax (-28dp) and Z-depth scale (0.975f) for tactile spatial continuity.
     */
    fun animateSubpageEnter(incoming: View, underlying: View? = null) {
        val ctx = incoming.context
        val widthPx = (incoming.parent as? View)?.width?.takeIf { it > 0 }?.toFloat()
            ?: ctx.resources.displayMetrics.widthPixels.toFloat()
        val startX = (widthPx * 0.20f).coerceAtLeast(dp(ctx, 84f))

        incoming.animate().cancel()
        incoming.alpha = 0f
        incoming.translationX = startX
        incoming.scaleX = 0.99f
        incoming.scaleY = 0.99f
        incoming.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(EASE_OUT_EXPO)
            .setListener(null)
            .start()

        underlying?.let { under ->
            under.animate().cancel()
            under.animate()
                .translationX(-dp(ctx, 28f))
                .scaleX(0.975f)
                .scaleY(0.975f)
                .alpha(0.86f)
                .setDuration(260L)
                .setInterpolator(EASE_OUT_EXPO)
                .setListener(null)
                .start()
        }
    }

    /**
     * Reverse mirror of [animateSubpageEnter]: slides [outgoing] smoothly rightward while restoring
     * [underlying] back to neutral resting depth, then invokes [onEnd] cleanly (with watchdog safety).
     */
    fun animateSubpageExit(
        outgoing: View,
        underlying: View? = null,
        onEnd: () -> Unit
    ) {
        val ctx = outgoing.context
        val widthPx = (outgoing.parent as? View)?.width?.takeIf { it > 0 }?.toFloat()
            ?: ctx.resources.displayMetrics.widthPixels.toFloat()
        val exitX = (widthPx * 0.24f).coerceAtLeast(dp(ctx, 96f))

        underlying?.let { under ->
            under.animate().cancel()
            under.animate()
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(240L)
                .setInterpolator(EASE_OUT_EXPO)
                .setListener(null)
                .start()
        }

        outgoing.isClickable = false
        outgoing.isFocusable = false
        outgoing.animate().cancel()

        var finished = false
        var fallback: Runnable? = null
        val finishOnce = {
            if (!finished) {
                finished = true
                fallback?.let(outgoing::removeCallbacks)
                fallback = null
                onEnd()
            }
        }
        val watchdog = Runnable { finishOnce() }
        fallback = watchdog
        outgoing.postDelayed(watchdog, 280L)

        outgoing.animate()
            .translationX(exitX)
            .alpha(0f)
            .setDuration(200L)
            .setInterpolator(EASE_STANDARD)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    outgoing.animate().setListener(null)
                    finishOnce()
                }

                override fun onAnimationCancel(animation: Animator) {
                    outgoing.animate().setListener(null)
                    finishOnce()
                }
            })
            .start()
    }

    /**
     * Directional spatial glide for switching segmented tab panels or inline view content:
     * `direction > 0` enters from +18dp right, `direction < 0` from -18dp left, `0` from +10dp below.
     */
    fun animatePanelSwitch(container: View, direction: Int = 0) {
        val ctx = container.context
        container.animate().cancel()
        container.alpha = 0f
        container.translationX = when {
            direction > 0 -> dp(ctx, 18f)
            direction < 0 -> -dp(ctx, 18f)
            else -> 0f
        }
        container.translationY = if (direction == 0) dp(ctx, 10f) else 0f
        container.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(200L)
            .setInterpolator(EASE_OUT_EXPO)
            .setListener(null)
            .start()
    }

    /**
     * Smooth collapsible section transition with optional 180-degree chevron rotation.
     */
    fun animateExpandCollapse(content: View, expanded: Boolean, chevron: View? = null) {
        chevron?.let { ch ->
            ch.animate().cancel()
            ch.animate()
                .rotation(if (expanded) 180f else 0f)
                .setDuration(200L)
                .setInterpolator(EASE_OUT_EXPO)
                .start()
        }
        content.animate().cancel()
        if (expanded) {
            content.visibility = View.VISIBLE
            content.alpha = 0f
            content.translationY = -dp(content.context, 8f)
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200L)
                .setInterpolator(EASE_OUT_EXPO)
                .setListener(null)
                .start()
        } else {
            if (content.visibility != View.VISIBLE) return
            content.animate()
                .alpha(0f)
                .translationY(-dp(content.context, 6f))
                .setDuration(150L)
                .setInterpolator(EASE_STANDARD)
                .withEndAction {
                    content.visibility = View.GONE
                    content.translationY = 0f
                    content.alpha = 1f
                }
                .start()
        }
    }

    private fun findSheetScrim(sheetView: View): View? =
        (sheetView.parent as? ViewGroup)?.findViewWithTag(AppSystemInsets.TAG_SHEET_SCRIM)

    /**
     * Bottom sheet entrance: rises from +64dp while fading in over 260ms,
     * with synchronized sibling scrim fade-in over 240ms.
     */
    fun animateSheetEnter(sheetView: View) {
        val scrim = findSheetScrim(sheetView)
        scrim?.let {
            it.animate().cancel()
            it.alpha = 0f
            it.animate()
                .alpha(1f)
                .setDuration(240L)
                .setInterpolator(EASE_OUT_EXPO)
                .start()
        }
        sheetView.animate().cancel()
        sheetView.translationY = dp(sheetView.context, 64f)
        sheetView.alpha = 0f
        sheetView.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260L)
            .setInterpolator(EASE_OUT_EXPO)
            .start()
    }

    /**
     * Bottom sheet exit: glides down 48dp while fading out over 170ms, then invokes [onEnd].
     */
    fun animateSheetExit(sheetView: View, onEnd: () -> Unit) {
        findSheetScrim(sheetView)?.let {
            it.animate().cancel()
            it.animate()
                .alpha(0f)
                .setDuration(160L)
                .setInterpolator(EASE_STANDARD)
                .start()
        }
        sheetView.animate().cancel()
        var ended = false
        val finish = {
            if (!ended) {
                ended = true
                onEnd()
            }
        }
        sheetView.animate()
            .translationY(dp(sheetView.context, 48f))
            .alpha(0f)
            .setDuration(170L)
            .setInterpolator(EASE_STANDARD)
            .withEndAction { finish() }
            .start()
    }

    /**
     * Returns true when [MotionSpec] has finished the slide-out animation and is
     * invoking the final [Dialog.dismiss] so custom Dialog subclasses can call `super.dismiss()`.
     */
    fun isBypassingSheetDismiss(dialog: Dialog): Boolean =
        bypassDismissDialogs[dialog] == true

    /**
     * Synchronously dismisses a bottom sheet dialog without running exit animations.
     * Use during Activity/screen teardown or before replacing an already-open sheet.
     */
    fun dismissSheetImmediately(dialog: Dialog) {
        dismissingDialogs.remove(dialog)
        bypassDismissDialogs[dialog] = true
        try {
            if (dialog.isShowing) {
                runCatching { dialog.dismiss() }
            }
        } finally {
            bypassDismissDialogs.remove(dialog)
        }
    }

    /**
     * Smoothly slides [sheetView] completely off the bottom of the screen (200ms EASE_STANDARD)
     * while fading its backdrop scrim to 0 in parallel, then dismisses [dialog] on completion.
     * Safe against duplicate calls and pre-layout invocation.
     */
    private fun findHostActivity(context: Context?): Activity? {
        var cur = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return cur as? Activity
    }

    /**
     * Returns true if [context] is backed by an [Activity] that is neither finishing nor destroyed,
     * preventing [android.view.WindowManager.BadTokenException] on asynchronous dialog/popup presentation.
     */
    fun isHostActivityAlive(context: Context?): Boolean {
        val host = findHostActivity(context) ?: return false
        return !host.isFinishing && !host.isDestroyed
    }

    fun dismissSheetAnimated(
        sheetView: View,
        dialog: Dialog,
        onDismissStart: () -> Unit = {}
    ) {
        if (dismissingDialogs[dialog] == true) return
        dismissingDialogs[dialog] = true
        onDismissStart()

        val scrim = findSheetScrim(sheetView)
        scrim?.isClickable = false

        val hostActivity = findHostActivity(dialog.context)
        if (hostActivity != null && (hostActivity.isFinishing || hostActivity.isDestroyed)) {
            scrim?.animate()?.cancel()
            sheetView.animate().cancel()
            dismissSheetImmediately(dialog)
            return
        }

        // If dismissed before first draw while still invisible, close immediately without blocking touches
        if (!sheetView.isLaidOut && sheetView.alpha == 0f) {
            scrim?.animate()?.cancel()
            sheetView.animate().cancel()
            dismissSheetImmediately(dialog)
            return
        }

        scrim?.let {
            it.animate().cancel()
            it.animate()
                .alpha(0f)
                .setDuration(180L)
                .setInterpolator(EASE_STANDARD)
                .start()
        }

        sheetView.animate().cancel()
        val offscreenY = if (sheetView.height > 0) {
            sheetView.height.toFloat()
        } else {
            val lpHeight = sheetView.layoutParams?.height?.takeIf { it > 0 } ?: 0
            val screenH = sheetView.resources.displayMetrics.heightPixels
            maxOf(lpHeight, screenH).toFloat()
        }
        var finalized = false
        var fallbackRunnable: Runnable? = null
        val finalizeDismiss = {
            if (!finalized) {
                finalized = true
                fallbackRunnable?.let(sheetView::removeCallbacks)
                fallbackRunnable = null
                bypassDismissDialogs[dialog] = true
                try {
                    if (dialog.isShowing) {
                        runCatching { dialog.dismiss() }
                    }
                } finally {
                    bypassDismissDialogs.remove(dialog)
                    dismissingDialogs.remove(dialog)
                }
            }
        }
        val watchdog = Runnable { finalizeDismiss() }
        fallbackRunnable = watchdog
        sheetView.postDelayed(watchdog, 320L)
        sheetView.animate()
            .translationY(offscreenY)
            .setDuration(200L)
            .setInterpolator(EASE_STANDARD)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sheetView.animate().setListener(null)
                    finalizeDismiss()
                }

                override fun onAnimationCancel(animation: Animator) {
                    sheetView.animate().setListener(null)
                    finalizeDismiss()
                }
            })
            .start()
    }

    /**
     * Attaches physical 1:1 vertical swipe-to-dismiss to a bottom sheet dialog:
     * - Dragging down on the sheet/handle bar (or on [scrollView] when `scrollY <= 0`) translates
     *   [sheetView] downward 1:1 and fades the scrim proportionally with zero window-manager jank.
     * - Releasing past 110dp OR with downward velocity > 1100px/s smoothly slides off-screen and
     *   dismisses; otherwise springs back smoothly to 0 in 240ms.
     * - Back key also triggers [dismissSheetAnimated].
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachSheetSwipeToDismiss(
        sheetView: View,
        scrollView: ScrollView?,
        dialog: Dialog,
        onDismissStart: () -> Unit = {}
    ) {
        val touchSlop = ViewConfiguration.get(sheetView.context).scaledTouchSlop.toFloat()
        val dismissThresholdPx = dp(sheetView.context, 110f)
        val dismissVelocityPxPerSec = 1100f

        var baseDim = dialog.window?.attributes?.dimAmount?.takeIf { it > 0f } ?: 0.55f
        var downRawY = 0f
        var dragging = false
        var velocityTracker: VelocityTracker? = null

        fun updateDimForOffset(dy: Float) {
            val h = if (sheetView.height > 0) sheetView.height.toFloat() else dp(sheetView.context, 420f)
            val progress = (dy / h).coerceIn(0f, 1f)
            val scrim = findSheetScrim(sheetView)
            if (scrim != null) {
                scrim.animate().cancel()
                scrim.alpha = (1f - progress).coerceIn(0f, 1f)
            } else {
                dialog.window?.setDimAmount((baseDim * (1f - progress * 0.85f)).coerceAtLeast(0f))
            }
        }

        fun finishDrag() {
            val vy = velocityTracker?.let {
                it.computeCurrentVelocity(1000)
                it.yVelocity
            } ?: 0f
            velocityTracker?.recycle()
            velocityTracker = null

            val wasDragging = dragging
            dragging = false
            if (!wasDragging) return

            if (sheetView.translationY > dismissThresholdPx || vy > dismissVelocityPxPerSec) {
                dismissSheetAnimated(sheetView, dialog, onDismissStart)
            } else {
                sheetView.animate().cancel()
                sheetView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(240L)
                    .setInterpolator(EASE_OUT_EXPO)
                    .start()
                val scrim = findSheetScrim(sheetView)
                if (scrim != null) {
                    scrim.animate().cancel()
                    scrim.animate()
                        .alpha(1f)
                        .setDuration(220L)
                        .setInterpolator(EASE_OUT_EXPO)
                        .start()
                } else {
                    dialog.window?.setDimAmount(baseDim)
                }
            }
        }

        // Direct touch handler on the sheet container (covers top drag handle bar & header)
        sheetView.setOnTouchListener { _, event ->
            if (dismissingDialogs[dialog] == true) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    baseDim = dialog.window?.attributes?.dimAmount?.takeIf { it > 0f } ?: baseDim
                    downRawY = event.rawY
                    dragging = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val rawDy = event.rawY - downRawY
                    if (!dragging && rawDy > touchSlop) {
                        dragging = true
                        downRawY = event.rawY
                        sheetView.animate().cancel()
                        sheetView.alpha = 1f
                    }
                    if (dragging) {
                        val dy = (event.rawY - downRawY).coerceAtLeast(0f)
                        sheetView.translationY = dy
                        updateDimForOffset(dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    finishDrag()
                    true
                }
                else -> false
            }
        }

        // Cooperative touch handler on inner ScrollView: hands off to swipe-to-dismiss only when at top
        var trackingScrollGesture = false
        var scrollStartedAtTop = false
        scrollView?.setOnTouchListener { sv, event ->
            if (dismissingDialogs[dialog] == true) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    baseDim = dialog.window?.attributes?.dimAmount?.takeIf { it > 0f } ?: baseDim
                    downRawY = event.rawY
                    dragging = false
                    trackingScrollGesture = true
                    scrollStartedAtTop = sv.scrollY <= 0
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!trackingScrollGesture) {
                        // ACTION_DOWN was initially delivered to a clickable child inside ScrollView,
                        // and ScrollView intercepted mid-gesture. Anchor fresh downRawY here so
                        // stale coordinates from an earlier gesture can never trigger a false dismiss.
                        trackingScrollGesture = true
                        scrollStartedAtTop = sv.scrollY <= 0
                        downRawY = event.rawY
                        dragging = false
                        velocityTracker?.recycle()
                        velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                        return@setOnTouchListener false
                    }
                    velocityTracker?.addMovement(event)
                    val rawDy = event.rawY - downRawY
                    if (!dragging && scrollStartedAtTop && sv.scrollY <= 0 && rawDy > touchSlop * 1.4f) {
                        dragging = true
                        downRawY = event.rawY
                        sheetView.animate().cancel()
                        sheetView.alpha = 1f
                    }
                    if (dragging) {
                        val dy = (event.rawY - downRawY).coerceAtLeast(0f)
                        sheetView.translationY = dy
                        updateDimForOffset(dy)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    trackingScrollGesture = false
                    scrollStartedAtTop = false
                    velocityTracker?.addMovement(event)
                    if (dragging) {
                        finishDrag()
                        true
                    } else {
                        velocityTracker?.recycle()
                        velocityTracker = null
                        false
                    }
                }
                else -> false
            }
        }

        // Intercept Back key to play smooth downward slide-out
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissSheetAnimated(sheetView, dialog, onDismissStart)
                true
            } else {
                false
            }
        }
    }

    /**
     * Clean 360ms horizontal damped sine shake for invalid PIN or validation rejection.
     * Tracks per-view animators so rapid triggers don't overlap and cleans up on detach.
     */
    fun shakeError(view: View) {
        activeShakeAnimators.remove(view)?.cancel()
        view.translationX = 0f
        val amplitude = dp(view.context, 10f)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val damped = (1f - t) * sin(t * 4.0 * PI).toFloat()
                view.translationX = amplitude * damped
            }
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (activeShakeAnimators[view] === animator) {
                    activeShakeAnimators.remove(view)
                }
                animator.cancel()
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.translationX = 0f
                view.removeOnAttachStateChangeListener(detachListener)
                if (activeShakeAnimators[view] === animator) {
                    activeShakeAnimators.remove(view)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                view.translationX = 0f
                view.removeOnAttachStateChangeListener(detachListener)
                if (activeShakeAnimators[view] === animator) {
                    activeShakeAnimators.remove(view)
                }
            }
        })
        view.addOnAttachStateChangeListener(detachListener)
        activeShakeAnimators[view] = animator
        animator.start()
    }

    private fun findModalScrim(modalView: View): View? {
        var cur: View? = modalView.parent as? View
        while (cur != null) {
            val found = (cur as? ViewGroup)?.findViewWithTag<View>(AppSystemInsets.TAG_MODAL_SCRIM)
            if (found != null) return found
            cur = cur.parent as? View
        }
        return null
    }

    /**
     * Centered modal dialog entrance: synchronized scrim fade + subtle 0.94 -> 1.0 scale
     * + gentle 12dp upward settle in 220ms EASE_OUT_EXPO.
     */
    fun animateModalEnter(modalView: View) {
        findModalScrim(modalView)?.let { scrim ->
            scrim.animate().cancel()
            scrim.alpha = 0f
            scrim.animate()
                .alpha(1f)
                .setDuration(200L)
                .setInterpolator(EASE_OUT_EXPO)
                .start()
        }
        modalView.animate().cancel()
        modalView.scaleX = 0.94f
        modalView.scaleY = 0.94f
        modalView.translationY = dp(modalView.context, 12f)
        modalView.alpha = 0f
        modalView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(EASE_OUT_EXPO)
            .setListener(null)
            .start()
    }

    /**
     * Centered modal dialog exit: smooth reverse mirror of [animateModalEnter] (1.0 -> 0.94 scale,
     * +10dp downward settle, alpha -> 0, synchronized scrim fade) over 160ms EASE_STANDARD.
     */
    fun dismissModalAnimated(
        modalView: View,
        dialog: Dialog,
        onDismissStart: () -> Unit = {}
    ) {
        if (dismissingDialogs[dialog] == true) return
        dismissingDialogs[dialog] = true
        onDismissStart()

        val scrim = findModalScrim(modalView)
        scrim?.isClickable = false

        val hostActivity = findHostActivity(dialog.context)
        if (hostActivity != null && (hostActivity.isFinishing || hostActivity.isDestroyed)) {
            scrim?.animate()?.cancel()
            modalView.animate().cancel()
            dismissSheetImmediately(dialog)
            return
        }

        scrim?.let {
            it.animate().cancel()
            it.animate()
                .alpha(0f)
                .setDuration(150L)
                .setInterpolator(EASE_STANDARD)
                .start()
        }

        modalView.animate().cancel()
        var finalized = false
        var fallbackRunnable: Runnable? = null
        val finalizeDismiss = {
            if (!finalized) {
                finalized = true
                fallbackRunnable?.let(modalView::removeCallbacks)
                fallbackRunnable = null
                bypassDismissDialogs[dialog] = true
                try {
                    if (dialog.isShowing) {
                        runCatching { dialog.dismiss() }
                    }
                } finally {
                    bypassDismissDialogs.remove(dialog)
                    dismissingDialogs.remove(dialog)
                }
            }
        }
        val watchdog = Runnable { finalizeDismiss() }
        fallbackRunnable = watchdog
        modalView.postDelayed(watchdog, 260L)

        modalView.animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .translationY(dp(modalView.context, 10f))
            .alpha(0f)
            .setDuration(160L)
            .setInterpolator(EASE_STANDARD)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    modalView.animate().setListener(null)
                    finalizeDismiss()
                }

                override fun onAnimationCancel(animation: Animator) {
                    modalView.animate().setListener(null)
                    finalizeDismiss()
                }
            })
            .start()
    }

    /**
     * Z-axis depth hierarchy: gently recedes the underlying screen when a bottom sheet
     * is presented, and smoothly restores it upon dismissal.
     */
    fun animateDepthRecede(backgroundView: View?, receded: Boolean) {
        val target = backgroundView ?: return
        target.animate().cancel()
        target.animate()
            .scaleX(if (receded) 0.975f else 1f)
            .scaleY(if (receded) 0.975f else 1f)
            .alpha(if (receded) 0.86f else 1f)
            .setDuration(if (receded) 260L else 240L)
            .setInterpolator(EASE_OUT_EXPO)
            .start()
    }

    /**
     * Smoothly interpolates [AppIconView.tintColor] using [ArgbEvaluator].
     * Cleans up animator entries on completion, cancellation, or window detach.
     */
    fun animateIconTint(
        iconView: AppIconView,
        targetColor: Int,
        durationMs: Long = 200L
    ) {
        activeTintAnimators.remove(iconView)?.cancel()
        val startColor = iconView.tintColor
        if (startColor == targetColor) return

        val animator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor).apply {
            duration = durationMs
            interpolator = EASE_OUT_EXPO
            addUpdateListener { anim ->
                iconView.tintColor = anim.animatedValue as Int
            }
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (activeTintAnimators[iconView] === animator) {
                    activeTintAnimators.remove(iconView)
                }
                animator.cancel()
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                iconView.removeOnAttachStateChangeListener(detachListener)
                if (activeTintAnimators[iconView] === animator) {
                    activeTintAnimators.remove(iconView)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                iconView.removeOnAttachStateChangeListener(detachListener)
                if (activeTintAnimators[iconView] === animator) {
                    activeTintAnimators.remove(iconView)
                }
            }
        })
        iconView.addOnAttachStateChangeListener(detachListener)
        activeTintAnimators[iconView] = animator
        animator.start()
    }

    /**
     * Restrained toast/banner entrance: subtle vertical glide + fade in 180ms.
     */
    fun animateToastShow(view: View) {
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = -dp(view.context, 12f)
        }
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(EASE_OUT_EXPO)
            .start()
    }

    /**
     * Restrained toast/banner exit: subtle upward glide + fade out in 150ms.
     */
    fun animateToastHide(view: View) {
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) return
        view.animate()
            .alpha(0f)
            .translationY(-dp(view.context, 10f))
            .setDuration(150L)
            .setInterpolator(EASE_STANDARD)
            .withEndAction {
                view.visibility = View.GONE
                view.translationY = 0f
            }
            .start()
    }

    /**
     * Micro-staggered entrance for compact grids or rows (alpha 0 -> 1, +10dp -> 0 in 200ms).
     */
    fun staggerChildren(
        parent: ViewGroup,
        stepMs: Long = 16L,
        maxDelayMs: Long = 120L
    ) {
        val rise = dp(parent.context, 10f)
        var visibleIndex = 0
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = rise
            val delay = min(visibleIndex * stepMs, maxDelayMs)
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(200L)
                .setInterpolator(EASE_OUT_EXPO)
                .start()
            visibleIndex++
        }
    }

    /**
     * capsule segmented bar whose active rounded pill slides smoothly along the X axis
     * (220ms EASE_OUT_EXPO) to maintain spatial continuity across tab switches.
     */
    fun createSlidingSegmentedBar(
        context: Context,
        labels: List<String>,
        initial: Int,
        bgColor: Int,
        pillColor: Int,
        activeTextColor: Int,
        inactiveTextColor: Int,
        trackStrokeColor: Int = Color.TRANSPARENT,
        pillStrokeColor: Int = Color.TRANSPARENT,
        onSelect: (Int) -> Unit
    ): FrameLayout {
        val count = labels.size.coerceAtLeast(1)
        var selectedIndex = initial.coerceIn(0, count - 1)
        var tabWidth = 0f
        val strokePx = dpInt(context, 0.9f).coerceAtLeast(1)

        val pad = dpInt(context, 3f)
        val container = FrameLayout(context).apply {
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 12f)
                setColor(bgColor)
                if (trackStrokeColor != Color.TRANSPARENT) {
                    setStroke(strokePx, trackStrokeColor)
                }
            }
        }

        val pillView = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 9f)
                setColor(pillColor)
                if (pillStrokeColor != Color.TRANSPARENT) {
                    setStroke(strokePx, pillStrokeColor)
                }
            }
        }
        container.addView(
            pillView,
            FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabViews = ArrayList<TextView>(labels.size)

        fun updateLabels(activeIdx: Int) {
            for (i in tabViews.indices) {
                val isSelected = i == activeIdx
                tabViews[i].setTextColor(if (isSelected) activeTextColor else inactiveTextColor)
                tabViews[i].setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        labels.forEachIndexed { idx, title ->
            val tv = TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(
                    dpInt(context, 6f),
                    dpInt(context, 9f),
                    dpInt(context, 6f),
                    dpInt(context, 9f)
                )
                setOnClickListener {
                    if (idx == selectedIndex) return@setOnClickListener
                    selectedIndex = idx
                    updateLabels(idx)
                    AppHaptics.perform(this, HapticLevel.LIGHT_TICK)
                    if (tabWidth > 0f) {
                        pillView.animate().cancel()
                        pillView.animate()
                            .translationX(idx * tabWidth)
                            .setDuration(220L)
                            .setInterpolator(EASE_OUT_EXPO)
                            .start()
                    }
                    onSelect(idx)
                }
            }
            tabViews.add(tv)
            row.addView(
                tv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        updateLabels(selectedIndex)

        container.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        fun syncPillGeometry() {
            val w = row.width
            val h = row.height
            if (w <= 0 || h <= 0) return
            val computedTabW = w.toFloat() / count
            val pillW = computedTabW.roundToInt()
            val lp = pillView.layoutParams as FrameLayout.LayoutParams
            val sizeChanged = lp.width != pillW || lp.height != h
            tabWidth = computedTabW
            if (sizeChanged) {
                lp.width = pillW
                lp.height = h
                pillView.layoutParams = lp
                pillView.animate().cancel()
                pillView.translationX = selectedIndex * tabWidth
            }
        }

        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            pillView.post { syncPillGeometry() }
        }
        row.post { syncPillGeometry() }

        return container
    }
}
