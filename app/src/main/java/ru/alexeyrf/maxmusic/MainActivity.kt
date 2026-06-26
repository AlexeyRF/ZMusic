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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Edit
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
import ru.alexeyrf.maxmusic.ui.theme.ZMusicTheme

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.text.style.TextOverflow
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

fun getEncryptedMaxmusicPrefs(context: Context): android.content.SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "maxmusic_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
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

    fun getCachedTracks(): List<ru.alexeyrf.maxmusic.client.MusicMessage> {
        val tracks = mutableListOf<ru.alexeyrf.maxmusic.client.MusicMessage>()
        for ((k, _) in cacheMeta) {
            tracks.add(ru.alexeyrf.maxmusic.client.MusicMessage(
                messageId = 0, 
                fileId = k, 
                filename = "Cached Track $k", 
                fileUrl = "", 
                chatId = 0
            ))
        }
        return tracks
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

    suspend fun downloadTrack(context: Context, fileId: Long, urlStr: String, fileName: String): File? = withContext(Dispatchers.IO) {
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
                    if (fileName.endsWith(".zmusic") && CryptoManager.isPasswordSet) {
                        try {
                            CryptoManager.decryptStream(context, input, output)
                        } catch (e: Exception) {
                            Logger.log("Ошибка дешифровки: ${e.message}")
                            // Fallback in case of wrong password or corruption
                            input.copyTo(output)
                        }
                    } else {
                        input.copyTo(output)
                    }
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
    NIGHTCORE("Nightcore"),
    SLOWED_REVERB("Slowed + Reverb")
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
    enum class PlayMode { SEQUENTIAL, SHUFFLE, SIMILAR }
    val playMode = mutableStateOf(PlayMode.SEQUENTIAL)
    val isCachedOnly = mutableStateOf(false)
    
    var playlist = listOf<ru.alexeyrf.maxmusic.client.MusicMessage>()
    var currentTrackIndex = -1
    
    var maxClient: ru.alexeyrf.maxmusic.client.MaxClient? = null
    var appContext: Context? = null

    var currentTrackStartTime: Long = 0L
    var currentTrackFileId: Long = -1L
    var currentTrackFilePath: String? = null

    fun recordVibeEvent() {
        if (currentTrackFileId != -1L && currentTrackFilePath != null && currentTrackStartTime > 0) {
            val listenMs = System.currentTimeMillis() - currentTrackStartTime
            val totalMs = duration.value.toLong()
            appContext?.let {
                VibeManager.recordListenEvent(it, currentTrackFileId, activeEffect.value, java.io.File(currentTrackFilePath!!), listenMs, totalMs)
            }
        }
        currentTrackStartTime = 0L
    }
    
    fun setPlaylist(context: Context, client: ru.alexeyrf.maxmusic.client.MaxClient, list: List<ru.alexeyrf.maxmusic.client.MusicMessage>, startIndex: Int) {
        appContext = context.applicationContext
        maxClient = client
        playlist = list
        currentTrackIndex = startIndex
    }
    
    fun rebuildVibePlaylist(context: Context) {
        if (VibeManager.isVibeModeEnabled.value) {
            playlist = VibeManager.generateVibePlaylist(playlist)
            currentTrackIndex = 0
            playCurrentTrack()
        }
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
                        file = AudioCacheManager.downloadTrack(context, track.fileId, url, track.filename)
                    }
                }
                
                if (file != null) {
                    currentTrackFileId = track.fileId
                    currentTrackFilePath = file.absolutePath
                    
                    if (VibeManager.isVibeModeEnabled.value) {
                        val predicted = VibeManager.predictedEffects[track.fileId]
                        if (predicted != null) {
                            activeEffect.value = predicted
                        }
                    }
                    
                    val overrideTitle = LocalMetadataManager.getTitle(context, track.fileId)
                    val overrideArtist = LocalMetadataManager.getArtist(context, track.fileId)
                    val fallbackName = if (!overrideTitle.isNullOrBlank()) {
                        if (!overrideArtist.isNullOrBlank()) "$overrideArtist - $overrideTitle" else overrideTitle
                    } else if (!track.serverTitle.isNullOrBlank()) {
                        if (!track.serverArtist.isNullOrBlank()) "${track.serverArtist} - ${track.serverTitle}" else track.serverTitle
                    } else track.filename.removePrefix("encrypted_").removeSuffix(".zmusic")
                    playUrl(context, file.absolutePath, fallbackName, track.fileId)
                }
                
                // Prefetch next track if sequential
                if (playMode.value == PlayMode.SEQUENTIAL && !isCachedOnly.value && playlist.size > 1) {
                    val nextIdx = (currentTrackIndex + 1) % playlist.size
                    val nextTrack = playlist[nextIdx]
                    CoroutineScope(Dispatchers.IO).launch {
                        val nextFile = java.io.File(context.cacheDir, "track_${nextTrack.fileId}.audio")
                        if (!nextFile.exists()) {
                            val nextUrl = client.getFileDownloadUrl(nextTrack.messageId, nextTrack.fileId)
                            if (nextUrl != null) {
                                AudioCacheManager.downloadTrack(context, nextTrack.fileId, nextUrl, nextTrack.filename)
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
            if (playMode.value == PlayMode.SHUFFLE) {
                nextIndex = playlist.indices.random()
            } else if (playMode.value == PlayMode.SIMILAR) {
                // Find track with closest BPM
                val currentFileId = playlist[currentTrackIndex].fileId
                val currentEffect = activeEffect.value
                val currentKey = "${currentFileId}_${currentEffect.name}"
                
                // Read TrackStats directly via reflection or SharedPreferences if needed.
                // But wait, VibeManager stores TrackStats. Let's just pick random for now or use VibeManager.
                // Wait, VibeManager.currentVibeTargetBpm? No, let's just pick one with closest BPM by calling analyzeTrackBpm or if it exists.
                // To keep it simple, if in SIMILAR mode, we can just use the next track from VibeManager's generateVibePlaylist if we want, OR
                // just pick a track from playlist that shares the artist for fallback, if BPM is too complex to get synchronously here.
                // Wait, user said "Выбирать надо схожие по bpm". I must use BPM.
                val context = appContext
                if (context != null) {
                    val currentFile = AudioCacheManager.getTrackFile(context, currentFileId)
                    val targetBpm = if (currentFile != null) VibeManager.analyzeTrackBpm(currentFile, currentEffect) else 120f
                    
                    var bestMatchIdx = nextIndex
                    var minBpmDiff = Float.MAX_VALUE
                    
                    for (i in playlist.indices) {
                        if (i == currentTrackIndex) continue
                        val file = AudioCacheManager.getTrackFile(context, playlist[i].fileId)
                        if (file != null) {
                            val bpm = VibeManager.analyzeTrackBpm(file, currentEffect)
                            val diff = kotlin.math.abs(bpm - targetBpm)
                            if (diff < minBpmDiff) {
                                minBpmDiff = diff
                                bestMatchIdx = i
                            }
                        }
                    }
                    if (bestMatchIdx == currentTrackIndex) bestMatchIdx = playlist.indices.random()
                    nextIndex = bestMatchIdx
                } else {
                    nextIndex = playlist.indices.random()
                }
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
            if (playMode.value == PlayMode.SHUFFLE || playMode.value == PlayMode.SIMILAR) {
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
    
    data class TrackMetadata(val title: String?, val artist: String?, val albumArt: Bitmap?)

    fun extractTrackMetadata(filePath: String): TrackMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val artBytes = retriever.embeddedPicture
            val art = if (artBytes != null) BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) else null
            return TrackMetadata(title, artist, art)
        } catch (e: Exception) {
        } finally {
            retriever.release()
        }
        return TrackMetadata(null, null, null)
    }
    
    fun playUrl(context: Context? = null, url: String, trackName: String, fileId: Long = -1L) {
        try {
            recordVibeEvent()
            mediaPlayer?.release()
            
            var finalTrackName = trackName
            if (url.startsWith("/")) {
                val metadata = extractTrackMetadata(url)
                currentAlbumArt.value = metadata.albumArt
                if (metadata.title != null && !finalTrackName.contains(" - ")) { // Don't override if we already set a good fallback
                    finalTrackName = if (metadata.artist != null) "${metadata.artist} - ${metadata.title}" else metadata.title
                }
            } else {
                currentAlbumArt.value = null
            }
            
            if (context != null && fileId != -1L) {
                val overrideCover = LocalMetadataManager.getCover(context, fileId)
                if (overrideCover != null) currentAlbumArt.value = overrideCover
                
                val overrideTitle = LocalMetadataManager.getTitle(context, fileId)
                val overrideArtist = LocalMetadataManager.getArtist(context, fileId)
                if (!overrideTitle.isNullOrBlank()) {
                    finalTrackName = if (!overrideArtist.isNullOrBlank()) "$overrideArtist - $overrideTitle" else overrideTitle
                }
            }
            
            currentTrackName.value = finalTrackName
            isPlayingState.value = false
            
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
                    Logger.log("Плеер готов к воспроизведению: $trackName")
                    applyEffect(activeEffect.value)
                    mp.start() 
                    isPlayingState.value = true
                    AudioPlayer.duration.value = mp.duration
                    currentTrackStartTime = System.currentTimeMillis()
                    
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
                AudioEffectType.SLOWED_REVERB -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        mp.playbackParams = mp.playbackParams.apply { speed = 0.8f; pitch = 0.8f }
                    }
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
        CryptoManager.init(this)
        AudioCacheManager.init(this)
        VibeManager.init(this)
        enableEdgeToEdge()
        setContent {
            ZMusicTheme {
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
                                onShowSettings = { currentScreen = Screen.Settings }
                            )
                        }
                        Screen.Settings -> {
                            SettingsScreen(
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
    Auth, MusicList, Settings
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
    val prefs = getEncryptedMaxmusicPrefs(context)

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
                Text("Вход в ZMusic", style = MaterialTheme.typography.headlineMedium)
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
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        isLoading = true
                        Logger.log("Повторный запрос кода...")
                        coroutineScope.launch {
                            try {
                                val success = maxClient.sendPhone(phone)
                                if (success) {
                                    Logger.log("Код отправлен повторно.")
                                } else {
                                    Logger.log("Ошибка: Сервер не принял повторный запрос.")
                                }
                            } catch (e: Exception) {
                                Logger.log("Ошибка сети: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading
                ) {
                    Text("Запросить код повторно")
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
                                    context.getSystemService(android.view.autofill.AutofillManager::class.java)?.commit()
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
    onShowSettings: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var musicList by remember { mutableStateOf<List<MusicMessage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = getEncryptedMaxmusicPrefs(context)
    var showPasswordDialog by remember { mutableStateOf(!CryptoManager.isPasswordSet) }

    if (showPasswordDialog) {
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { /* don't allow dismiss */ },
            title = { Text(if (prefs.getString("auth_token", null) != null) "С обновлением ZMusic!" else "Добро пожаловать в ZMusic") },
            text = {
                Column {
                    Text("Для обеспечения безопасности ваших треков, придумайте или введите пароль для шифрования (сохраните его в Google Паролях или другом менеджере). Он будет использоваться локально.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (pwd.isNotBlank()) {
                        CryptoManager.setPassword(context, pwd)
                        val autofillManager = context.getSystemService(android.view.autofill.AutofillManager::class.java)
                        autofillManager?.commit()
                        showPasswordDialog = false
                    }
                }) {
                    Text("Сохранить")
                }
            }
        )
    }

    val contentResolver = context.contentResolver
    
    var uploadFileUri by remember { mutableStateOf<Uri?>(null) }
    var uploadFileName by remember { mutableStateOf("") }
    var uploadFileBytes by remember { mutableStateOf<ByteArray?>(null) }

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
                    
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    if (ext !in listOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "zmusic")) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Поддерживаются только аудиофайлы и .zmusic", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        isLoading = false
                        return@launch
                    }
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        uploadFileBytes = bytes
                        uploadFileName = fileName
                        uploadFileUri = uri
                        isLoading = false
                    }
                } catch (e: Exception) {
                    Logger.log("Сбой при загрузке: ${e.message}")
                    isLoading = false
                }
            }
        }
    }

    if (uploadFileUri != null && uploadFileBytes != null) {
        val ext = uploadFileName.substringAfterLast('.', "")
        val rawName = uploadFileName.removeSuffix(".$ext")
        MetadataEditorDialog(
            initialTitle = rawName,
            initialArtist = "",
            initialCover = null,
            onDismiss = {
                uploadFileUri = null
                uploadFileBytes = null
            },
            onSave = { newTitle, newArtist, newCover ->
                val bytesToUpload = uploadFileBytes!!
                val fileNameToUpload = uploadFileName
                
                uploadFileUri = null
                uploadFileBytes = null
                isLoading = true
                
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        var processedBytes = bytesToUpload
                        var unencryptedBytes = bytesToUpload
                        var finalFileName = fileNameToUpload
                        
                        val isZmusic = fileNameToUpload.endsWith(".zmusic")
                        if (!isZmusic && (newTitle.isNotBlank() || newArtist.isNotBlank() || newCover != null)) {
                            processedBytes = ID3Editor.embedMetadata(context, bytesToUpload, newTitle, newArtist, newCover, ext)
                            val artistPrefix = if (newArtist.isNotBlank()) "$newArtist - " else ""
                            val titlePart = if (newTitle.isNotBlank()) newTitle else rawName
                            finalFileName = "$artistPrefix$titlePart.$ext"
                            unencryptedBytes = processedBytes
                        }
                        
                        if (CryptoManager.isPasswordSet && !isZmusic) {
                            try {
                                processedBytes = CryptoManager.encryptBytes(context, processedBytes)
                                val extIndex = finalFileName.lastIndexOf('.')
                                val name = if (extIndex != -1) finalFileName.substring(0, extIndex) else finalFileName
                                val extt = if (extIndex != -1) finalFileName.substring(extIndex + 1) else ""
                                finalFileName = "encrypted_${name}.${extt}.zmusic"
                            } catch (e: Exception) {
                                Logger.log("Ошибка шифрования: ${e.message}")
                            }
                        }
                        
                        val success = maxClient.sendMusic(finalFileName, processedBytes)
                        if (success) {
                            Logger.log("Успешно загружен трек: $finalFileName")
                            kotlinx.coroutines.delay(1500)
                            val newList = maxClient.getSavedMusic()
                            val newTrack = newList.find { it.filename == finalFileName }
                            if (newTrack != null) {
                                val cacheFile = java.io.File(context.cacheDir, "track_${newTrack.fileId}.audio")
                                cacheFile.writeBytes(unencryptedBytes)
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                musicList = newList
                            }
                        }
                    } catch (e: Exception) {
                        Logger.log("Ошибка загрузки: ${e.message}")
                    } finally {
                        isLoading = false
                    }
                }
            }
        )
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
                    IconButton(onClick = {
                        VibeManager.isVibeModeEnabled.value = !VibeManager.isVibeModeEnabled.value
                        if (VibeManager.isVibeModeEnabled.value) {
                            AudioPlayer.rebuildVibePlaylist(context)
                        }
                    }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Vibe Mode", tint = if (VibeManager.isVibeModeEnabled.value) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                    IconButton(onClick = onShowSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                filePickerLauncher.launch("*/*")
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
                            AudioPlayer.playMode.value = when (AudioPlayer.playMode.value) {
                                AudioPlayer.PlayMode.SEQUENTIAL -> AudioPlayer.PlayMode.SHUFFLE
                                AudioPlayer.PlayMode.SHUFFLE -> AudioPlayer.PlayMode.SIMILAR
                                AudioPlayer.PlayMode.SIMILAR -> AudioPlayer.PlayMode.SEQUENTIAL
                            }
                            AudioPlayer.updateService(context)
                        }) {
                            val icon = when (AudioPlayer.playMode.value) {
                                AudioPlayer.PlayMode.SIMILAR -> Icons.Filled.AutoAwesome
                                AudioPlayer.PlayMode.SHUFFLE -> Icons.Filled.Shuffle
                                else -> Icons.Filled.Repeat
                            }
                            val tint = if (AudioPlayer.playMode.value != AudioPlayer.PlayMode.SEQUENTIAL) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            Icon(icon, contentDescription = "Play Mode", tint = tint)
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
    val context = LocalContext.current
    var title by remember { mutableStateOf(track.serverTitle ?: track.filename.removePrefix("encrypted_").removeSuffix(".zmusic")) }
    var artist by remember { mutableStateOf<String?>(track.serverArtist) }
    var albumArt by remember { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(track.fileId) {
        withContext(Dispatchers.IO) {
            val file = AudioCacheManager.getTrackFile(context, track.fileId)
            if (file != null) {
                val meta = AudioPlayer.extractTrackMetadata(file.absolutePath)
                if (meta.title != null) title = meta.title
                if (meta.artist != null) artist = meta.artist
                if (meta.albumArt != null) albumArt = meta.albumArt
            }
            val overrideTitle = LocalMetadataManager.getTitle(context, track.fileId)
            if (overrideTitle != null) title = overrideTitle
            val overrideArtist = LocalMetadataManager.getArtist(context, track.fileId)
            if (overrideArtist != null) artist = overrideArtist
            val overrideCover = LocalMetadataManager.getCover(context, track.fileId)
            if (overrideCover != null) albumArt = overrideCover
        }
    }

    var showLocalEditor by remember { mutableStateOf(false) }
    if (showLocalEditor) {
        MetadataEditorDialog(
            initialTitle = title,
            initialArtist = artist ?: "",
            initialCover = albumArt,
            onDismiss = { showLocalEditor = false },
            onSave = { newTitle, newArtist, newCover ->
                showLocalEditor = false
                title = newTitle
                artist = newArtist.ifBlank { null }
                if (newCover != null) albumArt = newCover
                
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    LocalMetadataManager.saveTitle(context, track.fileId, newTitle)
                    LocalMetadataManager.saveArtist(context, track.fileId, newArtist)
                    LocalMetadataManager.saveCover(context, track.fileId, newCover)
                }
            }
        )
    }

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
            if (albumArt != null) {
                Image(
                    bitmap = albumArt!!.asImageBitmap(),
                    contentDescription = "Album Art",
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = "Music",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title, 
                    style = MaterialTheme.typography.bodyLarge, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                if (artist != null) {
                    Text(
                        text = artist!!, 
                        style = MaterialTheme.typography.bodySmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "ID: ${track.fileId}", 
                        style = MaterialTheme.typography.bodySmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { showLocalEditor = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit Metadata")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Настройки и данные") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            item {
                Text("Настройки кэша", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { AudioCacheManager.setCacheLimit(context, 100L * 1024 * 1024) }) { Text("100 MB") }
                    Button(onClick = { AudioCacheManager.setCacheLimit(context, 500L * 1024 * 1024) }) { Text("500 MB") }
                    Button(onClick = { AudioCacheManager.setCacheLimit(context, 1024L * 1024 * 1024) }) { Text("1 GB") }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Офлайн режим (только кэш)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = AudioPlayer.isCachedOnly.value,
                        onCheckedChange = { AudioPlayer.isCachedOnly.value = it }
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text("Логи приложения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(Logger.logs) { log ->
                Text(text = log, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
