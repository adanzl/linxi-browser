package acr.browser.lightning.config

import org.json.JSONArray
import org.json.JSONObject

data class RemoteConfig(
    val version: String = "",
    val timestamp: String = "",
    val env: String = "",
    val app: AppConfig? = null,
    val admin: AdminConfig? = null,
    /** Raw marks JSON object, keyed by userId. Saved as-is for per-user resolution. */
    val marksJson: JSONObject = JSONObject(),
    /** Raw whitelist JSON object, keyed by userId. Saved as-is for per-user resolution. */
    val whitelistJson: JSONObject = JSONObject(),
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
                marksJson = json.optJSONObject("marks") ?: JSONObject(),
                whitelistJson = json.optJSONObject("whitelist") ?: JSONObject(),
            )
        } catch (e: Exception) {
            null
        }

        /**
         * Extract marks for a specific user from the raw marks JSON.
         * If the userId doesn't exist as a key, return empty list.
         *
         * Format:
         * {
         *   "3": { "position": 1, "title": "X", "url": "..." },   ← single mark (object)
         *   "4": [{ "position": 1, "title": "Y", "url": "..." }]  ← multiple marks (array)
         * }
         */
        fun getMarksForUser(marksJson: JSONObject, userId: String): List<MarkItem> {
            if (userId.isEmpty() || !marksJson.has(userId)) return emptyList()
            val result = mutableListOf<MarkItem>()
            when (val value = marksJson.get(userId)) {
                is JSONObject -> {
                    val mark = parseMarkItem(value)
                    if (mark != null) result.add(mark)
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val obj = value.optJSONObject(i) ?: continue
                        val mark = parseMarkItem(obj)
                        if (mark != null) result.add(mark)
                    }
                }
            }
            return result.sortedBy { it.position }
        }

        /**
         * Extract whitelist config for a specific user from the raw whitelist JSON.
         * If the userId doesn't exist as a key, return empty config (open=false, no urls).
         *
         * Format:
         * {
         *   "3": { "open": "false", "urls": [...] },   ← user 3's config
         *   "4": { "open": "true", "urls": [...] }     ← user 4's config
         * }
         */
        fun getWhitelistForUser(whitelistJson: JSONObject, userId: String): WhitelistConfig {
            if (userId.isEmpty() || !whitelistJson.has(userId)) {
                return WhitelistConfig(open = false, urls = emptyList())
            }
            val userObj = whitelistJson.optJSONObject(userId)
                ?: return WhitelistConfig(open = false, urls = emptyList())
            return WhitelistConfig(
                open = userObj.optString("open", "false") == "true" || userObj.optBoolean("open", false),
                urls = parseStringArray(userObj.optJSONArray("urls")),
            )
        }

        private fun parseMarkItem(obj: JSONObject): MarkItem? {
            val title = obj.optString("title", "")
            val url = obj.optString("url", "")
            val position = obj.optInt("position", 0)
            return if (title.isNotEmpty() && url.isNotEmpty()) {
                MarkItem(title = title, url = url, position = position)
            } else null
        }

        private fun parseStringArray(arr: JSONArray?): List<String> {
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

data class MarkItem(
    val title: String,
    val url: String,
    val position: Int = 0,
)
