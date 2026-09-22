package com.bopomofo.t9ime.update

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.bopomofo.t9ime.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSize: Long
)

/**
 * GitHub Releases 自動更新管理器 (方案 A：應用內一鍵下載並安裝)
 */
object AppUpdateManager {

    private const val GITHUB_REPO_API =
        "https://api.github.com/repos/korit1201-tech/android-BOPOMOFO-t9/releases/latest"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 非同步檢查是否有新版本發布
     */
    fun checkUpdate(onResult: (hasUpdate: Boolean, info: ReleaseInfo?, error: String?) -> Unit) {
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(GITHUB_REPO_API)
                connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "BopomofoT9IME-Android")
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonStr = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                    val json = JSONObject(jsonStr)

                    val tagName = json.optString("tag_name", "").trim()
                    val body = json.optString("body", "").trim()
                    val remoteVer = tagName.removePrefix("v").removePrefix("V")

                    // 尋找附屬的 release APK
                    val assets = json.optJSONArray("assets")
                    var apkDownloadUrl = ""
                    var apkSize = 0L

                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkDownloadUrl = asset.optString("browser_download_url", "")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                    }

                    val currentVer = BuildConfig.VERSION_NAME
                    val hasUpdate = compareVersion(remoteVer, currentVer) > 0

                    val releaseInfo = if (apkDownloadUrl.isNotEmpty()) {
                        ReleaseInfo(tagName, remoteVer, body, apkDownloadUrl, apkSize)
                    } else null

                    mainHandler.post {
                        onResult(hasUpdate, releaseInfo, null)
                    }
                } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    mainHandler.post {
                        onResult(false, null, "目前尚未建立任何公開 Release 版本")
                    }
                } else {
                    mainHandler.post {
                        onResult(false, null, "GitHub API 回應代碼: $responseCode")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onResult(false, null, e.localizedMessage ?: "網路連線異常")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * 語意化版本比對 (v1 > v2 返回 1，v1 < v2 返回 -1，相等返回 0)
     */
    fun compareVersion(v1: String, v2: String): Int {
        val s1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val s2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(s1.size, s2.size)

        for (i in 0 until maxLen) {
            val num1 = if (i < s1.size) s1[i] else 0
            val num2 = if (i < s2.size) s2[i] else 0
            if (num1 > num2) return 1
            if (num1 < num2) return -1
        }
        return 0
    }

    /**
     * 顯示更新提示對話框
     */
    fun showUpdateDialog(activity: Activity, info: ReleaseInfo) {
        val sizeMb = if (info.apkSize > 0) String.format(" (%.1f MB)", info.apkSize / (1024.0 * 1024.0)) else ""
        val notes = if (info.releaseNotes.isNotBlank()) info.releaseNotes else "包含多項功能改進與穩定性修復。"

        AlertDialog.Builder(activity)
            .setTitle("🎉 發現新版本 ${info.tagName}$sizeMb")
            .setMessage("【當前版本】：v${BuildConfig.VERSION_NAME}\n\n【更新內容】：\n$notes")
            .setPositiveButton("立即下載並更新") { _, _ ->
                downloadAndInstall(activity, info)
            }
            .setNegativeButton("稍後提醒", null)
            .show()
    }

    /**
     * 背景下載 APK 並呼叫系統安裝器覆蓋更新 (方案 A)
     */
    fun downloadAndInstall(activity: Activity, info: ReleaseInfo) {
        // Android 8.0+ 檢查安裝未知來源應用權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, "為完成更新，請在接下來的設定中允許安裝應用程式", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
                return
            }
        }

        val downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val apkFile = File(downloadDir, "bopomofo_t9_update.apk")
        if (apkFile.exists()) {
            apkFile.delete()
        }

        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            // Fallback：跳轉瀏覽器下載
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
            activity.startActivity(browserIntent)
            return
        }

        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("安卓注音九宮格輸入法 更新下載中...")
            setDescription("版本：${info.tagName}")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(apkFile))
            setMimeType("application/vnd.android.package-archive")
        }

        Toast.makeText(activity, "開始在背景下載新版本，完成後將自動提示安裝...", Toast.LENGTH_SHORT).show()

        val downloadId = downloadManager.enqueue(request)

        val onCompleteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    try {
                        context.unregisterReceiver(this)
                    } catch (_: Exception) {}

                    installApk(context, apkFile)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(
                onCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            activity.registerReceiver(
                onCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * 呼叫系統 PackageInstaller 安裝 APK
     */
    private fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "下載檔案遺失，請重新檢查更新", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "喚起安裝失敗: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
