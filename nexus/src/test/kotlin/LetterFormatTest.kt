package tech.libeufin.nexus

import org.junit.Test
import tech.libeufin.nexus.tech.libeufin.nexus.chunkString
import tech.libeufin.nexus.tech.libeufin.nexus.getNonce
import tech.libeufin.util.toHexString

class LetterFormatTest {

    @Test
    fun chunkerTest() {
        val blob = getNonce(1024)
        println(chunkString(blob.toHexString()))
    }
}