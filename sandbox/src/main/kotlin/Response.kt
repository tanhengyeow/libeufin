package tech.libeufin.sandbox

import tech.libeufin.messages.ebics.response.EbicsResponse
import tech.libeufin.messages.ebics.response.ObjectFactory
import javax.xml.bind.JAXBElement
import javax.xml.namespace.QName

/**
 * Convenience wrapper around the main JAXB value.
 *
 * @param returnCode return code
 * @param reportText EBICS-compliant error text token, e.g. "[EBICS_OK]" (mandatory brackets!)
 * @param description short description about the response, e.g. "invalid signature".
 */
class Response(
    returnCode: String,
    reportText: String,
    description: String) {

    /**
     * For now, the sandbox returns _only_ technical return codes,
     * namely those that are _not_ related with business orders.  Therefore,
     * the relevant fields to fill are "ebicsResponse/header/mutable/report{Text,Code}".
     *
     * Once business return code will be returned, then the following fields will
     * also have to be filled out: "ebicsResponse/body/report{Text,Code}".
     */
    private val value = {
        val of = ObjectFactory()
        val tmp = of.createEbicsResponse()
        tmp.header = of.createEbicsResponseHeader()
        tmp.header.mutable = of.createResponseMutableHeaderType()
        tmp.header.mutable.reportText = "$reportText $description"
        tmp.header.mutable.returnCode = returnCode
        tmp
    }()

    fun get(): JAXBElement<EbicsResponse> {
        return JAXBElement(
            QName("urn:org:ebics:H004", "ebicsResponse"),
            EbicsResponse::class.java,
            value)
    }
}
