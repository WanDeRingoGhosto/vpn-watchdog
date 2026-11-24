package com.example.vpnwatchdog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

        companion object {
        private const val CHANNEL_ONGOING_ID = "vpn_watchdog_ongoing"
        private const val CHANNEL_ALERT_ID = "vpn_watchdog_alert"
        private const val NOTIF_ONGOING_ID = 1001
        private const val NOTIF_ALERT_ID = 1002

        private const val ACTION_PING_NOW = "com.example.vpnwatchdog.ACTION_PING_NOW"
        private const val ACTION_STOP_MONITORING = "com.example.vpnwatchdog.ACTION_STOP_MONITORING"
    }

    private lateinit var tvVpnStatus: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvDetails: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvIntervalLabel: TextView
    private lateinit var switchMonitoring: SwitchMaterial
    private lateinit var seekInterval: SeekBar

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = true
    private var intervalSeconds = 5
    
    private var monitorStartTime: Long = System.currentTimeMillis()
    private var actionReceiverRegistered = false
    
    private var lastRxBytes: Long = TrafficStats.getTotalRxBytes()
    private var lastTxBytes: Long = TrafficStats.getTotalTxBytes()
    private var lastSpeedTimeMs: Long = System.currentTimeMillis()

    private var lastAlertSignature: String? = null

    private val prefs by lazy {
        getSharedPreferences("watchdog_prefs", Context.MODE_PRIVATE)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isMonitoring) {
                updateOngoingNotification("Мониторинг запущен", "Интервал: $intervalSeconds сек")
            }
        }
    private fun registerActionReceiverIfNeeded() {
        if (actionReceiverRegistered) return
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_PING_NOW)
            addAction(ACTION_STOP_MONITORING)
        }
        ContextCompat.registerReceiver(
            this,
            actionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        actionReceiverRegistered = true
    }

    private fun unregisterActionReceiverIfNeeded() {
        if (!actionReceiverRegistered) return
        try {
            unregisterReceiver(actionReceiver)
        } catch (_: IllegalArgumentException) {
        }
        actionReceiverRegistered = false
    }

    private val actionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PING_NOW -> {
                    if (isMonitoring) {
                        handler.removeCallbacks(pingRunnable)
                        checkVpnAndPing()
                        handler.postDelayed(pingRunnable, intervalSeconds * 1000L)
                    }
                }
                ACTION_STOP_MONITORING -> {
                    isMonitoring = false
                    prefs.edit().putBoolean("is_monitoring", false).apply()
                    handler.removeCallbacks(pingRunnable)
                    if (::switchMonitoring.isInitialized) {
                        switchMonitoring.isChecked = false
                    }
                    cancelOngoingNotification()
                    setIdlePausedState()
                }
            }
        }
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(packageName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, requestCode, intent, flags)
    }

    private fun updateOngoingNotification(title: String, text: String) {
        if (!hasNotificationPermission()) return

        val pingIntent = actionPendingIntent(ACTION_PING_NOW, 2001)
        val stopIntent = actionPendingIntent(ACTION_STOP_MONITORING, 2002)

        val notification = NotificationCompat.Builder(this, CHANNEL_ONGOING_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setWhen(monitorStartTime)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Пинг", pingIntent)
            .addAction(0, "Стоп", stopIntent)
            .build()

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

    override fun onDestroy() {
        unregisterActionReceiverIfNeeded()
        handler.removeCallbacks(pingRunnable)
        super.onDestroy()
    }

    private fun setIdlePausedState() {
        tvPing.text = "-- мс"
        tvPing.setTextColor(Color.parseColor("#888888"))
        tvDetails.text = "Автопроверка выключена"
    }

    private fun loadSettings() {
        intervalSeconds = prefs.getInt("interval_seconds", 5).coerceIn(1, 15)
        isMonitoring = prefs.getBoolean("is_monitoring", true)
        seekInterval.progress = intervalSeconds - 1
    }

    private fun updateIntervalUi() {
        tvIntervalLabel.text = "Интервал: $intervalSeconds сек"
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
            tvSpeed.text = "Скорость недоступна"
            return
        }

        val elapsedSec = ((now - lastSpeedTimeMs) / 1000.0).coerceAtLeast(0.5)
        val rxRate = ((currentRx - lastRxBytes).coerceAtLeast(0L)) / elapsedSec
        val txRate = ((currentTx - lastTxBytes).coerceAtLeast(0L)) / elapsedSec

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastSpeedTimeMs = now

        tvSpeed.text = "↓ ${formatRate(rxRate)}  ↑ ${formatRate(txRate)}"
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
        val cn = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cn.activeNetwork ?: return NetworkState(
            hasInternetCapability = false,
            isVpnActive = false
        )
        val caps = cn.getNetworkCapabilities(activeNetwork) ?: return NetworkState(
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
                tvVpnStatus.text = "🌐 Нет интернета"
                tvVpnStatus.setTextColor(Color.parseColor("#FF5722"))
                showDeadState("Нет активного подключения к сети", "NO_INTERNET")
                return
            }

            state.isVpnActive -> {
                tvVpnStatus.text = "🟢 VPN туннель активен"
                tvVpnStatus.setTextColor(Color.parseColor("#4CAF50"))
            }

            else -> {
                tvVpnStatus.text = "🔴 VPN ВЫКЛЮЧЕН"
                tvVpnStatus.setTextColor(Color.parseColor("#F44336"))
            }
        }

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

                runOnUiThread {
                        if (code == 204 || code == 200) {
                             lastAlertSignature = null
                             cancelAlertNotification()

                        tvPing.text = "$ping мс"
                        val detailText = when {
                            ping < 400 -> {
                                tvPing.setTextColor(Color.parseColor("#4CAF50"))
                                if (state.isVpnActive) {
                                    "Соединение отличное (через VPN)"
                                } else {
                                    "Соединение отличное (без VPN)"
                                }
                            }
                            ping < 1200 -> {
                                tvPing.setTextColor(Color.parseColor("#FFC107"))
                                "Есть задержка, но работает"
                            }
                            else -> {
                                tvPing.setTextColor(Color.parseColor("#FF5722"))
                                "Сильно тормозит! Пора менять VPN"
                            }
                        }
                        tvDetails.text = detailText
                        if (isMonitoring) {
                            val title = if (state.isVpnActive) "VPN активен • $ping мс" else "Без VPN • $ping мс"
                            updateOngoingNotification(title, detailText)
                        }
                    } else {
                        showDeadState("Ошибка ответа: $code", "HTTP_$code")
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
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
        tvPing.text = "ОТВАЛ"
        tvPing.setTextColor(Color.parseColor("#F44336"))
        tvDetails.text = reason

        if (isMonitoring) {
            updateOngoingNotification("ОТВАЛ • Проверка каждые $intervalSeconds сек", reason)
        }
        triggerAlertIfNew(alertCode, reason)
    }

    private fun triggerAlertIfNew(code: String, message: String) {
        if (lastAlertSignature == code) return
        lastAlertSignature = code
        showPopupAlert("Black Fox Guardian: отвал связи", message)
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun updateOngoingNotification(title: String, text: String) {
        if (!hasNotificationPermission()) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ONGOING_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ONGOING_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelOngoingNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIF_ONGOING_ID)
    }

    private fun showPopupAlert(title: String, message: String) {
        if (!hasNotificationPermission()) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ALERT_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        handler.removeCallbacks(pingRunnable)
        cancelOngoingNotification()
    }
}
