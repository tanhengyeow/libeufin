package tech.libeufin.nexus

import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Node
import tech.libeufin.util.XMLUtil


class XPathTest {

    @Test
    fun pickDataFromSimpleXml() {
        val xml = """
            <root xmlns="foo">
              <node>lorem ipsum</node>
            </root>""".trimIndent()
        val doc: Document = XMLUtil.parseStringIntoDom(xml)
        val node = XMLUtil.getNodeFromXpath(doc, "/*[local-name()='root']")
        assert(node != null)
        val text = XMLUtil.getStringFromXpath(doc, "//*[local-name()='node']")
        assert(text != null)
        println(text)
    }
}























