package tech.libeufin.sandbox

import org.junit.Test
import tech.libeufin.messages.ebics.keyrequest.SignaturePubKeyOrderDataType
import java.math.BigInteger

class InnerIniLoadTest {

    val jaxbKey = {
        val classLoader = ClassLoader.getSystemClassLoader()
        val file = classLoader.getResource(
            "ebics_ini_inner_key.xml"
        )
        xmlProcess.convertStringToJaxb(
            SignaturePubKeyOrderDataType::class.java,
            file.readText()
        )
    }()

    @Test
    fun loadInnerKey() {

        val modulus = jaxbKey.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.modulus
        val exponent = jaxbKey.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.exponent

        loadRsaPublicKey(modulus, exponent)
    }


}