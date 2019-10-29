package tech.libeufin.sandbox

import org.junit.Test
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData

class InnerIniLoadTest {

    val jaxbKey = {
        val classLoader = ClassLoader.getSystemClassLoader()
        val file = classLoader.getResource(
            "ebics_ini_inner_key.xml"
        )
        XMLUtil.convertStringToJaxb<SignaturePubKeyOrderData>(file.readText())
    }()

    @Test
    fun loadInnerKey() {

        val modulus = jaxbKey.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.modulus
        val exponent = jaxbKey.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.exponent

        loadRsaPublicKey(modulus, exponent)
    }
}