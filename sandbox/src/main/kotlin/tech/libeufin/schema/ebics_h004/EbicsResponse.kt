package tech.libeufin.schema.ebics_h004

import org.apache.xml.security.binding.xmldsig.SignatureType
import java.math.BigInteger
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.HexBinaryAdapter
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import javax.xml.namespace.QName

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "authSignature", "body"])
@XmlRootElement(name = "ebicsResponse")
class EbicsResponse {
    @get:XmlElement(required = true)
    lateinit var header: EbicsResponse.Header

    @get:XmlElement(name = "AuthSignature", required = true)
    lateinit var authSignature: SignatureType

    @get:XmlElement(required = true)
    lateinit var body: Body

    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @get:XmlAnyAttribute
    var otherAttributes = HashMap<QName, String>()

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["_static", "mutable"])
    class Header {
        @get:XmlElement(name = "static", required = true)
        lateinit var _static: StaticHeaderType

        @get:XmlElement(required = true)
        lateinit var mutable: MutableHeaderType

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dataTransfer", "returnCode", "timestampBankParameter"])
    class Body {
        @get:XmlElement(name = "DataTransfer")
        var dataTransfer: DataTransferResponseType? = null

        @get:XmlElement(name = "ReturnCode", required = true)
        lateinit var returnCode: ReturnCode

        @get:XmlElement(name = "TimestampBankParameter")
        var timestampBankParameter: EbicsTypes.TimestampBankParameter? = null
    }


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = ["transactionPhase", "segmentNumber", "orderID", "returnCode", "reportText"]
    )
    class MutableHeaderType {
        @get:XmlElement(name = "TransactionPhase", required = true)
        @get:XmlSchemaType(name = "token")
        lateinit var transactionPhase: EbicsTypes.TransactionPhaseType

        @get:XmlElement(name = "SegmentNumber")
        var segmentNumber: SegmentNumber? = null

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
    @XmlType(name = "", propOrder = ["value"])
    class SegmentNumber {
        @XmlValue
        lateinit var value: BigInteger

        @XmlAttribute(name = "lastSegment", required = true)
        var lastSegment: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    class OrderData {
        @get:XmlValue
        lateinit var value: ByteArray
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
    @XmlType(name = "DataTransferResponseType", propOrder = ["dataEncryptionInfo", "orderData"])
    class DataTransferResponseType {
        @get:XmlElement(name = "DataEncryptionInfo")
        var dataEncryptionInfo: EbicsTypes.DataEncryptionInfo? = null

        @get:XmlElement(name = "OrderData", required = true)
        lateinit var orderData: OrderData
    }


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "ResponseStaticHeaderType", propOrder = ["transactionID", "numSegments"])
    class StaticHeaderType {
        @get:XmlElement(name = "TransactionID", type = String::class)
        @get:XmlJavaTypeAdapter(HexBinaryAdapter::class)
        @get:XmlSchemaType(name = "hexBinary")
        var transactionID: ByteArray? = null

        @get:XmlElement(name = "NumSegments")
        @get:XmlSchemaType(name = "positiveInteger")
        var numSegments: BigInteger? = null
    }
}
