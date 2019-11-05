package tech.libeufin.schema.ebics_h004

import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "body"])
@XmlRootElement(name = "ebicsUnsecuredRequest")
class EbicsUnsecuredRequest {
    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @get:XmlElement(name = "header", required = true)
    lateinit var header: Header

    @get:XmlElement(required = true)
    lateinit var body: Body

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["static", "mutable"])
    class Header {
        @XmlAccessorType(XmlAccessType.NONE)
        @XmlType(name = "")
        class EmptyMutableHeader

        @get:XmlElement(name = "static", required = true)
        lateinit var static: StaticHeaderType

        @get:XmlElement(required = true)
        lateinit var mutable: EmptyMutableHeader

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dataTransfer"])
    class Body {
        @get:XmlElement(name = "DataTransfer", required = true)
        lateinit var dataTransfer: UnsecuredDataTransfer
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["orderData"])
    class UnsecuredDataTransfer {
        @get:XmlElement(name = "OrderData", required = true)
        lateinit var orderData: OrderData
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "")
    class OrderData {
        @get:XmlValue
        lateinit var value: ByteArray
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = ["hostID", "partnerID", "userID", "systemID", "product", "orderDetails", "securityMedium"]
    )
    class StaticHeaderType {
        @get:XmlElement(name = "HostID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var hostID: String

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
}
