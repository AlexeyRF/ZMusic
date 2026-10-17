package ru.alexeyrf.maxmusic

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun MetadataEditorDialog(
    initialTitle: String,
    initialArtist: String,
    initialCover: Bitmap?,
    initialPlaylists: Set<String>,
    allPlaylists: List<String>,
    initialStartTrim: Int = 0,
    initialEndTrim: Int = 0,
    showEncryptionOption: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (String, String, Bitmap?, Boolean, Set<String>, Int, Int) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var cover by remember { mutableStateOf(initialCover) }
    var useZ2 by remember { mutableStateOf(true) }
    var selectedPlaylists by remember { mutableStateOf(initialPlaylists) }
    var startTrimStr by remember { mutableStateOf(if (initialStartTrim > 0) initialStartTrim.toString() else "") }
    var endTrimStr by remember { mutableStateOf(if (initialEndTrim > 0) initialEndTrim.toString() else "") }

    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, it)
                ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            }
            cover = bitmap
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать метаданные") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .align(Alignment.CenterHorizontally)
                        .clickable { imagePicker.launch("image/*") }
                ) {
                    if (cover != null) {
                        Image(
                            bitmap = cover!!.asImageBitmap(),
                            contentDescription = "Cover",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = "Pick Cover",
                            modifier = Modifier.fillMaxSize(),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Исполнитель") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Обрезка трека (интро / аутро)", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startTrimStr,
                        onValueChange = { startTrimStr = it.filter { c -> c.isDigit() } },
                        label = { Text("Интро (сек)") },
                        placeholder = { Text("0") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endTrimStr,
                        onValueChange = { endTrimStr = it.filter { c -> c.isDigit() } },
                        label = { Text("Аутро (сек)") },
                        placeholder = { Text("0") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        singleLine = true
                    )
                }
                Text(
                    "Пропустить первые и последние секунды трека при воспроизведении",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (allPlaylists.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Плейлисты", style = MaterialTheme.typography.titleSmall)
                    for (pl in allPlaylists) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedPlaylists.contains(pl),
                                onCheckedChange = { checked ->
                                    val newSet = selectedPlaylists.toMutableSet()
                                    if (checked) newSet.add(pl) else newSet.remove(pl)
                                    selectedPlaylists = newSet
                                }
                            )
                            Text(pl)
                        }
                    }
                }
                
                if (showEncryptionOption && CryptoManager.isPasswordSet) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Файл будет зашифрован в формате .z2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val sSec = startTrimStr.toIntOrNull() ?: 0
                val eSec = endTrimStr.toIntOrNull() ?: 0
                onSave(title, artist, cover, useZ2, selectedPlaylists, sSec, eSec)
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
