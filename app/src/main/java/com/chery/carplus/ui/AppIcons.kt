package com.chery.carplus.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser
import kotlin.math.min

/**
 * Unified CarPlus Automotive & UI Vector Icon System:
 * - Vehicle control icons are unified from `base.apk.1` (`com.xiaote`) automotive vector geometry
 *   onto a single strict drawing spec: 24x24 viewport, uniform 1.65u monochrome stroke,
 *   `Cap.ROUND` / `Join.ROUND`, and centered ~18u optical live area.
 * - Home-screen widget drawables (`res/drawable/ic_widget_*.xml`) share the exact same pathData
 *   and stroke specification so in-app controls and desktop widgets are pixel-identical.
 * - Utility & navigation icons use crisp 24x24 Material Design Icon (MDI) paths.
 */
enum class AppIconType(val svgPath: String, val customControlGlyph: Boolean = false) {
    CHEVRON_DOWN("M7.41,8.58L12,13.17L16.59,8.58L18,10L12,16L6,10L7.41,8.58Z"),
    CHEVRON_RIGHT("M8.59,16.58L13.17,12L8.59,7.41L10,6L16,12L10,18L8.59,16.58Z"),
    BACK_ARROW("M20,11V13H8L13.5,18.5L12.08,19.92L4.16,12L12.08,4.08L13.5,5.5L8,11H20Z"),
    MESSAGE_BUBBLE("M20,2A2,2 0 0,1 22,4V16A2,2 0 0,1 20,18H6L2,22V4C2,2.89 2.9,2 4,2H20M4,4V17.17L5.17,16H20V4H4M6,7H18V9H6V7M6,11H15V13H6V11Z"),
    MENU_HAMBURGER("M3,6H21V8H3V6M3,11H21V13H3V11M3,16H21V18H3V16Z"),
    MORE_HORIZ("M6,10A2,2 0 0,0 4,12A2,2 0 0,0 6,14A2,2 0 0,0 8,12A2,2 0 0,0 6,10M18,10A2,2 0 0,0 16,12A2,2 0 0,0 18,14A2,2 0 0,0 20,12A2,2 0 0,0 18,10M12,10A2,2 0 0,0 10,12A2,2 0 0,0 12,14A2,2 0 0,0 14,12A2,2 0 0,0 12,10Z"),
    MORE_VERT("M12,16A2,2 0 0,1 14,18A2,2 0 0,1 12,20A2,2 0 0,1 10,18A2,2 0 0,1 12,16M12,10A2,2 0 0,1 14,12A2,2 0 0,1 12,14A2,2 0 0,1 10,12A2,2 0 0,1 12,10M12,4A2,2 0 0,1 14,6A2,2 0 0,1 12,8A2,2 0 0,1 10,6A2,2 0 0,1 12,4Z"),

    // Unified automotive control icons (24x24 viewport, 1.65u stroke, ROUND cap/join)
    // Shared 1:1 with res/drawable/ic_widget_*.xml
    LOCK_CLOSED(
        "M7.75,10.5V7.5A4.25,4.25 0 0,1 16.25,7.5V10.5 M7.0,10.5H17.0A2.25,2.25 0 0,1 19.25,12.75V18.25A2.25,2.25 0 0,1 17.0,20.5H7.0A2.25,2.25 0 0,1 4.75,18.25V12.75A2.25,2.25 0 0,1 7.0,10.5Z M12,14.2V16.8",
        customControlGlyph = true
    ),
    LOCK_OPEN(
        "M16.25,10.5V7.0A4.25,4.25 0 0,0 7.75,6.6 M7.0,10.5H17.0A2.25,2.25 0 0,1 19.25,12.75V18.25A2.25,2.25 0 0,1 17.0,20.5H7.0A2.25,2.25 0 0,1 4.75,18.25V12.75A2.25,2.25 0 0,1 7.0,10.5Z M12,14.2V16.8",
        customControlGlyph = true
    ),
    FAN_BLADE(
        "M14.15,8.2C14.84,7.78 15.64,7.55 16.5,7.55C18.64,7.55 20.43,9.03 20.89,11.01C21.01,11.55 20.55,12 20,12H4C3.45,12 2.99,12.45 3.11,12.99C3.57,14.97 5.36,16.45 7.5,16.45C8.35,16.45 9.14,16.22 9.82,15.82 M12,12C9.54,12 7.55,9.98 7.55,7.5C7.55,5.36 9.03,3.57 11.01,3.11C11.55,2.99 12,3.45 12,4V20C12,20.55 12.45,21.01 12.99,20.89C14.97,20.43 16.45,18.64 16.45,16.5C16.45,14.02 14.46,12 12,12Z",
        customControlGlyph = true
    ),
    BOLT_FLASH(
        "M12.75,6.13C12.75,5.37 13.37,4.75 14.13,4.75H15.72C16.95,4.75 18.12,5.28 18.94,6.21C19.78,7.18 20.25,8.42 20.25,9.71V14.29C20.25,15.58 19.78,16.82 18.94,17.79C18.12,18.72 16.95,19.25 15.72,19.25H14.13C13.37,19.25 12.75,18.63 12.75,17.87V6.13Z M9,10H4 M8.91,6.65L4.09,5.35 M9,14H4 M8.91,17.35L4.09,18.65",
        customControlGlyph = true
    ),
    TRUNK_OPEN(
        "M4,7H12.82C13.26,7 13.69,7.15 14.04,7.41L19.05,11.27C19.63,11.72 19.92,12.45 19.8,13.18L19.28,16.33C19.12,17.29 18.28,18 17.31,18H15 M4,18H10 M13,7L16.66,4.8C17.45,4.33 18.46,4.46 19.1,5.1L20,6 M12.5,15A2.5,2.5 0 1,0 12.5,20A2.5,2.5 0 1,0 12.5,15Z",
        customControlGlyph = true
    ),
    TAILGATE_CLOSE(
        "M4,7H12.82C13.26,7 13.69,7.15 14.04,7.41L19.05,11.27C19.63,11.72 19.92,12.45 19.8,13.18L19.28,16.33C19.12,17.29 18.28,18 17.31,18H15 M4,18H10 M12.5,15A2.5,2.5 0 1,0 12.5,20A2.5,2.5 0 1,0 12.5,15Z M17.8,3.6V7.0 M15.9,5.3L17.8,7.2L19.7,5.3",
        customControlGlyph = true
    ),
    ENGINE_POWER(
        "M16.99,6.22A8.1,8.1 0 1,1 7.01,6.22 M12,3.2V11.5",
        customControlGlyph = true
    ),
    ENGINE_STOP(
        "M12,3.8A8.2,8.2 0 1,0 12,20.2A8.2,8.2 0 1,0 12,3.8Z M10.1,8.8H13.9A1.3,1.3 0 0,1 15.2,10.1V13.9A1.3,1.3 0 0,1 13.9,15.2H10.1A1.3,1.3 0 0,1 8.8,13.9V10.1A1.3,1.3 0 0,1 10.1,8.8Z",
        customControlGlyph = true
    ),
    WINDOW_OPEN(
        "M7.66,5.5C7.86,5.04 8.31,4.75 8.81,4.75H18A1.25,1.25 0 0,1 19.25,6V18A1.25,1.25 0 0,1 18,19.25H6A1.25,1.25 0 0,1 4.75,18V12.42C4.75,12.25 4.79,12.08 4.85,11.92L7.66,5.5Z M4.75,13.5H19.25 M12.8,7.2V11.1 M10.8,9.3L12.8,11.3L14.8,9.3 M14.2,16.4H16.6",
        customControlGlyph = true
    ),
    WINDOW_VENT(
        "M7.66,5.5C7.86,5.04 8.31,4.75 8.81,4.75H18A1.25,1.25 0 0,1 19.25,6V18A1.25,1.25 0 0,1 18,19.25H6A1.25,1.25 0 0,1 4.75,18V12.42C4.75,12.25 4.79,12.08 4.85,11.92L7.66,5.5Z M4.75,13.5H19.25 M6.4,10.0H19.25 M10.8,7.4C12.0,6.3 13.4,8.5 14.8,7.3 M14.2,16.4H16.6",
        customControlGlyph = true
    ),
    WINDOW_CLOSE(
        "M7.66,5.5C7.86,5.04 8.31,4.75 8.81,4.75H18A1.25,1.25 0 0,1 19.25,6V18A1.25,1.25 0 0,1 18,19.25H6A1.25,1.25 0 0,1 4.75,18V12.42C4.75,12.25 4.79,12.08 4.85,11.92L7.66,5.5Z M4.75,13.5H19.25 M12.8,11.3V7.4 M10.8,9.2L12.8,7.2L14.8,9.2 M14.2,16.4H16.6",
        customControlGlyph = true
    ),
    SUNROOF_OPEN(
        "M3.5,18.0L5.2,14.1C5.75,12.85 6.95,12.2 8.3,12.2H9.0 M15.6,12.2H16.0C16.7,12.2 17.35,12.55 17.75,13.15L19.8,16.0C20.25,16.65 20.5,17.2 20.5,18.0 M3.5,18.0H20.5 M6.2,9.8H12.0 M17.2,6.2H12.4 M14.4,4.5L12.4,6.2L14.4,7.9",
        customControlGlyph = true
    ),
    SUNROOF_TILT(
        "M3.5,18.0L5.2,14.1C5.75,12.85 6.95,12.2 8.3,12.2H9.0 M15.6,12.2H16.0C16.7,12.2 17.35,12.55 17.75,13.15L19.8,16.0C20.25,16.65 20.5,17.2 20.5,18.0 M3.5,18.0H20.5 M15.6,12.2L9.6,9.0 M7.8,6.8L9.6,5.0L11.4,6.8",
        customControlGlyph = true
    ),
    SUNROOF_CLOSE(
        "M3.5,18.0L5.2,14.1C5.75,12.85 6.95,12.2 8.3,12.2H16.0C16.7,12.2 17.35,12.55 17.75,13.15L19.8,16.0C20.25,16.65 20.5,17.2 20.5,18.0 M3.5,18.0H20.5 M12.2,5.0V9.0 M10.2,7.2L12.2,9.2L14.2,7.2",
        customControlGlyph = true
    ),
    CAR_CONTROLS(
        "M5.52,8.55L4.57,9.5C4.0,10.08 3.7,10.87 3.73,11.68L4.04,18.92C4.08,19.68 4.71,20.25 5.47,20.25C5.89,20.25 6.29,20.07 6.56,19.75L7.64,18.5H16.36L17.44,19.75C17.71,20.07 18.11,20.25 18.53,20.25C19.29,20.25 19.92,19.68 19.96,18.92L20.27,11.68C20.3,10.87 20.0,10.08 19.43,9.5L18.48,8.55C18.36,8.43 18.25,8.29 18.17,8.14L16.56,5.17C16.15,4.41 15.35,3.94 14.49,3.94H9.51C8.65,3.94 7.85,4.41 7.44,5.17L5.83,8.14C5.75,8.29 5.64,8.43 5.52,8.55Z M6.8,13.6H8.8 M15.2,13.6H17.2",
        customControlGlyph = true
    ),
    HORN_SOUND(
        "M3.75,10.5H13.15C13.15,10.5 15.5,10.48 16.82,10.0C18.82,9.27 20.25,6.25 20.25,6.25V17.75C20.25,17.75 18.82,14.73 16.82,14.0C15.5,13.52 13.15,13.5 13.15,13.5H3.75 M3.75,8.5V15.5 M7.85,15.8C7.85,16.18 8.11,16.65 8.8,17.07C9.48,17.47 10.47,17.75 11.6,17.75C12.73,17.75 13.72,17.47 14.4,17.07C15.09,16.65 15.35,16.18 15.35,15.8C15.35,15.42 15.09,14.95 14.4,14.53C13.72,14.13 12.73,13.85 11.6,13.85C10.47,13.85 9.48,14.13 8.8,14.53C8.11,14.95 7.85,15.42 7.85,15.8Z",
        customControlGlyph = true
    ),

    // Standard UI & Utility icons
    LOCATION_ARROW("M12,6.5A2.5,2.5 0 0,1 14.5,9A2.5,2.5 0 0,1 12,11.5A2.5,2.5 0 0,1 9.5,9A2.5,2.5 0 0,1 12,6.5M12,2A7,7 0 0,1 19,9C19,14.25 12,22 12,22C12,22 5,14.25 5,9A7,7 0 0,1 12,2M12,4A5,5 0 0,0 7,9C7,10 7,12 12,18.71C17,12 17,10 17,9A5,5 0 0,0 12,4Z"),
    SHIELD_CHECK("M21,11C21,16.55 17.16,21.74 12,23C6.84,21.74 3,16.55 3,11V5L12,1L21,5V11M12,21C15.75,20 19,15.54 19,11.22V6.3L12,3.18L5,6.3V11.22C5,15.54 8.25,20 12,21M10,17L6,13L7.41,11.59L10,14.17L16.59,7.58L18,9"),
    CHECK_CIRCLE("M12 2C6.5 2 2 6.5 2 12S6.5 22 12 22 22 17.5 22 12 17.5 2 12 2M12 20C7.59 20 4 16.41 4 12S7.59 4 12 4 20 7.59 20 12 16.41 20 12 20M16.59 7.58L10 14.17L7.41 11.59L6 13L10 17L18 9L16.59 7.58Z"),
    CHECK("M21,7L9,19L3.5,13.5L4.91,12.09L9,16.17L19.59,5.59L21,7Z"),
    SWAP_CAR("M21,9L17,5V8H10V10H17V13M7,11L3,15L7,19V16H14V14H7V11Z"),
    ADD_ICON("M19,13H13V19H11V13H5V11H11V5H13V11H19V13Z"),
    CLOSE_ICON("M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12L19,6.41Z"),
    BLUETOOTH_ICON("M14.88,16.29L13,18.17V14.41M13,5.83L14.88,7.71L13,9.58M17.71,7.71L12,2H11V9.58L6.41,5L5,6.41L10.59,12L5,17.58L6.41,19L11,14.41V22H12L17.71,16.29L13.41,12L17.71,7.71Z"),
    CLOUD_NET_ICON("M6.5 20Q4.22 20 2.61 18.43 1 16.85 1 14.58 1 12.63 2.17 11.1 3.35 9.57 5.25 9.15 5.88 6.85 7.75 5.43 9.63 4 12 4 14.93 4 16.96 6.04 19 8.07 19 11 20.73 11.2 21.86 12.5 23 13.78 23 15.5 23 17.38 21.69 18.69 20.38 20 18.5 20M6.5 18H18.5Q19.55 18 20.27 17.27 21 16.55 21 15.5 21 14.45 20.27 13.73 19.55 13 18.5 13H17V11Q17 8.93 15.54 7.46 14.08 6 12 6 9.93 6 8.46 7.46 7 8.93 7 11H6.5Q5.05 11 4.03 12.03 3 13.05 3 14.5 3 15.95 4.03 17 5.05 18 6.5 18M12 12Z"),
    CALENDAR("M12 12H17V17H12V12M19 3H18V1H16V3H8V1H6V3H5C3.9 3 3 3.9 3 5V19C3 20.1 3.9 21 5 21H19C20.1 21 21 20.1 21 19V5C21 3.9 20.1 3 19 3M19 5V7H5V5H19M5 19V9H19V19H5Z"),
    CLOCK("M12,20A8,8 0 0,0 20,12A8,8 0 0,0 12,4A8,8 0 0,0 4,12A8,8 0 0,0 12,20M12,2A10,10 0 0,1 22,12A10,10 0 0,1 12,22C6.47,22 2,17.5 2,12A10,10 0 0,1 12,2M12.5,7V12.25L17,14.92L16.25,16.15L11,13V7H12.5Z"),
    MAP_PIN("M12,6.5A2.5,2.5 0 0,1 14.5,9A2.5,2.5 0 0,1 12,11.5A2.5,2.5 0 0,1 9.5,9A2.5,2.5 0 0,1 12,6.5M12,2A7,7 0 0,1 19,9C19,14.25 12,22 12,22C12,22 5,14.25 5,9A7,7 0 0,1 12,2M12,4A5,5 0 0,0 7,9C7,10 7,12 12,18.71C17,12 17,10 17,9A5,5 0 0,0 12,4Z"),
    CROSSHAIR("M12,8A4,4 0 0,1 16,12A4,4 0 0,1 12,16A4,4 0 0,1 8,12A4,4 0 0,1 12,8M3.05,13H1V11H3.05C3.5,6.83 6.83,3.5 11,3.05V1H13V3.05C17.17,3.5 20.5,6.83 20.95,11H23V13H20.95C20.5,17.17 17.17,20.5 13,20.95V23H11V20.95C6.83,20.5 3.5,17.17 3.05,13M12,5A7,7 0 0,0 5,12A7,7 0 0,0 12,19A7,7 0 0,0 19,12A7,7 0 0,0 12,5Z"),
    INFO_CIRCLE("M11,9H13V7H11M12,20C7.59,20 4,16.41 4,12C4,7.59 7.59,4 12,4C16.41,4 20,7.59 20,12C20,16.41 16.41,20 12,20M12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12A10,10 0 0,0 12,2M11,17H13V11H11V17Z"),
    LOG_OUT("M17 7L15.59 8.41L18.17 11H8V13H18.17L15.59 15.58L17 17L22 12M4 5H12V3H4C2.9 3 2 3.9 2 5V19C2 20.1 2.9 21 4 21H12V19H4V5Z"),
    KEY_ROUND("M9 0C7.3 0 6 1.3 6 3S7.3 6 9 6C10.3 6 11.4 5.2 11.8 4H14V6H16V4H18V2H11.8C11.4 .8 10.3 0 9 0M9 2C9.6 2 10 2.4 10 3S9.6 4 9 4 8 3.6 8 3 8.4 2 9 2M6.5 8C5.8 8 5.3 8.4 5.1 9L3 15V23C3 23.6 3.4 24 4 24H5C5.6 24 6 23.6 6 23V22H18V23C18 23.6 18.4 24 19 24H20C20.6 24 21 23.6 21 23V15L18.9 9C18.7 8.4 18.1 8 17.5 8H6.5M6.5 9.5H17.5L19 14H5L6.5 9.5M6.5 16C7.3 16 8 16.7 8 17.5S7.3 19 6.5 19 5 18.3 5 17.5 5.7 16 6.5 16M17.5 16C18.3 16 19 16.7 19 17.5S18.3 19 17.5 19 16 18.3 16 17.5 16.7 16 17.5 16Z"),
    RADAR("M19.07,4.93L17.66,6.34C19.1,7.79 20,9.79 20,12A8,8 0 0,1 12,20A8,8 0 0,1 4,12C4,7.92 7.05,4.56 11,4.07V6.09C8.16,6.57 6,9.03 6,12A6,6 0 0,0 12,18A6,6 0 0,0 18,12C18,10.34 17.33,8.84 16.24,7.76L14.83,9.17C15.55,9.9 16,10.9 16,12A4,4 0 0,1 12,16A4,4 0 0,1 8,12C8,10.14 9.28,8.59 11,8.14V10.28C10.4,10.63 10,11.26 10,12A2,2 0 0,0 12,14A2,2 0 0,0 14,12C14,11.26 13.6,10.62 13,10.28V2H12A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12C22,9.24 20.88,6.74 19.07,4.93Z")
}

/**
 * Renders bespoke Tesla-inspired automotive control icons and crisp 24x24 MDI UI icons
 * with optional smooth rotation animation (e.g. active climate fan).
 */
class AppIconView(
    context: Context,
    initialType: AppIconType,
    initialTint: Int = Color.WHITE
) : View(context) {

    var iconType: AppIconType = initialType
        set(value) {
            if (field != value) {
                field = value
                cachedPath = null
                invalidate()
            }
        }

    var tintColor: Int = initialTint
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var spinEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) startSpin() else stopSpin()
            }
        }

    private var spinAngle = 0f
    private var spinAnimator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scaleMatrix = Matrix()
    private val drawPath = Path()
    private var cachedPath: Path? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        val size = min(availW, availH)
        if (size <= 0f) return

        val cx = paddingLeft + availW / 2f
        val cy = paddingTop + availH / 2f
        val left = cx - size / 2f
        val top = cy - size / 2f
        val scale = size / 24f

        fillPaint.color = tintColor
        strokePaint.color = tintColor

        canvas.save()
        canvas.translate(left, top)
        if (spinEnabled && spinAngle != 0f) {
            canvas.rotate(spinAngle, size / 2f, size / 2f)
        }

        val basePath = cachedPath ?: run {
            val parsed = PathParser.createPathFromPathData(iconType.svgPath) ?: Path()
            cachedPath = parsed
            parsed
        }
        scaleMatrix.reset()
        scaleMatrix.setScale(scale, scale)
        drawPath.reset()
        basePath.transform(scaleMatrix, drawPath)

        if (iconType.customControlGlyph) {
            strokePaint.strokeWidth = CONTROL_STROKE_WIDTH * scale
            canvas.drawPath(drawPath, strokePaint)
        } else {
            canvas.drawPath(drawPath, fillPaint)
        }
        canvas.restore()
    }

    companion object {
        /** Uniform stroke width (in 24x24 viewport units) shared with ic_widget_*.xml. */
        const val CONTROL_STROKE_WIDTH = 1.65f
    }

    private fun startSpin() {
        if (spinAnimator?.isRunning == true) return
        spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinAngle = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun stopSpin() {
        spinAnimator?.cancel()
        spinAnimator = null
        spinAngle = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        spinAnimator?.cancel()
        spinAnimator = null
        super.onDetachedFromWindow()
    }
}

/**
 * Sleek minimalist horizontal rounded fuel progress bar (ICE vehicle).
 */
class BatteryPillView(context: Context) : View(context) {

    var levelPercent: Int = 50
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var isChargingOrActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2A2D34")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        if (levelPercent > 0) {
            val rawFillW = w * (levelPercent / 100f)
            val fillW = rawFillW.coerceAtLeast(min(h, w)).coerceAtMost(w)
            fillPaint.color = when {
                levelPercent <= 18 -> Color.parseColor("#F87171")
                isChargingOrActive -> Color.parseColor("#4ADE80")
                else -> Color.parseColor("#D8DCE4")
            }
            rect.set(0f, 0f, fillW, h)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
    }
}

/**
 * Small status ring/dot shown inline beside the "安全性和驾驶员" phone key subtitle.
 */
class StatusDotView(context: Context) : View(context) {

    enum class State { CONNECTED, CONNECTING, DISCONNECTED, IDLE }

    var state: State = State.DISCONNECTED
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 1.2f * resources.displayMetrics.density
        if (r <= 0f) return

        val color = when (state) {
            State.CONNECTED -> Color.rgb(52, 211, 153)
            State.CONNECTING -> Color.rgb(251, 191, 36)
            State.DISCONNECTED -> Color.rgb(239, 108, 108)
            State.IDLE -> Color.rgb(126, 132, 146)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * resources.displayMetrics.density
        paint.color = color
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r * 0.45f, paint)
    }
}
