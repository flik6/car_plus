package com.chery.carplus.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class UpdateCheckResult(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val latestVersionName: String,
    val latestVersionCode: Int,
    val hasUpdate: Boolean,
    val releaseNotes: String,
    val downloadUrl: String,
    val assetSizeBytes: Long,
    val publishedAt: String
)

sealed class InstallApkResult {
    object Launched : InstallApkResult()
    object NeedsPermission : InstallApkResult()
    data class Failed(val message: String) : InstallApkResult()
}

object AppUpdater {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/flik6/car_plus/releases/latest"
    private const val USER_AGENT = "CarPlus-Android"
    private const val APK_ASSET_NAME = "CarPlus-debug.apk"
    private const val UPDATES_DIR_NAME = "updates"
    private const val UPDATE_APK_FILENAME = "CarPlus-update.apk"

    private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""")
    private val VERSION_NAME_REGEX = Regex("""versionName\s*=\s*([0-9.]+)""")
    private val TITLE_BUILD_REGEX = Regex("""Build\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val TITLE_VERSION_REGEX = Regex("""v([0-9]+(?:\.[0-9]+)+)""", RegexOption.IGNORE_CASE)

    fun getCurrentVersion(context: Context): Pair<String, Int> {
        val pkgInfo = getPackageInfoCompat(context)
        val versionName = pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: "1.0.0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode
        }
        return versionName to versionCode
    }

    fun checkForUpdate(context: Context): UpdateCheckResult {
        val pkgInfo = getPackageInfoCompat(context)
        val (currentVersionName, currentVersionCode) = getCurrentVersion(context)

        val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }

        val responseText = try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("检查更新失败，请稍后重试")
            }
            body
        } finally {
            conn.disconnect()
        }

        val root = JSONObject(responseText)
        val tagName = root.optString("tag_name", "").trim()
        val releaseName = root.optString("name", "").trim()
        val releaseBody = root.optString("body", "").trim()
        val publishedAt = root.optString("published_at", "").trim()

        val parsedVersionCode = VERSION_CODE_REGEX.find(releaseBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: TITLE_BUILD_REGEX.find(releaseName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0

        val parsedVersionName = VERSION_NAME_REGEX.find(releaseBody)?.groupValues?.getOrNull(1)
            ?: TITLE_VERSION_REGEX.find(releaseName)?.groupValues?.getOrNull(1)
            ?: tagName.removePrefix("v").takeIf { it.isNotBlank() }
            ?: currentVersionName

        var downloadUrl = ""
        var assetSizeBytes = 0L
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)
                if (name.equals(APK_ASSET_NAME, ignoreCase = true)) {
                    downloadUrl = url
                    assetSizeBytes = size
                    break
                }
                if (downloadUrl.isBlank() && name.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = url
                    assetSizeBytes = size
                }
            }
        }

        val hasUpdate = if (parsedVersionCode > 0) {
            parsedVersionCode > currentVersionCode
        } else {
            val remoteEpochMs = parseIso8601UtcMs(publishedAt)
            remoteEpochMs != null && remoteEpochMs > pkgInfo.lastUpdateTime + 60_000L
        }

        val displayNotes = releaseBody.ifBlank {
            releaseName.ifBlank { "最新版本安装包" }
        }

        return UpdateCheckResult(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            latestVersionName = parsedVersionName,
            latestVersionCode = parsedVersionCode,
            hasUpdate = hasUpdate,
            releaseNotes = displayNotes,
            downloadUrl = downloadUrl,
            assetSizeBytes = assetSizeBytes,
            publishedAt = publishedAt
        )
    }

    fun downloadApk(
        context: Context,
        url: String,
        versionTag: String = "latest",
        isCancelled: (() -> Boolean)? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        require(url.isNotBlank()) { "下载地址为空" }
        val safeTag = versionTag.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
        val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME).apply { mkdirs() }
        // Clean up any older cached update APKs before downloading a new version.
        updatesDir.listFiles()?.forEach { old ->
            if (old.isFile && old.name.endsWith(".apk")) runCatching { old.delete() }
        }
        val targetFile = File(updatesDir, "CarPlus-update-$safeTag.apk")
        val tempFile = File(updatesDir, "CarPlus-update-$safeTag.apk.tmp")
        if (tempFile.exists()) tempFile.delete()

        val conn = openFollowingRedirects(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("安装包下载失败，请稍后重试")
            }
            val total = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                conn.contentLengthLong
            } else {
                conn.contentLength.toLong()
            }
            var downloaded = 0L
            var lastReported = 0L

            BufferedInputStream(conn.inputStream, 16 * 1024).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (isCancelled?.invoke() == true || Thread.currentThread().isInterrupted) {
                            throw java.util.concurrent.CancellationException("安装包下载已取消")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= 64 * 1024L || (total > 0L && downloaded >= total)) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
            }

            if (downloaded <= 0L) {
                throw IOException("下载的安装包为空")
            }
            if (total > 0L && downloaded != total) {
                throw IOException("安装包下载不完整，请重试")
            }
            onProgress(downloaded, if (total > 0L) total else downloaded)

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            return targetFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun installApkResult(activity: Activity, apkFile: File): InstallApkResult {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return InstallApkResult.Failed("安装包文件不存在，请重新下载")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            return runCatching {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(settingsIntent)
                InstallApkResult.NeedsPermission
            }.getOrElse {
                InstallApkResult.Failed("无法打开系统安装权限设置")
            }
        }

        return runCatching {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
            InstallApkResult.Launched
        }.getOrElse {
            InstallApkResult.Failed("无法调起系统安装器，请稍后重试")
        }
    }

    fun installApk(activity: Activity, apkFile: File): Boolean =
        installApkResult(activity, apkFile) is InstallApkResult.Launched

    private fun openFollowingRedirects(startUrl: String, maxRedirects: Int = 5): HttpURLConnection {
        var currentUrl = startUrl
        repeat(maxRedirects + 1) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("下载重定向地址为空 (HTTP $code)")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IOException("下载重定向次数过多")
    }

    private fun getPackageInfoCompat(context: Context): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }

    private fun parseIso8601UtcMs(raw: String): Long? {
        if (raw.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
