package com.chery.carplus.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import com.chery.carplus.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Interactive 3D vehicle hero renderer powered by Three.js WebGL with
 * on-demand jsDelivr CDN model delivery and permanent local disk caching.
 *
 * Loading choreography:
 * - While the GLB is being downloaded (first time) or parsed, displays a calm
 *   ambient Qiri+ emblem on `#161719` plus a hairline progress bar (only when
 *   actually fetching from CDN).
 * - WebGL shaders pre-compile off-screen before reveal; on the first rendered
 *   frame, the WebView and ambient placeholder cross-fade seamlessly as the car
 *   glides in along a curved drive-in arc.
 */
class VehicleVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var modelKey: String = "arrizo8"
        set(value) {
            val normalized = value.ifBlank { "arrizo8" }
            val prevResolved = VehicleModelRepository.resolveModelKey(field)
            val nextResolved = VehicleModelRepository.resolveModelKey(normalized)
            field = normalized
            fallbackLabel.text = "${VehicleModelRepository.displayNameForKey(nextResolved)} · 3D 视图不可用"
            val alreadyLoadingSameKey = modelLoadJob?.isActive == true && requestedModelKey == nextResolved
            val alreadyFailedSameKey = downloadFailed && requestedModelKey == nextResolved
            if (engineReady &&
                !alreadyLoadingSameKey &&
                !alreadyFailedSameKey &&
                (activeLoadedKey != nextResolved || prevResolved != nextResolved)
            ) {
                requestModelLoad(normalized, isVehicleSwitch = activeLoadedKey != null && activeLoadedKey != nextResolved)
            } else {
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

    var engineRunning: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                pushStateToScene()
            }
        }

    var entranceStyle: String = "auto"
        set(value) {
            val normalized = normalizeEntranceStyle(value)
            if (field != normalized) {
                field = normalized
                pushEntranceStyleToScene()
            }
        }

    var onDoubleTapListener: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var modelLoadJob: Job? = null
    private var bgUpdateJob: Job? = null

    private val bgColor = Color.parseColor("#161719")
    private var engineReady = false
    private var sceneReady = false
    private var activeLoadedKey: String? = null
    private var activeLoadedUrl: String? = null
    private var requestedModelKey: String? = null
    private var loadGeneration = 0
    private var pendingFlash = false
    private var deckProgress = 0f
    private var collapsedVisibleRatio = 0.64f
    private var expandedVisibleRatio = 1.0f
    private var placeholderHidden = false
    private var hasDraggedOnce = false
    private var isDestroyed = false
    private var downloadFailed = false
    private var stateSyncScheduled = false
    private val stateSyncRunnable = Runnable {
        stateSyncScheduled = false
        flushStateToSceneNow()
    }

    private val webView: OrbitWebView
    private val placeholderView: FrameLayout
    private val ambientEmblem: ImageView
    private val progressContainer: LinearLayout
    private val progressTrack: FrameLayout
    private val progressFill: View
    private val loadingCaption: TextView
    private var loadingPulseAnimator: ObjectAnimator? = null
    private val fallbackLabel: TextView
    private val hintBadge: TextView

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler(
            "/car3d-cache/",
            WebViewAssetLoader.InternalStoragePathHandler(context, VehicleModelRepository.cacheDir(context))
        )
        .build()

    init {
        setBackgroundColor(bgColor)
        clipChildren = true
        clipToPadding = true

        webView = OrbitWebView(context).apply {
            setBackgroundColor(bgColor)
            alpha = 0f
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
                    // Fallback only if JS bridge onEngineReady never fires on legacy WebView
                    mainHandler.postDelayed({
                        if (!isDestroyed && !engineReady) {
                            engineReady = true
                            requestModelLoad(modelKey, isVehicleSwitch = false)
                        }
                    }, 1800L)
                }
            }
            addJavascriptInterface(JsBridge(), "Car3DBridge")
        }
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Calm ambient loading stage on #161719: breathing Qiri+ emblem + optional hairline download progress
        ambientEmblem = ImageView(context).apply {
            setImageResource(R.drawable.ic_qiri_adaptive_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Qiri+ Logo"
            alpha = 0.22f
            isClickable = false
            isFocusable = false
        }

        val trackWidthPx = dp(132)
        val trackHeightPx = dp(3)
        progressFill = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = trackHeightPx / 2f
                setColor(Color.parseColor("#3D9BFF"))
            }
        }
        progressTrack = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = trackHeightPx / 2f
                setColor(Color.parseColor("#262A32"))
            }
            addView(progressFill, LayoutParams(0, LayoutParams.MATCH_PARENT, Gravity.START))
        }
        loadingCaption = TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#8E9299"))
            gravity = Gravity.CENTER
        }
        progressContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            alpha = 0f
            addView(progressTrack, LinearLayout.LayoutParams(trackWidthPx, trackHeightPx))
            addView(
                loadingCaption,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(9)
                }
            )
        }

        val stageCenterStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ambientEmblem, LinearLayout.LayoutParams(dp(120), dp(36)))
            addView(
                progressContainer,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(16)
                }
            )
        }

        placeholderView = FrameLayout(context).apply {
            setBackgroundColor(bgColor)
            isClickable = false
            isFocusable = false
            addView(
                stageCenterStack,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    bottomMargin = dp(24)
                }
            )
            setOnClickListener {
                if (downloadFailed && !isDestroyed) {
                    downloadFailed = false
                    requestModelLoad(modelKey, isVehicleSwitch = false, forceReload = true)
                }
            }
        }
        placeholderHidden = false
        addView(placeholderView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        startAmbientPulse()

        // Subtle bottom-right 360° hint pill (hidden until 3D car first appears)
        hintBadge = TextView(context).apply {
            text = "360° 拖动旋转"
            textSize = 10f
            setTextColor(Color.argb(120, 210, 218, 232))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            alpha = 0f
            visibility = View.GONE
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
            text = "3D 视图不可用"
            textSize = 13f
            setTextColor(Color.argb(170, 200, 206, 216))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(fallbackLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Defer WebView page load until after the first native UI frame draws so app launch stays 60fps
        post {
            if (!isDestroyed) {
                webView.loadUrl("https://appassets.androidplatform.net/assets/car3d/index.html")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportZoom(false)
        s.useWideViewPort = false
        s.loadWithOverviewMode = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
    }

    private fun startAmbientPulse() {
        loadingPulseAnimator?.cancel()
        loadingPulseAnimator = ObjectAnimator.ofFloat(ambientEmblem, View.ALPHA, 0.14f, 0.32f).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun showPlaceholderOverlay(animateIn: Boolean) {
        if (isDestroyed) return
        placeholderHidden = false
        placeholderView.animate().cancel()
        placeholderView.translationY = 0f
        if (placeholderView.visibility != View.VISIBLE) {
            placeholderView.alpha = if (animateIn) 0f else 1f
            placeholderView.visibility = View.VISIBLE
        }
        startAmbientPulse()
        if (animateIn) {
            placeholderView.animate()
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .start()
        } else {
            placeholderView.alpha = 1f
        }
    }

    private fun showDownloadProgress(caption: String, percent: Int, showBar: Boolean = true) {
        if (isDestroyed) return
        loadingCaption.text = caption
        loadingCaption.setTextColor(Color.parseColor("#8E9299"))
        progressTrack.visibility = if (showBar) View.VISIBLE else View.GONE
        if (showBar) {
            val targetW = (dp(132) * (percent.coerceIn(0, 100) / 100f)).toInt()
            val lp = progressFill.layoutParams
            if (lp.width != targetW) {
                lp.width = targetW
                progressFill.layoutParams = lp
            }
        }
        if (progressContainer.visibility != View.VISIBLE) {
            progressContainer.visibility = View.VISIBLE
            progressContainer.alpha = 0f
            progressContainer.animate()
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .start()
        }
    }

    private fun hideDownloadProgress() {
        if (progressContainer.visibility == View.VISIBLE) {
            progressContainer.animate().cancel()
            progressContainer.visibility = View.GONE
            progressContainer.alpha = 0f
        }
    }

    private fun showDownloadError(displayName: String) {
        if (isDestroyed) return
        downloadFailed = true
        placeholderView.isClickable = true
        placeholderView.isFocusable = true
        progressTrack.visibility = View.GONE
        loadingCaption.text = "$displayName 3D 模型拉取失败 · 轻触重试"
        loadingCaption.setTextColor(Color.parseColor("#B0B6C2"))
        if (progressContainer.visibility != View.VISIBLE) {
            progressContainer.visibility = View.VISIBLE
            progressContainer.alpha = 1f
        }
    }

    fun reloadCurrentModel() {
        if (isDestroyed) return
        activeLoadedKey = null
        activeLoadedUrl = null
        sceneReady = false
        if (engineReady) {
            requestModelLoad(modelKey, isVehicleSwitch = false, forceReload = true)
        }
    }

    private fun requestModelLoad(
        rawHint: String,
        isVehicleSwitch: Boolean,
        forceReload: Boolean = false
    ) {
        if (isDestroyed || !engineReady) return
        val resolvedKey = VehicleModelRepository.resolveModelKey(rawHint)
        val displayName = VehicleModelRepository.displayNameForKey(resolvedKey)
        requestedModelKey = resolvedKey
        val gen = ++loadGeneration
        downloadFailed = false
        placeholderView.isClickable = false
        placeholderView.isFocusable = false

        if (isVehicleSwitch) {
            sceneReady = false
            showPlaceholderOverlay(animateIn = true)
        }
        hideDownloadProgress()

        modelLoadJob?.cancel()
        modelLoadJob = viewScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (VehicleModelRepository.getCachedModelFile(context, resolvedKey) == null) {
                    mainHandler.post {
                        if (!isDestroyed && loadGeneration == gen) {
                            if (!isVehicleSwitch) {
                                showPlaceholderOverlay(animateIn = false)
                            }
                            showDownloadProgress("正在获取 $displayName 3D 车模...", 0, showBar = true)
                        }
                    }
                }
                runCatching {
                    VehicleModelRepository.ensureModelReady(
                        context = context,
                        rawHint = rawHint,
                        isCancelled = { isDestroyed || loadGeneration != gen }
                    ) { pct, _, _ ->
                        mainHandler.post {
                            if (!isDestroyed && loadGeneration == gen) {
                                val label = if (pct >= 99) {
                                    "正在准备 $displayName 3D 展台..."
                                } else {
                                    "正在获取 $displayName 3D 车模 · $pct%"
                                }
                                showDownloadProgress(label, pct, showBar = true)
                            }
                        }
                    }
                }
            }

            if (isDestroyed || loadGeneration != gen) return@launch

            result.onSuccess { ready ->
                if (!forceReload && activeLoadedKey == ready.key && activeLoadedUrl == ready.cacheUrl && sceneReady) {
                    fadeOutPlaceholderAndRevealWebView()
                    return@onSuccess
                }
                if (!ready.fromCache) {
                    showDownloadProgress("正在准备 ${ready.displayName} 3D 展台...", 100, showBar = true)
                }
                activeLoadedKey = ready.key
                activeLoadedUrl = ready.cacheUrl
                val safeKey = JSONObject.quote(ready.key)
                val safeUrl = JSONObject.quote(ready.cacheUrl)
                val safeStyle = JSONObject.quote(entranceStyle)
                evalJs(
                    "if(window.Car3D){" +
                        "window.Car3D.setCollapsedVisibleRatio($collapsedVisibleRatio);" +
                        "window.Car3D.setExpandedVisibleRatio&&window.Car3D.setExpandedVisibleRatio($expandedVisibleRatio);" +
                        "window.Car3D.setEntranceStyle&&window.Car3D.setEntranceStyle($safeStyle);" +
                        "window.Car3D.loadModel($safeKey,$safeUrl,$forceReload);" +
                        "}"
                )

                // Only check manifest in background when loaded from cache; never re-downloads unless updated
                if (ready.fromCache) {
                    scheduleSilentModelUpdateCheck(ready.key)
                }
            }.onFailure {
                activeLoadedKey = null
                activeLoadedUrl = null
                sceneReady = false
                showDownloadError(displayName)
            }
        }
    }

    private fun scheduleSilentModelUpdateCheck(key: String) {
        bgUpdateJob?.cancel()
        bgUpdateJob = viewScope.launch(Dispatchers.IO) {
            runCatching {
                VehicleModelRepository.checkAndUpdateInBackground(context, key)
            }
            // Do not mutate activeLoadedUrl here: keep activeLoadedUrl pointing at the currently rendered
            // GLB on the main thread so the newly cached URL (?v=<newSha8>) loads cleanly on next reload/switch.
        }
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

    fun playEntranceAnimation(styleOverride: String? = null) {
        deckProgress = 0f
        if (isDestroyed || !sceneReady) return
        val normalizedOverride = styleOverride?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeEntranceStyle(it) }
        if (normalizedOverride != null) {
            val safeStyle = JSONObject.quote(normalizedOverride)
            evalJs(
                "window.Car3D && (window.Car3D.playEntrance ? window.Car3D.playEntrance($safeStyle) " +
                    ": (window.Car3D.playEntranceAnimation && window.Car3D.playEntranceAnimation()));"
            )
        } else {
            evalJs(
                "window.Car3D && (window.Car3D.playEntrance ? window.Car3D.playEntrance() " +
                    ": (window.Car3D.playEntranceAnimation && window.Car3D.playEntranceAnimation()));"
            )
        }
    }

    fun setCollapsedVisibleRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0.40f, 1.0f)
        collapsedVisibleRatio = clamped
        if (isDestroyed || !engineReady) return
        evalJs("window.Car3D && window.Car3D.setCollapsedVisibleRatio($clamped);")
    }

    fun setExpandedVisibleRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0.40f, 1.0f)
        if (kotlin.math.abs(expandedVisibleRatio - clamped) < 0.0005f) return
        expandedVisibleRatio = clamped
        if (isDestroyed || !engineReady) return
        evalJs("window.Car3D && window.Car3D.setExpandedVisibleRatio && window.Car3D.setExpandedVisibleRatio($clamped);")
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
        } else if (!hasDraggedOnce && sceneReady && webView.visibility == View.VISIBLE) {
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

    private fun normalizeEntranceStyle(raw: String): String = when (raw.trim()) {
        "right_drive", "left_drive", "reverse_park", "bay_exit", "auto" -> raw.trim()
        else -> "auto"
    }

    private fun pushEntranceStyleToScene() {
        if (isDestroyed || !engineReady) return
        val safeStyle = JSONObject.quote(entranceStyle)
        evalJs("window.Car3D && window.Car3D.setEntranceStyle && window.Car3D.setEntranceStyle($safeStyle);")
    }

    private fun pushStateToScene() {
        if (isDestroyed || !sceneReady) return
        if (!stateSyncScheduled) {
            stateSyncScheduled = true
            mainHandler.post(stateSyncRunnable)
        }
    }

    private fun flushStateToSceneNow() {
        if (isDestroyed || !sceneReady) return
        val safeStyle = JSONObject.quote(entranceStyle)
        val payload = JSONObject().apply {
            put("vehicleLocked", vehicleLocked)
            put("trunkOpen", trunkOpen)
            put("skylightOpen", skylightOpen)
            put("skylightMode", skylightMode)
            put("windowsMode", windowsMode)
            put("climateOn", climateOn)
            put("climateTemp", climateTemp)
            put("engineRunning", engineRunning)
        }
        webView.evaluateJavascript(
            "if(window.Car3D){" +
                "window.Car3D.setEntranceStyle&&window.Car3D.setEntranceStyle($safeStyle);" +
                "window.Car3D.setState($payload);" +
                "}",
            null
        )
    }

    private fun evalJs(script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
        } else {
            mainHandler.post {
                if (!isDestroyed) {
                    webView.evaluateJavascript(script, null)
                }
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
        stateSyncScheduled = false
        modelLoadJob?.cancel()
        bgUpdateJob?.cancel()
        viewScope.cancel()
        loadingPulseAnimator?.cancel()
        loadingPulseAnimator = null
        mainHandler.removeCallbacksAndMessages(null)
        webView.removeJavascriptInterface("Car3DBridge")
        webView.stopLoading()
        webView.destroy()
        super.onDetachedFromWindow()
    }

    private fun fadeOutPlaceholderAndRevealWebView() {
        if (isDestroyed) return
        if (webView.alpha < 1f) {
            webView.animate().cancel()
            webView.animate()
                .alpha(1f)
                .setDuration(300L)
                .setInterpolator(MotionSpec.EASE_OUT_EXPO)
                .start()
        }
        if (!hasDraggedOnce && deckProgress <= 0.05f && hintBadge.visibility != View.VISIBLE) {
            hintBadge.visibility = View.VISIBLE
            hintBadge.alpha = 0f
            hintBadge.animate()
                .alpha(1f)
                .setStartDelay(520L)
                .setDuration(320L)
                .setInterpolator(MotionSpec.EASE_STANDARD)
                .start()
        }
        if (placeholderHidden) return
        placeholderHidden = true
        loadingPulseAnimator?.cancel()
        loadingPulseAnimator = null
        if (placeholderView.visibility != View.VISIBLE) return
        placeholderView.animate().cancel()
        placeholderView.animate()
            .alpha(0f)
            .translationY(-dp(6).toFloat())
            .setDuration(360L)
            .setInterpolator(MotionSpec.EASE_OUT_EXPO)
            .withEndAction {
                placeholderView.visibility = View.GONE
                placeholderView.translationY = 0f
                hideDownloadProgress()
            }
            .start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class JsBridge {
        @JavascriptInterface
        fun onEngineReady() {
            mainHandler.post {
                if (isDestroyed || engineReady) return@post
                engineReady = true
                val safeStyle = JSONObject.quote(entranceStyle)
                evalJs(
                    "if(window.Car3D){" +
                        "window.Car3D.setCollapsedVisibleRatio($collapsedVisibleRatio);" +
                        "window.Car3D.setExpandedVisibleRatio&&window.Car3D.setExpandedVisibleRatio($expandedVisibleRatio);" +
                        "window.Car3D.setEntranceStyle&&window.Car3D.setEntranceStyle($safeStyle);" +
                        "window.Car3D.resize();" +
                        "}"
                )
                requestModelLoad(modelKey, isVehicleSwitch = false)
            }
        }

        @JavascriptInterface
        fun onSceneReady(@Suppress("UNUSED_PARAMETER") activeModel: String?) {
            mainHandler.post {
                if (isDestroyed) return@post
                sceneReady = true
                val deckJs = if (deckProgress > 0f) "window.Car3D.setDeckProgress($deckProgress);" else ""
                evalJs(
                    "if(window.Car3D){" +
                        "window.Car3D.setCollapsedVisibleRatio($collapsedVisibleRatio);" +
                        "window.Car3D.setExpandedVisibleRatio&&window.Car3D.setExpandedVisibleRatio($expandedVisibleRatio);" +
                        "window.Car3D.resize();" +
                        deckJs +
                        "}"
                )
                pushStateToScene()
                if (pendingFlash) {
                    pendingFlash = false
                    flashLights()
                }
                // Safety fallback if onFirstFrameRendered callback is delayed
                mainHandler.postDelayed({ fadeOutPlaceholderAndRevealWebView() }, 200L)
            }
        }

        @JavascriptInterface
        fun onFirstFrameRendered() {
            mainHandler.post {
                fadeOutPlaceholderAndRevealWebView()
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
        fun onWebGLError(errorMsg: String?) {
            mainHandler.post {
                if (isDestroyed) return@post
                val msg = errorMsg.orEmpty()
                if (msg.startsWith("GLB load failed")) {
                    // Recoverable model file error -> allow tap-to-retry instead of hiding WebView permanently
                    val key = VehicleModelRepository.resolveModelKey(modelKey)
                    VehicleModelRepository.getCachedModelFile(context, key)?.delete()
                    activeLoadedKey = null
                    activeLoadedUrl = null
                    sceneReady = false
                    showPlaceholderOverlay(animateIn = false)
                    showDownloadError(VehicleModelRepository.displayNameForKey(key))
                    return@post
                }
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
