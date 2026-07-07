package acr.browser.lightning.html.homepage

import acr.browser.lightning.R
import acr.browser.lightning.browser.theme.ThemeProvider
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.constant.FILE
import acr.browser.lightning.constant.UTF8
import acr.browser.lightning.config.RemoteConfig
import acr.browser.lightning.dialog.LoginSession
import acr.browser.lightning.html.HtmlPageFactory
import acr.browser.lightning.html.jsoup.andBuild
import acr.browser.lightning.html.jsoup.body
import acr.browser.lightning.html.jsoup.charset
import acr.browser.lightning.html.jsoup.id
import acr.browser.lightning.html.jsoup.parse
import acr.browser.lightning.html.jsoup.style
import acr.browser.lightning.html.jsoup.tag
import acr.browser.lightning.html.jsoup.title
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.search.SearchEngineProvider
import android.app.Application
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import javax.inject.Inject

/**
 * A factory for the home page.
 */
class HomePageFactory @Inject constructor(
    private val application: Application,
    private val searchEngineProvider: SearchEngineProvider,
    private val homePageReader: HomePageReader,
    private val themeProvider: ThemeProvider,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val userPreferencesDataStore: UserPreferencesDataStore,
) : HtmlPageFactory {

    private val title = application.getString(R.string.home)

    private fun Int.toColor(): String {
        val string = Integer.toHexString(this)

        return string.substring(2) + string.substring(0, 2)
    }

    private val backgroundColor: String
        get() = themeProvider.color(R.attr.colorPrimary).toColor()
    private val cardColor: String
        get() = themeProvider.color(R.attr.autoCompleteBackgroundColor).toColor()
    private val textColor: String
        get() = themeProvider.color(R.attr.autoCompleteTitleColor).toColor()

    override suspend fun buildPage(): String = withContext(coroutineDispatchers.io) {
        val (iconUrl, queryUrl, _) = searchEngineProvider.provideSearchEngine()
        val marksHtml = buildMarksHtml()
        val content = parse(homePageReader.provideHtml()) andBuild {
            title { title }
            style { content ->
                content.replace("--body-bg: {COLOR}", "--body-bg: #$backgroundColor;")
                    .replace("--box-bg: {COLOR}", "--box-bg: #$cardColor;")
                    .replace("--box-txt: {COLOR}", "--box-txt: #$textColor;")
            }
            charset { UTF8 }
            body {
                id("image_url") { attr("src", iconUrl) }
                tag("script") {
                    html(
                        html()
                            .replace("\${BASE_URL}", queryUrl)
                            .replace("&", "\\u0026")
                    )
                }
                if (marksHtml.isNotEmpty()) {
                    id("marks") { html(marksHtml) }
                }
            }
        }
        val page = createHomePage()
        FileWriter(page, false).use {
            it.write(content)
        }

        // Add timestamp to bust WebView cache, ensuring fresh marks are always shown
        "$FILE$page?t=${System.currentTimeMillis()}"
    }
    
    private suspend fun buildMarksHtml(): String {
        val marksJsonStr = userPreferencesDataStore.remoteMarks.get()
        if (marksJsonStr.isEmpty() || marksJsonStr == "{}") return ""
        return try {
            val marksObj = JSONObject(marksJsonStr)
            val userId = LoginSession.getUserId(application) ?: ""
            val items = RemoteConfig.getMarksForUser(marksObj, userId)
            android.util.Log.d("HomePageFactory", "marks for userId=$userId: ${items.map { "[p${it.position}]${it.title}" }}")
            val sb = StringBuilder("<div class='marks-container'>")
            for (item in items) {
                sb.append("<a class='mark-item' href='${escapeHtml(item.url)}'>")
                sb.append("<div class='mark-icon'>")
                sb.append(escapeHtml(item.title.take(1).uppercase()))
                sb.append("</div><span>${escapeHtml(item.title)}</span></a>")
            }
            sb.append("</div>")
            sb.toString()
        } catch (e: Exception) {
            android.util.Log.e("HomePageFactory", "buildMarksHtml error", e)
            ""
        }
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
    }

    /**
     * Create the home page file.
     */
    fun createHomePage(): File {
        val generatedHtml = File(application.filesDir, "generated-html")
        generatedHtml.mkdirs()
        return File(generatedHtml, FILENAME)
    }

    companion object {

        const val FILENAME = "homepage.html"

    }

}
