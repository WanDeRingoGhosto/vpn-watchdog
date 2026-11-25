package com.wdrg.blackfoxguardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

@RequiresApi(Build.VERSION_CODES.N)
class WatchdogTileService : TileService() {

    private var stateReceiverRegistered = false

    private val prefs by lazy {
        getSharedPreferences("watchdog_prefs", Context.MODE_PRIVATE)
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WatchdogService.ACTION_STATE_UPDATED) {
                syncTileState()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        registerStateReceiverIfNeeded()
        syncTileState()
    }

    override fun onStopListening() {
        unregisterStateReceiverIfNeeded()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val currentlyEnabled = prefs.getBoolean("is_monitoring", true)
        val nextEnabled = !currentlyEnabled

        prefs.edit().putBoolean("is_monitoring", nextEnabled).apply()

        val commandAction = if (nextEnabled) {
            WatchdogService.ACTION_START_MONITORING
        } else {
            WatchdogService.ACTION_STOP_MONITORING
        }

        val serviceIntent = Intent(this, WatchdogService::class.java).apply {
            action = commandAction
        }

        if (nextEnabled) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val stateIntent = Intent(WatchdogService.ACTION_STATE_UPDATED).apply {
            setPackage(packageName)
            putExtra(WatchdogService.EXTRA_IS_MONITORING, nextEnabled)
        }
        sendBroadcast(stateIntent)

        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val enabled = prefs.getBoolean("is_monitoring", true)
        val interval = prefs.getInt("interval_seconds", 5).coerceIn(1, 15)

        tile.icon = Icon.createWithResource(this, R.drawable.ic_fox_tile)
        tile.label = "Black Fox Guardian"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (enabled) "Активен • ${interval} сек" else "Выключен"
        }
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
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
}
