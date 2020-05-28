package tech.libeufin.nexus

import org.junit.Test

class SplitString {

    @Test
    fun splitString() {
        val chunks = mutableListOf<String>("first", "second", "third", "fourth")
        val join = chunks.joinToString("|")
        val chunkAgain = join.split("|")
        assert(chunks == chunkAgain)
    }
}