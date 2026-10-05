package ru.alexeyrf.maxmusic.client

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.value.Value
import org.msgpack.value.ValueType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStream

data class MusicMessage(
    val messageId: Long,
    val fileId: Long,
    val filename: String,
    val fileUrl: String = "",
    val chatId: Int = 0,
    val serverTitle: String? = null,
    val serverArtist: String? = null
)

/**
 * Полноценная реализация TCP-клиента протокола MaxMusic (PyMax) на Kotlin.
 */
class MaxClient(
    private val host: String = "api.oneme.ru", 
    private val port: Int = 443
) {
    private val tag = "MaxClient"
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val seqCounter = java.util.concurrent.atomic.AtomicInteger(0)
    var context: android.content.Context? = null

    private val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<Map<String, Any>>>()
    private var pingJob: Job? = null

    var isAuthenticated = false
    var authToken: String? = null
    var authTrackId: String? = null
    
    private var callsSeed: Long = 0L
    private var deviceId: String = ""

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    private fun generateFingerprint(): ByteArray {
        val certHash = hexStringToByteArray("1684414033eb263e2c615f8b7df5ed8793850a07656304997fbf07e9e21e1e93")
        val dexHash = hexStringToByteArray("8db68fcc0e85e8f041fe4a875c0a9bcfe542a8f679603728c651ed81b64dd684")
        val soHash = hexStringToByteArray("634ecc42b246784d975f180b4fecf903df235cdf0476da47163a85630eb1a6a8")
        
        val seedBytes = ByteBuffer.allocate(8).putLong(callsSeed).array()
        val deviceBytes = deviceId.toByteArray(Charsets.UTF_8)
        
        val md = java.security.MessageDigest.getInstance("SHA-256")
        
        md.update(certHash)
        md.update(seedBytes)
        val h1 = md.digest(deviceBytes)
        
        md.reset()
        md.update(dexHash)
        md.update(seedBytes)
        val h2 = md.digest(deviceBytes)
        
        md.reset()
        md.update(soHash)
        md.update(seedBytes)
        val h3 = md.digest(deviceBytes)
        
        return h1 + h2 + h3
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Connecting to $host:$port via SSL")
            socket = javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port)
            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())

            readerScope.launch {
                readLoop()
            }

            pingJob?.cancel()
            pingJob = readerScope.launch {
                while(isActive) {
                    delay(25000)
                    try {
                        sendRequest(0, 1, mapOf("interactive" to true)) // PING = 1
                    } catch (e: Exception) {
                        Log.e(tag, "Ping error", e)
                    }
                }
            }

            val manufacturer = android.os.Build.MANUFACTURER
            val model = android.os.Build.MODEL
            val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
            val osVersion = "Android ${android.os.Build.VERSION.RELEASE}"
            val timezone = java.util.TimeZone.getDefault().id
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            val screenStr = "${metrics.densityDpi}dpi ${metrics.densityDpi}dpi ${metrics.widthPixels}x${metrics.heightPixels}"

            // Отправляем первый пакет SESSION_INIT (opcode: 6)
            val newDeviceId = java.util.UUID.randomUUID().toString()
            val handshakePayload = mapOf(
                "mt_instanceid" to java.util.UUID.randomUUID().toString(),
                "userAgent" to mapOf(
                    "deviceType" to "ANDROID",
                    "appVersion" to "26.25.0",
                    "osVersion" to osVersion,
                    "timezone" to timezone,
                    "screen" to screenStr,
                    "locale" to "ru",
                    "deviceName" to deviceName,
                    "deviceLocale" to "ru",
                    "pushDeviceType" to "GCM",
                    "arch" to "arm64-v8a",
                    "buildNumber" to 6790
                ),
                "clientSessionId" to 1,
                "deviceId" to newDeviceId
            )
            val sessionRes = sendRequest(cmd = 0, opcode = 6, payload = handshakePayload)
            deviceId = newDeviceId
            callsSeed = (sessionRes["callsSeed"] as? Number)?.toLong() ?: 0L
            Log.d(tag, "Connected and session initialized, callsSeed=${callsSeed}")
        } catch (e: Exception) {
            Log.e(tag, "Connection failed", e)
            throw e
        }
    }

    private suspend fun readLoop() {
        val stream = input ?: return
        try {
            while (currentCoroutineContext().isActive) {
                val header = ByteArray(10)
                stream.readFully(header)
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)

                val ver = buffer.get()
                val cmd = buffer.get()
                val seq = buffer.short.toInt() and 0xFFFF
                val opcode = buffer.short.toInt() and 0xFFFF
                val packedLen = buffer.int
                val payloadLen = packedLen and 0x00FFFFFF
                val flags = (packedLen ushr 24) and 0xFF

                var payloadBytes = ByteArray(payloadLen)
                if (payloadLen > 0) {
                    stream.readFully(payloadBytes)
                }

                if ((flags and 0x03) != 0 && payloadBytes.isNotEmpty()) {
                    try {
                        payloadBytes = decompressLz4(payloadBytes)
                    } catch (e: Exception) {
                        Log.e(tag, "LZ4 decompress error", e)
                    }
                }

                val payload = if (payloadBytes.isNotEmpty()) decodeMsgPack(payloadBytes) else emptyMap<String, Any>()

                Log.d(tag, "Received frame: cmd=$cmd, opcode=$opcode, seq=$seq, payloadLen=$payloadLen")
                handleIncomingFrame(cmd.toInt(), seq, opcode, payload)
            }
        } catch (e: Exception) {
            Log.e(tag, "Read loop error, socket closed", e)
            disconnect()
        }
    }

    private val fileUploadWaiters = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    private fun handleIncomingFrame(cmd: Int, seq: Int, opcode: Int, payload: Map<String, Any>) {
        if (cmd == 1 || cmd == 3) { // RESPONSE or ERROR
            pendingRequests.remove(seq)?.complete(payload)
        } else if (cmd == 2) { // EVENT
            // Обработка асинхронных событий (NOTIF_*) 
            Log.d(tag, "Received event: opcode=$opcode payload=$payload")
            if (opcode == 136) { // NOTIF_ATTACH
                val fileIdStr = payload["fileId"]?.toString() ?: payload["file_id"]?.toString()
                val fileId = fileIdStr?.toLongOrNull()
                ru.alexeyrf.maxmusic.Logger.log("Parsed fileId from NOTIF_ATTACH: $fileId")
                if (fileId != null) {
                    val waiter = fileUploadWaiters.remove(fileId)
                    if (waiter != null) {
                        ru.alexeyrf.maxmusic.Logger.log("Completing fileUploadWaiters for fileId=$fileId")
                        waiter.complete(true)
                    } else {
                        ru.alexeyrf.maxmusic.Logger.log("No waiter found for fileId=$fileId")
                    }
                } else {
                    ru.alexeyrf.maxmusic.Logger.log("NOTIF_ATTACH payload didn't contain valid file_id/fileId: $payload")
                }
            }
        }
    }

    private suspend fun sendRequest(cmd: Int, opcode: Int, payload: Map<String, Any>? = null, timeoutMs: Long = 30000L): Map<String, Any> = withContext(Dispatchers.IO) {
        val seq = seqCounter.incrementAndGet() and 0xFFFF
        val deferred = CompletableDeferred<Map<String, Any>>()
        pendingRequests[seq] = deferred
        sendFrame(cmd, seq, opcode, payload)
        
        // Wait for response
        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (response == null) {
            pendingRequests.remove(seq)
            throw Exception("Тайм-аут запроса для opcode $opcode")
        }
        
        // Проверка на ошибку. Pymax Command.ERROR = 3
        if (response.containsKey("error")) {
            val errMsg = "Ошибка API: ${response["error"]} - ${response["message"]}"
            Log.e(tag, errMsg)
            throw Exception(errMsg)
        }
        
        response
    }

    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun sendFrame(cmd: Int, seq: Int, opcode: Int, payload: Map<String, Any>? = null) = withContext(Dispatchers.IO) {
        try {
            val payloadBytes = if (payload != null) encodeMsgPack(payload) else ByteArray(0)
            val flags = 0
            val payloadLen = payloadBytes.size
            val packedLen = (flags shl 24) or (payloadLen and 0x00FFFFFF)

            val buffer = ByteBuffer.allocate(10 + payloadBytes.size).order(ByteOrder.BIG_ENDIAN)
            buffer.put(10.toByte()) // ver = 10 
            buffer.put(cmd.toByte())
            buffer.putShort(seq.toShort())
            buffer.putShort(opcode.toShort())
            buffer.putInt(packedLen)
            buffer.put(payloadBytes)

            writeMutex.withLock {
                output?.write(buffer.array())
                output?.flush()
            }
        } catch (e: Exception) {
            Log.e(tag, "Send frame error", e)
            disconnect()
            throw e
        }
    }

    // --- Авторизация ---
    suspend fun sendPhone(phone: String): Boolean {
        // AUTH_REQUEST = 17
        val modeBytes = generateFingerprint()
        val res = sendRequest(0, 17, mapOf("phone" to phone, "type" to "START_AUTH", "mode" to modeBytes, "language" to "ru"))
        authToken = res["token"] as? String
        if (authToken == null) {
            throw Exception("Ответ сервера не содержит token: $res")
        }
        return true
    }

    suspend fun sendSmsCode(code: String): Boolean {
        val token = authToken ?: throw Exception("Не найден authToken")
        // AUTH = 18
        val res = sendRequest(0, 18, mapOf("token" to token, "verifyCode" to code, "authTokenType" to "CHECK_CODE"))
        
        if (res["passwordChallenge"] != null) {
            val challenge = res["passwordChallenge"] as? Map<*, *>
            authTrackId = challenge?.get("trackId") as? String
            return true // Требуется 2FA
        }

        val tokenAttrs = res["tokenAttrs"] as? Map<*, *>
        val login = tokenAttrs?.get("LOGIN") as? Map<*, *>
        if (login?.get("token") != null) {
            authToken = login["token"] as String
            doLogin()
            return false // success, no 2FA
        }
        throw Exception("Ответ не содержит login token: $res")
    }

    suspend fun sendPassword(password: String): Boolean {
        val trackId = authTrackId ?: return false
        // AUTH_LOGIN_CHECK_PASSWORD = 115, in older versions 107. We use 115 per pymax.
        val res = sendRequest(0, 115, mapOf("trackId" to trackId, "password" to password))
        
        val loginToken = res["loginToken"] as? String
        val tokenAttrs = res["tokenAttrs"] as? Map<*, *>
        val login = tokenAttrs?.get("LOGIN") as? Map<*, *>
        val loginToken2 = login?.get("token") as? String
        val fallbackToken = res["token"] as? String
        
        val finalToken = loginToken ?: loginToken2 ?: fallbackToken
        if (finalToken != null) {
            authToken = finalToken
            return doLogin()
        }
        return false
    }

    private suspend fun doLogin(): Boolean {
        // LOGIN = 19
        val token = authToken ?: return false
        
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
        val systemVersion = android.os.Build.VERSION.RELEASE
        
        val res = sendRequest(0, 19, mapOf(
            "token" to token,
            "userAgent" to mapOf(
                "appVersion" to "26.25.0", 
                "deviceType" to "ANDROID",
                "systemVersion" to systemVersion,
                "deviceName" to deviceName,
                "systemLang" to "ru"
            ),
            "chatsSync" to -1,
            "contactsSync" to -1,
            "draftsSync" to -1,
            "presenceSync" to -1,
            "interactive" to true
        ))
        
        if (res["token"] != null) {
            authToken = res["token"] as String
        }
        isAuthenticated = true
        return true
    }

    suspend fun resumeSession(): Boolean {
        if (authToken == null) return false
        return try {
            connect()
            doLogin()
        } catch (e: Exception) {
            Log.e(tag, "Resume session failed", e)
            false
        }
    }

    private fun fixEncoding(text: String?): String? {
        if (text == null) return null
        
        // Check for CP1251 decoded as CP1252/Latin1 (characters in \u00C0-\u00FF range, e.g. Áóì)
        val hasLatin1Cyrillic = text.any { it.code in 0xC0..0xFF }
        // Check for UTF-8 decoded as CP1252/Latin1
        val hasUtf8AsLatin1 = text.contains('\u00D0') || text.contains('\u00D1')
        
        if (hasLatin1Cyrillic || hasUtf8AsLatin1) {
            try {
                // Try treating the string as CP1252 bytes
                val cp1252 = java.nio.charset.Charset.forName("windows-1252")
                val cp1251 = java.nio.charset.Charset.forName("windows-1251")
                val utf8 = Charsets.UTF_8
                
                // Convert chars back to bytes
                val bytes = text.toByteArray(cp1252)
                
                // If it looks like UTF-8 (has 0xD0/0xD1), try UTF-8 first
                if (hasUtf8AsLatin1) {
                    try {
                        // Validate UTF-8 decoding
                        val decodedUtf8 = String(bytes, utf8)
                        if (!decodedUtf8.contains("\uFFFD")) {
                            return decodedUtf8
                        }
                    } catch (e: Exception) {}
                }
                
                // Otherwise try CP1251
                return String(bytes, cp1251)
            } catch (e: Exception) {
                // ignore
            }
        }
        return text
    }

    // --- Работа с Музыкой ---
    private var cachedMusicList: MutableList<MusicMessage>? = null
    private var isHistoryFullyLoaded = false

    suspend fun getSavedMusic(retryCount: Int = 1): List<MusicMessage> {
        if (authToken == null) {
            val localDir = java.io.File(context?.filesDir, "local_tracks")
            if (!localDir.exists()) localDir.mkdirs()
            val files = localDir.listFiles() ?: emptyArray()
            val list = mutableListOf<MusicMessage>()
            for (file in files) {
                val fileId = file.name.hashCode().toLong()
                val messageId = file.lastModified()
                list.add(MusicMessage(messageId, fileId, file.name, file.absolutePath, 0, null, null))
            }
            return list.sortedByDescending { it.messageId }
        }
        try {
            if (!isConnected()) {
                resumeSession()
            }
            
            val allMessages = mutableListOf<MusicMessage>()
            val knownIds = cachedMusicList?.map { it.messageId }?.toSet() ?: emptySet()
            var currentFrom = System.currentTimeMillis()
            var reachedKnown = false
            var apiCalls = 0
            
            while (!reachedKnown && apiCalls < 50) { // max 5000 messages history
                apiCalls++
                val res = sendRequest(0, 49, mapOf(
                    "chatId" to 0,
                    "backward" to 100,
                    "from" to currentFrom,
                    "itemType" to "REGULAR",
                    "getMessages" to true,
                    "interactive" to false
                ), timeoutMs = 15000L) // reduced timeout to fail faster on dead sockets
                
                val msgsList = res["messages"] as? List<*> ?: break
                if (msgsList.isEmpty()) {
                    isHistoryFullyLoaded = true
                    break
                }
                
                var minTime = currentFrom
                var fetchedCount = 0
                
                for (msgObj in msgsList) {
                    val msg = msgObj as? Map<*, *> ?: continue
                    val msgId = (msg["id"] as? Number)?.toLong() ?: continue
                    val msgTime = (msg["time"] as? Number)?.toLong() ?: continue
                    
                    if (msgTime < minTime) minTime = msgTime
                    fetchedCount++
                    
                    if (msgId in knownIds) {
                        reachedKnown = true
                        continue
                    }
                    
                    val attaches = msg["attaches"] as? List<*> ?: continue
                    for (attachObj in attaches) {
                        val attach = attachObj as? Map<*, *> ?: continue
                        val fileId = (attach["fileId"] as? Number)?.toLong()

                        var filename = attach["filename"] as? String
                            ?: attach["fileName"] as? String
                            ?: attach["name"] as? String
                            ?: attach["title"] as? String
                            ?: (attach["file"] as? Map<*, *>)?.get("name") as? String
                            ?: (attach["file"] as? Map<*, *>)?.get("filename") as? String
                            ?: "Unknown file"
                            
                        filename = fixEncoding(filename) ?: "Unknown file"

                        val ext = filename.substringAfterLast('.', "").lowercase()

                        val preview = attach["preview"] as? Map<*, *>
                        val serverTitle = fixEncoding(preview?.get("title") as? String)
                        val serverArtist = fixEncoding(preview?.get("artist") as? String)

                        if (fileId != null && (ext in listOf("mp3", "flac", "wav", "zmusic", "z2", "mp4", "mkv", "avi", "webm", "mov") || filename.contains(".zconfig"))) {
                            allMessages.add(MusicMessage(msgId, fileId, filename, "", 0, serverTitle, serverArtist))
                        }
                    }
                }
                
                if (reachedKnown) break
                if (fetchedCount < 100) {
                    isHistoryFullyLoaded = true
                    break
                }
                currentFrom = minTime
            }
            
            val combined = (allMessages + (cachedMusicList ?: emptyList()))
                .distinctBy { it.messageId }
                .sortedByDescending { it.messageId }
            
            cachedMusicList = combined.toMutableList()
            return cachedMusicList!!
        } catch (e: Exception) {
            Log.e(tag, "Failed to getSavedMusic, retryCount=$retryCount", e)
            if (retryCount > 0) {
                disconnect()
                return getSavedMusic(retryCount - 1)
            }
            // If all retries fail, return cached tracks
            return cachedMusicList ?: ru.alexeyrf.maxmusic.AudioCacheManager.getCachedTracks()
        }
    }
    
    suspend fun getFileDownloadUrl(messageId: Long, fileId: Long): String? {
        if (authToken == null) {
            val localDir = java.io.File(context?.filesDir, "local_tracks")
            val files = localDir.listFiles() ?: emptyArray()
            for (file in files) {
                if (file.name.hashCode().toLong() == fileId) {
                    return "file://" + file.absolutePath
                }
            }
            return null
        }
        return try {
            if (!isConnected()) {
                resumeSession()
            }
            // FILE_DOWNLOAD = 88
            val res = sendRequest(0, 88, mapOf(
                "chatId" to 0,
                "messageId" to messageId,
                "fileId" to fileId
            ), timeoutMs = 10000L)
            res["url"] as? String
        } catch (e: Exception) {
            Log.e(tag, "getFileDownloadUrl failed", e)
            null
        }
    }

    suspend fun deleteTrack(msgId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected()) return@withContext false
        try {
            // MSG_DELETE = 66
            val payload = mapOf(
                "chatId" to 0,
                "messageIds" to listOf(msgId),
                "forMe" to false
            )
            sendRequest(0, 66, payload, timeoutMs = 10000L)
            cachedMusicList?.removeAll { it.messageId == msgId }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to delete track", e)
            false
        }
    }

    suspend fun sendMusic(fileName: String, fileBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Started upload for fileName='$fileName', size=${fileBytes.size} bytes")
            // FILE_UPLOAD = 87
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Requesting upload URL via FILE_UPLOAD (87)...")
            val uploadRes = sendRequest(0, 87, mapOf("count" to 1, "profile" to false), 90000L)
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Got FILE_UPLOAD response: $uploadRes")
            
            val infoList = uploadRes["info"] as? List<*> ?: throw Exception("Сервер не вернул info для FILE_UPLOAD: $uploadRes")
            val info = infoList.firstOrNull() as? Map<*, *> ?: throw Exception("Пустой infoList")
            
            var urlStr = info["url"] as? String ?: throw Exception("Нет url в info: $info")
            if (urlStr.startsWith("http://")) {
                urlStr = urlStr.replaceFirst("http://", "https://")
                ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Converted http URL to https")
            }
            val fileId = (info["fileId"] as? Number)?.toLong() ?: throw Exception("Нет fileId в info: $info")
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Got upload URL: $urlStr | fileId: $fileId")
            
            val waiter = CompletableDeferred<Boolean>()
            fileUploadWaiters[fileId] = waiter
            
            // HTTP POST to upload URL
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Opening HTTP POST connection to $urlStr")
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 90000
            conn.setFixedLengthStreamingMode(fileBytes.size)
            val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
            conn.setRequestProperty("Content-Disposition", "attachment; filename=$encodedName")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", fileBytes.size.toString())
            conn.setRequestProperty("Content-Range", "0-${fileBytes.size - 1}/${fileBytes.size}")
            
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Writing ${fileBytes.size} bytes to HTTP stream...")
            try {
                conn.outputStream.use { it.write(fileBytes) }
                
                val responseCode = conn.responseCode
                ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] HTTP response code: $responseCode")
                
                if (responseCode in 200..299) {
                    val responseText = conn.inputStream?.bufferedReader()?.use { it.readText() }
                    ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] HTTP Upload Success! Response text: $responseText")
                } else {
                    val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] HTTP Upload Failed: $responseCode | Msg: $errorMsg")
                    throw Exception("Upload failed with HTTP $responseCode. Error: $errorMsg")
                }
            } catch (e: Exception) {
                ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Exception during HTTP POST: ${e.message}")
                fileUploadWaiters.remove(fileId)
                throw e
            }
            
            // Wait for EVENT 136 (NOTIF_ATTACH) from server
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Waiting for NOTIF_ATTACH (136) on TCP socket for fileId=$fileId...")
            val isReady = withTimeoutOrNull(5000) { waiter.await() }
            if (isReady == null) {
                ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Waiter timed out (5s)! Trying MSG_SEND anyway...")
                fileUploadWaiters.remove(fileId)
            } else {
                ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] NOTIF_ATTACH received, file is ready on server side.")
            }
            
            // MSG_SEND = 64
            val msgSendPayload = mapOf(
                "chatId" to 0,
                "notify" to true,
                "message" to mapOf(
                    "text" to "",
                    "cid" to System.currentTimeMillis(),
                    "elements" to emptyList<Any>(),
                    "attaches" to listOf(mapOf("fileId" to fileId, "_type" to "FILE"))
                )
            )
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Sending MSG_SEND (64) with payload: $msgSendPayload")
            val sendRes = sendRequest(0, 64, msgSendPayload)
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Got MSG_SEND response: $sendRes")
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Finished successfully!")
            return@withContext true
        } catch (e: Exception) {
            ru.alexeyrf.maxmusic.Logger.log(">>> [sendMusic] Send music error: ${e.message}")
            throw e
        }
    }

    fun disconnect() {
        try {
            pingJob?.cancel()
            socket?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            socket = null
            input = null
            output = null
        }
    }

    fun isConnected(): Boolean {
        return socket != null && socket?.isClosed == false
    }

    // ==========================================
    // Утилиты сериализации / десериализации MsgPack
    // ==========================================

    private fun encodeMsgPack(map: Map<String, Any>): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packValue(packer, map)
        val bytes = packer.toByteArray()
        packer.close()
        return bytes
    }

    private fun packValue(packer: MessagePacker, value: Any?) {
        when (value) {
            null -> packer.packNil()
            is Int -> packer.packInt(value)
            is Long -> packer.packLong(value)
            is String -> packer.packString(value)
            is Boolean -> packer.packBoolean(value)
            is ByteArray -> {
                packer.packBinaryHeader(value.size)
                packer.writePayload(value)
            }
            is List<*> -> {
                packer.packArrayHeader(value.size)
                value.forEach { packValue(packer, it) }
            }
            is Map<*, *> -> {
                packer.packMapHeader(value.size)
                value.forEach { (k, v) ->
                    packValue(packer, k)
                    packValue(packer, v)
                }
            }
            else -> {
                if (value is Number) {
                    packer.packLong(value.toLong())
                } else {
                    packer.packNil()
                }
            }
        }
    }

    private fun decodeMsgPack(bytes: ByteArray): Map<String, Any> {
        if (bytes.isEmpty()) return emptyMap()
        try {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            if (!unpacker.hasNext()) return emptyMap()
            val value = unpacker.unpackValue()
            @Suppress("UNCHECKED_CAST")
            return unpackValue(value) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) {
            Log.e(tag, "Msgpack decode error", e)
            return emptyMap()
        }
    }

    private fun unpackValue(value: Value): Any? {
        return when (value.valueType) {
            ValueType.NIL -> null
            ValueType.BOOLEAN -> value.asBooleanValue().boolean
            ValueType.INTEGER -> value.asIntegerValue().toLong()
            ValueType.FLOAT -> value.asFloatValue().toFloat()
            ValueType.STRING -> value.asStringValue().asString()
            ValueType.BINARY -> value.asBinaryValue().asByteArray()
            ValueType.ARRAY -> value.asArrayValue().map { unpackValue(it) }
            ValueType.MAP -> {
                val map = mutableMapOf<String, Any>()
                val kvArray = value.asMapValue().keyValueArray
                for (i in kvArray.indices step 2) {
                    val keyObj = unpackValue(kvArray[i])
                    val k = when (keyObj) {
                        is ByteArray -> String(keyObj, Charsets.UTF_8)
                        is String -> keyObj
                        else -> keyObj?.toString() ?: ""
                    }
                    val v = unpackValue(kvArray[i + 1])
                    if (v != null) map[k] = v
                }
                map
            }
            else -> null
        }
    }

    private fun decompressLz4(src: ByteArray, maxOutput: Int = 5 * 1024 * 1024): ByteArray {
        var dst = ByteArray(1024)
        var dstSize = 0

        fun ensureCapacity(needed: Int) {
            if (dstSize + needed > dst.size) {
                var newCap = dst.size * 2
                while (newCap < dstSize + needed) newCap *= 2
                dst = dst.copyOf(newCap)
            }
        }

        var pos = 0
        while (pos < src.size) {
            val token = src[pos].toInt() and 0xFF
            pos++

            var litLen = token ushr 4
            if (litLen == 15) {
                while (pos < src.size) {
                    val b = src[pos].toInt() and 0xFF
                    pos++
                    litLen += b
                    if (b != 255) break
                }
            }

            if (litLen > 0) {
                if (pos + litLen > src.size) throw Exception("LZ4: literal length out of bounds")
                ensureCapacity(litLen)
                System.arraycopy(src, pos, dst, dstSize, litLen)
                dstSize += litLen
                pos += litLen
                if (dstSize > maxOutput) throw Exception("LZ4: output too large")
            }

            if (pos >= src.size) break
            if (pos + 1 >= src.size) throw Exception("LZ4: incomplete offset")

            val offset = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            if (offset == 0) throw Exception("LZ4: zero offset")

            var matchLen = (token and 0x0F) + 4
            if ((token and 0x0F) == 0x0F) {
                while (pos < src.size) {
                    val b = src[pos].toInt() and 0xFF
                    pos++
                    matchLen += b
                    if (b != 255) break
                }
            }

            val matchPos = dstSize - offset
            if (matchPos < 0) throw Exception("LZ4: match out of bounds")

            ensureCapacity(matchLen)
            for (i in 0 until matchLen) {
                dst[dstSize] = dst[matchPos + (i % offset)]
                dstSize++
            }
            if (dstSize > maxOutput) throw Exception("LZ4: output too large")
        }

        return dst.copyOf(dstSize)
    }
}
