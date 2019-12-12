package tech.libeufin.util.ebics_h004

import org.apache.xml.security.binding.xmldsig.SignatureType
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.HexBinaryAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import javax.xml.datatype.XMLGregorianCalendar


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "authSignature", "body"])
@XmlRootElement(name = "ebicsNoPubKeyDigestsRequest")
class EbicsNpkdRequest {
    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @get:XmlElement(name = "header", required = true)
    lateinit var header: Header

    @get:XmlElement(name = "AuthSignature", required = true)
    lateinit var authSignature: SignatureType

    @get:XmlElement(required = true)
    lateinit var body: EmptyBody

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["static", "mutable"])
    class Header {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

        @get:XmlElement(name = "static", required = true)
        lateinit var static: StaticHeaderType

        @get:XmlElement(required = true)
        lateinit var mutable: EmptyMutableHeader
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "StaticHeader",
        propOrder = ["hostID", "nonce", "timestamp", "partnerID", "userID", "systemID", "product", "orderDetails", "securityMedium"]
    )
    class StaticHeaderType {
        @get:XmlElement(name = "HostID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var hostID: String

        @get:XmlElement(name = "Nonce", type = String::class)
        @get:XmlJavaTypeAdapter(HexBinaryAdapter::class)
        @get:XmlSchemaType(name = "hexBinary")
        lateinit var nonce: ByteArray

        @get:XmlElement(name = "Timestamp")
        @get:XmlSchemaType(name = "dateTime")
        var timestamp: XMLGregorianCalendar? = null

        @get:XmlElement(name = "PartnerID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var partnerID: String

        @get:XmlElement(name = "UserID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var userID: String

        @get:XmlElement(name = "SystemID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var systemID: String? = null

        @get:XmlElement(name = "Product")
        val product: EbicsTypes.Product? = null

        @get:XmlElement(name = "OrderDetails", required = true)
        lateinit var orderDetails: OrderDetails

        @get:XmlElement(name = "SecurityMedium", required = true)
        lateinit var securityMedium: String
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["orderType", "orderAttribute"])
    class OrderDetails {
        @get:XmlElement(name = "OrderType", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var orderType: String

        @get:XmlElement(name = "OrderAttribute", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var orderAttribute: String
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "")
    class EmptyMutableHeader

    @XmlAccessorType(XmlAccessType.NONE)
    class EmptyBody

    companion object {
        fun createRequest(
            hostId: String,
            partnerId: String,
            userId: String,
            aNonce: ByteArray,
            date: XMLGregorianCalendar
        ): EbicsNpkdRequest {
            return EbicsNpkdRequest().apply {
                version = "H004"
                revision = 1
                header = Header().apply {
                    authenticate = true
                    mutable = EmptyMutableHeader()
                    static = StaticHeaderType().apply {
                        hostID = hostId
                        partnerID = partnerId
                        userID = userId
                        securityMedium = "0000"
                        orderDetails = OrderDetails()
                        orderDetails.orderType = "HPB"
                        orderDetails.orderAttribute = "DZHNN"
                        nonce = aNonce
                        timestamp = date
                    }
                }
                body = EmptyBody()
                authSignature = SignatureType()
            }
        }
    }
}