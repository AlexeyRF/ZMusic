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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd

import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close

import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
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



enum class AuthStep {
    Loading, Phone, Code, Password
}

fun MusicMessage.isStreamLink(): Boolean =
    filename.endsWith(".rutube.zconfig") || filename.endsWith(".youtube.zconfig")

fun MusicMessage.isBackupConfig(): Boolean =
    filename.contains(".zconfig") && !isStreamLink()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicListScreen(
    modifier: Modifier = Modifier, 
    maxClient: MaxClient, 
    onFileSelected: (Uri) -> Unit,
    onShowSettings: () -> Unit,
    onGoToAuth: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    var musicList by remember { mutableStateOf<List<MusicMessage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = getEncryptedMaxmusicPrefs(context)
    val sessionPrefs = getSessionPrefs(context)
    var showPasswordDialog by remember { mutableStateOf(!CryptoManager.isPasswordSet) }
    var rutubeResults by remember { mutableStateOf<List<RutubeExtractor.RutubeVideo>>(emptyList()) }
    var isRutubeSearching by remember { mutableStateOf(false) }
    var selectedRutubeVideo by remember { mutableStateOf<RutubeExtractor.RutubeVideo?>(null) }
    var showRutubeOptionsDialog by remember { mutableStateOf(false) }
    var youtubeResults by remember { mutableStateOf<List<YouTubeExtractor.YouTubeVideo>>(emptyList()) }
    var isYouTubeSearching by remember { mutableStateOf(false) }
    var selectedYouTubeVideo by remember { mutableStateOf<YouTubeExtractor.YouTubeVideo?>(null) }
    var showYouTubeOptionsDialog by remember { mutableStateOf(false) }
    var showMaxAuthPrompt by remember { mutableStateOf(false) }

    if (showRutubeOptionsDialog && selectedRutubeVideo != null) {
        val video = selectedRutubeVideo!!
        AlertDialog(
            onDismissRequest = { showRutubeOptionsDialog = false },
            title = { Text(video.title) },
            text = {
                Column {
                    Text("Выберите действие:")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        showRutubeOptionsDialog = false
                        coroutineScope.launch {
                            val streamUrl = RutubeExtractor.getPlayUrl(video.id)
                            if (streamUrl != null) {
                                AudioPlayer.playUrl(context, streamUrl, video.title, -1L, "m3u8")
                                if (video.thumbnailUrl.isNotEmpty()) {
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        try {
                                            val thumbBytes = java.net.URL(video.thumbnailUrl).readBytes()
                                            val bmp = android.graphics.BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                                            AudioPlayer.currentAlbumArt.value = bmp
                                        } catch(e: Exception) {}
                                    }
                                }
                            } else {
                                android.widget.Toast.makeText(context, "Ошибка", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Слушать без сохранения") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        showRutubeOptionsDialog = false
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val json = org.json.JSONObject()
                                json.put("type", "rutube")
                                json.put("id", video.id)
                                json.put("title", video.title)
                                json.put("author", video.author)
                                json.put("thumbnail", video.thumbnailUrl)
                                val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
                                val processedBytes = if (CryptoManager.isPasswordSet) {
                                    try {
                                        CryptoManager.encryptBytes(context, jsonBytes)
                                    } catch (e: Exception) {
                                        jsonBytes
                                    }
                                } else {
                                    jsonBytes
                                }
                                val filename = "${video.title.replace(Regex("[^a-zA-Zа-яА-Я0-9]"), "_")}.rutube.zconfig"
                                val success = maxClient.sendMusic(filename, processedBytes)
                                if (success) {
                                    kotlinx.coroutines.delay(1000)
                                    val newList = maxClient.getSavedMusic().filter { !it.isBackupConfig() }
                                    val newTrack = newList.find { it.filename == filename }
                                    if (newTrack != null) {
                                        LocalMetadataManager.saveTitle(context, newTrack.fileId, video.title)
                                        LocalMetadataManager.saveArtist(context, newTrack.fileId, video.author)
                                        if (video.thumbnailUrl.isNotEmpty()) {
                                            try {
                                                val thumbBytes = withContext(Dispatchers.IO) { java.net.URL(video.thumbnailUrl).readBytes() }
                                                val bmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                                                if (bmp != null) {
                                                    LocalMetadataManager.saveCover(context, newTrack.fileId, bmp)
                                                }
                                            } catch (e: Exception) {}
                                        }
                                    }
                                    musicList = newList
                                    ConfigManager.saveLocalConfig(context)
                                }
                            } catch (e: Exception) {}
                            isLoading = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Добавить как ссылку") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        showRutubeOptionsDialog = false
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val tempFile = java.io.File(context.cacheDir, "rutube_temp.ts")
                                val success = RutubeExtractor.downloadVideo(video.id, tempFile) { }
                                if (success) {
                                    val rawBytes = tempFile.readBytes()
                                    var processedBytes = rawBytes
                                    val uuid = java.util.UUID.randomUUID().toString()
                                    val finalFileName = if (CryptoManager.isPasswordSet) {
                                        try {
                                            processedBytes = CryptoManager.encryptBytes(context, rawBytes)
                                            "encrypted_${uuid}.z2"
                                        } catch (e: Exception) {
                                            "${video.author} - ${video.title}.ts".replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                        }
                                    } else {
                                        "${video.author} - ${video.title}.ts".replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                    }
                                    val upSuccess = maxClient.sendMusic(finalFileName, processedBytes)
                                    if (upSuccess) {
                                        kotlinx.coroutines.delay(1000)
                                        val newList = maxClient.getSavedMusic().filter { !it.isBackupConfig() }
                                        val newTrack = newList.find { it.filename == finalFileName }
                                        if (newTrack != null) {
                                            val cacheFile = java.io.File(context.cacheDir, "track_${newTrack.fileId}.audio")
                                            cacheFile.writeBytes(rawBytes) // Cache decrypted audio
                                            AudioCacheManager.registerTrack(newTrack.fileId, newTrack.messageId, newTrack.filename, video.title, video.author)
                                            
                                            LocalMetadataManager.saveTitle(context, newTrack.fileId, video.title)
                                            LocalMetadataManager.saveArtist(context, newTrack.fileId, video.author)
                                            if (video.thumbnailUrl.isNotEmpty()) {
                                                try {
                                                    val thumbBytes = withContext(Dispatchers.IO) { java.net.URL(video.thumbnailUrl).readBytes() }
                                                    val bmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                                                    if (bmp != null) {
                                                        LocalMetadataManager.saveCover(context, newTrack.fileId, bmp)
                                                    }
                                                } catch (e: Exception) {}
                                            }
                                        }
                                        musicList = newList
                                        ConfigManager.saveLocalConfig(context)
                                    }
                                }
                                tempFile.delete()
                            } catch (e: Exception) {}
                            isLoading = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Скачать в библиотеку") }
                }
            },
            confirmButton = { TextButton(onClick = { showRutubeOptionsDialog = false }) { Text("Отмена") } }
        )
    }

    if (showYouTubeOptionsDialog && selectedYouTubeVideo != null) {
        val video = selectedYouTubeVideo!!
        AlertDialog(
            onDismissRequest = { showYouTubeOptionsDialog = false },
            title = { Text(video.title) },
            text = {
                Column {
                    Text("Выберите действие:")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        showYouTubeOptionsDialog = false
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val tempFile = java.io.File(context.cacheDir, "youtube_temp_${System.currentTimeMillis()}.m4a")
                                val success = YouTubeExtractor.downloadAudio(context, video.id, tempFile) { }
                                if (success && tempFile.exists() && tempFile.length() > 0L) {
                                    val rawBytes = tempFile.readBytes()
                                    val cleanTitle = video.title.trim().ifEmpty { "YouTube Track" }
                                    val cleanAuthor = video.author.trim()
                                    val safeBaseName = "${if (cleanAuthor.isNotEmpty()) "$cleanAuthor - " else ""}$cleanTitle"
                                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                    val fileName = "$safeBaseName.m4a"

                                    val fileId = (video.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL).let { if (it <= 0L) System.currentTimeMillis() else it }
                                    val messageId = System.currentTimeMillis()

                                    val localDir = java.io.File(context.filesDir, "local_tracks")
                                    if (!localDir.exists()) localDir.mkdirs()
                                    val localFile = java.io.File(localDir, fileName)
                                    localFile.writeBytes(rawBytes)

                                    val cacheFile = java.io.File(context.cacheDir, "track_${fileId}.audio")
                                    cacheFile.writeBytes(rawBytes)

                                    LocalMetadataManager.saveTitle(context, fileId, cleanTitle)
                                    if (cleanAuthor.isNotEmpty()) {
                                        LocalMetadataManager.saveArtist(context, fileId, cleanAuthor)
                                    }
                                    if (video.thumbnailUrl.isNotEmpty()) {
                                        try {
                                            val thumbBytes = withContext(Dispatchers.IO) {
                                                val proxy = ProxyManager.getProxy(context)
                                                java.net.URL(video.thumbnailUrl).openConnection(proxy).getInputStream().readBytes()
                                            }
                                            val bmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                                            if (bmp != null) {
                                                LocalMetadataManager.saveCover(context, fileId, bmp)
                                            }
                                        } catch (e: Exception) {}
                                    }

                                    AudioCacheManager.registerTrack(fileId, messageId, fileName, cleanTitle, cleanAuthor)

                                    val localMsg = MusicMessage(
                                        messageId = messageId,
                                        fileId = fileId,
                                        filename = fileName,
                                        fileUrl = "file://" + localFile.absolutePath,
                                        chatId = 0,
                                        serverTitle = cleanTitle,
                                        serverArtist = cleanAuthor
                                    )
                                    maxClient.addLocalTrack(localMsg)

                                    musicList = maxClient.getSavedMusic().filter { !it.isBackupConfig() }
                                    ConfigManager.saveLocalConfig(context)
                                    android.widget.Toast.makeText(context, "Трек '$cleanTitle' сохранен в кэш!", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Не удалось скачать аудио с YouTube", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                tempFile.delete()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                android.widget.Toast.makeText(context, "Ошибка при сохранении трека: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            isLoading = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Скачать в кэш") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        showYouTubeOptionsDialog = false
                        if (maxClient.authToken == null) {
                            showMaxAuthPrompt = true
                            return@Button
                        }
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val tempFile = java.io.File(context.cacheDir, "youtube_temp_${System.currentTimeMillis()}.m4a")
                                val success = YouTubeExtractor.downloadAudio(context, video.id, tempFile) { }
                                if (success && tempFile.exists() && tempFile.length() > 0L) {
                                    val rawBytes = tempFile.readBytes()
                                    var processedBytes = rawBytes
                                    val uuid = java.util.UUID.randomUUID().toString()
                                    val cleanTitle = video.title.trim().ifEmpty { "YouTube Track" }
                                    val cleanAuthor = video.author.trim()
                                    val safeBaseName = "${if (cleanAuthor.isNotEmpty()) "$cleanAuthor - " else ""}$cleanTitle"
                                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")

                                    val finalFileName = if (CryptoManager.isPasswordSet) {
                                        try {
                                            processedBytes = CryptoManager.encryptBytes(context, rawBytes)
                                            "encrypted_${uuid}.z2"
                                        } catch (e: Exception) {
                                            "$safeBaseName.m4a"
                                        }
                                    } else {
                                        "$safeBaseName.m4a"
                                    }
                                    val upSuccess = maxClient.sendMusic(finalFileName, processedBytes)
                                    if (upSuccess) {
                                        kotlinx.coroutines.delay(1000)
                                        val newList = maxClient.getSavedMusic().filter { !it.isBackupConfig() }
                                        val newTrack = newList.find { it.filename == finalFileName }
                                        if (newTrack != null) {
                                            val cacheFile = java.io.File(context.cacheDir, "track_${newTrack.fileId}.audio")
                                            cacheFile.writeBytes(rawBytes) // Cache decrypted audio
                                            AudioCacheManager.registerTrack(newTrack.fileId, newTrack.messageId, newTrack.filename, cleanTitle, cleanAuthor)
                                            
                                            LocalMetadataManager.saveTitle(context, newTrack.fileId, cleanTitle)
                                            if (cleanAuthor.isNotEmpty()) {
                                                LocalMetadataManager.saveArtist(context, newTrack.fileId, cleanAuthor)
                                            }
                                            if (video.thumbnailUrl.isNotEmpty()) {
                                                try {
                                                    val thumbBytes = withContext(Dispatchers.IO) {
                                                        val proxy = ProxyManager.getProxy(context)
                                                        java.net.URL(video.thumbnailUrl).openConnection(proxy).getInputStream().readBytes()
                                                    }
                                                    val bmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                                                    if (bmp != null) {
                                                        LocalMetadataManager.saveCover(context, newTrack.fileId, bmp)
                                                    }
                                                } catch (e: Exception) {}
                                            }
                                        }
                                        musicList = newList
                                        ConfigManager.saveLocalConfig(context)
                                        android.widget.Toast.makeText(context, "Трек '$cleanTitle' сохранен в библиотеку!", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Не удалось загрузить трек в библиотеку", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    android.widget.Toast.makeText(context, "Не удалось скачать аудио с YouTube", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                tempFile.delete()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                android.widget.Toast.makeText(context, "Ошибка при сохранении трека: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            isLoading = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Скачать в библиотеку") }
                }
            },
            confirmButton = { TextButton(onClick = { showYouTubeOptionsDialog = false }) { Text("Отмена") } }
        )
    }

    if (showMaxAuthPrompt) {
        AlertDialog(
            onDismissRequest = { showMaxAuthPrompt = false },
            title = { Text("Требуется вход") },
            text = { Text("Похоже вы хотите загрузить свои треки. Войдите через национальный мессенджер макс, чтобы ваши треки сохранялись в избранном.") },
            confirmButton = {
                TextButton(onClick = {
                    showMaxAuthPrompt = false
                    onGoToAuth()
                }) { Text("Войти") }
            },
            dismissButton = {
                TextButton(onClick = { showMaxAuthPrompt = false }) { Text("Отмена") }
            }
        )
    }
    if (showPasswordDialog) {
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { /* don't allow dismiss */ },
            title = { Text(if (sessionPrefs.getString("auth_token", null) != null) "С обновлением ZMusic!" else "Добро пожаловать в ZMusic") },
            text = {
                Column {
                    Text("Для обеспечения безопасности ваших треков, придумайте или введите пароль для шифрования (сохраните его в Google Паролях или другом менеджере). Он будет использоваться локально.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = "ZMusic Local",
                        onValueChange = {},
                        label = { Text("Аккаунт") },
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.autofill(listOf(androidx.compose.ui.autofill.ContentType.Username), currentValue = "ZMusic Local") {}
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.autofill(
                            autofillTypes = listOf(androidx.compose.ui.autofill.ContentType.Password),
                            currentValue = pwd,
                            onFill = { pwd = it }
                        )
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
    var uploadFileExtractedTitle by remember { mutableStateOf<String?>(null) }
    var uploadFileExtractedArtist by remember { mutableStateOf<String?>(null) }
    var uploadFileExtractedArt by remember { mutableStateOf<Bitmap?>(null) }
    
    var massUploadTreeUri by remember { mutableStateOf<Uri?>(null) }
    var massUploadUseZ2 by remember { mutableStateOf(false) }

    val performMassUpload = { treeUri: Uri, useZ2: Boolean ->
        isLoading = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                if (root != null) {
                    val childFiles = root.listFiles().filter { !it.isDirectory && it.name?.substringAfterLast('.', "")?.lowercase() in listOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "zmusic", "z2", "mp4", "mkv", "avi", "webm", "mov") }
                    
                    fun String.normalizeTrackName(): String {
                        var n = this
                        if (n.endsWith(".zmusic")) n = n.removeSuffix(".zmusic")
                        if (n.endsWith(".z2")) n = n.removeSuffix(".z2")
                        n = n.substringBeforeLast('.')
                        n = n.removePrefix("encrypted_")
                        n = n.replace(Regex("\\s*\\(\\d+\\)$"), "")
                        n = n.replace(Regex("(?i)\\s*-\\s*копия(\\s*\\(\\d+\\))?$"), "")
                        n = n.replace(Regex("(?i)\\s*copy(\\s*\\(\\d+\\))?$"), "")
                        return n.trim().lowercase()
                    }
                    
                    val existingNames = musicList.map { it.filename.normalizeTrackName() }.toMutableSet()
                    val uuidToTitle = mutableMapOf<String, String>()
                    
                    var uploadedCount = 0
                    var skippedCount = 0
                    for (file in childFiles) {
                        val fileName = file.name ?: "uploaded_audio.mp3"
                        val normalized = fileName.normalizeTrackName()
                        
                        if (existingNames.contains(normalized)) {
                            skippedCount++
                            continue
                        }
                        
                        val inputStream = contentResolver.openInputStream(file.uri)
                        val bytes = inputStream?.readBytes() ?: continue
                        
                        existingNames.add(normalized)
                        
                        var processedBytes = bytes
                        var finalFileName = fileName
                        
                        val isZmusic = fileName.endsWith(".zmusic") || fileName.endsWith(".z2")
                        val extIndex = fileName.lastIndexOf('.')
                        val rawName = if (extIndex != -1) fileName.substring(0, extIndex) else fileName
                        val fileExt = if (extIndex != -1) fileName.substring(extIndex + 1) else ""
                        
                        if (!isZmusic) {
                            var extractedTitle: String? = null
                            try {
                                val ext = if (fileExt.isNotBlank()) ".$fileExt" else ".tmp"
                                val tempFile = java.io.File.createTempFile("meta", ext, context.cacheDir)
                                tempFile.writeBytes(processedBytes)
                                val audioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                                val tag = audioFile.tag
                                if (tag != null) {
                                    extractedTitle = tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE).takeIf { it.isNotBlank() }
                                }
                                tempFile.delete()
                            } catch (e: Exception) {}
                            
                            if (extractedTitle.isNullOrBlank()) {
                                processedBytes = ru.alexeyrf.maxmusic.ID3Editor.embedMetadata(context, processedBytes, rawName, "", null, fileExt)
                            }
                        }
                        
                        if (ru.alexeyrf.maxmusic.CryptoManager.isPasswordSet && !isZmusic) {
                            try {
                                processedBytes = ru.alexeyrf.maxmusic.CryptoManager.encryptBytes(context, processedBytes)
                                val name = if (useZ2) {
                                    val u = java.util.UUID.randomUUID().toString()
                                    uuidToTitle[u] = rawName
                                    u
                                } else {
                                    rawName
                                }
                                finalFileName = if (useZ2) "encrypted_${name}.z2" else "encrypted_${name}.${fileExt}.zmusic"
                            } catch (e: Exception) {
                                ru.alexeyrf.maxmusic.Logger.log("Ошибка шифрования: ${e.message}")
                            }
                        }
                        
                        val success = maxClient.sendMusic(finalFileName, processedBytes)
                        if (success) uploadedCount++
                    }
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val msg = if (skippedCount > 0) "Загружено: $uploadedCount, пропущено дубликатов: $skippedCount" else "Загружено треков: $uploadedCount"
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        musicList = maxClient.getSavedMusic().filter { !it.isBackupConfig() }
                        
                        if (useZ2) {
                            for (track in musicList) {
                                if (track.filename.endsWith(".z2")) {
                                    val uuid = track.filename.removePrefix("encrypted_").substringBefore('.')
                                    val title = uuidToTitle[uuid]
                                    if (title != null) {
                                        ru.alexeyrf.maxmusic.LocalMetadataManager.saveTitle(context, track.fileId, title)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ru.alexeyrf.maxmusic.Logger.log("Ошибка загрузки папки: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }
    
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            if (ru.alexeyrf.maxmusic.CryptoManager.isPasswordSet) {
                massUploadTreeUri = treeUri
            } else {
                performMassUpload(treeUri, false)
            }
        }
    }

    if (massUploadTreeUri != null) {
        AlertDialog(
            onDismissRequest = { massUploadTreeUri = null },
            title = { Text("Загрузка папки") },
            text = {
                Column {
                    Text("Использовать формат .z2 для шифрования?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = massUploadUseZ2,
                            onCheckedChange = { massUploadUseZ2 = it }
                        )
                        Text("Шифровать как .z2")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = massUploadTreeUri!!
                    val useZ2 = massUploadUseZ2
                    massUploadTreeUri = null
                    performMassUpload(uri, useZ2)
                }) {
                    Text("Загрузить")
                }
            },
            dismissButton = {
                TextButton(onClick = { massUploadTreeUri = null }) {
                    Text("Отмена")
                }
            }
        )
    }

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
                    if (ext !in listOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "zmusic", "z2", "mp4", "mkv", "avi", "webm", "mov")) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Поддерживаются аудио/видео файлы, .zmusic и .z2", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        isLoading = false
                        return@launch
                    }
                    
                    var extractedTitle: String? = null
                    var extractedArtist: String? = null
                    var extractedArt: Bitmap? = null
                    
                    try {
                        val extSuffix = if (ext.isNotBlank()) ".$ext" else ".tmp"
                        val tempFile = java.io.File.createTempFile("meta", extSuffix, context.cacheDir)
                        tempFile.writeBytes(bytes)
                        val audioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                        val tag = audioFile.tag
                        if (tag != null) {
                            extractedTitle = tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE).takeIf { it.isNotBlank() }
                            extractedArtist = tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST).takeIf { it.isNotBlank() }
                            val artwork = tag.firstArtwork
                            if (artwork != null) {
                                val artBytes = artwork.binaryData
                                if (artBytes != null) {
                                    try {
                                        extractedArt = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                                    } catch (t: Throwable) {}
                                }
                            }
                        }
                        tempFile.delete()
                    } catch (e: Exception) {
                    }
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        uploadFileBytes = bytes
                        uploadFileName = fileName
                        uploadFileUri = uri
                        uploadFileExtractedTitle = extractedTitle
                        uploadFileExtractedArtist = extractedArtist
                        uploadFileExtractedArt = extractedArt
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
        val allPlaylists = remember { LocalMetadataManager.getAllPlaylists(context) }
        MetadataEditorDialog(
            initialTitle = uploadFileExtractedTitle ?: rawName,
            initialArtist = uploadFileExtractedArtist ?: "",
            initialCover = uploadFileExtractedArt,
            initialPlaylists = emptySet(),
            allPlaylists = allPlaylists,
            onDismiss = {
                uploadFileUri = null
                uploadFileBytes = null
                uploadFileExtractedTitle = null
                uploadFileExtractedArtist = null
                uploadFileExtractedArt = null
            },
            onSave = { newTitle, newArtist, newCover, useZ2, selectedPlaylists ->
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
                        
                        val isZmusic = fileNameToUpload.endsWith(".zmusic") || fileNameToUpload.endsWith(".z2")
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
                                val rawName = if (extIndex != -1) finalFileName.substring(0, extIndex) else finalFileName
                                val name = if (useZ2) java.util.UUID.randomUUID().toString() else rawName
                                val extt = if (extIndex != -1) finalFileName.substring(extIndex + 1) else ""
                                finalFileName = if (useZ2) "encrypted_${name}.z2" else "encrypted_${name}.${extt}.zmusic"
                            } catch (e: Exception) {
                                Logger.log("Ошибка шифрования: ${e.message}")
                            }
                        }
                        
                        val success = maxClient.sendMusic(finalFileName, processedBytes)
                        if (success) {
                            Logger.log("Успешно загружен трек: $finalFileName")
                            kotlinx.coroutines.delay(1500)
                            val newList = maxClient.getSavedMusic().filter { !it.isBackupConfig() }
                            val newTrack = newList.find { it.filename == finalFileName }
                            if (newTrack != null) {
                                val cacheFile = java.io.File(context.cacheDir, "track_${newTrack.fileId}.audio")
                                cacheFile.writeBytes(unencryptedBytes)
                                AudioCacheManager.registerTrack(newTrack.fileId, newTrack.messageId, newTrack.filename, newTrack.serverTitle, newTrack.serverArtist)
                                
                                if (useZ2) {
                                    val titleToSave = if (newTitle.isNotBlank()) newTitle else rawName
                                    LocalMetadataManager.saveTitle(context, newTrack.fileId, titleToSave)
                                    if (newArtist.isNotBlank()) LocalMetadataManager.saveArtist(context, newTrack.fileId, newArtist)
                                }
                                
                                if (selectedPlaylists.isNotEmpty()) {
                                    LocalMetadataManager.savePlaylists(context, newTrack.fileId, selectedPlaylists)
                                }
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
            musicList = maxClient.getSavedMusic().filter { !it.isBackupConfig() }
            
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("config_auto_loaded", false)) {
                if (maxClient.authToken != null) {
                    val configTracks = maxClient.getSavedMusic().filter { it.isBackupConfig() }
                    if (configTracks.isNotEmpty()) {
                        val latestGroup = configTracks.groupBy { it.filename.substringBefore(".part") }.maxByOrNull { it.key }
                        if (latestGroup != null) {
                            try {
                                coroutineScope.launch(Dispatchers.IO) {
                                    withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "Автозагрузка настроек с сервера...", android.widget.Toast.LENGTH_SHORT).show() }
                                    val providers = latestGroup.value.sortedBy { it.filename }.map { partTrack ->
                                        val url = maxClient.getFileDownloadUrl(partTrack.messageId, partTrack.fileId)
                                        val provider: () -> java.io.InputStream? = { java.net.URL(url).openStream() as java.io.InputStream? }
                                        provider
                                    }
                                    val success = ConfigManager.importConfig(context, providers)
                                    if (success) {
                                        prefs.edit().putBoolean("config_auto_loaded", true).apply()
                                        withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "Настройки восстановлены! Пожалуйста, перезапустите приложение.", android.widget.Toast.LENGTH_LONG).show() }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    prefs.edit().putBoolean("config_auto_loaded", true).apply()
                } else {
                    val localCfg = ConfigManager.getLocalConfigFile(context)
                    if (localCfg.exists() && localCfg.length() > 0) {
                        coroutineScope.launch(Dispatchers.IO) {
                            ConfigManager.importLocalConfig(context)
                            prefs.edit().putBoolean("config_auto_loaded", true).apply()
                        }
                    }
                }
            }
            
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    for (track in musicList) {
                        if (MusicItemCache.cache[track.fileId] == null) {
                            val file = AudioCacheManager.getTrackFile(context, track.fileId)
                            if (file != null) {
                                val ext = track.filename.substringAfterLast('.', "mp3")
                                val meta = AudioPlayer.extractTrackMetadata(file.absolutePath, ext)
                                
                                val overrideTitle = LocalMetadataManager.getTitle(context, track.fileId)
                                val overrideArtist = LocalMetadataManager.getArtist(context, track.fileId)
                                val overrideCover = LocalMetadataManager.getCover(context, track.fileId)
                                
                                val rawClean = when {
                                    track.filename.endsWith(".rutube.zconfig") -> track.filename.removeSuffix(".rutube.zconfig").replace('_', ' ')
                                    track.filename.endsWith(".youtube.zconfig") -> track.filename.removeSuffix(".youtube.zconfig").replace('_', ' ')
                                    else -> track.filename.removePrefix("encrypted_").removeSuffix(".zmusic").removeSuffix(".z2").substringBeforeLast('.').replace('+', ' ')
                                }
                                val defaultTitle = track.serverTitle ?: rawClean
                                val title = overrideTitle ?: meta.title ?: defaultTitle
                                val artist = overrideArtist ?: meta.artist ?: track.serverArtist
                                val albumArt = overrideCover ?: meta.albumArt
                                
                                MusicItemCache.cache[track.fileId] = MusicItemCache.ItemMeta(title, artist, albumArt)
                            }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("Ошибка загрузки плейлиста: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    var allPlaylists by remember { mutableStateOf(LocalMetadataManager.getAllPlaylists(context)) }
    var defaultPlaylist = remember { LocalMetadataManager.getDefaultPlaylist(context) }
    var selectedPlaylistTab by remember { mutableStateOf<String?>(if (allPlaylists.contains(defaultPlaylist)) defaultPlaylist else null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    if (showCreatePlaylistDialog) {
        var newPlaylistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Создать плейлист") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Название") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        LocalMetadataManager.addGlobalPlaylist(context, newPlaylistName.trim())
                        allPlaylists = LocalMetadataManager.getAllPlaylists(context)
                    }
                    showCreatePlaylistDialog = false
                }) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Отмена") }
            }
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedTracks by remember { mutableStateOf(setOf<Long>()) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showDeletePlaylistConfirm by remember { mutableStateOf(false) }

    if (showDeletePlaylistConfirm && selectedPlaylistTab != null) {
        AlertDialog(
            onDismissRequest = { showDeletePlaylistConfirm = false },
            title = { Text("Удалить плейлист?") },
            text = { Text("Вы уверены, что хотите удалить плейлист '$selectedPlaylistTab'?") },
            confirmButton = {
                TextButton(onClick = {
                    val pName = selectedPlaylistTab!!
                    selectedPlaylistTab = null
                    showDeletePlaylistConfirm = false
                    LocalMetadataManager.deleteGlobalPlaylist(context, pName)
                    allPlaylists = LocalMetadataManager.getAllPlaylists(context)
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePlaylistConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (showAddToPlaylistDialog) {
        var showCreateNew by remember { mutableStateOf(false) }
        var newPlaylistName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddToPlaylistDialog = false },
            title = { Text("Выберите плейлист") },
            text = {
                if (showCreateNew) {
                    Column {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            label = { Text("Название нового плейлиста") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showCreateNew = false }) { Text("Отмена") }
                            Button(onClick = {
                                val targetPlaylist = newPlaylistName.trim()
                                if (targetPlaylist.isNotBlank()) {
                                    LocalMetadataManager.addGlobalPlaylist(context, targetPlaylist)
                                    allPlaylists = LocalMetadataManager.getAllPlaylists(context)
                                    
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        selectedTracks.forEach { fileId ->
                                            val current = LocalMetadataManager.getPlaylists(context, fileId).toMutableSet()
                                            current.add(targetPlaylist)
                                            LocalMetadataManager.savePlaylists(context, fileId, current)
                                        }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            isSelectionMode = false
                                            selectedTracks = emptySet()
                                            showAddToPlaylistDialog = false
                                        }
                                    }
                                }
                            }) { Text("Создать") }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        item {
                            ListItem(
                                headlineContent = { Text("Создать новый...", color = MaterialTheme.colorScheme.primary) },
                                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { showCreateNew = true }
                            )
                            HorizontalDivider()
                        }
                        items(allPlaylists) { pl ->
                            ListItem(
                                headlineContent = { Text(pl) },
                                modifier = Modifier.clickable {
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        selectedTracks.forEach { fileId ->
                                            val current = LocalMetadataManager.getPlaylists(context, fileId).toMutableSet()
                                            current.add(pl)
                                            LocalMetadataManager.savePlaylists(context, fileId, current)
                                        }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            isSelectionMode = false
                                            selectedTracks = emptySet()
                                            showAddToPlaylistDialog = false
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                if (!showCreateNew) {
                    TextButton(onClick = { showAddToPlaylistDialog = false }) { Text("Закрыть") }
                }
            }
        )
    }

    var showManagePlaylistsDialog by remember { mutableStateOf(false) }

    if (showManagePlaylistsDialog) {
        var currentOrder by remember { mutableStateOf(allPlaylists) }
        var currentDefault by remember { mutableStateOf(LocalMetadataManager.getDefaultPlaylist(context)) }
        
        val density = LocalDensity.current
        val itemHeightPx = with(density) { 64.dp.toPx() }
        var draggedIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(0f) }

        AlertDialog(
            onDismissRequest = { showManagePlaylistsDialog = false },
            title = { Text("Управление плейлистами") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text("Все треки (По умолчанию)") },
                            trailingContent = {
                                RadioButton(
                                    selected = currentDefault == null,
                                    onClick = { currentDefault = null; LocalMetadataManager.setDefaultPlaylist(context, null) }
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                    items(currentOrder.size) { index ->
                        val pl = currentOrder[index]
                        val isDragging = draggedIndex == index
                        val offset = if (isDragging) dragOffset else 0f
                        
                        ListItem(
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .offset { IntOffset(0, offset.roundToInt()) },
                            headlineContent = { Text(pl) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = currentDefault == pl,
                                        onClick = { currentDefault = pl; LocalMetadataManager.setDefaultPlaylist(context, pl) }
                                    )
                                    IconButton(onClick = {
                                        if (selectedPlaylistTab == pl) selectedPlaylistTab = null
                                        if (currentDefault == pl) {
                                            currentDefault = null
                                            LocalMetadataManager.setDefaultPlaylist(context, null)
                                        }
                                        LocalMetadataManager.deleteGlobalPlaylist(context, pl)
                                        val newPls = LocalMetadataManager.getAllPlaylists(context)
                                        currentOrder = newPls
                                        allPlaylists = newPls
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDragStart = { draggedIndex = index },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffset += dragAmount.y
                                                        val currentIndex = draggedIndex ?: return@detectDragGestures
                                                        
                                                        if (dragOffset > itemHeightPx * 0.7f && currentIndex < currentOrder.size - 1) {
                                                            val mut = currentOrder.toMutableList()
                                                            val temp = mut[currentIndex]
                                                            mut[currentIndex] = mut[currentIndex + 1]
                                                            mut[currentIndex + 1] = temp
                                                            currentOrder = mut
                                                            draggedIndex = currentIndex + 1
                                                            dragOffset -= itemHeightPx
                                                        } else if (dragOffset < -itemHeightPx * 0.7f && currentIndex > 0) {
                                                            val mut = currentOrder.toMutableList()
                                                            val temp = mut[currentIndex]
                                                            mut[currentIndex] = mut[currentIndex - 1]
                                                            mut[currentIndex - 1] = temp
                                                            currentOrder = mut
                                                            draggedIndex = currentIndex - 1
                                                            dragOffset += itemHeightPx
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggedIndex = null
                                                        dragOffset = 0f
                                                        LocalMetadataManager.savePlaylistsOrder(context, currentOrder)
                                                        allPlaylists = currentOrder
                                                    },
                                                    onDragCancel = {
                                                        draggedIndex = null
                                                        dragOffset = 0f
                                                    }
                                                )
                                            }
                                    ) {
                                        Icon(Icons.Filled.DragHandle, contentDescription = "Перетащить")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManagePlaylistsDialog = false }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showManagePlaylistsDialog = false
                    showCreatePlaylistDialog = true
                }) { Text("Создать") }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                if (allPlaylists.isNotEmpty()) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = if (selectedPlaylistTab == null) 0 else allPlaylists.indexOf(selectedPlaylistTab) + 1,
                        edgePadding = 8.dp,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        Tab(
                            selected = selectedPlaylistTab == null,
                            onClick = { selectedPlaylistTab = null },
                            text = { Text("Все треки") }
                        )
                        allPlaylists.forEach { pl ->
                            Tab(
                                selected = selectedPlaylistTab == pl,
                                onClick = { selectedPlaylistTab = pl },
                                text = { 
                                    Text(
                                        text = pl,
                                        modifier = Modifier.combinedClickable(
                                            onClick = { selectedPlaylistTab = pl },
                                            onLongClick = { showManagePlaylistsDialog = true }
                                        )
                                    ) 
                                }
                            )
                        }
                    }
                }
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text("${selectedTracks.size} выбрано") },
                        windowInsets = WindowInsets(0.dp),
                        navigationIcon = {
                            IconButton(onClick = { 
                                isSelectionMode = false
                                selectedTracks = emptySet()
                            }) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
                        },
                        actions = {
                            IconButton(onClick = {
                                val allVisibleIds = if (AudioPlayer.isCachedOnly.value) {
                                    musicList.filter { java.io.File(context.cacheDir, "track_${it.fileId}.audio").exists() }
                                } else musicList
                                val finalVisible = allVisibleIds.filter { t -> 
                                    if (selectedPlaylistTab != null) LocalMetadataManager.getPlaylists(context, t.fileId).contains(selectedPlaylistTab) else true
                                }.filter { t ->
                                    if (searchQuery.isBlank()) true
                                    else {
                                        val c = MusicItemCache.cache[t.fileId]
                                        val title = c?.title ?: t.serverTitle ?: t.filename.lowercase()
                                        val art = c?.artist ?: t.serverArtist ?: ""
                                        title.contains(searchQuery, ignoreCase = true) || art.contains(searchQuery, ignoreCase = true)
                                    }
                                }.map { it.fileId }.toSet()
                                selectedTracks = finalVisible
                            }) { Icon(Icons.Filled.Check, contentDescription = "Выбрать все") }
                            
                            if (selectedPlaylistTab != null) {
                                IconButton(onClick = {
                                    val currentTab = selectedPlaylistTab!!
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        selectedTracks.forEach { fileId ->
                                            val current = LocalMetadataManager.getPlaylists(context, fileId).toMutableSet()
                                            current.remove(currentTab)
                                            LocalMetadataManager.savePlaylists(context, fileId, current)
                                        }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            isSelectionMode = false
                                            selectedTracks = emptySet()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Удалить из плейлиста")
                                }
                            }
                            
                            IconButton(onClick = { showAddToPlaylistDialog = true }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "В плейлист")
                            }
                        }
                    )
                } else if (isSearchActive) {
                    TopAppBar(
                        title = {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { 
                                    searchQuery = it
                                    rutubeResults = emptyList()
                                    isRutubeSearching = false
                                    youtubeResults = emptyList()
                                    isYouTubeSearching = false
                                },
                                placeholder = { Text("Поиск треков...") },
                                singleLine = true,
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { 
                                            searchQuery = ""
                                            rutubeResults = emptyList()
                                            isRutubeSearching = false
                                            youtubeResults = emptyList()
                                            isYouTubeSearching = false
                                        }) { Icon(Icons.Filled.Clear, contentDescription = "Очистить") }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                            )
                        },
                        windowInsets = WindowInsets(0.dp),
                        navigationIcon = {
                            IconButton(onClick = { 
                                isSearchActive = false
                                searchQuery = ""
                                rutubeResults = emptyList()
                                isRutubeSearching = false
                                youtubeResults = emptyList()
                                isYouTubeSearching = false
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                        }
                    )
                } else {
                    TopAppBar(
                        title = { Text("Музыка") },
                        windowInsets = WindowInsets(0.dp),
                        actions = {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Поиск")
                            }
                            IconButton(onClick = {
                                VibeManager.isVibeModeEnabled.value = !VibeManager.isVibeModeEnabled.value
                                if (VibeManager.isVibeModeEnabled.value) {
                                    val fullList = if (AudioPlayer.isCachedOnly.value) AudioCacheManager.getCachedTracks() else musicList
                                    AudioPlayer.rebuildVibePlaylist(context, fullList, selectedPlaylistTab)
                                }
                            }) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = "Vibe Mode", tint = if (VibeManager.isVibeModeEnabled.value) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                            IconButton(onClick = onShowSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { 
                        if (maxClient.authToken == null) showMaxAuthPrompt = true 
                        else folderPickerLauncher.launch(null) 
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add Folder")
                }
                FloatingActionButton(onClick = {
                    if (maxClient.authToken == null) showMaxAuthPrompt = true 
                    else filePickerLauncher.launch("*/*")
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
            }
        },
        bottomBar = {
            val trackName = AudioPlayer.currentTrackName.value
            if (trackName != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = trackName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(bottom = 0.dp)
                        )
                        var showEffects by remember { mutableStateOf(false) }
                        var showCustomEqDialog by remember { mutableStateOf(false) }
                        var showSpeedDialog by remember { mutableStateOf(false) }
                        
                        
                        if (showCustomEqDialog) {
                            val eq = AudioPlayer.equalizer
                            if (eq == null) {
                                AlertDialog(
                                    onDismissRequest = { showCustomEqDialog = false },
                                    title = { Text("Внимание") },
                                    text = { Text("Для настройки эквалайзера нужно включить трек.") },
                                    confirmButton = { TextButton(onClick = { showCustomEqDialog = false }) { Text("OK") } }
                                )
                            } else {
                                var newEffectName by remember { mutableStateOf("") }
                                var tempSpeed by remember { mutableStateOf(AudioPlayer.customSpeed.value ?: 1f) }
                                var tempReverb by remember { mutableStateOf(AudioPlayer.customReverbDelay.value ?: 0) }
                                var tempReverbStr by remember { mutableStateOf(if (tempReverb > 0) tempReverb.toString() else "") }
                                val numBands = eq.numberOfBands
                                val range = eq.bandLevelRange
                                val minLevel = range[0].toFloat()
                                val maxLevel = range[1].toFloat()
                                
                                val bandLevels = remember {
                                    val list = androidx.compose.runtime.mutableStateListOf<Short>()
                                    for (i in 0 until numBands) {
                                        list.add(eq.getBandLevel(i.toShort()))
                                    }
                                    list
                                }

                                AlertDialog(
                                    onDismissRequest = { 
                                        AudioPlayer.applyEffects()
                                        showCustomEqDialog = false 
                                    },
                                    title = { Text("Создать эффект") },
                                    text = {
                                        Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                                            OutlinedTextField(
                                                value = newEffectName,
                                                onValueChange = { newEffectName = it },
                                                label = { Text("Имя пресета") },
                                                singleLine = true
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text("Скорость: ${String.format("%.2f", tempSpeed)}x")
                                            Slider(
                                                value = tempSpeed,
                                                onValueChange = { tempSpeed = it; AudioPlayer.customSpeed.value = it },
                                                onValueChangeFinished = { AudioPlayer.applyEffects() },
                                                valueRange = 0.1f..5.0f
                                            )
                                            Text("Реверберация (0 = Выкл)")
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Slider(
                                                    value = tempReverb.toFloat(),
                                                    onValueChange = { 
                                                        tempReverb = it.toInt()
                                                        tempReverbStr = if (tempReverb > 0) tempReverb.toString() else ""
                                                        AudioPlayer.customReverbDelay.value = if (tempReverb > 0) tempReverb else null
                                                    },
                                                    onValueChangeFinished = { AudioPlayer.applyEffects() },
                                                    valueRange = 0f..500f,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = tempReverbStr,
                                                    onValueChange = { 
                                                        tempReverbStr = it
                                                        val parsed = it.toIntOrNull()
                                                        if (parsed != null) {
                                                            tempReverb = parsed
                                                            AudioPlayer.customReverbDelay.value = if (parsed > 0) parsed else null
                                                            AudioPlayer.applyEffects()
                                                        }
                                                    },
                                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                                    modifier = Modifier.width(80.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            for (i in 0 until numBands) {
                                                val freq = eq.getCenterFreq(i.toShort()) / 1000
                                                Text("Полоса ${freq} Гц", style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = bandLevels[i].toFloat(),
                                                    onValueChange = { 
                                                        bandLevels[i] = it.toInt().toShort() 
                                                        eq.setBandLevel(i.toShort(), bandLevels[i]) 
                                                    },
                                                    valueRange = minLevel..maxLevel
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            if (newEffectName.isNotBlank()) {
                                                val spd = if (tempSpeed != 1f) tempSpeed else null
                                                val rvb = if (tempReverb > 0) tempReverb else null
                                                LocalMetadataManager.saveCustomEffect(context, newEffectName, bandLevels.toList(), spd, rvb)
                                                AudioPlayer.toggleCustomEffect(newEffectName)
                                            }
                                            showCustomEqDialog = false
                                        }) { Text("Сохранить") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { 
                                            AudioPlayer.applyEffects() 
                                            showCustomEqDialog = false 
                                        }) { Text("Отмена") }
                                    }
                                )
                            }
                        }


                        
                        Box {
                            val effectsCount = AudioPlayer.activeEffects.size
                            val activeText = if (AudioPlayer.activeCustomEffects.isNotEmpty()) AudioPlayer.activeCustomEffects.first() 
                                             else if (AudioPlayer.activeEffects.isEmpty()) "Без эффектов" 
                                             else AudioPlayer.activeEffects.joinToString(", ") { effect ->
                                                 when (effect) {
                                                     AudioEffectType.BASSBOOST -> if (effectsCount >= 4) "BB" else if (effectsCount == 3) "Bass" else if (effectsCount == 2) "BassBst" else "BassBoost"
                                                     AudioEffectType.PITCH_DOWN -> if (effectsCount >= 4) "PD" else if (effectsCount == 3) "PDn" else if (effectsCount == 2) "PDown" else "Pitch Down"
                                                     AudioEffectType.PITCH_UP -> if (effectsCount >= 4) "PU" else if (effectsCount == 3) "PUp" else if (effectsCount == 2) "PUp" else "Pitch Up"
                                                     AudioEffectType.SPEED_UP -> if (effectsCount >= 4) "SU" else if (effectsCount == 3) "SpU" else if (effectsCount == 2) "SpeedU" else "Speed Up"
                                                     AudioEffectType.SLOWED -> if (effectsCount >= 4) "Sl" else if (effectsCount == 3) "Slw" else if (effectsCount == 2) "Slow" else "Slowed"
                                                     AudioEffectType.REVERB -> if (effectsCount >= 4) "Rv" else if (effectsCount == 3) "Rev" else if (effectsCount == 2) "Reverb" else "Reverb"
                                                 }
                                             }
                            TextButton(onClick = { showEffects = true }) {
                                Text(activeText)
                            }
                            
                            val customEffects = LocalMetadataManager.getCustomEffects(context)
                            
                            DropdownMenu(expanded = showEffects, onDismissRequest = { showEffects = false }) {
                                AudioEffectType.values().forEach { effect ->
                                    val isSelected = AudioPlayer.activeEffects.contains(effect)
                                    DropdownMenuItem(
                                        text = { Text(effect.title) },
                                        leadingIcon = {
                                            if (isSelected) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
                                        },
                                        onClick = { AudioPlayer.toggleEffect(effect) }
                                    )
                                }
                                
                                if (customEffects.isNotEmpty()) {
                                    HorizontalDivider()
                                    customEffects.keys.forEach { effectName ->
                                        val isSelected = AudioPlayer.activeCustomEffects.contains(effectName)
                                        DropdownMenuItem(
                                            text = { 
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(effectName, modifier = Modifier.weight(1f))
                                                    IconButton(onClick = { 
                                                        LocalMetadataManager.deleteCustomEffect(context, effectName)
                                                        if (isSelected) {
                                                            AudioPlayer.activeCustomEffects.remove(effectName)
                                                            AudioPlayer.applyEffects()
                                                        }
                                                        showEffects = false
                                                    }) {
                                                        Icon(androidx.compose.material.icons.Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                            },
                                            leadingIcon = {
                                                if (isSelected) {
                                                    Icon(Icons.Default.Check, contentDescription = null)
                                                }
                                            },
                                            onClick = { AudioPlayer.toggleCustomEffect(effectName) }
                                        )
                                    }
                                }
                                
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Создать свой эффект...") },
                                    onClick = { 
                                        showEffects = false
                                        showCustomEqDialog = true 
                                    }
                                )
                                HorizontalDivider()

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
                                else -> Icons.AutoMirrored.Filled.List
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
            } else {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                val baseDisplayList = if (AudioPlayer.isCachedOnly.value) {
                    musicList.filter { 
                        java.io.File(context.cacheDir, "track_${it.fileId}.audio").exists() || 
                        (it.fileUrl.startsWith("file://") && java.io.File(it.fileUrl.removePrefix("file://")).exists())
                    }
                } else {
                    musicList
                }
                
                val displayList = if (selectedPlaylistTab == null) {
                    baseDisplayList
                } else {
                    baseDisplayList.filter { track ->
                        val pls = LocalMetadataManager.getPlaylists(context, track.fileId)
                        pls.contains(selectedPlaylistTab)
                    }
                }.filter { track ->
                    if (searchQuery.isBlank()) true
                    else {
                        val c = MusicItemCache.cache[track.fileId]
                        val title = c?.title ?: track.serverTitle ?: track.filename.lowercase()
                        val art = c?.artist ?: track.serverArtist ?: ""
                        title.contains(searchQuery, ignoreCase = true) || art.contains(searchQuery, ignoreCase = true)
                    }
                }
                
                val uniqueDisplayList = remember(displayList) {
                    displayList.distinctBy { track ->
                        val keyPart = if (track.messageId != 0L) track.messageId else track.fileId
                        "${keyPart}_${track.fileId}_${track.filename}"
                    }
                }
                
                if (uniqueDisplayList.isEmpty() && (!isSearchActive || searchQuery.isBlank())) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (AudioPlayer.isCachedOnly.value) "Нет закешированных аудио" else "Нет аудио")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (uniqueDisplayList.isEmpty() && isSearchActive && searchQuery.isNotBlank()) {
                            item(key = "empty_library_notice") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 32.dp, bottom = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "В вашей медиатеке ничего не найдено", 
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(uniqueDisplayList, key = { track ->
                                val keyPart = if (track.messageId != 0L) track.messageId else track.fileId
                                "track_${keyPart}_${track.fileId}_${track.filename}"
                            }) { track ->
                                MusicItem(
                                    track = track,
                                    selectionMode = isSelectionMode,
                                    isSelected = selectedTracks.contains(track.fileId),
                                    onClick = {
                                        if (isSelectionMode) {
                                            val mSet = selectedTracks.toMutableSet()
                                            if (mSet.contains(track.fileId)) mSet.remove(track.fileId) else mSet.add(track.fileId)
                                            selectedTracks = mSet
                                        } else {
                                            AudioPlayer.selectTrack(context, maxClient, uniqueDisplayList, uniqueDisplayList.indexOf(track))
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedTracks = setOf(track.fileId)
                                        }
                                    },
                                     onDelete = {
                                        coroutineScope.launch {
                                            val oldList = musicList
                                            musicList = musicList.filter { 
                                                (track.messageId != 0L && it.messageId != track.messageId) || 
                                                (track.messageId == 0L && it.fileId != track.fileId)
                                            }
                                            val targetId = if (track.messageId != 0L) track.messageId else track.fileId
                                            val success = maxClient.deleteTrack(targetId)
                                            if (success) {
                                                val cacheFile = java.io.File(context.cacheDir, "track_${track.fileId}.audio")
                                                if (cacheFile.exists()) cacheFile.delete()
                                                if (track.fileUrl.startsWith("file://")) {
                                                    val localF = java.io.File(track.fileUrl.removePrefix("file://"))
                                                    if (localF.exists()) localF.delete()
                                                }
                                            } else {
                                                musicList = oldList
                                                android.widget.Toast.makeText(context, "Ошибка при удалении", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        if (isSearchActive && searchQuery.isNotBlank()) {
                            if (uniqueDisplayList.size < 10 && !isRutubeSearching && rutubeResults.isEmpty()) {
                                item(key = "rutube_search_btn") {
                                    TextButton(
                                        onClick = { 
                                            isRutubeSearching = true
                                            coroutineScope.launch {
                                                rutubeResults = RutubeExtractor.search(searchQuery)
                                                isRutubeSearching = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                    ) {
                                        Text("Не нашли нужное? Искать на Rutube")
                                    }
                                }
                            }
                            if (uniqueDisplayList.size < 10 && !isYouTubeSearching && youtubeResults.isEmpty()) {
                                item(key = "youtube_search_btn") {
                                    TextButton(
                                        onClick = { 
                                            isYouTubeSearching = true
                                            coroutineScope.launch {
                                                youtubeResults = YouTubeExtractor.search(context, searchQuery)
                                                isYouTubeSearching = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                    ) {
                                        Text("Искать на YouTube")
                                    }
                                }
                            }
                            if (isRutubeSearching) {
                                item(key = "rutube_loading") {
                                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                            if (rutubeResults.isNotEmpty()) {
                                val uniqueRutubeResults = rutubeResults.distinctBy { it.id }
                                item(key = "rutube_header") {
                                    Text("Результаты Rutube", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                                }
                                items(uniqueRutubeResults, key = { "rutube_${it.id}" }) { video ->
                                    ListItem(
                                        headlineContent = { Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(video.author) },
                                        modifier = Modifier.clickable {
                                            selectedRutubeVideo = video
                                            showRutubeOptionsDialog = true
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                            if (isYouTubeSearching) {
                                item(key = "youtube_loading") {
                                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                            if (youtubeResults.isNotEmpty()) {
                                val uniqueYouTubeResults = youtubeResults.distinctBy { it.id }
                                item(key = "youtube_header") {
                                    Text("Результаты YouTube", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                                }
                                items(uniqueYouTubeResults, key = { "youtube_${it.id}" }) { video ->
                                    ListItem(
                                        headlineContent = { Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { 
                                            val desc = if (video.author.isNotBlank() && video.duration.isNotBlank()) {
                                                "${video.author} • ${video.duration}"
                                            } else if (video.author.isNotBlank()) video.author else video.duration
                                            Text(desc) 
                                        },
                                        modifier = Modifier.clickable {
                                            selectedYouTubeVideo = video
                                            showYouTubeOptionsDialog = true
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
                
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.CircleShape)
                            .padding(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

object MusicItemCache {
    data class ItemMeta(val title: String, val artist: String?, val albumArt: Bitmap?)
    val cache = androidx.compose.runtime.mutableStateMapOf<Long, ItemMeta>()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicItem(
    track: MusicMessage, 
    onClick: () -> Unit, 
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit = {},
    selectionMode: Boolean = false,
    isSelected: Boolean = false
) {
    val context = LocalContext.current
    val cached = MusicItemCache.cache[track.fileId]
    val localTitle = LocalMetadataManager.getTitle(context, track.fileId)
    val localArtist = LocalMetadataManager.getArtist(context, track.fileId)
    val rawCleanTitle = when {
        track.filename.endsWith(".rutube.zconfig") -> track.filename.removeSuffix(".rutube.zconfig").replace('_', ' ')
        track.filename.endsWith(".youtube.zconfig") -> track.filename.removeSuffix(".youtube.zconfig").replace('_', ' ')
        else -> track.filename.removePrefix("encrypted_").removeSuffix(".zmusic").removeSuffix(".z2").substringBeforeLast('.').replace('+', ' ')
    }
    val cleanTitle = if (rawCleanTitle.startsWith("Cached Track") || rawCleanTitle.startsWith("Track ")) {
        localTitle ?: rawCleanTitle
    } else {
        rawCleanTitle
    }
    val defaultTitle = track.serverTitle ?: localTitle ?: cleanTitle
    val title = cached?.title ?: defaultTitle
    val artist = cached?.artist ?: (localArtist ?: track.serverArtist)
    val albumArt = cached?.albumArt ?: LocalMetadataManager.getCover(context, track.fileId)
    
    LaunchedEffect(track.fileId) {
        if (cached == null || cached.title.startsWith("Cached Track") || cached.title.startsWith("Track ")) {
            withContext(Dispatchers.IO) {
                val file = AudioCacheManager.getTrackFile(context, track.fileId)
                var tempTitle = title
                var tempArtist = artist
                var tempArt = albumArt
                
                if (file != null) {
                    if (track.filename.endsWith(".rutube.zconfig") || track.filename.endsWith(".youtube.zconfig")) {
                        try {
                            val raw = file.readBytes()
                            val text = if (CryptoManager.isPasswordSet) {
                                try { String(CryptoManager.decryptBytes(context, raw), Charsets.UTF_8) } catch (e: Exception) { String(raw, Charsets.UTF_8) }
                            } else String(raw, Charsets.UTF_8)
                            val json = org.json.JSONObject(text)
                            val jTitle = json.optString("title")
                            val jAuthor = json.optString("author")
                            if (jTitle.isNotEmpty()) tempTitle = jTitle
                            if (jAuthor.isNotEmpty()) tempArtist = jAuthor
                        } catch (e: Exception) {}
                    } else {
                        val ext = track.filename.substringAfterLast('.', "mp3")
                        val meta = AudioPlayer.extractTrackMetadata(file.absolutePath, ext)
                        if (meta.title != null) tempTitle = meta.title
                        if (meta.artist != null) tempArtist = meta.artist
                        if (meta.albumArt != null) tempArt = meta.albumArt
                    }
                }
                
                val overrideTitle = LocalMetadataManager.getTitle(context, track.fileId)
                if (overrideTitle != null) tempTitle = overrideTitle
                val overrideArtist = LocalMetadataManager.getArtist(context, track.fileId)
                if (overrideArtist != null) tempArtist = overrideArtist
                val overrideCover = LocalMetadataManager.getCover(context, track.fileId)
                if (overrideCover != null) tempArt = overrideCover
                
                MusicItemCache.cache[track.fileId] = MusicItemCache.ItemMeta(tempTitle, tempArtist, tempArt)
            }
        }
    }

    var showLocalEditor by remember { mutableStateOf(false) }
    if (showLocalEditor) {
        val currentPlaylists = remember { LocalMetadataManager.getPlaylists(context, track.fileId) }
        val allPlaylists = remember { LocalMetadataManager.getAllPlaylists(context) }
        MetadataEditorDialog(
            initialTitle = title,
            initialArtist = artist ?: "",
            initialCover = albumArt,
            initialPlaylists = currentPlaylists,
            allPlaylists = allPlaylists,
            showEncryptionOption = false,
            onDismiss = { showLocalEditor = false },
            onSave = { newTitle, newArtist, newCover, _, newPlaylists ->
                showLocalEditor = false
                MusicItemCache.cache[track.fileId] = MusicItemCache.ItemMeta(newTitle, newArtist.ifBlank { null }, newCover)
                
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    LocalMetadataManager.saveTitle(context, track.fileId, newTitle)
                    LocalMetadataManager.saveArtist(context, track.fileId, newArtist)
                    LocalMetadataManager.saveCover(context, track.fileId, newCover)
                    LocalMetadataManager.savePlaylists(context, track.fileId, newPlaylists)
                }
            }
        )
    }

    var showPropertiesDialog by remember { mutableStateOf(false) }
    if (showPropertiesDialog) {
        val cacheEntry = AudioCacheManager.getEntry(track.fileId)
        val cacheFile = File(context.cacheDir, "track_${track.fileId}.audio")
        val localDir = File(context.filesDir, "local_tracks")
        val localFile = File(localDir, track.filename)
        val urlFile = if (track.fileUrl.startsWith("file://")) File(track.fileUrl.removePrefix("file://")) 
                      else if (track.fileUrl.isNotBlank() && File(track.fileUrl).exists()) File(track.fileUrl)
                      else null
        val playlists = LocalMetadataManager.getPlaylists(context, track.fileId)

        val detailsReport = buildString {
            appendLine("=== ОСНОВНЫЕ ДАННЫЕ ===")
            appendLine("fileId: ${track.fileId}")
            appendLine("messageId: ${track.messageId}")
            appendLine("filename: ${track.filename}")
            appendLine("fileUrl: ${track.fileUrl.ifBlank { "<пусто>" }}")
            appendLine("chatId: ${track.chatId}")
            appendLine("serverTitle: ${track.serverTitle ?: "<null>"}")
            appendLine("serverArtist: ${track.serverArtist ?: "<null>"}")
            appendLine()
            appendLine("=== ОТОБРАЖЕНИЕ В ИНТЕРФЕЙСЕ ===")
            appendLine("title (в UI): $title")
            appendLine("artist (в UI): ${artist ?: "<null>"}")
            appendLine("defaultTitle: $defaultTitle")
            appendLine("cleanTitle: $cleanTitle")
            appendLine("cachedMeta: title='${cached?.title}', artist='${cached?.artist}'")
            appendLine()
            appendLine("=== LOCAL METADATA MANAGER ===")
            appendLine("localTitle: ${localTitle ?: "<null>"}")
            appendLine("localArtist: ${localArtist ?: "<null>"}")
            appendLine("hasCover: ${LocalMetadataManager.getCover(context, track.fileId) != null}")
            appendLine("playlists: ${if (playlists.isEmpty()) "<нет>" else playlists.joinToString(", ")}")
            appendLine()
            appendLine("=== AUDIO CACHE MANAGER ===")
            if (cacheEntry != null) {
                appendLine("В cacheMeta: ДА")
                appendLine("entry.messageId: ${cacheEntry.messageId}")
                appendLine("entry.filename: ${cacheEntry.filename}")
                appendLine("entry.title: ${cacheEntry.title ?: "<null>"}")
                appendLine("entry.artist: ${cacheEntry.artist ?: "<null>"}")
                appendLine("entry.accessCount: ${cacheEntry.accessCount}")
                appendLine("entry.lastAccessTime: ${cacheEntry.lastAccessTime} (${java.util.Date(cacheEntry.lastAccessTime)})")
            } else {
                appendLine("В cacheMeta: НЕТ")
            }
            appendLine()
            appendLine("=== ФАЙЛОВАЯ СИСТЕМА ===")
            appendLine("1) cacheDir/track_${track.fileId}.audio:")
            appendLine("   - существует: ${cacheFile.exists()}")
            if (cacheFile.exists()) {
                appendLine("   - размер: ${cacheFile.length()} байт (${cacheFile.length() / 1024} КБ)")
                appendLine("   - путь: ${cacheFile.absolutePath}")
            }
            appendLine("2) filesDir/local_tracks/${track.filename}:")
            appendLine("   - существует: ${localFile.exists()}")
            if (localFile.exists()) {
                appendLine("   - размер: ${localFile.length()} байт (${localFile.length() / 1024} КБ)")
                appendLine("   - путь: ${localFile.absolutePath}")
            }
            if (urlFile != null) {
                appendLine("3) track.fileUrl на диске:")
                appendLine("   - существует: ${urlFile.exists()}")
                if (urlFile.exists()) {
                    appendLine("   - размер: ${urlFile.length()} байт (${urlFile.length() / 1024} КБ)")
                    appendLine("   - путь: ${urlFile.absolutePath}")
                }
            }
            val testFile = if (cacheFile.exists()) cacheFile else if (localFile.exists()) localFile else urlFile
            if (testFile != null && testFile.exists()) {
                appendLine()
                appendLine("=== ТЕГИ ФАЙЛА (${testFile.name}) ===")
                try {
                    val ext = testFile.name.substringAfterLast('.', "mp3")
                    val meta = AudioPlayer.extractTrackMetadata(testFile.absolutePath, ext)
                    appendLine("tag.title: ${meta.title ?: "<не найден>"}")
                    appendLine("tag.artist: ${meta.artist ?: "<не найден>"}")
                    appendLine("tag.hasAlbumArt: ${meta.albumArt != null}")
                } catch (e: Exception) {
                    appendLine("Ошибка чтения тегов: ${e.message}")
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showPropertiesDialog = false },
            title = { Text("Свойства трека (Debug)") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = detailsReport,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Track Debug Info", detailsReport)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Скопировано в буфер обмена", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Text("Скопировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPropertiesDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (selectionMode && isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
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
            var showDeleteConfirm by remember { mutableStateOf(false) }
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Удалить трек?") },
                    text = { Text("Этот трек будет удален из Избранного (сохраненных сообщений).") },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            onDelete()
                        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
                    }
                )
            }
            
            if (selectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            } else {
                Box {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Меню трека")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Свойства трека (Debug)") },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            onClick = { 
                                showMenu = false
                                showPropertiesDialog = true 
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Редактировать метаданные") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { 
                                showMenu = false
                                showLocalEditor = true 
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { 
                                showMenu = false
                                showDeleteConfirm = true 
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier, 
    maxClient: MaxClient, 
    onBack: () -> Unit,
    onGoToAuth: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var serverConfigs by remember { mutableStateOf<List<ru.alexeyrf.maxmusic.client.MusicMessage>>(emptyList()) }
    var isConfigsLoading by remember { mutableStateOf(true) }
    var showLogs by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showRestartPrompt by remember { mutableStateOf(false) }
    var showServerAuthPrompt by remember { mutableStateOf(false) }
    
    val localConfigFile = remember { ConfigManager.getLocalConfigFile(context) }
    var localConfigExists by remember { mutableStateOf(localConfigFile.exists()) }
    var localConfigSize by remember { mutableStateOf(if (localConfigFile.exists()) localConfigFile.length() else 0L) }
    
    val configExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                android.widget.Toast.makeText(context, "Создание конфига...", android.widget.Toast.LENGTH_SHORT).show()
                val parts = ConfigManager.exportConfig(context)
                if (parts.isNotEmpty()) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            parts[0].inputStream().use { it.copyTo(out) }
                        }
                        if (parts.size > 1) {
                            android.widget.Toast.makeText(context, "Внимание: конфиг разделен на части, сохранена только первая!", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            android.widget.Toast.makeText(context, "Конфиг успешно сохранен в файл!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        android.widget.Toast.makeText(context, "Ошибка сохранения", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    val configImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                android.widget.Toast.makeText(context, "Загрузка конфига...", android.widget.Toast.LENGTH_SHORT).show()
                val success = ConfigManager.importConfig(context, listOf { context.contentResolver.openInputStream(uri) })
                if (success) {
                    showRestartPrompt = true
                } else {
                    android.widget.Toast.makeText(context, "Ошибка импорта!", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val allTracks = maxClient.getSavedMusic()
            serverConfigs = allTracks.filter { it.isBackupConfig() }.sortedByDescending { it.filename }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isConfigsLoading = false
        }
    }
    
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Сброс данных") },
            text = { Text("Вы уверены, что хотите сбросить все данные приложения? Настоятельно рекомендуется сначала экспортировать настройки.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.clearApplicationUserData()
                }) {
                    Text("Сбросить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (showRestartPrompt) {
        AlertDialog(
            onDismissRequest = { showRestartPrompt = false },
            title = { Text("Требуется перезапуск") },
            text = { Text("Настройки успешно загружены!\nПожалуйста, полностью закройте приложение (выгрузите из памяти) и откройте его заново для применения новых параметров.") },
            confirmButton = {
                TextButton(onClick = { showRestartPrompt = false }) { Text("Понятно") }
            }
        )
    }

    if (showServerAuthPrompt) {
        AlertDialog(
            onDismissRequest = { showServerAuthPrompt = false },
            title = { Text("Требуется вход в MAX") },
            text = { Text("Похоже вы хотите выгрузить свой zconfig на сервер. Войдите через национальный мессенджер макс, чтобы ваши настройки сохранялись в облаке.") },
            confirmButton = {
                TextButton(onClick = {
                    showServerAuthPrompt = false
                    onGoToAuth()
                }) { Text("Войти") }
            },
            dismissButton = {
                TextButton(onClick = { showServerAuthPrompt = false }) { Text("Отмена") }
            }
        )
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Настройки и данные") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            item {
                Text("Настройки кэша", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                val cacheSteps = listOf(0L, 50L * 1024 * 1024, 100L * 1024 * 1024, 500L * 1024 * 1024, 1024L * 1024 * 1024, 2048L * 1024 * 1024, 5120L * 1024 * 1024, AudioCacheManager.UNLIMITED_CACHE)
                val currentLimit = AudioCacheManager.maxCacheSizeBytes
                var sliderPos by remember { mutableStateOf(cacheSteps.indexOf(currentLimit).takeIf { it >= 0 }?.toFloat() ?: 3f) }
                
                Slider(
                    value = sliderPos,
                    onValueChange = { pos ->
                        sliderPos = pos
                        AudioCacheManager.setCacheLimit(context, cacheSteps[pos.toInt()])
                    },
                    valueRange = 0f..7f,
                    steps = 6
                )
                
                val limitValue = cacheSteps[sliderPos.toInt()]
                val currentText = when (limitValue) {
                    0L -> "Только оперативная память"
                    AudioCacheManager.UNLIMITED_CACHE -> "Безлимит"
                    else -> if (limitValue >= 1024L * 1024 * 1024) "${limitValue / (1024L * 1024 * 1024)} GB" else "${limitValue / (1024 * 1024)} MB"
                }
                Text(currentText, style = MaterialTheme.typography.bodySmall)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Офлайн режим (только кэш)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = AudioPlayer.isCachedOnly.value,
                        onCheckedChange = { AudioPlayer.isCachedOnly.value = it }
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Использовать Material UI (Dynamic Colors)", modifier = Modifier.weight(1f))
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    var materialEnabled by remember { mutableStateOf(prefs.getBoolean("material_ui", true)) }
                    Switch(
                        checked = materialEnabled,
                        onCheckedChange = { 
                            materialEnabled = it
                            prefs.edit().putBoolean("material_ui", it).apply()
                        }
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text("Настройки эффектов", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Задержка реверберации (мс)", style = MaterialTheme.typography.bodyMedium)
                var tempReverb by remember { mutableStateOf(AudioPlayer.customReverbDelay.value ?: 0) }
                var tempReverbStr by remember { mutableStateOf(if (tempReverb > 0) tempReverb.toString() else "") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = tempReverb.toFloat(),
                        onValueChange = { 
                            tempReverb = it.toInt()
                            tempReverbStr = if (tempReverb > 0) tempReverb.toString() else ""
                            AudioPlayer.customReverbDelay.value = if (tempReverb > 0) tempReverb else null
                            if (AudioPlayer.activeEffects.contains(AudioEffectType.REVERB)) AudioPlayer.syncReverb()
                        },
                        valueRange = 0f..500f,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tempReverbStr,
                        onValueChange = { 
                            tempReverbStr = it
                            val parsed = it.toIntOrNull()
                            if (parsed != null) {
                                tempReverb = parsed
                                AudioPlayer.customReverbDelay.value = if (parsed > 0) parsed else null
                                if (AudioPlayer.activeEffects.contains(AudioEffectType.REVERB)) AudioPlayer.syncReverb()
                            } else if (it.isEmpty()) {
                                tempReverb = 0
                                AudioPlayer.customReverbDelay.value = null
                                if (AudioPlayer.activeEffects.contains(AudioEffectType.REVERB)) AudioPlayer.syncReverb()
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.width(80.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Text("Переходы между треками", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                
                var currentTransitionMode by remember { mutableStateOf(AudioPlayer.transitionMode.value) }
                var currentTransitionDur by remember { mutableStateOf(AudioPlayer.transitionDurationSec.value) }
                
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AudioPlayer.TransitionMode.values().forEach { mode ->
                        val isSelected = currentTransitionMode == mode
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentTransitionMode = mode
                                    AudioPlayer.setTransitionMode(context, mode)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        currentTransitionMode = mode
                                        AudioPlayer.setTransitionMode(context, mode)
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                    )
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (currentTransitionMode != AudioPlayer.TransitionMode.NORMAL) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Длительность перехода: ${currentTransitionDur} сек",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = currentTransitionDur.toFloat(),
                        onValueChange = {
                            val sec = it.toInt()
                            currentTransitionDur = sec
                            AudioPlayer.setTransitionDuration(context, sec)
                        },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                    Text(
                        text = if (currentTransitionMode == AudioPlayer.TransitionMode.CRISPY) {
                            "При криспи-переходе уходящий трек мягко затухает по динамической кривой, а новый трек плавно выходит вперед без нагромождения частот и перегрузки баса."
                        } else {
                            "Текущий трек плавно затихает до нуля, после чего следующий трек начинает воспроизведение с нарастающей громкостью."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    var manualTransition by remember { mutableStateOf(AudioPlayer.manualTransitionEnabled.value) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "При ручном переключении",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Применять плавный переход при переключении кнопками или выборе трека во время воспроизведения",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = manualTransition,
                            onCheckedChange = {
                                manualTransition = it
                                AudioPlayer.setManualTransitionEnabled(context, it)
                            }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Text("Поддержка YouTube", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                
                var proxyEnabled by remember { mutableStateOf(ProxyManager.isProxyEnabled(context)) }
                var proxyHost by remember { mutableStateOf(ProxyManager.getProxyHost(context)) }
                var proxyPortStr by remember { mutableStateOf(ProxyManager.getProxyPort(context).let { if (it > 0) it.toString() else "1080" }) }
                var proxyUser by remember { mutableStateOf(ProxyManager.getProxyUser(context)) }
                var proxyPass by remember { mutableStateOf(ProxyManager.getProxyPass(context)) }
                
                var isTestingProxy by remember { mutableStateOf(false) }
                var proxyTestResult by remember { mutableStateOf<String?>(null) }
                var proxyTestSuccess by remember { mutableStateOf<Boolean?>(null) }
                
                val saveProxy = {
                    val port = proxyPortStr.toIntOrNull() ?: 1080
                    ProxyManager.setProxyConfig(context, proxyEnabled, proxyHost, port, proxyUser, proxyPass)
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SOCKS-прокси", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Использовать SOCKS прокси для доступа к YouTube",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = proxyEnabled,
                        onCheckedChange = {
                            proxyEnabled = it
                            saveProxy()
                        }
                    )
                }
                
                if (proxyEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = proxyHost,
                            onValueChange = {
                                proxyHost = it
                                saveProxy()
                            },
                            label = { Text("Хост / IP") },
                            placeholder = { Text("127.0.0.1") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = proxyPortStr,
                            onValueChange = {
                                proxyPortStr = it
                                saveProxy()
                            },
                            label = { Text("Порт") },
                            placeholder = { Text("1080") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = proxyUser,
                            onValueChange = {
                                proxyUser = it
                                saveProxy()
                            },
                            label = { Text("Логин (опционально)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = proxyPass,
                            onValueChange = {
                                proxyPass = it
                                saveProxy()
                            },
                            label = { Text("Пароль (опционально)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                saveProxy()
                                isTestingProxy = true
                                proxyTestResult = null
                                coroutineScope.launch {
                                    val (success, message) = ProxyManager.testConnection(context)
                                    isTestingProxy = false
                                    proxyTestSuccess = success
                                    proxyTestResult = message
                                }
                            },
                            enabled = !isTestingProxy
                        ) {
                            if (isTestingProxy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Проверка...")
                            } else {
                                Text("Проверить подключение")
                            }
                        }
                    }
                    if (proxyTestResult != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = proxyTestResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (proxyTestSuccess == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Text("Экспорт / Импорт настроек (.zconfig)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    text = if (localConfigExists) "Локальный zconfig: сохранен (${localConfigSize / 1024} КБ)" else "Локальный zconfig: еще не сохранен",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        coroutineScope.launch {
                            val saved = ConfigManager.saveLocalConfig(context)
                            if (saved != null) {
                                localConfigExists = true
                                localConfigSize = saved.length()
                                android.widget.Toast.makeText(context, "Конфиг сохранен локально!", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Ошибка сохранения", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Text("Сохранить локально", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (localConfigExists) {
                        OutlinedButton(onClick = {
                            coroutineScope.launch {
                                val ok = ConfigManager.importLocalConfig(context)
                                if (ok) {
                                    showRestartPrompt = true
                                } else {
                                    android.widget.Toast.makeText(context, "Ошибка импорта", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Text("Восстановить", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (maxClient.authToken == null) {
                            showServerAuthPrompt = true
                        } else {
                            coroutineScope.launch {
                                android.widget.Toast.makeText(context, "Создание общего конфига...", android.widget.Toast.LENGTH_SHORT).show()
                                val parts = ConfigManager.exportConfig(context)
                                android.widget.Toast.makeText(context, "Создано ${parts.size} частей. Выгрузка...", android.widget.Toast.LENGTH_SHORT).show()
                                var allOk = true
                                parts.forEach { part ->
                                    val res = ConfigManager.uploadConfig(context, maxClient, part)
                                    if (!res) allOk = false
                                }
                                if (allOk) {
                                    android.widget.Toast.makeText(context, "Успешно выгружено на сервер!", android.widget.Toast.LENGTH_LONG).show()
                                    val allTracks = maxClient.getSavedMusic()
                                    serverConfigs = allTracks.filter { it.isBackupConfig() }.sortedByDescending { it.filename }
                                } else {
                                    android.widget.Toast.makeText(context, "Ошибка выгрузки", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Text("На сервер", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { configExportLauncher.launch("config.zconfig") }, modifier = Modifier.weight(1f)) {
                        Text("В файл", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { configImportLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                        Text("Из файла", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text("Конфиги на сервере:", style = MaterialTheme.typography.titleSmall)
                
                if (isConfigsLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                } else if (serverConfigs.isEmpty()) {
                    Text("Нет сохраненных конфигов", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                } else {
                    val grouped = serverConfigs.groupBy { it.filename.substringBefore(".part") }
                    grouped.forEach { (baseName, parts) ->
                        ListItem(
                            headlineContent = { Text(baseName) },
                            supportingContent = { Text("Частей: ${parts.size}") },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        coroutineScope.launch {
                                            android.widget.Toast.makeText(context, "Скачивание и применение...", android.widget.Toast.LENGTH_SHORT).show()
                                            try {
                                                val providers = parts.sortedBy { it.filename }.map { partTrack ->
                                                    val url = maxClient.getFileDownloadUrl(partTrack.messageId, partTrack.fileId)
                                                    val provider: () -> java.io.InputStream? = { java.net.URL(url).openStream() as java.io.InputStream? }
                                                    provider
                                                }
                                                val success = ConfigManager.importConfig(context, providers)
                                                if (success) {
                                                    showRestartPrompt = true
                                                } else {
                                                    android.widget.Toast.makeText(context, "Ошибка импорта!", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                android.widget.Toast.makeText(context, "Ошибка сети", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Filled.Check, contentDescription = "Применить")
                                    }
                                    IconButton(onClick = {
                                        coroutineScope.launch {
                                            parts.forEach { partTrack ->
                                                maxClient.deleteTrack(partTrack.messageId)
                                            }
                                            val allTracks = maxClient.getSavedMusic()
                                            serverConfigs = allTracks.filter { it.isBackupConfig() }.sortedByDescending { it.filename }
                                        }
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Row(modifier = Modifier.fillMaxWidth().clickable { showLogs = !showLogs }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Логи приложения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(if (showLogs) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            }
            if (showLogs) {
                items(Logger.logs) { log ->
                    Text(text = log, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Сбросить приложение")
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
