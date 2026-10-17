package ru.alexeyrf.maxmusic

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

object LocalProxyServer {
    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    private var serverJob: Job? = null
    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (serverSocket != null && !serverSocket!!.isClosed) return

        try {
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverPort = serverSocket!!.localPort
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getStreamUrl(context: Context, targetUrl: String): String {
        start(context)
        if (!ProxyManager.isProxyEnabled(context)) {
            return targetUrl
        }
        if (serverPort <= 0) return targetUrl
        return try {
            "http://127.0.0.1:$serverPort/stream?url=" + URLEncoder.encode(targetUrl, "UTF-8")
        } catch (e: Exception) {
            targetUrl
        }
    }

    private fun handleClient(client: Socket) {
        val ctx = appContext ?: return
        try {
            client.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return

            val path = parts[1]
            if (!path.startsWith("/stream?url=")) {
                client.close()
                return
            }

            val encodedTarget = path.substringAfter("/stream?url=")
            val targetUrl = URLDecoder.decode(encodedTarget, "UTF-8")

            var rangeHeader: String? = null
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
                line = reader.readLine()
            }

            val proxy = ProxyManager.getProxy(ctx)
            var currentUrl = targetUrl
            var connection: HttpURLConnection? = null
            var redirects = 0
            val maxRedirects = 5

            while (redirects < maxRedirects) {
                val u = URL(currentUrl)
                val c = (u.openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", YouTubeExtractor.USER_AGENT)
                    if (!rangeHeader.isNullOrBlank()) {
                        setRequestProperty("Range", rangeHeader)
                    }
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
                connection = c
                break
            }

            val finalConn = connection ?: run {
                client.close()
                return
            }

            val responseCode = finalConn.responseCode
            val responseMsg = finalConn.responseMessage ?: "OK"
            val out: OutputStream = client.getOutputStream()

            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 $responseCode $responseMsg\r\n")

            finalConn.headerFields.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) {
                    val k = key.lowercase()
                    if (k in listOf("content-type", "content-length", "content-range", "accept-ranges")) {
                        headerBuilder.append("$key: ${values.first()}\r\n")
                    }
                }
            }
            headerBuilder.append("Connection: close\r\n\r\n")
            out.write(headerBuilder.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val inputStream = if (responseCode in 200..299) finalConn.inputStream else finalConn.errorStream
            if (inputStream != null) {
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    try {
                        out.write(buffer, 0, bytesRead)
                    } catch (e: Exception) {
                        break // Client disconnected
                    }
                }
                try { out.flush() } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            // Client disconnect or timeout
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    fun stop() {
        try {
            serverJob?.cancel()
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        serverPort = 0
    }
}
