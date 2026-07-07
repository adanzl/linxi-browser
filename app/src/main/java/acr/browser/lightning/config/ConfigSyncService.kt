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
import org.json.JSONObject
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
    private val appUpdateManager: AppUpdateManager,
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

        // Save raw marks/whitelist JSON objects for per-user resolution at read time
        userPreferencesDataStore.remoteMarks.set(config.marksJson.toString())
        userPreferencesDataStore.remoteWhitelistJson.set(config.whitelistJson.toString())

        // Also resolve whitelist for current logged-in user (backward compatibility for UrlHandler, ChildModeSettingsScreen)
        val currentUserId = acr.browser.lightning.dialog.LoginSession.getUserId(application) ?: ""
        val userWhitelist = RemoteConfig.getWhitelistForUser(config.whitelistJson, currentUserId)
        userPreferencesDataStore.remoteWhitelistOpen.set(userWhitelist.open)
        userPreferencesDataStore.childModeEnabled.set(userWhitelist.open)
        val urlsStr = userWhitelist.urls.joinToString(",")
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

        // Save remote marks (homepage quick links) - raw JSON object now saved above

        // Invalidate cached homepage so it regenerates with fresh marks
        val homepageFile = File(File(application.filesDir, "generated-html"), "homepage.html")
        if (homepageFile.exists()) {
            homepageFile.delete()
            logger.log(TAG, "Cached homepage deleted, will regenerate on next tab open")
        }

        // Check for app update
        appUpdateManager.checkForUpdate(config.app)

        // Log app config
        val appConfig = config.app
        if (appConfig != null) {
            logger.log(TAG, "  app: version=${appConfig.version}, url=${appConfig.url}")
        } else {
            logger.log(TAG, "  app: null")
        }

        // Log all config
        logger.log(TAG, "Config synced | version=${config.version}, env=${config.env}, timestamp=${config.timestamp}")
        logger.log(TAG, "  whitelist: open=${userWhitelist.open}, urls=${userWhitelist.urls.size}")
        logger.log(TAG, "  admin: pin=${if (remotePin.isNotEmpty()) "***" else "(empty)"}")
        logger.log(TAG, "  marks: rawJson keys=${config.marksJson.keys().asSequence().toList()}")
    }

    companion object {
        private const val TAG = "ConfigSyncService"
        private const val VERSION_URL = "https://leo-zhao.natapp4.cc/api/browser/version"
        private const val CONFIG_URL = "https://leo-zhao.natapp4.cc/api/browser/config"
        private const val POLL_INTERVAL_MS = 5000L
    }
}
