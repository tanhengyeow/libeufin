package tech.libeufin.nexus

import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.pickString

class XPathTest {

    @Test
    fun pickDataFromSimpleXml() {
        val xml = """
            <root xmlns="foo">
              <node>lorem ipsum</node>
            </root>""".trimIndent()
        val doc: Document = XMLUtil.parseStringIntoDom(xml)
        println(doc.pickString( "//*[local-name()='node']"))
    }
}























