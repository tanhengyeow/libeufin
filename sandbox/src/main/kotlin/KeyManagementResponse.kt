package tech.libeufin.sandbox

import tech.libeufin.messages.ebics.keyresponse.EbicsKeyManagementResponse
import tech.libeufin.messages.ebics.keyresponse.ObjectFactory
import javax.xml.bind.JAXBElement
import javax.xml.namespace.QName

class KeyManagementResponse(
    version: String,
    revision: Int,
    returnCode: String,
    orderId: String,
    reportText: String
) {

    private val value = {
        val of = ObjectFactory()
        val tmp = of.createEbicsKeyManagementResponse()
        tmp.version = version
        tmp.revision = revision
        tmp.header = of.createEbicsKeyManagementResponseHeader()
        tmp.header.mutable = of.createKeyMgmntResponseMutableHeaderType()
        tmp.header.mutable.orderID = orderId
        tmp.header.mutable.reportText = reportText
        tmp.body = of.createEbicsKeyManagementResponseBody()
        tmp.body.returnCode = of.createEbicsKeyManagementResponseBodyReturnCode()
        tmp.body.returnCode.value = returnCode
        tmp.body.returnCode.isAuthenticate = true
        tmp
    }()

    fun get(): JAXBElement<EbicsKeyManagementResponse> {
        return JAXBElement(
            QName(
                "urn:org:ebics:H004",
                "EbicsKeyManagementResponse"
            ),
            EbicsKeyManagementResponse::class.java,
            value
        )
    }
}