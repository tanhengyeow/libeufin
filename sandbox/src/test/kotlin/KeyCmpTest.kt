package tech.libeufin.sandbox

import org.junit.Test
import java.math.BigInteger
import junit.framework.TestCase.assertTrue

class KeyCmpTest {

    /**
     * This test simulates the way keys are compared when they get
     * confirmed via the "keyletter".  The scenario has one format
     * (ByteArray) for keys stored in the database, and another (hexadecimanl
     * string) for keys communicated in the keyletter.
     */
    @Test
    fun bytesCmp() {

        val HEX_STRING = "AA" // as coming from the keyletter
        val ba = byteArrayOf(0xAA.toByte()) // as coming from the database


        assertTrue(BigInteger(1, ba).equals(HEX_STRING.toBigInteger(16)))
    }
}