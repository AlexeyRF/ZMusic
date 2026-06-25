import sys
import os

filepath = r'D:\Documents\mm\app\src\main\java\ru\alexeyrf\maxmusic\MainActivity.kt'
with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
    lines = f.readlines()

new_code = '''class MainActivity : ComponentActivity() {
    private val maxClient = MaxClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioCacheManager.init(this)
        enableEdgeToEdge()
        setContent {
            MAXMusicTheme {
                var currentScreen by remember { mutableStateOf(Screen.Auth) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        Screen.Auth -> {
                            AuthScreen(
                                modifier = Modifier.padding(innerPadding),
                                maxClient = maxClient,
                                onAuthSuccess = { currentScreen = Screen.MusicList }
                            )
                        }
                        Screen.MusicList -> {
                            MusicListScreen(
                                modifier = Modifier.padding(innerPadding),
                                maxClient = maxClient,
                                onFileSelected = {},
                                onShowLogs = { currentScreen = Screen.Logs }
                            )
                        }
                        Screen.Logs -> {
                            LogsScreen(
                                modifier = Modifier.padding(innerPadding),
                                onBack = { currentScreen = Screen.MusicList }
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
    Auth, MusicList, Logs
}

@Composable
fun AuthScreen(modifier: Modifier = Modifier, maxClient: MaxClient, onAuthSuccess: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableStateOf(AuthStep.Loading) }
    
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val prefs = context.getSharedPreferences("maxmusic_prefs", Context.MODE_PRIVATE)

    LaunchedEffect(Unit) {
        val savedToken = prefs.getString("auth_token", null)
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
            step = AuthStep.Phone
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
                Text("Вход в PyMax Music", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Номер телефона") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
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
                    singleLine = true
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
                                    prefs.edit().putString("auth_token", maxClient.authToken).apply()
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
            }
            AuthStep.Password -> {
                Text("Двухфакторная аутентификация", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль 2FA") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
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
                                    prefs.edit().putString("auth_token", maxClient.authToken).apply()
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

enum class AuthStep {
    Loading, Phone, Code, Password
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicListScreen(
    modifier: Modifier = Modifier, 
    maxClient: MaxClient,
    onFileSelected: (Uri) -> Unit,
    onShowLogs: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var musicList by remember { mutableStateOf<List<MusicMessage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val contentResolver = context.contentResolver
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
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
                    
                    try {
                        val success = maxClient.sendMusic(fileName, bytes)
                        if (success) {
                            Logger.log("Успешно загружен трек: $fileName")
                            musicList = maxClient.getSavedMusic()
                        }
                    } catch (e: Exception) {
                        Logger.log("Ошибка загрузки: ${e.message}")
                    }
                } catch (e: Exception) {
                    Logger.log("Сбой при загрузке: ${e.message}")
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        musicList = maxClient.getSavedMusic()
        isLoading = false
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Музыка (Избранное)") },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Логи приложения") },
                            onClick = { onShowLogs(); showMenu = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Лимит: 100 MB") },
                            onClick = { AudioCacheManager.setCacheLimit(context, 100L * 1024 * 1024); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Лимит: 500 MB") },
                            onClick = { AudioCacheManager.setCacheLimit(context, 500L * 1024 * 1024); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Лимит: 1 GB") },
                            onClick = { AudioCacheManager.setCacheLimit(context, 1024L * 1024 * 1024); showMenu = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Только кэш: " + if (AudioPlayer.isCachedOnly.value) "Вкл" else "Выкл") },
                            onClick = { AudioPlayer.isCachedOnly.value = !AudioPlayer.isCachedOnly.value; showMenu = false }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                filePickerLauncher.launch("audio/*")
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить музыку")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Music List
            Box(modifier = Modifier.weight(1f)) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (musicList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Нет загруженной музыки")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(musicList) { track ->
                            MusicItem(track = track, onClick = {
                                AudioPlayer.setPlaylist(context, maxClient, musicList, musicList.indexOf(track))
                                AudioPlayer.playCurrentTrack()
                            })
                        }
                    }
                }
            }
            
            // Player controls
            val trackName = AudioPlayer.currentTrackName.value
            if (trackName != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = trackName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(bottom = 0.dp)
                        )
                        var showEffects by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { showEffects = true }) {
                                Text(AudioPlayer.activeEffect.value.title)
                            }
                            DropdownMenu(expanded = showEffects, onDismissRequest = { showEffects = false }) {
                                AudioEffectType.values().forEach { effect ->
                                    DropdownMenuItem(
                                        text = { Text(effect.title) },
                                        onClick = { AudioPlayer.applyEffect(effect); showEffects = false }
                                    )
                                }
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
                            AudioPlayer.isShuffle.value = !AudioPlayer.isShuffle.value
                            AudioPlayer.updateService(context)
                        }) {
                            Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = if (AudioPlayer.isShuffle.value) MaterialTheme.colorScheme.primary else LocalContentColor.current)
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
            }
        }
    }
}

@Composable
fun MusicItem(track: MusicMessage, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = track.filename, style = MaterialTheme.typography.bodyLarge)
                Text(text = "ID: ${track.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Логи приложения") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(androidx.compose.material.icons.filled.ArrowBack, contentDescription = "Назад")
                }
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            items(Logger.logs) { log ->
                Text(text = log, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
'''

with open(filepath, 'w', encoding='utf-8') as f:
    f.writelines(lines[:500])
    f.write(new_code)
