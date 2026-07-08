package acr.browser.lightning.browser.password

import acr.browser.lightning.R
import acr.browser.lightning.extensions.resizeAndShow
import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Dialog that prompts the user to save a password after form submission.
 */
object SavePasswordDialog {

    fun show(
        context: Context,
        domain: String,
        username: String,
        password: String,
        onSaved: () -> Unit
    ) {
        // Don't prompt if already saved
        if (PasswordManager.hasPassword(context, domain, username)) return

        AlertDialog.Builder(context).apply {
            setTitle(R.string.password_save_title)
            setMessage(context.getString(R.string.password_save_message, domain))
            setPositiveButton(R.string.password_save) { _, _ ->
                PasswordManager.savePassword(context, domain, username, password)
                onSaved()
            }
            setNegativeButton(R.string.password_never, null)
            setCancelable(true)
        }.resizeAndShow()
    }
}
