package ru.alexeyrf.maxmusic

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.media.audiofx.BassBoost
import android.media.audiofx.PresetReverb

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



enum class AudioEffectType(val title: String) {
    BASSBOOST("BassBoost"),
    SPEED_UP("Speed Up"),
    SLOWED("Slowed"),
    PITCH_UP("Pitch Up"),
    PITCH_DOWN("Pitch Down"),
    REVERB("Reverb")
}

object AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var currentFis: java.io.FileInputStream? = null
    private var bassBoost: BassBoost? = null
    var equalizer: android.media.audiofx.Equalizer? = null
    var currentSpeed = 1f
    
    val activeEffects = androidx.compose.runtime.mutableStateListOf<AudioEffectType>()
    val activeCustomEffects = androidx.compose.runtime.mutableStateListOf<String>()
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
    val trackHistory = mutableListOf<Int>()
    var historyIndex = -1
    val customSpeed = androidx.compose.runtime.mutableStateOf<Float?>(null)
    val customReverbDelay = androidx.compose.runtime.mutableStateOf<Int?>(null)
    private var reverbMediaPlayer: android.media.MediaPlayer? = null
    private var reverbJob: kotlinx.coroutines.Job? = null
    private var lastUrl: String? = null
    
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
                val currentEffectStr = if (activeEffects.isEmpty()) "NORMAL" else activeEffects.map { it.name }.sorted().joinToString("+")
                VibeManager.recordListenEvent(it, currentTrackFileId, activeEffects.toSet(), java.io.File(currentTrackFilePath!!), listenMs, totalMs)
            }
        }
        currentTrackStartTime = 0L
    }
    
    fun setPlaylist(context: Context, client: ru.alexeyrf.maxmusic.client.MaxClient, list: List<ru.alexeyrf.maxmusic.client.MusicMessage>, startIndex: Int) {
        appContext = context.applicationContext
        maxClient = client
        playlist = list
        currentTrackIndex = startIndex
        trackHistory.clear()
        trackHistory.add(startIndex)
        historyIndex = 0
    }
    
    fun rebuildVibePlaylist(context: Context, fullList: List<ru.alexeyrf.maxmusic.client.MusicMessage>? = null, activePlaylist: String? = null) {
        if (VibeManager.isVibeModeEnabled.value) {
            val baseList = fullList ?: playlist
            val oldHistoryTracks = trackHistory.mapNotNull { playlist.getOrNull(it) }
            playlist = VibeManager.generateVibePlaylist(baseList, activePlaylist, context)
            trackHistory.clear()
            for (track in oldHistoryTracks) {
                val newIdx = playlist.indexOfFirst { it.fileId == track.fileId }
                if (newIdx != -1) trackHistory.add(newIdx)
            }
            trackHistory.add(0)
            if (trackHistory.size > 500) trackHistory.removeAt(0)
            historyIndex = trackHistory.size - 1
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
                        if (predicted != null && predicted.isNotEmpty()) {
                            activeEffects.clear()
                            activeCustomEffects.clear()
                            activeEffects.addAll(predicted)
                            applyEffects()
                        }
                    }
                    
                    val overrideTitle = LocalMetadataManager.getTitle(context, track.fileId)
                    val overrideArtist = LocalMetadataManager.getArtist(context, track.fileId)
                    val fallbackName = if (!overrideTitle.isNullOrBlank()) {
                        if (!overrideArtist.isNullOrBlank()) "$overrideArtist - $overrideTitle" else overrideTitle
                    } else if (!track.serverTitle.isNullOrBlank()) {
                        if (!track.serverArtist.isNullOrBlank()) "${track.serverArtist} - ${track.serverTitle}" else track.serverTitle
                    } else track.filename.removePrefix("encrypted_").removeSuffix(".zmusic").removeSuffix(".z2").substringBeforeLast('.').replace('+', ' ')
                    val ext = track.filename.substringAfterLast('.', "mp3").lowercase()
                    playUrl(context, file.absolutePath, fallbackName, track.fileId, ext)
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
        val delay = customReverbDelay.value
        if (delay != null && delay > 0 && reverbMediaPlayer != null) {
            val effectiveDelay = (delay / currentSpeed).toInt()
            val targetPos = positionMs - effectiveDelay
            reverbJob?.cancel()
            if (targetPos > 0) {
                reverbMediaPlayer?.seekTo(targetPos)
                if (isPlayingState.value) reverbMediaPlayer?.start()
            } else {
                reverbMediaPlayer?.pause()
                if (isPlayingState.value) {
                    reverbJob = CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(-targetPos.toLong())
                        reverbMediaPlayer?.seekTo(0)
                        reverbMediaPlayer?.start()
                    }
                }
            }
        }
        appContext?.let { updateService(it) }
    }
    
    fun next() {
        if (playlist.isEmpty()) return
        if (currentTrackIndex !in playlist.indices) currentTrackIndex = 0
        val context = appContext ?: return
        
        if ((playMode.value == PlayMode.SHUFFLE || playMode.value == PlayMode.SIMILAR) && historyIndex >= 0 && historyIndex < trackHistory.size - 1) {
            historyIndex++
            currentTrackIndex = trackHistory[historyIndex]
            playCurrentTrack()
            return
        }

        var nextIndex = currentTrackIndex
        var attempts = 0
        
        do {
            if (playMode.value == PlayMode.SHUFFLE) {
                nextIndex = playlist.indices.random()
            } else if (playMode.value == PlayMode.SIMILAR) {
                // Find track with closest BPM
                val currentFileId = playlist[currentTrackIndex].fileId
                val currentEffectStr = if (activeEffects.isEmpty()) "NORMAL" else activeEffects.map { it.name }.sorted().joinToString("+")
                val currentKey = "${currentFileId}_$currentEffectStr"
                
                // Read TrackStats directly via reflection or SharedPreferences if needed.
                val context = appContext
                if (context != null) {
                    val currentFile = AudioCacheManager.getTrackFile(context, currentFileId)
                    val targetBpm = if (currentFile != null) VibeManager.analyzeTrackBpm(currentFile, activeEffects.toSet()) else 120f
                    
                    var bestMatchIdx = nextIndex
                    var minBpmDiff = Float.MAX_VALUE
                    
                    for (i in playlist.indices) {
                        if (i == currentTrackIndex) continue
                        val file = AudioCacheManager.getTrackFile(context, playlist[i].fileId)
                        if (file != null) {
                            val bpm = VibeManager.analyzeTrackBpm(file, activeEffects.toSet())
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
        if (historyIndex >= 0 && historyIndex < trackHistory.size - 1) trackHistory.subList(historyIndex + 1, trackHistory.size).clear()
        trackHistory.add(nextIndex)
        if (trackHistory.size > 500) trackHistory.removeAt(0)
        historyIndex = trackHistory.size - 1
        playCurrentTrack()
    }
    
    fun prev() {
        if (playlist.isEmpty()) return
        if (currentTrackIndex !in playlist.indices) currentTrackIndex = 0
        val context = appContext ?: return
        
        if ((playMode.value == PlayMode.SHUFFLE || playMode.value == PlayMode.SIMILAR) && historyIndex > 0) {
            historyIndex--
            currentTrackIndex = trackHistory[historyIndex]
            playCurrentTrack()
            return
        }

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
        if (playMode.value == PlayMode.SHUFFLE || playMode.value == PlayMode.SIMILAR) {
            trackHistory.add(0, nextIndex)
            if (trackHistory.size > 500) trackHistory.removeAt(trackHistory.size - 1)
            historyIndex = 0
        } else {
            if (historyIndex >= 0 && historyIndex < trackHistory.size - 1) trackHistory.subList(historyIndex + 1, trackHistory.size).clear()
            trackHistory.add(nextIndex)
            if (trackHistory.size > 500) trackHistory.removeAt(0)
            historyIndex = trackHistory.size - 1
        }
        playCurrentTrack()
    }
    
    data class TrackMetadata(val title: String?, val artist: String?, val albumArt: Bitmap?)

    fun extractTrackMetadata(filePath: String, originalExt: String = "mp3"): TrackMetadata {
        var tempFile: java.io.File? = null
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) return TrackMetadata(null, null, null)
            
            var targetFile = file
            if (filePath.endsWith(".audio")) {
                try {
                    var extToUse = originalExt.lowercase()
                    if (extToUse.isBlank() || extToUse == "zmusic" || extToUse == "z2" || extToUse == "audio") {
                        extToUse = "mp3"
                    }
                    tempFile = java.io.File.createTempFile("meta", ".$extToUse", file.parentFile)
                    file.copyTo(tempFile, overwrite = true)
                    targetFile = tempFile
                } catch (e: Exception) {}
            }
            
            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(targetFile)
            val tag = audioFile.tag
            
            if (tag != null) {
                val title = tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE).takeIf { it.isNotBlank() }
                val artist = tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST).takeIf { it.isNotBlank() }
                
                var art: Bitmap? = null
                val artwork = tag.firstArtwork
                if (artwork != null) {
                    val artBytes = artwork.binaryData
                    if (artBytes != null) {
                        try {
                            art = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                        } catch (t: Throwable) {
                            Logger.log("Ошибка декодирования обложки (OOM?): ${t.message}")
                        }
                    }
                }
                return TrackMetadata(title, artist, art)
            }
        } catch (t: Throwable) {
            Logger.log("Ошибка извлечения метаданных (Jaudiotagger): ${t.message}")
        } finally {
            tempFile?.delete()
        }
        return TrackMetadata(null, null, null)
    }
    
    fun playUrl(context: Context? = null, url: String, trackName: String, fileId: Long = -1L, originalExt: String = "mp3") {
        try {
            recordVibeEvent()
            mediaPlayer?.release()
            try { currentFis?.close() } catch (e: Exception) {}
            currentFis = null
            
            lastUrl = url
            var finalTrackName = trackName
            var currentMetaTitle: String? = null
            var currentMetaArtist: String? = null
            
            if (url.startsWith("/")) {
                val metadata = extractTrackMetadata(url, originalExt)
                currentMetaTitle = metadata.title
                currentMetaArtist = metadata.artist
                currentAlbumArt.value = metadata.albumArt
                if (metadata.title != null && !finalTrackName.contains(" - ")) { 
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
                
                MusicItemCache.cache[fileId] = MusicItemCache.ItemMeta(
                    title = overrideTitle ?: currentMetaTitle ?: trackName,
                    artist = overrideArtist ?: currentMetaArtist,
                    albumArt = currentAlbumArt.value
                )
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
                if (url.startsWith("/")) {
                    currentFis = java.io.FileInputStream(url)
                    setDataSource(currentFis!!.fd)
                } else {
                    setDataSource(url)
                }
                prepareAsync()
                setOnErrorListener { mp, what, extra ->
                    Logger.log("MediaPlayer error: what=$what extra=$extra")
                    isPlayingState.value = false
                    mp.reset()
                    true
                }
                setOnPreparedListener { mp ->
                    Logger.log("Плеер готов к воспроизведению: $trackName")
                    applyEffects()
                    mp.start() 
                    isPlayingState.value = true
                    syncReverb()
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
        } catch (t: Throwable) {
            Logger.log("Ошибка инициализации плеера: ${t.message}")
        }
    }
    
    fun pause(context: Context? = null) {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
        }
        reverbMediaPlayer?.pause()
        reverbJob?.cancel()
        isPlayingState.value = false
        progressJob?.cancel()
        context?.let { updateService(it) }
    }
    
    fun resume(context: Context? = null) {
        mediaPlayer?.start()
        isPlayingState.value = true
        val delay = customReverbDelay.value
        if (delay != null && delay > 0 && reverbMediaPlayer != null) {
            val effectiveDelay = (delay / currentSpeed).toInt()
            val targetPos = (mediaPlayer?.currentPosition ?: 0) - effectiveDelay
            if (targetPos > 0) {
                reverbMediaPlayer?.seekTo(targetPos)
                reverbMediaPlayer?.start()
            } else {
                reverbJob?.cancel()
                reverbJob = CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(-targetPos.toLong())
                    reverbMediaPlayer?.start()
                }
            }
        }
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
    
    fun toggleEffect(effect: AudioEffectType) {
        if (activeEffects.contains(effect)) {
            activeEffects.remove(effect)
        } else {
            if (effect == AudioEffectType.SPEED_UP) activeEffects.remove(AudioEffectType.SLOWED)
            if (effect == AudioEffectType.SLOWED) activeEffects.remove(AudioEffectType.SPEED_UP)
            if (effect == AudioEffectType.PITCH_UP) activeEffects.remove(AudioEffectType.PITCH_DOWN)
            if (effect == AudioEffectType.PITCH_DOWN) activeEffects.remove(AudioEffectType.PITCH_UP)
            activeEffects.add(effect)
        }
        applyEffects()
    }

    fun toggleCustomEffect(effectName: String) {
        if (activeCustomEffects.contains(effectName)) {
            activeCustomEffects.remove(effectName)
        } else {
            activeCustomEffects.clear()
            activeEffects.remove(AudioEffectType.BASSBOOST) // Custom effect overrides normal BassBoost EQ
            activeCustomEffects.add(effectName)
        }
        applyEffects()
    }

    fun applyEffects() {
        val mp = mediaPlayer ?: return
        
        try {
            bassBoost?.release()
            equalizer?.release()
            bassBoost = null
            equalizer = null
            
            var speed = 1.0f
            var pitch = 1.0f
            
            val activeCustomEffectName = activeCustomEffects.firstOrNull()
            val effectSpeed = if (activeCustomEffectName != null && appContext != null) LocalMetadataManager.getCustomEffectSpeed(appContext!!, activeCustomEffectName) else customSpeed.value
            
            if (effectSpeed != null) {
                speed = effectSpeed
            } else {
                if (activeEffects.contains(AudioEffectType.SPEED_UP)) speed *= 1.25f
                if (activeEffects.contains(AudioEffectType.SLOWED)) speed *= 0.8f
            }
            if (activeEffects.contains(AudioEffectType.PITCH_UP)) pitch *= 1.25f
            if (activeEffects.contains(AudioEffectType.PITCH_DOWN)) pitch *= 0.8f
            currentSpeed = speed

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                mp.playbackParams = mp.playbackParams.apply {
                    this.speed = speed
                    this.pitch = pitch
                }
                if (!isPlayingState.value) mp.pause()
                
                try {
                    reverbMediaPlayer?.let { rmp ->
                        rmp.playbackParams = rmp.playbackParams.apply {
                            this.speed = speed
                            this.pitch = pitch
                        }
                        if (!isPlayingState.value) rmp.pause()
                    }
                } catch (e: Exception) {}
            }
            
            val hasCustomReverb = activeCustomEffectName != null && appContext != null && LocalMetadataManager.getCustomEffectReverb(appContext!!, activeCustomEffectName) != null
            if (activeEffects.contains(AudioEffectType.REVERB) || hasCustomReverb) {
                syncReverb()
            } else {
                reverbJob?.cancel()
                reverbMediaPlayer?.release()
                reverbMediaPlayer = null
            }
            
            if (activeEffects.contains(AudioEffectType.BASSBOOST)) {
                try {
                    bassBoost = BassBoost(1, mp.audioSessionId).apply {
                        enabled = true
                        setStrength(1000)
                    }
                } catch (e: Exception) {
                    Logger.log("BassBoost не поддерживается")
                }
            }

            try {
                val customEffects = appContext?.let { LocalMetadataManager.getCustomEffects(it) } ?: emptyMap()
                val activeCustomEffectName = activeCustomEffects.firstOrNull()
                val customLevels = if (activeCustomEffectName != null) customEffects[activeCustomEffectName] else null

                equalizer = android.media.audiofx.Equalizer(1, mp.audioSessionId).apply {
                    enabled = activeEffects.contains(AudioEffectType.BASSBOOST) || activeCustomEffectName != null
                    val numBands = numberOfBands
                    for (i in 0 until numBands) {
                        if (customLevels != null && i < customLevels.size) {
                            setBandLevel(i.toShort(), customLevels[i])
                        } else if (activeEffects.contains(AudioEffectType.BASSBOOST)) {
                            val freq = getCenterFreq(i.toShort())
                            if (freq < 150000) {
                                setBandLevel(i.toShort(), bandLevelRange[1])
                            } else if (freq < 300000) {
                                setBandLevel(i.toShort(), (bandLevelRange[1] / 2).toShort())
                            } else {
                                setBandLevel(i.toShort(), 0)
                            }
                        } else {
                            setBandLevel(i.toShort(), 0)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log("Equalizer не поддерживается")
            }
        } catch (e: Exception) {
            Logger.log("Ошибка эффектов: ${e.message}")
        }
        
        appContext?.let { updateService(it) }
    }
    
    fun stop(context: Context? = null) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        reverbJob?.cancel()
        reverbMediaPlayer?.stop()
        reverbMediaPlayer?.release()
        reverbMediaPlayer = null
        try { currentFis?.close() } catch (e: Exception) {}
        currentFis = null
        reverbMediaPlayer?.pause()
        reverbJob?.cancel()
        isPlayingState.value = false
        progressJob?.cancel()
        currentTrackName.value = null
        bassBoost?.release()
        bassBoost = null
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

    fun syncReverb() {
        reverbJob?.cancel()
        reverbMediaPlayer?.release()
        reverbMediaPlayer = null
        
        val hasCustomReverb = activeCustomEffects.firstOrNull()?.let { appContext?.let { ctx -> LocalMetadataManager.getCustomEffectReverb(ctx, it) } } != null
        if (!activeEffects.contains(AudioEffectType.REVERB) && !hasCustomReverb) return

        val activeCustomEffectName = activeCustomEffects.firstOrNull()
        val customReverb = if (activeCustomEffectName != null && appContext != null) LocalMetadataManager.getCustomEffectReverb(appContext!!, activeCustomEffectName) else null
        
        val delayMsBase = customReverb ?: (customReverbDelay.value ?: 150)
        val effectiveDelay = (delayMsBase / currentSpeed).toInt()
        val url = lastUrl
        if (effectiveDelay > 0 && url != null) {
            reverbMediaPlayer = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                try {
                    if (url.startsWith("/")) {
                        val fis = java.io.FileInputStream(url)
                        setDataSource(fis.fd)
                    } else {
                        setDataSource(url)
                    }
                    setVolume(0.3f, 0.3f)
                    prepareAsync()
                    setOnPreparedListener { rmp ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            var pitch = 1.0f
                            if (activeEffects.contains(AudioEffectType.PITCH_UP)) pitch *= 1.25f
                            if (activeEffects.contains(AudioEffectType.PITCH_DOWN)) pitch *= 0.8f
                            rmp.playbackParams = rmp.playbackParams.apply {
                                this.speed = currentSpeed
                                this.pitch = pitch
                            }
                            if (!isPlayingState.value) rmp.pause()
                        }
                        
                        val targetPos = (mediaPlayer?.currentPosition ?: 0) - effectiveDelay
                        if (targetPos > 0) {
                            rmp.seekTo(targetPos)
                            if (isPlayingState.value) rmp.start()
                        } else {
                            if (isPlayingState.value) {
                                reverbJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    kotlinx.coroutines.delay(-targetPos.toLong())
                                    rmp.start()
                                }
                            }
                        }
                    }
                } catch(e:Exception) {}
            }
        }
    }
}