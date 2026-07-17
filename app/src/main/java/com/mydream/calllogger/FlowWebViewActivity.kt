package com.mydream.calllogger

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mydream.calllogger.ui.theme.CallLoggerTheme

/**
 * Hosts the partner's call-flow editor (menuthere.com/flow/<partnerId>) in a WebView
 * with no address bar, so the URL — and the device token in its fragment — is never
 * shown. A top app bar provides a Back button to return to the app.
 */
class FlowWebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val target: String = url

        setContent {
            CallLoggerTheme {
                // System back: step back in the web view if possible, else close.
                BackHandler { goBack() }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Call flow") },
                            navigationIcon = {
                                IconButton(onClick = { goBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    },
                ) { padding ->
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Always fetch the latest editor from the network — the flow
                                // page changes server-side, and a stale cached copy would show
                                // an old (broken) layout. Purge any existing cache too.
                                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                clearCache(true)
                                webViewClient = WebViewClient()
                                loadUrl(target)
                                webView = this
                            }
                        },
                    )
                }
            }
        }
    }

    private fun goBack() {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else finish()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun start(context: Context, url: String) {
            context.startActivity(
                Intent(context, FlowWebViewActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
