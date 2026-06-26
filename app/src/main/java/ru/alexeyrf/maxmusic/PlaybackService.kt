package ru.alexeyrf.maxmusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.core.app.NotificationCompat

class PlaybackService : Service() {
    private lateinit var mediaSession: MediaSessionCompat

    companion object {
        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_STOP = "action_stop"
        const val ACTION_NEXT = "action_next"
        const val ACTION_PREV = "action_prev"
        const val ACTION_SHUFFLE = "action_shuffle"
        const val ACTION_REPEAT = "action_repeat"
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "PlaybackService")
        mediaSession.isActive = true
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onSeekTo(pos: Long) {
                AudioPlayer.seekTo(pos.toInt())
            }
            override fun onPlay() {
                AudioPlayer.resume(this@PlaybackService)
            }
            override fun onPause() {
                AudioPlayer.pause(this@PlaybackService)
            }
            override fun onSkipToNext() {
                AudioPlayer.next()
            }
            override fun onSkipToPrevious() {
                AudioPlayer.prev()
            }
            override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                when (action) {
                    ACTION_SHUFFLE -> {
                        AudioPlayer.playMode.value = when (AudioPlayer.playMode.value) {
                            AudioPlayer.PlayMode.SEQUENTIAL -> AudioPlayer.PlayMode.SHUFFLE
                            AudioPlayer.PlayMode.SHUFFLE -> AudioPlayer.PlayMode.SIMILAR
                            AudioPlayer.PlayMode.SIMILAR -> AudioPlayer.PlayMode.SEQUENTIAL
                        }
                        AudioPlayer.updateService(this@PlaybackService)
                    }
                    ACTION_REPEAT -> {
                        AudioPlayer.isRepeat.value = !AudioPlayer.isRepeat.value
                        AudioPlayer.updateService(this@PlaybackService)
                    }
                }
            }
            override fun onSetShuffleMode(shuffleMode: Int) {
                AudioPlayer.playMode.value = when (AudioPlayer.playMode.value) {
                    AudioPlayer.PlayMode.SEQUENTIAL -> AudioPlayer.PlayMode.SHUFFLE
                    AudioPlayer.PlayMode.SHUFFLE -> AudioPlayer.PlayMode.SIMILAR
                    AudioPlayer.PlayMode.SIMILAR -> AudioPlayer.PlayMode.SEQUENTIAL
                }
                AudioPlayer.updateService(this@PlaybackService)
            }
            override fun onSetRepeatMode(repeatMode: Int) {
                AudioPlayer.isRepeat.value = !AudioPlayer.isRepeat.value
                AudioPlayer.updateService(this@PlaybackService)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trackName = AudioPlayer.currentTrackName.value ?: "Unknown Track"

        when (intent?.action) {
            ACTION_PLAY -> AudioPlayer.resume(this)
            ACTION_PAUSE -> AudioPlayer.pause(this)
            ACTION_NEXT -> AudioPlayer.next()
            ACTION_PREV -> AudioPlayer.prev()
            ACTION_SHUFFLE -> {
                AudioPlayer.playMode.value = when (AudioPlayer.playMode.value) {
                    AudioPlayer.PlayMode.SEQUENTIAL -> AudioPlayer.PlayMode.SHUFFLE
                    AudioPlayer.PlayMode.SHUFFLE -> AudioPlayer.PlayMode.SIMILAR
                    AudioPlayer.PlayMode.SIMILAR -> AudioPlayer.PlayMode.SEQUENTIAL
                }
            }
            ACTION_REPEAT -> {
                AudioPlayer.isRepeat.value = !AudioPlayer.isRepeat.value
            }
            ACTION_STOP -> {
                AudioPlayer.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = createNotification(trackName, AudioPlayer.isPlayingState.value)
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun createNotification(trackName: String, isPlaying: Boolean): android.app.Notification {
        mediaSession.setShuffleMode(if (AudioPlayer.playMode.value != AudioPlayer.PlayMode.SEQUENTIAL) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        mediaSession.setRepeatMode(if (AudioPlayer.isRepeat.value) PlaybackStateCompat.REPEAT_MODE_ALL else PlaybackStateCompat.REPEAT_MODE_NONE)
        
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or PlaybackStateCompat.ACTION_SET_REPEAT_MODE)
            .addCustomAction(ACTION_SHUFFLE, "Shuffle", if (AudioPlayer.playMode.value != AudioPlayer.PlayMode.SEQUENTIAL) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off)
            .addCustomAction(ACTION_REPEAT, "Repeat", if (AudioPlayer.isRepeat.value) R.drawable.ic_repeat_on else R.drawable.ic_repeat_off)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                AudioPlayer.currentPosition.value.toLong(),
                if (isPlaying) 1f else 0f
            )
        mediaSession.setPlaybackState(stateBuilder.build())

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, AudioPlayer.duration.value.toLong())
        
        val albumArt = AudioPlayer.currentAlbumArt.value
        if (albumArt != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        val shuffleIcon = if (AudioPlayer.playMode.value != AudioPlayer.PlayMode.SEQUENTIAL) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off
        val shuffleAction = NotificationCompat.Action(
            shuffleIcon, "Перемешать",
            PendingIntent.getService(this, 3, Intent(this, PlaybackService::class.java).setAction(ACTION_SHUFFLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Предыдущий",
            PendingIntent.getService(this, 4, Intent(this, PlaybackService::class.java).setAction(ACTION_PREV), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Пауза",
                PendingIntent.getService(this, 0, Intent(this, PlaybackService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Играть",
                PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        }

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next, "Следующий",
            PendingIntent.getService(this, 5, Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        val repeatIcon = if (AudioPlayer.isRepeat.value) R.drawable.ic_repeat_on else R.drawable.ic_repeat_off
        val repeatAction = NotificationCompat.Action(
            repeatIcon, "Повтор",
            PendingIntent.getService(this, 6, Intent(this, PlaybackService::class.java).setAction(ACTION_REPEAT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(trackName)
            .setContentText("ZMusic")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(shuffleAction)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(repeatAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3) // Prev, Play/Pause, Next
                .setMediaSession(mediaSession.sessionToken))

        if (albumArt != null) {
            builder.setLargeIcon(albumArt)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
    }
}
