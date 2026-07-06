package acr.browser.lightning.config

import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.log.Logger
import acr.browser.lightning.preference.UserPreferencesDataStore
import android.app.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigSyncService @Inject constructor(
    private val application: Application,
    private val appCoroutineScope: AppCoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val logger: Logger,
) {
    private var syncJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun startSync() {
        stopSync()
        syncJob = appCoroutineScope.launch(coroutineDispatchers.io) {
            while (isActive) {
                try {
                    syncOnce()
                } catch (e: Exception) {
                    logger.log(TAG, "Config sync error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun syncOnce() {
        // 1. Fetch version
        val versionBody = fetchUrl(VERSION_URL) ?: return

        // Parse remote version
        val remoteVersion = try {
            RemoteConfig.fromJson(versionBody)?.version
                ?: versionBody.trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            versionBody.trim().takeIf { it.isNotEmpty() }
        } ?: return

        // 2. Compare with local version
        val localVersion = userPreferencesDataStore.remoteConfigVersion.get()
        if (remoteVersion == localVersion && localVersion.isNotEmpty()) {
            return
        }

        // 3. Version changed (or first sync), fetch full config
        val configBody = fetchUrl(CONFIG_URL) ?: return
        val config = RemoteConfig.fromJson(configBody) ?: return

        // 4. Save config
        saveConfig(config)
    }

    private fun fetchUrl(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body.string()
            } else {
                logger.log(TAG, "HTTP ${response.code} for $url")
                null
            }
        } catch (e: Exception) {
            logger.log(TAG, "Failed to fetch $url: ${e.message}")
            null
        }
    }

    private suspend fun saveConfig(config: RemoteConfig) {
        userPreferencesDataStore.remoteConfigVersion.set(config.version)
        userPreferencesDataStore.remoteConfigPin.set(config.admin?.pin ?: "")
        val whitelistOpen = config.whitelist?.open ?: false
        userPreferencesDataStore.remoteWhitelistOpen.set(whitelistOpen)
        // Sync child mode enabled state with server whitelist open flag
        userPreferencesDataStore.childModeEnabled.set(whitelistOpen)
        val urlsStr = config.whitelist?.urls?.joinToString(",") ?: ""
        userPreferencesDataStore.remoteWhitelistUrls.set(urlsStr)

        // Sync remote pin to local child mode pin
        val remotePin = config.admin?.pin ?: ""
        if (remotePin.isNotEmpty()) {
            // Only update if no local pin exists yet
            val localPin = userPreferencesDataStore.childModePin.get()
            if (localPin.isEmpty()) {
                userPreferencesDataStore.childModePin.set(remotePin)
            }
        } else {
            // Server cleared the pin, clear local pin too
            userPreferencesDataStore.childModePin.set("")
        }

        // Save remote marks (homepage quick links)
        val marksJson = JSONArray(config.marks.map { mark ->
            org.json.JSONObject().apply {
                put("title", mark.title)
                put("url", mark.url)
                put("position", mark.position)
            }
        }).toString()
        userPreferencesDataStore.remoteMarks.set(marksJson)

        // Invalidate cached homepage so it regenerates with fresh marks
        val homepageFile = File(File(application.filesDir, "generated-html"), "homepage.html")
        if (homepageFile.exists()) {
            homepageFile.delete()
            logger.log(TAG, "Cached homepage deleted, will regenerate on next tab open")
        }

        // Log all config
        logger.log(TAG, "Config synced | version=${config.version}, env=${config.env}, timestamp=${config.timestamp}")
        logger.log(TAG, "  whitelist: open=$whitelistOpen, urls=${config.whitelist?.urls?.size ?: 0}")
        logger.log(TAG, "  admin: pin=${if (remotePin.isNotEmpty()) "***" else "(empty)"}")
        logger.log(TAG, "  marks: count=${config.marks.size}")
        if (config.marks.isNotEmpty()) {
            config.marks.forEach { mark ->
                logger.log(TAG, "    [${mark.position}] ${mark.title} -> ${mark.url}")
            }
        }
    }

    companion object {
        private const val TAG = "ConfigSyncService"
        private const val VERSION_URL = "https://leo-zhao.natapp4.cc/api/browser/version"
        private const val CONFIG_URL = "https://leo-zhao.natapp4.cc/api/browser/config"
        private const val POLL_INTERVAL_MS = 5000L
    }
}
