package com.example.vpnwatchdog

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvVpnStatus: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvDetails: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = true

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkVpnAndPing()
                handler.postDelayed(this, 5000) // Проверка каждые 5 секунд
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvVpnStatus = findViewById(R.id.tvVpnStatus)
        tvPing = findViewById(R.id.tvPing)
        tvDetails = findViewById(R.id.tvDetails)

        findViewById<Button>(R.id.btnCheckNow).setOnClickListener {
            tvDetails.text = "Замеряю..."
            checkVpnAndPing()
        }

        findViewById<Button>(R.id.btnOpenVpnSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        handler.post(pingRunnable)
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun checkVpnAndPing() {
        val vpnOn = isVpnActive()
        tvVpnStatus.text = if (vpnOn) "🟢 VPN туннель активен" else "🔴 VPN ВЫКЛЮЧЕН"
        tvVpnStatus.setTextColor(if (vpnOn) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))

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
                                tvDetails.text = "Соединение отличное"
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
            } catch (e: Exception) {
                runOnUiThread {
                    showDeadState("Нет ответа (Таймаут > 3 сек)")
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
