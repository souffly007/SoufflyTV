package fr.bonobo.soufflytv.platform

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Chiffrement des sauvegardes de comptes (même format sur Android et sur PC)

actual fun passwordEncrypt(data: ByteArray, password: String, salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray =
    backupCipher(Cipher.ENCRYPT_MODE, password, salt, iv, iterations).doFinal(data)

actual fun passwordDecrypt(data: ByteArray, password: String, salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray =
    backupCipher(Cipher.DECRYPT_MODE, password, salt, iv, iterations).doFinal(data)

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

private fun backupCipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray, iterations: Int): Cipher {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    val key = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
    return Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    }
}
