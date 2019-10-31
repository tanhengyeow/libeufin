package tech.libeufin.sandbox

import junit.framework.TestCase.assertEquals
import org.apache.xml.security.binding.xmldsig.SignatureType
import org.junit.Test
import org.w3c.dom.Element
import tech.libeufin.schema.ebics_h004.*
import tech.libeufin.schema.ebics_hev.HEVResponse
import tech.libeufin.schema.ebics_hev.SystemReturnCodeType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EbicsMessagesTest {
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
        val ini = classLoader.getResource("ebics_ini_request_sample.xml")
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
        val ini = classLoader.getResource("ebics_ini_request_sample.xml")!!
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
            body = EbicsKeyManagementResponse.Body().apply {
                returnCode = EbicsKeyManagementResponse.Body.ReturnCode().apply {
                    authenticate = true
                    value = "000000"
                }
            }
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

    @Test
    fun hiaLoad() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val hia = classLoader.getResource("hia_request.xml")!!
        val hiaDom = XMLUtil.parseStringIntoDom(hia.readText())
        val x: Element = hiaDom.getElementsByTagNameNS(
            "urn:org:ebics:H004",
            "OrderDetails"
        )?.item(0) as Element

        x.setAttributeNS(
            "http://www.w3.org/2001/XMLSchema-instance",
            "type",
            "UnsecuredReqOrderDetailsType"
        )

        XMLUtil.convertDomToJaxb<EbicsUnsecuredRequest>(
            EbicsUnsecuredRequest::class.java,
            hiaDom
        )
    }

    @Test
    fun loadInnerKey() {
        val jaxbKey = run {
            val classLoader = ClassLoader.getSystemClassLoader()
            val file = classLoader.getResource(
                "ebics_ini_inner_key.xml"
            )
            assertNotNull(file)
            XMLUtil.convertStringToJaxb<SignaturePubKeyOrderData>(file.readText())
        }

        val modulus = jaxbKey.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.modulus
        val exponent = jaxbKey.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.exponent
        CryptoUtil.loadRsaPublicKeyFromComponents(modulus, exponent)
    }

    @Test
    fun loadIniMessage() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val text = classLoader.getResource("ebics_ini_request_sample.xml")!!.readText()
        XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(text)
    }

    @Test
    fun loadResponse() {
        val response = EbicsResponse().apply {
            version = "H004"
            header = EbicsResponse.Header().apply {
                _static = ResponseStaticHeaderType()
                mutable = EbicsResponseMutableHeaderType().apply {
                    this.reportText = "foo"
                    this.returnCode = "bar"
                    this.transactionPhase = TransactionPhaseType.INITIALISATION
                }
            }
            authSignature = SignatureType()
            body = EbicsResponse.Body().apply {
                returnCode = EbicsResponse.Body.ReturnCode().apply {
                    authenticate = true
                    value = "asdf"
                }
            }
        }
        print(XMLUtil.convertJaxbToString(response))
    }
}