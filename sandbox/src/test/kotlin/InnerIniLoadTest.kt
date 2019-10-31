package tech.libeufin.sandbox

import org.junit.Test
import tech.libeufin.schema.ebics_h004.EbicsUnsecuredRequest
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import kotlin.test.assertNotNull

class InnerIniLoadTest {
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
}