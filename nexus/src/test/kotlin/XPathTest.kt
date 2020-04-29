package tech.libeufin.nexus

import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.util.XMLUtil

class XPathTest {

    @Test
    fun pickDataFromSimpleXml() {
        val xml = """
            <root xmlns="foo">
              <node>lorem ipsum</node>
            </root>""".trimIndent()
        val doc: Document = XMLUtil.parseStringIntoDom(xml)
        XMLUtil.getNodeFromXpath(doc, "/*[local-name()='root']")
        val text = XMLUtil.getStringFromXpath(doc, "//*[local-name()='node']")
        println(text)
    }
}























