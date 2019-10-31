package tech.libeufin.sandbox

import org.junit.Test
import org.junit.Assert.*
import java.security.KeyPairGenerator
import javax.xml.transform.stream.StreamSource

class XmlUtilTest {

    val processor = tech.libeufin.sandbox.XMLUtil()

    @Test
    fun hevValidation(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val hev = classLoader.getResourceAsStream("ebics_hev.xml")
        assertTrue(processor.validate(StreamSource(hev)))
    }

    @Test
    fun iniValidation(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResourceAsStream("ebics_ini_request_sample.xml")
        assertTrue(processor.validate(StreamSource(ini)))
    }

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
        kotlin.test.assertTrue(XMLUtil.verifyEbicsDocument(doc, pair.public))
        kotlin.test.assertFalse(XMLUtil.verifyEbicsDocument(doc, otherPair.public))
    }
}