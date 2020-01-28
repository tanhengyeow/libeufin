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

import org.junit.Test
import tech.libeufin.util.CryptoUtil
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import javax.crypto.EncryptedPrivateKeyInfo
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoUtilTest {

    @Test
    fun loadFromModulusAndExponent() {
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        val pub2 = CryptoUtil.loadRsaPublicKeyFromComponents(
            keyPair.public.modulus.toByteArray(),
            keyPair.public.publicExponent.toByteArray()
        )
        assertEquals(keyPair.public, pub2)
    }

    @Test
    fun keyGeneration() {
        val gen: KeyPairGenerator = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.genKeyPair()
        println(pair.private)
        assertTrue(pair.private is RSAPrivateCrtKey)
    }

    @Test
    fun testCryptoUtilBasics() {
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        val encodedPriv = keyPair.private.encoded
        val encodedPub = keyPair.public.encoded
        val otherKeyPair =
            CryptoUtil.RsaCrtKeyPair(CryptoUtil.loadRsaPrivateKey(encodedPriv), CryptoUtil.loadRsaPublicKey(encodedPub))
        assertEquals(keyPair.private, otherKeyPair.private)
        assertEquals(keyPair.public, otherKeyPair.public)
    }

    @Test
    fun testEbicsE002() {
        val data = "Hello, World!".toByteArray()
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        val enc = CryptoUtil.encryptEbicsE002(data, keyPair.public)
        val dec = CryptoUtil.decryptEbicsE002(enc, keyPair.private)
        assertTrue(data.contentEquals(dec))
    }

    @Test
    fun testEbicsA006() {
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        val data = "Hello, World".toByteArray(Charsets.UTF_8)
        val sig = CryptoUtil.signEbicsA006(data, keyPair.private)
        assertTrue(CryptoUtil.verifyEbicsA006(sig, data, keyPair.public))
    }

    @Test
    fun testPassphraseEncryption() {

        val keyPair = CryptoUtil.generateRsaKeyPair(1024)

        /* encrypt with original key */
        val data = "Hello, World!".toByteArray(Charsets.UTF_8)
        val secret = CryptoUtil.encryptEbicsE002(data, keyPair.public)

        /* encrypt and decrypt private key */
        val encPriv = CryptoUtil.encryptKey(keyPair.private.encoded, "secret")
        val plainPriv = CryptoUtil.decryptKey(EncryptedPrivateKeyInfo(encPriv),"secret")

        /* decrypt with decrypted private key */
        val revealed = CryptoUtil.decryptEbicsE002(secret, plainPriv)

        assertEquals(
            String(revealed, charset = Charsets.UTF_8),
            String(data, charset = Charsets.UTF_8)
        )
    }
}