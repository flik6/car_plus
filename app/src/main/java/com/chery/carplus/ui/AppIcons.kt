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
 * - Vehicle control icons use a bespoke Tesla-inspired minimalist automotive icon suite
 *   drawn with uniform optical stroke geometry on a 24x24 grid (Cap.ROUND / Join.ROUND).
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

    // Bespoke Tesla-inspired minimalist automotive control icons
    LOCK_CLOSED("", customControlGlyph = true),
    LOCK_OPEN("", customControlGlyph = true),
    FAN_BLADE("", customControlGlyph = true),
    BOLT_FLASH("", customControlGlyph = true),
    TRUNK_OPEN("", customControlGlyph = true),
    TAILGATE_CLOSE("", customControlGlyph = true),
    ENGINE_POWER("", customControlGlyph = true),
    ENGINE_STOP("", customControlGlyph = true),
    WINDOW_OPEN("", customControlGlyph = true),
    WINDOW_VENT("", customControlGlyph = true),
    WINDOW_CLOSE("", customControlGlyph = true),
    SUNROOF_OPEN("", customControlGlyph = true),
    SUNROOF_TILT("", customControlGlyph = true),
    SUNROOF_CLOSE("", customControlGlyph = true),
    CAR_CONTROLS("", customControlGlyph = true),
    HORN_SOUND("", customControlGlyph = true),

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
    private val tempPath = Path()
    private val tempRect = RectF()
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

        if (iconType.customControlGlyph) {
            canvas.scale(scale, scale)
            drawBespokeControlGlyph(canvas, iconType)
        } else {
            val basePath = cachedPath ?: run {
                val parsed = PathParser.createPathFromPathData(iconType.svgPath) ?: Path()
                cachedPath = parsed
                parsed
            }
            scaleMatrix.reset()
            scaleMatrix.setScale(scale, scale)
            drawPath.reset()
            basePath.transform(scaleMatrix, drawPath)
            canvas.drawPath(drawPath, fillPaint)
        }
        canvas.restore()
    }

    /**
     * Bespoke Tesla-inspired automotive control icon suite on a normalized 24x24 canvas.
     * Unified architectural stroke (1.65u), aerodynamic fastback sedan proportions,
     * and crisp optical contrast at both 22dp (collapsed bar) and 28dp (expanded deck).
     */
    private fun drawBespokeControlGlyph(canvas: Canvas, type: AppIconType) {
        strokePaint.strokeWidth = 1.65f
        when (type) {
            AppIconType.LOCK_CLOSED -> drawLockClosed(canvas)
            AppIconType.LOCK_OPEN -> drawLockOpen(canvas)
            AppIconType.FAN_BLADE -> drawClimateFan(canvas)
            AppIconType.BOLT_FLASH -> drawHeadlampFlash(canvas)
            AppIconType.TRUNK_OPEN -> drawTrunkProfile(canvas, open = true)
            AppIconType.TAILGATE_CLOSE -> drawTrunkProfile(canvas, open = false)
            AppIconType.ENGINE_POWER -> drawEnginePower(canvas)
            AppIconType.ENGINE_STOP -> drawEngineStop(canvas)
            AppIconType.WINDOW_OPEN -> drawDoorWindow(canvas, mode = 1)
            AppIconType.WINDOW_VENT -> drawDoorWindow(canvas, mode = 0)
            AppIconType.WINDOW_CLOSE -> drawDoorWindow(canvas, mode = 2)
            AppIconType.SUNROOF_OPEN -> drawSunroofProfile(canvas, mode = 1)
            AppIconType.SUNROOF_TILT -> drawSunroofProfile(canvas, mode = 0)
            AppIconType.SUNROOF_CLOSE -> drawSunroofProfile(canvas, mode = 2)
            AppIconType.CAR_CONTROLS -> drawCarFrontSilhouette(canvas)
            AppIconType.HORN_SOUND -> drawHornSound(canvas)
            else -> Unit
        }
    }

    private fun drawLockClosed(canvas: Canvas) {
        // Tall architectural U-shackle
        tempPath.reset()
        tempPath.moveTo(8.3f, 10.2f)
        tempPath.lineTo(8.3f, 7.2f)
        tempRect.set(8.3f, 3.5f, 15.7f, 10.9f)
        tempPath.arcTo(tempRect, 180f, 180f, false)
        tempPath.lineTo(15.7f, 10.2f)
        canvas.drawPath(tempPath, strokePaint)

        // Refined rounded lock body
        tempRect.set(5.4f, 10.2f, 18.6f, 20.4f)
        canvas.drawRoundRect(tempRect, 2.5f, 2.5f, strokePaint)

        // Crisp keyhole dot & slim vertical stem
        canvas.drawCircle(12f, 14.5f, 1.35f, fillPaint)
        strokePaint.strokeWidth = 1.55f
        canvas.drawLine(12f, 15.4f, 12f, 17.5f, strokePaint)
    }

    private fun drawLockOpen(canvas: Canvas) {
        // Raised & pivoted open U-shackle (clearly distinct silhouette)
        tempPath.reset()
        tempPath.moveTo(8.3f, 10.2f)
        tempPath.lineTo(8.3f, 6.7f)
        tempRect.set(8.3f, 3.0f, 15.7f, 10.4f)
        tempPath.arcTo(tempRect, 180f, 152f, false)
        canvas.drawPath(tempPath, strokePaint)

        // Matching refined rounded lock body
        tempRect.set(5.4f, 10.2f, 18.6f, 20.4f)
        canvas.drawRoundRect(tempRect, 2.5f, 2.5f, strokePaint)

        // Crisp keyhole dot & slim stem
        canvas.drawCircle(12f, 14.5f, 1.35f, fillPaint)
        strokePaint.strokeWidth = 1.55f
        canvas.drawLine(12f, 15.4f, 12f, 17.5f, strokePaint)
    }

    private fun drawClimateFan(canvas: Canvas) {
        // Iconic Tesla 4-blade sculpted turbine fan around hollow center hub
        tempPath.reset()
        tempPath.moveTo(11.6f, 9.7f)
        tempPath.cubicTo(9.8f, 6.8f, 10.1f, 3.0f, 13.4f, 2.7f)
        tempPath.cubicTo(16.6f, 2.4f, 17.4f, 5.8f, 15.1f, 7.6f)
        tempPath.cubicTo(14.0f, 8.5f, 13.4f, 9.1f, 13.1f, 10.0f)
        tempPath.close()

        for (i in 0 until 4) {
            canvas.save()
            canvas.rotate(i * 90f, 12f, 12f)
            canvas.drawPath(tempPath, fillPaint)
            canvas.restore()
        }

        // Precision hub ring
        strokePaint.strokeWidth = 1.45f
        canvas.drawCircle(12f, 12f, 2.05f, strokePaint)
    }

    private fun drawHeadlampFlash(canvas: Canvas) {
        // Sleek Tesla D-profile LED headlamp housing (facing left)
        tempPath.reset()
        tempPath.moveTo(12.2f, 6.0f)
        tempPath.cubicTo(17.8f, 6.0f, 21.0f, 8.4f, 21.0f, 12.0f)
        tempPath.cubicTo(21.0f, 15.6f, 17.8f, 18.0f, 12.2f, 18.0f)
        tempPath.cubicTo(11.1f, 15.8f, 11.1f, 8.2f, 12.2f, 6.0f)
        tempPath.close()
        canvas.drawPath(tempPath, strokePaint)

        // Subtle inner lens reflector arc
        strokePaint.strokeWidth = 1.25f
        tempPath.reset()
        tempPath.moveTo(14.4f, 8.8f)
        tempPath.cubicTo(16.9f, 9.2f, 18.2f, 10.4f, 18.2f, 12.0f)
        tempPath.cubicTo(18.2f, 13.6f, 16.9f, 14.8f, 14.4f, 15.2f)
        canvas.drawPath(tempPath, strokePaint)

        // 3 crisp splayed high-beam rays on the left
        strokePaint.strokeWidth = 1.65f
        canvas.drawLine(3.2f, 7.6f, 8.3f, 8.4f, strokePaint)
        canvas.drawLine(2.6f, 12.0f, 8.1f, 12.0f, strokePaint)
        canvas.drawLine(3.2f, 16.4f, 8.3f, 15.6f, strokePaint)
    }

    private fun drawTrunkProfile(canvas: Canvas, open: Boolean) {
        // Signature Tesla side-profile fastback sedan with hinged open rear trunk
        strokePaint.strokeWidth = 1.55f

        // Fastback roofline + hood + front bumper + rocker + rear bumper (trunk gap open at rear)
        tempPath.reset()
        tempPath.moveTo(7.4f, 10.6f)
        tempPath.cubicTo(9.4f, 8.8f, 12.0f, 8.5f, 14.4f, 9.3f)
        tempPath.lineTo(16.5f, 11.4f) // windshield to hood
        tempPath.cubicTo(18.6f, 11.6f, 20.8f, 12.4f, 21.4f, 14.2f) // sleek front hood
        tempPath.lineTo(21.4f, 16.2f)
        tempPath.lineTo(19.6f, 16.2f)
        canvas.drawPath(tempPath, strokePaint)

        // Lower sill between wheels & rear overhang
        canvas.drawLine(9.2f, 16.2f, 15.4f, 16.2f, strokePaint)
        tempPath.reset()
        tempPath.moveTo(5.0f, 16.2f)
        tempPath.lineTo(3.2f, 16.2f)
        tempPath.lineTo(3.0f, 13.5f)
        tempPath.lineTo(4.0f, 12.2f)
        canvas.drawPath(tempPath, strokePaint)

        // Front & rear wheels
        canvas.drawCircle(7.1f, 16.3f, 2.0f, strokePaint)
        canvas.drawCircle(17.5f, 16.3f, 2.0f, strokePaint)

        // Raised hinged rear trunk lid (hinged at rear roofline x=7.4, y=10.6)
        strokePaint.strokeWidth = 1.75f
        canvas.drawLine(7.4f, 10.6f, 3.8f, 6.2f, strokePaint)

        // Subtle directional cue (open up-chevron vs close down-arrow)
        strokePaint.strokeWidth = 1.45f
        if (open) {
            tempPath.reset()
            tempPath.moveTo(7.8f, 6.2f)
            tempPath.lineTo(9.4f, 4.4f)
            tempPath.lineTo(11.0f, 6.2f)
            canvas.drawPath(tempPath, strokePaint)
        } else {
            canvas.drawLine(9.6f, 4.0f, 9.6f, 7.6f, strokePaint)
            tempPath.reset()
            tempPath.moveTo(8.1f, 6.2f)
            tempPath.lineTo(9.6f, 7.8f)
            tempPath.lineTo(11.1f, 6.2f)
            canvas.drawPath(tempPath, strokePaint)
        }
    }

    private fun drawEnginePower(canvas: Canvas) {
        // Pure automotive ignition power symbol
        strokePaint.strokeWidth = 1.75f
        tempRect.set(4.5f, 5.2f, 19.5f, 20.2f)
        canvas.drawArc(tempRect, -56f, 292f, false, strokePaint)
        canvas.drawLine(12f, 3.2f, 12f, 11.4f, strokePaint)
    }

    private fun drawEngineStop(canvas: Canvas) {
        // Precision ring + clean rounded square engine stop core
        strokePaint.strokeWidth = 1.65f
        canvas.drawCircle(12f, 12f, 8.2f, strokePaint)
        tempRect.set(8.6f, 8.6f, 15.4f, 15.4f)
        canvas.drawRoundRect(tempRect, 1.4f, 1.4f, fillPaint)
    }

    /**
     * mode: 0 = VENT (slight top gap + breeze), 1 = OPEN (down arrow), 2 = CLOSE (up arrow)
     */
    private fun drawDoorWindow(canvas: Canvas, mode: Int) {
        strokePaint.strokeWidth = 1.6f
        // Automotive door & A-pillar window frame
        tempPath.reset()
        tempPath.moveTo(4.6f, 19.4f)
        tempPath.lineTo(4.6f, 11.5f)
        tempPath.lineTo(11.2f, 4.8f)
        tempPath.lineTo(19.4f, 4.8f)
        tempPath.lineTo(19.4f, 19.4f)
        tempPath.close()
        canvas.drawPath(tempPath, strokePaint)

        // Door beltline divider
        canvas.drawLine(4.6f, 13.6f, 19.4f, 13.6f, strokePaint)

        when (mode) {
            0 -> {
                // Vent mode: glass slightly cracked open near top + 2 sleek breeze lines
                strokePaint.strokeWidth = 1.35f
                canvas.drawLine(8.2f, 8.4f, 19.2f, 8.4f, strokePaint)
                tempPath.reset()
                tempPath.moveTo(11.8f, 6.6f)
                tempPath.cubicTo(13.2f, 5.6f, 14.4f, 7.6f, 16.0f, 6.6f)
                canvas.drawPath(tempPath, strokePaint)
                // Tiny door handle bar
                canvas.drawLine(14.8f, 16.2f, 17.2f, 16.2f, strokePaint)
            }
            1 -> {
                // Open: crisp downward arrow inside window
                strokePaint.strokeWidth = 1.55f
                canvas.drawLine(13.2f, 7.2f, 13.2f, 11.4f, strokePaint)
                tempPath.reset()
                tempPath.moveTo(11.2f, 9.7f)
                tempPath.lineTo(13.2f, 11.6f)
                tempPath.lineTo(15.2f, 9.7f)
                canvas.drawPath(tempPath, strokePaint)
                canvas.drawLine(14.8f, 16.2f, 17.2f, 16.2f, strokePaint)
            }
            else -> {
                // Close: crisp upward arrow inside window
                strokePaint.strokeWidth = 1.55f
                canvas.drawLine(13.2f, 11.6f, 13.2f, 7.4f, strokePaint)
                tempPath.reset()
                tempPath.moveTo(11.2f, 9.2f)
                tempPath.lineTo(13.2f, 7.2f)
                tempPath.lineTo(15.2f, 9.2f)
                canvas.drawPath(tempPath, strokePaint)
                canvas.drawLine(14.8f, 16.2f, 17.2f, 16.2f, strokePaint)
            }
        }
    }

    /**
     * mode: 0 = TILT (rear edge hinged upward), 1 = OPEN (sliding panel + back arrow), 2 = CLOSE
     */
    private fun drawSunroofProfile(canvas: Canvas, mode: Int) {
        strokePaint.strokeWidth = 1.6f
        // Sleek sedan cabin roofline silhouette (front facing right)
        tempPath.reset()
        tempPath.moveTo(2.8f, 17.6f)
        tempPath.cubicTo(4.2f, 13.8f, 6.2f, 11.4f, 9.2f, 11.2f)
        canvas.drawPath(tempPath, strokePaint)

        // Windshield & front cowl
        tempPath.reset()
        tempPath.moveTo(15.6f, 11.2f)
        tempPath.lineTo(19.0f, 14.8f)
        tempPath.lineTo(21.4f, 16.0f)
        tempPath.lineTo(21.4f, 17.6f)
        canvas.drawPath(tempPath, strokePaint)

        // Beltline
        strokePaint.strokeWidth = 1.4f
        canvas.drawLine(2.8f, 17.6f, 21.4f, 17.6f, strokePaint)

        when (mode) {
            0 -> {
                // Tilt: sunroof glass hinged at front (15.4, 11.0), rear edge angled cleanly up
                strokePaint.strokeWidth = 1.8f
                canvas.drawLine(15.4f, 11.0f, 9.4f, 8.1f, strokePaint)
                // Tiny upward vent arrow above rear gap
                strokePaint.strokeWidth = 1.4f
                tempPath.reset()
                tempPath.moveTo(8.0f, 6.3f)
                tempPath.lineTo(9.5f, 4.7f)
                tempPath.lineTo(11.0f, 6.3f)
                canvas.drawPath(tempPath, strokePaint)
            }
            1 -> {
                // Open: sunroof panel lifted and slid back along roof
                strokePaint.strokeWidth = 1.8f
                canvas.drawLine(6.8f, 9.1f, 12.6f, 9.1f, strokePaint)
                // Subtle rearward slide arrow
                strokePaint.strokeWidth = 1.4f
                canvas.drawLine(16.0f, 6.5f, 11.2f, 6.5f, strokePaint)
                tempPath.reset()
                tempPath.moveTo(12.8f, 5.0f)
                tempPath.lineTo(11.0f, 6.5f)
                tempPath.lineTo(12.8f, 8.0f)
                canvas.drawPath(tempPath, strokePaint)
            }
            else -> {
                // Close: flush roof line + clean downward close arrow
                strokePaint.strokeWidth = 1.7f
                canvas.drawLine(9.2f, 11.2f, 15.6f, 11.2f, strokePaint)
                strokePaint.strokeWidth = 1.45f
                canvas.drawLine(12.4f, 4.6f, 12.4f, 8.6f, strokePaint)
                tempPath.reset()
                tempPath.moveTo(10.6f, 7.0f)
                tempPath.lineTo(12.4f, 8.8f)
                tempPath.lineTo(14.2f, 7.0f)
                canvas.drawPath(tempPath, strokePaint)
            }
        }
    }

    private fun drawCarFrontSilhouette(canvas: Canvas) {
        // Minimalist Tesla-style front sedan silhouette
        strokePaint.strokeWidth = 1.6f
        // Greenhouse / cabin arch
        tempPath.reset()
        tempPath.moveTo(5.6f, 11.0f)
        tempPath.lineTo(7.4f, 6.6f)
        tempPath.cubicTo(8.0f, 5.4f, 9.4f, 5.0f, 12.0f, 5.0f)
        tempPath.cubicTo(14.6f, 5.0f, 16.0f, 5.4f, 16.6f, 6.6f)
        tempPath.lineTo(18.4f, 11.0f)
        canvas.drawPath(tempPath, strokePaint)

        // Subtle wing mirrors
        canvas.drawLine(3.6f, 10.1f, 5.5f, 10.6f, strokePaint)
        canvas.drawLine(20.4f, 10.1f, 18.5f, 10.6f, strokePaint)

        // Wide planted lower body & fender stance
        tempRect.set(3.8f, 11.0f, 20.2f, 17.4f)
        canvas.drawRoundRect(tempRect, 2.4f, 2.4f, strokePaint)

        // Left & right tires
        canvas.drawLine(6.2f, 17.5f, 6.2f, 19.6f, strokePaint)
        canvas.drawLine(17.8f, 17.5f, 17.8f, 19.6f, strokePaint)

        // Crisp LED DRL headlamp bars
        strokePaint.strokeWidth = 1.55f
        canvas.drawLine(6.3f, 14.0f, 8.7f, 14.3f, strokePaint)
        canvas.drawLine(17.7f, 14.0f, 15.3f, 14.3f, strokePaint)
    }

    private fun drawHornSound(canvas: Canvas) {
        // Minimalist sculpted automotive horn + acoustic sound waves
        strokePaint.strokeWidth = 1.6f
        tempPath.reset()
        tempPath.moveTo(3.2f, 10.2f)
        tempPath.lineTo(7.0f, 10.2f)
        tempPath.lineTo(13.2f, 6.4f)
        tempPath.lineTo(13.2f, 17.6f)
        tempPath.lineTo(7.0f, 13.8f)
        tempPath.lineTo(3.2f, 13.8f)
        tempPath.close()
        canvas.drawPath(tempPath, strokePaint)

        tempRect.set(13.6f, 8.6f, 17.8f, 15.4f)
        canvas.drawArc(tempRect, -54f, 108f, false, strokePaint)
        tempRect.set(15.2f, 6.2f, 21.0f, 17.8f)
        canvas.drawArc(tempRect, -56f, 112f, false, strokePaint)
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
