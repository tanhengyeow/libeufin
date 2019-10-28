package tech.libeufin.sandbox

import org.junit.Test
import java.security.KeyPairGenerator
import kotlin.test.*



class XmlSigTest {

    @Test
    fun basicSigningTest() {
        val doc = XMLUtil.parseStringIntoDom("""
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
        val otherPair = kpg.genKeyPair()
        XMLUtil.signEbicsDocument(doc, pair.private)
        println(XMLUtil.convertDomToString(doc))
        assertTrue(XMLUtil.verifyEbicsDocument(doc, pair.public))
        assertFalse(XMLUtil.verifyEbicsDocument(doc, otherPair.public))
    }
}