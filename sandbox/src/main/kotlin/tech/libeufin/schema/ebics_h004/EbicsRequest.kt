package tech.libeufin.schema.ebics_h004

import org.apache.xml.security.binding.xmldsig.SignatureType
import java.math.BigInteger
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.HexBinaryAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import javax.xml.datatype.XMLGregorianCalendar

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "authSignature", "body"])
@XmlRootElement(name = "ebicsRequest")
class EbicsRequest {
    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @get:XmlElement(name = "header", required = true)
    lateinit var header: Header

    @get:XmlElement(name = "AuthSignature", required = true)
    lateinit var authSignature: SignatureType

    @get:XmlElement(name = "body")
    lateinit var body: Body

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["static", "mutable"])
    class Header {
        @get:XmlElement(name = "static", required = true)
        lateinit var static: StaticHeaderType

        @get:XmlElement(required = true)
        lateinit var mutable: MutableHeader

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = [
            "hostID", "nonce", "timestamp", "partnerID", "userID", "systemID",
            "product", "orderDetails", "bankPubKeyDigests", "securityMedium",
            "numSegments", "transactionID"
        ]
    )
    class StaticHeaderType {
        @get:XmlElement(name = "HostID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var hostID: String

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "Nonce", type = String::class)
        @get:XmlJavaTypeAdapter(HexBinaryAdapter::class)
        @get:XmlSchemaType(name = "hexBinary")
        var nonce: ByteArray? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "Timestamp")
        @get:XmlSchemaType(name = "dateTime")
        var timestamp: XMLGregorianCalendar? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "PartnerID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var partnerID: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "UserID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var userID: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "SystemID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var systemID: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "Product")
        var product: EbicsTypes.Product? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "OrderDetails")
        var orderDetails: OrderDetails? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "BankPubKeyDigests")
        var bankPubKeyDigests: BankPubKeyDigests? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "SecurityMedium")
        var securityMedium: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "NumSegments")
        var numSegments: BigInteger? = null

        /**
         * Present only in the transaction / finalization phase.
         */
        @get:XmlElement(name = "TransactionID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var transactionID: String? = null
    }


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["transactionPhase", "segmentNumber"])
    class MutableHeader {
        @get:XmlElement(name = "TransactionPhase", required = true)
        @get:XmlSchemaType(name = "token")
        lateinit var transactionPhase: EbicsTypes.TransactionPhaseType

        /**
         * Number of the currently transmitted segment, if this message
         * contains order data.
         */
        @get:XmlElement(name = "SegmentNumber")
        var segmentNumber: EbicsTypes.SegmentNumber? = null

    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = ["orderType", "orderID", "orderAttribute", "orderParams"])
    class OrderDetails {
        @get:XmlElement(name = "OrderType", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var orderType: String

        /**
         * Only present if this ebicsRequest is a upload order
         * relating to an already existing order.
         */
        @get:XmlElement(name = "OrderID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var orderID: String? = null

        @get:XmlElement(name = "OrderAttribute", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var orderAttribute: String

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElements(
            XmlElement(
                name = "StandardOrderParams",
                type = StandardOrderParams::class))
        var orderParams: OrderParams? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(propOrder = ["preValidation", "dataTransfer", "transferReceipt"])
    class Body {
        @get:XmlElement(name = "PreValidation")
        var preValidation: PreValidation? = null

        @get:XmlElement(name = "DataTransfer")
        var dataTransfer: DataTransfer? = null

        @get:XmlElement(name = "TransferReceipt")
        var transferReceipt: TransferReceipt? = null
    }

    /**
     * FIXME: not implemented yet
     */
    @XmlAccessorType(XmlAccessType.NONE)
    class PreValidation {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    class SignatureData {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

        @get:XmlValue
        var value: ByteArray? = null
    }


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(propOrder = ["dataEncryptionInfo", "signatureData", "orderData", "hostId"])
    class DataTransfer {

        @get:XmlElement(name = "DataEncryptionInfo")
        var dataEncryptionInfo: EbicsTypes.DataEncryptionInfo? = null

        @get:XmlElement(name = "SignatureData")
        var signatureData: SignatureData? = null

        @get:XmlElement(name = "OrderData")
        var orderData: ByteArray? = null

        @get:XmlElement(name = "HostID")
        var hostId: String? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["receiptCode"])
    class TransferReceipt {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

        @get:XmlElement(name = "ReceiptCode")
        var receiptCode: Int? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    abstract class OrderParams

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dateRange"])
    class StandardOrderParams : OrderParams() {
        @get:XmlElement(name = "DateRange")
        var dateRange: DateRange? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["start", "end"])
    class DateRange {
        @get:XmlElement(name = "Start")
        lateinit var start: XMLGregorianCalendar

        @get:XmlElement(name = "END")
        lateinit var end: XMLGregorianCalendar
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["authentication", "encryption"])
    class BankPubKeyDigests {
        @get:XmlElement(name = "Authentication")
        lateinit var authentication: EbicsTypes.PubKeyDigest

        @get:XmlElement(name = "Encryption")
        lateinit var encryption: EbicsTypes.PubKeyDigest
    }
}
