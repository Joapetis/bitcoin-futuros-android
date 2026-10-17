package com.joapetis.bitcoinfuturos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.Locale

class BotKeepAliveService : Service() {
    companion object {
        const val ACTION_STOP = "com.joapetis.bitcoinfuturos.STOP"
        const val ACTION_START = "com.joapetis.bitcoinfuturos.START"
        private const val CHANNEL_ID = "crypto_sim_status"
        private const val NOTIFICATION_ID = 4101
    }
    private var worker: ScheduledExecutorService? = null
    private var status = "Iniciando enjambre multi-temporal y Gemini Nano"
    private var lastTick = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        NativeTradingEngine.init(this)
        PixelAi.prepare()
        startEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            worker?.shutdownNow(); worker = null
            status = "Simulación detenida por el usuario"
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null) startEngine()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun startEngine() {
        if (worker != null) return
        worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "native-trading-engine").apply { isDaemon = false } }
        worker?.scheduleWithFixedDelay({
            try {
                NativeTradingEngine.tickAll()
                lastTick = System.currentTimeMillis()
                status = "Activo · ${NativeTradingEngine.aiWarmupLabel()} ·${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(lastTick))}"
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            } catch (e: Exception) {
                status = "Error temporal: ${e.message ?: "sin conexión"}".take(100)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            }
        }, 0, 15, TimeUnit.SECONDS)
    }

    private fun buildNotification(): Notification {
        val bots = NativeTradingEngine.snapshots()
        val pnl = bots.sumOf { it.pnl }
        val capital = bots.sumOf { it.equity }
        val best = bots.maxByOrNull { it.pnl }?.let { "${it.symbol} (${String.format(Locale.US, "%+.2f", it.pnl)} USDT)" } ?: "Esperando datos"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, BotKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Bitcoin Futures · v0.20 IA")
            .setContentText("P/L: ${String.format(Locale.US, "%+.2f", pnl)} USDT · Capital: ${String.format(Locale.US, "%.2f", capital)} USDT")
            .setStyle(Notification.BigTextStyle().bigText(
                "P/L global: ${String.format(Locale.US, "%+.2f", pnl)} USDT\nCapital: ${String.format(Locale.US, "%.2f", capital)} USDT\n$best\n$status"))
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Detener bot", stop).build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Estado del simulador", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    override fun onDestroy() { worker?.shutdownNow(); worker = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
