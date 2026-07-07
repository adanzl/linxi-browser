@file:Suppress("DEPRECATION")
package acr.browser.lightning.browser.tab

import acr.browser.lightning.BuildConfig
import acr.browser.lightning.R
import acr.browser.lightning.browser.di.IncognitoMode
import acr.browser.lightning.constant.FILE
import acr.browser.lightning.extensions.snackbar
import acr.browser.lightning.log.Logger
import acr.browser.lightning.config.RemoteConfig
import acr.browser.lightning.dialog.LoginSession
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.preference.datastore.getUnsafe
import acr.browser.lightning.utils.IntentUtils
import acr.browser.lightning.utils.Utils
import acr.browser.lightning.utils.isSpecialUrl
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.MailTo
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File
import java.net.URISyntaxException
import javax.inject.Inject

/**
 * Extract only the domain from a URL.
 * Strips protocol, path, query, and port.
 */
private fun extractDomain(url: String): String {
    var s = url.trim().lowercase()
    s = s.removePrefix("https://").removePrefix("http://")
    val slashIndex = s.indexOf('/')
    if (slashIndex > 0) s = s.substring(0, slashIndex)
    val lastColonIndex = s.lastIndexOf(':')
    if (lastColonIndex > 0 && s.substring(lastColonIndex + 1).all { it.isDigit() }) {
        s = s.substring(0, lastColonIndex)
    }
    s = s.removePrefix("www.")
    return s.trimEnd('.')
}

/**
 * Handle URLs loaded by the [WebView] and determine if they should be loaded by the browser or
 * another app.
 */
class UrlHandler @Inject constructor(
    private val activity: Activity,
    private val logger: Logger,
    private val intentUtils: IntentUtils,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    @IncognitoMode private val incognitoMode: Boolean
) {

    /**
     * Return true if the [url] should be loaded by another app or in another way, false if the
     * browser can let the [view] continue loading as it wants.
     */
    fun shouldOverrideLoading(
        view: WebView,
        url: String,
        headers: Map<String, String>
    ): Boolean {
        // Child mode whitelist check
        if (shouldBlockForChildMode(url)) {
            view.stopLoading()
            activity.snackbar(R.string.child_mode_blocked_message)
            return true
        }
        if (incognitoMode) {
            // If we are in incognito, immediately load, we don't want the url to leave the app
            return continueLoadingUrl(view, url, headers)
        }
        if (URLUtil.isAboutUrl(url)) {
            // If this is an about page, immediately load, we don't need to leave the app
            return continueLoadingUrl(view, url, headers)
        }

        return if (isMailOrIntent(url, view) || intentUtils.startActivityForUrl(view, url)) {
            // If it was a mailto: link, or an intent, or could be launched elsewhere, do that
            true
        } else {
            // If none of the special conditions was met, continue with loading the url
            continueLoadingUrl(view, url, headers)
        }
    }

    private fun continueLoadingUrl(
        webView: WebView,
        url: String,
        headers: Map<String, String>
    ): Boolean {
        if (!URLUtil.isNetworkUrl(url)
            && !URLUtil.isFileUrl(url)
            && !URLUtil.isAboutUrl(url)
            && !URLUtil.isDataUrl(url)
            && !URLUtil.isJavaScriptUrl(url)
        ) {
            webView.stopLoading()
            return true
        }
        return when {
            headers.isEmpty() -> false
            else -> {
                webView.loadUrl(url, headers)
                true
            }
        }
    }

    private fun isMailOrIntent(url: String, view: WebView): Boolean {
        if (url.startsWith("mailto:")) {
            val mailTo = MailTo.parse(url)
            val i = Utils.newEmailIntent(mailTo.to, mailTo.subject, mailTo.body, mailTo.cc)
            activity.startActivity(i)
            view.reload()
            return true
        } else if (url.startsWith("intent://")) {
            val intent = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (ignored: URISyntaxException) {
                null
            }

            if (intent != null) {
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                try {
                    activity.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    logger.log(TAG, "ActivityNotFoundException")
                }

                return true
            }
        } else if (URLUtil.isFileUrl(url) && !url.isSpecialUrl()) {
            val file = File(url.replace(FILE, ""))

            if (file.exists()) {
                val newMimeType = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(Utils.guessFileExtension(file.toString()))

                val intent = Intent(Intent.ACTION_VIEW)
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val contentUri = FileProvider.getUriForFile(
                    activity,
                    BuildConfig.APPLICATION_ID + ".fileprovider",
                    file
                )
                intent.setDataAndType(contentUri, newMimeType)

                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    println("LightningWebClient: cannot open downloaded file")
                }

            } else {
                activity.snackbar(R.string.message_open_download_fail)
            }
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "UrlHandler"
    }

    private fun shouldBlockForChildMode(url: String): Boolean {
        // Try per-user whitelist resolution first
        val whitelistJsonStr = userPreferencesDataStore.remoteWhitelistJson.getUnsafe()
        val userId = LoginSession.getUserId(activity) ?: ""
        val whitelistConfig = if (whitelistJsonStr.isNotEmpty() && whitelistJsonStr != "{}") {
            try {
                val whitelistJson = org.json.JSONObject(whitelistJsonStr)
                RemoteConfig.getWhitelistForUser(whitelistJson, userId)
            } catch (_: Exception) {
                null
            }
        } else null

        val remoteOpen = whitelistConfig?.open ?: userPreferencesDataStore.remoteWhitelistOpen.getUnsafe()
        val localEnabled = userPreferencesDataStore.childModeEnabled.getUnsafe()
        if (!localEnabled && !remoteOpen) return false

        val localWhitelist = userPreferencesDataStore.childModeWhitelist.getUnsafe()
        val remoteWhitelistUrls = whitelistConfig?.urls?.joinToString(",")
            ?: userPreferencesDataStore.remoteWhitelistUrls.getUnsafe()

        // Merge local and remote whitelist
        val combinedList = listOfNotNull(
            localWhitelist.takeIf { it.isNotEmpty() },
            remoteWhitelistUrls.takeIf { it.isNotEmpty() }
        ).flatMap { it.split(",") }
        val whitelistDomains = combinedList.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        if (whitelistDomains.isEmpty()) return false

        // Allow about: and data: and javascript: URLs
        if (!URLUtil.isNetworkUrl(url)) return false
        val domain = extractDomain(url)
        if (domain.isEmpty()) return false

        return !whitelistDomains.contains(domain)
    }
}
