import org.junit.Test

import tech.libeufin.nexus.normalizeSubject

class SubjectNormalization {

    @Test
    fun testBeforeAndAfter() {
        val mereValue = "1ENVZ6EYGB6Z509KRJ6E59GK1EQXZF8XXNY9SN33C2KDGSHV9KA0"
        assert(mereValue == normalizeSubject(mereValue))
    }
}