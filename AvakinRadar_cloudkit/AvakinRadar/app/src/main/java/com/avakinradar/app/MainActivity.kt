package com.avakinradar.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.avakinradar.app.data.MonitorSettings
import com.avakinradar.app.data.MonitorStatus
import com.avakinradar.app.data.CheckState
import com.avakinradar.app.data.CheckSummary
import com.avakinradar.app.data.SnapshotRepository
import com.avakinradar.app.model.ChangeType
import com.avakinradar.app.model.OfferChange
import com.avakinradar.app.model.OfferCategory
import com.avakinradar.app.model.StoreOffer
import com.avakinradar.app.monitor.MonitorScheduler
import com.avakinradar.app.monitor.MonitorWorker
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            RadarApp(onEnableMonitoring = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            })
        }
    }
}

private val Ink = Color(0xFF101020)
private val Violet = Color(0xFF8A5CFF)
private val Blue = Color(0xFF4D9CFF)
private val CardColor = Color(0xFF1C1B32)

@Composable
private fun RadarApp(onEnableMonitoring: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { SnapshotRepository(context) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var requestedStoreUrl by remember { mutableStateOf("https://store.avakin.com/") }
    var revision by remember { mutableIntStateOf(0) }
    var storeLoading by remember { mutableStateOf(false) }
    var storeError by remember { mutableStateOf<String?>(null) }
    var checkRunning by remember { mutableStateOf(false) }
    var checkPhase by remember { mutableStateOf("") }
    var terminalWorkMessage by remember { mutableStateOf<String?>(null) }
    var queueWaiting by remember { mutableStateOf(false) }
    var queueWaitStartedAt by remember { mutableLongStateOf(0L) }
    var queueWaitSeconds by remember { mutableIntStateOf(0) }
    val webView = remember(context) { WebView(context) }

    DisposableEffect(repository.preferences) {
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            mainHandler.post { revision++ }
        }
        repository.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { repository.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(webView) {
        configureWebView(context, webView, { storeLoading = it }, { storeError = it })
        onDispose {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    LaunchedEffect(context) {
        MonitorScheduler.cancelLegacyManual(context)
        combine(
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MonitorScheduler.MANUAL_WORK),
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MonitorScheduler.PERIODIC_WORK)
        ) { manual, periodic ->
            val requestedId = repository.status().lastCheck?.workId
            val currentManual = requestedId?.let { id -> manual.firstOrNull { it.id.toString() == id } }
                ?: manual.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            val running = currentManual?.takeIf { it.state == WorkInfo.State.RUNNING }
                ?: periodic.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val queued = currentManual?.takeIf { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            val completed = currentManual?.takeIf { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED || it.state == WorkInfo.State.SUCCEEDED }
            Triple(running, queued, completed)
        }.collect { (running, queued, completed) ->
            when {
                running != null -> {
                    checkRunning = true
                    queueWaiting = false
                    checkPhase = running.progress.getString(MonitorScheduler.PROGRESS_PHASE) ?: "Проверка выполняется…"
                    terminalWorkMessage = null
                }
                queued != null -> {
                    checkRunning = true
                    if (!queueWaiting) {
                        queueWaitStartedAt = repository.status().lastCheck?.checkedAt ?: System.currentTimeMillis()
                        queueWaitSeconds = 0
                    }
                    queueWaiting = true
                    val reason = if (Build.VERSION.SDK_INT >= 31 && queued.runAttemptCount > 0) workStopReason(queued.stopReason) else null
                    checkPhase = "Android принял задачу (${queued.state}; попытка ${queued.runAttemptCount + 1})." + (reason?.let { " Причина ожидания: $it" } ?: "")
                    terminalWorkMessage = null
                }
                completed != null -> {
                    checkRunning = false
                    queueWaiting = false
                    checkPhase = ""
                    val currentCheck = repository.status().lastCheck
                    terminalWorkMessage = null
                    if (currentCheck?.state == CheckState.RUNNING) {
                        terminalWorkMessage = when (completed.state) {
                            WorkInfo.State.FAILED -> completed.outputData.getString(MonitorWorker.ERROR_MESSAGE) ?: "Фоновая задача завершилась без результата."
                            WorkInfo.State.CANCELLED -> "Проверка была отменена до получения результата."
                            else -> "Задача завершилась, но исполнитель не сохранил итог проверки."
                        }
                        val message = terminalWorkMessage ?: "Задача завершилась, но исполнитель не сохранил итог проверки."
                        val failed = CheckSummary(System.currentTimeMillis(), CheckState.FAILED, error = message)
                        repository.saveFailure(failed)
                        repository.appendEvent(com.avakinradar.app.data.CheckEvent(failed.checkedAt, "worker_terminal_without_result", message))
                        revision++
                    }
                }
                else -> { checkRunning = false; queueWaiting = false; checkPhase = "" }
            }
        }
    }
    LaunchedEffect(queueWaiting, queueWaitStartedAt) {
        while (queueWaiting) {            delay(5_000)
            queueWaitSeconds = ((System.currentTimeMillis() - queueWaitStartedAt) / 1_000).toInt()
        }
    }
    val settings = remember(revision) { repository.settings() }
    val offers = remember(revision) { repository.loadOffers().orEmpty() }
    val history = remember(revision) { repository.history() }
    val status = remember(revision) { repository.status() }

    LaunchedEffect(status.lastCheck?.checkedAt, status.lastCheck?.state, checkRunning) {
        val pending = status.lastCheck?.takeIf { it.state == CheckState.RUNNING } ?: return@LaunchedEffect
        if (!checkRunning && pending.events.any { it.stage == "button_pressed" }) {
            delay(25_000)
            val latest = repository.status().lastCheck
            if (!checkRunning && latest?.state == CheckState.RUNNING && latest.checkedAt == pending.checkedAt) {
                val message = "WorkManager не сообщил о запуске задачи в течение 25 секунд. Возможна системная блокировка фоновой работы; повторный снимок не создавался."
                val failed = CheckSummary(System.currentTimeMillis(), CheckState.FAILED, error = message, workId = pending.workId)
                repository.saveFailure(failed)
                repository.appendEvent(com.avakinradar.app.data.CheckEvent(failed.checkedAt, "worker_not_started", message))
                terminalWorkMessage = message
                revision++
            }
        }
    }

    BackHandler(enabled = selectedTab == 3) {
        if (webView.canGoBack()) webView.goBack() else selectedTab = 0
    }

    fun updateSettings(next: MonitorSettings) {
        repository.updateSettings(next)
        when {
            next.monitoringEnabled && !settings.monitoringEnabled -> {
                MonitorScheduler.enable(context, next.intervalHours)
                if (next.notifyNew || next.notifyChanges || next.notifyRemoved) onEnableMonitoring()
            }
            next.monitoringEnabled && next.intervalHours != settings.intervalHours ->
                MonitorScheduler.schedulePeriodic(context, next.intervalHours)
            !next.monitoringEnabled && settings.monitoringEnabled -> MonitorScheduler.disable(context)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Violet, secondary = Blue, background = Ink, surface = CardColor)) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                if (selectedTab != 3) {
                NavigationBar(containerColor = CardColor) {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Radar, null) }, label = { Text("Обзор") })
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("История") })
                    NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") })
                    NavigationBarItem(selected = false, onClick = {
                        selectedTab = 3
                        requestedStoreUrl = "https://store.avakin.com/"
                    }, icon = { Icon(Icons.Default.ShoppingBag, null) }, label = { Text("Магазин") })
                }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (selectedTab != 3) Header()
                when (selectedTab) {
                    0 -> Overview(
                        settings = settings,
                        offers = offers,
                        status = status,
                        checkRunning = checkRunning,
                        checkPhase = checkPhase,
                        queueWaiting = queueWaiting,
                        queueWaitSeconds = queueWaitSeconds,
                        terminalWorkMessage = terminalWorkMessage,
                        onSettingsChange = ::updateSettings,
                        onCheckNow = {
                            if (!checkRunning) {
                                terminalWorkMessage = null
                                runCatching {
                                    MonitorScheduler.enqueueManual(context) { workId ->
                                        val requestedAt = System.currentTimeMillis()
                                        repository.startCheckRequest(requestedAt, workId)
                                        repository.appendEvent(com.avakinradar.app.data.CheckEvent(System.currentTimeMillis(), "work_enqueued", "Ручная приоритетная задача создана. ID: $workId"))
                                        checkRunning = true
                                        checkPhase = "Запускаю приоритетную проверку…"
                                    }
                                }.onFailure { error ->
                                    val failure = CheckSummary(System.currentTimeMillis(), CheckState.FAILED, error = error.message ?: "WorkManager не принял задачу проверки.")
                                    repository.saveFailure(failure)
                                    repository.appendEvent(com.avakinradar.app.data.CheckEvent(failure.checkedAt, "enqueue_failed", failure.error.orEmpty()))
                                    checkRunning = false
                                }
                            }
                        },
                        onOpenOffer = { url -> requestedStoreUrl = url; storeError = null; selectedTab = 3 }
                    )
                    1 -> HistoryScreen(history, onOpenOffer = { url -> requestedStoreUrl = url; storeError = null; selectedTab = 3 })
                    2 -> SettingsScreen(settings, onSettingsChange = ::updateSettings, onOpenStore = { requestedStoreUrl = "https://store.avakin.com/"; storeError = null; selectedTab = 3 })
                    else -> StoreScreen(webView, requestedStoreUrl, storeLoading, storeError, onBack = { selectedTab = 0 })
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Radar, contentDescription = null, tint = Violet, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("AVAKIN RADAR", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            Text("Публичные предложения магазина", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Overview(
    settings: MonitorSettings,
    offers: List<StoreOffer>,
    status: MonitorStatus,
    checkRunning: Boolean,
    checkPhase: String,
    queueWaiting: Boolean,
    queueWaitSeconds: Int,
    terminalWorkMessage: String?,
    onSettingsChange: (MonitorSettings) -> Unit,
    onCheckNow: () -> Unit,
    onOpenOffer: (String) -> Unit
) {
    var selectedFilter by remember { mutableStateOf("Все") }
    val filtered = remember(offers, selectedFilter) {
        when (selectedFilter) {
            "Валюта" -> offers.filter { it.category in setOf(OfferCategory.CROWNS, OfferCategory.AVACOINS, OfferCategory.CURRENCY_BUNDLE) }
            "Акции" -> offers.filter { it.webExclusive || it.category == OfferCategory.WEB_EXCLUSIVE }
            else -> offers
        }
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Radar, contentDescription = null, tint = Violet, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Мониторинг магазина", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                        Switch(checked = settings.monitoringEnabled, onCheckedChange = { onSettingsChange(settings.copy(monitoringEnabled = it)) })
                    }
                    Text(if (settings.monitoringEnabled) "Проверки включены · каждые ${settings.intervalHours} ч" else "Периодические проверки выключены", color = if (settings.monitoringEnabled) Color(0xFF71E6B0) else Color.LightGray)
                    Text(
                        when {
                            checkRunning -> checkPhase.ifBlank { "Проверяю публичный каталог магазина…" } +
                                if (queueWaiting && queueWaitSeconds >= 60) " Ожидание уже $queueWaitSeconds сек.; ручная задача не ждёт ограничения сети. Возможно, запуск задерживает управление фоном MIUI." else ""
                            terminalWorkMessage != null -> terminalWorkMessage
                            else -> statusText(status)
                        },
                        color = if (status.lastError != null && !checkRunning) Color(0xFFFFCC80) else Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (checkRunning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCheckNow, enabled = !checkRunning, colors = ButtonDefaults.buttonColors(containerColor = Violet), modifier = Modifier.weight(1f)) {
                            if (checkRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Text("Проверить предложения")
                        }
                        OutlinedButton(onClick = { onOpenOffer("https://store.avakin.com/") }, modifier = Modifier.weight(1f)) { Text("Открыть магазин") }
                    }
                }
            }
        }
        status.lastCheck?.let { lastCheck ->
            item { CheckSummaryCard(lastCheck) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Предложения", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (status.lastSuccessAt == null) "—" else "${offers.size}", color = Blue, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("Все", "Валюта", "Акции").forEach { option ->                    FilterChip(selected = selectedFilter == option, onClick = { selectedFilter = option }, label = { Text(option) })
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(18.dp)) {
                    Text(
                        if (status.lastError != null) "Нет проверенных данных. Последний снимок сохранён; попробуйте повторить проверку позже."
                        else "Пока нет сохранённых предложений. Выполните проверку, чтобы получить данные с официального магазина.",
                        Modifier.padding(16.dp), color = Color.LightGray
                    )
                }
            }
        } else {
            items(filtered, key = StoreOffer::id) { offer -> OfferCard(offer, onOpen = { onOpenOffer(offer.url) }) }
        }
        item {
            Text(
                "Источник — общедоступная страница store.avakin.com. Приложение не входит в аккаунт, не проходит CAPTCHA и не использует закрытые API.",
                color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun CheckSummaryCard(result: CheckSummary) {
    var showEvents by remember(result.checkedAt) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Результат последней проверки", color = Color.White, fontWeight = FontWeight.Bold)
            Text("${formatTime(result.checkedAt)} · ${if (result.state == CheckState.SUCCEEDED) "успешно" else if (result.state == CheckState.RUNNING) "выполняется" else "ошибка"}", color = if (result.state == CheckState.SUCCEEDED) Color(0xFF71E6B0) else Color(0xFFFFCC80))
            if (result.state == CheckState.SUCCEEDED) {
                Text("Получено предложений: ${result.receivedCount}", color = Color.LightGray)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CheckMetric("Новые", result.newCount, Modifier.weight(1f))
                    CheckMetric("Изменились", result.changedCount, Modifier.weight(1f))
                    CheckMetric("Исчезли", result.removedCount, Modifier.weight(1f))
                    CheckMetric("Без изменений", result.unchangedCount, Modifier.weight(1f))
                }
            } else if (result.state == CheckState.FAILED) {
                Text(result.error ?: "Магазин не вернул результат.", color = Color(0xFFFFCC80))
                Text("Предыдущий корректный снимок сохранён.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Каталог загружается; результат появится здесь.", color = Color.LightGray)
            }
            if (result.events.isNotEmpty()) {
                TextButton(onClick = { showEvents = !showEvents }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showEvents) "Скрыть этапы проверки" else "Показать этапы проверки (${result.events.size})", color = Violet)
                }
                if (showEvents) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        result.events.forEach { event ->
                            Text("${formatTime(event.timestamp)} · ${checkStageLabel(event.stage)}", color = Blue, style = MaterialTheme.typography.labelSmall)
                            Text(event.message, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = Blue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(label, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OfferCard(offer: StoreOffer, onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (offer.imageUrl != null) {
                AsyncImage(
                    model = offer.imageUrl, contentDescription = offer.title,
                    modifier = Modifier.size(72.dp).background(Color(0xFF2A2941), RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(offer.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                val contents = buildList {
                    offer.crowns?.let { add("${number(it)} Crowns") }
                    offer.avacoins?.let { add("${number(it)} Avacoins") }
                    offer.bonus?.let { add("+${number(it)} бонус") }
                    offer.inAppAmount?.let { add("в игре ${number(it)}") }
                }
                if (contents.isNotEmpty()) Text(contents.joinToString(" · "), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Text(if (offer.webExclusive) "Эксклюзив магазина · ${categoryLabel(offer.category)}" else categoryLabel(offer.category), color = Blue, style = MaterialTheme.typography.labelSmall)
                offer.expiresAt?.let { Text("До: $it", color = Color.Gray, style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.width(8.dp))
            Text(offer.price ?: "Цена не указана", color = Color(0xFF71E6B0), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HistoryScreen(changes: List<OfferChange>, onOpenOffer: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("История изменений", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
        if (changes.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardColor)) {
                    Text("Записей пока нет. Первый успешный снимок станет базой для сравнения и не создаст искусственных уведомлений.", Modifier.padding(16.dp), color = Color.LightGray)
                }
            }
        }
        items(changes, key = { "${it.detectedAt}-${it.type}-${it.offer.id}" }) { change ->
            Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().clickable { onOpenOffer(change.offer.url) }.padding(14.dp)) {
                    Text(changeLabel(change), color = Blue, style = MaterialTheme.typography.labelMedium)
                    Text(change.offer.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(changeDescription(change), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    Text(formatTime(change.detectedAt), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: MonitorSettings, onSettingsChange: (MonitorSettings) -> Unit, onOpenStore: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Настройки", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Источник данных", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Официальный публичный магазин Avakin Life", color = Color.LightGray)
                    Text("https://store.avakin.com/", color = Blue, modifier = Modifier.clickable(onClick = onOpenStore))
                    Text("Без авторизации, cookies аккаунта, закрытых API и стороннего сервера.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Интервал фоновой проверки", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Работа Android в фоне зависит от энергосбережения и производителя телефона.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6L, 12L, 24L).forEach { hours ->
                            FilterChip(selected = settings.intervalHours == hours, onClick = { onSettingsChange(settings.copy(intervalHours = hours)) }, label = { Text("$hours ч") })
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Уведомления", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                    SettingSwitch("Новые предложения", settings.notifyNew) { onSettingsChange(settings.copy(notifyNew = it)) }
                    SettingSwitch("Изменение цены или состава", settings.notifyChanges) { onSettingsChange(settings.copy(notifyChanges = it)) }
                    SettingSwitch("Исчезновение предложения", settings.notifyRemoved) { onSettingsChange(settings.copy(notifyRemoved = it)) }
                    Text("На Android 13 и новее нужно разрешить уведомления в системном окне при включении мониторинга.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        item { Text("Avakin Radar 1.0 · снимки и история хранятся только на устройстве.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.LightGray, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StoreScreen(webView: WebView, url: String, loading: Boolean, error: String?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {        Row(Modifier.fillMaxWidth().background(CardColor), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White) }
            Text("Магазин Avakin", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Violet)
            IconButton(onClick = { webView.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Обновить", tint = Color.White) }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView },
                update = { view -> if (view.tag != url) { view.tag = url; view.loadUrl(url) } }
            )
            if (error != null) {
                Card(Modifier.align(Alignment.Center).padding(24.dp), colors = CardDefaults.cardColors(containerColor = CardColor)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Магазин не загрузился", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(error, color = Color.LightGray)
                        Button(onClick = { webView.loadUrl(url) }, colors = ButtonDefaults.buttonColors(containerColor = Violet)) { Text("Повторить") }
                    }
                }
            }
        }
    }
}

private fun configureWebView(context: Context, webView: WebView, onLoading: (Boolean) -> Unit, onError: (String?) -> Unit) {
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.javaScriptCanOpenWindowsAutomatically = false
    webView.settings.setSupportMultipleWindows(true)
    webView.settings.useWideViewPort = true
    webView.settings.loadWithOverviewMode = false
    webView.settings.textZoom = 100
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    webView.settings.userAgentString = webView.settings.userAgentString
        .replace("; wv", "", ignoreCase = true)
        .replace("Version/4.0 ", "", ignoreCase = true)
    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) { onLoading(newProgress < 100) }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = WebView(context)
            popup.settings.javaScriptEnabled = true
            popup.settings.domStorageEnabled = true
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme != "https") return true
                    if (uri.host.equals("store.avakin.com", ignoreCase = true)) webView.loadUrl(uri.toString())
                    else openExternalHttps(context, uri.toString(), onError)
                    popupView.destroy()
                    return true
                }
            }
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "https" && uri.host.equals("store.avakin.com", true)) return false
            if (uri.scheme == "https") openExternalHttps(context, uri.toString(), onError)
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            onLoading(true)
            onError(null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // Let the official storefront control its own responsive layout.
            // Avoid forcing document/body widths: this can collapse product grids in portrait.
            onLoading(false)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
            if (request?.isForMainFrame == true) {
                onLoading(false)
                onError(error?.description?.toString() ?: "Проверьте подключение к интернету.")
            }
        }
    }
}

private fun openExternalHttps(context: Context, rawUrl: String, onError: (String?) -> Unit) {
    val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull()
    if (uri?.scheme != "https") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
        onError("Ссылка требует внешний браузер, но Android не нашёл приложение для её открытия.")
    }
}

private fun statusText(status: MonitorStatus): String = when {
    status.lastError != null -> "Последняя проверка: ${status.lastCheckedAt?.let(::formatTime) ?: "—"}. ${status.lastError}"
    status.lastSuccessAt != null -> "Обновлено ${formatTime(status.lastSuccessAt)} · ${status.offerCount} предложений"
    else -> "Ожидает первую проверку публичных данных"
}

private fun categoryLabel(category: OfferCategory): String = when (category) {
    OfferCategory.CROWNS -> "Crowns"
    OfferCategory.AVACOINS -> "Avacoins"
    OfferCategory.CURRENCY_BUNDLE -> "Пакет валюты"
    OfferCategory.WEB_EXCLUSIVE -> "Эксклюзив магазина"
    OfferCategory.OTHER -> "Предложение"
}

private fun changeLabel(change: OfferChange): String = when (change.type) {
    ChangeType.NEW -> "Новое предложение"
    ChangeType.PRICE_CHANGED -> "Изменилась цена"
    ChangeType.CONTENT_CHANGED -> "Изменился состав предложения"
    ChangeType.REMOVED -> "Предложение исчезло"
}

private fun changeDescription(change: OfferChange): String = when (change.type) {
    ChangeType.NEW -> listOfNotNull(change.offer.price, change.offer.crowns?.let { "${number(it)} Crowns" }, change.offer.avacoins?.let { "${number(it)} Avacoins" }).joinToString(" · ")
    ChangeType.PRICE_CHANGED -> "${change.previous?.price ?: "Цена не указана"} → ${change.offer.price ?: "Цена не указана"}"
    ChangeType.CONTENT_CHANGED -> listOfNotNull(change.offer.crowns?.let { "${number(it)} Crowns" }, change.offer.avacoins?.let { "${number(it)} Avacoins" }, change.offer.bonus?.let { "+${number(it)} бонус" }).joinToString(" · ")
    ChangeType.REMOVED -> "Последняя известная цена: ${change.offer.price ?: "не указана"}"
}

private fun number(value: Int): String = String.format(Locale.getDefault(), "%,d", value)
private fun formatTime(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun workStopReason(code: Int): String = when (code) {
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "ограничение сети"
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "ограничение фоновой работы системой"
    WorkInfo.STOP_REASON_QUOTA -> "лимит ускоренных фоновых задач"
    WorkInfo.STOP_REASON_DEVICE_STATE -> "режим энергосбережения или Doze"
    WorkInfo.STOP_REASON_TIMEOUT -> "превышено время выполнения"
    WorkInfo.STOP_REASON_USER -> "задача остановлена пользователем или системой"
    else -> "код остановки Android $code"
}

private fun checkStageLabel(stage: String): String = when (stage) {
    "button_pressed" -> "Запрос проверки"
    "work_enqueued" -> "Задача принята WorkManager"
    "worker_accepted" -> "Исполнитель запущен"
    "parse_started" -> "Загрузка каталога"
    "request_sent" -> "HTTP-запрос отправлен"
    "http_response" -> "Ответ сервера"
    "webview_requested" -> "Открытие WebView"
    "webview_started" -> "WebView начал загрузку"
    "webview_loaded" -> "Страница загружена"
    "cards_parsed" -> "Карточки обработаны"
    "parse_completed" -> "Парсер завершил работу"
    "comparison_started" -> "Сравнение снимков"
    "comparison_completed" -> "Сравнение завершено"
    "result_saved" -> "Снимок сохранён"
    "check_failed", "parse_failed", "webview_http_error", "webview_network_error", "timeout", "enqueue_failed", "worker_stopped", "worker_terminal_without_result" -> "Ошибка"
    else -> stage
}