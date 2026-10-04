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

    private var localServer: LocalBridgeServer? = null

    companion object {
        const val CHANNEL_ID = "gtstore_client_channel"
        const val NOTIF_ID = 2001
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIF_ID, notification)

        if (localServer == null) {
            // Passa o Context ('this') para permitir a leitura do assets/payload.bin
            localServer = LocalBridgeServer(this, port = 8080)
            try {
                localServer?.start()
                isRunning = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        localServer?.stop()
        localServer = null
        isRunning = false
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
                description = "Mantém o servidor local ativo para injeção no console"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GTSTORE Ponte Ativa")
            .setContentText("Servidor rodando na porta 8080. Pronto para injetar no PS4.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
