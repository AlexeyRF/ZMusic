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

