package ru.alexeyrf.maxmusic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object YouTubeExtractor {

    data class YouTubeVideo(
        val id: String,
        val title: String,
        val author: String,
        val thumbnailUrl: String,
        val duration: String
    )

    private const val ANDROID_CLIENT_VERSION = "21.02.35"
    const val USER_AGENT = "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip"

    private fun getClientContext(clientName: String = "ANDROID", clientVersion: String = ANDROID_CLIENT_VERSION): JSONObject {
        val client = JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            if (clientName == "ANDROID") {
                put("androidSdkVersion", 30)
                put("userAgent", USER_AGENT)
                put("osName", "Android")
                put("osVersion", "11")
            } else {
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
            }
            put("hl", "ru")
            put("gl", "US")
        }
        return JSONObject().apply {
            put("client", client)
        }
    }

    suspend fun search(context: Context, query: String): List<YouTubeVideo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<YouTubeVideo>()
        try {
            val proxy = ProxyManager.getProxy(context)
            val url = URL("https://www.youtube.com/youtubei/v1/search")
            val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("X-Youtube-Client-Name", "3")
                setRequestProperty("X-Youtube-Client-Version", ANDROID_CLIENT_VERSION)
                setRequestProperty("Origin", "https://www.youtube.com")
            }

            val payload = JSONObject().apply {
                put("context", getClientContext())
                put("query", query)
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(responseText)
                val sections = json.optJSONObject("contents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                if (sections != null) {
                    for (i in 0 until sections.length()) {
                        val sec = sections.optJSONObject(i) ?: continue
                        val itemSection = sec.optJSONObject("itemSectionRenderer") ?: continue
                        val contents = itemSection.optJSONArray("contents") ?: continue

                        for (j in 0 until contents.length()) {
                            val item = contents.optJSONObject(j) ?: continue
                            val renderer = item.optJSONObject("compactVideoRenderer")
                                ?: item.optJSONObject("videoRenderer")
                                ?: continue

                            val videoId = renderer.optString("videoId")
                            if (videoId.isEmpty()) continue

                            val titleObj = renderer.optJSONObject("title")
                            val title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                ?: titleObj?.optString("simpleText")
                                ?: "Без названия"

                            val bylineObj = renderer.optJSONObject("shortBylineText")
                                ?: renderer.optJSONObject("longBylineText")
                                ?: renderer.optJSONObject("ownerText")
                            val author = bylineObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                ?: bylineObj?.optString("simpleText")
                                ?: ""

                            val thumbArray = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val thumbnailUrl = if (thumbArray != null && thumbArray.length() > 0) {
                                thumbArray.optJSONObject(thumbArray.length() - 1)?.optString("url") ?: ""
                            } else ""

                            val lengthObj = renderer.optJSONObject("lengthText")
                            val duration = lengthObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                ?: lengthObj?.optString("simpleText")
                                ?: ""

                            list.add(YouTubeVideo(videoId, title, author, thumbnailUrl, duration))
                        }
                    }
                }
            } else {
                Logger.log("Ошибка поиска YouTube: HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Logger.log("Сбой поиска YouTube: ${e.localizedMessage ?: e.message}")
        }
        list
    }

    private fun fetchPlayUrl(
        context: Context,
        videoId: String,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        sdkVersion: Int?
    ): String? {
        try {
            val proxy = ProxyManager.getProxy(context)
            val url = URL("https://www.youtube.com/youtubei/v1/player")
            val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", userAgent)
                if (clientName == "ANDROID") {
                    setRequestProperty("X-Youtube-Client-Name", "3")
                    setRequestProperty("X-Youtube-Client-Version", clientVersion)
                    setRequestProperty("Origin", "https://www.youtube.com")
                }
            }

            val clientObj = JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                if (sdkVersion != null) {
                    put("androidSdkVersion", sdkVersion)
                    put("userAgent", userAgent)
                    put("osName", "Android")
                    put("osVersion", "11")
                } else {
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                }
                put("hl", "ru")
                put("gl", "US")
            }

            val payload = JSONObject().apply {
                put("context", JSONObject().apply { put("client", clientObj) })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(responseText)
                val playabilityStatus = json.optJSONObject("playabilityStatus")
                val status = playabilityStatus?.optString("status")
                if (status != null && status != "OK") {
                    val reason = playabilityStatus.optString("reason")
                    Logger.log("YouTube player status ($clientName): $status, reason: $reason")
                }

                val streamingData = json.optJSONObject("streamingData") ?: return null

                // 1. Try adaptiveFormats for audio-only streams with direct URL
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                if (adaptiveFormats != null) {
                    var bestAudioUrl: String? = null
                    var maxBitrate = 0
                    var preferredM4aUrl: String? = null

                    for (i in 0 until adaptiveFormats.length()) {
                        val fmt = adaptiveFormats.optJSONObject(i) ?: continue
                        val mimeType = fmt.optString("mimeType")
                        val streamUrl = fmt.optString("url")
                        val itag = fmt.optInt("itag")
                        val bitrate = fmt.optInt("bitrate", 0)

                        if (mimeType.contains("audio", ignoreCase = true) && streamUrl.isNotEmpty()) {
                            if (itag == 140) {
                                preferredM4aUrl = streamUrl
                            }
                            if (bitrate > maxBitrate) {
                                maxBitrate = bitrate
                                bestAudioUrl = streamUrl
                            }
                        }
                    }
                    val chosen = preferredM4aUrl ?: bestAudioUrl
                    if (chosen != null) return chosen
                }

                // 2. Try formats (combined video+audio, e.g. itag 18 is 360p MP4 with 128k AAC audio)
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    var itag18Url: String? = null
                    var anyFormatUrl: String? = null
                    for (i in 0 until formats.length()) {
                        val fmt = formats.optJSONObject(i) ?: continue
                        val streamUrl = fmt.optString("url")
                        val itag = fmt.optInt("itag")
                        if (streamUrl.isNotEmpty()) {
                            if (itag == 18) {
                                itag18Url = streamUrl
                            }
                            if (anyFormatUrl == null) {
                                anyFormatUrl = streamUrl
                            }
                        }
                    }
                    val chosen = itag18Url ?: anyFormatUrl
                    if (chosen != null) return chosen
                }
            } else {
                Logger.log("Ошибка плеера YouTube ($clientName): HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Logger.log("Сбой получения аудиопотока YouTube ($clientName): ${e.localizedMessage ?: e.message}")
        }
        return null
    }

    suspend fun getPlayUrl(context: Context, videoId: String): String? = withContext(Dispatchers.IO) {
        // 1. Try ANDROID client (21.02.35)
        var playUrl = fetchPlayUrl(
            context = context,
            videoId = videoId,
            clientName = "ANDROID",
            clientVersion = ANDROID_CLIENT_VERSION,
            userAgent = USER_AGENT,
            sdkVersion = 30
        )
        if (playUrl == null) {
            // 2. Fallback to ANDROID_VR
            playUrl = fetchPlayUrl(
                context = context,
                videoId = videoId,
                clientName = "ANDROID_VR",
                clientVersion = "1.65.10",
                userAgent = "Mozilla/5.0 (Android; Mobile VR) AppleWebKit/537.36",
                sdkVersion = null
            )
        }
        playUrl
    }

    suspend fun downloadAudio(
        context: Context, 
        videoId: String, 
        outputFile: File, 
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val playUrl = getPlayUrl(context, videoId) ?: run {
                Logger.log("Не удалось получить ссылку потока для скачивания YouTube: $videoId")
                return@withContext false
            }
            val proxy = ProxyManager.getProxy(context)
            
            var currentUrl = playUrl
            var conn: HttpURLConnection? = null
            var redirects = 0
            val maxRedirects = 6

            while (redirects < maxRedirects) {
                val u = URL(currentUrl)
                val c = (u.openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                val code = c.responseCode
                if (code in 301..308) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (!loc.isNullOrBlank()) {
                        currentUrl = if (loc.startsWith("http")) loc else URL(u, loc).toString()
                        redirects++
                        continue
                    }
                }
                conn = c
                break
            }

            val finalConn = conn ?: return@withContext false
            val responseCode = finalConn.responseCode
            if (responseCode in 200..299) {
                val totalLength = finalConn.contentLength.toLong().takeIf { it > 0 } ?: (10L * 1024 * 1024)
                var totalBytesRead = 0L

                finalConn.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            val pct = ((totalBytesRead * 100) / totalLength).toInt().coerceIn(0, 100)
                            onProgress(pct)
                        }
                    }
                }
                return@withContext outputFile.exists() && outputFile.length() > 0
            } else {
                Logger.log("Ошибка скачивания YouTube: HTTP $responseCode")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Logger.log("Сбой скачивания аудио YouTube: ${e.localizedMessage ?: e.message}")
        }
        false
    }
}
