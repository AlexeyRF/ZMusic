package ru.alexeyrf.maxmusic

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val PREF_NAME = "crypto_prefs"
    private const val KEY_PASSWORD_HASH = "password_hash"
    var isPasswordSet = false
    private var secretKey: SecretKeySpec? = null
    
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedHash = prefs.getString(KEY_PASSWORD_HASH, null)
        isPasswordSet = savedHash != null
    }

    fun setPassword(context: Context, password: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        secretKey = SecretKeySpec(keyBytes, "AES")
        
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PASSWORD_HASH, Base64.encodeToString(keyBytes, Base64.DEFAULT)).apply()
        isPasswordSet = true
    }

    fun getSecretKey(context: Context): SecretKeySpec? {
        if (secretKey != null) return secretKey
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedHash = prefs.getString(KEY_PASSWORD_HASH, null) ?: return null
        val keyBytes = Base64.decode(savedHash, Base64.DEFAULT)
        secretKey = SecretKeySpec(keyBytes, "AES")
        return secretKey
    }

    fun encryptStream(context: Context, input: InputStream, output: OutputStream) {
        val key = getSecretKey(context) ?: throw IllegalStateException("Password not set")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16) // use zero IV for simplicity or random and prepend it
        // using static IV for simplicity, but random is better.
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val cos = CipherOutputStream(output, cipher)
        input.copyTo(cos)
        cos.close()
    }

    fun decryptStream(context: Context, input: InputStream, output: OutputStream) {
        val key = getSecretKey(context) ?: throw IllegalStateException("Password not set")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        val cis = CipherInputStream(input, cipher)
        cis.copyTo(output)
        cis.close()
    }
    
    fun encryptBytes(context: Context, bytes: ByteArray): ByteArray {
        val key = getSecretKey(context) ?: throw IllegalStateException("Password not set")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }

    fun decryptBytes(context: Context, bytes: ByteArray): ByteArray {
        val key = getSecretKey(context) ?: throw IllegalStateException("Password not set")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }
}
