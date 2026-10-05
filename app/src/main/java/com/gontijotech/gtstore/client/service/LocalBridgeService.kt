package com.gontijotech.gtstore.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gontijotech.gtstore.client.server.LocalBridgeServer
import com.gontijotech.gtstore.client.ui.MainActivity

class LocalBridgeService : Service() {

    private var server: LocalBridgeServer? = null

    companion object {
        const val CHANNEL_ID = "gtstore_bridge_service_channel"
        const val NOTIFICATION_ID = 8080

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando ponte local..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (server == null) {
                server = LocalBridgeServer(applicationContext, 8080)
                server?.start()
            }
            isRunning = true
            updateNotification("Servidor ativo na porta 8080")
        } catch (e: Exception) {
            isRunning = false
            updateNotification("Falha ao iniciar servidor: ${e.message}")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            server?.stop()
        } catch (_: Exception) {}
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GTSTORE Ponte Local",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal de execução do servidor local de injeção DPI"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GTSTORE Ponte Local")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
