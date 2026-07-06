package acr.browser.lightning.config

import acr.browser.lightning.BuildConfig
import acr.browser.lightning.R
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.log.Logger
import acr.browser.lightning.preference.UserPreferencesDataStore
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateManager @Inject constructor(
    private val application: Application,
    private val logger: Logger,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val appCoroutineScope: AppCoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 检查是否有新版本，如果有则弹出确认框
     */
    suspend fun checkForUpdate(appConfig: AppConfig?) {
        if (appConfig == null) {
            logger.log(TAG, "checkForUpdate: appConfig is null")
            return
        }
        val remoteVersion = appConfig.version
        val downloadUrl = appConfig.url
        if (remoteVersion.isEmpty() || downloadUrl.isEmpty()) {
            logger.log(TAG, "checkForUpdate: version or url is empty")
            return
        }

        val currentVersion = BuildConfig.VERSION_NAME
        if (!isNewerVersion(remoteVersion, currentVersion)) {
            logger.log(TAG, "checkForUpdate: remote=$remoteVersion <= current=$currentVersion, no update")
            return
        }

        // 检查是否已经提示过此版本
        val promptedVersion = userPreferencesDataStore.remoteUpdatePromptedVersion.get()
        if (promptedVersion == remoteVersion) {
            logger.log(TAG, "checkForUpdate: already prompted for version $remoteVersion")
            return
        }

        // 记录已提示，避免重复弹窗
        userPreferencesDataStore.remoteUpdatePromptedVersion.set(remoteVersion)
        logger.log(TAG, "checkForUpdate: new version found remote=$remoteVersion, current=$currentVersion")

        // 在主线程弹出确认框
        appCoroutineScope.launch(coroutineDispatchers.main) {
            showUpdateDialog(remoteVersion, downloadUrl)
        }
    }

    /**
     * 语义化版本比较
     */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * 弹出更新确认框
     */
    private fun showUpdateDialog(version: String, url: String) {
        val dialog = AlertDialog.Builder(application)
            .setTitle(application.getString(R.string.update_title))
            .setMessage(application.getString(R.string.update_message, version))
            .setPositiveButton(application.getString(R.string.update_download)) { _, _ ->
                downloadAndInstall(url)
            }
            .setNegativeButton(application.getString(R.string.update_later), null)
            .setCancelable(false)
            .show()

        // 设置 dialog 窗口类型，确保能从 Application context 正常显示
        dialog.window?.let { window ->
            window.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
    }

    /**
     * 下载 APK 并安装
     */
    private fun downloadAndInstall(url: String) {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            try {
                logger.log(TAG, "Starting APK download from $url")

                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    logger.log(TAG, "Download failed: HTTP ${response.code}")
                    withContext(coroutineDispatchers.main) {
                        Toast.makeText(application, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val body = response.body

                val apkFile = File(application.cacheDir, APK_FILE_NAME)
                FileOutputStream(apkFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }

                logger.log(TAG, "APK downloaded to ${apkFile.absolutePath}")
                withContext(coroutineDispatchers.main) {
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                logger.log(TAG, "Download failed: ${e.message}")
                withContext(coroutineDispatchers.main) {
                    Toast.makeText(application, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 安装 APK
     */
    private fun installApk(file: File) {
        if (!file.exists()) {
            logger.log(TAG, "APK file not found: ${file.absolutePath}")
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                application,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            application.startActivity(intent)
            logger.log(TAG, "Install intent sent for ${file.absolutePath}")
        } catch (e: Exception) {
            logger.log(TAG, "Failed to install APK: ${e.message}")
            Toast.makeText(application, R.string.update_install_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val APK_FILE_NAME = "update.apk"
    }
}
