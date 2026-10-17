package ru.alexeyrf.maxmusic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

object ProxyManager {
    private const val PREFS_NAME = "settings"
    private const val KEY_ENABLED = "youtube_proxy_enabled"
    private const val KEY_HOST = "youtube_proxy_host"
    private const val KEY_PORT = "youtube_proxy_port"
    private const val KEY_USER = "youtube_proxy_user"
    private const val KEY_PASS = "youtube_proxy_pass"

    fun isProxyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setProxyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun getProxyHost(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOST, "") ?: ""
    }

    fun getProxyPort(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, 1080)
    }

    fun getProxyUser(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER, "") ?: ""
    }

    fun getProxyPass(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PASS, "") ?: ""
    }

    fun setProxyConfig(context: Context, enabled: Boolean, host: String, port: Int, user: String = "", pass: String = "") {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASS, pass)
            .apply()
    }

    fun getProxy(context: Context): Proxy {
        if (!isProxyEnabled(context)) return Proxy.NO_PROXY
        val host = getProxyHost(context)
        val port = getProxyPort(context)
        if (host.isBlank() || port <= 0 || port > 65535) return Proxy.NO_PROXY

        val user = getProxyUser(context)
        val pass = getProxyPass(context)
        if (user.isNotBlank()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestingHost.equals(host, ignoreCase = true) && requestingPort == port) {
                        return PasswordAuthentication(user, pass.toCharArray())
                    }
                    return super.getPasswordAuthentication()
                }
            })
        }

        return try {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        } catch (e: Exception) {
            e.printStackTrace()
            Proxy.NO_PROXY
        }
    }

    suspend fun testConnection(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val proxy = getProxy(context)
        try {
            val url = URL("https://www.youtube.com/generate_204")
            val conn = url.openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            val code = conn.responseCode
            if (code == 204 || code == 200) {
                return@withContext true to "Прокси работает отлично! Доступ к YouTube получен."
            } else {
                return@withContext false to "Сервер вернул код $code"
            }
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            return@withContext false to "Ошибка подключения: $msg"
        }
    }
}
