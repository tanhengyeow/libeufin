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

import java.lang.Exception
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * RSA key pair.
 */
data class RsaCrtKeyPair(val private: RSAPrivateCrtKey, val public: RSAPublicKey)

/**
 * Helpers for dealing with crypographic operations in EBICS / LibEuFin.
 */
class CryptoUtil {
    companion object {
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
    }
}
