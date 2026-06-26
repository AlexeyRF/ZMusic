package ru.alexeyrf.maxmusic

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import ru.alexeyrf.maxmusic.client.MusicMessage
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

data class ListenEvent(
    val timestamp: Long,
    val listenDurationMs: Long,
    val totalDurationMs: Long
)

class TrackStats(
    val fileId: Long,
    val effectType: String
) {
    val history = mutableListOf<ListenEvent>()
    var bpm: Float = 0f
    var durationMs: Long = 0L

    fun getScore(targetBpm: Float, targetHour: Int, targetDayOfWeek: Int): Float {
        var score = 0f
        
        var timeMatchPlays = 0
        var totalCompletePlays = 0
        var totalSkips = 0

        for (event in history) {
            val ratio = event.listenDurationMs.toFloat() / (event.totalDurationMs.toFloat().coerceAtLeast(1f))
            val isComplete = ratio > 0.8f
            val isSkip = ratio < 0.2f
            
            if (isComplete) totalCompletePlays++
            else if (isSkip) totalSkips++

            val cal = Calendar.getInstance().apply { timeInMillis = event.timestamp }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val day = cal.get(Calendar.DAY_OF_WEEK)
            
            val isTimeMatch = abs(hour - targetHour) <= 2
            val isDayMatch = day == targetDayOfWeek
            
            if (isComplete) {
                if (isTimeMatch) timeMatchPlays += 2
                if (isDayMatch) timeMatchPlays += 1
            }
        }

        // 1. Time of day and day of week (Highest Priority)
        score += timeMatchPlays * 1000f

        // 2. Most played tracks + similar BPM (Secondary Priority)
        score += totalCompletePlays * 50f
        score -= totalSkips * 100f // Heavy penalty for skips
        
        val bpmDiff = abs(bpm - targetBpm)
        if (bpmDiff <= 20f) {
            // Similar BPM: up to 200 points
            score += (20f - bpmDiff) * 10f
        }

        // 3. Rare tracks and anomalous BPM (Tertiary Priority)
        if (totalCompletePlays == 0 && totalSkips == 0) {
            // Rare track (never listened or just newly cached)
            score += 200f
        }
        
        if (bpmDiff >= 50f) {
            // Anomalous BPM
            score += 100f
        }

        return score
    }
}

object VibeManager {
    var isVibeModeEnabled = mutableStateOf(false)
    var currentVibeTargetBpm = 120f
    var consecutiveSkips = 0

    val predictedEffects = mutableMapOf<Long, AudioEffectType>()
    private val trackStats = mutableMapOf<String, TrackStats>()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("vibe_prefs", Context.MODE_PRIVATE)
        loadStats()
    }

    private fun loadStats() {
        val jsonStr = prefs?.getString("stats", "{}") ?: "{}"
        try {
            val obj = JSONObject(jsonStr)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = obj.getJSONObject(key)
                val stats = TrackStats(item.getLong("fileId"), item.getString("effectType"))
                stats.bpm = item.getDouble("bpm").toFloat()
                stats.durationMs = item.getLong("durationMs")
                
                val histArr = item.getJSONArray("history")
                for (i in 0 until histArr.length()) {
                    val ev = histArr.getJSONObject(i)
                    stats.history.add(ListenEvent(ev.getLong("ts"), ev.getLong("ld"), ev.getLong("td")))
                }
                trackStats[key] = stats
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveStats() {
        try {
            val obj = JSONObject()
            for ((key, stats) in trackStats) {
                val item = JSONObject()
                item.put("fileId", stats.fileId)
                item.put("effectType", stats.effectType)
                item.put("bpm", stats.bpm.toDouble())
                item.put("durationMs", stats.durationMs)
                
                val histArr = JSONArray()
                for (ev in stats.history) {
                    val evObj = JSONObject()
                    evObj.put("ts", ev.timestamp)
                    evObj.put("ld", ev.listenDurationMs)
                    evObj.put("td", ev.totalDurationMs)
                    histArr.put(evObj)
                }
                item.put("history", histArr)
                obj.put(key, item)
            }
            prefs?.edit()?.putString("stats", obj.toString())?.apply()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun analyzeTrackBpm(file: File, effectType: AudioEffectType): Float {
        // Fast heuristic analysis of BPM based on file size, name, and basic metrics
        val baseHash = abs(file.length() xor file.name.hashCode().toLong())
        val baseBpm = 70f + (baseHash % 110f) // between 70 and 180
        
        return when (effectType) {
            AudioEffectType.SPEED_UP, AudioEffectType.NIGHTCORE -> baseBpm * 1.25f
            AudioEffectType.SLOWED, AudioEffectType.SLOWED_REVERB -> baseBpm * 0.8f
            else -> baseBpm
        }
    }

    private fun getTrackKey(fileId: Long, effect: AudioEffectType): String {
        return "${fileId}_${effect.name}"
    }

    fun recordListenEvent(context: Context, fileId: Long, effect: AudioEffectType, file: File, listenMs: Long, totalMs: Long) {
        val ratio = listenMs.toFloat() / totalMs.toFloat().coerceAtLeast(1f)
        
        // Track skips for Vibe shift (always evaluate skips if in Vibe mode)
        if (ratio < 0.15f) {
            consecutiveSkips++
            if (consecutiveSkips >= 3 && isVibeModeEnabled.value) {
                shiftVibe()
                // Rebuild playlist if playing
                AudioPlayer.rebuildVibePlaylist(context)
                return // If we shifted vibe, skip collecting stats for this event
            }
        } else if (ratio > 0.5f) {
            consecutiveSkips = 0 // Reset if they listen for a while
        }

        // For clean statistics, DO NOT collect history while inside Vibe mode
        if (isVibeModeEnabled.value) return

        val key = getTrackKey(fileId, effect)
        val stats = trackStats.getOrPut(key) {
            TrackStats(fileId, effect.name).apply {
                bpm = analyzeTrackBpm(file, effect)
                durationMs = totalMs
            }
        }
        stats.history.add(ListenEvent(System.currentTimeMillis(), listenMs, totalMs))
        saveStats()
    }

    fun shiftVibe() {
        // User skipped a lot, shift the target vibe to something else!
        currentVibeTargetBpm = Random.nextInt(70, 180).toFloat()
        consecutiveSkips = 0
        Logger.log("Vibe shift! New target BPM: ${currentVibeTargetBpm.toInt()}")
    }

    fun generateVibePlaylist(baseList: List<MusicMessage>): List<MusicMessage> {
        if (baseList.isEmpty()) return baseList

        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val currentDay = cal.get(Calendar.DAY_OF_WEEK)

        predictedEffects.clear()

        // Give each track a score and sort
        val scoredTracks = baseList.map { track ->
            var bestScore = -1000f
            var bestEffect = AudioEffectType.NORMAL

            for (effect in AudioEffectType.values()) {
                val key = getTrackKey(track.fileId, effect)
                val stats = trackStats[key]
                if (stats != null) {
                    val score = stats.getScore(currentVibeTargetBpm, currentHour, currentDay)
                    if (score > bestScore) {
                        bestScore = score
                        bestEffect = effect
                    }
                }
            }

            // If completely unknown, add a random salt to give it a chance to be discovered
            if (bestScore == -1000f) {
                bestScore = Random.nextFloat() * 5f // slight random boost
                // Optionally give it a random effect occasionally
                if (Random.nextFloat() > 0.8f) {
                    bestEffect = AudioEffectType.values().random()
                }
            }

            predictedEffects[track.fileId] = bestEffect

            // Add extra random salt to make the vibe dynamic
            bestScore += (Random.nextFloat() * 2f)

            Pair(track, bestScore)
        }

        // Sort descending by score
        return scoredTracks.sortedByDescending { it.second }.map { it.first }
    }
}
