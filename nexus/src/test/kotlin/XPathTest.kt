package tech.libeufin.nexus

import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.util.XMLUtil


class XPathTest {

    @Test
    fun pickDataFromSimpleXml() {
        val xml = """
            <root>
              <node>lorem ipsum</node>
            </root>""".trimIndent()
        val doc: Document = tech.libeufin.util.XMLUtil.parseStringIntoDom(xml)
        val nodeSlashes = XMLUtil.evalXpath(doc, "/root/node/text()")
        println(nodeSlashes?.nodeValue) 
        val nodeDoubleSlashes = XMLUtil.evalXpath(doc, "//node/text()")
        println(nodeDoubleSlashes?.nodeValue)
    }
}