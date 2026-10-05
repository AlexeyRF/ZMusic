package ru.alexeyrf.maxmusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object RutubeExtractor {

    data class RutubeVideo(
        val id: String,
        val title: String,
        val author: String,
        val thumbnailUrl: String,
        val duration: Int
    )

    suspend fun search(query: String): List<RutubeVideo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<RutubeVideo>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://rutube.ru/api/search/video/?query=$encodedQuery")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val results = json.optJSONArray("results")
                
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val title = item.optString("title")
                        val author = item.optJSONObject("author")?.optString("name") ?: ""
                        val thumbnailUrl = item.optString("thumbnail_url")
                        val duration = item.optInt("duration", 0)
                        
                        if (id.isNotEmpty()) {
                            list.add(RutubeVideo(id, title, author, thumbnailUrl, duration))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun getPlayUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://rutube.ru/api/play/options/$videoId/?format=json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val m3u8 = json.optJSONObject("video_balancer")?.optString("m3u8")
                if (!m3u8.isNullOrEmpty()) {
                    return@withContext m3u8
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
    suspend fun downloadVideo(videoId: String, outputFile: java.io.File, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val m3u8Url = getPlayUrl(videoId) ?: return@withContext false
            val m3u8Content = URL(m3u8Url).readText()
            
            // Find highest quality or first stream
            val streamLines = m3u8Content.lines().filter { !it.startsWith("#") && it.isNotBlank() }
            if (streamLines.isEmpty()) return@withContext false
            var bestStreamUrl = streamLines.last()
            if (!bestStreamUrl.startsWith("http")) bestStreamUrl = URL(URL(m3u8Url), bestStreamUrl).toString()
            
            val streamContent = URL(bestStreamUrl).readText()
            val tsUrls = streamContent.lines().filter { !it.startsWith("#") && it.isNotBlank() }
            if (tsUrls.isEmpty()) return@withContext false
            
            val out = java.io.FileOutputStream(outputFile)
            for ((index, tsUrl) in tsUrls.withIndex()) {
                val fullUrl = if (tsUrl.startsWith("http")) tsUrl else URL(URL(bestStreamUrl), tsUrl).toString()
                val chunk = URL(fullUrl).readBytes()
                out.write(chunk)
                onProgress((index * 100) / tsUrls.size)
            }
            out.close()
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        false
    }
}


