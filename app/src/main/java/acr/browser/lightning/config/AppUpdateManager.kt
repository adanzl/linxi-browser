package acr.browser.lightning.config

import acr.browser.lightning.BuildConfig
import acr.browser.lightning.R
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.log.Logger
import android.app.Application
import android.content.Intent
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateManager @Inject constructor(
    private val application: Application,
    private val logger: Logger,
    private val appCoroutineScope: AppCoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
) {

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

        logger.log(TAG, "checkForUpdate: new version found remote=$remoteVersion, current=$currentVersion")

        // 在主线程启动 UpdatePromptActivity 展示对话框
        appCoroutineScope.launch(coroutineDispatchers.main) {
            val intent = Intent(application, UpdatePromptActivity::class.java).apply {
                putExtra("version", remoteVersion)
                putExtra("url", downloadUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            application.startActivity(intent)
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

    companion object {
        private const val TAG = "AppUpdateManager"
    }
}
