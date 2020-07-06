package tech.libeufin.nexus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.util.XMLUtil
import kotlin.test.assertEquals

fun loadXmlResource(name: String): Document {
    val classLoader = ClassLoader.getSystemClassLoader()
    val res = classLoader.getResource(name)
    if (res == null) {
        throw Exception("resource $name not found");
    }
    return XMLUtil.parseStringIntoDom(res.readText())
}

class Iso20022Test {
    @Test
    fun testTransactionsImport() {
        val camt53 = loadXmlResource("iso20022-samples/camt.053/de.camt.053.001.02.xml")
        val r = parseCamtMessage(camt53)
        assertEquals("msg-001", r.messageId)
        assertEquals("2020-07-03T12:44:40+05:30", r.creationDateTime)
        assertEquals(CashManagementResponseType.Statement, r.messageType)
        assertEquals(1, r.reports.size)

        // First Entry
        assertEquals("100.00", r.reports[0].entries[0].entryAmount.amount)
        assertEquals("EUR", r.reports[0].entries[0].entryAmount.currency)
        assertEquals(CreditDebitIndicator.CRDT, r.reports[0].entries[0].creditDebitIndicator)
        assertEquals(EntryStatus.BOOK, r.reports[0].entries[0].status)
        assertEquals(null, r.reports[0].entries[0].entryRef)
        assertEquals("acctsvcrref-001", r.reports[0].entries[0].accountServicerRef)
        assertEquals("PMNT", r.reports[0].entries[0].bankTransactionCode.domain)
        assertEquals("RCDT", r.reports[0].entries[0].bankTransactionCode.family)
        assertEquals("ESCT", r.reports[0].entries[0].bankTransactionCode.subfamily)
        assertEquals("166", r.reports[0].entries[0].bankTransactionCode.proprietaryCode)
        assertEquals("DK", r.reports[0].entries[0].bankTransactionCode.proprietaryIssuer)
        assertEquals(1, r.reports[0].entries[0].transactionInfos.size)
        assertEquals("EUR", r.reports[0].entries[0].transactionInfos[0].amount.currency)
        assertEquals("100.00", r.reports[0].entries[0].transactionInfos[0].amount.amount)
        assertEquals(CreditDebitIndicator.CRDT, r.reports[0].entries[0].transactionInfos[0].creditDebitIndicator)
        assertEquals("unstructured info one", r.reports[0].entries[0].transactionInfos[0].unstructuredRemittanceInformation)

        // Second Entry
        assertEquals("unstructured info across lines", r.reports[0].entries[1].transactionInfos[0].unstructuredRemittanceInformation)

        // Third Entry

        // Make sure that round-tripping of entry CamtBankAccountEntry JSON works
        for (entry in r.reports.flatMap { it.entries }) {
            val txStr = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(entry)
            println(txStr)
            val tx2 = jacksonObjectMapper().readValue(txStr, CamtBankAccountEntry::class.java)
            val tx2Str = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx2)
            assertEquals(jacksonObjectMapper().readTree(txStr), jacksonObjectMapper().readTree(tx2Str))
        }
    }
}
