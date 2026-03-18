package com.sskaraoke.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sskaraoke.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TARGET_URL = "https://bukp.duckdns.org"
        private const val PREFS_FILE = "ss_karaoke_credentials"
        private const val KEY_USERNAME = "saved_username"
        private const val KEY_PASSWORD = "saved_password"
        private const val KEY_HAS_CREDENTIALS = "has_credentials"
    }

    private lateinit var binding: ActivityMainBinding
    private var pendingUsername: String = ""
    private var pendingPassword: String = ""
    private var isPageLoaded = false

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackNavigation()
        setupWebView()
        loadKaraokeWebsite()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    /** Forward D-pad / remote keys to the WebView so navigation works on TV. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isPageLoaded && binding.webView.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------------------
    // WebView setup
    // ---------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        // Attach the JavaScript bridge for credential capture
        webView.addJavascriptInterface(CredentialBridge(), "AndroidCredentialBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Only allow navigation within the target domain
                return if (url.startsWith(TARGET_URL)) {
                    false  // let the WebView load it
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.navigation_blocked),
                        Toast.LENGTH_SHORT
                    ).show()
                    true  // block it
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                isPageLoaded = false
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                isPageLoaded = true
                binding.progressBar.visibility = View.GONE
                injectCredentialDetector(view)
                tryAutoFill(view)
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                binding.progressBar.visibility = View.GONE
                showError(description)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
            }
        }
    }

    // ---------------------------------------------------------------------------
    // JavaScript injection
    // ---------------------------------------------------------------------------

    /**
     * Injects a script that intercepts form submissions, captures username/password
     * fields, and forwards them to the Android bridge.
     */
    private fun injectCredentialDetector(view: WebView) {
        val script = """
            (function() {
                function attachFormListeners() {
                    var forms = document.querySelectorAll('form');
                    forms.forEach(function(form) {
                        if (form._credListenerAttached) return;
                        form._credListenerAttached = true;
                        form.addEventListener('submit', function() {
                            var userField = form.querySelector('input[type="text"], input[type="email"], input[name*="user"], input[name*="login"], input[name*="email"]');
                            var passField = form.querySelector('input[type="password"]');
                            if (userField && passField) {
                                AndroidCredentialBridge.onFormSubmit(userField.value, passField.value);
                            }
                        }, true);
                    });
                }
                attachFormListeners();
                // Also watch for dynamically added forms
                var observer = new MutationObserver(function() { attachFormListeners(); });
                observer.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    /**
     * Auto-fills saved credentials into the first login form found on the page.
     */
    private fun tryAutoFill(view: WebView) {
        val prefs = getEncryptedPrefs() ?: return
        if (!prefs.getBoolean(KEY_HAS_CREDENTIALS, false)) return

        val username = prefs.getString(KEY_USERNAME, "") ?: return
        val password = prefs.getString(KEY_PASSWORD, "") ?: return
        if (username.isEmpty() || password.isEmpty()) return

        val escapedUser = username.replace("\\", "\\\\").replace("'", "\\'")
        val escapedPass = password.replace("\\", "\\\\").replace("'", "\\'")

        val script = """
            (function() {
                var userField = document.querySelector('input[type="text"], input[type="email"], input[name*="user"], input[name*="login"], input[name*="email"]');
                var passField = document.querySelector('input[type="password"]');
                if (userField) userField.value = '$escapedUser';
                if (passField) passField.value = '$escapedPass';
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    // ---------------------------------------------------------------------------
    // JavaScript → Android bridge
    // ---------------------------------------------------------------------------

    inner class CredentialBridge {
        /**
         * Called from JavaScript when a form with username + password is submitted.
         * Runs on a background thread – must post to UI thread for dialogs.
         */
        @JavascriptInterface
        fun onFormSubmit(username: String, password: String) {
            if (username.isBlank() || password.isBlank()) return
            runOnUiThread {
                pendingUsername = username
                pendingPassword = password
                promptSaveCredentials()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Password management
    // ---------------------------------------------------------------------------

    private fun promptSaveCredentials() {
        // Skip prompt if the exact same credentials are already saved
        val prefs = getEncryptedPrefs()
        if (prefs != null && prefs.getBoolean(KEY_HAS_CREDENTIALS, false)) {
            val savedUser = prefs.getString(KEY_USERNAME, "")
            val savedPass = prefs.getString(KEY_PASSWORD, "")
            if (savedUser == pendingUsername && savedPass == pendingPassword) return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.save_password_title)
            .setMessage(R.string.save_password_message)
            .setPositiveButton(R.string.save) { _, _ ->
                saveCredentials(pendingUsername, pendingPassword)
                Toast.makeText(this, R.string.password_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dont_save, null)
            .show()
    }

    private fun saveCredentials(username: String, password: String) {
        val prefs = getEncryptedPrefs() ?: return
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putBoolean(KEY_HAS_CREDENTIALS, true)
            .apply()
    }

    private fun getEncryptedPrefs(): android.content.SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Navigation helpers
    // ---------------------------------------------------------------------------

    private fun loadKaraokeWebsite() {
        if (isNetworkAvailable()) {
            binding.webView.loadUrl(TARGET_URL)
        } else {
            showError(getString(R.string.no_internet))
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ -> loadKaraokeWebsite() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
