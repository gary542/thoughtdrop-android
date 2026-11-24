package org.thoughtdrop.notebook

import android.content.Intent
import android.os.Build
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Notebook(app: Application) : AndroidViewModel(app) {
    val store = ThoughtStorage.from(app)
    var query by mutableStateOf("")
    var total by mutableIntStateOf(0)
        private set
    private val queryFlow = kotlinx.coroutines.flow.MutableStateFlow("")
    fun search(value: String) { query = value; queryFlow.value = value }
    var playing by mutableStateOf<String?>(null)
        private set
    var notes by mutableStateOf(emptyList<ThoughtNote>())
        private set
    var message by mutableStateOf<String?>(null)
    var hasKey by mutableStateOf(!store.prefs.getString("key", "").isNullOrBlank())
        private set
    private val player = VoicePlayer { playing = null }
    init {
        viewModelScope.launch {
            val initial = withContext(Dispatchers.IO) { store.restore(); store.all() }
            initial.filter { !it.isTranscribed && it.errorMessage.isBlank() }.forEach { retry(it.id) }
            kotlinx.coroutines.flow.combine(store.changes, queryFlow) { _, text -> text }.collectLatest { text ->
                val (size, rows) = withContext(Dispatchers.IO) { store.all().size to store.search(text) }
                total = size
                notes = rows
            }
        }
    }
    fun saveKey(key: String) {
        store.prefs.edit().putString("key", key.trim()).apply()
        hasKey = key.isNotBlank()
        if (hasKey) viewModelScope.launch(Dispatchers.IO) {
            store.all().filterNot { it.isTranscribed }.forEach { retry(it.id) }
        }
    }
    fun retry(id: String) = TranscriptionWorker.queue(getApplication<Application>(), id)
    fun record() {
        try {
            val app = getApplication<Application>()
            if (RecorderService.started.value != 0L) RecorderService.stop(app)
            else { stopPlaying(); RecorderService.start(app) }
        } catch (_: Exception) { message = "Open ThoughtDrop and try recording again." }
    }
    fun edit(note: ThoughtNote, title: String, transcript: String) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.edit(note.id, title, transcript) } }
            catch (_: Exception) { message = "Could not save your edits." }
        }
    }
    fun listen(note: ThoughtNote) {
        if (playing == note.id) { stopPlaying(); return }
        try { player.play(note.audioPath); playing = note.id }
        catch (_: Exception) { playing = null; message = "This recording could not be played." }
    }
    fun stopPlaying() { player.stop(); playing = null }
    fun remove(note: ThoughtNote) {
        if (playing == note.id) stopPlaying()
        WorkManager.getInstance(getApplication<Application>()).cancelUniqueWork("transcribe-${note.id}")
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.delete(note.id) } }
            catch (_: Exception) { message = "Could not delete the note." }
        }
    }
    override fun onCleared() { stopPlaying() }
}

class MainActivity : ComponentActivity() {
    private val notebook by viewModels<Notebook>()
    private var captureRequested by mutableIntStateOf(0)
    private fun requestCapture(intent: Intent) {
        if (intent.component?.className == "$packageName.QuickCapture" ||
            (intent.action == Intent.ACTION_MAIN && notebook.store.prefs.getBoolean("captureOnLaunch", false))) {
            captureRequested++
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestCapture(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestCapture(intent)
        setContent {
            MaterialTheme(colorScheme = Palette) {
                NotebookScreen(notebook, captureRequested) { captureRequested = 0 }
            }
        }
    }
    override fun onStop() {
        super.onStop()
        notebook.stopPlaying()
    }
}

private val Mint = Color(0xFFB8EFC8)
private val Ink = Color(0xFF10251D)
private val Palette = darkColorScheme(primary = Mint, onPrimary = Ink,
    background = Color(0xFF101815), surface = Color(0xFF16211C),
    surfaceVariant = Color(0xFF23322A), onSurfaceVariant = Color(0xFFB6C8BD),
    onBackground = Color(0xFFE7EFE8), onSurface = Color(0xFFE7EFE8))

private fun date(t: Long, format: String) = SimpleDateFormat(format, Locale.getDefault()).format(Date(t))
private fun timer(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookScreen(vm: Notebook, captureRequested: Int, consumeCapture: () -> Unit) {
    val ctx = LocalContext.current
    var settings by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ThoughtNote?>(null) }
    var editing by remember { mutableStateOf<ThoughtNote?>(null) }
    val started by RecorderService.started.collectAsState()
    val serviceMessage by RecorderService.status.collectAsState()
    LaunchedEffect(serviceMessage) { serviceMessage?.let { vm.message = it; RecorderService.status.value = null } }
    val active = started != 0L
    val snacks = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snacks.showSnackbar(it); vm.message = null } }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && RecorderService.started.value == 0L) vm.record()
        else if (!allowed) vm.message = "Microphone permission is needed. You can enable it in Android Settings → Apps → ThoughtDrop."
    }
    val capture = {
        if (active || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.record()
        else request.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(captureRequested) {
        if (captureRequested != 0) {
            consumeCapture()
            if (!active) capture()
        }
    }
    Scaffold(containerColor = Palette.background, snackbarHost = { SnackbarHost(snacks) },
        topBar = {
            TopAppBar(title = { Text("ThoughtDrop", fontFamily = FontFamily.Serif, fontSize = 25.sp) },
                navigationIcon = { Icon(Icons.Default.GraphicEq, null, Modifier.padding(start = 20.dp, end = 6.dp), tint = Mint) },
                actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background))
        },
        bottomBar = {
            var elapsed by remember(started) { mutableLongStateOf(0L) }
            LaunchedEffect(started) {
                while (started != 0L) { elapsed = SystemClock.elapsedRealtime() - started; delay(250) }
            }
            Surface(color = Palette.surface) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(if (active) "Recording" else "Capture a thought", fontFamily = FontFamily.Serif, fontSize = 24.sp)
                        Text(if (active) timer(elapsed) + "  ·  Tap to save" else "Tap the mic to record", style = MaterialTheme.typography.bodySmall, color = Palette.onSurfaceVariant)
                    }
                    FloatingActionButton(onClick = capture, shape = CircleShape, modifier = Modifier.size(64.dp),
                        containerColor = if (active) Color(0xFFFFB7A5) else Mint, contentColor = Ink) {
                        Icon(if (active) Icons.Default.Stop else Icons.Default.Mic,
                            if (active) "Stop and save recording" else "Start recording", Modifier.size(29.dp))
                    }
                }
            }
        }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(date(System.currentTimeMillis(), "EEEE, d MMMM").uppercase(Locale.getDefault()),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.3.sp, color = Mint)
                Text("Your notebook", Modifier.padding(vertical = 8.dp), fontFamily = FontFamily.Serif,
                    fontSize = 40.sp, letterSpacing = (-1).sp)
            }
            if (vm.total > 0 || vm.query.isNotEmpty()) item {
                OutlinedTextField(vm.query, vm::search, Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Search thoughts") },
                    shape = RoundedCornerShape(16.dp))
            }
            val visible = vm.notes
            if (visible.isEmpty()) item {
                Surface(shape = RoundedCornerShape(20.dp), color = Palette.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Icon(Icons.Default.GraphicEq, null, Modifier.size(34.dp), tint = Mint)
                        Spacer(Modifier.height(20.dp))
                        Text(if (vm.total == 0) "Your first thought" else "No matching thoughts",
                            fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 27.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(if (vm.total == 0) "Tap the microphone below. Your voice note will be saved on this phone, even offline." else "Try a different word from the title or transcript.", color = Palette.onSurfaceVariant)
                    }
                }
            }
            items(visible, key = { it.id }) { n ->
                Surface(shape = RoundedCornerShape(18.dp), color = Palette.surface) {
                    Column(Modifier.padding(16.dp)) {
                        Text(date(n.createdAt, "d MMM yyyy  ·  HH:mm"), color = Mint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(n.title, Modifier.padding(vertical = 6.dp), fontSize = 22.sp, fontFamily = FontFamily.Serif)
                        Text(if (n.isTranscribed) n.transcript else n.errorMessage.ifBlank {
                            if (vm.hasKey) "Saved locally · Transcription queued" else "Saved locally · Add a key for transcription"
                        }, color = Palette.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.listen(n) }, enabled = !active) {
                                Icon(if (vm.playing == n.id) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                                Text(if (vm.playing == n.id) "Stop" else "Play")
                            }
                            if (!n.isTranscribed) TextButton(onClick = { if (vm.hasKey) vm.retry(n.id) else settings = true }) {
                                Text(if (!vm.hasKey) "Add key" else "Retry")
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { editing = n }) { Icon(Icons.Default.Edit, "Edit ${n.title}") }
                            IconButton(onClick = { deleting = n }) { Icon(Icons.Default.DeleteOutline, "Delete ${n.title}") }
                        }
                    }
                }
            }
        }
    }
    if (settings) KeyDialog(vm, onClose = { settings = false })
    editing?.let { note ->
        var title by rememberSaveable(note.id) { mutableStateOf(note.title) }
        var transcript by rememberSaveable(note.id) { mutableStateOf(note.transcript) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit thought") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(transcript, { transcript = it }, label = { Text("Transcript") }, maxLines = 6)
            } }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                vm.edit(note, title, transcript); editing = null
            }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    deleting?.let { note ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this thought?") },
            text = { Text("The recording and its transcript will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { vm.remove(note); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun KeyDialog(vm: Notebook, onClose: () -> Unit) {
    val context = LocalContext.current
    var auto by remember { mutableStateOf(vm.store.prefs.getBoolean("captureOnLaunch", false)) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var key by remember { mutableStateOf(vm.store.prefs.getString("key", "").orEmpty()) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Settings", fontFamily = FontFamily.Serif) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add your Gemini API key to automatically send saved recordings to Google for titles and transcripts when online. Leave it blank for local recording only.")
            OutlinedTextField(key, { key = it }, label = { Text("Gemini API key") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Record immediately on launch", Modifier.weight(1f))
                Switch(checked = auto, onCheckedChange = { auto = it })
            }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                TextButton(onClick = { notif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Enable recording notification") }
            }
        } }, confirmButton = { TextButton(onClick = {
            vm.store.prefs.edit().putBoolean("captureOnLaunch", auto).apply()
            vm.saveKey(key); onClose() }) { Text("Save settings") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
