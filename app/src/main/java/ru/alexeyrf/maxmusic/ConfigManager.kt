package ru.alexeyrf.maxmusic

import android.content.Context
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import ru.alexeyrf.maxmusic.client.MaxClient

object ConfigManager {
    const val MAX_PART_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB
    
    // Exports all settings to a split set of .zconfig files
    suspend fun exportConfig(context: Context): List<File> = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val tempZip = File(context.cacheDir, "temp_config_$timestamp.zip")
        val encryptedZconfig = File(context.cacheDir, "config_$timestamp.zconfig")
        
        try {
            // 1. Zip the settings
            ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                // Add shared_prefs
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                if (prefsDir.exists()) {
                    prefsDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name != "crypto_prefs.xml" && file.name != "session_prefs.xml") { // Skip crypto and session prefs
                            zos.putNextEntry(ZipEntry("shared_prefs/${file.name}"))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                
                // Add custom_covers
                val coversDir = File(context.filesDir, "custom_covers")
                if (coversDir.exists()) {
                    coversDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            zos.putNextEntry(ZipEntry("custom_covers/${file.name}"))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                // Add stream_links
                val streamLinksDir = File(context.filesDir, "stream_links")
                if (streamLinksDir.exists()) {
                    streamLinksDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            zos.putNextEntry(ZipEntry("stream_links/${file.name}"))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                // Add saved_music_cache.json
                val savedCache = File(context.filesDir, "saved_music_cache.json")
                if (savedCache.exists()) {
                    zos.putNextEntry(ZipEntry("files/saved_music_cache.json"))
                    savedCache.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            
            // 2. Encrypt the ZIP
            tempZip.inputStream().use { fis ->
                encryptedZconfig.outputStream().use { fos ->
                    CryptoManager.encryptStream(context, fis, fos)
                }
            }
            
            // 3. Split if > 2GB
            val parts = mutableListOf<File>()
            if (encryptedZconfig.length() > MAX_PART_SIZE) {
                var partNum = 0
                encryptedZconfig.inputStream().use { fis ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var currentPartSize = 0L
                    var currentOut: OutputStream? = null
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        if (currentOut == null) {
                            val partFile = File(context.cacheDir, "config_${timestamp}.zconfig.part$partNum")
                            parts.add(partFile)
                            currentOut = partFile.outputStream()
                        }
                        
                        currentOut.write(buffer, 0, bytesRead)
                        currentPartSize += bytesRead
                        
                        if (currentPartSize >= MAX_PART_SIZE) {
                            currentOut.close()
                            currentOut = null
                            currentPartSize = 0L
                            partNum++
                        }
                    }
                    currentOut?.close()
                }
                encryptedZconfig.delete() // delete the combined one
            } else {
                parts.add(encryptedZconfig)
            }
            
            return@withContext parts
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }

    fun getLocalConfigFile(context: Context): File {
        return File(context.filesDir, "current_config.zconfig")
    }

    // Saves current settings to a local encrypted zconfig file
    suspend fun saveLocalConfig(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val parts = exportConfig(context)
            if (parts.isNotEmpty()) {
                val localTarget = getLocalConfigFile(context)
                parts[0].copyTo(localTarget, overwrite = true)
                return@withContext localTarget
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    // Loads settings from the local encrypted zconfig file if it exists
    suspend fun importLocalConfig(context: Context): Boolean = withContext(Dispatchers.IO) {
        val localTarget = getLocalConfigFile(context)
        if (!localTarget.exists()) return@withContext false
        try {
            return@withContext importConfig(context, listOf { localTarget.inputStream() })
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
    
    // Uploads a config part to the server
    suspend fun uploadConfig(context: Context, maxClient: MaxClient, file: File): Boolean {
        return try {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            maxClient.sendMusic(file.name, bytes)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // Import config from a list of parts
    suspend fun importConfig(context: Context, inputStreamProviders: List<() -> InputStream?>) = withContext(Dispatchers.IO) {
        val encryptedZconfig = File(context.cacheDir, "import_config.zconfig")
        val decryptedZip = File(context.cacheDir, "import_temp.zip")
        
        try {
            // 1. Copy to temp
            encryptedZconfig.outputStream().use { output ->
                for (provider in inputStreamProviders) {
                    val ins = provider() ?: throw Exception("Cannot read config file part")
                    ins.use { input ->
                        input.copyTo(output)
                    }
                }
            }
            
            // 2. Decrypt
            encryptedZconfig.inputStream().use { fis ->
                decryptedZip.outputStream().use { fos ->
                    CryptoManager.decryptStream(context, fis, fos)
                }
            }
            
            // 3. Extract to data directory
            ZipInputStream(decryptedZip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val targetFile = when {
                        entry.name.startsWith("custom_covers/") -> File(context.filesDir, entry.name)
                        entry.name.startsWith("stream_links/") -> File(context.filesDir, entry.name)
                        entry.name.startsWith("files/") -> File(context.filesDir, entry.name.removePrefix("files/"))
                        entry.name.startsWith("shared_prefs/") -> File(context.applicationInfo.dataDir, entry.name)
                        else -> File(context.applicationInfo.dataDir, entry.name)
                    }
                    // Ensure parent exists
                    targetFile.parentFile?.mkdirs()
                    
                    targetFile.outputStream().use { fos ->
                        zis.copyTo(fos)
                    }
                    // Also duplicate custom_covers to dataDir/custom_covers if needed for compatibility
                    if (entry.name.startsWith("custom_covers/")) {
                        try {
                            val compatFile = File(context.applicationInfo.dataDir, entry.name)
                            compatFile.parentFile?.mkdirs()
                            targetFile.copyTo(compatFile, overwrite = true)
                        } catch (e: Exception) {}
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            
            // Return true indicating success, app needs to be restarted
            return@withContext true
            
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        } finally {
            if (encryptedZconfig.exists()) encryptedZconfig.delete()
            if (decryptedZip.exists()) decryptedZip.delete()
        }
    }
}
