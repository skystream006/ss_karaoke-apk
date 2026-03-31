package com.sskaraoke.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.JavascriptInterface
import kotlin.math.abs
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

        /** Size of the cursor indicator in dp. Must match the layout dimension. */
        private const val CURSOR_SIZE_DP = 24f
        /** Pixels to move the cursor per D-pad press (at 1× density ≈ 40 px). */
        private const val CURSOR_STEP_DP = 40f
        /** Duration in ms between synthetic ACTION_DOWN and ACTION_UP for a cursor click. */
        private const val CLICK_DURATION_MS = 100L
        /**
         * Per-frame lerp factor used by the Choreographer-based cursor animation.
         * Each frame the cursor closes this fraction of the remaining distance to the target.
         * At 60 fps a factor of 0.30 reaches ~97 % of the target in ~10 frames (≈ 167 ms),
         * giving a natural spring-like feel without any abrupt restarts on rapid D-pad presses.
         */
        private const val CURSOR_LERP_FACTOR = 0.30f
    }

    private lateinit var binding: ActivityMainBinding
    private var pendingUsername: String = ""
    private var pendingPassword: String = ""
    private var isPageLoaded = false

    // Fullscreen video support
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Cursor mode – disabled on mobile phones (touch devices), enabled on TV/non-phone devices.
    private var cursorModeEnabled = true
    private var cursorX = 0f
    private var cursorY = 0f
    // Current rendered position of the cursor overlay (animated toward cursorX/cursorY).
    private var displayX = 0f
    private var displayY = 0f
    private var isCursorAnimating = false

    /**
     * Choreographer callback that runs every vsync frame while the cursor is moving.
     * It lerps [displayX]/[displayY] toward the [cursorX]/[cursorY] target using
     * [CURSOR_LERP_FACTOR], which produces a smooth spring-like deceleration without
     * any abrupt restarts when the target changes mid-animation (e.g. rapid D-pad presses).
     */
    private val cursorFrameCallback: Choreographer.FrameCallback by lazy {
        Choreographer.FrameCallback {
            val dx = cursorX - displayX
            val dy = cursorY - displayY
            if (abs(dx) < 0.5f && abs(dy) < 0.5f) {
                // Close enough – snap to target and stop animating.
                displayX = cursorX
                displayY = cursorY
                updateCursorPosition()
                injectHoverEvent()
                isCursorAnimating = false
            } else {
                displayX += dx * CURSOR_LERP_FACTOR
                displayY += dy * CURSOR_LERP_FACTOR
                updateCursorPosition()
                injectHoverEvent()
                Choreographer.getInstance().postFrameCallback(cursorFrameCallback)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cursorModeEnabled = isTvDevice()
        if (cursorModeEnabled) binding.cursor.visibility = View.VISIBLE

        setupBackNavigation()
        setupWebView()
        loadKaraokeWebsite()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        binding.webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        // webView.onPause() throttles rendering/animations; pauseTimers() is intentionally
        // NOT called here because it freezes all JavaScript timers globally, including
        // WebSocket/socket.io heartbeat intervals, which would cause the server to drop
        // the socket connection whenever the app is briefly backgrounded.
        binding.webView.onPause()
    }

    /**
     * Resume the WebView whenever the window regains focus so that a brief focus loss
     * (e.g. the save-credentials AlertDialog appearing) does not leave the WebView in a
     * paused/stalled state that interrupts ongoing video playback.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            binding.webView.onResume()
            binding.webView.resumeTimers()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Choreographer.getInstance().removeFrameCallback(cursorFrameCallback)
        isCursorAnimating = false
        binding.webView.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
    }

    /**
     * Release WebView memory caches when the system signals memory pressure.
     * On low-RAM devices this prevents the OS from killing the process mid-playback.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isDestroyed) return
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // At critical pressure the OS is about to kill processes; purge both the
                // in-memory and disk caches to reclaim as much memory as possible.
                binding.webView.clearCache(true)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // At low pressure clear only the in-memory cache so disk-cached resources
                // can still be reused on the next page load.
                binding.webView.clearCache(false)
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    customViewCallback?.onCustomViewHidden()
                    return
                }
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** Forward D-pad / remote keys to the WebView so navigation works on TV. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isPageLoaded && cursorModeEnabled) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = CURSOR_STEP_DP * resources.displayMetrics.density
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP    -> moveCursor(0f, -step)
                            KeyEvent.KEYCODE_DPAD_DOWN  -> moveCursor(0f,  step)
                            KeyEvent.KEYCODE_DPAD_LEFT  -> moveCursor(-step, 0f)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor( step, 0f)
                        }
                    }
                    // Consume both DOWN and UP so the WebView's built-in D-pad focus
                    // navigation never activates while cursor mode is in use.
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) performCursorClick()
                    return true
                }
            }
        }
        if (isPageLoaded && binding.webView.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Forward mouse hover and scroll-wheel events to the WebView so a connected mouse works. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Keep the cursor indicator in sync when a real mouse is used.
        if (cursorModeEnabled && event.action == MotionEvent.ACTION_HOVER_MOVE) {
            cursorX = event.x.coerceIn(0f, binding.webView.width.toFloat())
            cursorY = event.y.coerceIn(0f, binding.webView.height.toFloat())
            displayX = cursorX
            displayY = cursorY
            updateCursorPosition()
        }
        if (binding.webView.onGenericMotionEvent(event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---------------------------------------------------------------------------
    // Cursor mode helpers
    // ---------------------------------------------------------------------------

    /** Move the virtual cursor by (dx, dy) pixels and clamp to the WebView bounds.
     *  The Choreographer callback will smoothly lerp the overlay toward the new target
     *  on every vsync frame, so rapid D-pad presses simply update the target and the
     *  animation continues without any abrupt restart. */
    private fun moveCursor(dx: Float, dy: Float) {
        val webView = binding.webView
        cursorX = (cursorX + dx).coerceIn(0f, webView.width.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, webView.height.toFloat())

        if (!isCursorAnimating) {
            isCursorAnimating = true
            Choreographer.getInstance().postFrameCallback(cursorFrameCallback)
        }
    }

    /** Position the cursor overlay View so its centre sits at (displayX, displayY). */
    private fun updateCursorPosition() {
        val halfPx = CURSOR_SIZE_DP * resources.displayMetrics.density / 2f
        binding.cursor.translationX = displayX - halfPx
        binding.cursor.translationY = displayY - halfPx
    }

    /** Inject a synthetic hover-move event at the current rendered cursor position so the
     *  WebView (and thus the page's CSS :hover rules) responds to cursor movement. */
    private fun injectHoverEvent() {
        val webView = binding.webView
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, displayX, displayY, 0)
        event.source = InputDevice.SOURCE_MOUSE
        webView.onGenericMotionEvent(event)
        event.recycle()
    }

    /** Inject a synthetic tap (ACTION_DOWN + ACTION_UP) at the current cursor position.
     *  Any running movement animation is stopped first so the click lands exactly where
     *  the user sees the cursor. */
    private fun performCursorClick() {
        Choreographer.getInstance().removeFrameCallback(cursorFrameCallback)
        isCursorAnimating = false
        displayX = cursorX
        displayY = cursorY
        updateCursorPosition()

        val webView = binding.webView
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now,                    MotionEvent.ACTION_DOWN, displayX, displayY, 0)
        val up   = MotionEvent.obtain(now, now + CLICK_DURATION_MS, MotionEvent.ACTION_UP,   displayX, displayY, 0)
        webView.onTouchEvent(down)
        webView.onTouchEvent(up)
        down.recycle()
        up.recycle()
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
            // Allow the web app to play video programmatically (e.g. when a socket event fires)
            // without requiring a user tap. Navigation is already locked to TARGET_URL via
            // shouldOverrideUrlLoading, so only trusted content can trigger autoplay.
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        // Attach the JavaScript bridge for credential capture
        webView.addJavascriptInterface(CredentialBridge(), "AndroidCredentialBridge")

        // On low-RAM devices the WebView renderer process can be killed by the OS.
        // Requesting IMPORTANT priority keeps it alive while the app is in the foreground,
        // and setting waivePriority=true lets the OS reclaim resources when the WebView
        // is not visible (e.g. app is backgrounded), preventing unnecessary memory pressure.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

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
                injectAutoFocusPreventer(view)
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

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webView.visibility = View.GONE
                hideSystemUi()
            }

            override fun onHideCustomView() {
                binding.fullscreenContainer.removeView(customView)
                binding.fullscreenContainer.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                customView = null
                customViewCallback = null
                showSystemUi()
            }
        }

        // Position the cursor at the centre of the WebView once its size is known.
        webView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                webView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (cursorModeEnabled) {
                    cursorX = webView.width / 2f
                    cursorY = webView.height / 2f
                    displayX = cursorX
                    displayY = cursorY
                    updateCursorPosition()
                }
            }
        })
    }

    // ---------------------------------------------------------------------------
    // JavaScript injection
    // ---------------------------------------------------------------------------

    /**
     * Injects a script that removes the `autofocus` attribute from all non-password inputs
     * and suppresses programmatic focus()/select() calls on them, preventing the soft
     * keyboard from appearing unexpectedly on page load.  Password inputs are left
     * untouched so that [tryAutoFill] can still focus them normally.
     */
    private fun injectAutoFocusPreventer(view: WebView) {
        val script = """
            (function() {
                function preventAutoFocus(root) {
                    var inputs = root.querySelectorAll('input:not([type="password"]), textarea, select');
                    inputs.forEach(function(el) {
                        el.removeAttribute('autofocus');
                        el.focus = function() {};
                        el.select = function() {};
                    });
                }
                preventAutoFocus(document);
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(m) {
                        m.addedNodes.forEach(function(node) {
                            if (node.nodeType === 1) {
                                if (node.matches && node.matches('input:not([type="password"]), textarea, select')) {
                                    node.removeAttribute('autofocus');
                                    node.focus = function() {};
                                    node.select = function() {};
                                }
                                preventAutoFocus(node);
                            }
                        });
                    });
                });
                observer.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

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
     * Auto-fills the stored password into the site's password field and clicks the "Enter" button.
     * Uses a polling retry (up to 10 attempts × 500 ms) to handle SPA pages that render
     * their password prompt after the initial page-load event.
     */
    private fun tryAutoFill(view: WebView) {
        val prefs = getEncryptedPrefs() ?: return
        if (!prefs.getBoolean(KEY_HAS_CREDENTIALS, false)) return

        val password = prefs.getString(KEY_PASSWORD, "") ?: return
        if (password.isEmpty()) return

        val escapedPass = password.replace("\\", "\\\\").replace("'", "\\'")

        val script = """
            (function() {
                var maxAttempts = 10;
                var attempt = 0;
                function tryFill() {
                    attempt++;
                    var passField = document.querySelector('input[type="password"]');
                    if (passField) {
                        passField.focus();
                        var descriptor = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                        var nativeSetter = descriptor && descriptor.set;
                        if (nativeSetter) {
                            nativeSetter.call(passField, '$escapedPass');
                        } else {
                            passField.value = '$escapedPass';
                        }
                        passField.dispatchEvent(new Event('input', {bubbles: true}));
                        passField.dispatchEvent(new Event('change', {bubbles: true}));
                        var btn = document.querySelector('button[type="submit"], input[type="submit"]');
                        if (!btn) {
                            var buttons = document.querySelectorAll('button');
                            for (var i = 0; i < buttons.length; i++) {
                                if (/enter/i.test(buttons[i].textContent.trim())) {
                                    btn = buttons[i];
                                    break;
                                }
                            }
                        }
                        if (btn) btn.click();
                        return;
                    }
                    if (attempt < maxAttempts) {
                        setTimeout(tryFill, 500);
                    }
                }
                tryFill();
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
    // Device detection
    // ---------------------------------------------------------------------------

    /**
     * Returns true when running on a TV device (has the Leanback/TV feature).
     * Cursor mode is only useful on TV where D-pad navigation is the primary input method;
     * on phones and tablets the touchscreen is sufficient and the cursor should be hidden.
     */
    private fun isTvDevice(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    // ---------------------------------------------------------------------------
    // Navigation helpers
    // ---------------------------------------------------------------------------

    private fun loadKaraokeWebsite() {
        val prefs = getEncryptedPrefs()
        if (prefs != null && !prefs.getBoolean(KEY_HAS_CREDENTIALS, false)) {
            promptForPassword()
            return
        }
        // Either encrypted prefs are unavailable (crypto error) or password is already stored – proceed.
        if (isNetworkAvailable()) {
            binding.webView.loadUrl(TARGET_URL)
        } else {
            showError(getString(R.string.no_internet))
        }
    }

    /**
     * Shows a dialog asking the user to enter the site password.
     * The dialog cannot be dismissed without providing a non-empty password.
     * Once saved, the website is loaded immediately.
     */
    private fun promptForPassword() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.enter_password_title)
            .setMessage(R.string.enter_password_message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.save, null) // listener set below to prevent auto-dismiss
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text.toString()
                if (password.isBlank()) {
                    input.error = getString(R.string.password_required)
                } else {
                    saveCredentials("", password) // password-only prompt; username is not needed here
                    dialog.dismiss()
                    loadKaraokeWebsite()
                }
            }
        }
        dialog.show()
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

    // ---------------------------------------------------------------------------
    // Fullscreen helpers
    // ---------------------------------------------------------------------------

    private fun hideSystemUi() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemUi() {
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}
