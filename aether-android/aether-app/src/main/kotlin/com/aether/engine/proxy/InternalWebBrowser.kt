package com.aether.engine.proxy

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * InternalWebBrowser — In-app WebView browser (in-app WebView)
 *
 * ใช้เปิด URL โดยไม่ต้องออกจากแอพ (in-app browser)
 * ป้องกันการตรวจพบว่าเราเปิด Chrome จริง
 * ใช้สำหรับ login flows, OAuth, payment gateways
 */
class InternalWebBrowser : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CODE = "code"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_WITH_RESULT = "withResult"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "AetherEngine"

        setTitle(title)

        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    setTitle(view?.title ?: (this@InternalWebBrowser).title)
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(url)
        }

        setContentView(webView)
    }

    override fun onBackPressed() {
        // Let WebView handle back navigation first
        val webView = (window.decorView.findViewById(android.R.id.content) as? FrameLayout)
            ?.getChildAt(0) as? WebView
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
