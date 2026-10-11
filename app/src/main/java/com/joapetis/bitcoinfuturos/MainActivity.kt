package com.joapetis.bitcoinfuturos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        web = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(7, 17, 31))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportZoom(false)
            }
            addJavascriptInterface(BotBridge(), "AndroidBotBridge")
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 410)
        }
    }

    private inner class BotBridge {
        @JavascriptInterface
        fun updateSummary(pnl: String, equity: String, best: String, status: String) {
            val intent = Intent(this@MainActivity, BotKeepAliveService::class.java).apply {
                action = BotKeepAliveService.ACTION_UPDATE
                putExtra(BotKeepAliveService.EXTRA_PNL, pnl.take(48))
                putExtra(BotKeepAliveService.EXTRA_EQUITY, equity.take(48))
                putExtra(BotKeepAliveService.EXTRA_BEST, best.take(80))
                putExtra(BotKeepAliveService.EXTRA_STATUS, status.take(48))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startService(intent) else startService(intent)
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}