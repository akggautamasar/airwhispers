package com.airwhispers.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.airwhispers.AppContainer
import com.airwhispers.R
import com.airwhispers.core.AppLog

/**
 * One-swipe arming: the Quick Settings tile is the fastest honest way to start
 * Call Assist from inside another app (for example, a second before answering a
 * WhatsApp call).
 */
@RequiresApi(Build.VERSION_CODES.N)
class CallAssistTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val container = AppContainer.peekOrNull() ?: return
        val running = container.settingsStore.callAssist.value.enabled
        val intent = Intent(this, CallAssistService::class.java).apply {
            action = if (running) Notifier.ACTION_STOP else Notifier.ACTION_START
        }
        if (running) {
            startService(intent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        AppLog.i("Tile", "toggled", "running" to running)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val container = AppContainer.peekOrNull()
        val active = container?.settingsStore?.callAssist?.value?.enabled == true
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.call_assist_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) getString(R.string.call_assist_active) else getString(R.string.call_assist_inactive)
        }
        tile.updateTile()
    }
}
