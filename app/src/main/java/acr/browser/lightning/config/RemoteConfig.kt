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
    val marks: List<MarkItem> = emptyList(),
) {
    companion object {
        fun fromJson(jsonString: String): RemoteConfig? = try {
            val root = JSONObject(jsonString)
            // Handle API response wrapper {code, data, msg}
            val json = if (root.has("code") && root.has("data")) {
                root.optJSONObject("data")
            } else {
                root
            } ?: return null
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
                marks = parseMarksArray(json.optJSONArray("marks")),
            )
        } catch (e: Exception) {
            null
        }

        private fun parseJsonArray(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
        }

        private fun parseMarksArray(arr: JSONArray?): List<MarkItem> {
            if (arr == null) return emptyList()
            val result = mutableListOf<MarkItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title", "")
                val url = obj.optString("url", "")
                val position = obj.optInt("position", 0)
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    result.add(MarkItem(title = title, url = url, position = position))
                }
            }
            return result.sortedBy { it.position }
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

data class MarkItem(
    val title: String,
    val url: String,
    val position: Int = 0,
)
