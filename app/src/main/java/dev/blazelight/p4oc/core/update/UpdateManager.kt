package dev.blazelight.p4oc.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import dev.blazelight.p4oc.BuildConfig
import dev.blazelight.p4oc.MainActivity
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateManager"
private const val GITHUB_API = "https://api.github.com/repos/theblazehen/P4OC/releases/latest"
private const val UPDATE_NOTIFICATION_ID = 2001
private const val DOWNLOAD_NOTIFICATION_ID = 2002
private const val CHANNEL_ID = "app_updates"

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("body") val body: String? = null,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0
)

data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String,
    val releaseNotes: String?,
    val assetSize: Long
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class UpdateManager(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init {
        createNotificationChannel()
    }

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext UpdateCheckResult.Error("GitHub API: ${response.code}")
            }

            val body = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Empty response")
            val release = json.decodeFromString<GitHubRelease>(body)

            val latestTag = release.tagName.removePrefix("v")
            val currentTag = BuildConfig.VERSION_NAME

            if (!isNewerVersion(latestTag, currentTag)) {
                return@withContext UpdateCheckResult.UpToDate
            }

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext UpdateCheckResult.Error("No APK found in release")

            val info = UpdateInfo(
                latestVersion = release.tagName,
                currentVersion = BuildConfig.VERSION_NAME,
                downloadUrl = apkAsset.browserDownloadUrl,
                releaseNotes = release.body,
                assetSize = apkAsset.size
            )
            UpdateCheckResult.Available(info)
        } catch (e: Exception) {
            AppLog.e(TAG, "Update check failed: ${e.message}")
            UpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun downloadApk(info: UpdateInfo, onProgress: (Float) -> Unit = {}): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates")
            dir.mkdirs()
            val file = File(dir, "p4oc-${info.latestVersion}.apk")
            if (file.exists()) file.delete()

            val request = Request.Builder()
                .url(info.downloadUrl)
                .header("Accept", "application/octet-stream")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val total = body.contentLength()
            var downloaded = 0L

            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                FileOutputStream(file).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (total > 0) {
                            onProgress(downloaded.toFloat() / total)
                        }
                    }
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Result.success(uri)
        } catch (e: Exception) {
            AppLog.e(TAG, "Download failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun installApk(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
        }
        context.startActivity(intent)
    }

    fun showUpdateNotification(info: UpdateInfo) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_update", info.latestVersion)
            putExtra("update_download_url", info.downloadUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, UPDATE_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Update disponible: ${info.latestVersion}")
            .setContentText(info.releaseNotes?.take(120) ?: "Toca para descargar")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                info.releaseNotes?.take(500) ?: "Nueva versión ${info.latestVersion} disponible"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    fun showDownloadProgressNotification(progress: Float) {
        val pct = (progress * 100).toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Descargando actualización")
            .setContentText("$pct%")
            .setProgress(100, pct, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(DOWNLOAD_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    fun cancelDownloadNotification() {
        NotificationManagerCompat.from(context).cancel(DOWNLOAD_NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "Actualizaciones de la app",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones cuando hay una nueva versión disponible"
            }
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}
