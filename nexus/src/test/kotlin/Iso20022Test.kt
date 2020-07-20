package tech.libeufin.nexus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.nexus.iso20022.*
import tech.libeufin.util.DestructionError
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.destructXml
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

fun loadXmlResource(name: String): Document {
    val classLoader = ClassLoader.getSystemClassLoader()
    val res = classLoader.getResource(name)
    if (res == null) {
        throw Exception("resource $name not found");
    }
    return XMLUtil.parseStringIntoDom(res.readText())
}

class Iso20022Test {
    @Test(expected = DestructionError::class)
    fun testUniqueChild() {
        val xml = """
            <a>
              <b/>
              <b/>
            </a>
        """.trimIndent()
        // when XML is invalid, DestructionError is thrown.
        val doc = XMLUtil.parseStringIntoDom(xml)
        destructXml(doc) {
            requireRootElement("a") {
                requireOnlyChild {  }
            }
        }

    }
    @Test
    fun testTransactionsImport() {
        val camt53 = loadXmlResource("iso20022-samples/camt.053/de.camt.053.001.02.xml")
        val r = parseCamtMessage(camt53)
        assertEquals("msg-001", r.messageId)
        assertEquals("2020-07-03T12:44:40+05:30", r.creationDateTime)
        assertEquals(CashManagementResponseType.Statement, r.messageType)
        assertEquals(1, r.reports.size)

        // First Entry
        assertTrue(BigDecimal(100).compareTo(r.reports[0].entries[0].amount.value) == 0)
        assertEquals("EUR", r.reports[0].entries[0].amount.currency)
        assertEquals(CreditDebitIndicator.CRDT, r.reports[0].entries[0].creditDebitIndicator)
        assertEquals(EntryStatus.BOOK, r.reports[0].entries[0].status)
        assertEquals(null, r.reports[0].entries[0].entryRef)
        assertEquals("acctsvcrref-001", r.reports[0].entries[0].accountServicerRef)
        assertEquals("PMNT-RCDT-ESCT", r.reports[0].entries[0].bankTransactionCode)
        assertNotNull(r.reports[0].entries[0].details)
        assertEquals("unstructured info one", r.reports[0].entries[0].details?.unstructuredRemittanceInformation)

        // Second Entry
        assertEquals("unstructured info across lines", r.reports[0].entries[1].details?.unstructuredRemittanceInformation)

        // Third Entry

        // Make sure that round-tripping of entry CamtBankAccountEntry JSON works
        for (entry in r.reports.flatMap { it.entries }) {
            val txStr = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(entry)
            val tx2 = jacksonObjectMapper().readValue(txStr, CamtBankAccountEntry::class.java)
            val tx2Str = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx2)
            assertEquals(jacksonObjectMapper().readTree(txStr), jacksonObjectMapper().readTree(tx2Str))
        }

        println(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(r))
    }
}
