package tech.libeufin.nexus

import org.junit.Test
import tech.libeufin.util.chunkString
import tech.libeufin.util.toHexString
import java.security.SecureRandom

/**
 * @param size in bits
 */
private fun getNonce(size: Int): ByteArray {
    val sr = SecureRandom()
    val ret = ByteArray(size / 8)
    sr.nextBytes(ret)
    return ret
}

class LetterFormatTest {

    @Test
    fun chunkerTest() {
        val blob = getNonce(1024)
        println(chunkString(blob.toHexString()))
    }
}