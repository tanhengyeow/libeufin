/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.sandbox

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.lang.Exception
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import java.security.MessageDigest
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec







/**
 * RSA key pair.
 */
data class RsaCrtKeyPair(val private: RSAPrivateCrtKey, val public: RSAPublicKey)

/**
 * Helpers for dealing with crypographic operations in EBICS / LibEuFin.
 */
class CryptoUtil {

    class EncryptionResult(
        val encryptedTransactionKey: ByteArray,
        val pubKeyDigest: ByteArray,
        val encryptedData: ByteArray
    )

    companion object {
        private val bouncyCastleProvider = BouncyCastleProvider()

        /**
         * Load an RSA private key from its binary PKCS#8 encoding.
         */
        fun loadRsaPrivateKey(encodedPrivateKey: ByteArray): RSAPrivateCrtKey {
            val spec = PKCS8EncodedKeySpec(encodedPrivateKey)
            val priv = KeyFactory.getInstance("RSA").generatePrivate(spec)
            if (priv !is RSAPrivateCrtKey)
                throw Exception("wrong encoding")
            return priv
        }

        /**
         * Load an RSA public key from its binary X509 encoding.
         */
        fun loadRsaPublicKey(encodedPublicKey: ByteArray): RSAPublicKey {
            val spec = X509EncodedKeySpec(encodedPublicKey)
            val pub = KeyFactory.getInstance("RSA").generatePublic(spec)
            if (pub !is RSAPublicKey)
                throw Exception("wrong encoding")
            return pub
        }

        /**
         * Load an RSA public key from its binary X509 encoding.
         */
        fun getRsaPublicFromPrivate(rsaPrivateCrtKey: RSAPrivateCrtKey): RSAPublicKey {
            val spec = RSAPublicKeySpec(rsaPrivateCrtKey.modulus, rsaPrivateCrtKey.publicExponent)
            val pub = KeyFactory.getInstance("RSA").generatePublic(spec)
            if (pub !is RSAPublicKey)
                throw Exception("wrong encoding")
            return pub
        }

        /**
         * Generate a fresh RSA key pair.
         *
         * @param nbits size of the modulus in bits
         */
        fun generateRsaKeyPair(nbits: Int): RsaCrtKeyPair {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(nbits)
            val pair = gen.genKeyPair()
            val priv = pair.private
            val pub = pair.public
            if (priv !is RSAPrivateCrtKey)
                throw Exception("key generation failed")
            if (pub !is RSAPublicKey)
                throw Exception("key generation failed")
            return RsaCrtKeyPair(priv, pub)
        }

        /**
         * Load an RSA public key from its components.
         *
         * @param exponent
         * @param modulus
         * @return key
         */
        fun loadRsaPublicKeyFromComponents(modulus: ByteArray, exponent: ByteArray): RSAPublicKey {
            val modulusBigInt = BigInteger(1, modulus)
            val exponentBigInt = BigInteger(1, exponent)

            val keyFactory = KeyFactory.getInstance("RSA")
            val tmp = RSAPublicKeySpec(modulusBigInt, exponentBigInt)
            return keyFactory.generatePublic(tmp) as RSAPublicKey
        }

        /**
         * Hash an RSA public key according to the EBICS standard (EBICS 2.5: 4.4.1.2.3).
         */
        fun getEbicsPublicKeyHash(publicKey: RSAPublicKey): ByteArray {
            val keyBytes = ByteArrayOutputStream()
            keyBytes.writeBytes(publicKey.publicExponent.toByteArray())
            keyBytes.write(' '.toInt())
            keyBytes.writeBytes(publicKey.modulus.toByteArray())
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(keyBytes.toByteArray())
        }

        /**
         * Encrypt data according to the EBICS E002 encryption process.
         */
        fun encryptEbicsE002(data: ByteArray, encryptionPublicKey: RSAPublicKey): EncryptionResult {
            val keygen = KeyGenerator.getInstance("AES", bouncyCastleProvider)
            keygen.init(128)
            val transactionKey = keygen.generateKey()
            val symmetricCipher = Cipher.getInstance("AES/CBC/X9.23Padding", bouncyCastleProvider)
            val ivParameterSpec = IvParameterSpec(ByteArray(16))
            symmetricCipher.init(Cipher.ENCRYPT_MODE, transactionKey, ivParameterSpec)
            val encryptedData = symmetricCipher.doFinal(data)
            val asymmetricCipher = Cipher.getInstance("RSA/None/PKCS1Padding", bouncyCastleProvider)
            asymmetricCipher.init(Cipher.ENCRYPT_MODE, encryptionPublicKey)
            val encryptedTransactionKey = asymmetricCipher.doFinal(transactionKey.encoded)
            val pubKeyDigest = getEbicsPublicKeyHash(encryptionPublicKey)
            return EncryptionResult(encryptedTransactionKey, pubKeyDigest, encryptedData)
        }

        fun decryptEbicsE002(enc: EncryptionResult, privateKey: RSAPrivateCrtKey): ByteArray {
            val asymmetricCipher = Cipher.getInstance("RSA/None/PKCS1Padding", bouncyCastleProvider)
            asymmetricCipher.init(Cipher.DECRYPT_MODE, privateKey)
            val transactionKeyBytes = asymmetricCipher.doFinal(enc.encryptedTransactionKey)
            val secretKeySpec = SecretKeySpec(transactionKeyBytes, "AES")
            val symmetricCipher = Cipher.getInstance("AES/CBC/X9.23Padding", bouncyCastleProvider)
            val ivParameterSpec = IvParameterSpec(ByteArray(16))
            symmetricCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            val data = symmetricCipher.doFinal(enc.encryptedData)
            return data
        }

        fun ByteArray.toHexString() : String {
            return this.joinToString("") {
                java.lang.String.format("%02x", it)
            }
        }
    }
}
