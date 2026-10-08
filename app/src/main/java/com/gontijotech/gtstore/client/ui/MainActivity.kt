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
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.gontijotech.gtstore.client.service.LocalBridgeService

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val localServerUrl = "http://127.0.0.1:8080"
    private var isReloading = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Habilita tela cheia ponta a ponta (Edge-to-Edge)
        enableEdgeToEdge()

        // 1. Inicia o serviço do servidor local em segundo plano
        startLocalBridgeService()

        // 2. Configura a WebView em tela cheia
        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#06080d")) // Fundo escuro idêntico à loja
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }

        configureWebSettings(webView.settings)
        setupWebViewClient()

        setContentView(webView)

        // 3. Carrega a loja local
        webView.loadUrl(localServerUrl)

        // 4. Suporte nativo ao gesto de voltar
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
            // Fundamental para o carrinho, IP e pedidos persistirem no app
            domStorageEnabled = true
            databaseEnabled = true

            // Layout responsivo
            useWideViewPort = true
            loadWithOverviewMode = true
            displayZoomControls = false
            builtInZoomControls = false

            // Suporte a chamadas HTTP locais + HTTPS do Firebase
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // Intercepta links do WhatsApp e abre no aplicativo nativo
                if (url.startsWith("whatsapp://") || url.contains("wa.me") || url.contains("api.whatsapp.com")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (_: Exception) {
                        return false
                    }
                }

                // Mantém a navegação interna dentro do próprio WebView
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                // Se o WebView carregar antes do servidor na 8080 terminar de subir, tenta reconectar após 600ms
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
