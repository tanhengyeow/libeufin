package tech.libeufin.sandbox

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import tech.libeufin.messages.ebics.hev.HEVResponseDataType
import tech.libeufin.messages.ebics.keyrequest.EbicsUnsecuredRequest
import tech.libeufin.messages.ebics.keyrequest.SignaturePubKeyOrderDataType
import javax.xml.bind.JAXBElement

class JaxbTest {

    val processor = tech.libeufin.sandbox.XML()
    val classLoader = ClassLoader.getSystemClassLoader()


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
     * Test the JAXB instantiation of a XmlRoot document.
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

    @Test
    fun jaxbToString() {

        val hevResponse = HEVResponse(
            "000000",
            "EBICS_OK",
            arrayOf(
                ProtocolAndVersion("H003", "02.40"),
                ProtocolAndVersion("H004", "02.50")
            )
        )

        processor.getStringFromJaxb(hevResponse.makeHEVResponse())

        // just checking that no exceptions were raised before
        assertTrue(true)
    }
}