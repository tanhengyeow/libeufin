import org.junit.Test
import tech.libeufin.sandbox.buildCamtString
import tech.libeufin.util.RawPayment
import tech.libeufin.util.XMLUtil
import kotlin.test.assertTrue

class CamtTest {

    @Test
    fun validationTest() {
        val payment = RawPayment(
            creditorIban = "GB33BUKB20201222222222",
            creditorName = "Oliver Smith",
            creditorBic = "BUKBGB33",
            debitorIban = "GB33BUKB20201333333333",
            debitorName = "John Doe",
            debitorBic = "BUKBGB33",
            amount = "2",
            currency = "EUR",
            subject = "reimbursement",
            date = "1000-02-02",
            uid = "0"
        )
        val xml = buildCamtString(
            53,
            "GB33BUKB20201222222222",
            mutableListOf(payment)
        )
        assertTrue {
            XMLUtil.validateFromString(xml.get(0))
        }
    }
}