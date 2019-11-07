package tech.libeufin.sandbox

import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.ExpectedException
import org.xml.sax.SAXParseException
import tech.libeufin.schema.ebics_h004.EbicsKeyManagementResponse
import java.rmi.UnmarshalException
import java.security.KeyPairGenerator
import java.util.*
import javax.xml.transform.stream.StreamSource

class XmlUtilTest {

    @Test
    fun exceptionOnConversion() {
        try {
            XMLUtil.convertStringToJaxb<EbicsKeyManagementResponse>("<malformed xml>")
        } catch (e: javax.xml.bind.UnmarshalException) {
            // just ensuring this is the exception
            logger.info("caught")
            return
        }

        assertTrue(false)
    }

    @Test
    fun hevValidation(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val hev = classLoader.getResourceAsStream("ebics_hev.xml")
        assertTrue(XMLUtil.validate(StreamSource(hev)))
    }

    @Test
    fun iniValidation(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResourceAsStream("ebics_ini_request_sample.xml")
        assertTrue(XMLUtil.validate(StreamSource(ini)))
    }

    @Test
    fun basicSigningTest() {
        val doc = XMLUtil.parseStringIntoDom("""
            <myMessage xmlns:ebics="urn:org:ebics:H004">
                <ebics:AuthSignature />
                <foo authenticate="true">Hello World</foo>
            </myMessage>
        """.trimIndent())
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.genKeyPair()
        val otherPair = kpg.genKeyPair()
        XMLUtil.signEbicsDocument(doc, pair.private)
        kotlin.test.assertTrue(XMLUtil.verifyEbicsDocument(doc, pair.public))
        kotlin.test.assertFalse(XMLUtil.verifyEbicsDocument(doc, otherPair.public))
    }

    @Test
    fun multiAuthSigningTest() {
        val doc = XMLUtil.parseStringIntoDom("""
            <myMessage xmlns:ebics="urn:org:ebics:H004">
                <ebics:AuthSignature />
                <foo authenticate="true">Hello World</foo>
                <bar authenticate="true">Another one!</bar>
            </myMessage>
        """.trimIndent())
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.genKeyPair()
        XMLUtil.signEbicsDocument(doc, pair.private)
        kotlin.test.assertTrue(XMLUtil.verifyEbicsDocument(doc, pair.public))
    }

    @Test
    fun testRefSignature() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val docText = classLoader.getResourceAsStream("signature1/doc.xml")!!.readAllBytes().toString(Charsets.UTF_8)
        val doc = XMLUtil.parseStringIntoDom(docText)
        val keyText = classLoader.getResourceAsStream("signature1/public_key.txt")!!.readAllBytes()
        val keyBytes = Base64.getDecoder().decode(keyText)
        val key = CryptoUtil.loadRsaPublicKey(keyBytes)
        assertTrue(XMLUtil.verifyEbicsDocument(doc, key))
    }
}