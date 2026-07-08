package acr.browser.lightning.browser.password

import android.webkit.JavascriptInterface
import acr.browser.lightning.log.Logger

/**
 * JavaScript interface injected into WebView to detect form submissions
 * with password fields and report credentials back to native code.
 */
class PasswordJsInterface(
    private val logger: Logger,
    private val onFormSubmit: (domain: String, username: String, password: String) -> Unit
) {

    @JavascriptInterface
    fun onPasswordFormSubmit(domain: String, username: String, password: String) {
        if (username.isNotEmpty() && password.isNotEmpty()) {
            logger.log(TAG, "Password form submitted: domain=$domain, user=$username")
            onFormSubmit(domain, username, password)
        }
    }

    companion object {
        const val INTERFACE_NAME = "PasswordBridge"
        private const val TAG = "PasswordJsInterface"

        /**
         * JS code to inject into every page. Detects form submissions with password fields
         * and reports credentials to the native bridge.
         */
        fun getDetectionScript(): String = """
            (function() {
                if (window.__passwordDetectionInstalled) return;
                window.__passwordDetectionInstalled = true;

                function findPasswordForm(form) {
                    var pwdInput = form ? form.querySelector('input[type="password"]') : null;
                    if (!pwdInput) return null;

                    // Find username field: text/email input in same form, or by name/id heuristics
                    var usernameInput = null;
                    var inputs = form ? form.querySelectorAll('input[type="text"], input[type="email"], input:not([type])') : [];
                    for (var i = 0; i < inputs.length; i++) {
                        var field = inputs[i];
                        var name = (field.name || '').toLowerCase();
                        var id = (field.id || '').toLowerCase();
                        if (name.indexOf('user') >= 0 || name.indexOf('email') >= 0 ||
                            name.indexOf('account') >= 0 || name.indexOf('name') >= 0 ||
                            id.indexOf('user') >= 0 || id.indexOf('email') >= 0 ||
                            id.indexOf('account') >= 0 || id.indexOf('name') >= 0) {
                            usernameInput = field;
                            break;
                        }
                    }
                    // Fallback: first non-password text input
                    if (!usernameInput) {
                        for (var i = 0; i < inputs.length; i++) {
                            if (inputs[i] !== pwdInput) {
                                usernameInput = inputs[i];
                                break;
                            }
                        }
                    }

                    return {
                        username: usernameInput ? (usernameInput.value || '') : '',
                        password: pwdInput.value || ''
                    };
                }

                // Intercept form submit
                document.addEventListener('submit', function(e) {
                    var form = e.target;
                    if (form && form.tagName === 'FORM') {
                        var creds = findPasswordForm(form);
                        if (creds && creds.password) {
                            var domain = window.location.hostname || window.location.href;
                            if (window.$INTERFACE_NAME && window.$INTERFACE_NAME.onPasswordFormSubmit) {
                                window.$INTERFACE_NAME.onPasswordFormSubmit(domain, creds.username, creds.password);
                            }
                        }
                    }
                }, true);

                // Also detect programmatic submissions (fetch/XHR login)
                var origFetch = window.fetch;
                window.fetch = function() {
                    return origFetch.apply(this, arguments).then(function(response) {
                        try {
                            var url = arguments[0] || '';
                            var opts = arguments[1] || {};
                            var body = opts.body;
                            if (typeof body === 'string' && body.indexOf('password') >= 0) {
                                try {
                                    var params = JSON.parse(body);
                                    if (params.password && (params.username || params.user || params.account)) {
                                        var domain = window.location.hostname || url;
                                        var user = params.username || params.user || params.account || '';
                                        if (window.$INTERFACE_NAME && window.$INTERFACE_NAME.onPasswordFormSubmit) {
                                            window.$INTERFACE_NAME.onPasswordFormSubmit(domain, user, params.password);
                                        }
                                    }
                                } catch(ex) {}
                            }
                        } catch(ex) {}
                        return response;
                    });
                };
            })();
        """.replace("\$INTERFACE_NAME", INTERFACE_NAME)

        /**
         * JS code to auto-fill saved credentials into login form fields.
         */
        fun getAutofillScript(username: String, password: String): String = """
            (function() {
                var inputs = document.querySelectorAll('input[type="password"]');
                for (var i = 0; i < inputs.length; i++) {
                    var pwd = inputs[i];
                    var form = pwd.closest('form');
                    var allInputs = form ? form.querySelectorAll('input[type="text"], input[type="email"], input:not([type])')
                                         : document.querySelectorAll('input[type="text"], input[type="email"], input:not([type])');
                    for (var j = 0; j < allInputs.length; j++) {
                        var field = allInputs[j];
                        var name = (field.name || '').toLowerCase();
                        var id = (field.id || '').toLowerCase();
                        if (name.indexOf('user') >= 0 || name.indexOf('email') >= 0 ||
                            name.indexOf('account') >= 0 || name.indexOf('name') >= 0 ||
                            id.indexOf('user') >= 0 || id.indexOf('email') >= 0 ||
                            id.indexOf('account') >= 0 || id.indexOf('name') >= 0) {
                            field.value = '$username';
                            field.dispatchEvent(new Event('input', {bubbles: true}));
                            field.dispatchEvent(new Event('change', {bubbles: true}));
                            break;
                        }
                    }
                    // Fill password
                    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    nativeInputValueSetter.call(pwd, '$password');
                    pwd.dispatchEvent(new Event('input', {bubbles: true}));
                    pwd.dispatchEvent(new Event('change', {bubbles: true}));
                    break; // Only fill first password field
                }
            })();
        """
    }
}
