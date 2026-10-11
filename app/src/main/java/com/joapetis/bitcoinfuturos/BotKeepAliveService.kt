package com.joapetis.bitcoinfuturos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class BotKeepAliveService : Service() {
    companion object {
        const val ACTION_STOP = "com.joapetis.bitcoinfuturos.STOP"
        const val ACTION_UPDATE = "com.joapetis.bitcoinfuturos.UPDATE"
        const val EXTRA_PNL = "pnl"
        const val EXTRA_EQUITY = "equity"
        const val EXTRA_BEST = "best"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "crypto_sim_status"
        private const val NOTIFICATION_ID = 4101
    }
    private var pnl = "0,00 USDT"
    private var equity = "1.000,00 USDT"
    private var best = "Esperando datos del mercado"
    private var status = "Simulación activada"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_UPDATE) {
            pnl = intent.getStringExtra(EXTRA_PNL) ?: pnl
            equity = intent.getStringExtra(EXTRA_EQUITY) ?: equity
            best = intent.getStringExtra(EXTRA_BEST) ?: best
            status = intent.getStringExtra(EXTRA_STATUS) ?: status
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, BotKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Crypto Futures · Alpha 0.1")
            .setContentText("P/L: $pnl · Capital: $equity")
            .setStyle(Notification.BigTextStyle().bigText(
                "P/L global: $pnl\nCapital simulado: $equity\nMejor bot: $best\n$status\nSolo simulación; no se envían órdenes reales."))
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Detener aviso", stop).build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Estado del simulador", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Resultados globales y estado de Crypto Futures Alpha"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
}