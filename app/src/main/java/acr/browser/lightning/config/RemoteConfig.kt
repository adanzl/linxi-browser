package acr.browser.lightning.config

import org.json.JSONArray
import org.json.JSONObject

data class RemoteConfig(
    val version: String = "",
    val timestamp: String = "",
    val env: String = "",
    val app: AppConfig? = null,
    val admin: AdminConfig? = null,
    val whitelist: WhitelistConfig? = null,
) {
    companion object {
        fun fromJson(jsonString: String): RemoteConfig? = try {
            val json = JSONObject(jsonString)
            RemoteConfig(
                version = json.optString("version", ""),
                timestamp = json.optString("timestamp", ""),
                env = json.optString("env", ""),
                app = json.optJSONObject("app")?.let { app ->
                    AppConfig(
                        version = app.optString("version", ""),
                        url = app.optString("url", ""),
                    )
                },
                admin = json.optJSONObject("admin")?.let { admin ->
                    AdminConfig(pin = admin.optString("pin", ""))
                },
                whitelist = json.optJSONObject("whitelist")?.let { wl ->
                    WhitelistConfig(
                        open = wl.optString("open", "false") == "true" || wl.optBoolean("open", false),
                        urls = parseJsonArray(wl.optJSONArray("urls")),
                    )
                },
            )
        } catch (e: Exception) {
            null
        }

        private fun parseJsonArray(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
        }
    }
}

data class AppConfig(
    val version: String = "",
    val url: String = "",
)

data class AdminConfig(
    val pin: String = "",
)

data class WhitelistConfig(
    val open: Boolean = false,
    val urls: List<String> = emptyList(),
)
