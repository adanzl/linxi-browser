package acr.browser.lightning.browser.password

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages saved passwords for web forms.
 * Stores credentials per domain in SharedPreferences.
 */
object PasswordManager {

    private const val PREFS_NAME = "saved_passwords"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class SavedPassword(
        val domain: String,
        val username: String,
        val password: String
    )

    /**
     * Save a password entry for a given domain.
     */
    fun savePassword(context: Context, domain: String, username: String, password: String) {
        val all = getAllPasswords(context).toMutableList()
        // Remove existing entry for same domain+username
        all.removeAll { it.domain == domain && it.username == username }
        all.add(SavedPassword(domain, username, password))
        persistAll(context, all)
    }

    /**
     * Get saved password for a domain. Returns the most recent one.
     */
    fun getPassword(context: Context, domain: String): SavedPassword? {
        return getAllPasswords(context).lastOrNull { it.domain == domain }
    }

    /**
     * Get all saved passwords.
     */
    fun getAllPasswords(context: Context): List<SavedPassword> {
        val json = prefs(context).getString("passwords", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SavedPassword(
                    domain = obj.getString("domain"),
                    username = obj.getString("username"),
                    password = obj.getString("password")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Delete a saved password.
     */
    fun deletePassword(context: Context, domain: String, username: String) {
        val all = getAllPasswords(context).toMutableList()
        all.removeAll { it.domain == domain && it.username == username }
        persistAll(context, all)
    }

    /**
     * Check if a password is already saved for this domain+username.
     */
    fun hasPassword(context: Context, domain: String, username: String): Boolean {
        return getAllPasswords(context).any { it.domain == domain && it.username == username }
    }

    private fun persistAll(context: Context, passwords: List<SavedPassword>) {
        val array = JSONArray()
        for (p in passwords) {
            array.put(JSONObject().apply {
                put("domain", p.domain)
                put("username", p.username)
                put("password", p.password)
            })
        }
        prefs(context).edit().putString("passwords", array.toString()).apply()
    }
}
