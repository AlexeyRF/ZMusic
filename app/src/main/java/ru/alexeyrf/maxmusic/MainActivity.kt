package ru.alexeyrf.maxmusic

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.media.audiofx.BassBoost
import android.media.audiofx.PresetReverb
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.launch
import ru.alexeyrf.maxmusic.client.MaxClient
import ru.alexeyrf.maxmusic.client.MusicMessage
import ru.alexeyrf.maxmusic.ui.theme.MAXMusicTheme

import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AudioCacheManager {
    private const val PREFS_NAME = "audio_cache_prefs"
    var maxCacheSizeBytes: Long = 500L * 1024 * 1024 // 500 MB default
    
    data class CacheEntry(val fileId: Long, var lastAccessTime: Long, var accessCount: Int)
    
    // fileId -> CacheEntry
    private val cacheMeta = mutableMapOf<Long, CacheEntry>()
    private var prefs: android.content.SharedPreferences? = null
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        maxCacheSizeBytes = prefs!!.getLong("max_cache_size", 500L * 1024 * 1024)
        
        // load metadata
        val json = prefs!!.getString("meta", "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(json)
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                val item = obj.getJSONObject(key)
                cacheMeta[key.toLong()] = CacheEntry(
                    key.toLong(),
                    item.getLong("lastAccessTime"),
                    item.getInt("accessCount")
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    fun setCacheLimit(context: Context, bytes: Long) {
        maxCacheSizeBytes = bytes
        prefs?.edit()?.putLong("max_cache_size", bytes)?.apply()
        cleanupCache(context)
    }
    
    private fun saveMeta() {
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in cacheMeta) {
                val item = org.json.JSONObject()
                item.put("lastAccessTime", v.lastAccessTime)
                item.put("accessCount", v.accessCount)
                obj.put(k.toString(), item)
            }
            prefs?.edit()?.putString("meta", obj.toString())?.apply()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getTrackFile(context: Context, fileId: Long): File? {
        val file = File(context.cacheDir, "track_$fileId.audio")
        if (file.exists()) {
            val entry = cacheMeta[fileId] ?: CacheEntry(fileId, 0, 0)
            entry.lastAccessTime = System.currentTimeMillis()
            entry.accessCount += 1
            cacheMeta[fileId] = entry
            saveMeta()
            return file
        }
        return null
    }

    suspend fun downloadTrack(context: Context, fileId: Long, urlStr: String): File? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "track_$fileId.audio")
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connect()
            
            if (conn.responseCode != 200) {
                Logger.log("Ошибка скачивания: HTTP ${conn.responseCode}")
                return@withContext null
            }
            
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val entry = cacheMeta[fileId] ?: CacheEntry(fileId, 0, 0)
            entry.lastAccessTime = System.currentTimeMillis()
            entry.accessCount += 1
            cacheMeta[fileId] = entry
            
            cleanupCache(context)
            saveMeta()
            return@withContext file
        } catch (e: Exception) {
            Logger.log("Ошибка скачивания: ${e.localizedMessage}")
            return@withContext null
        }
    }
    
    private fun cleanupCache(context: Context) {
        var totalSize = 0L
        val dir = context.cacheDir
        val files = dir.listFiles { _, name -> name.startsWith("track_") && name.endsWith(".audio") } ?: return
        
        for (f in files) {
            totalSize += f.length()
        }
        
        if (totalSize <= maxCacheSizeBytes) return
        
        // sort by accessCount (ascending), then lastAccessTime (ascending)
        val sortedEntries = cacheMeta.values.sortedWith(compareBy({ it.accessCount }, { it.lastAccessTime }))
        
        for (entry in sortedEntries) {
            if (totalSize <= maxCacheSizeBytes) break
            
            val f = File(dir, "track_${entry.fileId}.audio")
            if (f.exists()) {
                totalSize -= f.length()
                f.delete()
            }
            cacheMeta.remove(entry.fileId)
        }
    }
}

enum class AudioEffectType(val title: String) {
    NORMAL("Обычный"),
    BASSBOOST("BassBoost"),
    SPEED_UP("Speed Up"),
    SLOWED("Slowed"),
    REVERB("Reverb"),
    NIGHTCORE("Nightcore")
}

object AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var bassBoost: BassBoost? = null
    private var reverb: PresetReverb? = null
    
    val activeEffect = mutableStateOf(AudioEffectType.NORMAL)
    val isPlayingState = mutableStateOf(false)
    val currentTrackName = mutableStateOf<String?>(null)
    val currentAlbumArt = mutableStateOf<Bitmap?>(null)
    
    val currentPosition = mutableStateOf(0)
    val duration = mutableStateOf(0)
    private var progressJob: kotlinx.coroutines.Job? = null
    
    val isRepeat = mutableStateOf(false)
    val isShuffle = mutableStateOf(false)
    val isCachedOnly = mutableStateOf(false)
    
    var playlist = listOf<ru.alexeyrf.maxmusic.client.MusicMessage>()
    var currentTrackIndex = -1
    
    var maxClient: ru.alexeyrf.maxmusic.client.MaxClient? = null
    var appContext: Context? = null
    
    fun setPlaylist(context: Context, client: ru.alexeyrf.maxmusic.client.MaxClient, list: List<ru.alexeyrf.maxmusic.client.MusicMessage>, startIndex: Int) {
        appContext = context.applicationContext
        maxClient = client
        playlist = list
        currentTrackIndex = startIndex
    }
    
    fun playCurrentTrack() {
        val track = playlist.getOrNull(currentTrackIndex) ?: return
        val context = appContext ?: return
        val client = maxClient ?: return
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                var file = AudioCacheManager.getTrackFile(context, track.fileId)
                if (file == null) {
                    val url = client.getFileDownloadUrl(track.messageId, track.fileId)
                    if (url != null) {
                        file = AudioCacheManager.downloadTrack(context, track.fileId, url)
                    }
                }
                
                if (file != null) {
                    playUrl(context, file.absolutePath, track.filename)
                }
                
                // Prefetch next track if sequential
                if (!isShuffle.value && !isCachedOnly.value && playlist.size > 1) {
                    val nextIdx = (currentTrackIndex + 1) % playlist.size
                    val nextTrack = playlist[nextIdx]
                    CoroutineScope(Dispatchers.IO).launch {
                        val nextFile = java.io.File(context.cacheDir, "track_${nextTrack.fileId}.audio")
                        if (!nextFile.exists()) {
                            val nextUrl = client.getFileDownloadUrl(nextTrack.messageId, nextTrack.fileId)
                            if (nextUrl != null) {
                                AudioCacheManager.downloadTrack(context, nextTrack.fileId, nextUrl)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log("Ошибка загрузки трека: ${e.localizedMessage}")
            }
        }
    }
    
    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
        currentPosition.value = positionMs
        appContext?.let { updateService(it) }
    }
    
    fun next() {
        if (playlist.isEmpty()) return
        val context = appContext ?: return
        var nextIndex = currentTrackIndex
        var attempts = 0
        
        do {
            if (isShuffle.value) {
                nextIndex = playlist.indices.random()
            } else {
                nextIndex = (nextIndex + 1) % playlist.size
            }
            if (!isCachedOnly.value) break
            
            val track = playlist[nextIndex]
            if (java.io.File(context.cacheDir, "track_${track.fileId}.audio").exists()) break
            
            attempts++
            if (attempts >= playlist.size) break
        } while (true)
        
        currentTrackIndex = nextIndex
        playCurrentTrack()
    }
    
    fun prev() {
        if (playlist.isEmpty()) return
        val context = appContext ?: return
        var nextIndex = currentTrackIndex
        var attempts = 0
        
        do {
            if (isShuffle.value) {
                nextIndex = playlist.indices.random()
            } else {
                nextIndex = if (nextIndex - 1 < 0) playlist.size - 1 else nextIndex - 1
            }
            if (!isCachedOnly.value) break
            
            val track = playlist[nextIndex]
            if (java.io.File(context.cacheDir, "track_${track.fileId}.audio").exists()) break
            
            attempts++
            if (attempts >= playlist.size) break
        } while (true)
        
        currentTrackIndex = nextIndex
        playCurrentTrack()
    }
    
    private fun extractAlbumArt(filePath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val art = retriever.embeddedPicture
            if (art != null) {
                return BitmapFactory.decodeByteArray(art, 0, art.size)
            }
        } catch (e: Exception) {
        } finally {
            retriever.release()
        }
        return null
    }
    
    fun playUrl(context: Context? = null, url: String, trackName: String) {
        try {
            mediaPlayer?.release()
            currentTrackName.value = trackName
            isPlayingState.value = false
            currentAlbumArt.value = if (url.startsWith("/")) extractAlbumArt(url) else null
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { mp ->
                    Logger.log("Началось воспроизведение: $trackName")
                    applyEffect(activeEffect.value)
                    mp.start() 
                    isPlayingState.value = true
                    AudioPlayer.duration.value = mp.duration
                    
                    progressJob?.cancel()
                    progressJob = CoroutineScope(Dispatchers.Main).launch {
                        while(true) {
                            if (mediaPlayer?.isPlaying == true) {
                                AudioPlayer.currentPosition.value = mediaPlayer?.currentPosition ?: 0
                            }
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    
                    context?.let { updateService(it) }
                }
                setOnCompletionListener {
                    isPlayingState.value = false
                    progressJob?.cancel()
                    if (isRepeat.value) {
                        playCurrentTrack()
                    } else {
                        next()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Logger.log("Ошибка MediaPlayer: what=$what extra=$extra")
                    isPlayingState.value = false
                    progressJob?.cancel()
                    context?.let { updateService(it) }
                    true
                }
            }
        } catch (e: Exception) {
            Logger.log("Ошибка инициализации плеера: ${e.localizedMessage}")
        }
    }
    
    fun pause(context: Context? = null) {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
        }
        isPlayingState.value = false
        progressJob?.cancel()
        context?.let { updateService(it) }
    }
    
    fun resume(context: Context? = null) {
        mediaPlayer?.start()
        isPlayingState.value = true
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while(true) {
                if (mediaPlayer?.isPlaying == true) {
                    currentPosition.value = mediaPlayer?.currentPosition ?: 0
                }
                kotlinx.coroutines.delay(1000)
            }
        }
        context?.let { updateService(it) }
    }
    
    fun applyEffect(effect: AudioEffectType) {
        activeEffect.value = effect
        val mp = mediaPlayer ?: return
        
        try {
            bassBoost?.release()
            reverb?.release()
            bassBoost = null
            reverb = null
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                mp.playbackParams = mp.playbackParams.apply {
                    speed = 1.0f
                    pitch = 1.0f
                }
            }
            
            when (effect) {
                AudioEffectType.BASSBOOST -> {
                    bassBoost = BassBoost(0, mp.audioSessionId).apply {
                        enabled = true
                        setStrength(1000)
                    }
                }
                AudioEffectType.SPEED_UP -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        mp.playbackParams = mp.playbackParams.apply { speed = 1.25f }
                    }
                }
                AudioEffectType.SLOWED -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        mp.playbackParams = mp.playbackParams.apply { speed = 0.8f; pitch = 0.8f }
                    }
                }
                AudioEffectType.NIGHTCORE -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        mp.playbackParams = mp.playbackParams.apply { speed = 1.25f; pitch = 1.25f }
                    }
                }
                AudioEffectType.REVERB -> {
                    reverb = PresetReverb(0, mp.audioSessionId).apply {
                        preset = PresetReverb.PRESET_LARGEHALL
                        enabled = true
                    }
                    mp.attachAuxEffect(reverb!!.id)
                    mp.setAuxEffectSendLevel(1.0f)
                }
                AudioEffectType.NORMAL -> {}
            }
        } catch (e: Exception) {
            Logger.log("Ошибка эффекта: ${e.localizedMessage}")
        }
    }
    
    fun stop(context: Context? = null) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlayingState.value = false
        progressJob?.cancel()
        currentTrackName.value = null
        bassBoost?.release()
        reverb?.release()
        bassBoost = null
        reverb = null
        context?.let {
            val intent = android.content.Intent(it, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_STOP
            }
            it.startService(intent)
        }
    }
    
    fun updateService(context: Context) {
        val intent = android.content.Intent(context, PlaybackService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

object Logger {
    val logs = mutableStateListOf<String>()
    fun log(msg: String) {
        logs.add(0, msg)
    }
}
object MaxClientProvider {
    val client = MaxClient()
}

class MainActivity : ComponentActivity() {
    private val maxClient = MaxClientProvider.client

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioCacheManager.init(this)
        enableEdgeToEdge()
        setContent {
            MAXMusicTheme {
                var currentScreen by remember { mutableStateOf(Screen.Auth) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        Screen.Auth -> {
                            AuthScreen(
                                modifier = Modifier.padding(innerPadding),
                                maxClient = maxClient,
                                onAuthSuccess = { currentScreen = Screen.MusicList }
                            )
                        }
                        Screen.MusicList -> {
                            MusicListScreen(
                                modifier = Modifier.padding(innerPadding),
                                maxClient = maxClient,
                                onFileSelected = {},
                                onShowLogs = { currentScreen = Screen.Logs }
                            )
                        }
                        Screen.Logs -> {
                            LogsScreen(
                                modifier = Modifier.padding(innerPadding),
                                onBack = { currentScreen = Screen.MusicList }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        maxClient.disconnect()
    }
}

enum class Screen {
    Auth, MusicList, Logs
}

@Composable
fun AuthScreen(modifier: Modifier = Modifier, maxClient: MaxClient, onAuthSuccess: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableStateOf(AuthStep.Loading) }
    
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val prefs = context.getSharedPreferences("maxmusic_prefs", Context.MODE_PRIVATE)

    LaunchedEffect(Unit) {
        val savedToken = prefs.getString("auth_token", null)
        if (savedToken != null) {
            maxClient.authToken = savedToken
            Logger.log("Найдена сохраненная сессия. Подключение...")
            val success = maxClient.resumeSession()
            if (success) {
                Logger.log("Сессия восстановлена!")
                onAuthSuccess()
            } else {
                Logger.log("Не удалось восстановить сессию. Требуется вход.")
                step = AuthStep.Phone
            }
        } else {
            step = AuthStep.Phone
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (step) {
            AuthStep.Loading -> {
                Text("Авторизация...", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(32.dp))
                CircularProgressIndicator()
            }
            AuthStep.Phone -> {
                Text("Вход в PyMax Music", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Номер телефона") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        isLoading = true
                        Logger.log("Запуск подключения...")
                        coroutineScope.launch {
                            try {
                                maxClient.connect()
                                Logger.log("Успешное подключение к сокету. Отправляем телефон...")
                                val success = maxClient.sendPhone(phone)
                                if (success) {
                                    Logger.log("Телефон принят, ожидание SMS...")
                                    step = AuthStep.Code
                                } else {
                                    Logger.log("Ошибка: Телефон не принят сервером.")
                                }
                            } catch (e: Exception) {
                                Logger.log("Ошибка сети: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = phone.isNotBlank() && !isLoading
                ) {
                    Text("Получить код")
                }
            }
            AuthStep.Code -> {
                Text("Введите SMS код", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("SMS код") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        isLoading = true
                        Logger.log("Отправка SMS кода...")
                        coroutineScope.launch {
                            try {
                                val requires2FA = maxClient.sendSmsCode(code)
                                if (requires2FA) {
                                    Logger.log("Требуется пароль 2FA.")
                                    step = AuthStep.Password
                                } else {
                                    Logger.log("Успешный вход!")
                                    prefs.edit().putString("auth_token", maxClient.authToken).apply()
                                    onAuthSuccess()
                                }
                            } catch (e: Exception) {
                                Logger.log("Ошибка проверки кода: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = code.isNotBlank() && !isLoading
                ) {
                    Text("Отправить")
                }
            }
            AuthStep.Password -> {
                Text("Двухфакторная аутентификация", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль 2FA") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        isLoading = true
                        Logger.log("Отправка пароля 2FA...")
                        coroutineScope.launch {
                            try {
                                val success = maxClient.sendPassword(password)
                                if (success) {
                                    Logger.log("Успешный вход!")
                                    prefs.edit().putString("auth_token", maxClient.authToken).apply()
                                    onAuthSuccess()
                                } else {
                                    Logger.log("Неверный пароль или ошибка входа.")
                                }
                            } catch (e: Exception) {
                                Logger.log("Ошибка проверки пароля: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = password.isNotBlank() && !isLoading
                ) {
                    Text("Войти")
                }
            }
        }
        if (isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }
    }
}

enum class AuthStep {
    Loading, Phone, Code, Password
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicListScreen(
    modifier: Modifier = Modifier, 
    maxClient: MaxClient,
    onFileSelected: (Uri) -> Unit,
    onShowLogs: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var musicList by remember { mutableStateOf<List<MusicMessage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val contentResolver = context.contentResolver
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isLoading = true
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes() ?: ByteArray(0)
                    
                    var fileName = "uploaded_audio.mp3"
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) fileName = it.getString(nameIndex)
                        }
                    }
                    
                    try {
                        val success = maxClient.sendMusic(fileName, bytes)
                        if (success) {
                            Logger.log("Успешно загружен трек: $fileName")
                            kotlinx.coroutines.delay(1500)
                            musicList = maxClient.getSavedMusic()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.log("Ошибка загрузки: ${e.message}")
                    }
                } catch (e: Exception) {
                    Logger.log("Сбой при загрузке: ${e.message}")
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            musicList = maxClient.getSavedMusic()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("Ошибка загрузки плейлиста: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Музыка (Избранное)") },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Логи приложения") },
                            onClick = { onShowLogs(); showMenu = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Лимит: 100 MB") },
                            onClick = { AudioCacheManager.setCacheLimit(context, 100L * 1024 * 1024); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Лимит: 500 MB") },
                            onClick = { AudioCacheManager.setCacheLimit(context, 500L * 1024 * 1024); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Лимит: 1 GB") },
                            onClick = { AudioCacheManager.setCacheLimit(context, 1024L * 1024 * 1024); showMenu = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Только кэш: " + if (AudioPlayer.isCachedOnly.value) "Вкл" else "Выкл") },
                            onClick = { AudioPlayer.isCachedOnly.value = !AudioPlayer.isCachedOnly.value; showMenu = false }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                filePickerLauncher.launch("audio/*")
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        },
        bottomBar = {
            val trackName = AudioPlayer.currentTrackName.value
            if (trackName != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = trackName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(bottom = 0.dp)
                        )
                        var showEffects by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { showEffects = true }) {
                                Text(AudioPlayer.activeEffect.value.title)
                            }
                            DropdownMenu(expanded = showEffects, onDismissRequest = { showEffects = false }) {
                                AudioEffectType.values().forEach { effect ->
                                    DropdownMenuItem(
                                        text = { Text(effect.title) },
                                        onClick = { AudioPlayer.applyEffect(effect); showEffects = false }
                                    )
                                }
                            }
                        }
                    }
                    fun formatTimeMs(ms: Int): String {
                        val totalSeconds = ms / 1000
                        val m = totalSeconds / 60
                        val s = totalSeconds % 60
                        return String.format("%02d:%02d", m, s)
                    }
                    
                    Slider(
                        value = AudioPlayer.currentPosition.value.toFloat(),
                        onValueChange = { AudioPlayer.seekTo(it.toInt()) },
                        valueRange = 0f..(AudioPlayer.duration.value.coerceAtLeast(1)).toFloat(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formatTimeMs(AudioPlayer.currentPosition.value), style = MaterialTheme.typography.bodySmall)
                        Text(text = formatTimeMs(AudioPlayer.duration.value), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { 
                            AudioPlayer.isShuffle.value = !AudioPlayer.isShuffle.value
                            AudioPlayer.updateService(context)
                        }) {
                            Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = if (AudioPlayer.isShuffle.value) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                        IconButton(onClick = { AudioPlayer.prev() }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Prev")
                        }
                        IconButton(onClick = { 
                            if (AudioPlayer.isPlayingState.value) AudioPlayer.pause(context) else AudioPlayer.resume(context) 
                        }) {
                            Icon(if (AudioPlayer.isPlayingState.value) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play/Pause", modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { AudioPlayer.next() }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                        }
                        IconButton(onClick = { 
                            AudioPlayer.isRepeat.value = !AudioPlayer.isRepeat.value 
                            AudioPlayer.updateService(context)
                        }) {
                            Icon(Icons.Filled.Repeat, contentDescription = "Repeat", tint = if (AudioPlayer.isRepeat.value) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (musicList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Нет аудио")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(musicList) { track ->
                            MusicItem(track = track, onClick = {
                                AudioPlayer.setPlaylist(context, maxClient, musicList, musicList.indexOf(track))
                                AudioPlayer.playCurrentTrack()
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MusicItem(track: MusicMessage, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = track.filename, style = MaterialTheme.typography.bodyLarge)
                Text(text = "ID: ${track.fileId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Логи приложения") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            items(Logger.logs) { log ->
                Text(text = log, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
