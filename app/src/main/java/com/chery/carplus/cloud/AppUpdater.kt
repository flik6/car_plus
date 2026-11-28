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
import java.security.MessageDigest
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
    val publishedAt: String,
    val expectedSha256: String = ""
)

sealed class InstallApkResult {
    object Launched : InstallApkResult()
    object NeedsPermission : InstallApkResult()
    data class Failed(val message: String) : InstallApkResult()
}

object AppUpdater {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/flik6/car_plus/releases/latest"
    private const val USER_AGENT = "QiriPlus-Android"
    private const val APK_ASSET_NAME = "QiriPlus-debug.apk"
    private const val LEGACY_APK_ASSET_NAME = "CarPlus-debug.apk"
    private const val UPDATES_DIR_NAME = "updates"

    private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""")
    private val VERSION_NAME_REGEX = Regex("""versionName\s*=\s*([0-9.]+)""")
    private val TITLE_BUILD_REGEX = Regex("""Build\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val TITLE_VERSION_REGEX = Regex("""v([0-9]+(?:\.[0-9]+)+)""", RegexOption.IGNORE_CASE)
    private val SHA256_REGEX = Regex("""(?:sha256|SHA-256)\s*[:=]\s*([0-9a-fA-F]{64})""")

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

        var primaryDownloadUrl = ""
        var primaryAssetSizeBytes = 0L
        var primarySha256 = ""
        var legacyDownloadUrl = ""
        var legacyAssetSizeBytes = 0L
        var legacySha256 = ""
        var fallbackDownloadUrl = ""
        var fallbackAssetSizeBytes = 0L
        var fallbackSha256 = ""
        val bodySha256 = SHA256_REGEX.find(releaseBody)?.groupValues?.getOrNull(1)
            ?.lowercase(Locale.US).orEmpty()
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)
                val digest = asset.optString("digest", "").trim()
                val assetSha256 = if (digest.startsWith("sha256:", ignoreCase = true)) {
                    digest.substringAfter(":").trim().lowercase(Locale.US)
                        .takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
                        .orEmpty()
                } else {
                    ""
                }
                if (name.equals(APK_ASSET_NAME, ignoreCase = true)) {
                    primaryDownloadUrl = url
                    primaryAssetSizeBytes = size
                    primarySha256 = assetSha256
                    break
                }
                if (legacyDownloadUrl.isBlank() && name.equals(LEGACY_APK_ASSET_NAME, ignoreCase = true)) {
                    legacyDownloadUrl = url
                    legacyAssetSizeBytes = size
                    legacySha256 = assetSha256
                } else if (fallbackDownloadUrl.isBlank() && name.endsWith(".apk", ignoreCase = true)) {
                    fallbackDownloadUrl = url
                    fallbackAssetSizeBytes = size
                    fallbackSha256 = assetSha256
                }
            }
        }
        val (downloadUrl, assetSizeBytes, selectedAssetSha256) = when {
            primaryDownloadUrl.isNotBlank() -> Triple(primaryDownloadUrl, primaryAssetSizeBytes, primarySha256)
            legacyDownloadUrl.isNotBlank() -> Triple(legacyDownloadUrl, legacyAssetSizeBytes, legacySha256)
            else -> Triple(fallbackDownloadUrl, fallbackAssetSizeBytes, fallbackSha256)
        }
        val expectedSha256 = selectedAssetSha256.ifBlank { bodySha256 }

        val hasUpdate = if (parsedVersionCode > 0) {
            parsedVersionCode > currentVersionCode
        } else {
            val remoteEpochMs = parseIso8601UtcMs(publishedAt)
            parsedVersionName != currentVersionName &&
                remoteEpochMs != null &&
                remoteEpochMs > pkgInfo.lastUpdateTime + 60_000L
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
            publishedAt = publishedAt,
            expectedSha256 = expectedSha256
        )
    }

    fun downloadApk(
        context: Context,
        url: String,
        versionTag: String = "latest",
        expectedSha256: String = "",
        isCancelled: (() -> Boolean)? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        require(url.isNotBlank()) { "下载地址为空" }
        val safeTag = versionTag.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
        val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME).apply { mkdirs() }
        // Clean up any older cached update APKs (both QiriPlus-update-* and legacy CarPlus-update-*) before downloading.
        updatesDir.listFiles()?.forEach { old ->
            if (old.isFile && (old.name.endsWith(".apk") || old.name.endsWith(".apk.tmp"))) {
                runCatching { old.delete() }
            }
        }
        val targetFile = File(updatesDir, "QiriPlus-update-$safeTag.apk")
        val tempFile = File(updatesDir, "QiriPlus-update-$safeTag.apk.tmp")
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
            val sha256 = MessageDigest.getInstance("SHA-256")

            BufferedInputStream(conn.inputStream, 16 * 1024).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (isCancelled?.invoke() == true || Thread.currentThread().isInterrupted) {
                            throw java.util.concurrent.CancellationException("安装包下载已取消")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        sha256.update(buffer, 0, read)
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
            val expectedNorm = expectedSha256.trim().lowercase(Locale.US)
            if (expectedNorm.isNotEmpty()) {
                val actualHex = sha256.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
                if (actualHex != expectedNorm) {
                    throw IOException("安装包校验失败（SHA-256 不匹配），已拒绝安装")
                }
            }
            verifyDownloadedApkMatchesCurrentApp(context, tempFile)
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
        val verifyError = runCatching {
            verifyDownloadedApkMatchesCurrentApp(activity, apkFile)
        }.exceptionOrNull()
        if (verifyError != null) {
            return InstallApkResult.Failed(verifyError.message ?: "安装包签名校验失败，已拒绝安装")
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
        var currentUrl = startUrl.trim()
        repeat(maxRedirects + 1) {
            val parsed = URL(currentUrl)
            if (!parsed.protocol.equals("https", ignoreCase = true)) {
                throw IOException("仅允许通过 HTTPS 下载安装包")
            }
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = try {
                conn.responseCode
            } catch (e: Throwable) {
                conn.disconnect()
                throw e
            }
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("下载重定向地址为空 (HTTP $code)")
                }
                val nextUrl = URL(parsed, location)
                if (!nextUrl.protocol.equals("https", ignoreCase = true)) {
                    throw IOException("检测到不安全的 HTTP 下载重定向，已拒绝")
                }
                currentUrl = nextUrl.toString()
            } else {
                return conn
            }
        }
        throw IOException("下载重定向次数过多")
    }

    private fun verifyDownloadedApkMatchesCurrentApp(context: Context, apkFile: File) {
        val archiveInfo = getPackageArchiveInfoCompat(context.packageManager, apkFile.absolutePath)
            ?: throw IOException("无法解析安装包信息，请重新下载")
        if (archiveInfo.packageName != context.packageName) {
            throw IOException("安装包包名不匹配，已拒绝安装")
        }
        val installedDigests = getSigningCertDigests(getPackageInfoWithSignaturesCompat(context))
        val archiveDigests = getSigningCertDigests(archiveInfo)
        if (installedDigests.isEmpty() || archiveDigests.isEmpty() ||
            installedDigests.none { it in archiveDigests }
        ) {
            throw IOException("安装包签名与当前应用不一致，已拒绝安装")
        }
    }

    private fun getPackageInfoWithSignaturesCompat(context: Context): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }
    }

    private fun getPackageArchiveInfoCompat(pm: PackageManager, archivePath: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                archivePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archivePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archivePath, PackageManager.GET_SIGNATURES)
        }
    }

    private fun getSigningCertDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            when {
                signingInfo == null -> emptyArray()
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners.orEmpty()
                else -> signingInfo.signingCertificateHistory?.takeIf { it.isNotEmpty() }
                    ?: signingInfo.apkContentsSigners.orEmpty()
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.mapNotNull { sig ->
            runCatching {
                md.digest(sig.toByteArray()).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
            }.getOrNull()
        }.toSet()
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
