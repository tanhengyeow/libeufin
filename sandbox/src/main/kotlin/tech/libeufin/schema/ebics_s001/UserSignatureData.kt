package tech.libeufin.schema.ebics_s001

import javax.xml.bind.annotation.*

@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "UserSignatureData")
@XmlType(name = "", propOrder = ["orderSignatureList"])
class UserSignatureData {
    @XmlElement(name = "OrderSignature", type = OrderSignatureData::class)
    var orderSignatureList: List<OrderSignatureData>? = null

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["signatureVersion", "signatureValue", "partnerID", "customerID"])
    class OrderSignatureData {
        @XmlElement(name = "SignatureVersion")
        lateinit var signatureVersion: String

        @XmlElement(name = "SignatureValue")
        lateinit var signatureValue: ByteArray

        @XmlElement(name = "PartnerID")
        lateinit var partnerID: String

        @XmlElement(name = "CustomerID")
        lateinit var customerID: String
    }
}