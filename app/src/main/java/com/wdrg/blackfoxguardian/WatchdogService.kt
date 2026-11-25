package com.wdrg.blackfoxguardian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class WatchdogService : Service() {

    companion object {
        const val CHANNEL_ONGOING_ID = "vpn_watchdog_ongoing"
        const val CHANNEL_ALERT_ID = "vpn_watchdog_alert"
        const val NOTIF_ONGOING_ID = 1001
        const val NOTIF_ALERT_ID = 1002

        const val ACTION_START_MONITORING = "com.wdrg.blackfoxguardian.ACTION_START_MONITORING"
        const val ACTION_PING_NOW = "com.wdrg.blackfoxguardian.ACTION_PING_NOW"
        const val ACTION_STOP_MONITORING = "com.wdrg.blackfoxguardian.ACTION_STOP_MONITORING"
        const val ACTION_STATE_UPDATED = "com.wdrg.blackfoxguardian.ACTION_STATE_UPDATED"

        const val EXTRA_VPN_STATUS = "extra_vpn_status"
        const val EXTRA_VPN_COLOR = "extra_vpn_color"
        const val EXTRA_PING_TEXT = "extra_ping_text"
        const val EXTRA_PING_COLOR = "extra_ping_color"
        const val EXTRA_DETAILS_TEXT = "extra_details_text"
        const val EXTRA_SPEED_TEXT = "extra_speed_text"
        const val EXTRA_IS_MONITORING = "extra_is_monitoring"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = true
    private var intervalSeconds = 5
    private var monitorStartTime: Long = System.currentTimeMillis()

    private var lastRxBytes: Long = TrafficStats.getTotalRxBytes()
    private var lastTxBytes: Long = TrafficStats.getTotalTxBytes()
    private var lastSpeedTimeMs: Long = System.currentTimeMillis()
    private var lastAlertSignature: String? = null

    private var lastVpnStatus = "Проверка..."
    private var lastVpnColor = "#888888"
    private var lastPingText = "-- мс"
    private var lastPingColor = "#888888"
    private var lastDetailsText = "Замеряю..."
    private var lastSpeedText = "↓ 0.0 КБ/с ↑ 0.0 КБ/с"

    private val prefs by lazy {
        getSharedPreferences("watchdog_prefs", Context.MODE_PRIVATE)
    }

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkVpnAndPing()
                handler.postDelayed(this, intervalSeconds * 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        loadSettings()
        startForeground(
            NOTIF_ONGOING_ID,
            buildOngoingNotification("Мониторинг запущен", "Интервал: ${intervalSeconds} сек")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSettings()

        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                stopMonitoringInternal()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PING_NOW -> {
                if (!isMonitoring) {
                    isMonitoring = true
                    prefs.edit().putBoolean("is_monitoring", true).apply()
                    monitorStartTime = System.currentTimeMillis()
                }
                startForeground(
                    NOTIF_ONGOING_ID,
                    buildOngoingNotification("Проверка связи...", "Интервал: ${intervalSeconds} сек")
                )
                handler.removeCallbacks(pingRunnable)
                checkVpnAndPing()
                handler.postDelayed(pingRunnable, intervalSeconds * 1000L)
            }

            ACTION_START_MONITORING, null -> {
                if (isMonitoring) {
                    monitorStartTime = System.currentTimeMillis()
                    startForeground(
                        NOTIF_ONGOING_ID,
                        buildOngoingNotification("Мониторинг запущен", "Интервал: ${intervalSeconds} сек")
                    )
                    handler.removeCallbacks(pingRunnable)
                    handler.post(pingRunnable)
                } else {
                    stopMonitoringInternal()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val shouldRun = prefs.getBoolean("is_monitoring", true)
        if (shouldRun) {
            val restartIntent = Intent(applicationContext, WatchdogService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            ContextCompat.startForegroundService(applicationContext, restartIntent)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pingRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopMonitoringInternal() {
        isMonitoring = false
        prefs.edit().putBoolean("is_monitoring", false).apply()
        handler.removeCallbacks(pingRunnable)
        cancelOngoingNotification()

        lastPingText = "-- мс"
        lastPingColor = "#888888"
        lastDetailsText = "Автопроверка выключена"
        broadcastState()
    }

    private fun loadSettings() {
        intervalSeconds = prefs.getInt("interval_seconds", 5).coerceIn(1, 15)
        isMonitoring = prefs.getBoolean("is_monitoring", true)
    }

    private fun updateNetworkSpeed() {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val now = System.currentTimeMillis()

        if (currentRx == TrafficStats.UNSUPPORTED.toLong() ||
            currentTx == TrafficStats.UNSUPPORTED.toLong() ||
            lastRxBytes == TrafficStats.UNSUPPORTED.toLong() ||
            lastTxBytes == TrafficStats.UNSUPPORTED.toLong()
        ) {
            lastSpeedText = "Скорость недоступна"
            return
        }

        val elapsedSec = ((now - lastSpeedTimeMs) / 1000.0).coerceAtLeast(0.5)
        val rxRate = ((currentRx - lastRxBytes).coerceAtLeast(0L)) / elapsedSec
        val txRate = ((currentTx - lastTxBytes).coerceAtLeast(0L)) / elapsedSec

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastSpeedTimeMs = now

        lastSpeedText = "↓ ${formatRate(rxRate)} ↑ ${formatRate(txRate)}"
    }

    private fun formatRate(bytesPerSec: Double): String {
        return if (bytesPerSec >= 1024 * 1024) {
            String.format(Locale.US, "%.1f МБ/с", bytesPerSec / (1024.0 * 1024.0))
        } else {
            String.format(Locale.US, "%.0f КБ/с", bytesPerSec / 1024.0)
        }
    }

    private data class NetworkState(
        val hasInternetCapability: Boolean,
        val isVpnActive: Boolean
    )

    private fun getNetworkState(): NetworkState {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return NetworkState(
            hasInternetCapability = false,
            isVpnActive = false
        )
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkState(
            hasInternetCapability = false,
            isVpnActive = false
        )
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return NetworkState(hasInternetCapability = hasInternet, isVpnActive = vpnActive)
    }

    private fun checkVpnAndPing() {
        updateNetworkSpeed()
        val state = getNetworkState()

        when {
            !state.hasInternetCapability && !state.isVpnActive -> {
                lastVpnStatus = "● Нет интернета"
                lastVpnColor = "#FF5722"
                showDeadState("Нет активного подключения к сети", "NO_INTERNET")
                return
            }

            state.isVpnActive -> {
                lastVpnStatus = "● VPN туннель активен"
                lastVpnColor = "#4CAF50"
            }

            else -> {
                lastVpnStatus = "● VPN ВЫКЛЮЧЕН"
                lastVpnColor = "#F44336"
            }
        }

        broadcastState()

        thread {
            val startTime = System.currentTimeMillis()
            try {
                val url = URL("https://www.google.com/generate_204")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "HEAD"
                val code = conn.responseCode
                val ping = System.currentTimeMillis() - startTime
                conn.disconnect()

                handler.post {
                    if (code == 204 || code == 200) {
                        lastAlertSignature = null
                        cancelAlertNotification()

                        lastPingText = "$ping мс"
                        val detailText = when {
                            ping < 400 -> {
                                lastPingColor = "#4CAF50"
                                if (state.isVpnActive) {
                                    "Соединение отличное (через VPN)"
                                } else {
                                    "Соединение отличное (без VPN)"
                                }
                            }

                            ping < 1200 -> {
                                lastPingColor = "#FFC107"
                                "Есть задержка, но работает"
                            }

                            else -> {
                                lastPingColor = "#FF5722"
                                "Сильно тормозит! Пора менять VPN"
                            }
                        }

                        lastDetailsText = detailText
                        if (isMonitoring) {
                            val title = if (state.isVpnActive) "VPN активен • $ping мс" else "Без VPN • $ping мс"
                            updateOngoingNotification(title, detailText)
                        }
                        broadcastState()
                    } else {
                        showDeadState("Ошибка ответа: $code", "HTTP_$code")
                    }
                }
            } catch (_: Exception) {
                handler.post {
                    val currentState = getNetworkState()
                    val (reason, code) = when {
                        !currentState.hasInternetCapability && !currentState.isVpnActive ->
                            "Пропал интернет" to "NO_INTERNET"
                        currentState.isVpnActive ->
                            "VPN включён, но пакеты не идут (таймаут > 3 сек)" to "VPN_STUCK"
                        else ->
                            "Нет ответа от сервера (таймаут > 3 сек)" to "TIMEOUT"
                    }
                    showDeadState(reason, code)
                }
            }
        }
    }

    private fun showDeadState(reason: String, alertCode: String) {
        lastPingText = "ОТВАЛ"
        lastPingColor = "#F44336"
        lastDetailsText = reason

        if (isMonitoring) {
            updateOngoingNotification("ОТВАЛ • Проверка каждые ${intervalSeconds} сек", reason)
            triggerAlertIfNew(alertCode, reason)
        }
        broadcastState()
    }

    private fun triggerAlertIfNew(code: String, message: String) {
        if (lastAlertSignature == code) return
        lastAlertSignature = code

        val appName = applicationInfo.loadLabel(packageManager).toString()
        val cleanMessage = message.replace(Regex("[\\s\\(\\)\\*\\+\\-]"), " ").trim()
        showPopupAlert("$appName: Отвал", cleanMessage)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ongoingChannel = NotificationChannel(
            CHANNEL_ONGOING_ID,
            "Постоянный статус мониторинга",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Текущий статус VPN и пинга"
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERT_ID,
            "Оповещения об отвале",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Всплывающие уведомления при обрыве связи или зависании VPN"
        }

        manager.createNotificationChannel(ongoingChannel)
        manager.createNotificationChannel(alertChannel)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, WatchdogService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun buildOngoingNotification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ONGOING_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setWhen(monitorStartTime)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Пинг", serviceActionPendingIntent(ACTION_PING_NOW, 2001))
            .addAction(0, "Стоп", serviceActionPendingIntent(ACTION_STOP_MONITORING, 2002))
            .build()

    private fun updateOngoingNotification(title: String, text: String) {
        if (!hasNotificationPermission()) return
        val notification = buildOngoingNotification(title, text)
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ONGOING_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelOngoingNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIF_ONGOING_ID)
    }

    private fun cancelAlertNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIF_ALERT_ID)
    }

    private fun showPopupAlert(title: String, message: String) {
        if (!hasNotificationPermission()) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ALERT_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun broadcastState() {
        val stateIntent = Intent(ACTION_STATE_UPDATED).apply {
            setPackage(packageName)
            putExtra(EXTRA_VPN_STATUS, lastVpnStatus)
            putExtra(EXTRA_VPN_COLOR, lastVpnColor)
            putExtra(EXTRA_PING_TEXT, lastPingText)
            putExtra(EXTRA_PING_COLOR, lastPingColor)
            putExtra(EXTRA_DETAILS_TEXT, lastDetailsText)
            putExtra(EXTRA_SPEED_TEXT, lastSpeedText)
            putExtra(EXTRA_IS_MONITORING, isMonitoring)
        }
        sendBroadcast(stateIntent)
    }
}
