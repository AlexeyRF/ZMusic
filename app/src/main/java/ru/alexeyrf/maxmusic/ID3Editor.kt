package ru.alexeyrf.maxmusic

import android.content.Context
import android.graphics.Bitmap
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.ByteArrayOutputStream
import java.io.File

object ID3Editor {
    fun embedMetadata(context: Context, audioBytes: ByteArray, title: String?, artist: String?, cover: Bitmap?, formatExt: String): ByteArray {
        val tempFile = File(context.cacheDir, "temp_id3_edit.$formatExt")
        try {
            tempFile.writeBytes(audioBytes)
            val audioFile = AudioFileIO.read(tempFile)
            var tag = audioFile.tagOrCreateAndSetDefault
            
            if (!title.isNullOrBlank()) {
                tag.setField(FieldKey.TITLE, title)
            }
            if (!artist.isNullOrBlank()) {
                tag.setField(FieldKey.ARTIST, artist)
            }
            if (cover != null) {
                val coverFile = File(context.cacheDir, "temp_cover.png")
                val outStream = java.io.FileOutputStream(coverFile)
                cover.compress(Bitmap.CompressFormat.PNG, 100, outStream)
                outStream.flush()
                outStream.close()
                
                val artwork = org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(coverFile)
                tag.deleteArtworkField()
                tag.setField(artwork)
                coverFile.delete()
            }
            
            audioFile.commit()
            return tempFile.readBytes()
        } catch (e: Exception) {
            e.printStackTrace()
            return audioBytes // Return unmodified if it fails
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
