package acr.browser.lightning.dialog

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.json.JSONObject
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

    /** MyTodo 网页根地址（公网 / 局域网），与原生登录选用的 API 基址无关。 */
    const val LOCAL_WEB_ROOT = "http://192.168.50.172:8848"
    const val REMOTE_WEB_ROOT = "https://leo-zhao.natapp4.cc"
    const val LOCAL_API_BASE = "$LOCAL_WEB_ROOT/api"
    const val REMOTE_API_BASE = "$REMOTE_WEB_ROOT/api"

    private val WEB_APP_ROOTS = listOf(LOCAL_WEB_ROOT, REMOTE_WEB_ROOT)

    private const val PREFS_NAME = "login_session"
    private const val COOKIE_PREFS_NAME = "login_cookies"
    private const val KEY_USERNAME = "username"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_API_BASE = "api_base"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "access_token_expires_at"
    private const val KEY_PENDING_NATIVE_WEB_OVERRIDE = "pending_native_web_override"
    private const val KEY_PENDING_NATIVE_WEB_OVERRIDE_REVISION = "pending_native_web_override_revision"
    private const val KEY_PENDING_NATIVE_WEB_OVERRIDE_AT = "pending_native_web_override_at"

    /** 原生登录后允许强制覆盖网页会话的时间窗口。 */
    private const val PENDING_NATIVE_WEB_OVERRIDE_TTL_MS = 5 * 60 * 1000L

    /** WebView localStorage keys — prefixed to avoid colliding with third-party sites. */
    const val LS_SAVE_USER = "lx_saveUser"
    const val LS_ACCESS_TOKEN = "lx_access_token"
    const val LS_REFRESH_TOKEN = "lx_refresh_token"
    const val LS_EXPIRES_AT = "lx_access_token_expires_at"
    const val LS_BAUTH = "lx_bAuth"
    const val LS_SESSION_REVISION = "lx_session_revision"
    /** 网页主动注销标记；存在时不应自动注入原生会话。 */
    const val LS_WEB_LOGOUT_SIGNAL = "lx_web_logout_signal"

    private const val KEY_SESSION_REVISION = "session_revision"
    private const val KEY_WEB_USER_SEEN_ORIGINS = "web_user_seen_origins"

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
        saveTokensWithExpiresAt(context, accessToken, refreshToken, expiresAt)
    }

    /** 写入 token 与绝对过期时间（毫秒），用于从网页会话同步续期结果。 */
    fun saveTokensWithExpiresAt(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresAtMillis: Long,
    ) {
        prefs(context).edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_EXPIRES_AT, expiresAtMillis.toString())
            if (refreshToken != null) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            } else {
                remove(KEY_REFRESH_TOKEN)
            }
            apply()
        }
    }

    data class WebSessionSnapshot(
        val userId: String?,
        val accessToken: String?,
        val refreshToken: String?,
        val expiresAtMillis: Long,
        val bAuth: Boolean,
        val sessionRevision: String?,
    ) {
        fun hasLoggedInUser(): Boolean =
            !userId.isNullOrEmpty() && !accessToken.isNullOrEmpty()
    }

    /** 是否为本项目 MyTodo 网页（公网或局域网均可接收 token 同步）。 */
    fun isOurWebAppUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return WEB_APP_ROOTS.any { root -> url.startsWith(root) }
    }

    /** 公网 / 局域网各自独立的 MyTodo 来源键（localStorage 按 origin 隔离）。 */
    fun resolveWebAppOrigin(url: String?): String? =
        WEB_APP_ROOTS.firstOrNull { url?.startsWith(it) == true }

    fun markWebUserSeenOnOrigin(context: Context, origin: String) {
        val set = getWebUserSeenOrigins(context).toMutableSet()
        set.add(origin)
        prefs(context).edit().putStringSet(KEY_WEB_USER_SEEN_ORIGINS, set).apply()
    }

    fun hasWebUserSeenOnOrigin(context: Context, origin: String): Boolean =
        getWebUserSeenOrigins(context).contains(origin)

    private fun getWebUserSeenOrigins(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WEB_USER_SEEN_ORIGINS, emptySet()) ?: emptySet()

    private fun clearWebUserSeenOrigins(context: Context) {
        prefs(context).edit().remove(KEY_WEB_USER_SEEN_ORIGINS).apply()
    }

    /**
     * 原生登录成功后尚未把会话注入 MyTodo 网页时为 true。
     *
     * 该标记会绑定当时的 session_revision 并带过期时间：
     * - 用户切换账号 / 退出后 revision 变化，旧标记自动失效；
     * - App 在注入前被杀，重启后超过窗口期也不再强制覆盖退出标记。
     */
    fun setPendingNativeWebOverride(context: Context, pending: Boolean) {
        val editor = prefs(context).edit()
        if (pending) {
            editor.putBoolean(KEY_PENDING_NATIVE_WEB_OVERRIDE, true)
            editor.putString(KEY_PENDING_NATIVE_WEB_OVERRIDE_REVISION, ensureSessionRevision(context))
            editor.putLong(KEY_PENDING_NATIVE_WEB_OVERRIDE_AT, System.currentTimeMillis())
        } else {
            editor.remove(KEY_PENDING_NATIVE_WEB_OVERRIDE)
            editor.remove(KEY_PENDING_NATIVE_WEB_OVERRIDE_REVISION)
            editor.remove(KEY_PENDING_NATIVE_WEB_OVERRIDE_AT)
        }
        editor.apply()
    }

    /**
     * 仅当 pending 标记仍然对应“当前”原生会话且未超时才算有效。
     * 过期或 revision 不匹配时立即清除，避免残留标记绕过退出标记。
     */
    fun isPendingNativeWebOverride(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_PENDING_NATIVE_WEB_OVERRIDE, false)) return false
        val pendingRevision = p.getString(KEY_PENDING_NATIVE_WEB_OVERRIDE_REVISION, null)
        val issuedAt = p.getLong(KEY_PENDING_NATIVE_WEB_OVERRIDE_AT, 0L)
        val expired = issuedAt <= 0L ||
            System.currentTimeMillis() - issuedAt > PENDING_NATIVE_WEB_OVERRIDE_TTL_MS
        val revisionMatches = pendingRevision != null &&
            pendingRevision == getSessionRevision(context)
        val valid = !expired && revisionMatches && hasSavedUser(context)
        if (!valid) {
            setPendingNativeWebOverride(context, false)
        }
        return valid
    }

    /** 一次读取网页 localStorage 中的完整登录会话。 */
    fun buildReadWebSessionJs(): String = buildString {
        append("(function(){var o={};")
        listOf(
            LS_SAVE_USER,
            LS_ACCESS_TOKEN,
            LS_REFRESH_TOKEN,
            LS_EXPIRES_AT,
            LS_BAUTH,
            LS_SESSION_REVISION,
            LS_WEB_LOGOUT_SIGNAL,
        ).forEach { key ->
            append("o['$key']=localStorage.getItem('$key');")
        }
        append("return JSON.stringify(o);})()")
    }

    /**
     * 网页切换账号或网页会话为准时，整组替换原生凭证（清除旧 token）。
     */
    fun replaceSessionFromWeb(context: Context, snapshot: WebSessionSnapshot) {
        val userId = snapshot.userId ?: return
        val accessToken = snapshot.accessToken ?: return
        prefs(context).edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_EXPIRES_AT, snapshot.expiresAtMillis.toString())
            if (snapshot.refreshToken != null) {
                putString(KEY_REFRESH_TOKEN, snapshot.refreshToken)
            } else {
                remove(KEY_REFRESH_TOKEN)
            }
            snapshot.sessionRevision?.let { putString(KEY_SESSION_REVISION, it) }
            apply()
        }
    }

    /** 同一用户下仅同步 token / 过期时间（网页续期后写回原生）。 */
    fun syncTokensFromWeb(context: Context, snapshot: WebSessionSnapshot): Boolean {
        val userId = snapshot.userId ?: return false
        if (userId != getUserId(context)) return false
        val accessToken = snapshot.accessToken ?: return false
        saveTokensWithExpiresAt(
            context,
            accessToken,
            snapshot.refreshToken,
            snapshot.expiresAtMillis,
        )
        snapshot.sessionRevision?.let { rev ->
            prefs(context).edit().putString(KEY_SESSION_REVISION, rev).apply()
        }
        return true
    }

    fun webTokensDifferFromNative(context: Context, snapshot: WebSessionSnapshot): Boolean {
        val accessToken = snapshot.accessToken ?: return false
        if (accessToken != getAccessToken(context)) return true
        if (snapshot.expiresAtMillis != getTokenExpiresAt(context)) return true
        if (snapshot.refreshToken != getRefreshToken(context)) return true
        val webRevision = snapshot.sessionRevision
        if (!webRevision.isNullOrEmpty() && webRevision != getSessionRevision(context)) return true
        return false
    }

    fun getSessionRevision(context: Context): String? =
        prefs(context).getString(KEY_SESSION_REVISION, null)

    /** 原生重新登录后生成新会话版本，与网页 lx_session_revision 对齐。 */
    fun bumpSessionRevision(context: Context): String {
        val revision = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
        prefs(context).edit().putString(KEY_SESSION_REVISION, revision).apply()
        return revision
    }

    fun getAccessToken(context: Context): String? =
        prefs(context).getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_REFRESH_TOKEN, null)

    fun getTokenExpiresAt(context: Context): Long =
        prefs(context).getString(KEY_EXPIRES_AT, null)?.toLongOrNull() ?: 0L

    data class InjectExpectation(
        val userId: String,
        val accessToken: String,
        val expiresAtMillis: Long,
        val refreshToken: String?,
        val sessionRevision: String,
    )

    /** 旧版本无 session_revision 时，为已有原生会话补写版本号。 */
    fun ensureSessionRevision(context: Context): String =
        getSessionRevision(context) ?: bumpSessionRevision(context)

    fun captureInjectExpectation(context: Context): InjectExpectation? {
        val userId = getUserId(context) ?: return null
        val accessToken = getAccessToken(context) ?: return null
        val revision = ensureSessionRevision(context)
        return InjectExpectation(
            userId = userId,
            accessToken = accessToken,
            expiresAtMillis = getTokenExpiresAt(context),
            refreshToken = getRefreshToken(context),
            sessionRevision = revision,
        )
    }

    data class AtomicInjectOptions(
        /** 仅用户主动原生登录 / pending 同步时为 true，可覆盖退出标记。 */
        val forceOverride: Boolean,
        /** 自动推送原生 token 时：注入前网页会话须仍与该快照一致。 */
        val webGuard: WebSessionSnapshot? = null,
    )

    /**
     * 单次 JS：检查退出标记与现有 token → 条件写入 → 读回完整凭证。
     */
    fun buildAtomicSessionInjectJs(context: Context, options: AtomicInjectOptions): String? {
        val exp = captureInjectExpectation(context) ?: return null
        val expJson = JSONObject().apply {
            put("u", exp.userId)
            put("a", exp.accessToken)
            put("e", exp.expiresAtMillis.toString())
            put("r", exp.sessionRevision)
            if (exp.refreshToken != null) {
                put("rt", exp.refreshToken)
            }
        }
        val guard = options.webGuard
        val guardJson = guard?.let { g ->
            JSONObject().apply {
                put("u", g.userId)
                put("a", g.accessToken)
                put("e", g.expiresAtMillis.toString())
                // rt 缺省表示“读取时网页没有 refresh token”；JS 侧 gn() 会把
                // 缺省 / 空串 / 字面量 "null" 都归一为 null 后比较。
                g.refreshToken?.let { put("rt", it) }
                g.sessionRevision?.let { put("r", it) }
            }
        }
        return buildString {
            append("(function(){")
            append("var force=").append(options.forceOverride).append(";")
            append("var exp=").append(expJson.toString()).append(";")
            if (guardJson != null) {
                append("var guard=").append(guardJson.toString()).append(";")
            }
            append("if(!force&&localStorage.getItem('$LS_WEB_LOGOUT_SIGNAL')==='1'){")
            append("return JSON.stringify({ok:false,reason:'logout'});}")
            if (guardJson != null) {
                // 归一化规则与读取端 WebSessionSnapshot 的 opt() 保持一致：
                // 缺省 / 空串 / 字面量 "null" 一律视为 null。
                append("var gn=function(v){return(v==null||v===''||v==='null')?null:v;};")
                append("if(!force){")
                append("if(gn(localStorage.getItem('$LS_SAVE_USER'))!==gn(guard.u)")
                append("||gn(localStorage.getItem('$LS_ACCESS_TOKEN'))!==gn(guard.a)")
                append("||gn(localStorage.getItem('$LS_EXPIRES_AT'))!==gn(guard.e)")
                append("||gn(localStorage.getItem('$LS_REFRESH_TOKEN'))!==gn(guard.rt)){")
                append("return JSON.stringify({ok:false,reason:'session_changed'});}")
                append("if(guard.r&&gn(localStorage.getItem('$LS_SESSION_REVISION'))!==guard.r){")
                append("return JSON.stringify({ok:false,reason:'session_changed'});}")
                append("}")
            } else if (!options.forceOverride) {
                append("if(!force){var cur=localStorage.getItem('$LS_ACCESS_TOKEN');")
                append("if(cur&&cur.length>0){return JSON.stringify({ok:false,reason:'has_token'});}}")
            }
            append("['saveUser','access_token','access_token_expires_at','refresh_token','bAuth']")
            append(".forEach(function(k){localStorage.removeItem(k);});")
            append("localStorage.setItem('$LS_SAVE_USER',exp.u);")
            append("localStorage.setItem('$LS_ACCESS_TOKEN',exp.a);")
            append("localStorage.setItem('$LS_EXPIRES_AT',exp.e);")
            if (exp.refreshToken != null) {
                append("localStorage.setItem('$LS_REFRESH_TOKEN',exp.rt);")
            } else {
                append("localStorage.removeItem('$LS_REFRESH_TOKEN');")
            }
            append("localStorage.setItem('$LS_BAUTH','true');")
            append("localStorage.setItem('$LS_SESSION_REVISION',exp.r);")
            append("localStorage.removeItem('$LS_WEB_LOGOUT_SIGNAL');")
            append("var rt=localStorage.getItem('$LS_REFRESH_TOKEN');")
            append("return JSON.stringify({ok:true,")
            append("u:localStorage.getItem('$LS_SAVE_USER'),")
            append("a:localStorage.getItem('$LS_ACCESS_TOKEN'),")
            append("e:localStorage.getItem('$LS_EXPIRES_AT'),")
            append("r:localStorage.getItem('$LS_SESSION_REVISION'),")
            append("rt:rt});")
            append("})()")
        }
    }

    fun parseAtomicInjectResult(result: String?): JSONObject? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            val jsonStr = org.json.JSONTokener(result).nextValue() as String
            JSONObject(jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    fun verifyAtomicInjectResult(result: String?, expected: InjectExpectation): Boolean {
        val json = parseAtomicInjectResult(result) ?: return false
        if (!json.optBoolean("ok", false)) return false
        if (json.optString("u") != expected.userId) return false
        if (json.optString("a") != expected.accessToken) return false
        if (json.optString("e") != expected.expiresAtMillis.toString()) return false
        if (json.optString("r") != expected.sessionRevision) return false
        val rtOk = when (val expectedRt = expected.refreshToken) {
            null -> json.isNull("rt") || json.optString("rt").let { it.isEmpty() || it == "null" }
            else -> json.optString("rt") == expectedRt
        }
        return rtOk
    }

    /**
     * 原子注入并校验完整凭证；写入前/后均确认目标 URL 与原生会话版本未变。
     */
    fun performVerifiedNativeInject(
        context: Context,
        webView: android.webkit.WebView,
        pageUrl: String,
        options: AtomicInjectOptions,
        onSuccess: () -> Unit,
    ) {
        if (webView.url != pageUrl) return
        val expected = captureInjectExpectation(context) ?: return
        val js = buildAtomicSessionInjectJs(context, options) ?: return
        if (options.forceOverride) {
            setPendingNativeWebOverride(context, true)
        }
        webView.evaluateJavascript(js) { raw ->
            if (webView.url != pageUrl) return@evaluateJavascript
            if (getSessionRevision(context) != expected.sessionRevision) return@evaluateJavascript
            val parsed = parseAtomicInjectResult(raw)
            if (parsed != null && !parsed.optBoolean("ok", false)) {
                // 网页已退出或会话已变，取消强制覆盖，避免下次扫描再次绕过
                setPendingNativeWebOverride(context, false)
                return@evaluateJavascript
            }
            if (!verifyAtomicInjectResult(raw, expected)) return@evaluateJavascript
            setPendingNativeWebOverride(context, false)
            onSuccess()
        }
    }

    /**
     * Clear all saved session data (logout).
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        cookiePrefs(context).edit().clear().apply()
        clearWebUserSeenOrigins(context)
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
