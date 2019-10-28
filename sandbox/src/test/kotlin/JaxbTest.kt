package tech.libeufin.sandbox

import junit.framework.TestCase.assertEquals
import org.junit.Test
import tech.libeufin.messages.ebics.keyrequest.EbicsUnsecuredRequest
import tech.libeufin.messages.ebics.keyrequest.SignaturePubKeyOrderDataType

class JaxbTest {

    val processor = XML()
    val classLoader = ClassLoader.getSystemClassLoader()
    val hevResponseJaxb = HEVResponse(
        "000000",
        "EBICS_OK",
        arrayOf(
            ProtocolAndVersion("H003", "02.40"),
            ProtocolAndVersion("H004", "02.50")
        )
    )


    /**
     * Tests the JAXB instantiation of non-XmlRootElement documents,
     * as notably are the inner XML strings carrying keys in INI/HIA
     * messages.
     */
    @Test
    fun importNonRoot() {

        val ini = classLoader.getResource(
            "ebics_ini_inner_key.xml"
        )

        val jaxb = xmlProcess.convertStringToJaxb(
            SignaturePubKeyOrderDataType::class.java,
            ini.readText()
        )

        assertEquals("A006", jaxb.value.signaturePubKeyInfo.signatureVersion)
    }

    /**
     * Test string -> JAXB
     */
    @Test
    fun stringToJaxb() {

        val ini = classLoader.getResource("ebics_ini_request_sample_patched.xml")

        val jaxb = xmlProcess.convertStringToJaxb(
            EbicsUnsecuredRequest::class.java,
            ini.readText()
        )

        assertEquals(
            "INI",
            jaxb.value.header.static.orderDetails.orderType
        )
    }

    /**
     * Test JAXB -> string
     */
    @Test
    fun jaxbToString() {
        processor.convertJaxbToString(hevResponseJaxb.get())
    }

    /**
     * Test JAXB -> DOM
     */
    @Test
    fun jaxbToDom() {
        processor.convertJaxbToDom(hevResponseJaxb.get())
    }


    /**
     * Test DOM -> JAXB
     */
    @Test
    fun domToJaxb() {
        val ini = classLoader.getResource("ebics_ini_request_sample_patched.xml")
        val iniDom = XML.parseStringIntoDom(ini.readText())
        processor.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            iniDom
        )
    }
}