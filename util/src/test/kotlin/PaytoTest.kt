import org.junit.Test
import tech.libeufin.util.EbicsProtocolError
import tech.libeufin.util.parsePayto

class PaytoTest {

    @Test
    fun parsePaytoTest() {
        val noBic = parsePayto("payto://iban/IBAN123?receiver-name=The%20Name")
        assert(noBic.iban == "IBAN123" && noBic.name == "The Name")
        val withBic = parsePayto("payto://iban/BIC123/IBAN123?receiver-name=The%20Name")
        assert(withBic.iban == "IBAN123" && withBic.name == "The Name")
        try {
            parsePayto("http://iban/BIC123/IBAN123?receiver-name=The%20Name")
        } catch (e: EbicsProtocolError) {
            println("wrong scheme was caught")
        }
        try {
            parsePayto(
                "payto://iban/BIC123/IBAN123?receiver-name=The%20Name&address=house"
            )
        } catch (e: EbicsProtocolError) {
            println("more than one parameter isn't allowed")
        }
    }
}