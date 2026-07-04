package acr.browser.lightning.utils

import android.content.Context
import java.io.IOException

/**
 * Utility class for reading files from assets directory.
 * Replaces Mezzanine plugin functionality.
 */
object AssetReader {

    /**
     * Read HTML file from assets
     */
    fun readHtml(context: Context, fileName: String): String {
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * Read JS file from assets
     */
    fun readJs(context: Context, fileName: String): String {
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * Read homepage HTML
     */
    fun getHomepageHtml(context: Context): String {
        return readHtml(context, "homepage.html")
    }

    /**
     * Read bookmarks HTML
     */
    fun getBookmarksHtml(context: Context): String {
        return readHtml(context, "bookmarks.html")
    }

    /**
     * Read list page HTML
     */
    fun getListPageHtml(context: Context): String {
        return readHtml(context, "list.html")
    }

    /**
     * Read InvertPage JS
     */
    fun getInvertPageJs(context: Context): String {
        return readJs(context, "InvertPage.js")
    }

    /**
     * Read TextReflow JS
     */
    fun getTextReflowJs(context: Context): String {
        return readJs(context, "TextReflow.js")
    }

    /**
     * Read ThemeColor JS
     */
    fun getThemeColorJs(context: Context): String {
        return readJs(context, "ThemeColor.js")
    }
}
