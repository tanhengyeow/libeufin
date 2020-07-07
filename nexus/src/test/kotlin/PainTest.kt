import ch.qos.logback.core.joran.spi.XMLUtil
import org.junit.Test
import tech.libeufin.nexus.iso20022.NexusPaymentInitiationData
import tech.libeufin.nexus.iso20022.createPain001document
import kotlin.test.assertTrue

class PainTest {

    @Test
    fun validationTest() {
        val xml = createPain001document(
            NexusPaymentInitiationData(
                debtorIban = "GB33BUKB20201222222222",
                debtorBic = "BUKBGB33",
                debtorName = "Oliver Smith",
                currency = "EUR",
                amount = "1",
                creditorIban = "GB33BUKB20201222222222",
                creditorName = "Oliver Smith",
                messageId = "message id",
                paymentInformationId = "payment information id",
                preparationTimestamp = 0,
                subject = "subject",
                instructionId = "instruction id",
                endToEndId = "end to end id"
            )
        )
        assertTrue {
            tech.libeufin.util.XMLUtil.validateFromString(xml)
        }
    }
}