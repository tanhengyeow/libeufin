import org.junit.Test
import tech.libeufin.util.EbicsOrderUtil
import kotlin.test.assertEquals


class EbicsOrderUtilTest {

    @Test
    fun testComputeOrderIDFromNumber() {
        assertEquals("OR01", EbicsOrderUtil.computeOrderIDFromNumber(1))
        assertEquals("OR0A", EbicsOrderUtil.computeOrderIDFromNumber(10))
        assertEquals("OR10", EbicsOrderUtil.computeOrderIDFromNumber(36))
        assertEquals("OR11", EbicsOrderUtil.computeOrderIDFromNumber(37))
    }
}