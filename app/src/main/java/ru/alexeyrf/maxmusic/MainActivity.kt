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

fun getSessionPrefs(context: Context): android.content.SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "session_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

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

object Logger {
    val logs = mutableStateListOf<String>()
    fun log(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$time] $msg"
        android.util.Log.d("ZMusic", formatted)
        try {
            if (logs.size > 500) {
                logs.removeAt(logs.size - 1)
            }
            logs.add(0, formatted)
        } catch (e: Exception) {
            // ignore concurrent modifications
        }
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
        val oldPrefs = getEncryptedMaxmusicPrefs(this)
        val sessionPrefs = getSessionPrefs(this)
        val oldToken = oldPrefs.getString("auth_token", null)
        if (oldToken != null) {
            sessionPrefs.edit().putString("auth_token", oldToken).apply()
            oldPrefs.edit().remove("auth_token").apply()
        }
        maxClient.context = this
        AudioCacheManager.init(this)
        VibeManager.init(this)
        AudioPlayer.initSettings(this)
        enableEdgeToEdge()
        setContent {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            var useMaterial by remember { mutableStateOf(prefs.getBoolean("material_ui", true)) }
            
            // Listen to prefs changes
            DisposableEffect(Unit) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == "material_ui") useMaterial = p.getBoolean(key, true)
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            ZMusicTheme(dynamicColor = useMaterial) {
                var currentScreen by remember { mutableStateOf(Screen.Auth) }
                var isStartupAuth by remember { mutableStateOf(true) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        Screen.Auth -> {
                            AuthScreen(
                                modifier = Modifier.padding(innerPadding),
                                maxClient = maxClient,
                                isStartup = isStartupAuth,
                                onAuthSuccess = { currentScreen = Screen.MusicList }
                            )
                        }
                        Screen.MusicList -> {
                            MusicListScreen(
                                modifier = Modifier,
                                maxClient = maxClient,
                                onFileSelected = {},
                                onShowSettings = { currentScreen = Screen.Settings },
                                onGoToAuth = { 
                                    isStartupAuth = false
                                    currentScreen = Screen.Auth 
                                }
                            )
                        }
                        Screen.Settings -> {
                            SettingsScreen(
                                modifier = Modifier,
                                maxClient = maxClient,
                                onBack = { currentScreen = Screen.MusicList },
                                onGoToAuth = {
                                    isStartupAuth = false
                                    currentScreen = Screen.Auth
                                }
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
fun AuthScreen(modifier: Modifier = Modifier, maxClient: MaxClient, isStartup: Boolean = true, onAuthSuccess: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableStateOf(AuthStep.Loading) }
    
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val prefs = getEncryptedMaxmusicPrefs(context)
    val sessionPrefs = getSessionPrefs(context)

    LaunchedEffect(Unit) {
        val savedToken = sessionPrefs.getString("auth_token", null)
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
            if (isStartup) onAuthSuccess() else step = AuthStep.Phone
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
                    singleLine = true,
                    modifier = Modifier.autofill(listOf(androidx.compose.ui.autofill.ContentType.PhoneNumber), currentValue = phone) { phone = it }
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
                    singleLine = true,
                    modifier = Modifier.autofill(listOf(androidx.compose.ui.autofill.ContentType.SmsOtpCode), currentValue = code) { code = it }
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
                                    sessionPrefs.edit().putString("auth_token", maxClient.authToken).apply()
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
                    value = phone,
                    onValueChange = {},
                    label = { Text("Номер телефона") },
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier.autofill(listOf(androidx.compose.ui.autofill.ContentType.Username), currentValue = phone) {}
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль 2FA") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Text)
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
                                    sessionPrefs.edit().putString("auth_token", maxClient.authToken).apply()
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

