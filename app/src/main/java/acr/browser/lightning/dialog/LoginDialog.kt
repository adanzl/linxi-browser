package acr.browser.lightning.dialog

import acr.browser.lightning.R
import android.app.Dialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Non-dismissible login dialog shown on browser startup.
 * Mimics the MyTodo login card: user selection + password + login button.
 * Uses PersistentCookieJar to save server-side session cookies,
 * and LoginSession to save the selected username locally.
 */
class LoginDialog : DialogFragment() {

    interface LoginCallback {
        fun onLoginSuccess(username: String)
    }

    private var callback: LoginCallback? = null
    private var selectedUserName: String = ""
    private var userList: List<UserItem> = emptyList()
    private var resolvedApiBase: String = REMOTE_API_BASE

    data class UserItem(val id: Int, val name: String, val icon: String = "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = View.inflate(ctx, R.layout.dialog_login, null)
        val spinner = view.findViewById<Spinner>(R.id.login_user_spinner)
        val avatarView = view.findViewById<ImageView>(R.id.login_user_avatar)
        val passwordEdit = view.findViewById<EditText>(R.id.login_password_edittext)
        val errorText = view.findViewById<TextView>(R.id.login_error_text)
        val loginButton = view.findViewById<Button>(R.id.login_button)
        val progress = view.findViewById<ProgressBar>(R.id.login_progress)

        // Resolve server address: try local first, fallback to remote; then load user list
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            resolvedApiBase = resolveApiBase()
            try {
                val users = fetchUserList()
                userList = users
                val names = users.map { it.name }
                val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                if (users.isNotEmpty()) {
                    selectedUserName = users[0].name
                    loadAvatar(avatarView, users[0].icon)
                }
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                        if (position in users.indices) {
                            selectedUserName = users[position].name
                            loadAvatar(avatarView, users[position].icon)
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            } catch (e: Exception) {
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.login_error_network)
            }
        }

        loginButton.setOnClickListener {
            val password = passwordEdit.text.toString()
            if (selectedUserName.isEmpty()) {
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.login_select_user)
                return@setOnClickListener
            }
            errorText.visibility = View.GONE
            loginButton.isEnabled = false
            progress.visibility = View.VISIBLE

            scope.launch {
                try {
                    val result = doLogin(selectedUserName, password)
                    if (result.success) {
                        LoginSession.save(ctx, selectedUserName, result.userId, resolvedApiBase)
                        LoginSession.saveTokens(ctx, result.accessToken, result.refreshToken, result.expiresIn)
                        callback?.onLoginSuccess(selectedUserName)
                        dismiss()
                    } else {
                        errorText.visibility = View.VISIBLE
                        errorText.text = result.errorMsg ?: getString(R.string.login_error_password)
                    }
                } catch (e: Exception) {
                    errorText.visibility = View.VISIBLE
                    errorText.text = e.message ?: getString(R.string.login_error_network)
                } finally {
                    loginButton.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }

        return AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(false)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Prevent dismissal via back press
        dialog?.setCanceledOnTouchOutside(false)
        dialog?.setOnKeyListener { _, keyCode, _ ->
            keyCode == android.view.KeyEvent.KEYCODE_BACK
        }
    }

    fun setCallback(cb: LoginCallback) {
        callback = cb
    }

    data class LoginResult(
        val success: Boolean,
        val userId: String = "",
        val accessToken: String = "",
        val refreshToken: String? = null,
        val expiresIn: Long = 86400,
        val errorMsg: String? = null
    )

    /**
     * Build OkHttpClient with PersistentCookieJar so server-side session
     * cookies are saved across app restarts (like browser cookies).
     */
    private fun buildCookieClient(): OkHttpClient {
        val ctx = requireContext()
        return OkHttpClient.Builder()
            .cookieJar(LoginSession.PersistentCookieJar(ctx))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun fetchUserList(): List<UserItem> = withContext(Dispatchers.IO) {
        val client = buildCookieClient()
        val request = Request.Builder()
            .url("$resolvedApiBase/getAllUser")
            .get()
            .build()
        val response = client.newCall(request).execute()
        val body = response.body.string()
        val json = JSONObject(body)
        val dataObj = json.optJSONObject("data")
        val dataArray = dataObj?.optJSONArray("data") ?: json.optJSONArray("data")
        val users = mutableListOf<UserItem>()
        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.optJSONObject(i) ?: continue
                val id = obj.optInt("id", -1)
                val name = obj.optString("name", "")
                val icon = obj.optString("icon", "")
                if (id >= 0 && name.isNotEmpty()) {
                    users.add(UserItem(id, name, resolveIconUrl(icon)))
                }
            }
        }
        users
    }

    private suspend fun doLogin(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val client = buildCookieClient()
        val jsonBody = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$resolvedApiBase/auth/login")
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body.string()
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code == 0) {
            val userId = json.optJSONObject("user")?.optInt("id", -1)?.toString() ?: ""
            val accessToken = json.optString("access_token", "")
            val refreshToken = json.optString("refresh_token", "").takeIf { it.isNotEmpty() }
            val expiresIn = json.optLong("expires_in", 86400)
            LoginResult(success = true, userId = userId, accessToken = accessToken, refreshToken = refreshToken, expiresIn = expiresIn)
        } else {
            val msg = json.optString("msg", "").takeIf { it.isNotEmpty() }
            LoginResult(success = false, errorMsg = msg)
        }
    }

    /**
     * Try local server first (simple connectivity check on root URL, 500ms timeout),
     * fallback to remote domain. Mirrors MyTodo's checkAddress approach.
     */
    private suspend fun resolveApiBase(): String = withContext(Dispatchers.IO) {
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val probeRequest = Request.Builder()
            .url(LOCAL_ROOT_URL)
            .get()
            .build()
        try {
            probeClient.newCall(probeRequest).execute().use { response ->
                // Any response (even non-2xx) means the server is reachable
                LOCAL_API_BASE
            }
        } catch (_: Exception) {
            // Connection failed / timeout -> fallback to remote
            REMOTE_API_BASE
        }
    }

    /**
     * Convert icon name to full URL (like MyTodo's getPicDisplayUrl).
     * - http/https URL -> use directly
     * - data: URI -> use directly
     * - filename -> construct {apiBase}/pic/view?name={filename}
     */
    private fun resolveIconUrl(icon: String): String {
        if (icon.isEmpty()) return ""
        if (icon.startsWith("http://") || icon.startsWith("https://") || icon.startsWith("data:")) {
            return icon
        }
        return "$resolvedApiBase/pic/view?name=${android.net.Uri.encode(icon)}"
    }

    /**
     * Load avatar image from URL into ImageView using OkHttp.
     */
    private fun loadAvatar(imageView: ImageView, iconUrl: String) {
        if (iconUrl.isEmpty()) {
            imageView.setImageResource(R.drawable.ic_folder)
            return
        }
        if (iconUrl.startsWith("data:")) {
            // Data URI - decode base64
            try {
                val base64 = iconUrl.substringAfter("base64,")
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) imageView.setImageBitmap(bitmap)
                else imageView.setImageResource(R.drawable.ic_folder)
            } catch (_: Exception) {
                imageView.setImageResource(R.drawable.ic_folder)
            }
            return
        }
        // HTTP URL - load async
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder().url(iconUrl).get().build()
                    client.newCall(request).execute().use { response ->
                        response.body.byteStream().use { BitmapFactory.decodeStream(it) }
                    }
                }
                if (bitmap != null) imageView.setImageBitmap(bitmap)
                else imageView.setImageResource(R.drawable.ic_folder)
            } catch (_: Exception) {
                imageView.setImageResource(R.drawable.ic_folder)
            }
        }
    }

    companion object {
        private const val LOCAL_ROOT_URL = "http://192.168.50.172:8848/"
        private const val LOCAL_API_BASE = "http://192.168.50.172:8848/api"
        private const val REMOTE_API_BASE = "https://leo-zhao.natapp4.cc/api"
        private const val PROBE_TIMEOUT_MS = 500L
        const val TAG = "LoginDialog"
    }
}
