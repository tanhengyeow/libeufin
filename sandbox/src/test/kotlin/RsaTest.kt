package tech.libeufin.sandbox

import org.junit.Test
import java.math.BigInteger
import java.util.*

class RsaTest {

    val publicModulus = BigInteger("65537")
    val publicExponent = BigInteger(512, Random())

    @Test
    fun loadFromModulusAndExponent() {
        val key = loadRsaPublicKey(publicExponent.toByteArray(), publicModulus.toByteArray())
        println(key.toString())
    }

    /**
     * Values generating helper.
     */
    @Test
    fun getBase64Values() {

        println(
            "Modulus: ${Base64.getEncoder().encodeToString(publicModulus.toByteArray())}"
        )
        println(
            "Exponent: ${Base64.getEncoder().encodeToString(publicExponent.toByteArray())}"
        )
    }
}