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

import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
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
            RsaCrtKeyPair(CryptoUtil.loadRsaPrivateKey(encodedPriv), CryptoUtil.loadRsaPublicKey(encodedPub))
        assertEquals(keyPair.private, otherKeyPair.private)
        assertEquals(keyPair.public, otherKeyPair.public)
    }

    @Test
    fun testEbicsE002() {
        val data = "Hello, World!"
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        CryptoUtil.encryptEbicsE002(data.toByteArray(), keyPair.private)
    }
}