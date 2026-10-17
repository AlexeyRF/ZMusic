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



object AudioCacheManager {
    private const val PREFS_NAME = "audio_cache_prefs"
    const val UNLIMITED_CACHE = Long.MAX_VALUE
    var maxCacheSizeBytes: Long = 500L * 1024 * 1024 // 500 MB default
    
    data class CacheEntry(
        val fileId: Long, 
        var lastAccessTime: Long, 
        var accessCount: Int,
        var messageId: Long = 0L,
        var filename: String = "",
        var title: String? = null,
        var artist: String? = null
    )
    
    var appContext: Context? = null
    
    // fileId -> CacheEntry
    private val cacheMeta = mutableMapOf<Long, CacheEntry>()
    private var prefs: android.content.SharedPreferences? = null

    fun getEntry(fileId: Long): CacheEntry? = cacheMeta[fileId]
    
    fun isAudioFileEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        val header = ByteArray(16)
        try {
            file.inputStream().use { it.read(header) }
        } catch (e: Exception) { return false }
        
        // MP3: "ID3"
        if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) return false
        // MP3 sync frame: 0xFF followed by 0xEx
        if ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0) return false
        // FLAC: "fLaC"
        if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() && header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) return false
        // OGG: "OggS"
        if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() && header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) return false
        // RIFF/WAV: "RIFF"
        if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x46.toByte()) return false
        // MP4/M4A: "ftyp" at offset 4
        if (header.size >= 8 && header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) return false

        return true
    }

    fun ensureDecrypted(context: Context, file: File): Boolean {
        if (!file.exists() || !isAudioFileEncrypted(file)) return true
        if (!CryptoManager.isPasswordSet) return false
        val tempDecrypted = File(file.parentFile, "${file.name}.dec_tmp")
        return try {
            file.inputStream().use { input ->
                tempDecrypted.outputStream().use { output ->
                    CryptoManager.decryptStream(context, input, output)
                }
            }
            if (tempDecrypted.exists() && tempDecrypted.length() > 0L) {
                if (!isAudioFileEncrypted(tempDecrypted)) {
                    tempDecrypted.copyTo(file, overwrite = true)
                    tempDecrypted.delete()
                    Logger.log("Успешная дешифровка файла: ${file.name}")
                    true
                } else {
                    tempDecrypted.delete()
                    false
                }
            } else {
                tempDecrypted.delete()
                false
            }
        } catch (e: Exception) {
            tempDecrypted.delete()
            Logger.log("Ошибка ensureDecrypted для ${file.name}: ${e.message}")
            false
        }
    }
    
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        maxCacheSizeBytes = prefs!!.getLong("max_cache_size", 500L * 1024 * 1024)
        
        // load metadata
        val json = prefs!!.getString("meta", "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(json)
            val it = obj.keys()
            var purged = false
            while (it.hasNext()) {
                val key = it.next()
                val item = obj.getJSONObject(key)
                val fId = key.toLong()
                val file = File(context.cacheDir, "track_$fId.audio")
                if (!file.exists()) {
                    purged = true
                    continue
                }
                val loadedFilename = item.optString("filename", "")
                val loadedTitle = if (item.has("title") && !item.isNull("title")) item.getString("title") else null
                val loadedArtist = if (item.has("artist") && !item.isNull("artist")) item.getString("artist") else null
                val rawMsgId = item.optLong("messageId", 0L)
                val validMsgId = if (rawMsgId != fId && rawMsgId > 0L) rawMsgId else 0L
                cacheMeta[fId] = CacheEntry(
                    fId,
                    item.optLong("lastAccessTime", 0L),
                    item.optInt("accessCount", 0),
                    validMsgId,
                    loadedFilename,
                    loadedTitle,
                    loadedArtist
                )
            }
            if (purged) saveMeta()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getCachedTracks(context: Context? = appContext): List<ru.alexeyrf.maxmusic.client.MusicMessage> {
        val tracks = mutableListOf<ru.alexeyrf.maxmusic.client.MusicMessage>()
        val ctx = context ?: appContext ?: return tracks
        var metaUpdated = false
        for ((k, entry) in cacheMeta) {
            val file = File(ctx.cacheDir, "track_$k.audio")
            if (!file.exists()) {
                continue
            }
            val msgId = if (entry.messageId != 0L && entry.messageId != k) entry.messageId else 0L
            var name = entry.filename
            var title = entry.title
            var artist = entry.artist

            if (title.isNullOrBlank()) {
                title = LocalMetadataManager.getTitle(ctx, k, name)
            }
            if (artist.isNullOrBlank()) {
                artist = LocalMetadataManager.getArtist(ctx, k, name)
            }

            // If audio file is encrypted, try decrypting it
            if (CryptoManager.isPasswordSet && isAudioFileEncrypted(file)) {
                ensureDecrypted(ctx, file)
            }

            val isPlaceholder = name.isBlank() || name.startsWith("Cached Track") || name.startsWith("Track ") || name == "Unknown file"
            if (isPlaceholder || title.isNullOrBlank()) {
                val meta = AudioPlayer.extractTrackMetadata(file.absolutePath)
                if (!meta.title.isNullOrBlank()) {
                    title = meta.title
                    entry.title = title
                    metaUpdated = true
                }
                if (!meta.artist.isNullOrBlank()) {
                    artist = meta.artist
                    entry.artist = artist
                    metaUpdated = true
                }
            }

            if (isPlaceholder) {
                name = when {
                    !title.isNullOrBlank() && !artist.isNullOrBlank() -> "$artist - $title.mp3"
                    !title.isNullOrBlank() -> "$title.mp3"
                    else -> "Track $k.mp3"
                }
                if (!name.startsWith("Track ")) {
                    entry.filename = name
                    metaUpdated = true
                }
            }

            tracks.add(ru.alexeyrf.maxmusic.client.MusicMessage(
                messageId = msgId, 
                fileId = k, 
                filename = name, 
                fileUrl = "file://" + file.absolutePath, 
                chatId = 0,
                serverTitle = title,
                serverArtist = artist
            ))
        }
        if (metaUpdated) {
            saveMeta()
        }
        return tracks
    }
    
    fun registerTrack(fileId: Long, messageId: Long = 0L, filename: String = "", title: String? = null, artist: String? = null) {
        val entry = cacheMeta[fileId] ?: CacheEntry(fileId, 0, 0)
        entry.lastAccessTime = System.currentTimeMillis()
        entry.accessCount += 1
        if (messageId != 0L && messageId != fileId) {
            entry.messageId = messageId
        }
        val isNewPlaceholder = filename.startsWith("Track ") || filename.startsWith("Cached Track") || filename == "Unknown file"
        if (filename.isNotBlank() && (!isNewPlaceholder || entry.filename.isBlank())) {
            entry.filename = filename
        }
        if (!title.isNullOrBlank()) entry.title = title
        if (!artist.isNullOrBlank()) entry.artist = artist
        
        if (entry.filename.isBlank() || entry.filename.startsWith("Track ") || entry.filename.startsWith("Cached Track")) {
            if (!entry.title.isNullOrBlank()) {
                entry.filename = if (!entry.artist.isNullOrBlank()) "${entry.artist} - ${entry.title}.mp3" else "${entry.title}.mp3"
            }
        }
        
        cacheMeta[fileId] = entry
        saveMeta()
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
                item.put("messageId", if (v.messageId != 0L) v.messageId else k)
                item.put("filename", v.filename)
                if (v.title != null) item.put("title", v.title)
                if (v.artist != null) item.put("artist", v.artist)
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

    suspend fun downloadTrack(
        context: Context, 
        fileId: Long, 
        urlStr: String, 
        fileName: String,
        messageId: Long = 0L,
        title: String? = null,
        artist: String? = null
    ): File? = withContext(Dispatchers.IO) {
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
                    if ((fileName.endsWith(".zmusic") || fileName.endsWith(".z2")) && CryptoManager.isPasswordSet) {
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
            
            if (CryptoManager.isPasswordSet && isAudioFileEncrypted(file)) {
                ensureDecrypted(context, file)
            }
            
            var resolvedTitle = title
            var resolvedArtist = artist
            if (resolvedTitle.isNullOrBlank() || resolvedArtist.isNullOrBlank()) {
                val meta = AudioPlayer.extractTrackMetadata(file.absolutePath)
                if (!meta.title.isNullOrBlank()) resolvedTitle = meta.title
                if (!meta.artist.isNullOrBlank()) resolvedArtist = meta.artist
            }
            
            var finalFilename = fileName
            if (finalFilename.isBlank() || finalFilename.startsWith("Track ") || finalFilename.startsWith("Cached Track") || finalFilename == "Unknown file") {
                if (!resolvedTitle.isNullOrBlank()) {
                    finalFilename = if (!resolvedArtist.isNullOrBlank()) "$resolvedArtist - $resolvedTitle.mp3" else "$resolvedTitle.mp3"
                }
            }

            val entry = cacheMeta[fileId] ?: CacheEntry(fileId, 0, 0)
            entry.lastAccessTime = System.currentTimeMillis()
            entry.accessCount += 1
            if (messageId != 0L && messageId != fileId) entry.messageId = messageId
            if (finalFilename.isNotBlank()) entry.filename = finalFilename
            if (!resolvedTitle.isNullOrBlank()) entry.title = resolvedTitle
            if (!resolvedArtist.isNullOrBlank()) entry.artist = resolvedArtist
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
        if (maxCacheSizeBytes == UNLIMITED_CACHE) return
        
        // sort by lastAccessTime (ascending)
        val sortedEntries = cacheMeta.values.sortedBy { it.lastAccessTime }
        
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

