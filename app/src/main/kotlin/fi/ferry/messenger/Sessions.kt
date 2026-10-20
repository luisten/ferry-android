package fi.ferry.messenger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Instagram sessions (what IGSession returns, by account ID) and nothing else: each a file in app storage that
 * backups skip, encrypted with an Android Keystore key that can't leave this phone (iOS keeps them in the Keychain).
 * Never logged.
 */
class Sessions(context: Context) {
    private val dir = File(context.noBackupFilesDir, "sessions").apply { mkdirs() }

    fun accounts(): List<String> = dir.list { _, name -> name.all(Char::isDigit) }.orEmpty().toList()

    /** Null when there's none, or it can't be read any more (the key gone): the user logs in again. */
    fun read(account: String): ByteArray? = runCatching {
        val file = AtomicFile(File(dir, account)).readFully()
        cipher(Cipher.DECRYPT_MODE, GCMParameterSpec(128, file, 0, IV)).doFinal(file, IV, file.size - IV)
    }.getOrNull()

    fun write(account: String, session: ByteArray) {
        val cipher = cipher(Cipher.ENCRYPT_MODE, null)
        val file = AtomicFile(File(dir, account))
        val out = file.startWrite()
        try {
            out.write(cipher.iv)
            out.write(cipher.doFinal(session))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun cipher(mode: Int, iv: GCMParameterSpec?) =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key(), iv) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEY = "sessions"
        const val IV = 12 // GCM's, which the Keystore picks for each write
    }
}
