package com.chery.carplus.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Interactive offline 3D vehicle hero renderer powered by local Three.js WebGL
 * assets under `assets/car3d/` (currently featuring Chery Arrizo 8 with
 * extensible multi-model registry support).
 *
 * Keeps full API compatibility with the previous 2D Canvas vehicle view while
 * adding 360-degree touch orbit, pinch zoom, double-tap camera reset,
 * articulated doors/trunk/sunroof/folding-mirrors, and studio PBR reflections.
 */
class VehicleVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var modelKey: String = "arrizo8"
        set(value) {
            val normalized = value.ifBlank { "arrizo8" }
            if (field != normalized) {
                field = normalized
                pushStateToScene()
            }
        }

    var vehicleLocked: Boolean = true
        set(value) {
            val changed = field != value
            field = value
            pushStateToScene()
            if (changed && !value) flashLights()
        }

    var climateOn: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                pushStateToScene()
            }
        }

    var climateTemp: Double = 23.0
        set(value) {
            if (field != value) {
                field = value
                pushStateToScene()
            }
        }

    var trunkOpen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                pushStateToScene()
            }
        }

    var skylightOpen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                pushStateToScene()
            }
        }

    var skylightMode: String = "closed"
        set(value) {
            val normalized = value.ifBlank { "closed" }
            if (field != normalized) {
                field = normalized
                pushStateToScene()
            }
        }

    var windowsMode: String = "closed"
        set(value) {
            val normalized = value.ifBlank { "closed" }
            if (field != normalized) {
                field = normalized
                pushStateToScene()
            }
        }

    var doorsOpen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                pushStateToScene()
            }
        }

    var engineRunning: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                pushStateToScene()
            }
        }

    var onDoubleTapListener: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgColor = Color.parseColor("#161719")
    private var sceneReady = false
    private var pendingFlash = false
    private var deckProgress = 0f
    private var collapsedVisibleRatio = 0.64f
    private var placeholderHidden = false
    private var hasDraggedOnce = false
    private var isDestroyed = false

    private val webView: OrbitWebView
    private val placeholderView: ImageView
    private val fallbackLabel: TextView
    private val hintBadge: TextView

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    init {
        setBackgroundColor(bgColor)
        clipChildren = true
        clipToPadding = true

        webView = OrbitWebView(context).apply {
            setBackgroundColor(bgColor)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            configureWebSettings(settings)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Fallback only if JS bridge onSceneReady never fires (e.g. legacy WebView)
                    mainHandler.postDelayed({
                        if (!isDestroyed && !sceneReady) {
                            sceneReady = true
                            pushStateToScene()
                            if (pendingFlash) {
                                pendingFlash = false
                                flashLights()
                            }
                        }
                    }, 4000L)
                }
            }
            addJavascriptInterface(JsBridge(), "Car3DBridge")
        }
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Keep seamless dark stage clear so the 3D drive-in entrance animation plays unobstructed
        placeholderView = ImageView(context).apply {
            setBackgroundColor(bgColor)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        placeholderHidden = true
        addView(placeholderView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Subtle bottom-right 360° hint pill
        hintBadge = TextView(context).apply {
            text = "360° 拖动旋转"
            textSize = 10f
            setTextColor(Color.argb(120, 210, 218, 232))
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        addView(
            hintBadge,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(4)
                marginEnd = dp(12)
            }
        )

        // Graceful fallback label (only shown if WebGL is unavailable on very old devices)
        fallbackLabel = TextView(context).apply {
            text = "艾瑞泽 8 · 3D 视图不可用"
            textSize = 13f
            setTextColor(Color.argb(170, 200, 206, 216))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(fallbackLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Serve offline assets via WebViewAssetLoader HTTPS origin so GLTFLoader fetches .glb cleanly
        webView.loadUrl("https://appassets.androidplatform.net/assets/car3d/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun configureWebSettings(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.allowFileAccessFromFileURLs = true
        s.allowUniversalAccessFromFileURLs = false
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportZoom(false)
        s.useWideViewPort = false
        s.loadWithOverviewMode = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
    }

    fun flashLights() {
        if (isDestroyed) return
        if (!sceneReady) {
            pendingFlash = true
            return
        }
        evalJs("window.Car3D && window.Car3D.flashLights();")
    }

    fun triggerActionFeedback(actionKey: String) {
        if (isDestroyed || !sceneReady) return
        val safeKey = JSONObject.quote(actionKey)
        evalJs("window.Car3D && window.Car3D.triggerActionFeedback && window.Car3D.triggerActionFeedback($safeKey);")
    }

    fun resetCamera() {
        deckProgress = 0f
        if (isDestroyed || !sceneReady) return
        evalJs("window.Car3D && window.Car3D.resetCamera();")
    }

    fun playEntranceAnimation() {
        deckProgress = 0f
        if (isDestroyed || !sceneReady) return
        evalJs("window.Car3D && window.Car3D.playEntranceAnimation && window.Car3D.playEntranceAnimation();")
    }

    fun setCollapsedVisibleRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0.40f, 1.0f)
        collapsedVisibleRatio = clamped
        if (isDestroyed || !sceneReady) return
        evalJs("window.Car3D && window.Car3D.setCollapsedVisibleRatio($clamped);")
    }

    fun setDeckProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        deckProgress = clamped
        if (clamped > 0.05f) {
            if (hintBadge.visibility == View.VISIBLE) {
                hintBadge.animate().cancel()
                hintBadge.alpha = 0f
                hintBadge.visibility = View.GONE
            }
        } else if (!hasDraggedOnce && webView.visibility == View.VISIBLE) {
            if (hintBadge.visibility != View.VISIBLE || hintBadge.alpha < 1f) {
                hintBadge.visibility = View.VISIBLE
                hintBadge.animate().cancel()
                hintBadge.animate()
                    .alpha(1f)
                    .setDuration(200L)
                    .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                    .start()
            }
        }
        if (isDestroyed || !sceneReady) return
        evalJs("window.Car3D && window.Car3D.setDeckProgress($clamped);")
    }

    fun setTopDownFocus(enabled: Boolean) {
        setDeckProgress(if (enabled) 1f else 0f)
    }

    fun onHostResume() {
        if (isDestroyed) return
        webView.onResume()
        if (sceneReady) {
            evalJs("window.Car3D && window.Car3D.setRenderActive(true);")
        }
    }

    fun onHostPause() {
        if (isDestroyed) return
        if (sceneReady) {
            evalJs("window.Car3D && window.Car3D.setRenderActive(false);")
        }
        webView.onPause()
    }

    private fun pushStateToScene() {
        if (isDestroyed || !sceneReady) return
        val payload = JSONObject().apply {
            put("modelKey", modelKey)
            put("vehicleLocked", vehicleLocked)
            put("doorsOpen", doorsOpen)
            put("trunkOpen", trunkOpen)
            put("skylightOpen", skylightOpen)
            put("skylightMode", skylightMode)
            put("windowsMode", windowsMode)
            put("climateOn", climateOn)
            put("climateTemp", climateTemp)
            put("engineRunning", engineRunning)
        }
        evalJs("window.Car3D && window.Car3D.setState($payload);")
    }

    private fun evalJs(script: String) {
        mainHandler.post {
            if (!isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isDestroyed && w > 0 && h > 0 && (w != oldw || h != oldh)) {
            evalJs("window.Car3D && window.Car3D.resize();")
        }
    }

    override fun onDetachedFromWindow() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        webView.removeJavascriptInterface("Car3DBridge")
        webView.stopLoading()
        webView.destroy()
        super.onDetachedFromWindow()
    }

    private fun fadeOutPlaceholder() {
        if (isDestroyed || placeholderHidden) return
        placeholderHidden = true
        if (placeholderView.visibility != View.VISIBLE) return
        placeholderView.animate().cancel()
        placeholderView.animate()
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .withEndAction {
                placeholderView.visibility = View.GONE
            }
            .start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class JsBridge {
        @JavascriptInterface
        fun onSceneReady(@Suppress("UNUSED_PARAMETER") activeModel: String?) {
            mainHandler.post {
                if (isDestroyed) return@post
                sceneReady = true
                evalJs("window.Car3D && window.Car3D.setCollapsedVisibleRatio($collapsedVisibleRatio);")
                evalJs("window.Car3D && window.Car3D.resize();")
                pushStateToScene()
                if (deckProgress > 0f) {
                    evalJs("window.Car3D && window.Car3D.setDeckProgress($deckProgress);")
                }
                if (pendingFlash) {
                    pendingFlash = false
                    flashLights()
                }
                // Safety fallback if onFirstFrameRendered callback is delayed
                mainHandler.postDelayed({ fadeOutPlaceholder() }, 160L)
            }
        }

        @JavascriptInterface
        fun onFirstFrameRendered() {
            mainHandler.post {
                fadeOutPlaceholder()
            }
        }

        @JavascriptInterface
        fun onVehicleTap() {
            mainHandler.post {
                if (!isDestroyed) {
                    performClick()
                }
            }
        }

        @JavascriptInterface
        fun onVehicleDoubleTap() {
            mainHandler.post {
                if (!isDestroyed) {
                    onDoubleTapListener?.invoke()
                }
            }
        }

        @JavascriptInterface
        fun onFirstOrbitDrag() {
            mainHandler.post {
                if (isDestroyed) return@post
                hasDraggedOnce = true
                if (hintBadge.visibility != View.VISIBLE || hintBadge.alpha <= 0f) return@post
                hintBadge.animate().cancel()
                hintBadge.animate()
                    .alpha(0f)
                    .setDuration(240L)
                    .setInterpolator(MotionSpec.EASE_STANDARD)
                    .withEndAction {
                        hintBadge.visibility = View.GONE
                    }
                    .start()
            }
        }

        @JavascriptInterface
        fun onWebGLError(@Suppress("UNUSED_PARAMETER") errorMsg: String?) {
            mainHandler.post {
                if (isDestroyed) return@post
                placeholderHidden = true
                placeholderView.visibility = View.GONE
                webView.visibility = View.GONE
                hintBadge.visibility = View.GONE
                fallbackLabel.visibility = View.VISIBLE
            }
        }
    }

    /**
     * WebView subclass that claims touch gestures while the user orbits or
     * pinches the 3D vehicle so the parent ScrollView does not hijack drags.
     */
    private class OrbitWebView(context: Context) : WebView(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent?): Boolean {
            when (event?.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
