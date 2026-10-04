package com.gontijotech.gtstore.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gontijotech.gtstore.client.service.LocalBridgeService
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnOpenStore: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Inicia o serviço e o servidor local automaticamente ao abrir a aplicação
        if (!LocalBridgeService.isRunning) {
            startBridgeService()
        }

        // 2. Construção da interface de monitorização
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "GTSTORE Ponte Local"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }

        tvStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }

        tvUrl = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 48)
        }

        btnToggle = Button(this).apply {
            text = "Alternar Servidor"
            setOnClickListener { toggleService() }
        }

        btnOpenStore = Button(this).apply {
            text = "Abrir Loja no Navegador"
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8080")))
            }
        }

        layout.addView(title)
        layout.addView(tvStatus)
        layout.addView(tvUrl)
        layout.addView(btnToggle)
        layout.addView(btnOpenStore)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun toggleService() {
        if (LocalBridgeService.isRunning) {
            stopService(Intent(this, LocalBridgeService::class.java))
        } else {
            startBridgeService()
        }
        window.decorView.postDelayed({ updateUi() }, 500)
    }

    private fun startBridgeService() {
        val intent = Intent(this, LocalBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateUi() {
        val running = LocalBridgeService.isRunning
        tvStatus.text = if (running) "Status: ATIVO (Servidor pronto)" else "Status: PARADO"
        tvStatus.setTextColor(if (running) 0xFF2ECC71.toInt() else 0xFFFF453A.toInt())

        val ip = getWifiIpAddress()
        tvUrl.text = if (running) "Acesse: http://127.0.0.1:8080 ou http://$ip:8080" else "Servidor desligado"
        btnToggle.text = if (running) "Parar Servidor" else "Iniciar Servidor"
        btnOpenStore.isEnabled = running
    }

    private fun getWifiIpAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wm.connectionInfo.ipAddress
            if (ipInt == 0) "127.0.0.1"
            else InetAddress.getByAddress(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()
            ).hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
