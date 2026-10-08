package com.gontijotech.gtstore.client.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gontijotech.gtstore.client.service.LocalBridgeService

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val localServerUrl = "http://127.0.0.1:8080"
    private var isReloading = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Habilita ponta a ponta
        enableEdgeToEdge()

        // 1. Inicia o serviço do servidor local
        startLocalBridgeService()

        // 2. Configura a WebView
        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#06080d"))
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }

        configureWebSettings(webView.settings)
        setupWebViewClient()

        // 3. Container com fundo escuro que aplica o espaçamento seguro do sistema
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#06080d"))
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        // 4. Aplica os insets: afasta a barra de status no topo e a de gestos no rodapé
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,     // Respeita o relógio, bateria e notch da câmera
                systemBars.right,
                systemBars.bottom   // Respeita a barra de gestos inferior
            )
            insets
        }

        setContentView(rootLayout)

        // 5. Carrega a loja
        webView.loadUrl(localServerUrl)

        // 6. Gesto/botão nativo de voltar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun startLocalBridgeService() {
        val intent = Intent(this, LocalBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(settings: WebSettings) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            useWideViewPort = true
            loadWithOverviewMode = true
            displayZoomControls = false
            builtInZoomControls = false

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("whatsapp://") || url.contains("wa.me") || url.contains("api.whatsapp.com")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (_: Exception) {
                        return false
                    }
                }

                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true && !isReloading) {
                    isReloading = true
                    view?.postDelayed({
                        isReloading = false
                        view.loadUrl(localServerUrl)
                    }, 600)
                }
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
