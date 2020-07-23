package tech.libeufin.util.ebics_h004

import org.apache.xml.security.binding.xmldsig.SignatureType
import org.apache.xml.security.binding.xmldsig.SignedInfoType
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.XMLUtil
import java.math.BigInteger
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import kotlin.math.min

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "authSignature", "body"])
@XmlRootElement(name = "ebicsResponse")
class EbicsResponse {
    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @get:XmlElement(required = true)
    lateinit var header: Header

    @get:XmlElement(name = "AuthSignature", required = true)
    lateinit var authSignature: SignatureType

    @get:XmlElement(required = true)
    lateinit var body: Body

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
        var segmentNumber: EbicsTypes.SegmentNumber? = null

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
    class OrderData {
        @get:XmlValue
        lateinit var value: String
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
        @get:XmlElement(name = "TransactionID")
        var transactionID: String? = null

        @get:XmlElement(name = "NumSegments")
        @get:XmlSchemaType(name = "positiveInteger")
        var numSegments: BigInteger? = null
    }

    companion object {

        fun createForUploadWithError(
            errorText: String, errorCode: String, phase: EbicsTypes.TransactionPhaseType
        ): EbicsResponse {
            val resp = EbicsResponse().apply {
                this.version = "H004"
                this.revision = 1
                this.header = EbicsResponse.Header().apply {
                    this.authenticate = true
                    this.mutable = EbicsResponse.MutableHeaderType().apply {
                        this.reportText = errorText
                        this.returnCode = errorCode
                        this.transactionPhase = phase
                    }
                    _static = EbicsResponse.StaticHeaderType()
                }
                this.authSignature = SignatureType()
                this.body = EbicsResponse.Body().apply {
                    this.returnCode = EbicsResponse.ReturnCode().apply {
                        this.authenticate = true
                        this.value = errorCode
                    }
                }
            }
            return resp
        }

        fun createForUploadInitializationPhase(transactionID: String, orderID: String): EbicsResponse {
            return EbicsResponse().apply {
                this.version = "H004"
                this.revision = 1
                this.header = Header().apply {
                    this.authenticate = true
                    this._static = StaticHeaderType().apply {
                        this.transactionID = transactionID
                    }
                    this.mutable = MutableHeaderType().apply {
                        this.transactionPhase =
                            EbicsTypes.TransactionPhaseType.INITIALISATION
                        this.orderID = orderID
                        this.reportText = "[EBICS_OK] OK"
                        this.returnCode = "000000"
                    }
                }
                this.authSignature = SignatureType()
                this.body = Body().apply {
                    this.returnCode = ReturnCode().apply {
                        this.authenticate = true
                        this.value = "000000"
                    }
                }
            }
        }

        fun createForDownloadReceiptPhase(transactionID: String, positiveAck: Boolean): EbicsResponse {
            return EbicsResponse().apply {
                this.version = "H004"
                this.revision = 1
                this.header = Header().apply {
                    this.authenticate = true
                    this._static = StaticHeaderType().apply {
                        this.transactionID = transactionID
                    }
                    this.mutable = MutableHeaderType().apply {
                        this.transactionPhase =
                            EbicsTypes.TransactionPhaseType.RECEIPT
                        if (positiveAck) {
                            this.reportText = "[EBICS_DOWNLOAD_POSTPROCESS_DONE] Received positive receipt"
                            this.returnCode = "011000"
                        } else {
                            this.reportText = "[EBICS_DOWNLOAD_POSTPROCESS_SKIPPED] Received negative receipt"
                            this.returnCode = "011001"
                        }
                    }
                }
                this.authSignature = SignatureType()
                this.body = Body().apply {
                    this.returnCode = ReturnCode().apply {
                        this.authenticate = true
                        this.value = "000000"
                    }
                }
            }
        }

        fun createForUploadTransferPhase(
            transactionID: String,
            segmentNumber: Int,
            lastSegment: Boolean,
            orderID: String
        ): EbicsResponse {
            return EbicsResponse().apply {
                this.version = "H004"
                this.revision = 1
                this.header = Header().apply {
                    this.authenticate = true
                    this._static = StaticHeaderType().apply {
                        this.transactionID = transactionID
                    }
                    this.mutable = MutableHeaderType().apply {
                        this.transactionPhase =
                            EbicsTypes.TransactionPhaseType.TRANSFER
                        this.segmentNumber = EbicsTypes.SegmentNumber().apply {
                            this.value = BigInteger.valueOf(segmentNumber.toLong())
                            if (lastSegment) {
                                this.lastSegment = true
                            }
                        }
                        this.orderID = orderID
                        this.reportText = "[EBICS_OK] OK"
                        this.returnCode = "000000"
                    }
                }
                this.authSignature = SignatureType()
                this.body = Body().apply {
                    this.returnCode = ReturnCode().apply {
                        this.authenticate = true
                        this.value = "000000"
                    }
                }
            }
        }

        /**
         * @param requestedSegment requested segment as a 1-based index
         */
        fun createForDownloadTransferPhase(
            transactionID: String,
            numSegments: Int,
            segmentSize: Int,
            encodedData: String,
            requestedSegment: Int
        ): EbicsResponse {
            return EbicsResponse().apply {
                this.version = "H004"
                this.revision = 1
                this.header = Header().apply {
                    this.authenticate = true
                    this._static = StaticHeaderType().apply {
                        this.transactionID = transactionID
                        this.numSegments = BigInteger.valueOf(numSegments.toLong())
                    }
                    this.mutable = MutableHeaderType().apply {
                        this.transactionPhase =
                            EbicsTypes.TransactionPhaseType.TRANSFER
                        this.segmentNumber = EbicsTypes.SegmentNumber().apply {
                            this.lastSegment = numSegments == requestedSegment
                            this.value = BigInteger.valueOf(requestedSegment.toLong())
                        }
                        this.reportText = "[EBICS_OK] OK"
                        this.returnCode = "000000"
                    }
                }
                this.authSignature = SignatureType()
                this.body = Body().apply {
                    this.returnCode = ReturnCode().apply {
                        this.authenticate = true
                        this.value = "000000"
                    }
                    this.dataTransfer = DataTransferResponseType().apply {
                        this.orderData = OrderData().apply {
                            val start = segmentSize * (requestedSegment - 1)
                            this.value = encodedData.substring(start, min(start + segmentSize, encodedData.length))
                        }
                    }
                }
            }
        }

        fun createForDownloadInitializationPhase(
            transactionID: String,
            numSegments: Int,
            segmentSize: Int,
            enc: CryptoUtil.EncryptionResult,
            encodedData: String
        ): EbicsResponse {
            return EbicsResponse().apply {
                this.version = "H004"
                this.revision = 1
                this.header = Header().apply {
                    this.authenticate = true
                    this._static = StaticHeaderType().apply {
                        this.transactionID = transactionID
                        this.numSegments = BigInteger.valueOf(numSegments.toLong())
                    }
                    this.mutable = MutableHeaderType().apply {
                        this.transactionPhase =
                            EbicsTypes.TransactionPhaseType.INITIALISATION
                        this.segmentNumber = EbicsTypes.SegmentNumber().apply {
                            this.lastSegment = (numSegments == 1)
                            this.value = BigInteger.valueOf(1)
                        }
                        this.reportText = "[EBICS_OK] OK"
                        this.returnCode = "000000"
                    }
                }
                this.authSignature = SignatureType()
                this.body = Body().apply {
                    this.returnCode = ReturnCode().apply {
                        this.authenticate = true
                        this.value = "000000"
                    }
                    this.dataTransfer = DataTransferResponseType().apply {
                        this.dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                            this.authenticate = true
                            this.encryptionPubKeyDigest = EbicsTypes.PubKeyDigest()
                                .apply {
                                this.algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                this.version = "E002"
                                this.value = enc.pubKeyDigest
                            }
                            this.transactionKey = enc.encryptedTransactionKey
                        }
                        this.orderData = OrderData().apply {
                            this.value = encodedData.substring(0, min(segmentSize, encodedData.length))
                        }
                    }
                }
            }
        }
    }
}
