package com.example.vpnwatchdog

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

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

    private var lastRxBytes: Long = TrafficStats.getTotalRxBytes()
    private var lastTxBytes: Long = TrafficStats.getTotalTxBytes()
    private var lastSpeedTimeMs: Long = System.currentTimeMillis()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvVpnStatus = findViewById(R.id.tvVpnStatus)
        tvPing = findViewById(R.id.tvPing)
        tvDetails = findViewById(R.id.tvDetails)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvIntervalLabel = findViewById(R.id.tvIntervalLabel)
        switchMonitoring = findViewById(R.id.switchMonitoring)
        seekInterval = findViewById(R.id.seekInterval)

        loadSettings()
        updateIntervalUi()

        switchMonitoring.isChecked = isMonitoring
        switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            isMonitoring = isChecked
            prefs.edit().putBoolean("is_monitoring", isMonitoring).apply()
            handler.removeCallbacks(pingRunnable)
            if (isMonitoring) {
                tvDetails.text = "Замеряю..."
                handler.post(pingRunnable)
            } else {
                tvDetails.text = "Мониторинг выключен"
            }
        }

        seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                intervalSeconds = progress + 1
                updateIntervalUi()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt("interval_seconds", intervalSeconds).apply()
                if (isMonitoring) {
                    handler.removeCallbacks(pingRunnable)
                    handler.postDelayed(pingRunnable, intervalSeconds * 1000L)
                }
            }
        })

        findViewById<Button>(R.id.btnCheckNow).setOnClickListener {
            tvDetails.text = "Замеряю..."
            checkVpnAndPing()
        }

        findViewById<Button>(R.id.btnOpenVpnSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        if (isMonitoring) {
            handler.post(pingRunnable)
        } else {
            checkVpnAndPing()
            tvDetails.text = "Мониторинг выключен"
        }
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
                showDeadState("Нет активного подключения к сети")
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
                        tvPing.text = "$ping мс"
                        when {
                            ping < 400 -> {
                                tvPing.setTextColor(Color.parseColor("#4CAF50"))
                                tvDetails.text = if (state.isVpnActive) {
                                    "Соединение отличное (через VPN)"
                                } else {
                                    "Соединение отличное (без VPN)"
                                }
                            }
                            ping < 1200 -> {
                                tvPing.setTextColor(Color.parseColor("#FFC107"))
                                tvDetails.text = "Есть задержка, но работает"
                            }
                            else -> {
                                tvPing.setTextColor(Color.parseColor("#FF5722"))
                                tvDetails.text = "Сильно тормозит! Пора менять VPN"
                            }
                        }
                    } else {
                        showDeadState("Ошибка ответа: $code")
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    val currentState = getNetworkState()
                    val reason = when {
                        !currentState.hasInternetCapability && !currentState.isVpnActive ->
                            "Пропал интернет"
                        currentState.isVpnActive ->
                            "VPN включён, но пакеты не идут (таймаут > 3 сек)"
                        else ->
                            "Нет ответа от сервера (таймаут > 3 сек)"
                    }
                    showDeadState(reason)
                }
            }
        }
    }

    private fun showDeadState(reason: String) {
        tvPing.text = "ОТВАЛ"
        tvPing.setTextColor(Color.parseColor("#F44336"))
        tvDetails.text = reason
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        handler.removeCallbacks(pingRunnable)
    }
}
