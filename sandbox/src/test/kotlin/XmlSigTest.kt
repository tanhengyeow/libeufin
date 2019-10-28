package tech.libeufin.sandbox

import org.junit.Test
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.security.KeyPairGenerator
import java.util.*
import javax.xml.crypto.NodeSetData
import javax.xml.crypto.URIDereferencer
import javax.xml.crypto.dom.DOMURIReference
import javax.xml.crypto.dsig.*
import javax.xml.crypto.dsig.dom.DOMSignContext
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec
import javax.xml.crypto.dsig.spec.TransformParameterSpec
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory


class XmlSigTest {

    @Test
    fun basicSigningTest() {
        val doc = XML.parseStringIntoDom("""
            <foo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                <AuthSignature />
                <bar authenticate='true'>bla</bar>Hello World
                <spam>
                eggs
                
                ham
                </spam>
            </foo>
        """.trimIndent())
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.genKeyPair()
        XML.signEbicsDocument(doc, pair.private)
        println(XML.convertDomToString(doc))
    }
}