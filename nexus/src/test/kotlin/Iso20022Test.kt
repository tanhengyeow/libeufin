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
        val camt53 = loadXmlResource("iso20022-samples/camt.053.001.02.gesamtbeispiel.xml")
        val r = parseCamtMessage(camt53)
        assertEquals(r.messageId, "27632364572")
        assertEquals(r.creationDateTime, "2016-05-11T19:30:47.0+01:00")
        assertEquals(r.messageType, CashManagementResponseType.Statement)
        for (tx in r.transactions) {
            // Make sure that roundtripping works
            val txStr = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx)
            println(txStr)
            val tx2 = jacksonObjectMapper().readValue(txStr, BankTransaction::class.java)
            val tx2Str = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx2)
            assertEquals(jacksonObjectMapper().readTree(txStr), jacksonObjectMapper().readTree(tx2Str))
        }
    }
}
