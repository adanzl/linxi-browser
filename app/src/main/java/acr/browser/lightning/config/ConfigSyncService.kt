package acr.browser.lightning.config

import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.log.Logger
import acr.browser.lightning.preference.UserPreferencesDataStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigSyncService @Inject constructor(
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
                response.body?.string()
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
        userPreferencesDataStore.remoteWhitelistOpen.set(config.whitelist?.open ?: false)
        val urlsStr = config.whitelist?.urls?.joinToString(",") ?: ""
        userPreferencesDataStore.remoteWhitelistUrls.set(urlsStr)

        // If remote pin is set, also update local child mode pin (MD5 hash stored as-is)
        if (config.admin?.pin?.isNotEmpty() == true) {
            // Only update if no local pin exists yet, or force update
            val localPin = userPreferencesDataStore.childModePin.get()
            if (localPin.isEmpty()) {
                userPreferencesDataStore.childModePin.set(config.admin.pin)
            }
        }

        logger.log(TAG, "Config synced: version=${config.version}, whitelist open=${config.whitelist?.open}")
    }

    companion object {
        private const val TAG = "ConfigSyncService"
        private const val VERSION_URL = "https://leo-zhao.natapp4.cc/api/browser/version"
        private const val CONFIG_URL = "https://leo-zhao.natapp4.cc/api/browser/config"
        private const val POLL_INTERVAL_MS = 5000L
    }
}
