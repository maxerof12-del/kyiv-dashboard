package ua.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import ua.dash.DashViewModel
import ua.dash.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: DashViewModel, state: UiState, openUrl: (String) -> Unit, onClose: () -> Unit) {
    val p = if (state.lightTheme) LightPalette else DarkPalette
    val scheme = if (state.lightTheme) lightColorScheme() else darkColorScheme()
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides p) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(p.bg)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Налаштування", color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onClose) { Text("Готово") }
                }

                state.loginMessage?.let {
                    Text(
                        it, color = p.text, fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.card2)
                            .clickable { vm.clearLoginMessage() }
                            .padding(12.dp)
                    )
                }

                Section("Годування (бот на телефоні)", p) {
                    var host by remember { mutableStateOf(vm.prefs.botHost) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Показувати годування", color = p.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(checked = state.feedEnabled, onCheckedChange = { vm.setFeedEnabled(it) })
                    }
                    Text(
                        "Дані беруться з вашого бота «Кормление» (Termux на телефоні). Телефон і планшет мають бути в одному Wi-Fi. " +
                            "Адресу телефону дашборд знаходить сам.",
                        color = p.muted, fontSize = 13.sp
                    )
                    Text(
                        when {
                            state.botSearching -> "Шукаю…"
                            state.botFeed.data != null -> "Підключено: ${state.botFeed.data.host} ✓"
                            else -> state.botFeed.error ?: "Ще не підключено"
                        },
                        color = if (state.botFeed.data != null) p.good else p.muted, fontSize = 14.sp
                    )
                    Text(
                        "IP цього планшета: " + ua.dash.data.BotFeedRepo.myIps().joinToString().ifBlank { "немає Wi-Fi" } +
                            ". IP телефону має починатися так само (перші три числа).",
                        color = p.muted, fontSize = 13.sp
                    )
                    var code by remember { mutableStateOf(vm.prefs.botCode) }
                    Text(
                        "Код бота: його показує Termux при запуску бота («Код для дашборда: …») " +
                            "або бот у Telegram на команду /dash. З кодом дані йдуть через інтернет — Wi-Fi не важливий.",
                        color = p.muted, fontSize = 13.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = code, onValueChange = { code = it },
                            label = { Text("Код бота") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { vm.setBotCode(code) }) { Text("Зберегти код") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = host, onValueChange = { host = it },
                            label = { Text("IP телефону (необов'язково)") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { vm.setBotHost(host) }) { Text("Зберегти") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = { vm.findBot() }) { Text("Знайти автоматично") }
                    }
                }

                Section("Карта", p) {
                    var url by remember { mutableStateOf(state.mapUrl) }
                    Text(
                        "Будь-який сайт з картою. За замовчуванням — офіційна карта «Повітряна тривога».",
                        color = p.muted, fontSize = 13.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it },
                            label = { Text("Адреса сайту карти") },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { vm.setMapUrl(url) }) { Text("Зберегти") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = { url = ua.dash.data.Prefs.DEFAULT_MAP; vm.setMapUrl(url) }) { Text("Типова") }
                    }
                }

                Section("Адреса для графіка ДТЕК", p) {
                    var street by remember { mutableStateOf(vm.prefs.street) }
                    var house by remember { mutableStateOf(vm.prefs.house) }
                    val streets = remember(state.power.data) {
                        try {
                            val a = JSONArray(vm.prefs.streetsCache)
                            List(a.length()) { a.getString(it) }
                        } catch (e: Exception) { emptyList() }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = street, onValueChange = { street = it },
                            label = { Text("Вулиця (як на сайті ДТЕК)") },
                            singleLine = true, modifier = Modifier.weight(2f)
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = house, onValueChange = { house = it },
                            label = { Text("Будинок") },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { vm.saveAddress(street, house) }) { Text("Зберегти") }
                    }
                    val q = street.trim().lowercase()
                    val matches = if (q.length >= 3 && streets.none { it == street.trim() })
                        streets.filter { it.lowercase().contains(q) }.take(8) else emptyList()
                    if (matches.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            matches.forEach { s ->
                                Text(
                                    s, color = p.text, fontSize = 14.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(p.card2)
                                        .clickable { street = s }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    val group = vm.prefs.dtekGroup
                    Text(
                        if (group.isNotBlank()) "Група: ${state.power.data?.groupName ?: group} ($group)" else "Група ще не визначена",
                        color = p.muted, fontSize = 14.sp
                    )
                    state.power.error?.let { Text(it, color = p.bad, fontSize = 14.sp) }
                }

                Section("Повітряна тривога", p) {
                    var region by remember { mutableStateOf(vm.prefs.alertRegion) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("м. Київ", "Київська область").forEach { r ->
                            val sel = region == r
                            Button(
                                onClick = { region = r; vm.setAlertRegion(r) },
                                colors = if (sel) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                            ) { Text(r) }
                        }
                    }
                }

                Section("Екран", p) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Світла тема", color = p.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(checked = state.lightTheme, onCheckedChange = { vm.setLightTheme(it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Нічне затемнення (23:00–07:00)", color = p.text, fontSize = 16.sp)
                            Text("Мінімальна яскравість вночі", color = p.muted, fontSize = 13.sp)
                        }
                        Switch(checked = state.nightDim, onCheckedChange = { vm.setNightDim(it) })
                    }
                    Text(
                        "Порада: щоб дашборд запускався сам після перезавантаження, у налаштуваннях Android " +
                            "оберіть Kyiv Dashboard як «Головний екран» (Застосунки → Застосунки за замовчуванням → Головний екран).",
                        color = p.muted, fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, p: Palette, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        content()
    }
}
