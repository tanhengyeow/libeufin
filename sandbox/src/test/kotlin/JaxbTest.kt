package tech.libeufin.sandbox

import junit.framework.TestCase.assertEquals
import org.junit.Test
import tech.libeufin.schema.ebics_h004.EbicsKeyManagementResponse
import tech.libeufin.schema.ebics_h004.EbicsUnsecuredRequest
import tech.libeufin.schema.ebics_h004.HIARequestOrderDataType
import tech.libeufin.schema.ebics_hev.HEVResponse
import tech.libeufin.schema.ebics_hev.SystemReturnCodeType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import kotlin.test.assertTrue

class JaxbTest {
    /**
     * Tests the JAXB instantiation of non-XmlRootElement documents,
     * as notably are the inner XML strings carrying keys in INI/HIA
     * messages.
     */
    @Test
    fun importNonRoot() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_inner_key.xml")
        val jaxb = XMLUtil.convertStringToJaxb<SignaturePubKeyOrderData>(ini.readText())
        assertEquals("A006", jaxb.value.signaturePubKeyInfo.signatureVersion)
    }

    /**
     * Test string -> JAXB
     */
    @Test
    fun stringToJaxb() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_request_sample_patched.xml")
        val jaxb = XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(ini.readText())
        println("jaxb loaded")
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
        val hevResponseJaxb = HEVResponse().apply {
            this.systemReturnCode = SystemReturnCodeType().apply {
                this.reportText = "[EBICS_OK]"
                this.returnCode = "000000"
            }
            this.versionNumber = listOf(HEVResponse.VersionNumber.create("H004", "02.50"))
        }
        XMLUtil.convertJaxbToString(hevResponseJaxb)
    }


    /**
     * Test DOM -> JAXB
     */
    @Test
    fun domToJaxb() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResource("ebics_ini_request_sample_patched.xml")
        val iniDom = XMLUtil.parseStringIntoDom(ini.readText())
        XMLUtil.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            iniDom
        )
    }

    @Test
    fun testKeyMgmgResponse() {
        val responseXml = EbicsKeyManagementResponse().apply {
            header = EbicsKeyManagementResponse.Header().apply {
                mutable = EbicsKeyManagementResponse.Header.KeyManagementResponseMutableHeaderType().apply {
                    reportText = "foo"
                    returnCode = "bar"
                }
                _static = EbicsKeyManagementResponse.Header.EmptyStaticHeader()
            }
            version = "H004"
            body = EbicsKeyManagementResponse.Body()
        }
        val text = XMLUtil.convertJaxbToString(responseXml)
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun testParseHiaRequestOrderData() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val hia = classLoader.getResource("hia_request_order_data.xml")!!.readText()
        XMLUtil.convertStringToJaxb<HIARequestOrderDataType>(hia)
    }
}