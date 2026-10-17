package ru.alexeyrf.maxmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

object LocalMetadataManager {
    private const val PREF_NAME = "local_metadata_prefs"

    fun normalizeFileName(filename: String): String {
        return filename
            .replace('+', ' ')
            .replace("%20", " ")
            .replace(Regex("\\s+"), " ")
            .removePrefix("encrypted_")
            .removeSuffix(".zmusic")
            .removeSuffix(".z2")
            .removeSuffix(".m4a")
            .removeSuffix(".mp3")
            .removeSuffix(".ts")
            .removeSuffix(".rutube.zconfig")
            .removeSuffix(".youtube.zconfig")
            .trim()
    }

    fun saveTitle(context: Context, fileId: Long, title: String, filename: String? = null) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString("title_$fileId", title)
        if (!filename.isNullOrBlank()) {
            val fnKey = filename.lowercase().hashCode().toLong()
            val normKey = normalizeFileName(filename).lowercase().hashCode().toLong()
            editor.putString("title_$fnKey", title)
            editor.putString("title_$normKey", title)
        }
        editor.apply()
    }

    fun getTitle(context: Context, fileId: Long, filename: String? = null): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val byId = prefs.getString("title_$fileId", null)
        if (!byId.isNullOrBlank()) return byId
        if (!filename.isNullOrBlank()) {
            val fnKey = filename.lowercase().hashCode().toLong()
            val byFn = prefs.getString("title_$fnKey", null)
            if (!byFn.isNullOrBlank()) return byFn
            val normKey = normalizeFileName(filename).lowercase().hashCode().toLong()
            val byNorm = prefs.getString("title_$normKey", null)
            if (!byNorm.isNullOrBlank()) return byNorm
        }
        return null
    }

    fun saveArtist(context: Context, fileId: Long, artist: String, filename: String? = null) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString("artist_$fileId", artist)
        if (!filename.isNullOrBlank()) {
            val fnKey = filename.lowercase().hashCode().toLong()
            val normKey = normalizeFileName(filename).lowercase().hashCode().toLong()
            editor.putString("artist_$fnKey", artist)
            editor.putString("artist_$normKey", artist)
        }
        editor.apply()
    }

    fun getArtist(context: Context, fileId: Long, filename: String? = null): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val byId = prefs.getString("artist_$fileId", null)
        if (!byId.isNullOrBlank()) return byId
        if (!filename.isNullOrBlank()) {
            val fnKey = filename.lowercase().hashCode().toLong()
            val byFn = prefs.getString("artist_$fnKey", null)
            if (!byFn.isNullOrBlank()) return byFn
            val normKey = normalizeFileName(filename).lowercase().hashCode().toLong()
            val byNorm = prefs.getString("artist_$normKey", null)
            if (!byNorm.isNullOrBlank()) return byNorm
        }
        return null
    }

    fun saveCover(context: Context, fileId: Long, bitmap: Bitmap?, filename: String? = null) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val fnKey = if (!filename.isNullOrBlank()) filename.lowercase().hashCode().toLong() else null
        val normKey = if (!filename.isNullOrBlank()) normalizeFileName(filename).lowercase().hashCode().toLong() else null

        if (bitmap == null) {
            editor.remove("cover_$fileId")
            if (fnKey != null) editor.remove("cover_$fnKey")
            if (normKey != null) editor.remove("cover_$normKey")
            editor.apply()
            return
        }
        
        val coversDir = File(context.filesDir, "custom_covers")
        if (!coversDir.exists()) coversDir.mkdirs()
        
        val coverFile = File(coversDir, "cover_$fileId.png")
        FileOutputStream(coverFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        editor.putString("cover_$fileId", coverFile.absolutePath)
        if (fnKey != null) {
            editor.putString("cover_$fnKey", coverFile.absolutePath)
        }
        if (normKey != null) {
            editor.putString("cover_$normKey", coverFile.absolutePath)
        }
        editor.apply()
    }

    fun getCover(context: Context, fileId: Long, filename: String? = null): Bitmap? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var path = prefs.getString("cover_$fileId", null)
        if (path == null && !filename.isNullOrBlank()) {
            val fnKey = filename.lowercase().hashCode().toLong()
            path = prefs.getString("cover_$fnKey", null)
            if (path == null) {
                val normKey = normalizeFileName(filename).lowercase().hashCode().toLong()
                path = prefs.getString("cover_$normKey", null)
            }
        }
        if (path == null) return null
        var file = File(path)
        if (!file.exists()) {
            val fallback = File(File(context.filesDir, "custom_covers"), file.name)
            if (fallback.exists()) file = fallback else return null
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    fun saveTrim(context: Context, fileId: Long, startSec: Int, endSec: Int, filename: String? = null) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putInt("trim_start_$fileId", startSec.coerceAtLeast(0))
            .putInt("trim_end_$fileId", endSec.coerceAtLeast(0))
        if (!filename.isNullOrBlank()) {
            val fnKey = filename.lowercase().hashCode().toLong()
            val normKey = normalizeFileName(filename).lowercase().hashCode().toLong()
            editor.putInt("trim_start_$fnKey", startSec.coerceAtLeast(0))
            editor.putInt("trim_end_$fnKey", endSec.coerceAtLeast(0))
            editor.putInt("trim_start_$normKey", startSec.coerceAtLeast(0))
            editor.putInt("trim_end_$normKey", endSec.coerceAtLeast(0))
        }
        editor.apply()
    }

    fun getStartTrim(context: Context, fileId: Long, filename: String? = null): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val byId = prefs.getInt("trim_start_$fileId", -1)
        if (byId >= 0) return byId
        if (!filename.isNullOrBlank()) {
            val fnKey = filename.lowercase().hashCode().toLong()
            val byFn = prefs.getInt("trim_start_$fnKey", -1)
            if (byFn >= 0) return byFn
            val normKey = normalizeFileName(filename).lowercase().hashCode().toLong()
            val byNorm = prefs.getInt("trim_start_$normKey", -1)
            if (byNorm >= 0) return byNorm
        }
        return 0
    }

    fun getEndTrim(context: Context, fileId: Long, filename: String? = null): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val byId = prefs.getInt("trim_end_$fileId", -1)
        if (byId >= 0) return byId
        if (!filename.isNullOrBlank()) {
            val fnKey = filename.lowercase().hashCode().toLong()
            val byFn = prefs.getInt("trim_end_$fnKey", -1)
            if (byFn >= 0) return byFn
            val normKey = normalizeFileName(filename).lowercase().hashCode().toLong()
            val byNorm = prefs.getInt("trim_end_$normKey", -1)
            if (byNorm >= 0) return byNorm
        }
        return 0
    }
    
    fun savePlaylists(context: Context, fileId: Long, playlists: Set<String>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("playlists_$fileId", playlists).apply()
    }

    fun getPlaylists(context: Context, fileId: Long): Set<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet("playlists_$fileId", emptySet()) ?: emptySet()
    }

    fun getAllPlaylists(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("all_playlists", emptySet()) ?: emptySet()
        val orderString = prefs.getString("all_playlists_order", "") ?: ""
        val order = orderString.split(",").filter { it.isNotBlank() }
        val orderedList = order.filter { set.contains(it) }.toMutableList()
        val missing = set.filter { !orderedList.contains(it) }
        orderedList.addAll(missing)
        return orderedList
    }

    fun addGlobalPlaylist(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet("all_playlists", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(name)
        val orderString = prefs.getString("all_playlists_order", "") ?: ""
        val order = orderString.split(",").filter { it.isNotBlank() }.toMutableList()
        if (!order.contains(name)) order.add(name)
        prefs.edit()
            .putStringSet("all_playlists", current)
            .putString("all_playlists_order", order.joinToString(","))
            .apply()
    }
    
    fun deleteGlobalPlaylist(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getString("default_playlist", null) == name) {
            prefs.edit().remove("default_playlist").apply()
        }
        val current = prefs.getStringSet("all_playlists", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(name)
        val orderString = prefs.getString("all_playlists_order", "") ?: ""
        val order = orderString.split(",").filter { it.isNotBlank() }.toMutableList()
        order.remove(name)
        prefs.edit()
            .putStringSet("all_playlists", current)
            .putString("all_playlists_order", order.joinToString(","))
            .apply()
    }

    fun moveGlobalPlaylist(context: Context, name: String, direction: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val list = getAllPlaylists(context).toMutableList()
        val index = list.indexOf(name)
        if (index == -1) return
        val newIndex = index + direction
        if (newIndex in 0 until list.size) {
            list.removeAt(index)
            list.add(newIndex, name)
            prefs.edit().putString("all_playlists_order", list.joinToString(",")).apply()
        }
    }
    
    fun savePlaylistsOrder(context: Context, order: List<String>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("all_playlists_order", order.joinToString(",")).apply()
    }
    
    fun getDefaultPlaylist(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString("default_playlist", null)
    }

    fun setDefaultPlaylist(context: Context, name: String?) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("default_playlist", name).apply()
    }
    
    fun saveCustomEffect(context: Context, name: String, levels: List<Short>, speed: Float? = null, reverb: Int? = null) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("effect_$name", levels.joinToString(",")).apply()
        if (speed != null) prefs.edit().putFloat("effect_speed_$name", speed).apply() else prefs.edit().remove("effect_speed_$name").apply()
        if (reverb != null) prefs.edit().putInt("effect_reverb_$name", reverb).apply() else prefs.edit().remove("effect_reverb_$name").apply()
        
        val all = prefs.getStringSet("all_custom_effects", emptySet())?.toMutableSet() ?: mutableSetOf()
        all.add(name)
        prefs.edit().putStringSet("all_custom_effects", all).apply()
    }

    fun getCustomEffectSpeed(context: Context, name: String): Float? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains("effect_speed_$name")) prefs.getFloat("effect_speed_$name", 1f) else null
    }

    fun getCustomEffectReverb(context: Context, name: String): Int? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains("effect_reverb_$name")) prefs.getInt("effect_reverb_$name", 0) else null
    }

    fun deleteCustomEffect(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("effect_$name")
             .remove("effect_speed_$name")
             .remove("effect_reverb_$name")
             .apply()
        val all = prefs.getStringSet("all_custom_effects", emptySet())?.toMutableSet() ?: mutableSetOf()
        all.remove(name)
        prefs.edit().putStringSet("all_custom_effects", all).apply()
    }
    
    fun getCustomEffects(context: Context): Map<String, List<Short>> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val all = prefs.getStringSet("all_custom_effects", emptySet()) ?: emptySet()
        val result = mutableMapOf<String, List<Short>>()
        all.forEach { name ->
            val str = prefs.getString("effect_$name", "") ?: ""
            if (str.isNotBlank()) {
                result[name] = str.split(",").map { it.toShort() }
            }
        }
        return result
    }
}
