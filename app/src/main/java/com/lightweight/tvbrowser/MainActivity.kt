package com.lightweight.tvbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var cursorView: CursorView
    private var fullscreenVideoView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val targetUrl = "https://theogpiratebot.online/"

    // Virtual Mouse State + Acceleration
    private var isMouseMode = false
    private var cursorX = 640f
    private var cursorY = 360f
    private val baseStep = 22f
    private var backLongPressHandled = false

    // Expanded Ad, Tracker, and Video Embed Pop-up Blocklist
    private val blockedDomains = listOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "googletagmanager.com", "popads.net", "popcash.net", "propellerads.com",
        "adsterra.com", "exoclick.com", "juicyads.com", "clickadu.com",
        "hilltopads.net", "mc.yandex.ru", "facebook.net", "scorecardresearch.com",
        "outbrain.com", "taboola.com", "adnxs.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "criteo.com", "histats.com", "acscdn.com",
        "usrpubtrk.com", "TrafficJunky", "tsyndicate.com", "bidgear.com",
        "galaksion.com", "evadav.com", "pushground.com", "richpush.co"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootLayout = FrameLayout(this)
        webView = WebView(this)
        cursorView = CursorView(this)

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        rootLayout.addView(
            cursorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        cursorView.visibility = View.GONE
        setContentView(rootLayout)

        configureLowRamWebView()
        webView.loadUrl(targetUrl)

        Toast.makeText(
            this,
            "Hold BACK (or press MENU) to switch between D-Pad & Virtual Mouse",
            Toast.LENGTH_LONG
        ).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureLowRamWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false

        // Critical for Live TV (.m3u8) and third-party movie servers
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Clean Linux Desktop User-Agent for 1080p TV layout without mobile redirect traps
        settings.userAgentString =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // 1 GB RAM survival settings
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.loadsImagesAutomatically = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase() ?: return null
                if (blockedDomains.any { url.contains(it) }) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString()?.lowercase() ?: return false

                // Block any ad domain navigation in both main page and video iframes
                if (blockedDomains.any { url.contains(it) }) {
                    return true
                }

                // Allow main site navigation and legitimate embedded video players
                if (url.contains("theogpiratebot.online") || !request.isForMainFrame) {
                    return false
                }

                // Block main window hijacks to external spam pages
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectDebloatAndTvHelpers()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (fullscreenVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenVideoView = view
                fullscreenCallback = callback
                webView.visibility = View.GONE
                rootLayout.addView(
                    fullscreenVideoView,
                    0,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                if (fullscreenVideoView == null) return
                rootLayout.removeView(fullscreenVideoView)
                fullscreenVideoView = null
                fullscreenCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }
        }
    }

    // Strips GPU-heavy CSS blurs, blocks popups, removes click-hijack overlays, and highlights D-pad focus
    private fun injectDebloatAndTvHelpers() {
        val js = """
            (function() {
                if (!document.getElementById('tv-debloat-style')) {
                    var style = document.createElement('style');
                    style.id = 'tv-debloat-style';
                    style.innerHTML = `
                        *:focus {
                            outline: 3px solid #00E5FF !important;
                            outline-offset: 2px !important;
                            box-shadow: 0 0 12px #00E5FF !important;
                        }
                        * {
                            backdrop-filter: none !important;
                            -webkit-backdrop-filter: none !important;
                        }
                        iframe[src*="ads"], div[id*="ad-"], div[class*="ad-"], .adsbox {
                            display: none !important;
                        }
                    `;
                    document.head.appendChild(style);
                }

                // Kill window.open popups
                window.open = function() { return null; };

                // Auto-dismiss social promo modals and strip invisible click-jacking overlays
                setInterval(function() {
                    var buttons = document.querySelectorAll('button, a, span');
                    for (var i = 0; i < buttons.length; i++) {
                        var txt = (buttons[i].innerText || '').trim().toLowerCase();
                        if (txt === "don't show this again" || txt === "close window") {
                            buttons[i].click();
                        }
                    }
                    // Remove transparent high-z-index click-hijack overlays over video embeds
                    var divs = document.querySelectorAll('div');
                    for (var j = 0; j < divs.length; j++) {
                        var st = window.getComputedStyle(divs[j]);
                        if (parseInt(st.zIndex) > 99999 && st.opacity === '0' && st.position === 'fixed') {
                            divs[j].remove();
                        }
                    }
                }, 2000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // 1. Reliable Long-Press BACK or MENU key to toggle Virtual Mouse
        if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            toggleMouseMode()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 1) {
                    backLongPressHandled = true
                    toggleMouseMode()
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (backLongPressHandled) {
                    backLongPressHandled = false
                    return true
                }
                // Normal short press Back behavior
                if (fullscreenVideoView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return true
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
                finish()
                return true
            }
        }

        // 2. Media Play/Pause remote key support
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (event.action == KeyEvent.ACTION_UP) {
                webView.evaluateJavascript(
                    "(function(){ var v = document.querySelector('video'); if(v){ v.paused ? v.play() : v.pause(); } })();",
                    null
                )
            }
            return true
        }

        // 3. Virtual Mouse Navigation with Hold-Acceleration
        if (isMouseMode) {
            val accel = (1f + (event.repeatCount.coerceAtMost(8) * 0.45f))
            val step = baseStep * accel
            val maxW = (rootLayout.width.takeIf { it > 0 } ?: 1920).toFloat()
            val maxH = (rootLayout.height.takeIf { it > 0 } ?: 1080).toFloat()

            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        cursorY = (cursorY - step).coerceAtLeast(0f)
                        if (cursorY < maxH * 0.12f) webView.scrollBy(0, -90)
                        cursorView.invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        cursorY = (cursorY + step).coerceAtMost(maxH)
                        if (cursorY > maxH * 0.88f) webView.scrollBy(0, 90)
                        cursorView.invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        cursorX = (cursorX - step).coerceAtLeast(0f)
                        cursorView.invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        cursorX = (cursorX + step).coerceAtMost(maxW)
                        cursorView.invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (event.repeatCount == 0) {
                            simulateTouchClick(cursorX, cursorY)
                        }
                        return true
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP && keyCode in listOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
                )
            ) {
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun toggleMouseMode() {
        isMouseMode = !isMouseMode
        val w = rootLayout.width.takeIf { it > 0 } ?: 1280
        val h = rootLayout.height.takeIf { it > 0 } ?: 720
        cursorX = w / 2f
        cursorY = h / 2f
        cursorView.visibility = if (isMouseMode) View.VISIBLE else View.GONE
        cursorView.invalidate()
        val msg = if (isMouseMode) "Virtual Mouse: ON (D-Pad moves pointer)" else "Virtual Mouse: OFF (Standard TV D-Pad)"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun simulateTouchClick(x: Float, y: Float) {
        webView.requestFocus()
        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime + 40
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0)
        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()

        // Ensure TV soft keyboard opens if the clicked element is a search/text input
        webView.evaluateJavascript(
            "(function(){ var el = document.elementFromPoint(${x / resources.displayMetrics.density}, ${y / resources.displayMetrics.density}); return (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')); })();"
        ) { result ->
            if (result == "true") {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            webView.clearCache(false)
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.clearCache(false)
        webView.destroy()
        super.onDestroy()
    }

    inner class CursorView(context: Activity) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF1744")
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isMouseMode) {
                canvas.drawCircle(cursorX, cursorY, 13f, fillPaint)
                canvas.drawCircle(cursorX, cursorY, 13f, strokePaint)
                canvas.drawCircle(cursorX, cursorY, 2.5f, centerDotPaint)
            }
        }
    }
}
