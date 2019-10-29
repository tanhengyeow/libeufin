/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.schema.ebics_h004

import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.apache.xml.security.binding.xmldsig.SignatureType
import org.w3c.dom.Element
import java.math.BigInteger
import java.util.*
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.HexBinaryAdapter
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import javax.xml.datatype.XMLGregorianCalendar
import javax.xml.namespace.QName

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "Product", propOrder = ["value"])
class Product {
    @get:XmlValue
    @get:XmlJavaTypeAdapter(NormalizedStringAdapter::class)
    lateinit var value: String

    @get:XmlAttribute(name = "Language", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var language: String

    @get:XmlAttribute(name = "InstituteID")
    @get:XmlJavaTypeAdapter(NormalizedStringAdapter::class)
    var instituteID: String? = null
}


/**
 * Order details for the static EBICS header.
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "OrderDetailsType", propOrder = ["orderType", "orderAttribute"])
class OrderDetails {
    @get:XmlElement(name = "OrderType", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var orderType: String

    @get:XmlElement(name = "OrderAttribute", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var orderAttribute: String
}


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(
    name = "StaticHeader",
    propOrder = ["hostID", "nonce", "timestamp", "partnerID", "userID", "systemID", "product", "orderDetails", "securityMedium"]
)
class StaticHeader {
    @get:XmlElement(name = "HostID", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var hostID: String

    @get:XmlElement(name = "Nonce", type = String::class)
    @get:XmlJavaTypeAdapter(HexBinaryAdapter::class)
    @get:XmlSchemaType(name = "hexBinary")
    var nonce: ByteArray? = null

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
    val product: Product? = null

    @get:XmlElement(name = "OrderDetails", required = true)
    lateinit var orderDetails: OrderDetails

    @get:XmlElement(name = "SecurityMedium", required = true)
    lateinit var securityMedium: String
}


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "body"])
@XmlRootElement(name = "ebicsUnsecuredRequest")
class EbicsUnsecuredRequest {
    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["static", "mutable"])
    class Header {
        @XmlAccessorType(XmlAccessType.NONE)
        @XmlType(name = "")
        class EmptyMutableHeader

        @get:XmlElement(name = "static", required = true)
        lateinit var static: StaticHeader

        @get:XmlElement(required = true)
        lateinit var mutable: EmptyMutableHeader

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dataTransfer"])
    class Body {
        @get:XmlElement(name = "DataTransfer", required = true)
        lateinit var dataTransfer: DataTransfer

        @XmlAccessorType(XmlAccessType.NONE)
        @XmlType(name = "", propOrder = ["orderData"])
        class DataTransfer {
            @get:XmlElement(name = "OrderData", required = true)
            lateinit var orderData: OrderData

            @XmlAccessorType(XmlAccessType.NONE)
            @XmlType(name = "")
            class OrderData {
                @get:XmlValue
                lateinit var value: ByteArray

                @get:XmlAnyAttribute
                val otherAttributes = HashMap<QName, String>()
            }
        }
    }

    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @get:XmlElement(name = "header", required = true)
    lateinit var header: Header

    @get:XmlElement(required = true)
    lateinit var body: Body
}


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "DataTransferResponseType", propOrder = ["dataEncryptionInfo", "orderData", "any"])
class DataTransferResponse {
    @get:XmlElement(name = "DataEncryptionInfo")
    var dataEncryptionInfo: DataEncryptionInfo? = null

    @get:XmlElement(name = "OrderData", required = true)
    lateinit var orderData: OrderData

    @get:XmlAnyElement(lax = true)
    var any: List<Any>? = null

    @XmlAccessorType(XmlAccessType.NONE)
    class OrderData {
        @get:XmlValue
        lateinit var value: ByteArray

        @get:XmlAnyAttribute
        var otherAttributes = HashMap<QName, String>()
    }

    @XmlAccessorType(XmlAccessType.NONE)
    class DataEncryptionInfo {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

        @get:XmlElement(name = "EncryptionPubKeyDigest", required = true)
        lateinit var encryptionPubKeyDigest: EncryptionPubKeyDigest

        @get:XmlElement(name = "TransactionKey", required = true)
        lateinit var transactionKey: ByteArray

        @get:XmlAnyElement(lax = true)
        var any: List<Any>? = null

        @XmlAccessorType(XmlAccessType.NONE)
        class EncryptionPubKeyDigest {
            @get:XmlAttribute(name = "Version", required = true)
            @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
            lateinit var version: String

            @XmlAttribute(name = "Algorithm", required = true)
            @XmlSchemaType(name = "anyURI")
            lateinit var algorithm: String

            @get:XmlValue
            lateinit var value: ByteArray
        }
    }
}


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(
    name = "ResponseMutableHeaderType",
    propOrder = ["transactionPhase", "segmentNumber", "orderID", "returnCode", "reportText", "any"]
)
class ResponseMutableHeaderType {
    @XmlElement(name = "TransactionPhase", required = true)
    @XmlSchemaType(name = "token")
    lateinit var transactionPhase: TransactionPhaseType

    @XmlElement(name = "SegmentNumber")
    var segmentNumber: SegmentNumber? = null

    @XmlElement(name = "OrderID")
    @XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    @XmlSchemaType(name = "token")
    var orderID: String? = null

    @XmlElement(name = "ReturnCode", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    @XmlSchemaType(name = "token")
    lateinit var returnCode: String

    @XmlElement(name = "ReportText", required = true)
    @XmlJavaTypeAdapter(NormalizedStringAdapter::class)
    @XmlSchemaType(name = "normalizedString")
    lateinit var reportText: String

    @XmlAnyElement(lax = true)
    var any: List<Any>? = null

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["value"])
    class SegmentNumber {
        @XmlValue
        lateinit var value: BigInteger

        @XmlAttribute(name = "lastSegment", required = true)
        var lastSegment: Boolean = false
    }
}

@Suppress("UNUSED_PARAMETER")
enum class TransactionPhaseType(value: String) {
    @XmlEnumValue("Initialisation")
    INITIALISATION("Initialisation"),

    /**
     * Auftragsdatentransfer
     *
     */
    @XmlEnumValue("Transfer")
    TRANSFER("Transfer"),

    /**
     * Quittungstransfer
     *
     */
    @XmlEnumValue("Receipt")
    RECEIPT("Receipt");
}

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "ResponseStaticHeaderType", propOrder = ["transactionID", "numSegments"])
class ResponseStaticHeaderType {
    @XmlElement(name = "TransactionID", type = String::class)
    @XmlJavaTypeAdapter(HexBinaryAdapter::class)
    @XmlSchemaType(name = "hexBinary")
    var transactionID: ByteArray? = null

    @XmlElement(name = "NumSegments")
    @XmlSchemaType(name = "positiveInteger")
    var numSegments: BigInteger? = null
}


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

        init {
            println("creating header")
        }

        @get:XmlElement(name = "static", required = true)
        var _static: ResponseStaticHeaderType? = null

        @get:XmlElement(required = true)
        var mutable: ResponseMutableHeaderType? = null

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dataTransfer", "returnCode", "timestampBankParameter"])
    class Body {
        @XmlElement(name = "DataTransfer")
        var dataTransfer: DataTransferResponse? = null

        @XmlElement(name = "ReturnCode", required = true)
        lateinit var returnCode: ReturnCode

        @XmlElement(name = "TimestampBankParameter")
        var timestampBankParameter: TimestampBankParameter? = null

        @XmlAccessorType(XmlAccessType.NONE)
        class TimestampBankParameter {
            @XmlValue
            lateinit var value: XMLGregorianCalendar

            @XmlAttribute(name = "authenticate", required = true)
            var authenticate: Boolean = false
        }

        @XmlAccessorType(XmlAccessType.NONE)
        class ReturnCode {
            @get:XmlValue
            @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
            lateinit var value: String

            @get:XmlAttribute(name = "authenticate", required = true)
            var authenticate: Boolean = false
        }
    }
}


class PubKeyValueType {
    @XmlElement(name = "RSAKeyValue", namespace = "http://www.w3.org/2000/09/xmldsig#", required = true)
    lateinit var rsaKeyValue: RSAKeyValueType

    @XmlElement(name = "TimeStamp")
    @XmlSchemaType(name = "dateTime")
    var timeStamp: XMLGregorianCalendar? = null

    @XmlAnyElement(lax = true)
    var any: List<Any>? = null
}


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(
    name = "AuthenticationPubKeyInfoType", propOrder = [
        "x509Data",
        "pubKeyValue",
        "authenticationVersion"
    ]
)
class AuthenticationPubKeyInfoType {
    @get:XmlAnyElement()
    var x509Data: Element? = null

    @get:XmlElement(name = "PubKeyValue", required = true)
    lateinit var pubKeyValue: PubKeyValueType

    @get:XmlElement(name = "AuthenticationVersion", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    @get:XmlSchemaType(name = "token")
    lateinit var authenticationVersion: String
}


@XmlAccessorType(XmlAccessType.NONE)
@XmlType(
    name = "AuthenticationPubKeyInfoType", propOrder = [
        "x509Data",
        "pubKeyValue",
        "encryptionVersion"
    ]
)
class EncryptionPubKeyInfoType {
    @get:XmlAnyElement()
    var x509Data: Element? = null

    @get:XmlElement(name = "PubKeyValue", required = true)
    lateinit var pubKeyValue: PubKeyValueType

    @get:XmlElement(name = "EncryptionVersion", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    @get:XmlSchemaType(name = "token")
    lateinit var encryptionVersion: String
}


@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "HIARequestOrderDataType",
    propOrder = ["authenticationPubKeyInfo", "encryptionPubKeyInfo", "partnerID", "userID", "any"]
)
class HIARequestOrderDataType {
    @XmlElement(name = "AuthenticationPubKeyInfo", required = true)
    lateinit var authenticationPubKeyInfo: AuthenticationPubKeyInfoType

    @XmlElement(name = "EncryptionPubKeyInfo", required = true)
    lateinit var encryptionPubKeyInfo: EncryptionPubKeyInfoType

    @XmlElement(name = "PartnerID", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    @XmlSchemaType(name = "token")
    lateinit var partnerID: String

    @XmlElement(name = "UserID", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    @XmlSchemaType(name = "token")
    lateinit var userID: String

    @XmlAnyElement(lax = true)
    var any: List<Any>? = null
}
