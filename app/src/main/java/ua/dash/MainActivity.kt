package ua.dash

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ua.dash.data.DtekRepo
import ua.dash.ui.Dashboard
import ua.dash.ui.SettingsScreen
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    private val vm: DashViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            var showSettings by remember { mutableStateOf(false) }
            var battery by remember { mutableStateOf(readBattery()) }

            LaunchedEffect(state.nightDim) {
                while (true) {
                    battery = readBattery()
                    applyBrightness(state.nightDim)
                    delay(60_000)
                }
            }

            if (showSettings) {
                BackHandler { showSettings = false; hideBars() }
                SettingsScreen(vm, state, openUrl = ::openUrl, onClose = { showSettings = false; hideBars() })
            } else {
                Dashboard(
                    state = state,
                    battery = battery,
                    onSettings = { showSettings = true },
                    onRefresh = { vm.refreshAll() },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshAll()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun readBattery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        return pct to charging
    }

    private fun applyBrightness(nightDim: Boolean) {
        val h = ZonedDateTime.now(DtekRepo.KYIV).hour
        val night = nightDim && (h >= 23 || h < 7)
        val lp = window.attributes
        val target = if (night) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (lp.screenBrightness != target) {
            lp.screenBrightness = target
            window.attributes = lp
        }
    }
}
