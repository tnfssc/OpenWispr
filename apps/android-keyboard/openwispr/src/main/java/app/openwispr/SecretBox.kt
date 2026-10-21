package app.openwispr

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (provider API keys) with an AES key that never leaves Android Keystore.
 * Replaces androidx.security EncryptedSharedPreferences, which is deprecated in favour of using
 * Keystore directly.
 */
class SecretBox(private val alias: String) {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KeystoreProvider).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreProvider).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    /** IV and ciphertext, Base64-encoded. */
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(Transformation).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IvSize))
        }
        return String(cipher.doFinal(bytes, IvSize, bytes.size - IvSize))
    }

    private companion object {
        const val KeystoreProvider = "AndroidKeyStore"
        const val Transformation = "AES/GCM/NoPadding"
        const val IvSize = 12
    }
}
