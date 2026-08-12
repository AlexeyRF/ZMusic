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
                        if (file.isFile && file.name != "crypto_prefs.xml") { // Skip crypto prefs to avoid leaking encryption key
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
                    val targetFile = File(context.applicationInfo.dataDir, entry.name)
                    // Ensure parent exists
                    targetFile.parentFile?.mkdirs()
                    
                    targetFile.outputStream().use { fos ->
                        zis.copyTo(fos)
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
