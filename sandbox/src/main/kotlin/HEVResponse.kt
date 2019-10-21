package tech.libeufin.sandbox

import tech.libeufin.messages.ebics.hev.HEVResponseDataType
import tech.libeufin.messages.ebics.hev.ObjectFactory
import javax.xml.bind.JAXBElement

/**
 * Convenience wrapper around the main JAXB value.
 */
class HEVResponse(
    returnCode: String,
    reportText: String,
    protocolAndVersion: Array<ProtocolAndVersion>?) {

    constructor(
        returnCode: String,
        reportText: String
    ) : this(returnCode, reportText, null)

    private val value = {
        val of = ObjectFactory()
        val tmp = of.createHEVResponseDataType()
        tmp.systemReturnCode = of.createSystemReturnCodeType()
        tmp.systemReturnCode.reportText = reportText
        tmp.systemReturnCode.returnCode = returnCode

        protocolAndVersion?.forEach {
            val entry = of.createHEVResponseDataTypeVersionNumber()
            entry.protocolVersion = it.protocol
            entry.value = it.version
            tmp.versionNumber.add(entry)
        }
        of.createEbicsHEVResponse(tmp)
    }()
    
    fun get(): JAXBElement<HEVResponseDataType> {
        return value
    }
}
