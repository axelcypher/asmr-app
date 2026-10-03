package de.axelcypher.asmr.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val random = SecureRandom()

/** Zufälliger, URL-sicherer Token mit [bytes] Bytes Entropie. */
fun randomToken(bytes: Int = 32): String {
    val buffer = ByteArray(bytes).also(random::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
}

fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** PKCE S256: BASE64URL(SHA256(verifier)). */
fun pkceChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

/** PBKDF2-HMAC-SHA256, Format `pbkdf2$<iterationen>$<salt>$<hash>`. */
object Passwords {
    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2\$$ITERATIONS\$${b64(salt)}\$${b64(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = Base64.getDecoder().decode(parts[2])
        val expected = Base64.getDecoder().decode(parts[3])
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS))
            .encoded

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
