package com.meshcentral.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Loads or lazily creates the agent's long-lived signing identity.
 *
 * The identity lives in the AndroidKeyStore under the same alias the first
 * setup used, so any host — including the foreground service started by the
 * system with no activity in the process — signs the handshake with the same
 * certificate the MeshCentral server already trusts.
 */
object AgentIdentity {

    private const val TAG = "AgentIdentity"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "meshcentral-agent-identity"
    private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
    private const val CERTIFICATE_LIFETIME_MILLIS = 20L * 365L * ONE_DAY_MILLIS

    fun ensure(context: Context): Boolean {
        if (agentCertificate != null && agentCertificateKey != null) return true

        val prefs = context.getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val certB64 = prefs.getString("agentCert", null)
                val keyB64 = prefs.getString("agentKey", null)
                if (certB64 != null && keyB64 != null) {
                    val certificate = CertificateFactory.getInstance("X509").generateCertificate(
                        ByteArrayInputStream(Base64.decode(certB64, Base64.DEFAULT))
                    ) as X509Certificate
                    val keySpec = PKCS8EncodedKeySpec(Base64.decode(keyB64, Base64.DEFAULT))
                    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                    val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .build()
                    keyStore.setEntry(
                        KEY_ALIAS,
                        KeyStore.PrivateKeyEntry(privateKey, arrayOf(certificate)),
                        protection
                    )
                } else {
                    generateIdentity()
                }
            }

            agentCertificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
            agentCertificateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
            prefs.edit().remove("agentCert").remove("agentKey").apply()
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to load or create agent identity", error)
            agentCertificate = null
            agentCertificateKey = null
            false
        }
    }

    private fun generateIdentity() {
        val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val now = System.currentTimeMillis()
        keyGen.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=android.agent.meshcentral.com"))
                .setCertificateSerialNumber(BigInteger(63, SecureRandom()).max(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - ONE_DAY_MILLIS))
                .setCertificateNotAfter(Date(now + CERTIFICATE_LIFETIME_MILLIS))
                .build()
        )
        keyGen.generateKeyPair()
    }
}