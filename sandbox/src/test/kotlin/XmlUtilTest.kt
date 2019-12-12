package tech.libeufin.sandbox

import org.apache.xml.security.binding.xmldsig.SignatureType
import org.junit.Test
import org.junit.Assert.*
import org.junit.Ignore
import tech.libeufin.util.schema.ebics_h004.EbicsKeyManagementResponse
import tech.libeufin.util.schema.ebics_h004.EbicsResponse
import tech.libeufin.util.schema.ebics_h004.EbicsTypes
import tech.libeufin.util.schema.ebics_h004.HTDResponseOrderData
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.XMLUtil
import java.security.KeyPairGenerator
import java.util.*
import javax.xml.transform.stream.StreamSource

class XmlUtilTest {

    @Test
    fun deserializeConsecutiveLists() {

        // NOTE: this needs wrapping elements to be parsed into a JAXB object.
        val tmp = XMLUtil.convertStringToJaxb<HTDResponseOrderData>("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <HTDResponseOrderData xmlns="urn:org:ebics:H004">
              <PartnerInfo>
                <AddressInfo>
                  <Name>Foo</Name>
                </AddressInfo>
                <BankInfo>
                  <HostID>host01</HostID>
                </BankInfo>
                  <AccountInfo Currency="EUR" Description="ACCT" ID="acctid1">
                    <AccountNumber international="true">DE21500105174751659277</AccountNumber>
                    <BankCode international="true">INGDDEFFXXX</BankCode>
                    <AccountHolder>Mina Musterfrau</AccountHolder>
                  </AccountInfo>
                  <AccountInfo Currency="EUR" Description="glsdemoacct" ID="glsdemo">
                    <AccountNumber international="true">DE91430609670123123123</AccountNumber>
                    <BankCode international="true">GENODEM1GLS</BankCode>
                    <AccountHolder>Mina Musterfrau</AccountHolder>
                  </AccountInfo>
                  <OrderInfo>
                    <OrderType>C53</OrderType>
                    <TransferType>Download</TransferType>
                    <Description>foo</Description>
                  </OrderInfo>
                  <OrderInfo>
                    <OrderType>C52</OrderType>
                    <TransferType>Download</TransferType>
                    <Description>foo</Description>
                  </OrderInfo>
                  <OrderInfo>
                    <OrderType>CCC</OrderType>
                    <TransferType>Upload</TransferType>
                    <Description>foo</Description>
                  </OrderInfo>
              </PartnerInfo>
              <UserInfo>
                <UserID Status="5">USER1</UserID>
                <Name>Some User</Name>
                <Permission>
                  <OrderTypes>C54 C53 C52 CCC</OrderTypes>
                </Permission>
              </UserInfo>
            </HTDResponseOrderData>""".trimIndent()
        )

        LOGGER.debug(tmp.value.partnerInfo.orderInfoList[0].description)
    }

    @Test
    fun exceptionOnConversion() {
        try {
            XMLUtil.convertStringToJaxb<EbicsKeyManagementResponse>("<malformed xml>")
        } catch (e: javax.xml.bind.UnmarshalException) {
            // just ensuring this is the exception
            LOGGER.info("caught")
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

    @Ignore
    @Test
    fun verifySigningWithConversion() {

        val pair = CryptoUtil.generateRsaKeyPair(2048)

        val response = EbicsResponse().apply {
            version = "H004"
            header = EbicsResponse.Header().apply {
                _static = EbicsResponse.StaticHeaderType()
                mutable = EbicsResponse.MutableHeaderType().apply {
                    this.reportText = "foo"
                    this.returnCode = "bar"
                    this.transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                }
            }
            authSignature = SignatureType()
            body = EbicsResponse.Body().apply {
                returnCode = EbicsResponse.ReturnCode().apply {
                    authenticate = true
                    value = "asdf"
                }
            }
        }

        val signature = signEbicsResponseX002(response, pair.private)
        val signatureJaxb = XMLUtil.convertStringToJaxb<EbicsResponse>(signature)

        assertTrue(

            XMLUtil.verifyEbicsDocument(
                XMLUtil.convertJaxbToDocument(signatureJaxb.value),
                pair.public
            )
        )
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