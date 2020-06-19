package tech.libeufin.nexus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.util.XMLUtil


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
        for (tx in r.transactions) {
            val txStr = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx)
            println(txStr)
            val tx2 = jacksonObjectMapper().readValue(txStr, BankTransaction::class.java)
        }
    }
}