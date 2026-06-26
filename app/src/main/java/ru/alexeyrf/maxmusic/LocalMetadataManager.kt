package ru.alexeyrf.maxmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

object LocalMetadataManager {
    private const val PREF_NAME = "local_metadata_prefs"

    fun saveTitle(context: Context, fileId: Long, title: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("title_$fileId", title).apply()
    }

    fun getTitle(context: Context, fileId: Long): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString("title_$fileId", null)
    }

    fun saveArtist(context: Context, fileId: Long, artist: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("artist_$fileId", artist).apply()
    }

    fun getArtist(context: Context, fileId: Long): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString("artist_$fileId", null)
    }

    fun saveCover(context: Context, fileId: Long, bitmap: Bitmap?) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (bitmap == null) {
            prefs.edit().remove("cover_$fileId").apply()
            return
        }
        
        val coversDir = File(context.filesDir, "custom_covers")
        if (!coversDir.exists()) coversDir.mkdirs()
        
        val coverFile = File(coversDir, "cover_$fileId.png")
        FileOutputStream(coverFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        prefs.edit().putString("cover_$fileId", coverFile.absolutePath).apply()
    }

    fun getCover(context: Context, fileId: Long): Bitmap? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString("cover_$fileId", null) ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }
}
