package acr.browser.lightning.config

import acr.browser.lightning.BrowserApp
import acr.browser.lightning.BuildConfig
import acr.browser.lightning.R
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatcherProvider
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.log.AndroidLogger
import acr.browser.lightning.log.Logger
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 透明 Activity，用于展示更新确认框。
 * ConfigSyncService 检测到新版本后启动此 Activity，
 * 由它来展示对话框（因为需要 Activity context）。
 */
class UpdatePromptActivity : Activity() {

    private val appCoroutineScope: AppCoroutineScope by lazy {
        (application as BrowserApp).appCoroutineScope
    }

    private val coroutineDispatchers: CoroutineDispatchers by lazy {
        CoroutineDispatcherProvider(
            main = Dispatchers.Main,
            io = Dispatchers.IO,
            default = Dispatchers.Default
        )
    }

    private val logger: Logger by lazy { AndroidLogger() }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = intent.getStringExtra(EXTRA_VERSION) ?: return
        val url = intent.getStringExtra(EXTRA_URL) ?: return

        showUpdateDialog(version, url)
    }

    private fun showUpdateDialog(version: String, url: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title))
            .setMessage(getString(R.string.update_message, version))
            .setPositiveButton(getString(R.string.update_download)) { _, _ ->
                downloadAndInstall(url)
            }
            .setNegativeButton(getString(R.string.update_later)) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .setOnDismissListener { finish() }
            .show()
    }

    private fun downloadAndInstall(url: String) {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            try {
                logger.log(TAG, "Starting APK download from $url")

                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    logger.log(TAG, "Download failed: HTTP ${response.code}")
                    withContext(coroutineDispatchers.main) {
                        Toast.makeText(this@UpdatePromptActivity, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }

                val body = response.body
                val apkFile = File(cacheDir, APK_FILE_NAME)
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
                    Toast.makeText(this@UpdatePromptActivity, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun installApk(file: File) {
        if (!file.exists()) {
            logger.log(TAG, "APK file not found: ${file.absolutePath}")
            finish()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            logger.log(TAG, "Install intent sent for ${file.absolutePath}")
        } catch (e: Exception) {
            logger.log(TAG, "Failed to install APK: ${e.message}")
            Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        private const val TAG = "UpdatePromptActivity"
        private const val APK_FILE_NAME = "update.apk"

        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
    }
}
