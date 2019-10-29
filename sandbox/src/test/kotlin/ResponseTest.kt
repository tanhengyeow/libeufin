package tech.libeufin.sandbox

import org.apache.xml.security.binding.xmldsig.SignatureType
import org.junit.Test
import tech.libeufin.schema.ebics_h004.EbicsResponse

class ResponseTest {

    @Test
    fun loadResponse() {
        val response = EbicsResponse().apply {
            version = "H004"
            header = EbicsResponse.Header().apply {
            }
            authSignature = SignatureType()
            body = EbicsResponse.Body()
        }
        print(XMLUtil.convertJaxbToString(response))
    }

}