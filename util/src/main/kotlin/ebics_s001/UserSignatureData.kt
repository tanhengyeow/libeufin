package tech.libeufin.util.ebics_s001

import javax.xml.bind.annotation.*

@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "UserSignatureData")
@XmlType(name = "", propOrder = ["orderSignatureList"])
class UserSignatureData {
    @XmlElement(name = "OrderSignatureData", type = OrderSignatureData::class)
    var orderSignatureList: List<OrderSignatureData>? = null

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["signatureVersion", "signatureValue", "partnerID", "userID"])
    class OrderSignatureData {
        @XmlElement(name = "SignatureVersion")
        lateinit var signatureVersion: String

        @XmlElement(name = "SignatureValue")
        lateinit var signatureValue: ByteArray

        @XmlElement(name = "PartnerID")
        lateinit var partnerID: String

        @XmlElement(name = "UserID")
        lateinit var userID: String
    }
}