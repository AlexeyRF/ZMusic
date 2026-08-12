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
    
    fun saveCustomEffect(context: Context, name: String, levels: List<Short>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("effect_$name", levels.joinToString(",")).apply()
        
        val all = prefs.getStringSet("all_custom_effects", emptySet())?.toMutableSet() ?: mutableSetOf()
        all.add(name)
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
