package com.wdrg.blackfoxguardian

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvVpnStatus: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvDetails: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvIntervalLabel: TextView
    private lateinit var switchMonitoring: SwitchMaterial
    private lateinit var seekInterval: SeekBar

    private var isMonitoring = true
    private var intervalSeconds = 5
    private var stateReceiverRegistered = false

    private val prefs by lazy {
        getSharedPreferences("watchdog_prefs", Context.MODE_PRIVATE)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isMonitoring) {
            sendServiceCommand(WatchdogService.ACTION_START_MONITORING)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WatchdogService.ACTION_STATE_UPDATED) return

            val running = intent.getBooleanExtra(WatchdogService.EXTRA_IS_MONITORING, isMonitoring)
            isMonitoring = running

            if (::switchMonitoring.isInitialized && switchMonitoring.isChecked != running) {
                switchMonitoring.isChecked = running
            }

            intent.getStringExtra(WatchdogService.EXTRA_VPN_STATUS)?.let {
                tvVpnStatus.text = it
            }
            intent.getStringExtra(WatchdogService.EXTRA_VPN_COLOR)?.let {
                tvVpnStatus.setTextColor(Color.parseColor(it))
            }
            intent.getStringExtra(WatchdogService.EXTRA_PING_TEXT)?.let {
                tvPing.text = it
            }
            intent.getStringExtra(WatchdogService.EXTRA_PING_COLOR)?.let {
                tvPing.setTextColor(Color.parseColor(it))
            }
            intent.getStringExtra(WatchdogService.EXTRA_DETAILS_TEXT)?.let {
                tvDetails.text = it
            }
            intent.getStringExtra(WatchdogService.EXTRA_SPEED_TEXT)?.let {
                tvSpeed.text = it
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()

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
        if (!isMonitoring) {
            setIdlePausedState()
        } else {
            sendServiceCommand(WatchdogService.ACTION_START_MONITORING)
        }

        switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            isMonitoring = isChecked
            prefs.edit().putBoolean("is_monitoring", isMonitoring).apply()

            if (isMonitoring) {
                requestNotificationPermissionIfNeeded()
                tvDetails.text = "Замеряю..."
                sendServiceCommand(WatchdogService.ACTION_START_MONITORING)
            } else {
                sendServiceCommand(WatchdogService.ACTION_STOP_MONITORING)
                setIdlePausedState()
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
                    sendServiceCommand(WatchdogService.ACTION_START_MONITORING)
                }
            }
        })

        findViewById<Button>(R.id.btnCheckNow).setOnClickListener {
            tvDetails.text = "Замеряю..."
            sendServiceCommand(WatchdogService.ACTION_PING_NOW)
        }

        findViewById<Button>(R.id.btnOpenVpnSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerStateReceiverIfNeeded()
    }

    override fun onStop() {
        unregisterStateReceiverIfNeeded()
        super.onStop()
    }

    private fun sendServiceCommand(action: String) {
        val serviceIntent = Intent(this, WatchdogService::class.java).apply {
            this.action = action
        }
        if (action == WatchdogService.ACTION_STOP_MONITORING) {
            startService(serviceIntent)
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    private fun registerStateReceiverIfNeeded() {
        if (stateReceiverRegistered) return
        val filter = IntentFilter(WatchdogService.ACTION_STATE_UPDATED)
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        stateReceiverRegistered = true
    }

    private fun unregisterStateReceiverIfNeeded() {
        if (!stateReceiverRegistered) return
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        stateReceiverRegistered = false
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
}
