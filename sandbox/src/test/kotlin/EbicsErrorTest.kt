import org.apache.xml.security.binding.xmldsig.SignatureType
import org.junit.Test
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes

class EbicsErrorTest {

    @Test
    fun makeEbicsErrorResponse() {
        val pair = CryptoUtil.generateRsaKeyPair(2048)
        val resp = EbicsResponse.createForUploadWithError(
            "[EBICS_ERROR] abc",
            "012345",
            EbicsTypes.TransactionPhaseType.INITIALISATION
        )
        val signedResp = XMLUtil.signEbicsResponse(resp, pair.private)
        XMLUtil.validateFromString(signedResp)
        assert(resp.header.mutable.reportText == "[EBICS_ERROR] abc")
        assert(resp.header.mutable.returnCode == "012345")
        assert(resp.body.returnCode.value == "012345")
    }
}