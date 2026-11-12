package com.persona.chat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import coil.*
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dao = AppDb.get(this).dao()
        Coil.setImageLoader(ImageLoader.Builder(this).components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build())
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App(dao) } } }
    }
}

@Composable fun App(dao: AppDao) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(bottomBar = { NavigationBar { listOf("Chat", "Triggers", "Schedules").forEachIndexed { i, t ->
        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) }) } } }) { p ->
        Box(Modifier.padding(p).fillMaxSize()) { when (tab) { 0 -> ChatScreen(dao); 1 -> TriggerScreen(dao); else -> ScheduleScreen(dao) } }
    }
}

@Composable fun ChatScreen(dao: AppDao) {
    val msgs by dao.messages().collectAsState(emptyList())
    val rules by dao.triggers().collectAsState(emptyList())
    var text by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val list = rememberLazyListState()
    LaunchedEffect(msgs.size, typing) { list.animateScrollToItem(maxOf(0, msgs.size - 1 + if (typing) 1 else 0)) }
    fun send() {
        val t = text.trim(); if (t.isEmpty()) return; text = ""
        scope.launch {
            dao.insert(Msg(fromMe = true, text = t))
            val (r, reply) = Engine.choose(rules, t) ?: return@launch
            typing = true; delay(Engine.delayFor(r))
            dao.insert(Msg(fromMe = false, text = reply, triggerId = r.id, media = r.media.lines().filter { it.isNotBlank() }.randomOrNull())); typing = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), list, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(msgs, key = { it.id }) { Bubble(it.text, it.fromMe, it.ts, it.media) }
            if (typing) item { TypingDots() }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4)
            Spacer(Modifier.width(8.dp)); Button(onClick = { send() }) { Text("Send") }
        }
    }
}

@Composable fun Bubble(t: String, mine: Boolean, ts: Long, m: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(18.dp), color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (m != null) AsyncImage(File(m), null, Modifier.sizeIn(maxWidth = 220.dp, maxHeight = 260.dp).clip(RoundedCornerShape(12.dp)))
                if (t.isNotEmpty()) Text(t); Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable fun TypingDots() {
    var n by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) { while (true) { delay(350); n = n % 3 + 1 } }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text("●".repeat(n) + "○".repeat(3 - n), Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable fun TriggerScreen(dao: AppDao) {
    val rules by dao.triggers().collectAsState(emptyList())
    var editing by remember { mutableStateOf<TriggerRule?>(null) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { editing = r }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                            Text("${r.mode} · ${r.keywords.lines().joinToString(", ")} · ${r.responses.lines().count { it.isNotBlank() }} replies", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(r.enabled, { scope.launch { dao.save(r.copy(enabled = it)) } })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { editing = TriggerRule() }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+ Trigger") }
    }
    editing?.let { init ->
        Editor(init, onSave = { scope.launch { dao.save(it) }; editing = null },
            onDelete = { scope.launch { dao.delete(init) }; editing = null }, onClose = { editing = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Editor(init: TriggerRule, onSave: (TriggerRule) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(init.name) }
    var kw by remember { mutableStateOf(init.keywords) }
    var mode by remember { mutableStateOf(init.mode) }
    var lo by remember { mutableStateOf((init.minDelayMs / 1000.0).toString()) }
    var hi by remember { mutableStateOf((init.maxDelayMs / 1000.0).toString()) }
    var pr by remember { mutableStateOf(init.priority.toString()) }
    var cs by remember { mutableStateOf(init.caseSensitive) }
    var ip by remember { mutableStateOf(init.ignorePunct) }
    var nr by remember { mutableStateOf(init.noRepeat) }
    var test by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val media = remember { mutableStateListOf<String>().apply { addAll(init.media.lines().filter { it.isNotBlank() }) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) Media.import(ctx, u)?.let { if (it !in media) media.add(it) } }
    val resp = remember { mutableStateListOf<String>().apply { addAll(init.responses.lines().filter { it.isNotBlank() }.ifEmpty { listOf("") }) } }
    fun build() = init.copy(name = name, keywords = kw, mode = mode, responses = resp.filter { it.isNotBlank() }.joinToString("\n"), media = media.joinToString("\n"),
        minDelayMs = ((lo.toDoubleOrNull() ?: 1.0) * 1000).toLong(), maxDelayMs = ((hi.toDoubleOrNull() ?: 3.0) * 1000).toLong(),
        priority = pr.toIntOrNull() ?: 0, caseSensitive = cs, ignorePunct = ip, noRepeat = nr)
    AlertDialog(onDismissRequest = onClose, title = { Text(if (init.id == 0L) "New trigger" else "Edit trigger") },
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("Save") } },
        dismissButton = { Row { if (init.id != 0L) TextButton(onClick = onDelete) { Text("Delete") }; TextButton(onClick = onClose) { Text("Cancel") } } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(kw, { kw = it }, label = { Text("Keywords / aliases (one per line)") })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EXACT" to "Exact", "WORD" to "Whole word", "PHRASE" to "Phrase", "CONTAINS" to "Contains").forEach { (k, l) ->
                    FilterChip(mode == k, { mode = k }, label = { Text(l) }) } }
            Text("Replies (one chosen at random)", style = MaterialTheme.typography.labelLarge)
            resp.forEachIndexed { i, s -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(s, { resp[i] = it }, Modifier.weight(1f), singleLine = true)
                TextButton(onClick = { if (resp.size > 1) resp.removeAt(i) else resp[0] = "" }) { Text("−") } } }
            OutlinedButton(onClick = { resp.add("") }) { Text("+ Add reply") }
            Text("GIFs / images (one chosen at random)", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                media.forEachIndexed { i, p -> Box { AsyncImage(File(p), null, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)))
                    TextButton(onClick = { media.removeAt(i) }, modifier = Modifier.align(Alignment.TopEnd)) { Text("✕") } } } }
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("+ Add GIF / image") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(lo, { lo = it }, Modifier.weight(1f), label = { Text("Min delay s") }, singleLine = true)
                OutlinedTextField(hi, { hi = it }, Modifier.weight(1f), label = { Text("Max delay s") }, singleLine = true) }
            OutlinedTextField(pr, { pr = it }, label = { Text("Priority (higher wins)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cs, { cs = it }); Text("Case-sensitive") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ip, { ip = it }); Text("Ignore punctuation") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(nr, { nr = it }); Text("Avoid repeating last reply") }
            OutlinedTextField(test, { test = it }, label = { Text("Test message") }, singleLine = true)
            if (test.isNotEmpty()) Text(if (Engine.matches(build(), test)) "✔ Matches ($mode)" else "✘ No match")
        } })
}

@Composable fun ScheduleScreen(dao: AppDao) {
    val list by dao.schedules().collectAsState(emptyList())
    var editing by remember { mutableStateOf<Schedule?>(null) }
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    fun save(s: Schedule) { scope.launch { val id = dao.saveSchedule(s); Scheduler.schedule(ctx, s.copy(id = if (s.id == 0L) id else s.id)) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Delivery uses Android's inexact alarms: no extra permission needed, but battery saver/Doze can delay messages by minutes. Delivery is not guaranteed.", style = MaterialTheme.typography.bodySmall) }
            items(list, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { editing = s }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                            Text("%02d:%02d · %s · %s".format(s.hour, s.minute, if (s.days == 127) "Every day" else listOf("Su","Mo","Tu","We","Th","Fr","Sa").filterIndexed { i, _ -> (s.days shr i) and 1 == 1 }.joinToString(" "), s.text), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(s.enabled, { save(s.copy(enabled = it)) })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { editing = Schedule() }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+ Schedule") }
    }
    editing?.let { init -> SchedEditor(init, { save(it); editing = null },
        { scope.launch { Scheduler.cancel(ctx, init.id); dao.deleteSchedule(init) }; editing = null }, { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SchedEditor(init: Schedule, onSave: (Schedule) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(init.name) }; var text by remember { mutableStateOf(init.text) }
    var h by remember { mutableStateOf(init.hour.toString()) }; var m by remember { mutableStateOf(init.minute.toString()) }
    var days by remember { mutableStateOf(init.days) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (init.id == 0L) "New schedule" else "Edit schedule") },
        confirmButton = { TextButton(onClick = { onSave(init.copy(name = name, text = text, hour = (h.toIntOrNull() ?: 7).coerceIn(0, 23),
            minute = (m.toIntOrNull() ?: 0).coerceIn(0, 59), days = if (days == 0) 127 else days)) }) { Text("Save") } },
        dismissButton = { Row { if (init.id != 0L) TextButton(onClick = onDelete) { Text("Delete") }; TextButton(onClick = onClose) { Text("Cancel") } } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(text, { text = it }, label = { Text("Message") })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(h, { h = it }, Modifier.weight(1f), label = { Text("Hour (0-23)") }, singleLine = true)
                OutlinedTextField(m, { m = it }, Modifier.weight(1f), label = { Text("Minute") }, singleLine = true) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Su","Mo","Tu","We","Th","Fr","Sa").forEachIndexed { i, l -> FilterChip(((days shr i) and 1) == 1, { days = days xor (1 shl i) }, label = { Text(l) }) } }
        } })
}
