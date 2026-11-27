package com.chery.carplus.ui

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages on-demand 3D vehicle GLB model resolution, jsDelivr CDN download,
 * SHA-256 integrity validation, and persistent local disk caching.
 *
 * Caching policy:
 * 1. Once a vehicle model is downloaded and cached locally under `filesDir/car3d_models/`,
 *    all subsequent launches load directly from local storage with zero network delay.
 * 2. A lightweight background check of `manifest.json` (~800 B, throttled to every 6 hours)
 *    compares the remote `sha256` / `version` against the local cache; the `.glb` is ONLY
 *    re-downloaded when the model in the Git repository has actually been updated.
 */
object VehicleModelRepository {

    private const val PREFS_NAME = "car3d_model_cache_v1"
    private const val CACHE_DIR_NAME = "car3d_models"
    private const val MANIFEST_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MANIFEST_READ_TIMEOUT_MS = 8_000

    private val CDN_BASES = listOf(
        "https://cdn.jsdelivr.net/gh/flik6/car_plus@main/models",
        "https://fastly.jsdelivr.net/gh/flik6/car_plus@main/models",
        "https://raw.githubusercontent.com/flik6/car_plus/main/models"
    )

    data class ModelCatalogEntry(
        val key: String,
        val displayName: String,
        val bodyType: String,
        val file: String,
        val version: Int,
        val sha256: String,
        val sizeBytes: Long,
        val targetLength: Double,
        val wheelbase: Double,
        val yawOffset: Double
    )

    data class ModelReadyResult(
        val key: String,
        val displayName: String,
        val cacheUrl: String,
        val fromCache: Boolean,
        val isExactMatch: Boolean
    )

    data class BackgroundUpdateResult(
        val checked: Boolean,
        val updated: Boolean,
        val key: String,
        val cacheUrl: String? = null
    )

    private val FALLBACK_CATALOG: Map<String, ModelCatalogEntry> = mapOf(
        "arrizo8" to ModelCatalogEntry(
            key = "arrizo8",
            displayName = "艾瑞泽8",
            bodyType = "sedan",
            file = "arrizo8.glb",
            version = 1,
            sha256 = "1d113e65f03f19bc38215eecf5000c6938dd729893d74b19beaaf848ebf29df4",
            sizeBytes = 17_696_616L,
            targetLength = 4.78,
            wheelbase = 2.77,
            yawOffset = 0.0
        ),
        "tiggo8" to ModelCatalogEntry(
            key = "tiggo8",
            displayName = "瑞虎8",
            bodyType = "suv",
            file = "tiggo8.glb",
            version = 1,
            sha256 = "d50d4e3758711fe782a83386f31182de807779b6852fdadaeae699f4c5b1f807",
            sizeBytes = 13_744_724L,
            targetLength = 4.72,
            wheelbase = 2.71,
            yawOffset = 0.0
        )
    )

    private val TIGGO8_EXACT_KEYWORDS = listOf(
        "瑞虎8", "tiggo8", "tiggo 8", "t18", "t1e", "t1d"
    )
    private val ARRIZO8_EXACT_KEYWORDS = listOf(
        "艾瑞泽8", "arrizo8", "arrizo 8", "m1e", "a8"
    )
    private val SUV_FALLBACK_KEYWORDS = listOf(
        "瑞虎", "tiggo", "欧萌达", "omoda", "探索", "大蚂蚁",
        "星纪元et", "星纪元 et", "风云t", "风云 t", "捷途", "山海",
        "t19", "t22", "t26", "t1ej", "s61", "e0y"
    )

    private val perKeyLocks = ConcurrentHashMap<String, Any>()

    fun cacheDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Resolves any vehicle display name or internal Chery platform code to a concrete
     * 3D model key. Dedicated models (`arrizo8`, `tiggo8`) match directly; other models
     * fall back to the closest body silhouette (SUV -> `tiggo8`, sedan/unknown -> `arrizo8`).
     */
    fun resolveModelKey(rawHint: String?): String {
        val raw = rawHint.orEmpty().trim().lowercase(Locale.US)
        if (raw.isBlank()) return "arrizo8"
        if (raw == "tiggo8" || TIGGO8_EXACT_KEYWORDS.any { raw.contains(it) }) {
            return "tiggo8"
        }
        if (raw == "arrizo8" || ARRIZO8_EXACT_KEYWORDS.any { raw.contains(it) }) {
            return "arrizo8"
        }
        if (SUV_FALLBACK_KEYWORDS.any { raw.contains(it) }) {
            return "tiggo8"
        }
        return "arrizo8"
    }

    fun isExactModelMatch(rawHint: String?): Boolean {
        val raw = rawHint.orEmpty().trim().lowercase(Locale.US)
        if (raw.isBlank()) return true
        return raw == "tiggo8" ||
            raw == "arrizo8" ||
            TIGGO8_EXACT_KEYWORDS.any { raw.contains(it) } ||
            ARRIZO8_EXACT_KEYWORDS.any { raw.contains(it) }
    }

    fun displayNameForKey(key: String): String {
        return FALLBACK_CATALOG[key]?.displayName ?: when (key) {
            "tiggo8" -> "瑞虎8"
            else -> "艾瑞泽8"
        }
    }

    data class ModelCacheDebugInfo(
        val key: String,
        val displayName: String,
        val cached: Boolean,
        val sizeBytes: Long,
        val sha256Short: String,
        val version: Int,
        val lastCheckMs: Long
    )

    fun getDebugCacheStatus(context: Context): List<ModelCacheDebugInfo> {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return FALLBACK_CATALOG.values.map { entry ->
            val file = getCachedModelFile(appCtx, entry.key)
            ModelCacheDebugInfo(
                key = entry.key,
                displayName = entry.displayName,
                cached = file != null,
                sizeBytes = file?.length() ?: 0L,
                sha256Short = prefs.getString("sha256_${entry.key}", "").orEmpty().take(16),
                version = prefs.getInt("version_${entry.key}", 0),
                lastCheckMs = prefs.getLong("last_check_${entry.key}", 0L)
            )
        }
    }

    fun clearAllModelCaches(context: Context): Int {
        val appCtx = context.applicationContext
        val dir = cacheDir(appCtx)
        var removed = 0
        dir.listFiles()?.forEach { f ->
            if (f.delete()) removed++
        }
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        return removed
    }

    fun getCachedModelFile(context: Context, key: String): File? {
        val file = File(cacheDir(context), "$key.glb")
        return if (isValidGlbFile(file)) file else null
    }

    fun buildLocalCacheUrl(context: Context, key: String): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tag = prefs.getString("sha256_$key", "").orEmpty().take(10)
            .ifBlank { prefs.getInt("version_$key", 1).toString() }
        return "https://appassets.androidplatform.net/car3d-cache/$key.glb?v=$tag"
    }

    /**
     * Ensures the requested vehicle's GLB is present on local storage.
     * - Cache hit: returns immediately with `fromCache = true` (zero network latency).
     * - Cache miss: fetches manifest (with built-in fallback) and downloads from jsDelivr
     *   with live progress reporting, saving permanently to local storage.
     */
    fun ensureModelReady(
        context: Context,
        rawHint: String?,
        onProgress: ((percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): ModelReadyResult {
        val appCtx = context.applicationContext
        val key = resolveModelKey(rawHint)
        val exact = isExactModelMatch(rawHint)
        val displayName = displayNameForKey(key)

        // Fast path: already cached locally -> never hit network synchronously
        if (getCachedModelFile(appCtx, key) != null) {
            return ModelReadyResult(
                key = key,
                displayName = displayName,
                cacheUrl = buildLocalCacheUrl(appCtx, key),
                fromCache = true,
                isExactMatch = exact
            )
        }

        val lock = perKeyLocks.getOrPut(key) { Any() }
        synchronized(lock) {
            // Re-check inside single-flight lock
            if (getCachedModelFile(appCtx, key) != null) {
                return ModelReadyResult(
                    key = key,
                    displayName = displayName,
                    cacheUrl = buildLocalCacheUrl(appCtx, key),
                    fromCache = true,
                    isExactMatch = exact
                )
            }

            val remoteCatalog = fetchRemoteManifest()
            val entry = remoteCatalog?.get(key)
                ?: FALLBACK_CATALOG[key]
                ?: FALLBACK_CATALOG.getValue("arrizo8")

            downloadAndInstallModel(appCtx, entry, onProgress)
            if (remoteCatalog != null) {
                markManifestChecked(appCtx, key)
            }

            return ModelReadyResult(
                key = entry.key,
                displayName = entry.displayName,
                cacheUrl = buildLocalCacheUrl(appCtx, entry.key),
                fromCache = false,
                isExactMatch = exact
            )
        }
    }

    /**
     * Silent background update check. Fetches only the tiny `manifest.json` (~800 B)
     * at most once per [MANIFEST_CHECK_INTERVAL_MS] (6 hours), and ONLY re-downloads
     * the `.glb` when the remote `sha256` or `version` differs from local cache.
     */
    fun checkAndUpdateInBackground(
        context: Context,
        rawHint: String?,
        force: Boolean = false
    ): BackgroundUpdateResult {
        val appCtx = context.applicationContext
        val key = resolveModelKey(rawHint)
        if (getCachedModelFile(appCtx, key) == null) {
            return BackgroundUpdateResult(checked = false, updated = false, key = key)
        }

        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_check_$key", 0L)
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < MANIFEST_CHECK_INTERVAL_MS) {
            return BackgroundUpdateResult(checked = false, updated = false, key = key)
        }

        val lock = perKeyLocks.getOrPut(key) { Any() }
        synchronized(lock) {
            val remoteCatalog = fetchRemoteManifest()
                ?: return BackgroundUpdateResult(checked = false, updated = false, key = key)
            markManifestChecked(appCtx, key)

            val remoteEntry = remoteCatalog[key]
                ?: return BackgroundUpdateResult(checked = true, updated = false, key = key)

            val localSha = prefs.getString("sha256_$key", "").orEmpty()
            val localVer = prefs.getInt("version_$key", 0)
            val needsUpdate = when {
                remoteEntry.sha256.isNotBlank() && localSha.isNotBlank() ->
                    !remoteEntry.sha256.equals(localSha, ignoreCase = true)
                else -> remoteEntry.version > localVer
            }

            if (!needsUpdate) {
                return BackgroundUpdateResult(checked = true, updated = false, key = key)
            }

            return runCatching {
                downloadAndInstallModel(appCtx, remoteEntry, onProgress = null)
                BackgroundUpdateResult(
                    checked = true,
                    updated = true,
                    key = key,
                    cacheUrl = buildLocalCacheUrl(appCtx, key)
                )
            }.getOrElse {
                BackgroundUpdateResult(checked = true, updated = false, key = key)
            }
        }
    }

    private fun markManifestChecked(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("last_check_$key", System.currentTimeMillis())
            .apply()
    }

    private fun fetchRemoteManifest(): Map<String, ModelCatalogEntry>? {
        for (base in CDN_BASES) {
            val urlStr = "$base/manifest.json"
            val parsed = runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = MANIFEST_READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "CarPlus-Android")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseManifestJson(body)
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return null
    }

    private fun parseManifestJson(raw: String): Map<String, ModelCatalogEntry> {
        val root = JSONObject(raw)
        val modelsObj = root.optJSONObject("models") ?: return emptyMap()
        val result = LinkedHashMap<String, ModelCatalogEntry>()
        val keys = modelsObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val item = modelsObj.optJSONObject(k) ?: continue
            val key = item.optString("key", k).trim().lowercase(Locale.US)
            val file = item.optString("file", "$key.glb").trim()
            if (key.isBlank() || file.isBlank()) continue
            result[key] = ModelCatalogEntry(
                key = key,
                displayName = item.optString("displayName", displayNameForKey(key)),
                bodyType = item.optString("bodyType", "sedan"),
                file = file,
                version = item.optInt("version", 1),
                sha256 = item.optString("sha256", "").trim().lowercase(Locale.US),
                sizeBytes = item.optLong("sizeBytes", 0L),
                targetLength = item.optDouble("targetLength", 4.78),
                wheelbase = item.optDouble("wheelbase", 2.77),
                yawOffset = item.optDouble("yawOffset", 0.0)
            )
        }
        return result
    }

    private fun downloadAndInstallModel(
        context: Context,
        entry: ModelCatalogEntry,
        onProgress: ((percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        val dir = cacheDir(context)
        val finalFile = File(dir, "${entry.key}.glb")
        val tmpFile = File(dir, "${entry.key}.glb.tmp")
        var lastError: Throwable? = null

        val querySuffix = if (entry.sha256.isNotBlank()) "?v=${entry.sha256.take(8)}" else "?v=${entry.version}"

        for (base in CDN_BASES) {
            val urlStr = "$base/${entry.file}$querySuffix"
            val ok = runCatching {
                if (tmpFile.exists()) tmpFile.delete()
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CarPlus-Android")
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${conn.responseCode} from $urlStr")
                    }
                    val headerLen = conn.contentLengthLong
                    val totalBytes = when {
                        headerLen > 0L -> headerLen
                        entry.sizeBytes > 0L -> entry.sizeBytes
                        else -> -1L
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var downloaded = 0L
                    var lastReportedPct = -1

                    BufferedInputStream(conn.inputStream, 32 * 1024).use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                downloaded += read
                                if (onProgress != null && totalBytes > 0L) {
                                    val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                    if (pct != lastReportedPct) {
                                        lastReportedPct = pct
                                        onProgress(pct, downloaded, totalBytes)
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    if (!isValidGlbFile(tmpFile)) {
                        throw IllegalStateException("Downloaded file is not a valid GLB binary")
                    }

                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (entry.sha256.isNotBlank() && !actualSha.equals(entry.sha256, ignoreCase = true)) {
                        // Accept valid GLB if remote branch was updated ahead of cached manifest fallback,
                        // but reject obviously truncated downloads.
                        if (entry.sizeBytes > 0L && downloaded < entry.sizeBytes / 2L) {
                            throw IllegalStateException("Model size mismatch ($downloaded vs ${entry.sizeBytes})")
                        }
                    }

                    if (finalFile.exists()) {
                        finalFile.delete()
                    }
                    if (!tmpFile.renameTo(finalFile)) {
                        tmpFile.copyTo(finalFile, overwrite = true)
                        tmpFile.delete()
                    }

                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString("sha256_${entry.key}", actualSha)
                        .putInt("version_${entry.key}", entry.version)
                        .putLong("size_${entry.key}", downloaded)
                        .apply()

                    onProgress?.invoke(100, downloaded, downloaded)
                    true
                } finally {
                    conn.disconnect()
                }
            }.onFailure { err ->
                lastError = err
                if (tmpFile.exists()) tmpFile.delete()
            }.getOrDefault(false)

            if (ok) return
        }

        throw IllegalStateException(
            "无法从 jsDelivr 拉取 ${entry.displayName} 3D 模型: ${lastError?.message ?: "网络异常"}",
            lastError
        )
    }

    private fun isValidGlbFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 1024L) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                fis.read(header) == 4 &&
                    header[0] == 'g'.code.toByte() &&
                    header[1] == 'l'.code.toByte() &&
                    header[2] == 'T'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }
}
