package tech.libeufin.util.ebics_h004

import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "body"])
@XmlRootElement(name = "ebicsKeyManagementResponse")
class EbicsKeyManagementResponse {
    @get:XmlElement(required = true)
    lateinit var header: Header

    @get:XmlElement(required = true)
    lateinit var body: Body

    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["_static", "mutable"])
    class Header {
        @get:XmlElement(name = "static", required = true)
        lateinit var _static: EmptyStaticHeader

        @get:XmlElement(required = true)
        lateinit var mutable: MutableHeaderType

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["orderID", "returnCode", "reportText"])
    class MutableHeaderType {
        @get:XmlElement(name = "OrderID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        @get:XmlSchemaType(name = "token")
        var orderID: String? = null

        @get:XmlElement(name = "ReturnCode", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        @get:XmlSchemaType(name = "token")
        lateinit var returnCode: String

        @get:XmlElement(name = "ReportText", required = true)
        @get:XmlJavaTypeAdapter(NormalizedStringAdapter::class)
        @get:XmlSchemaType(name = "normalizedString")
        lateinit var reportText: String
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "")
    class EmptyStaticHeader


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dataTransfer", "returnCode", "timestampBankParameter"])
    class Body {
        @get:XmlElement(name = "DataTransfer")
        var dataTransfer: DataTransfer? = null

        @get:XmlElement(name = "ReturnCode", required = true)
        lateinit var returnCode: ReturnCode

        @get:XmlElement(name = "TimestampBankParameter")
        var timestampBankParameter: EbicsTypes.TimestampBankParameter? = null
    }


    @XmlAccessorType(XmlAccessType.NONE)
    class ReturnCode {
        @get:XmlValue
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var value: String

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dataEncryptionInfo", "orderData"])
    class DataTransfer {
        @get:XmlElement(name = "DataEncryptionInfo")
        var dataEncryptionInfo: EbicsTypes.DataEncryptionInfo? = null

        @get:XmlElement(name = "OrderData", required = true)
        lateinit var orderData: OrderData
    }

    @XmlAccessorType(XmlAccessType.NONE)
    class OrderData {
        @get:XmlValue
        lateinit var value: String
    }
}
