package de.axelcypher.asmr.data.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE zwischen App und Server für den SSO-Login (RFC 7636, Methode S256). */
object Pkce {
    private val random = SecureRandom()

    fun newVerifier(): String {
        val bytes = ByteArray(48).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
}
