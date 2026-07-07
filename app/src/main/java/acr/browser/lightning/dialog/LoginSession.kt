package acr.browser.lightning.dialog

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import java.util.concurrent.TimeUnit

/**
 * Manages login session persistence via SharedPreferences.
 * Mimics MyTodo's localStorage approach: save username after login,
 * check on next startup to auto-login.
 *
 * Also provides a PersistentCookieJar to persist server-side session cookies
 * across app restarts (the web app relies on browser cookies for auth).
 */
object LoginSession {

    private const val PREFS_NAME = "login_session"
    private const val COOKIE_PREFS_NAME = "login_cookies"
    private const val KEY_USERNAME = "username"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_API_BASE = "api_base"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "access_token_expires_at"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun cookiePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(COOKIE_PREFS_NAME, Context.MODE_PRIVATE)

    // ===== User session save/load/clear =====

    /**
     * Save login info after successful login (like MyTodo's localStorage.setItem("saveUser", id)).
     */
    fun save(context: Context, username: String, userId: String, apiBase: String) {
        prefs(context).edit().apply {
            putString(KEY_USERNAME, username)
            putString(KEY_USER_ID, userId)
            putString(KEY_API_BASE, apiBase)
            apply()
        }
    }

    /**
     * Save auth tokens from login response (mimics MyTodo's localStorage auth keys).
     */
    fun saveTokens(context: Context, accessToken: String, refreshToken: String?, expiresIn: Long) {
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000
        prefs(context).edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_EXPIRES_AT, expiresAt.toString())
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            apply()
        }
    }

    fun getAccessToken(context: Context): String? =
        prefs(context).getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_REFRESH_TOKEN, null)

    fun getTokenExpiresAt(context: Context): Long =
        prefs(context).getString(KEY_EXPIRES_AT, null)?.toLongOrNull() ?: 0L

    /**
     * Build JS to inject auth tokens into WebView localStorage.
     * Mimics what MyTodo's auth-util.ts does after login.
     */
    fun buildLocalStorageInjection(context: Context): String? {
        val userId = getUserId(context) ?: return null
        val accessToken = getAccessToken(context) ?: return null
        val expiresAt = getTokenExpiresAt(context)
        val refreshToken = getRefreshToken(context)
        return buildString {
            append("localStorage.setItem('saveUser','$userId');")
            append("localStorage.setItem('access_token','$accessToken');")
            append("localStorage.setItem('access_token_expires_at','$expiresAt');")
            if (refreshToken != null) {
                append("localStorage.setItem('refresh_token','$refreshToken');")
            }
            append("localStorage.setItem('bAuth','true');")
        }
    }

    /**
     * Clear all saved session data (logout).
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        cookiePrefs(context).edit().clear().apply()
    }

    /**
     * Check if there is a saved user (like MyTodo's localStorage.getItem("saveUser")).
     */
    fun hasSavedUser(context: Context): Boolean {
        val name = prefs(context).getString(KEY_USERNAME, null)
        return !name.isNullOrEmpty()
    }

    fun getUsername(context: Context): String? =
        prefs(context).getString(KEY_USERNAME, null)

    fun getUserId(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)

    fun getApiBase(context: Context): String? =
        prefs(context).getString(KEY_API_BASE, null)

    // ===== Persistent Cookie Jar =====

    /**
     * OkHttp CookieJar that persists cookies to SharedPreferences,
     * so server-side sessions survive app restarts (like browser cookies).
     */
    class PersistentCookieJar(context: Context) : okhttp3.CookieJar {

        private val store = context.getSharedPreferences(COOKIE_PREFS_NAME, Context.MODE_PRIVATE)

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val editor = store.edit()
            for (cookie in cookies) {
                val key = cookieKey(url, cookie)
                editor.putString(key, "${cookie.name}=${cookie.value};path=${cookie.path};domain=${cookie.domain};expiresAt=${cookie.expiresAt};secure=${cookie.secure};httpOnly=${cookie.httpOnly}")
            }
            editor.apply()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookies = mutableListOf<Cookie>()
            val now = System.currentTimeMillis()
            for (key in store.all.keys) {
                val raw = store.getString(key, null) ?: continue
                val cookie = parseCookie(raw, now) ?: continue
                if (url.host.endsWith(cookie.domain, ignoreCase = true)) {
                    cookies.add(cookie)
                }
            }
            return cookies
        }

        private fun cookieKey(url: HttpUrl, cookie: Cookie): String =
            "${cookie.name}@${cookie.domain}${cookie.path}"

        private fun parseCookie(raw: String, now: Long): Cookie? {
            // Format: name=value;path=...;domain=...;expiresAt=...;secure=...;httpOnly=...
            val parts = raw.split(";")
            if (parts.isEmpty()) return null
            val nameValue = parts[0].split("=", limit = 2)
            if (nameValue.size < 2) return null
            val name = nameValue[0]
            val value = nameValue[1]
            var path = "/"
            var domain = ""
            var expiresAt = Long.MAX_VALUE
            var secure = false
            var httpOnly = false
            for (i in 1 until parts.size) {
                val kv = parts[i].split("=", limit = 2)
                if (kv.size < 2) continue
                when (kv[0].trim()) {
                    "path" -> path = kv[1]
                    "domain" -> domain = kv[1]
                    "expiresAt" -> expiresAt = kv[1].toLongOrNull() ?: Long.MAX_VALUE
                    "secure" -> secure = kv[1].toBooleanStrictOrNull() ?: false
                    "httpOnly" -> httpOnly = kv[1].toBooleanStrictOrNull() ?: false
                }
            }
            if (domain.isEmpty()) return null
            if (expiresAt < now) return null // expired
            return Cookie.Builder()
                .name(name)
                .value(value)
                .path(path)
                .domain(domain)
                .expiresAt(expiresAt)
                .apply {
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                }
                .build()
        }
    }
}
