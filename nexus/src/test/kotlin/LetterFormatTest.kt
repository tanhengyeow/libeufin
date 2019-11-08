package tech.libeufin.nexus

import org.junit.Test
import tech.libeufin.sandbox.toHexString

class LetterFormatTest {

    @Test
    fun chunkerTest() {
        val blob = getNonce(1024)
        println(chunkString(blob.toHexString()))
    }
}